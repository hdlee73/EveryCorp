"""REST API v1. 명세: docs/API_SPEC.md"""
from __future__ import annotations

import hmac
from typing import Literal

from fastapi import APIRouter, Depends, Header, Query
from fastapi.responses import Response, StreamingResponse

from ..config import get_settings
from ..errors import ApiError, UpstreamError
from ..http import get_client
from ..parsers import dart_viewer as dv
from ..services import app_meta, company_service, debt_service, disclosures_service, market_service, price_service, reports_service
from ..services.corp_index import corp_index


def require_app_token(x_app_token: str | None = Header(default=None)) -> None:
    expected = get_settings().app_token
    if expected and not hmac.compare_digest(x_app_token or "", expected):
        raise ApiError(401, "UNAUTHORIZED", "앱 토큰이 올바르지 않습니다.")


public = APIRouter(prefix="/api/v1")
router = APIRouter(prefix="/api/v1", dependencies=[Depends(require_app_token)])


def _corp_or_404(corp_code: str):
    if len(corp_code) != 8 or not corp_code.isdigit():
        raise ApiError(400, "INVALID_CORP_CODE", "corp_code는 8자리 숫자입니다.")
    return corp_index.get(corp_code)


# ───────── 공개 ─────────

@public.get("/health")
async def health() -> dict:
    return {"status": "ok", "corp_index_size": len(corp_index.entries)}


@public.get("/app/meta")
async def get_app_meta(current_version: str | None = Query(None, description="앱 versionName")) -> dict:
    return await app_meta.get_meta(current_version)


# ───────── 기업 검색 ─────────

@router.get("/corps/search")
async def search_corps(
    q: str = Query(..., min_length=1, max_length=50),
    limit: int = Query(20, ge=1, le=50),
    listed_only: bool = False,
) -> dict:
    hits = corp_index.search(q, limit=limit, listed_only=listed_only)
    await market_service.ensure_markets(hits)
    return {
        "query": q,
        "count": len(hits),
        "items": [
            {
                "corp_code": e.corp_code,
                "corp_name": e.corp_name,
                "corp_name_eng": e.corp_name_eng or None,
                "stock_code": e.stock_code or None,
                "listing": "LISTED" if e.is_listed else "UNLISTED",
                "market": market_service.market_of(e),
                "badge": market_service.badge_of(e),
            }
            for e in hits
        ],
    }


# ───────── 탭 1 ─────────

@router.get("/corps/{corp_code}/overview")
async def overview(corp_code: str) -> dict:
    _corp_or_404(corp_code)
    return await company_service.get_overview(corp_code)


# ───────── 탭 2 ─────────

@router.get("/corps/{corp_code}/audit-reports")
async def audit_reports(corp_code: str, years: int = Query(3, ge=1, le=5)) -> dict:
    _corp_or_404(corp_code)
    return await reports_service.get_audit_reports(corp_code, years)


@router.get("/reports/pdf")
async def pdf_proxy(rcp_no: str = Query(..., pattern=r"^\d{14}$"), dcm_no: str = Query(..., pattern=r"^\d{5,12}$")):
    """DART PDF 중계(폴백). 직접 다운로드가 막힐 때 앱이 사용한다.

    DART는 뷰어 세션 쿠키·Referer가 없으면 PDF 대신 HTML 안내를 줄 수 있어
    뷰어 페이지를 먼저 열어 쿠키를 받은 뒤 같은 세션으로 PDF를 요청한다.
    """
    client = get_client()
    viewer = dv.viewer_url(rcp_no, dcm_no)
    first = await client.get(viewer)
    req = client.build_request(
        "GET",
        dv.pdf_url(rcp_no, dcm_no),
        headers={"Referer": viewer},
        cookies=first.cookies,
    )
    resp = await client.send(req, stream=True)
    head = b""
    async for chunk in resp.aiter_bytes():
        head = chunk
        break
    if resp.status_code != 200 or not head.startswith(b"%PDF"):
        await resp.aclose()
        raise UpstreamError("DART", "PDF를 받지 못했습니다(문서번호 확인 필요).")

    async def body():
        try:
            yield head
            async for chunk in resp.aiter_bytes():
                yield chunk
        finally:
            await resp.aclose()

    headers = {"Content-Disposition": f'attachment; filename="{rcp_no}_{dcm_no}.pdf"'}
    if cl := resp.headers.get("content-length"):
        headers["Content-Length"] = cl
    return StreamingResponse(body(), media_type="application/pdf", headers=headers)


# ───────── 탭 3 ─────────

@router.get("/corps/{corp_code}/disclosures")
async def disclosures(
    corp_code: str,
    years: int = Query(1, ge=1, le=10),
    source: Literal["all", "dart", "kind"] = "all",
    dedupe: bool = False,
    q: str | None = Query(None, max_length=50),
    page: int = Query(1, ge=1),
    size: int = Query(50, ge=10, le=100),
) -> dict:
    _corp_or_404(corp_code)
    return await disclosures_service.get_disclosures(corp_code, years, source, dedupe, page, size, q)


# ───────── 탭 5 ─────────

@router.get("/corps/{corp_code}/debt-securities")
async def debt_securities(corp_code: str) -> dict:
    _corp_or_404(corp_code)
    return await debt_service.get_debt_securities(corp_code)


# ───────── 탭 4 ─────────

@router.get("/corps/{corp_code}/stock/quote")
async def stock_quote(corp_code: str) -> dict:
    _corp_or_404(corp_code)
    return await price_service.get_quote(corp_code)


@router.get("/corps/{corp_code}/stock/chart")
async def stock_chart(corp_code: str, days: int = Query(365, ge=30, le=1100)) -> dict:
    _corp_or_404(corp_code)
    return await price_service.get_chart(corp_code, days)
