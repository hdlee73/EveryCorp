"""탭 2. 감사보고서·검토보고서 (PDF 직접 다운로드).

수집 경로 두 가지를 합친다.
1) 외부감사관련 공시(pblntf_ty=F): 외감법인의 단독 '감사보고서', '연결감사보고서' 공시 → 뷰어에서 본문 dcm_no
2) 정기공시(pblntf_ty=A): 사업/반기/분기보고서 → 뷰어 '첨부선택'에서 감사보고서·검토보고서 첨부의 rcp_no·dcm_no
같은 기간·종류(감사/검토)·연결여부가 겹치면 단독 공시를 우선한다.
"""
from __future__ import annotations

import asyncio
import logging
import re
from datetime import date, timedelta

from ..cache import cached
from ..errors import UpstreamError
from ..http import get_client
from ..parsers import dart_viewer as dv
from . import dart_client
from .company_service import _fetch_report_api, get_company, get_periodic_reports
from .corp_index import corp_index

log = logging.getLogger(__name__)

_PERIOD_RE = re.compile(r"\((\d{4})\.(\d{2})\)")
_PERIODIC_KIND_RE = re.compile(r"(사업|반기|분기)보고서")
_SEM = asyncio.Semaphore(4)  # DART 웹 동시 요청 제한(과도한 크롤링 방지)


@cached(ttl=7 * 24 * 3600)
async def fetch_viewer_html(rcp_no: str) -> str:
    async with _SEM:
        r = await get_client().get(f"{dv.DART_HOST}/dsaf001/main.do", params={"rcpNo": rcp_no})
    if r.status_code != 200:
        raise UpstreamError("DART 뷰어", f"HTTP {r.status_code}")
    return r.text


def _period(report_nm: str, rcept_dt: str) -> str:
    m = _PERIOD_RE.search(report_nm)
    if m:
        return f"{m.group(1)}-{m.group(2)}"
    return f"{rcept_dt[:4]}-{rcept_dt[4:6]}" if len(rcept_dt) >= 6 else ""


def _period_label(period: str, parent_kind: str | None, kind: str) -> str:
    y, _, m = period.partition("-")
    suffix = {"사업": "결산", "반기": "반기", "분기": "분기"}.get(parent_kind or "", "결산" if kind == "AUDIT" else "중간")
    return f"{y}.{m} {suffix}"


def _safe_filename(*parts: str) -> str:
    name = "_".join(p for p in parts if p)
    return re.sub(r'[\\/:*?"<>|\s]+', "_", name)[:120] + ".pdf"


def _make_item(corp_name: str, title: str, rcp_no: str, dcm_no: str, period: str, rcept_dt: str,
               origin: str, parent_nm: str, parent_kind: str | None) -> dict:
    kind, consolidated = dv.classify(title)
    return {
        "id": f"{rcp_no}-{dcm_no}",
        "title": title,
        "kind": kind,
        "consolidated": consolidated,
        "period": period,
        "period_label": _period_label(period, parent_kind, kind),
        "rcept_dt": rcept_dt,
        "rcp_no": rcp_no,
        "dcm_no": dcm_no,
        "origin": origin,                    # STANDALONE | ATTACHMENT
        "source_report_name": parent_nm,
        "pdf_url": dv.pdf_url(rcp_no, dcm_no),
        "pdf_proxy_path": f"/api/v1/reports/pdf?rcp_no={rcp_no}&dcm_no={dcm_no}",
        "viewer_url": dv.viewer_url(rcp_no, dcm_no),
        "file_name": _safe_filename(corp_name, period, title),
    }


async def _standalone(corp_name: str, it: dict) -> list[dict]:
    title = re.sub(r"^\[[^\]]+\]\s*", "", it["report_nm"]).strip()  # [기재정정] 등 접두어 제거
    title = _PERIOD_RE.sub("", title).strip()
    if not dv.is_audit_or_review(title):
        return []
    html = await fetch_viewer_html(it["rcept_no"])
    dcm = dv.parse_main_dcm_no(html)
    if not dcm:
        return []
    return [_make_item(corp_name, title, it["rcept_no"], dcm, _period(it["report_nm"], it["rcept_dt"]),
                       it["rcept_dt"], "STANDALONE", it["report_nm"].strip(), None)]


async def _attachments(corp_name: str, it: dict) -> list[dict]:
    km = _PERIODIC_KIND_RE.search(it["report_nm"])
    if not km:
        return []
    html = await fetch_viewer_html(it["rcept_no"])
    period = _period(it["report_nm"], it["rcept_dt"])
    return [
        _make_item(corp_name, d.title, d.rcp_no, d.dcm_no, period, it["rcept_dt"], "ATTACHMENT",
                   it["report_nm"].strip(), km.group(1))
        for d in dv.parse_attachments(html)
        if dv.is_audit_or_review(d.title)
    ]


