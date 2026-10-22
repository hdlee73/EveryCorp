"""KIND 공시 목록 수집(상장사 전용)."""
from __future__ import annotations

import asyncio
from datetime import date

from ..errors import UpstreamError
from ..http import get_client
from ..parsers.kind import KindRow, kind_company_code, parse_details

URL = "https://kind.krx.co.kr/disclosure/details.do"
PAGE_SIZE = 100
_SEM = asyncio.Semaphore(2)


def _form(corp_name: str, stock_code: str, bgn: date, end: date, page: int) -> dict:
    return {
        "method": "searchDetailsSub",
        "currentPageSize": str(PAGE_SIZE),
        "pageIndex": str(page),
        "orderMode": "1",       # 공시일자
        "orderStat": "D",       # 내림차순
        "forward": "details_sub",
        "searchCodeType": "",
        "repIsuSrtCd": f"A{stock_code}",
        "searchCorpName": corp_name,
        "fromDate": bgn.isoformat(),
        "toDate": end.isoformat(),
        "reportNm": "",
        "bfrDsclsType": "on",
    }


async def fetch_kind_disclosures(corp_name: str, stock_code: str, bgn: date, end: date, max_pages: int = 40) -> list[KindRow]:
    code5 = kind_company_code(stock_code)
    out: list[KindRow] = []
    headers = {"Referer": "https://kind.krx.co.kr/disclosure/details.do?method=searchDetailsMain", "X-Requested-With": "XMLHttpRequest"}
    for page in range(1, max_pages + 1):
        async with _SEM:
            r = await get_client().post(URL, data=_form(corp_name, stock_code, bgn, end, page), headers=headers)
        if r.status_code != 200:
            raise UpstreamError("KIND", f"HTTP {r.status_code}")
        rows = parse_details(r.text)
        # 회사명 검색은 부분일치이므로 회사코드로 다시 거른다(코드가 없는 행은 회사명 완전일치로).
        out.extend(x for x in rows if (x.company_code == code5 if x.company_code else x.company == corp_name))
        if len(rows) < PAGE_SIZE:
            break
    return out
