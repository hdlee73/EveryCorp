"""탭 4. 주가현황(상장사 전용).

현재가: 키움 REST API(키가 있을 때, 1순위) → 네이버 금융 실시간 폴링(키 불필요) → KRX 전일 종가.
1년 일봉: 키움 ka10081 수정주가 일봉(1순위) → 공공데이터포털 금융위 주식시세정보(T+1) → 네이버 siseJson.
키움이 막히는 경우(고정 IP 불일치 8010, 호출 한도 등)에도 앱이 멈추지 않도록 항상 폴백을 둔다.
현재가는 5초, 일봉은 1시간 캐시한다(앱은 장중 5초 간격 폴링 권장).
"""
from __future__ import annotations

import logging
from datetime import date, timedelta

from ..cache import cache, cached
from ..config import get_settings
from ..errors import ApiError, UpstreamError
from ..http import get_client
from ..parsers import price as pp
from .company_service import CORP_CLS, get_company
from . import kiwoom_client
from .kiwoom_client import kiwoom_enabled
from .krx_client import krx_enabled, previous_close

log = logging.getLogger(__name__)


# ───────────────────────── 네이버 ─────────────────────────

async def _naver_quote(stock_code: str) -> dict:
    r = await get_client().get(
        f"https://polling.finance.naver.com/api/realtime/domestic/stock/{stock_code}",
        headers={"Referer": "https://m.stock.naver.com/"},
    )
    if r.status_code == 200:
        q = pp.parse_naver_realtime(r.json())
        if q and q.get("price"):
            return q
    # 구형식 엔드포인트
    r = await get_client().get("https://polling.finance.naver.com/api/realtime", params={"query": f"SERVICE_ITEM:{stock_code}"})
    q = pp.parse_naver_realtime(r.json()) if r.status_code == 200 else None
    if not q or not q.get("price"):
        raise UpstreamError("NAVER", "시세 응답을 해석하지 못했습니다.")
    return q


async def _require_listed(corp_code: str) -> tuple[str, str, str]:
    company = await get_company(corp_code)
    stock_code = (company.get("stock_code") or "").strip()
    if not stock_code or company.get("corp_cls") not in ("Y", "K", "N"):
        raise ApiError(404, "NOT_LISTED", "비상장 법인은 주가 정보를 제공하지 않습니다.")
    return stock_code, company.get("stock_name") or company.get("corp_name", ""), CORP_CLS[company["corp_cls"]]


async def get_quote(corp_code: str) -> dict:
    stock_code, name, market = await _require_listed(corp_code)

    async def load() -> dict:
        q, source = None, None
        if kiwoom_enabled():
            try:
                q, source = await kiwoom_client.quote(stock_code), "KIWOOM"
            except Exception as e:  # noqa: BLE001
                log.warning("Kiwoom quote failed, fallback to Naver: %s", e)
        if q is None:
            try:
                q, source = await _naver_quote(stock_code), "NAVER"
            except Exception as e:  # noqa: BLE001
                log.warning("Naver quote failed: %s", e)
        if q is None and not krx_enabled():
            raise UpstreamError("시세", "현재가를 가져오지 못했습니다.")
        if q is None:
            q = await previous_close(stock_code, market)
            if q is None:
                raise UpstreamError("KRX", "전일 종가를 찾지 못했습니다.")
            source = "KRX"
            q["delayed"] = True
        q.update(stock_code=stock_code, name=q.get("name") or name, source=source, fetched_at=pp.now_iso())
        return q

    return await cache.get_or_load(("quote", stock_code), 5, load)


# ───────────────────────── 일봉 ─────────────────────────

