"""서버 진단 도구. 외부 API가 실제로 어떤 응답을 주는지(키는 가리고) 요약해 출력한다.

사용: docker compose exec api python -m app.diag            (삼성전자 기본)
      docker compose exec api python -m app.diag 00126380 005930
"""
from __future__ import annotations

import asyncio
import re
import sys
from datetime import date, timedelta

from .http import get_client
from .parsers import dart_viewer as dv
from .parsers import kind as kp
from .services import dart_client, kind_client


def head(title: str) -> None:
    print(f"\n===== {title} =====")


def snippet(text: str, n: int = 300) -> str:
    return re.sub(r"\s+", " ", text)[:n]


async def diag_kind(name: str, stock: str) -> None:
    head("KIND 공시")
    end = date.today()
    bgn = end - timedelta(days=90)
    client = get_client()
    try:
        r = await client.get("https://kind.krx.co.kr/disclosure/details.do?method=searchDetailsMain", timeout=15)
        print("메인 GET:", r.status_code, len(r.text), snippet(r.text, 120))
    except Exception as e:  # noqa: BLE001
        print("메인 GET 실패:", type(e).__name__, e)
    try:
        form = kind_client._form(name, stock, bgn, end, 1)
        headers = {"Referer": "https://kind.krx.co.kr/disclosure/details.do?method=searchDetailsMain",
                   "X-Requested-With": "XMLHttpRequest"}
        r = await client.post(kind_client.URL, data=form, headers=headers, timeout=20)
        rows = kp.parse_details(r.text)
        print("POST:", r.status_code, "len", len(r.text), "parsed rows", len(rows))
        print("본문 앞부분:", snippet(r.text, 400))
        for x in rows[:3]:
            print("  행:", x)
    except Exception as e:  # noqa: BLE001
        print("POST 실패:", type(e).__name__, e)


async def diag_reports(corp_code: str) -> None:
    head("감사·검토보고서 (DART)")
    end = date.today()
    bgn = end - timedelta(days=365 * 3 + 1)
    for ty in ("F", "A"):
        try:
            items = await dart_client.list_disclosures(corp_code, bgn.strftime("%Y%m%d"), end.strftime("%Y%m%d"),
                                                       pblntf_ty=ty, max_pages=2)
        except Exception as e:  # noqa: BLE001
            print(f"pblntf_ty={ty} 목록 실패:", type(e).__name__, e)
            continue
        print(f"pblntf_ty={ty}: {len(items)}건")
        for it in items[:4]:
            print("  ", it.get("rcept_dt"), it.get("report_nm"), it.get("rcept_no"))
        target = next((i for i in items if re.search("사업|반기|분기|감사", i.get("report_nm", ""))), None)
        if target:
            try:
                r = await get_client().get(f"{dv.DART_HOST}/dsaf001/main.do", params={"rcpNo": target["rcept_no"]})
                html = r.text
                print("  뷰어:", target["rcept_no"], r.status_code, "len", len(html),
                      "select#att" if 'id="att"' in html else "select#att 없음",
                      "dcm:", dv.parse_main_dcm_no(html))
                for d in dv.parse_attachments(html)[:12]:
                    print("    첨부:", d.title, d.rcp_no, d.dcm_no)
                if 'id="att"' not in html:
                    print("  뷰어 앞부분:", snippet(html, 300))
                else:
                    i = html.find('id="att"')
                    print("  select#att 원문:", snippet(html[max(0, i - 40):i + 900], 900))
                hits = [m.start() for m in re.finditer("감사|검토", html)]
                print("  '감사|검토' 출현", len(hits), "곳")
                for pos in hits[:6]:
                    print("    ...", snippet(html[max(0, pos - 80):pos + 160], 240))
            except Exception as e:  # noqa: BLE001
                print("  뷰어 실패:", type(e).__name__, e)


async def main() -> None:
    corp_code = sys.argv[1] if len(sys.argv) > 1 else "00126380"
    stock = sys.argv[2] if len(sys.argv) > 2 else "005930"
    name = "삼성전자" if corp_code == "00126380" else ""
    await diag_kind(name, stock)
    await diag_reports(corp_code)
    head("끝")


if __name__ == "__main__":
    asyncio.run(main())
