"""키움 기반 시장 데이터(조회 전용): 캔들(분·일·주·월), 호가, 수급(투자자·프로그램·공매도·신용), 랭킹 + 최근 공시 태그.

계좌·잔고·주문은 다루지 않는다(kiwoom_client.ALLOWED_API_IDS 로 막음).
키움 키가 없거나 막히면 일·주·월봉은 공공데이터포털/네이버 일봉으로 대체하고, 나머지는 503 으로 안내한다.
"""
from __future__ import annotations

import asyncio
import logging
import re
from datetime import date, timedelta

from ..cache import cache
from ..errors import ApiError
from ..parsers import kiwoom_market as km
from . import dart_client, kiwoom_client
from .corp_index import corp_index
from .disclosures_service import _dart_item
from .kiwoom_client import kiwoom_enabled
from .price_service import _daily, _require_listed

log = logging.getLogger(__name__)

PERIODS = {
    # 앱 값: (키움 period, 분 단위, 기본 개수, 캐시 초)
    "m1": ("m", 1, 240, 20), "m5": ("m", 5, 240, 30), "m15": ("m", 15, 240, 60), "m30": ("m", 30, 240, 60),
    "m60": ("m", 60, 240, 120),
    "D": ("D", 0, 250, 600), "W": ("W", 0, 156, 1800), "M": ("M", 0, 120, 3600),
}


def _require_kiwoom() -> None:
    if not kiwoom_enabled():
        raise ApiError(503, "KIWOOM_DISABLED", "서버에 키움 REST API 키가 없어 이 정보는 볼 수 없습니다.")


async def get_candles(corp_code: str, period: str = "D", count: int | None = None) -> dict:
    if period not in PERIODS:
        raise ApiError(400, "INVALID_PERIOD", "period 는 m1·m5·m15·m30·m60·D·W·M 중 하나입니다.")
    stock_code, name, _ = await _require_listed(corp_code)
    kp, scope, default_count, ttl = PERIODS[period]
    n = max(30, min(count or default_count, 600))

    async def load() -> dict:
        rows, source, warn = [], "", None
        if kiwoom_enabled():
            try:
                rows, source = await kiwoom_client.candles(stock_code, kp, scope or 1, n), "KIWOOM"
            except Exception as e:  # noqa: BLE001
                warn = f"키움 차트 조회 실패: {getattr(e, 'message', e)}"
                log.warning("Kiwoom candles failed: %s", e)
        if not rows and kp != "m":  # 일봉 기반 폴백(주·월은 일봉을 묶어서 만든다)
            days = {"D": int(n * 1.5) + 10, "W": n * 7 + 14, "M": n * 31 + 31}[kp]
            end = date.today()
            daily, source = await _daily(stock_code, end - timedelta(days=min(days, 3700)), end)
            daily_rows = [{"t": r["date"], "o": r.get("open") or r["close"], "h": r.get("high") or r["close"],
                           "l": r.get("low") or r["close"], "c": r["close"], "v": r.get("volume") or 0}
                          for r in daily if r.get("close")]
            rows = daily_rows if kp == "D" else km.aggregate_candles(daily_rows, kp)
            rows = rows[-n:]
        if not rows and kp == "m":
            raise ApiError(503, "INTRADAY_UNAVAILABLE", warn or "분봉은 키움 REST API 키가 있어야 볼 수 있습니다.")
        return {"stock_code": stock_code, "name": name, "period": period, "source": source,
                "candles": rows, "warning": warn if source != "KIWOOM" else None}

    return await cache.get_or_load(("candles", stock_code, period, n), ttl, load)


async def get_orderbook(corp_code: str) -> dict:
    _require_kiwoom()
    stock_code, name, _ = await _require_listed(corp_code)

    async def load() -> dict:
        ob = await kiwoom_client.orderbook(stock_code)
        return {"stock_code": stock_code, "name": name, "source": "KIWOOM", **ob}

    return await cache.get_or_load(("orderbook", stock_code), 2, load)


async def get_flows(corp_code: str, days: int = 20) -> dict:
    """투자자별 순매수(주)·프로그램 순매수(백만원)·공매도·신용잔고. 한 가지가 실패해도 나머지는 보여 준다."""
    _require_kiwoom()
    stock_code, name, _ = await _require_listed(corp_code)
    days = max(5, min(days, 60))

    async def load() -> dict:
        names = ("investors", "program", "short", "credit")
        calls = (kiwoom_client.investors(stock_code), kiwoom_client.program(stock_code),
                 kiwoom_client.short_selling(stock_code, days=int(days * 1.6) + 7), kiwoom_client.credit(stock_code))
        results = await asyncio.gather(*calls, return_exceptions=True)
        out: dict = {"stock_code": stock_code, "name": name, "source": "KIWOOM", "warnings": []}
        label = {"investors": "투자자별 매매", "program": "프로그램 매매", "short": "공매도", "credit": "신용잔고"}
        for key, res in zip(names, results):
            if isinstance(res, BaseException):
                out[key] = []
                out["warnings"].append(f"{label[key]}: {getattr(res, 'message', None) or res}")
                log.warning("flows %s failed: %s", key, res)
            else:
                out[key] = res[:days]
        out["summary"] = summarize_flows(out["investors"], out["program"])
        if all(not out[k] for k in names) and out["warnings"]:
            raise ApiError(502, "UPSTREAM_ERROR", "수급 정보를 가져오지 못했습니다. " + " / ".join(out["warnings"]))
        return out

    return await cache.get_or_load(("flows", stock_code, days), 600, load)


