import httpx
import pytest
import respx
from fastapi.testclient import TestClient

from app.cache import cache
from app.config import get_settings
from app.main import create_app
from app.parsers import price as pp
from app.services.corp_index import corp_index, parse_corp_code_xml
from app.services.kiwoom_client import kiwoom_token

from pathlib import Path

FX = Path(__file__).parent / "fixtures"
KW = "https://api.kiwoom.com"
DART = "https://opendart.fss.or.kr/api"


def test_parse_kiwoom_quote_down_has_sign_prefix():
    q = pp.parse_kiwoom_quote({
        "stk_nm": "삼성전자", "cur_prc": "-71000", "pred_pre": "-500", "flu_rt": "-0.70", "pre_sig": "5",
        "open_pric": "+71500", "high_pric": "+71800", "low_pric": "-70800", "trde_qty": "12345678",
        "250hgst": "+88800", "250lwst": "-49900", "per": "12.5", "pbr": "", "eps": "5,677",
    })
    assert q["price"] == 71000 and q["change"] == -500 and q["change_rate"] == -0.70 and q["direction"] == "DOWN"
    assert q["open"] == 71500 and q["week52_high"] == 88800 and q["week52_low"] == 49900
    assert q["per"] == 12.5 and q["pbr"] is None and q["eps"] == 5677.0


def test_parse_kiwoom_quote_up_and_flat():
    up = pp.parse_kiwoom_quote({"cur_prc": "+72000", "pred_pre": "+1000", "flu_rt": "+1.41", "pre_sig": "2"})
    assert up["direction"] == "UP" and up["change"] == 1000 and up["change_rate"] == 1.41
    flat = pp.parse_kiwoom_quote({"cur_prc": "71000", "pred_pre": "0", "flu_rt": "0.00", "pre_sig": "3"})
    assert flat["direction"] == "FLAT" and flat["change"] == 0


def test_parse_kiwoom_chart_sorted_ascending():
    rows = pp.parse_kiwoom_chart([
        {"dt": "20261021", "cur_prc": "71500", "open_pric": "71000", "high_pric": "72000", "low_pric": "70900", "trde_qty": "100"},
        {"dt": "20261020", "cur_prc": "-70000", "open_pric": "70500", "high_pric": "71000", "low_pric": "69900", "trde_qty": "200"},
        {"dt": "bad"},
    ])
    assert [r["date"] for r in rows] == ["2026-10-20", "2026-10-21"]
    assert rows[0]["close"] == 70000


@pytest.fixture
def client(monkeypatch):
    monkeypatch.setenv("DART_API_KEY", "k")
    monkeypatch.setenv("KIWOOM_APP_KEY", "ak")
    monkeypatch.setenv("KIWOOM_SECRET_KEY", "sk")
    monkeypatch.setenv("APP_TOKEN", "")
    get_settings.cache_clear()
    cache.clear()
    kiwoom_token.token = None
    corp_index.load(parse_corp_code_xml((FX / "corpCode.xml").read_bytes()))
    yield TestClient(create_app(start_background=False))
    get_settings.cache_clear()
    kiwoom_token.token = None


def _company():
    respx.get(f"{DART}/company.json").mock(return_value=httpx.Response(200, json={
        "status": "000", "corp_name": "삼성전자", "stock_code": "005930", "stock_name": "삼성전자", "corp_cls": "Y"}))


@respx.mock
def test_quote_uses_kiwoom_and_refreshes_expired_token(client):
    _company()
    tok = respx.post(f"{KW}/oauth2/token").mock(side_effect=[
        httpx.Response(200, json={"token": "t1", "expires_dt": "20991231235959", "return_code": 0}),
        httpx.Response(200, json={"token": "t2", "expires_dt": "20991231235959", "return_code": 0}),
    ])
    info = respx.post(f"{KW}/api/dostk/stkinfo").mock(side_effect=[
        httpx.Response(200, json={"return_code": 3, "return_msg": "[8005:Token이 유효하지 않습니다]"}),
        httpx.Response(200, json={"return_code": 0, "stk_nm": "삼성전자", "cur_prc": "-71000", "pred_pre": "-500",
                                  "flu_rt": "-0.70", "pre_sig": "5", "250hgst": "+88800", "250lwst": "-49900"}),
    ])
    body = client.get("/api/v1/corps/00126380/stock/quote").json()
    assert body["source"] == "KIWOOM" and body["price"] == 71000 and body["direction"] == "DOWN"
    assert tok.call_count == 2
    sent = info.calls.last.request
    assert sent.headers["api-id"] == "ka10001" and sent.headers["authorization"] == "Bearer t2"


@respx.mock
def test_quote_falls_back_to_naver_when_kiwoom_ip_rejected(client):
    _company()
    respx.post(f"{KW}/oauth2/token").mock(return_value=httpx.Response(200, json={
        "return_code": 8010, "return_msg": "[8010:Token을 발급받은 IP와 서비스를 요청한 IP가 동일하지 않습니다]"}))
    respx.get("https://polling.finance.naver.com/api/realtime/domestic/stock/005930").mock(return_value=httpx.Response(200, json={
        "datas": [{"stockName": "삼성전자", "closePrice": "71,000", "compareToPreviousClosePrice": "500",
                   "compareToPreviousPrice": {"code": "5"}, "fluctuationsRatio": "0.70"}]}))
    body = client.get("/api/v1/corps/00126380/stock/quote").json()
    assert body["source"] == "NAVER" and body["price"] == 71000 and body["direction"] == "DOWN"


@respx.mock
def test_chart_paginates_with_cont_key(client):
    _company()
    respx.post(f"{KW}/oauth2/token").mock(return_value=httpx.Response(200, json={"token": "t", "expires_dt": "20991231235959"}))
    from datetime import date, timedelta
    today = date.today()

    def rows(start, n):
        return [{"dt": (start - timedelta(days=i)).strftime("%Y%m%d"), "cur_prc": "50000", "open_pric": "1", "high_pric": "51000",
                 "low_pric": "49000", "trde_qty": "1"} for i in range(n)]

    page1 = httpx.Response(200, json={"return_code": 0, "stk_dt_pole_chart_qry": rows(today, 30)}, headers={"cont-yn": "Y", "next-key": "K1"})
    page2 = httpx.Response(200, json={"return_code": 0, "stk_dt_pole_chart_qry": rows(today - timedelta(days=30), 400)}, headers={"cont-yn": "N"})
    route = respx.post(f"{KW}/api/dostk/chart").mock(side_effect=[page1, page2])
    body = client.get("/api/v1/corps/00126380/stock/chart").json()
    assert body["source"] == "KIWOOM" and len(body["points"]) > 300
    assert route.calls[1].request.headers["next-key"] == "K1" and route.calls[1].request.headers["cont-yn"] == "Y"
    assert body["range_52w"]["high"] == 51000
