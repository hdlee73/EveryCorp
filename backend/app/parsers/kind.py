"""KIND(kind.krx.co.kr) 상세검색 결과 HTML 파서.

요청: POST https://kind.krx.co.kr/disclosure/details.do  (method=searchDetailsSub, forward=details_sub)
응답 표(행 예시):
  <tr>
    <td class="first txc">1</td>
    <td class="txc">2026-10-21 17:32</td>
    <td><a href="#company" onclick="companysummary_open('00593'); return false;" id="companysum">삼성전자</a></td>
    <td><a href="#viewer" onclick="openDisclsViewer('20261021000812',''); return false;" title="...">기업설명회(IR) 개최</a></td>
    <td class="txc">삼성전자</td>
  </tr>
- 회사코드(companysummary_open 인자)는 종목코드 앞 5자리.
- 원문 뷰어: https://kind.krx.co.kr/common/disclsviewer.do?method=search&acptno={acptno}
"""
from __future__ import annotations

import re
from dataclasses import dataclass

from bs4 import BeautifulSoup

_VIEWER_RE = re.compile(r"openDisclsViewer\(\s*'(\d+)'\s*(?:,\s*'([^']*)')?")
_COMPANY_RE = re.compile(r"companysummary_open\(\s*'(\w+)'")
_DT_RE = re.compile(r"(\d{4}-\d{2}-\d{2})(?:\s+(\d{2}:\d{2}))?")
_TOTAL_RE = re.compile(r"전체\s*<em>\s*([\d,]+)\s*</em>|총\s*([\d,]+)\s*건")


@dataclass(frozen=True, slots=True)
class KindRow:
    acptno: str
    docno: str
    date: str          # YYYY-MM-DD
    time: str | None   # HH:MM
    company: str
    company_code: str | None
    title: str
    submitter: str


def parse_details(html: str) -> list[KindRow]:
    soup = BeautifulSoup(html, "html.parser")
    rows: list[KindRow] = []
    for tr in soup.find_all("tr"):
        a_viewer = tr.find("a", onclick=_VIEWER_RE)
        if a_viewer is None:
            continue
        m = _VIEWER_RE.search(a_viewer["onclick"])
        tds = tr.find_all("td")
        dt_m = None
        for td in tds:
            dt_m = _DT_RE.search(td.get_text(" ", strip=True))
            if dt_m:
                break
        if not dt_m:
            continue
        a_comp = tr.find("a", onclick=_COMPANY_RE)
        comp_code = _COMPANY_RE.search(a_comp["onclick"]).group(1) if a_comp else None
        title = (a_viewer.get("title") or "").strip() or a_viewer.get_text(" ", strip=True)
        title = re.sub(r"\s+", " ", title)
        submitter = tds[-1].get_text(" ", strip=True) if tds else ""
        rows.append(KindRow(
            acptno=m.group(1),
            docno=m.group(2) or "",
            date=dt_m.group(1),
            time=dt_m.group(2),
            company=a_comp.get_text(strip=True) if a_comp else "",
            company_code=comp_code,
            title=title,
            submitter=submitter,
        ))
    return rows


def viewer_url(acptno: str, docno: str = "") -> str:
    return f"https://kind.krx.co.kr/common/disclsviewer.do?method=search&acptno={acptno}&docno={docno}&viewerhost=&viewerport="


def kind_company_code(stock_code: str) -> str:
    """종목코드(6자리) → KIND 회사코드(앞 5자리)."""
    return stock_code[:5]
