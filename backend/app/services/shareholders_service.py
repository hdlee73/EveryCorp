"""최대주주 현황(DART 정기보고서 기준)과 변동 이력."""
from __future__ import annotations

import logging

from ..cache import cached
from ..parsers.dart_shareholders import summarize_changes, summarize_holders
from . import dart_client
from .company_service import _basis, get_company, get_periodic_reports
from .dart_client import DartNoData

log = logging.getLogger(__name__)

SOURCE_NOTE = "출처: Open DART 최대주주 현황·변동현황(정기보고서 기준). 정기보고서 제출 대상이 아닌 기업은 제공되지 않습니다."


async def _rows(endpoint: str, corp_code: str, year: str, code: str) -> list[dict] | None:
    try:
        return (await dart_client.get_json(endpoint, corp_code=corp_code, bsns_year=year, reprt_code=code)).get("list", [])
    except DartNoData:
        return None


@cached(ttl=6 * 3600)
async def get_shareholders(corp_code: str) -> dict:
    from ..parsers.dart_overview import bsns_year_candidates, reprt_code_for

    company = await get_company(corp_code)
    fiscal_month = int(company.get("acc_mt") or 12)
    reports = await get_periodic_reports(corp_code)
    for report in reports[:4]:
        code = reprt_code_for(report, fiscal_month)
        for year in bsns_year_candidates(report, fiscal_month):
            rows = await _rows("hyslrSttus.json", corp_code, year, code)
            if not rows:
                continue
            changes = await _rows("hyslrChgSttus.json", corp_code, year, code)
            return {
                "corp_code": corp_code,
                "available": True,
                "source": SOURCE_NOTE,
                "basis": _basis(report, year, code),
                **summarize_holders(rows),
                "changes": summarize_changes(changes or []),
                "viewer_url": f"https://dart.fss.or.kr/dsaf001/main.do?rcpNo={report.rcept_no}",
            }
    from . import audit_holders_service

    est = await audit_holders_service.get_audit_holders(corp_code)
    if est:
        return audit_holders_service.as_shareholders(corp_code, est)
    return {
        "corp_code": corp_code,
        "available": False,
        "message": "정기보고서에 공시된 최대주주 현황이 없습니다(정기보고서 제출 대상이 아닌 비상장 기업 등).",
        "source": SOURCE_NOTE,
        "holders": [],
        "changes": [],
    }