def merge_reports(items: list[dict]) -> list[dict]:
    """기간·종류·연결여부가 같으면 하나만(단독 공시 > 최신 접수) 남기고 최신순 정렬."""
    best: dict[tuple, dict] = {}
    for it in items:
        key = (it["period"], it["kind"], it["consolidated"])
        cur = best.get(key)
        rank = (1 if it["origin"] == "STANDALONE" else 0, it["rcept_dt"], it["rcp_no"])
        if cur is None or rank > (1 if cur["origin"] == "STANDALONE" else 0, cur["rcept_dt"], cur["rcp_no"]):
            best[key] = it
    return sorted(best.values(), key=lambda x: (x["period"], x["rcept_dt"], not x["consolidated"]), reverse=True)


def summarize_opinions(rows: list[dict]) -> list[dict]:
    """회계감사인의 명칭 및 감사의견(accnutAdtorNmNdAdtOpinion) → 기수별 감사의견·감사인만."""
    out = []
    seen: dict[str, int] = {}
    for r in rows:
        term = (r.get("bsns_year") or "").strip()
        auditor = (r.get("adtor") or "").strip()
        opinion = (r.get("adt_opinion") or "").strip()
        if not (term and (auditor or opinion)) or (auditor in ("", "-") and opinion in ("", "-")):
            continue
        seen[term] = seen.get(term, 0) + 1
        out.append({"term": term, "auditor": "" if auditor == "-" else auditor, "opinion": "" if opinion == "-" else opinion, "scope": ""})
    # 같은 기수가 두 줄이면 연결·별도로 본다. DART 응답에는 둘을 가르는 필드가 없어서,
    # 감사인·의견이 같으면 한 줄로 합쳐 "연결·별도 동일"로 보여 주고, 다르면 표 순서(연결 먼저)대로 구분한다.
    merged: list[dict] = []
    idx: dict[str, int] = {}
    for o in out:
        if seen[o["term"]] != 2:
            merged.append(o)
            continue
        n = idx.get(o["term"], 0)
        idx[o["term"]] = n + 1
        pair = [x for x in out if x["term"] == o["term"]]
        if (pair[0]["auditor"], pair[0]["opinion"]) == (pair[1]["auditor"], pair[1]["opinion"]):
            if n == 0:
                merged.append({**o, "scope": "연결·별도 동일"})
        else:
            merged.append({**o, "scope": ("연결", "별도")[n]})
    out = merged
    return out


async def get_period_opinions(corp_code: str, since: date) -> dict[str, dict]:
    """정기보고서(사업·반기·분기)별 당기 감사의견·감사인 → {"YYYY-MM": {auditor, opinion}}.

    DART 응답에는 연결·별도를 가르는 필드가 없어서 같은 기간의 연결·별도 카드가 같은 값을 쓴다.
    실패해도 보고서 목록은 내려주므로 빈 dict로 둔다.
    """
    try:
        company = await get_company(corp_code)
        fiscal_month = int(company.get("acc_mt") or 12)
        reports = [r for r in await get_periodic_reports(corp_code) if r.rcept_dt >= since.strftime("%Y%m%d")][:12]
    except Exception as e:  # noqa: BLE001
        log.warning("period opinions failed: %s", e)
        return {}

    async def one(report) -> tuple[str, dict] | None:
        got = await _fetch_report_api("accnutAdtorNmNdAdtOpinion.json", corp_code, report, fiscal_month)
        items = summarize_opinions(got[0]) if got else []
        if not items:
            return None
        return f"{report.period_year}-{report.period_month:02d}", {"auditor": items[0]["auditor"], "opinion": items[0]["opinion"]}

    out: dict[str, dict] = {}
    for r in await asyncio.gather(*(one(rep) for rep in reports), return_exceptions=True):
        if isinstance(r, Exception):
            log.warning("period opinion failed: %s", r)
        elif r:
            out[r[0]] = r[1]
    return out


@cached(ttl=6 * 3600)
async def get_audit_reports(corp_code: str, years: int = 3) -> dict:
    entry = corp_index.get(corp_code)
    corp_name = entry.corp_name if entry else corp_code
    end = date.today()
    bgn = end - timedelta(days=365 * years + 1)
    bgn_s, end_s = bgn.strftime("%Y%m%d"), end.strftime("%Y%m%d")

    f_items, a_items, opinions = await asyncio.gather(
        dart_client.list_disclosures(corp_code, bgn_s, end_s, pblntf_ty="F", max_pages=5),
        dart_client.list_disclosures(corp_code, bgn_s, end_s, pblntf_ty="A", max_pages=5),
        get_period_opinions(corp_code, bgn),
    )
    tasks = [_standalone(corp_name, it) for it in f_items] + [_attachments(corp_name, it) for it in a_items]
    results = await asyncio.gather(*tasks, return_exceptions=True)
    collected: list[dict] = []
    failed = 0
    for r in results:
        if isinstance(r, Exception):
            failed += 1
            log.warning("viewer parse failed: %s", r)
            continue
        collected.extend(r)
    reports = merge_reports(collected)
    for rep in reports:
        got = opinions.get(rep["period"], {})
        rep["auditor"], rep["opinion"] = got.get("auditor", ""), got.get("opinion", "")
    return {
        "corp_code": corp_code,
        "corp_name": corp_name,
        "from": bgn.isoformat(),
        "to": end.isoformat(),
        "count": len(reports),
        "partial": failed > 0,
        "reports": reports,
    }
