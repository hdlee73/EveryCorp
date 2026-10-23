"""DART 웹 뷰어(dsaf001/main.do) 파서: rcp_no·dcm_no 추출.

뷰어 HTML 구조(참고: FinanceData/OpenDartReader dart_utils.py의 attach_docs·sub_docs가 같은 구조를 파싱함)
- 본문 목차: 스크립트 안에 `node1['dcmNo'] = "9443961";` 형태로 문서번호가 들어 있다.
  PDF 버튼은 `openPdfDownload('20240312000736', '9443961')` 형태.
- 첨부서류: `<select id="att">` 의 option value 가 `rcpNo=...&dcmNo=...`(2026-10 실서버 확인; 예전엔
  `/dsaf001/main.do?rcpNo=...` 형태도 있었음)이고 option 텍스트가 `2026.08.14&nbsp; 반기검토보고서` 처럼 날짜 + 이름.
"""
from __future__ import annotations

import re
from dataclasses import dataclass
from urllib.parse import parse_qs, urlparse

from bs4 import BeautifulSoup

DART_HOST = "https://dart.fss.or.kr"

_DCM_RE = re.compile(r"""\[['"]dcmNo['"]\]\s*=\s*["'](\d+)["']""")
_PDF_BTN_RE = re.compile(r"""openPdfDownload\(\s*['"](\d+)['"]\s*,\s*['"](\d+)['"]""")
_VIEWDOC_RE = re.compile(r"""viewDoc\(\s*['"](\d+)['"]\s*,\s*['"](\d+)['"]""")
_AUDIT_RE = re.compile(r"(감사|검토)보고서")
_EXCLUDE_RE = re.compile(r"감사전|내부회계|감사위원회|감사의\s*감사보고서|감사인\s*선임")
_DATE_PREFIX_RE = re.compile(r"^\s*(?:\d{8}|\d{4}\.\d{2}\.\d{2})\s*")


@dataclass(frozen=True, slots=True)
class ViewerDoc:
    title: str
    rcp_no: str
    dcm_no: str


def parse_main_dcm_no(html: str) -> str | None:
    """본문(주 문서) dcm_no."""
    m = _PDF_BTN_RE.search(html)
    if m:
        return m.group(2)
    m = _DCM_RE.search(html)
    if m:
        return m.group(1)
    m = _VIEWDOC_RE.search(html)
    return m.group(2) if m else None


def parse_attachments(html: str) -> list[ViewerDoc]:
    """첨부서류 드롭다운(<select id="att">)의 모든 문서."""
    soup = BeautifulSoup(html, "html.parser")
    sel = soup.find("select", id="att")
    if sel is None:
        return []
    docs: list[ViewerDoc] = []
    for opt in sel.find_all("option"):
        value = (opt.get("value") or "").strip()
        if not value or value == "null":
            continue
        # 실제 값은 "rcpNo=…&dcmNo=…"(경로 없음)이지만 "/dsaf001/main.do?rcpNo=…" 형태도 허용한다.
        qs = parse_qs(urlparse(value).query or value.lstrip("?"))
        rcp = (qs.get("rcpNo") or qs.get("rcp_no") or [""])[0]
        dcm = (qs.get("dcmNo") or qs.get("dcm_no") or [""])[0]
        if not (rcp and dcm):
            continue
        title = _DATE_PREFIX_RE.sub("", opt.get_text(" ", strip=True))
        docs.append(ViewerDoc(title=title, rcp_no=rcp, dcm_no=dcm))
    return docs


def is_audit_or_review(title: str) -> bool:
    return bool(_AUDIT_RE.search(title)) and not _EXCLUDE_RE.search(title)


def classify(title: str) -> tuple[str, bool]:
    """(AUDIT|REVIEW, 연결여부)"""
    kind = "REVIEW" if "검토" in title else "AUDIT"
    return kind, "연결" in title


def viewer_url(rcp_no: str, dcm_no: str | None = None) -> str:
    u = f"{DART_HOST}/dsaf001/main.do?rcpNo={rcp_no}"
    return u + (f"&dcmNo={dcm_no}" if dcm_no else "")


def mobile_viewer_url(rcp_no: str) -> str:
    return f"https://m.dart.fss.or.kr/html_mdart/MD1007.html?rcpNo={rcp_no}"


def pdf_url(rcp_no: str, dcm_no: str) -> str:
    return f"{DART_HOST}/pdf/download/pdf.do?rcp_no={rcp_no}&dcm_no={dcm_no}"


_NODE_ATTR_RE = re.compile(r"""node\d+\['(text|id|rcpNo|dcmNo|eleId|offset|length|dtd)'\]\s*=\s*"([^"]*)\"""")


def parse_nodes(html: str) -> list[dict]:
    """뷰어 목차 노드(text·rcpNo·dcmNo·eleId·offset·length·dtd). 노드는 'text' 속성에서 시작한다(2026-10 실서버 확인)."""
    blocks: list[dict] = []
    cur: dict = {}
    for k, v in _NODE_ATTR_RE.findall(html):
        if k == "text" and cur:
            blocks.append(cur)
            cur = {}
        cur[k] = v
    if cur:
        blocks.append(cur)
    return blocks
