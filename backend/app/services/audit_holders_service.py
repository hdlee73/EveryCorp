"""비상장 외감법인의 최대주주(추정): 단독 감사보고서 주석의 '주주현황' 표에서 추출한다.

DART 최대주주 API는 정기보고서 제출 법인만 제공하므로, 그 외 법인은 감사보고서 주석을 읽는 차선책을 쓴다.
회사마다 표 모양이 달라 못 찾을 수 있고, 찾아도 감사 시점 기준 값이라 화면에 '추정'임을 표시한다.
"""
from __future__ import annotations

import logging
import re
from datetime import date, timedelta

from ..cache import cached
from ..http import get_client
from ..parsers import audit_notes as an
from ..parsers import dart_viewer as dv
from . import dart_client
from .reports_service import fetch_viewer_html

log = logging.getLogger(__name__)

ESTIMATE_NOTE = "감사보고서 주석(일반사항)의 주주현황 표에서 추출한 추정값입니다. 감사 대상 회계연도 말 기준이며 DART 최대주주 공시와 다를 수 있습니다."
_PERIOD_RE = re.compile(r"\((\d{4})\.(\d{2})\)")


async def _note_text(rcp_no: str) -> str:
    html = await fetch_viewer_html(rcp_no)
    node = next((n for n in dv.parse_nodes(html) if "주석" in n.get("text", "")), None)
    if not node:
        return ""
    r = await get_client().get(f"{dv.DART_HOST}/report/viewer.do", params={
        "rcpNo": node.get("rcpNo") or rcp_no, "dcmNo": node.get("dcmNo"), "eleId": node.get("eleId"),
        "offset": node.get("offset"), "length": node.get("length"), "dtd": node.get("dtd")})
    return an.html_to_text(r.text) if r.status_code == 200 else ""


@cached(ttl=24 * 3600)
async def get_audit_holders(corp_code: str) -> dict | None:
    end = date.today()
    bgn = end - timedelta(days=365 * 2 + 1)
    try:
        items = await dart_client.list_disclosures(corp_code, bgn.strftime("%Y%m%d"), end.strftime("%Y%m%d"), pblntf_ty="F", max_pages=3)
    except Exception as e:  # noqa: BLE001
        log.warning("audit holders list failed: %s", e)
        return None
    audits = sorted(
        (i for i in items if dv.is_audit_or_review(i.get("report_nm", "")) and "연결" not in i.get("report_nm", "")),
        key=lambda i: i.get("rcept_dt", ""), reverse=True,
    )
    for it in audits[:2]:
        try:
            got = an.extract_holders(await _note_text(it["rcept_no"]))
        except Exception as e:  # noqa: BLE001
            log.warning("audit note parse failed: %s", e)
            continue
        if got:
            m = _PERIOD_RE.search(it.get("report_nm", ""))
            return {
                **got,
                "report_name": it.get("report_nm", "").strip(),
                "rcept_no": it["rcept_no"],
                "period_end": f"{m.group(1)}-{m.group(2)}" if m else "",
                "bsns_year": m.group(1) if m else "",
            }
    return None


def _basis(h: dict) -> dict:
    return {"report_name": h["report_name"], "rcept_no": h["rcept_no"], "bsns_year": h["bsns_year"], "reprt_code": "", "period_end": h["period_end"]}


def as_shareholders(corp_code: str, h: dict) -> dict:
    """최대주주 탭(ShareholdersDto) 형식."""
    rows = [{"name": x["name"], "relation": "", "stock_kind": "", "begin_shares": None, "begin_ratio": None,
             "end_shares": x["shares"], "end_ratio": x["ratio"], "note": ""} for x in h["holders"]]
    total = {"name": "합계", "relation": "", "stock_kind": "", "begin_shares": None, "begin_ratio": None,
             "end_shares": sum(x["shares"] for x in h["holders"]), "end_ratio": h["total_ratio"], "note": ""}
    largest = max(h["holders"], key=lambda x: x["ratio"])
    return {
        "corp_code": corp_code, "available": True, "estimated": True, "source": ESTIMATE_NOTE, "basis": _basis(h),
        "holders": rows, "total": total, "totals": [total], "largest": largest["name"], "changes": [],
        "viewer_url": f"https://dart.fss.or.kr/dsaf001/main.do?rcpNo={h['rcept_no']}",
    }


def as_summary(h: dict) -> dict:
    """기업개황 '최대주주' 요약(summarize_shareholders) 형식."""
    top = max(h["holders"], key=lambda x: x["ratio"])
    n = len(h["holders"]) - 1
    display = (f"최대주주 {top['name']} 외 {n}명 (합산 {h['total_ratio']:.1f}%)" if n
               else f"최대주주 {top['name']} (지분율 {top['ratio']:.1f}%)") + " · 감사보고서 주석 기준 추정"
    return {
        "available": True, "estimated": True, "name": top["name"], "related_count": n, "total_ratio": h["total_ratio"],
        "display": display, "basis": _basis(h),
        "holders": [{"name": x["name"], "relation": None, "shares": x["shares"], "ratio": x["ratio"]} for x in h["holders"]],
    }
