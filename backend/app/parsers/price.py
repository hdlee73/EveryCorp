"""시세 응답 파서(순수 함수).

- 키움 REST API(ka10001 현재가, ka10081 일봉)
- 네이버 금융 실시간 폴링(polling.finance.naver.com) — 신/구 두 형식
- 공공데이터포털 금융위원회_주식시세정보(getStockPriceInfo) items
- 네이버 siseJson(일봉) 폴백
"""
from __future__ import annotations

import re
from datetime import datetime

from ..utils.money import parse_amount, parse_ratio

def _num(v) -> int | None:
    return parse_amount(str(v)) if v is not None else None


def _direction(change: int | None) -> str:
    if not change:
        return "FLAT"
    return "UP" if change > 0 else "DOWN"


def parse_naver_realtime(data: dict) -> dict | None:
    # 신형식: {"datas":[{"closePrice":"71,000","compareToPreviousClosePrice":"-500","fluctuationsRatio":"-0.70",...}]}
    datas = data.get("datas")
    if datas:
        d = datas[0]
        change = _num(d.get("compareToPreviousClosePrice")) or 0
        rate = parse_ratio(d.get("fluctuationsRatio")) or 0.0
        code = str((d.get("compareToPreviousPrice") or {}).get("code", ""))
        if code in ("4", "5") and change > 0:
            change, rate = -change, -abs(rate)
        return {
            "name": d.get("stockName"),
            "price": _num(d.get("closePrice")),
            "change": change,
            "change_rate": rate,
            "direction": _direction(change),
            "open": _num(d.get("openPrice")),
            "high": _num(d.get("highPrice")),
            "low": _num(d.get("lowPrice")),
            "volume": _num(d.get("accumulatedTradingVolume")),
            "market_status": d.get("marketStatus"),
            "traded_at": d.get("localTradedAt"),
        }
    # 구형식: {"result":{"areas":[{"datas":[{"nv":71000,"cv":500,"cr":0.7,"rf":"5",...}]}]}}
    try:
        d = data["result"]["areas"][0]["datas"][0]
    except (KeyError, IndexError, TypeError):
        return None
    change = abs(int(d.get("cv", 0)))
    rate = abs(float(d.get("cr", 0)))
    if str(d.get("rf")) in ("4", "5"):
        change, rate = -change, -rate
    return {
        "name": d.get("nm"),
        "price": int(d.get("nv")) if d.get("nv") is not None else None,
        "change": change,
        "change_rate": rate,
        "direction": _direction(change),
        "open": d.get("ov"),
        "high": d.get("hv"),
        "low": d.get("lv"),
        "volume": d.get("aq"),
        "market_status": d.get("ms"),
        "traded_at": None,
    }


def parse_datagokr_items(data: dict) -> list[dict]:
    """getStockPriceInfo JSON → [{date, open, high, low, close, volume}] (오름차순)."""
    try:
        items = data["response"]["body"]["items"]["item"]
    except (KeyError, TypeError):
        return []
    if isinstance(items, dict):
        items = [items]
    rows = []
    for it in items:
        d = str(it.get("basDt", ""))
        if len(d) != 8:
            continue
        rows.append({
            "date": f"{d[:4]}-{d[4:6]}-{d[6:]}",
            "open": _num(it.get("mkp")),
            "high": _num(it.get("hipr")),
            "low": _num(it.get("lopr")),
            "close": _num(it.get("clpr")),
            "volume": _num(it.get("trqu")),
        })
    return sorted(rows, key=lambda r: r["date"])


_SISE_ROW = re.compile(r'\[\s*"(\d{8})"\s*,\s*([\d.]+)\s*,\s*([\d.]+)\s*,\s*([\d.]+)\s*,\s*([\d.]+)\s*,\s*([\d.]+)')


def parse_naver_sise_json(text: str) -> list[dict]:
    """api.finance.naver.com/siseJson.naver 응답(따옴표 섞인 JS 배열)."""
    rows = []
    for m in _SISE_ROW.finditer(text):
        d = m.group(1)
        rows.append({
            "date": f"{d[:4]}-{d[4:6]}-{d[6:]}",
            "open": int(float(m.group(2))),
            "high": int(float(m.group(3))),
            "low": int(float(m.group(4))),
            "close": int(float(m.group(5))),
            "volume": int(float(m.group(6))),
        })
    return sorted(rows, key=lambda r: r["date"])


