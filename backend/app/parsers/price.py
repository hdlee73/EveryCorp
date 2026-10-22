"""시세 응답 파서(순수 함수).

- KIS 국내주식 현재가 시세(inquire-price, TR FHKST01010100) output
- 네이버 금융 실시간 폴링(polling.finance.naver.com) — 신/구 두 형식
- 공공데이터포털 금융위원회_주식시세정보(getStockPriceInfo) items
- 네이버 siseJson(일봉) 폴백
"""
from __future__ import annotations

import re
from datetime import datetime

from ..utils.money import parse_amount, parse_ratio

_KIS_SIGN = {"1": "UP", "2": "UP", "3": "FLAT", "4": "DOWN", "5": "DOWN"}


def _num(v) -> int | None:
    return parse_amount(str(v)) if v is not None else None


def _direction(change: int | None) -> str:
    if not change:
        return "FLAT"
    return "UP" if change > 0 else "DOWN"


def parse_kis_quote(output: dict) -> dict:
    sign = _KIS_SIGN.get(str(output.get("prdy_vrss_sign", "3")), "FLAT")
    change = abs(_num(output.get("prdy_vrss")) or 0)
    rate = abs(parse_ratio(output.get("prdy_ctrt")) or 0.0)
    if sign == "DOWN":
        change, rate = -change, -rate
    return {
        "price": _num(output.get("stck_prpr")),
        "change": change,
        "change_rate": rate,
        "direction": sign,
        "open": _num(output.get("stck_oprc")),
        "high": _num(output.get("stck_hgpr")),
        "low": _num(output.get("stck_lwpr")),
        "volume": _num(output.get("acml_vol")),
        "week52_high": _num(output.get("w52_hgpr")),
        "week52_low": _num(output.get("w52_lwpr")),
    }


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
