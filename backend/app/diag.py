"""서버 진단 도구. 외부 API가 실제로 어떤 응답을 주는지(키는 가리고) 요약해 출력한다.

사용: docker compose exec api python -m app.diag            (삼성전자 기본)
      docker compose exec api python -m app.diag 00126380 005930
      docker compose exec api python -m app.diag <corp_code> <stock_code> --holder   (비상장: 감사보고서 주석 탐색)
      docker compose exec api python -m app.diag 00126380 005930 --etf              (키움 ETF TR 후보)
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


async def diag_holder_note(corp_code: str) -> None:
    head("감사보고서 본문에서 주주·지분 문구 찾기 (비상장 최대주주 추출용)")
    end = date.today()
    bgn = end - timedelta(days=365 * 2 + 1)
    try:
        items = await dart_client.list_disclosures(corp_code, bgn.strftime("%Y%m%d"), end.strftime("%Y%m%d"), pblntf_ty="F", max_pages=2)
    except Exception as e:  # noqa: BLE001
        print("목록 실패:", type(e).__name__, e)
        return
    audits = [i for i in items if "감사보고서" in i.get("report_nm", "") and "연결" not in i.get("report_nm", "")]
    print("감사보고서 공시:", [(i.get("rcept_dt"), i.get("report_nm")) for i in audits[:3]])
    if not audits:
        return
    rcp = audits[0]["rcept_no"]
    client = get_client()
    r = await client.get(f"{dv.DART_HOST}/dsaf001/main.do", params={"rcpNo": rcp})
    html = r.text
    nodes = re.findall(r"node\d+\['(text|id|rcpNo|dcmNo|eleId|offset|length|dtd)'\]\s*=\s*\"([^\"]*)\"", html)
    print("뷰어:", r.status_code, "len", len(html), "노드 속성", len(nodes))
    blocks: list[dict] = []
    cur: dict = {}
    for k, v in nodes:
        if k == "text" and cur:
            blocks.append(cur)
            cur = {}
        cur[k] = v
    if cur:
        blocks.append(cur)
    print("노드:", [(b.get("id"), b.get("text")) for b in blocks][:25])
    for b in blocks:
        if not re.search(r"주석|일반|개요", b.get("text", "")):
            continue
        try:
            rr = await client.get(f"{dv.DART_HOST}/report/viewer.do", params={
                "rcpNo": b.get("rcpNo") or rcp, "dcmNo": b.get("dcmNo"), "eleId": b.get("eleId"),
                "offset": b.get("offset"), "length": b.get("length"), "dtd": b.get("dtd")})
            text = re.sub(r"<[^>]+>", " ", rr.text)
            text = re.sub(r"\s+", " ", text)
            print(f"  [{b.get('text')}] {rr.status_code} len {len(text)}")
            for m in list(re.finditer(r"최대주주|지배기업|모회사|주주\s*현황|지분율", text))[:6]:
                print("    ...", text[max(0, m.start() - 80):m.start() + 200])
        except Exception as e:  # noqa: BLE001
            print("  노드 실패:", type(e).__name__, e)


ETF_CODE = "069500"  # KODEX 200


async def diag_kiwoom_etf() -> None:
    head("키움 ETF TR 후보 (069500 KODEX 200)")
    from .services import kiwoom_client as kw

    if not kw.kiwoom_enabled():
        print("키움 키 없음")
        return
    today = date.today().strftime("%Y%m%d")
    tries = [
        ("/api/dostk/stkinfo", "ka10001", {"stk_cd": ETF_CODE}),
        ("/api/dostk/etf", "ka40001", {"stk_cd": ETF_CODE, "etfobjt_idex_cd": "207", "dt": "1"}),
        ("/api/dostk/etf", "ka40002", {"stk_cd": ETF_CODE}),
        ("/api/dostk/etf", "ka40003", {"stk_cd": ETF_CODE}),
        ("/api/dostk/etf", "ka40004", {"txon_type": "0", "navpre": "0", "mngmcomp": "0000", "txon_yn": "0", "trace_idex": "0", "stex_tp": "1"}),
        ("/api/dostk/etf", "ka40006", {"stk_cd": ETF_CODE}),
        ("/api/dostk/chart", "ka10081", {"stk_cd": ETF_CODE, "base_dt": today, "upd_stkpc_tp": "1"}),
    ]
    for path, api_id, body in tries:
        try:
            data, _ = await kw._post(path, api_id, body)
            keys = {k: (v if not isinstance(v, list) else f"list[{len(v)}]") for k, v in data.items()}
            print(f"{api_id} {path}: return_code={data.get('return_code')} msg={str(data.get('return_msg'))[:80]}")
            print("   키:", snippet(str(keys), 500))
            for k, v in data.items():
                if isinstance(v, list) and v:
                    print("   첫 행:", snippet(str(v[0]), 600))
        except Exception as e:  # noqa: BLE001
            print(f"{api_id} 실패:", type(e).__name__, e)


async def main() -> None:
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    corp_code = args[0] if len(args) > 0 else "00126380"
    stock = args[1] if len(args) > 1 else "005930"
    name = "삼성전자" if corp_code == "00126380" else ""
    await diag_kind(name, stock)
    await diag_reports(corp_code)
    await diag_opinions(corp_code)
    await diag_bonds(corp_code)
    if "--holder" in sys.argv:
        await diag_holder_note(corp_code)
    if "--etf" in sys.argv:
        await diag_kiwoom_etf()
    head("끝")


if __name__ == "__main__":
    asyncio.run(main())
