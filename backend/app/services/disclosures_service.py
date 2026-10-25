"""탭 3. 공시목록 (DART + KIND 통합, 공시일자 최신순)."""
from __future__ import annotations

import asyncio
import logging
import re
from datetime import date, timedelta

from ..cache import cached
from ..config import get_settings
from ..parsers import dart_viewer as dv
from ..parsers import kind as kp
from . import dart_client, kind_client
from .company_service import get_company

log = logging.getLogger(__name__)


def _dart_item(it: dict) -> dict:
    d = it.get("rcept_dt", "")
    return {
        "id": f"DART-{it['rcept_no']}",
        "source": "DART",
        "date": f"{d[:4]}-{d[4:6]}-{d[6:8]}",
        "time": None,
        "title": re.sub(r"\s+", " ", it.get("report_nm", "")).strip(),
        "submitter": it.get("flr_nm"),
        "remark": it.get("rm") or None,   # 유:유가 코:코스닥 채:채권 넥:코넥스 공:공정위 연:연결 정:정정 철:철회
        "url": dv.viewer_url(it["rcept_no"]),
        "mobile_url": dv.mobile_viewer_url(it["rcept_no"]),
    }


def _kind_item(r: kp.KindRow) -> dict:
    url = kp.viewer_url(r.acptno, r.docno)
    return {
        "id": f"KIND-{r.acptno}",
        "source": "KIND",
        "date": r.date,
        "time": r.time,
        "title": r.title,
        "submitter": r.submitter or r.company,
        "remark": None,
        "url": url,
        "mobile_url": url,
    }


def _norm_title(t: str) -> str:
    t = re.sub(r"^\[[^\]]+\]", "", t)
    t = re.sub(r"\([^)]*\)", "", t)
    return re.sub(r"[\s·ㆍ]+", "", t)


def merge_disclosures(dart: list[dict], kind: list[dict], dedupe: bool) -> list[dict]:
    items = dart + kind
    if dedupe:
        # 같은 날짜 + 같은 제목(괄호·접두어 무시)이면 DART 쪽만 남긴다.
        dart_keys = {(x["date"], _norm_title(x["title"])) for x in dart}
        items = dart + [x for x in kind if (x["date"], _norm_title(x["title"])) not in dart_keys]
    # 공시일자 desc → 시간(있는 것 우선) desc → 접수번호 desc
    return sorted(items, key=lambda x: (x["date"], x["time"] or "", x["id"].split("-", 1)[1]), reverse=True)


@cached(ttl=30 * 60)
async def _collect(corp_code: str, years: int) -> dict:
    end = date.today()
    bgn = end - timedelta(days=365 * years)
    company = await get_company(corp_code)
    stock_code = (company.get("stock_code") or "").strip()

    dart_task = dart_client.list_disclosures(corp_code, bgn.strftime("%Y%m%d"), end.strftime("%Y%m%d"), max_pages=100)
    kind_task = (
        kind_client.fetch_kind_disclosures(company.get("stock_name") or company.get("corp_name", ""), stock_code, bgn, end)
        if stock_code and get_settings().kind_from_server else asyncio.sleep(0, result=[])
    )
    dart_raw, kind_raw = await asyncio.gather(dart_task, kind_task, return_exceptions=True)
    warnings = []
    if isinstance(dart_raw, Exception):
        warnings.append(f"DART 목록을 불러오지 못했습니다: {dart_raw}")
        dart_raw = []
    if isinstance(kind_raw, Exception):
        log.warning("KIND failed: %s", kind_raw)
        warnings.append("KIND 목록을 불러오지 못했습니다.")
        kind_raw = []
    return {
        "from": bgn.isoformat(),
        "to": end.isoformat(),
        "kind_supported": bool(stock_code),
        "dart": [_dart_item(x) for x in dart_raw],
        "kind": [_kind_item(x) for x in kind_raw],
        "warnings": warnings,
    }


_PERIODIC_TITLE_RE = re.compile(r"^(\[[^\]]+\]\s*)*(사업|반기|분기)보고서")


def is_periodic_title(title: str) -> bool:
    """정기보고서(사업·반기·분기보고서, [기재정정] 등 접두어 허용)."""
    return bool(_PERIODIC_TITLE_RE.match(title.strip()))


_MAJOR_TITLE_RE = re.compile(r"^(\[[^\]]+\]\s*)*주요사항보고서")


def is_major_title(title: str) -> bool:
    """주요사항보고서(예: 주요사항보고서(유상증자결정), [기재정정] 접두어 허용)."""
    return bool(_MAJOR_TITLE_RE.match(title.strip()))


async def get_disclosures(corp_code: str, years: int, source: str, dedupe: bool, page: int, size: int, q: str | None,
                          category: str = "all") -> dict:
    data = await _collect(corp_code, years)
    dart = data["dart"] if source in ("all", "dart") else []
    kind = data["kind"] if source in ("all", "kind") else []
    items = merge_disclosures(dart, kind, dedupe)
    if category == "periodic":
        items = [x for x in items if is_periodic_title(x["title"])]
    elif category == "major":
        items = [x for x in items if is_major_title(x["title"])]
    if q:
        ql = q.strip().lower()
        items = [x for x in items if ql in x["title"].lower()]
    total = len(items)
    start = (page - 1) * size
    return {
        "corp_code": corp_code,
        "from": data["from"],
        "to": data["to"],
        "years": years,
        "source": source,
        "kind_supported": data["kind_supported"],
        "kind_from_server": get_settings().kind_from_server,
        "counts": {
            "dart": sum(1 for x in items if x["source"] == "DART"),
            "kind": sum(1 for x in items if x["source"] == "KIND"),
            "total": total,
        },
        "category": category,
        "page": page,
        "size": size,
        "has_next": start + size < total,
        "items": items[start:start + size],
        "warnings": data["warnings"],
    }
