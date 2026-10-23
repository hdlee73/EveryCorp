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
from .config import get_settings
from .services import dart_client, kind_client
from .services.company_service import get_company


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


# 공공데이터포털(금융위원회/예탁결제원) 채권 API. 엔드포인트 경로는 명세에서 확인하지 못해 후보를 모두 시도한다.
BOND_CANDIDATES = [
    # 확인된 주소(채권발행정보): 발행자별 발행종목 내역
    "https://apis.data.go.kr/1160100/GetBondTradInfoService_V2/getIssuIssuItemStat_V2",
    # 채권기본정보는 명세 화면에서 경로를 확인하지 못해 여러 후보를 시도한다.
    "https://apis.data.go.kr/1160100/GetBondBasiInfoService/getBondBasiInfo",
    "https://apis.data.go.kr/1160100/GetBondBasiInfoService_V2/getBondBasiInfo_V2",
    "https://apis.data.go.kr/1160100/GetBondIssuInfoService/getBondBasiInfo",
    "https://apis.data.go.kr/1160100/GetBondIssuInfoService_V2/getBondBasiInfo_V2",
    "https://apis.data.go.kr/1160100/GetBondIssuInfoService/getBondIssuInfo",
    "https://apis.data.go.kr/1160100/GetBondTradInfoService_V2/getBondBasiInfo_V2",
]


async def diag_bonds(corp_code: str) -> None:
    head("채권 발행·잔액 (공공데이터포털)")
    key = get_settings().data_go_kr_key
    if not key:
        print("DATA_GO_KR_KEY 없음(.env에 추가 필요)")
    try:
        company = await get_company(corp_code)
        crno = (company.get("jurir_no") or "").replace("-", "")
        print("법인등록번호(DART):", crno[:6] + "*******" if crno else "없음")
    except Exception as e:  # noqa: BLE001
        print("기업개황 실패:", type(e).__name__, e)
        return
    if not (key and crno):
        return
    for url in BOND_CANDIDATES:
        try:
            r = await get_client().get(url, params={"serviceKey": key, "resultType": "json", "numOfRows": 3,
                                                      "pageNo": 1, "crno": crno})
            print(url.rsplit("/", 2)[-2] + "/" + url.rsplit("/", 1)[-1], "->", r.status_code, "len", len(r.text))
            body = r.text.replace(key, "***")
            if "NO_OPENAPI_SERVICE_ERROR" in body:
                print("    (없는 서비스 경로)")
            else:
                print("   ", snippet(body, 900))
        except Exception as e:  # noqa: BLE001
            print(url, "실패:", type(e).__name__, e)


async def diag_opinions(corp_code: str) -> None:
    head("감사의견 원본 행 (연결/별도 구분 확인용)")
    from .services.company_service import _fetch_report_api, get_company, get_periodic_reports

    try:
        company = await get_company(corp_code)
        fm = int(company.get("acc_mt") or 12)
        annuals = [r for r in await get_periodic_reports(corp_code) if r.kind == "사업"]
        if not annuals:
            print("사업보고서 없음")
            return
        got = await _fetch_report_api("accnutAdtorNmNdAdtOpinion.json", corp_code, annuals[0], fm)
        for r in (got[0] if got else []):
            print({k: (str(v)[:40]) for k, v in r.items() if k not in ("rcept_no", "corp_cls", "corp_code", "corp_name")})
    except Exception as e:  # noqa: BLE001
        print("실패:", type(e).__name__, e)


async def main() -> None:
    corp_code = sys.argv[1] if len(sys.argv) > 1 else "00126380"
    stock = sys.argv[2] if len(sys.argv) > 2 else "005930"
    name = "삼성전자" if corp_code == "00126380" else ""
    await diag_kind(name, stock)
    await diag_reports(corp_code)
    await diag_opinions(corp_code)
    await diag_bonds(corp_code)
    head("끝")


if __name__ == "__main__":
    asyncio.run(main())
