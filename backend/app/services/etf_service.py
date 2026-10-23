"""ETF 조회(키움 ETF TR). 목록은 키움 ka40004 전체시세를 6시간 캐시해 이름·코드로 검색한다.

ETF는 DART 법인이 아니어서 기업 검색(corp_code)과 별도로 6자리 종목코드로 다룬다.
"""
from __future__ import annotations

import asyncio
import logging
import re

from ..cache import cached
from ..errors import ApiError, UpstreamError
from ..parsers import etf as ep
from . import kiwoom_client, price_service
from .kiwoom_client import kiwoom_enabled

log = logging.getLogger(__name__)

_CODE_RE = re.compile(r"^[0-9A-Z]{6}$")
_MAX_PAGES = 12  # 100건 × 12 = 1,200종(상장 ETF 수보다 넉넉)
SOURCE_NOTE = "출처: 키움 REST API(ETF 전체시세·일별추이·종목정보). 추적오차율은 소수점 없는 응답을 100으로 나눠 표시합니다."


def _require_kiwoom() -> None:
    if not kiwoom_enabled():
        raise ApiError(503, "KIWOOM_REQUIRED", "ETF 조회에는 키움 API 설정이 필요합니다.")


@cached(ttl=6 * 3600)
async def list_etfs() -> list[dict]:
    _require_kiwoom()
    items: list[dict] = []
    cont: str | None = None
    for _ in range(_MAX_PAGES):
        rows, cont = await kiwoom_client.etf_list_page(cont)
        items += ep.parse_etf_list(rows)
        if not cont or not rows:
            break
        await asyncio.sleep(0.3)
    if not items:
        raise UpstreamError("키움", "ETF 목록이 비어 있습니다.")
    return list({i["code"]: i for i in items}.values())


async def search(q: str, limit: int = 20) -> dict:
    hits = ep.search_etfs(await list_etfs(), q, limit)
    return {"query": q, "count": len(hits), "items": hits}


def check_code(code: str) -> str:
    code = code.strip().upper()
    if not _CODE_RE.match(code):
        raise ApiError(400, "INVALID_STOCK_CODE", "종목코드는 6자리 영숫자입니다.")
    return code


async def _name_of(code: str, info: dict) -> str:
    if info.get("name"):
        return info["name"]
    try:
        return next((i["name"] for i in await list_etfs() if i["code"] == code), code)
    except Exception:  # noqa: BLE001
        return code


@cached(ttl=6 * 3600)
async def _info(code: str) -> dict:
    return ep.parse_etf_info(await kiwoom_client.etf_info(code))


@cached(ttl=300)
async def _daily(code: str) -> list[dict]:
    return ep.parse_etf_daily(await kiwoom_client.etf_daily(code))


async def get_etf(code: str) -> dict:
    """종목정보(6시간)·일별추이(5분)는 캐시하고 현재가만 매번(5초 캐시) 새로 받아, 앱이 짧은 간격으로 불러도 된다."""
    code = check_code(code)
    _require_kiwoom()
    info, daily = await asyncio.gather(_info(code), _daily(code))
    name = await _name_of(code, info)
    quote = await price_service.quote_by_code(code, name, "KOSPI")
    return {
        "code": code,
        "name": name,
        "index_name": info["index_name"],
        "tax_type": info["tax_type"],
        "par_value": info["par_value"],
        "quote": quote,
        "daily": daily,
        "source": SOURCE_NOTE,
    }


async def get_etf_chart(code: str, days: int = 365) -> dict:
    code = check_code(code)
    name = ""
    try:
        name = next((i["name"] for i in await list_etfs() if i["code"] == code), "")
    except Exception:  # noqa: BLE001
        pass
    return await price_service.chart_by_code(code, name or code, days)