def summarize_flows(investors: list[dict], program: list[dict]) -> dict:
    """최근 5일·20일 누적 순매수(주체별)와 프로그램 누적(백만원)."""
    def acc(rows: list[dict], key: str, n: int) -> int | None:
        vals = [r.get(key) for r in rows[:n] if r.get(key) is not None]
        return sum(vals) if vals else None

    out = {}
    for n in (5, 20):
        out[f"d{n}"] = {k: acc(investors, k, n) for k in ("individual", "foreign", "institution")}
        out[f"d{n}"]["program_amt"] = acc(program, "net_amt", n)
    return out


# ───────────────────────── 랭킹 + 공시 태그 ─────────────────────────

_TAG_RULES: list[tuple[str, tuple[str, ...]]] = [
    ("유상증자", ("유상증자",)),
    ("무상증자", ("무상증자",)),
    ("CB", ("전환사채",)),
    ("BW", ("신주인수권부사채",)),
    ("EB", ("교환사채",)),
    ("공급계약", ("공급계약",)),
    ("실적", ("영업(잠정)실적", "잠정실적", "손익구조", "매출액또는손익")),
    ("자사주", ("자기주식",)),
    ("최대주주변경", ("최대주주변경", "최대주주 변경")),
    ("합병·분할", ("합병", "분할")),
    ("감자", ("감자",)),
    ("소송", ("소송",)),
    ("지분변동", ("대량보유", "임원ㆍ주요주주", "임원·주요주주", "주요주주특정증권")),
    ("배당", ("배당",)),
    ("투자", ("타법인주식", "신규시설투자", "유형자산양수")),
    ("정기보고서", ("사업보고서", "반기보고서", "분기보고서")),
]


def tag_disclosure(title: str) -> str:
    t = re.sub(r"^(\[[^\]]+\]\s*)+", "", title or "").replace(" ", "")
    for tag, keys in _TAG_RULES:
        if any(k.replace(" ", "") in t for k in keys):
            return tag
    return "기타공시"


async def recent_disclosures_by_stock(days: int = 3) -> dict[str, list[dict]]:
    """최근 [days]일 DART 전체 공시(상장사) → {종목코드: [공시, 최신순]}. 15분 캐시."""
    async def load() -> dict[str, list[dict]]:
        end = date.today()
        items = await dart_client.list_disclosures(
            None, (end - timedelta(days=days - 1)).strftime("%Y%m%d"), end.strftime("%Y%m%d"), max_pages=30,
        )
        out: dict[str, list[dict]] = {}
        for it in items:
            code = str(it.get("stock_code") or "").strip()
            if len(code) != 6 or not it.get("rcept_no"):
                continue
            d = _dart_item(it)
            d["tag"] = tag_disclosure(d["title"])
            out.setdefault(code, []).append(d)
        for v in out.values():
            v.sort(key=lambda x: x["id"], reverse=True)  # 접수번호(날짜+일련번호) 최신순
        return out

    return await cache.get_or_load(("recent_disclosures", days), 900, load)


RANK_KINDS = {"volume": "거래량", "value": "거래대금", "rise": "상승률", "fall": "하락률"}


async def get_rankings(kind: str = "volume", market: str = "all", limit: int = 50, with_tags: bool = True,
                       days: int = 3) -> dict:
    if kind not in RANK_KINDS:
        raise ApiError(400, "INVALID_KIND", "kind 는 volume·value·rise·fall 중 하나입니다.")
    if market not in kiwoom_client.MARKET_TP:
        raise ApiError(400, "INVALID_MARKET", "market 은 all·kospi·kosdaq 중 하나입니다.")
    _require_kiwoom()
    rows = await cache.get_or_load(("ranking", kind, market), 30, lambda: kiwoom_client.ranking(kind, market))
    rows = rows[: max(10, min(limit, 100))]
    warnings: list[str] = []
    recent: dict[str, list[dict]] = {}
    if with_tags:
        try:
            recent = await recent_disclosures_by_stock(days)
        except Exception as e:  # noqa: BLE001
            warnings.append(f"최근 공시를 불러오지 못해 태그 없이 보여 줍니다: {getattr(e, 'message', e)}")
    items = []
    for i, r in enumerate(rows, 1):
        entry = corp_index.by_stock.get(r["stock_code"])
        ds = recent.get(r["stock_code"], [])
        tags: list[str] = []
        for d in ds:
            if d["tag"] not in tags:
                tags.append(d["tag"])
        items.append({**r, "rank": i, "corp_code": entry.corp_code if entry else None,
                      "corp_name": entry.corp_name if entry else r["name"],
                      "tags": tags, "disclosures": ds[:3], "disclosure_count": len(ds)})
    return {"kind": kind, "kind_label": RANK_KINDS[kind], "market": market, "source": "KIWOOM",
            "tag_days": days if with_tags else 0, "items": items, "warnings": warnings}
