"""탭 1. 기업개황: 대표이사·최대주주·재무·손익."""
from __future__ import annotations

import asyncio
from datetime import date, timedelta

from ..cache import cached
from ..errors import ApiError
from ..parsers.dart_overview import (
    PeriodicReport,
    build_comparison_tables,
    bsns_year_candidates,
    parse_periodic_reports,
    reprt_code_for,
    summarize_financials,
    summarize_shareholders,
)
from . import dart_client
from .corp_index import corp_index
from .dart_client import DartNoData

CORP_CLS = {"Y": "KOSPI", "K": "KOSDAQ", "N": "KONEX", "E": "OTHER"}


@cached(ttl=24 * 3600)
async def get_company(corp_code: str) -> dict:
    try:
        return await dart_client.get_json("company.json", corp_code=corp_code)
    except DartNoData:
        raise ApiError(404, "CORP_NOT_FOUND", "DART에 등록된 기업개황이 없습니다.")


@cached(ttl=6 * 3600)
async def get_periodic_reports(corp_code: str) -> list[PeriodicReport]:
    """최근 약 27개월간 정기보고서(사업/반기/분기) 목록, 최신 기간 순."""
    end = date.today()
    bgn = end - timedelta(days=820)
    items = await dart_client.list_disclosures(corp_code, bgn.strftime("%Y%m%d"), end.strftime("%Y%m%d"), pblntf_ty="A", max_pages=5)
    return parse_periodic_reports(items)


async def _fetch_report_api(path: str, corp_code: str, report: PeriodicReport, fiscal_month: int, **extra) -> tuple[list[dict], str] | None:
    code = reprt_code_for(report, fiscal_month)
    for year in bsns_year_candidates(report, fiscal_month):
        try:
            data = await dart_client.get_json(path, corp_code=corp_code, bsns_year=year, reprt_code=code, **extra)
            return data.get("list", []), year
        except DartNoData:
            continue
    return None


@cached(ttl=6 * 3600)
async def get_shareholders(corp_code: str, fiscal_month: int) -> dict:
    """최직근 정기보고서(사업/반기/분기 중 최신)의 최대주주현황."""
    reports = await get_periodic_reports(corp_code)
    for report in reports[:4]:  # 최신 보고서에 없으면 직전 보고서로
        got = await _fetch_report_api("hyslrSttus.json", corp_code, report, fiscal_month)
        if got is None:
            continue
        rows, year = got
        summary = summarize_shareholders(rows)
        if summary["available"]:
            summary["basis"] = _basis(report, year, reprt_code_for(report, fiscal_month))
            return summary
    return {
        "available": False,
        "display": "정기보고서(사업·반기·분기) 제출 대상이 아니어서 최대주주 현황을 제공하지 않습니다.",
        "holders": [],
    }


async def _statements(corp_code: str, report: PeriodicReport, fiscal_month: int, order: tuple[str, ...]):
    """보고서 하나의 전체 재무제표. 연결/별도 중 order 순서대로 시도 → (rows, year, fs_div) 또는 None."""
    for fs_div in order:
        got = await _fetch_report_api("fnlttSinglAcntAll.json", corp_code, report, fiscal_month, fs_div=fs_div)
        if got is not None and got[0]:
            return got[0], got[1], fs_div
    return None


@cached(ttl=6 * 3600)
async def get_financials(corp_code: str, fiscal_month: int, industry_code: str | None) -> dict:
    """최직근 정기보고서(사업·반기·분기) + 최근 사업보고서 기준 재무 비교. 연결(CFS) 우선, 없으면 별도(OFS)."""
    reports = await get_periodic_reports(corp_code)
    annuals = [r for r in reports if r.kind == "사업"]
    latest = reports[0] if reports else None
    if latest is None or not annuals:
        return {
            "available": False,
            "message": "DART 재무정보 API는 정기보고서 제출 법인만 제공합니다. 감사보고서 탭에서 재무제표 원문(PDF)을 확인하세요.",
        }
    annual = annuals[0]

    latest_got = await _statements(corp_code, latest, fiscal_month, ("CFS", "OFS"))
    if latest_got is None:
        return {"available": False, "message": "최근 정기보고서의 재무제표를 받지 못했습니다."}
    latest_rows, latest_year, fs_div = latest_got
    if latest.rcept_no == annual.rcept_no:
        annual_rows, annual_year = latest_rows, latest_year
    else:
        other = "OFS" if fs_div == "CFS" else "CFS"
        got = await _statements(corp_code, annual, fiscal_month, (fs_div, other))
        annual_rows, annual_year = (got[0], got[1]) if got else (None, None)

    # 기존 필드(최근 결산 기준 요약)도 유지한다.
    summary = summarize_financials(annual_rows or latest_rows, industry_code)
    summary.update(available=True, fs_div=fs_div, fs_label="연결" if fs_div == "CFS" else "별도",
                   basis=_basis(latest, latest_year, reprt_code_for(latest, fiscal_month)))
    summary["tables"] = build_comparison_tables(
        latest_rows, latest.kind, latest.period_year, latest.period_month,
        annual_rows, int(annual_year) if annual_year else None, fiscal_month, industry_code,
    )
    return summary


def _basis(report: PeriodicReport, bsns_year: str, reprt_code: str) -> dict:
    return {
        "report_name": report.report_nm,
        "rcept_no": report.rcept_no,
        "bsns_year": bsns_year,
        "reprt_code": reprt_code,
        "period_end": f"{report.period_year}-{report.period_month:02d}",
    }


async def get_overview(corp_code: str) -> dict:
    entry = corp_index.get(corp_code)
    company = await get_company(corp_code)
    fiscal_month = int(company.get("acc_mt") or 12)
    industry = company.get("induty_code")
    listed = bool((company.get("stock_code") or "").strip()) and company.get("corp_cls") in ("Y", "K", "N")

    sh, fin = await asyncio.gather(
        get_shareholders(corp_code, fiscal_month),
        get_financials(corp_code, fiscal_month, industry),
        return_exceptions=True,
    )
    if isinstance(sh, Exception):
        sh = {"available": False, "display": "최대주주 정보를 불러오지 못했습니다.", "holders": [], "error": str(sh)}
    if isinstance(fin, Exception):
        fin = {"available": False, "message": "재무정보를 불러오지 못했습니다.", "error": str(fin)}

    return {
        "corp_code": corp_code,
        "corp_name": company.get("corp_name") or (entry.corp_name if entry else ""),
        "corp_name_eng": company.get("corp_name_eng"),
        "stock_code": (company.get("stock_code") or "").strip() or None,
        "listing": "LISTED" if listed else "UNLISTED",
        "market": CORP_CLS.get(company.get("corp_cls", ""), "OTHER"),
        "ceo_name": company.get("ceo_nm"),
        "industry_code": industry,
        "established": company.get("est_dt"),
        "fiscal_month": f"{fiscal_month:02d}",
        "address": company.get("adres"),
        "homepage": company.get("hm_url"),
        "phone": company.get("phn_no"),
        "largest_shareholder": sh,
        "financials": fin,
    }
