from pathlib import Path

import httpx
import pytest
import respx
from fastapi.testclient import TestClient

from app.cache import cache
from app.config import get_settings
from app.main import create_app
from app.services.corp_index import corp_index, parse_corp_code_xml

FX = Path(__file__).parent / "fixtures"
DART = "https://opendart.fss.or.kr/api"


@pytest.fixture(autouse=True)
def setup(monkeypatch):
    monkeypatch.setenv("DART_API_KEY", "test-key")
    monkeypatch.setenv("APP_TOKEN", "app-secret")
    get_settings.cache_clear()
    cache.clear()
    corp_index.load(parse_corp_code_xml((FX / "corpCode.xml").read_bytes()))
    yield
    get_settings.cache_clear()


@pytest.fixture
def client():
    return TestClient(create_app(start_background=False), headers={"X-App-Token": "app-secret"})


def test_search_requires_token():
    c = TestClient(create_app(start_background=False))
    r = c.get("/api/v1/corps/search", params={"q": "삼성"})
    assert r.status_code == 401
    assert r.json()["error"]["code"] == "UNAUTHORIZED"


def test_search_ranking_and_chosung(client):
    items = client.get("/api/v1/corps/search", params={"q": "삼성전자"}).json()["items"]
    assert [i["corp_name"] for i in items] == ["삼성전자", "삼성전자서비스"]
    assert items[0]["badge"] == "상장" and items[1]["badge"] == "외감"
    assert client.get("/api/v1/corps/search", params={"q": "ㅅㅅㅈㅈ"}).json()["items"][0]["corp_code"] == "00126380"
    assert client.get("/api/v1/corps/search", params={"q": "000660"}).json()["items"][0]["corp_name"] == "SK하이닉스"
    assert client.get("/api/v1/corps/search", params={"q": "삼성물산테스트"}).json()["count"] == 1


@respx.mock
def test_overview_end_to_end(client):
    respx.get(f"{DART}/company.json").mock(return_value=httpx.Response(200, json={
        "status": "000", "corp_name": "삼성전자", "stock_code": "005930", "stock_name": "삼성전자", "corp_cls": "Y",
        "ceo_nm": "전영현, 노태문", "induty_code": "264", "acc_mt": "12", "est_dt": "19690113",
    }))
    respx.get(f"{DART}/list.json").mock(return_value=httpx.Response(200, json={
        "status": "000", "total_page": 1, "list": [
            {"report_nm": "반기보고서 (2026.06)", "rcept_no": "20260814000300", "rcept_dt": "20260814"},
            {"report_nm": "사업보고서 (2025.12)", "rcept_no": "20260310000100", "rcept_dt": "20260310"},
        ]}))
    respx.get(f"{DART}/hyslrSttus.json", params={"reprt_code": "11012"}).mock(return_value=httpx.Response(200, json={
        "status": "000", "list": [
            {"nm": "삼성생명보험㈜", "relate": "최대주주 본인", "stock_knd": "보통주", "trmend_posesn_stock_qota_rt": "8.51"},
            {"nm": "삼성물산㈜", "relate": "계열회사", "stock_knd": "보통주", "trmend_posesn_stock_qota_rt": "5.01"},
            {"nm": "계", "relate": "-", "stock_knd": "보통주", "trmend_posesn_stock_qota_rt": "13.52"},
        ]}))
    respx.get(f"{DART}/fnlttSinglAcntAll.json", params={"fs_div": "CFS"}).mock(return_value=httpx.Response(200, json={
        "status": "000", "list": [
            {"sj_div": "BS", "account_id": "ifrs-full_Assets", "account_nm": "자산총계", "thstrm_amount": "514531948000000"},
            {"sj_div": "IS", "account_id": "ifrs-full_Revenue", "account_nm": "매출액", "thstrm_amount": "300870903000000"},
        ]}))

    body = client.get("/api/v1/corps/00126380/overview").json()
    assert body["ceo_name"] == "전영현, 노태문"
    assert body["listing"] == "LISTED" and body["market"] == "KOSPI"
    assert body["largest_shareholder"]["display"] == "최대주주 삼성생명보험㈜ 외 특수관계인 1명 (합산 지분율 13.5%)"
    assert body["largest_shareholder"]["basis"]["reprt_code"] == "11012"
    fin = body["financials"]
    assert fin["fs_label"] == "연결" and fin["basis"]["bsns_year"] == "2025"
    assert fin["income_statement"]["revenue"]["display"] == "300조 8,709억원"


@respx.mock
def test_quote_not_listed(client):
    respx.get(f"{DART}/company.json").mock(return_value=httpx.Response(200, json={
        "status": "000", "corp_name": "삼성전자서비스", "stock_code": "", "corp_cls": "E"}))
    r = client.get("/api/v1/corps/00258801/stock/quote")
    assert r.status_code == 404
    assert r.json()["error"]["code"] == "NOT_LISTED"


def test_invalid_corp_code(client):
    assert client.get("/api/v1/corps/abc/overview").status_code == 400


def test_health_and_meta_public():
    c = TestClient(create_app(start_background=False))
    assert c.get("/api/v1/health").json()["status"] == "ok"
    with respx.mock:
        respx.get("https://api.github.com/repos/hdlee73/EveryCorp/releases/latest").mock(return_value=httpx.Response(200, json={
            "tag_name": "v1.1.0", "html_url": "https://github.com/hdlee73/EveryCorp/releases/tag/v1.1.0",
            "body": "# EveryCorp v1.1.0 변경 내용\n- 공시 필터 추가\n", "assets": [
                {"name": "everycorp-v1.1.0.apk", "browser_download_url": "https://x/apk", "size": 10},
                {"name": "everycorp-v1.1.0.apk.sha256", "browser_download_url": "https://x/sha", "size": 1},
            ]}))
        meta = c.get("/api/v1/app/meta", params={"current_version": "1.0.0"}).json()
    assert meta["author"] == {"name": "이현덕", "email": "hdlee73@gmail.com"}
    assert meta["update_available"] is True and meta["force_update"] is False
    assert meta["latest"]["notes"] == "- 공시 필터 추가"


@respx.mock
def test_debt_securities_endpoint(client):
    respx.get(f"{DART}/company.json").mock(return_value=httpx.Response(200, json={"status": "000", "corp_name": "삼성전자", "stock_code": "005930", "corp_cls": "Y", "acc_mt": "12"}))
    respx.get(f"{DART}/list.json").mock(return_value=httpx.Response(200, json={"status": "000", "total_page": 1, "list": [
        {"report_nm": "사업보고서 (2025.12)", "rcept_no": "20260310000100", "rcept_dt": "20260310"}]}))
    respx.get(f"{DART}/cprndNrdmpBlce.json").mock(return_value=httpx.Response(200, json={"status": "000", "list": [
        {"remndr_exprtn1": "합계", "remndr_exprtn2": "-", "yy1_below": "10", "sm": "10"}]}))
    for ep in ("entrprsBilScritsNrdmpBlce", "srtpdPsndbtNrdmpBlce", "newCaplScritsNrdmpBlce", "cndlCaplScritsNrdmpBlce"):
        respx.get(f"{DART}/{ep}.json").mock(return_value=httpx.Response(200, json={"status": "013", "message": "조회된 데이타가 없습니다."}))
    body = client.get("/api/v1/corps/00126380/debt-securities").json()
    assert body["available"] is True
    assert [s["key"] for s in body["sections"]] == ["CORP_BOND"]
    assert "기업어음증권" in body["missing"] and "단기사채" in body["missing"]
    assert body["basis"]["reprt_code"] == "11011"
