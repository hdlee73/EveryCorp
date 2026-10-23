"""탭 5. 채권·단기증권(회사채, CP, 단기사채 등) 미상환 잔액.

원천: Open DART 증권 미상환 잔액 API(정기보고서 기준, 보고서 기준일 시점 잔액).
우선 공공데이터포털 채권기본정보(예탁결제원)의 종목별 잔액을 쓰고, 없거나 실패하면 DART 로 대체한다.
"""
from __future__ import annotations

import asyncio
import logging
from datetime import date, timedelta

import httpx

from ..cache import cached
from ..config import get_settings
from ..http import get_client
from ..parsers.bond_data import as_list, summarize_outstanding
from ..parsers.dart_debt import SECTIONS, summarize_section
from . import dart_client
from .company_service import _basis, get_company, get_periodic_reports
from .dart_client import DartNoData

log = logging.getLogger(__name__)

# DART 공시 기준 금액 단위는 보고서에 따라 다를 수 있어 원문 확인 문구를 함께 내려준다.
SOURCE_NOTE = "출처: Open DART 증권 미상환 잔액(정기보고서 기준, 보고서 기준일 시점)."
KSD_SOURCE_NOTE = "출처: 공공데이터포털 금융위원회 채권기본정보(한국예탁결제원 제공, 종목별 잔액, 공공누리 2유형). 전 영업일 기준이며 비상장·사모 일부는 빠질 수 있습니다."
NONE_SOURCE_NOTE = "확인한 출처: 공공데이터포털 금융위원회 채권기본정보(한국예탁결제원), Open DART 증권 미상환 잔액."
KSD_UNIT_NOTE = "금액 단위: 억원(종목별 채권잔액)"
KSD_URL = "https://apis.data.go.kr/1160100/GetBondIssuInfoService_V2/getBondBasiInfo_V2"
UNIT_NOTE = "공시 원문 단위(통상 백만원) 그대로의 숫자입니다. 정확한 단위는 DART 원문에서 확인하세요."


async def _fetch_section(corp_code: str, endpoint: str, year: str, reprt_code: str) -> list[dict] | None:
    try:
        data = await dart_client.get_json(endpoint, corp_code=corp_code, bsns_year=year, reprt_code=reprt_code)
        return data.get("list", [])
    except DartNoData:
        return None


async def _ksd_page(key: str, crno: str, bas_dt: str | None, page: int) -> tuple[list[dict], int]:
    params = {"serviceKey": key, "resultType": "json", "numOfRows": 500, "pageNo": page, "crno": crno}
    if bas_dt:
        params["basDt"] = bas_dt
    r = await get_client().get(KSD_URL, params=params)
    r.raise_for_status()
    body = r.json().get("response", {}).get("body", {})
    return as_list((body.get("items") or {}).get("item") if isinstance(body.get("items"), dict) else None), int(body.get("totalCount") or 0)


async def _ksd_snapshot(key: str, crno: str, today: date) -> list[dict]:
    """최근 영업일부터 거슬러 올라가며 종목별 잔액을 받는다. 일자 지정이 안 먹으면 기준일 미지정(최신)으로 한 번 더."""
    for back in range(1, 8):
        d = today - timedelta(days=back)
        if d.weekday() >= 5:
            continue
        rows: list[dict] = []
        for page in range(1, 5):
            got, total = await _ksd_page(key, crno, d.strftime("%Y%m%d"), page)
            rows += got
            if len(rows) >= total or not got:
                break
        if rows:
            return rows
    got, _ = await _ksd_page(key, crno, None, 1)
    return got


async def _from_ksd(corp_code: str) -> tuple[dict | None, str]:
    """(결과, 사용하지 못한 이유). DART 로 대체될 때 이유를 출처 문구에 덧붙인다."""
    key = get_settings().data_go_kr_key
    if not key:
        return None, "공공데이터포털 인증키 미설정"
    company = await get_company(corp_code)
    crno = (company.get("jurir_no") or "").replace("-", "").strip()
    if not crno:
        return None, "법인등록번호 확인 불가"
    try:
        rows = await _ksd_snapshot(key, crno, date.today())
    except httpx.HTTPStatusError as e:
        code = e.response.status_code
        log.warning("ksd bond http %s for %s", code, corp_code)
        return None, f"조회 거부(HTTP {code})" + (", 활용신청 승인 전이거나 키 불일치 가능" if code in (401, 403) else "")
    except Exception as e:  # noqa: BLE001  키 미승인·서비스 장애 때는 DART 로 대체
        log.warning("ksd bond failed for %s: %s", corp_code, type(e).__name__)
        return None, "조회 오류"
    sections = summarize_outstanding(rows, date.today())
    if not sections:
        return None, "예탁결제원 등록 미상환 종목 없음"
    return {
        "corp_code": corp_code,
        "available": True,
        "unit_note": KSD_UNIT_NOTE,
        "source": KSD_SOURCE_NOTE,
        "sections": sections,
        "missing": [],
    }, ""


@cached(ttl=6 * 3600)
async def get_debt_securities(corp_code: str) -> dict:
    from ..parsers.dart_overview import bsns_year_candidates, reprt_code_for

    ksd, ksd_reason = await _from_ksd(corp_code)
    if ksd:
        return ksd
    source = SOURCE_NOTE + (f" (예탁결제원 채권기본정보 미사용: {ksd_reason})" if ksd_reason else "")

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
                    "source": source,
                    "basis": _basis(report, year, code),
                    "sections": sections,
                    "missing": [t for k, t, _ in SECTIONS if k not in {s["key"] for s in sections}],
                    "viewer_url": f"https://dart.fss.or.kr/dsaf001/main.do?rcpNo={report.rcept_no}",
                }
    return {
        "corp_code": corp_code,
        "available": False,
        "message": "조회된 채권·단기증권 발행잔액이 없습니다. 예탁결제원 종목정보와 DART 공시를 모두 확인했으나 이 기업의 잔액이 없거나(사모·미발행 등) 조회 대상에서 빠진 경우입니다.",
        "unit_note": "",
        "source": NONE_SOURCE_NOTE,
        "sections": [],
        "missing": [t for _, t, _ in SECTIONS],
    }
