"""탭 5. 채권·단기증권(회사채, CP, 단기사채 등) 미상환 잔액.

원천: Open DART 증권 미상환 잔액 API(정기보고서 기준, 보고서 기준일 시점 잔액).
한국예탁결제원(공공데이터포털 채권기본정보/채권발행정보)의 종목별 일 단위 잔액은 활용신청·명세 확인 후 추가한다.
"""
from __future__ import annotations

import asyncio
import logging

from ..cache import cached
from ..parsers.dart_debt import SECTIONS, summarize_section
from . import dart_client
from .company_service import _basis, get_company, get_periodic_reports
from .dart_client import DartNoData

log = logging.getLogger(__name__)

# DART 공시 기준 금액 단위는 보고서에 따라 다를 수 있어 원문 확인 문구를 함께 내려준다.
SOURCE_NOTE = "출처: Open DART 증권 미상환 잔액(정기보고서 기준, 보고서 기준일 시점). 공공데이터포털(예탁결제원) 종목별 잔액은 연동 준비 중입니다."
UNIT_NOTE = "공시 원문 단위(통상 백만원) 그대로의 숫자입니다. 정확한 단위는 DART 원문에서 확인하세요."


async def _fetch_section(corp_code: str, endpoint: str, year: str, reprt_code: str) -> list[dict] | None:
    try:
        data = await dart_client.get_json(endpoint, corp_code=corp_code, bsns_year=year, reprt_code=reprt_code)
        return data.get("list", [])
    except DartNoData:
        return None


@cached(ttl=6 * 3600)
async def get_debt_securities(corp_code: str) -> dict:
    from ..parsers.dart_overview import bsns_year_candidates, reprt_code_for

    company = await get_company(corp_code)
    fiscal_month = int(company.get("acc_mt") or 12)
    reports = await get_periodic_reports(corp_code)

    for report in reports[:4]:  # 최신 보고서부터, 한 건이라도 잔액 표가 있으면 채택
        code = reprt_code_for(report, fiscal_month)
        for year in bsns_year_candidates(report, fiscal_month):
            results = await asyncio.gather(
                *[_fetch_section(corp_code, ep, year, code) for _, _, ep in SECTIONS], return_exceptions=True
            )
            sections = []
            for (key, title, _), rows in zip(SECTIONS, results):
                if isinstance(rows, Exception):
                    log.warning("debt section %s failed: %s", key, rows)
                    continue
                if rows:
                    sections.append(summarize_section(key, title, rows))
            if sections:
                return {
                    "corp_code": corp_code,
                    "available": True,
                    "unit_note": UNIT_NOTE,
                    "source": SOURCE_NOTE,
                    "basis": _basis(report, year, code),
                    "sections": sections,
                    "missing": [t for k, t, _ in SECTIONS if k not in {s["key"] for s in sections}],
                    "viewer_url": f"https://dart.fss.or.kr/dsaf001/main.do?rcpNo={report.rcept_no}",
                }
    return {
        "corp_code": corp_code,
        "available": False,
        "message": "정기보고서에 공시된 채권·단기증권 미상환 잔액이 없습니다(정기보고서 제출 대상이 아니거나 발행 내역이 없는 경우).",
        "unit_note": UNIT_NOTE,
        "source": SOURCE_NOTE,
        "sections": [],
        "missing": [t for _, t, _ in SECTIONS],
    }