async def _datagokr_daily(stock_code: str, bgn: date) -> list[dict]:
    key = get_settings().data_go_kr_key
    if not key:
        return []
    r = await get_client().get(
        "https://apis.data.go.kr/1160100/service/GetStockSecuritiesInfoService/getStockPriceInfo",
        params={
            "serviceKey": key,
            "resultType": "json",
            "likeSrtnCd": stock_code,
            "beginBasDt": bgn.strftime("%Y%m%d"),
            "numOfRows": 400,
            "pageNo": 1,
        },
    )
    if r.status_code != 200:
        raise UpstreamError("공공데이터포털", f"HTTP {r.status_code}")
    # likeSrtnCd는 부분일치라 다른 종목이 섞일 수 있어 응답에서 정확히 거른다
    body = (r.json().get("response") or {}).get("body") or {}
    items = (body.get("items") or {}).get("item") or []
    if isinstance(items, dict):
        items = [items]
    mine = [i for i in items if str(i.get("srtnCd")) == stock_code]
    return pp.parse_datagokr_items({"response": {"body": {"items": {"item": mine}}}})


async def _naver_daily(stock_code: str, bgn: date, end: date) -> list[dict]:
    r = await get_client().get(
        "https://api.finance.naver.com/siseJson.naver",
        params={"symbol": stock_code, "requestType": 1, "startTime": bgn.strftime("%Y%m%d"),
                "endTime": end.strftime("%Y%m%d"), "timeframe": "day"},
    )
    if r.status_code != 200:
        raise UpstreamError("NAVER", f"HTTP {r.status_code}")
    return pp.parse_naver_sise_json(r.text)


@cached(ttl=3600)
async def _daily(stock_code: str, bgn: date, end: date) -> tuple[list[dict], str]:
    if kiwoom_enabled():
        try:
            rows = await kiwoom_client.daily(stock_code, bgn)
            if len(rows) >= 20:
                return rows, "KIWOOM"
        except Exception as e:  # noqa: BLE001
            log.warning("Kiwoom daily failed, fallback: %s", e)
    try:
        rows = await _datagokr_daily(stock_code, bgn)
        if len(rows) >= 20:
            return rows, "DATA_GO_KR"
    except Exception as e:  # noqa: BLE001
        log.warning("data.go.kr failed: %s", e)
    return await _naver_daily(stock_code, bgn, end), "NAVER"


async def get_chart(corp_code: str, days: int = 365) -> dict:
    stock_code, name, _ = await _require_listed(corp_code)
    end = date.today()
    bgn = end - timedelta(days=days)
    rows, source = await _daily(stock_code, bgn, end)
    return {
        "stock_code": stock_code,
        "name": name,
        "from": bgn.isoformat(),
        "to": end.isoformat(),
        "source": source,
        "points": [{"date": r["date"], "close": r["close"], "high": r["high"], "low": r["low"], "volume": r["volume"]} for r in rows],
        "range_52w": pp.summarize_range(rows),
        "last_date": rows[-1]["date"] if rows else None,
    }


# ───────────────────────── 당일 분봉 ─────────────────────────

async def _naver_minutes(stock_code: str) -> list[dict]:
    r = await get_client().get(
        "https://fchart.stock.naver.com/sise.nhn",
        params={"symbol": stock_code, "timeframe": "minute", "count": 600, "requestType": 0},
    )
    if r.status_code != 200:
        raise UpstreamError("NAVER", f"HTTP {r.status_code}")
    return pp.parse_naver_fchart_minutes(r.text)


async def get_intraday(corp_code: str) -> dict:
    """당일(없으면 직전 거래일) 1분봉 가격·거래량. 키움 → 네이버 순. 30초 캐시."""
    stock_code, name, _ = await _require_listed(corp_code)

    async def load() -> dict:
        rows, source = [], ""
        if kiwoom_enabled():
            try:
                rows, source = pp.latest_session(await kiwoom_client.minutes(stock_code)), "KIWOOM"
            except Exception as e:  # noqa: BLE001
                log.warning("Kiwoom minutes failed, fallback to Naver: %s", e)
        if not rows:
            try:
                rows, source = pp.latest_session(await _naver_minutes(stock_code)), "NAVER"
            except Exception as e:  # noqa: BLE001
                log.warning("Naver minutes failed: %s", e)
        return {
            "stock_code": stock_code,
            "name": name,
            "source": source,
            "date": rows[0]["date"] if rows else None,
            "points": [{"time": r["time"], "price": r["price"], "volume": r["volume"]} for r in rows],
        }

    return await cache.get_or_load(("intraday", stock_code), 30, load)
