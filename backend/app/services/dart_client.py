"""Open DART API 클라이언트.

참고(개발가이드): https://opendart.fss.or.kr/guide/main.do
- 공시검색 list.json (DS001), 기업개황 company.json (DS001), 고유번호 corpCode.xml (DS001)
- 최대주주현황 hyslrSttus.json (DS002)
- 단일회사 전체 재무제표 fnlttSinglAcntAll.json (DS003)
"""
from __future__ import annotations

import asyncio
from typing import Any

import httpx

from ..config import get_settings
from ..errors import ApiError, UpstreamError
from ..http import get_client

BASE = "https://opendart.fss.or.kr/api"

# DART 응답 status
OK = "000"
NO_DATA = "013"

# 보고서 코드 (reprt_code)
REPRT_ANNUAL = "11011"   # 사업보고서
REPRT_HALF = "11012"     # 반기보고서
REPRT_Q1 = "11013"       # 1분기보고서
REPRT_Q3 = "11014"       # 3분기보고서


_ATTEMPTS = 3
_SEM = asyncio.Semaphore(6)


class DartNoData(Exception):
    """status 013: 조회된 데이터 없음."""


def _key() -> str:
    key = get_settings().dart_api_key
    if not key:
        raise ApiError(503, "DART_KEY_MISSING", "서버에 DART_API_KEY가 설정되지 않았습니다.")
    return key


async def get_json(path: str, **params: Any) -> dict:
    """DART JSON API 호출. 000이 아니면 예외(013은 DartNoData)."""
    params = {k: v for k, v in params.items() if v is not None}
    params["crtfc_key"] = _key()
    r = None
    for attempt in range(_ATTEMPTS):  # 일시적 네트워크 오류(ReadError 등)·HTML 응답은 간격을 두고 다시 시도
        try:
            async with _SEM:  # DART 동시 호출 수 제한(백그라운드 수집과 화면 요청이 한꺼번에 몰리면 연결을 끊는다)
                r = await get_client().get(f"{BASE}/{path}", params=params)
            if r.status_code == 200:
                data = r.json()
                break
            err = f"HTTP {r.status_code}"
        except (httpx.HTTPError, ValueError) as e:
            err = f"{type(e).__name__} {e}"[:120]
        if attempt == _ATTEMPTS - 1:
            raise UpstreamError("DART", err)
        await asyncio.sleep(1 + attempt)
    status = data.get("status")
    if status == OK:
        return data
    if status == NO_DATA:
        raise DartNoData(data.get("message", ""))
    # 020: 요청 제한 초과, 010/011/012: 키 문제, 800: 점검 중
    raise UpstreamError("DART", f"{status} {data.get('message', '')}")


async def get_bytes(path: str, **params: Any) -> bytes:
    params["crtfc_key"] = _key()
    r = await get_client().get(f"{BASE}/{path}", params=params, timeout=120)
    if r.status_code != 200:
        raise UpstreamError("DART", f"HTTP {r.status_code}")
    return r.content


async def list_disclosures(
    corp_code: str,
    bgn_de: str,
    end_de: str,
    pblntf_ty: str | None = None,
    max_pages: int = 50,
) -> list[dict]:
    """공시검색(list.json)을 모든 페이지에 걸쳐 가져온다. corp_code가 있으면 기간 제한이 없다."""
    items: list[dict] = []
    page = 1
    while page <= max_pages:
        try:
            data = await get_json(
                "list.json",
                corp_code=corp_code,
                bgn_de=bgn_de,
                end_de=end_de,
                pblntf_ty=pblntf_ty,
                page_no=page,
                page_count=100,
                last_reprt_at="N",
            )
        except DartNoData:
            break
        items.extend(data.get("list", []))
        if page >= int(data.get("total_page", 1)):
            break
        page += 1
    return items