def summarize_range(rows: list[dict]) -> dict:
    """52주 최고/최저(장중 고가·저가 기준, 없으면 종가)."""
    if not rows:
        return {"high": None, "high_date": None, "low": None, "low_date": None}
    hi = max(rows, key=lambda r: r["high"] or r["close"] or 0)
    lo = min(rows, key=lambda r: r["low"] or r["close"] or 10**15)
    return {
        "high": hi["high"] or hi["close"],
        "high_date": hi["date"],
        "low": lo["low"] or lo["close"],
        "low_date": lo["date"],
    }


def now_iso() -> str:
    return datetime.now().astimezone().isoformat(timespec="seconds")


# ───────── 키움 REST API ─────────
# 키움은 모든 값을 문자열로 주고, 가격에도 등락 방향 부호(+/-)를 붙여 보낸다(예: "-70000" = 70,000원, 하락 상태).
_KIWOOM_SIG = {"1": "UP", "2": "UP", "3": "FLAT", "4": "DOWN", "5": "DOWN"}


def kiwoom_abs_int(v) -> int | None:
    s = str(v if v is not None else "").strip().replace(",", "")
    if not s:
        return None
    try:
        return abs(int(float(s)))
    except ValueError:
        return None


def kiwoom_float(v) -> float | None:
    s = str(v if v is not None else "").strip().replace(",", "")
    if not s:
        return None
    try:
        return float(s)
    except ValueError:
        return None


def parse_kiwoom_quote(d: dict) -> dict:
    """ka10001(주식기본정보요청) 응답 → 공통 시세 형식."""
    sig = _KIWOOM_SIG.get(str(d.get("pre_sig", "")).strip())
    change = kiwoom_float(d.get("pred_pre")) or 0.0
    rate = kiwoom_float(d.get("flu_rt")) or 0.0
    change_abs, rate_abs = abs(int(change)), abs(rate)
    if sig is None:  # 대비기호가 없으면 값의 부호로 판단
        sig = "UP" if change > 0 else "DOWN" if change < 0 else "FLAT"
    if sig == "DOWN":
        change_abs, rate_abs = -change_abs, -rate_abs
    elif sig == "FLAT":
        change_abs, rate_abs = 0, 0.0
    return {
        "name": d.get("stk_nm"),
        "price": kiwoom_abs_int(d.get("cur_prc")),
        "change": change_abs,
        "change_rate": round(rate_abs, 2),
        "direction": sig,
        "open": kiwoom_abs_int(d.get("open_pric")),
        "high": kiwoom_abs_int(d.get("high_pric")),
        "low": kiwoom_abs_int(d.get("low_pric")),
        "volume": kiwoom_abs_int(d.get("trde_qty")),
        # 250거래일 최고/최저 ≈ 52주
        "week52_high": kiwoom_abs_int(d.get("250hgst")),
        "week52_low": kiwoom_abs_int(d.get("250lwst")),
        "per": kiwoom_float(d.get("per")),
        "pbr": kiwoom_float(d.get("pbr")),
        "eps": kiwoom_float(d.get("eps")),
        "bps": kiwoom_float(d.get("bps")),
        "roe": kiwoom_float(d.get("roe")),
    }


def parse_kiwoom_chart(rows: list[dict]) -> list[dict]:
    """ka10081(주식일봉차트조회요청) 의 stk_dt_pole_chart_qry → 오름차순 일봉."""
    out = []
    for r in rows:
        d = str(r.get("dt", ""))
        if len(d) != 8:
            continue
        out.append({
            "date": f"{d[:4]}-{d[4:6]}-{d[6:]}",
            "open": kiwoom_abs_int(r.get("open_pric")),
            "high": kiwoom_abs_int(r.get("high_pric")),
            "low": kiwoom_abs_int(r.get("low_pric")),
            "close": kiwoom_abs_int(r.get("cur_prc")),
            "volume": kiwoom_abs_int(r.get("trde_qty")),
        })
    return sorted(out, key=lambda x: x["date"])
