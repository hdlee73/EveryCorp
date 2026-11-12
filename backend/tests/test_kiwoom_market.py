"""키움 시장 데이터(캔들·호가·수급·랭킹)와 실시간 중계 테스트. 외부 호출은 모두 모킹."""
import asyncio
import json
from datetime import date, timedelta
from pathlib import Path

import httpx
import pytest
import respx
from fastapi.testclient import TestClient

from app.cache import cache
from app.config import get_settings
from app.main import create_app
from app.parsers import kiwoom_market as km
from app.services import kiwoom_client
from app.services.corp_index import corp_index, parse_corp_code_xml
from app.services.kiwoom_client import kiwoom_token, rate_limiter
from app.services.market_data_service import summarize_flows, tag_disclosure
from app.services.realtime_service import RealtimeHub

FX = Path(__file__).parent / "fixtures"
KW = "https://api.kiwoom.com"
DART = "https://opendart.fss.or.kr/api"


# ───────── 파서 ─────────

def test_parse_minute_candles_strips_signs_and_sorts():
    rows = km.parse_candles("ka10080", {"stk_min_pole_chart_qry": [
        {"cntr_tm": "20261111090200", "cur_prc": "-70100", "open_pric": "-70000", "high_pric": "+70300", "low_pric": "-69900", "trde_qty": "1200"},
        {"cntr_tm": "20261111090100", "cur_prc": "+70000", "open_pric": "70000", "high_pric": "70100", "low_pric": "69800", "trde_qty": "800"},
        {"cntr_tm": "bad", "cur_prc": "1"},
    ]})
    assert [r["t"] for r in rows] == ["2026-11-11T09:01", "2026-11-11T09:02"]
    assert rows[1] == {"t": "2026-11-11T09:02", "o": 70000, "h": 70300, "l": 69900, "c": 70100, "v": 1200}


def test_parse_weekly_candles_uses_week_key():
    rows = km.parse_candles("ka10082", {"stk_stk_pole_chart_qry": [
        {"dt": "20261109", "cur_prc": "71000", "open_pric": "70000", "high_pric": "72000", "low_pric": "69000", "trde_qty": "5"},
    ]})
    assert rows == [{"t": "2026-11-09", "o": 70000, "h": 72000, "l": 69000, "c": 71000, "v": 5}]


def test_aggregate_daily_to_weekly_and_monthly():
    daily = [
        {"date": "2026-11-02", "open": 100, "high": 110, "low": 95, "close": 105, "volume": 10},  # 월
        {"date": "2026-11-06", "open": 106, "high": 120, "low": 101, "close": 118, "volume": 20},  # 금
        {"date": "2026-11-09", "open": 118, "high": 119, "low": 90, "close": 92, "volume": 5},   # 다음 주 월
    ]
    w = km.aggregate_candles(daily, "W")
    assert w[0] == {"t": "2026-11-02", "o": 100, "h": 120, "l": 95, "c": 118, "v": 30}
    assert w[1]["t"] == "2026-11-09" and w[1]["c"] == 92
    m = km.aggregate_candles(daily, "M")
    assert len(m) == 1 and m[0]["h"] == 120 and m[0]["l"] == 90 and m[0]["v"] == 35


def test_parse_orderbook_ten_levels():
    data = {"bid_req_base_tm": "101530", "sel_fpr_bid": "+70100", "sel_fpr_req": "1500", "buy_fpr_bid": "-70000",
            "buy_fpr_req": "2300", "sel_2th_pre_bid": "70200", "sel_2th_pre_req": "900", "buy_2th_pre_bid": "69900",
            "buy_2th_pre_req": "100", "tot_sel_req": "50000", "tot_buy_req": "64000"}
    ob = km.parse_orderbook(data)
    assert ob["asks"] == [{"price": 70100, "qty": 1500}, {"price": 70200, "qty": 900}]
    assert ob["bids"][0] == {"price": 70000, "qty": 2300}
    assert ob["total_ask"] == 50000 and ob["total_bid"] == 64000 and ob["time"] == "10:15:30"


def test_parse_investors_keeps_net_sign():
    rows = km.parse_investors({"stk_invsr_orgn": [
        {"dt": "20261110", "cur_prc": "+70000", "flu_rt": "+1.20", "ind_invsr": "-120000", "frgnr_invsr": "+80000", "orgn": "+40000"},
        {"dt": "20261111", "cur_prc": "-69000", "ind_invsr": "5000", "frgnr_invsr": "-3000", "orgn": "-2000"},
    ]})
    assert rows[0]["date"] == "2026-11-11" and rows[0]["foreign"] == -3000
    assert rows[1]["individual"] == -120000 and rows[1]["close"] == 70000 and rows[1]["change_rate"] == 1.2


def test_parse_short_credit_program():
    s = km.parse_short({"shrts_trnsn": [{"dt": "20261111", "close_pric": "-70000", "trde_qty": "100000",
                                          "shrts_qty": "5000", "trde_wght": "+5.00", "shrts_avg_pric": "70100"}]})
    assert s[0]["short_qty"] == 5000 and s[0]["short_ratio"] == 5.0 and s[0]["short_avg_price"] == 70100
    c = km.parse_credit({"crd_trde_trend": [{"dt": "20261111", "new": "1000", "rpya": "800", "remn": "250000", "remn_rt": "0.42"}]})
    assert c[0]["balance"] == 250000 and c[0]["balance_rate"] == 0.42
    p = km.parse_program({"stk_daly_prm_trde_trnsn": [{"dt": "20261111", "prm_netprps_amt": "-1234", "prm_buy_amt": "500"}]})
    assert p[0]["net_amt"] == -1234 and p[0]["buy_amt"] == 500


def test_parse_ranking_and_tags():
    rows = km.parse_ranking({"tdy_trde_qty_upper": [
        {"stk_cd": "005930_AL", "stk_nm": "삼성전자", "cur_prc": "+71000", "pred_pre": "+500", "flu_rt": "+0.71", "trde_qty": "12000000", "trde_amt": "850000"},
        {"stk_cd": "", "stk_nm": "?"},
    ]})
    assert rows == [{"stock_code": "005930", "name": "삼성전자", "price": 71000, "change": 500, "change_rate": 0.71,
                     "direction": "UP", "volume": 12000000, "trade_value": 850000}]
    assert tag_disclosure("[기재정정]주요사항보고서(유상증자결정)") == "유상증자"
    assert tag_disclosure("단일판매ㆍ공급계약체결") == "공급계약"
    assert tag_disclosure("주식등의대량보유상황보고서(일반)") == "지분변동"
    assert tag_disclosure("기타 안내사항") == "기타공시"


def test_real_message_only_trade_and_orderbook():
    msg = {"trnm": "REAL", "data": [
        {"type": "0B", "item": "005930", "values": {"10": "-70100", "11": "-400", "12": "-0.57", "13": "1000", "15": "-10", "20": "101500", "228": "98.5"}},
        {"type": "0D", "item": "005930", "values": {"41": "70200", "61": "300", "51": "70100", "71": "500", "121": "9000", "125": "8000", "21": "101501"}},
        {"type": "00", "item": "005930", "values": {"9201": "계좌번호"}},  # 주문체결(계좌) — 버려야 한다
        {"type": "04", "item": "", "values": {"9201": "계좌"}},
    ]}
    ev = km.parse_real_message(msg)
    assert [e["type"] for e in ev] == ["trade", "orderbook"]
    assert ev[0]["price"] == 70100 and ev[0]["change"] == -400 and ev[0]["direction"] == "DOWN" and ev[0]["time"] == "10:15:00"
    assert ev[1]["asks"] == [{"price": 70200, "qty": 300}] and ev[1]["total_bid"] == 8000
    assert "9201" not in json.dumps(ev)


def test_summarize_flows():
    inv = [{"individual": 1, "foreign": -2, "institution": 3}] * 25
    s = summarize_flows(inv, [{"net_amt": 10}] * 3)
    assert s["d5"] == {"individual": 5, "foreign": -10, "institution": 15, "program_amt": 30}
    assert s["d20"]["foreign"] == -40 and s["d20"]["program_amt"] == 30


# ───────── 조회 전용 차단 ─────────

async def test_account_tr_is_blocked_before_any_call():
    with respx.mock(assert_all_called=False) as mock:
        route = mock.post(url__startswith=KW)
        with pytest.raises(kiwoom_client.KiwoomBlocked):
            await kiwoom_client._post("/api/dostk/acnt", "kt00018", {})
        with pytest.raises(kiwoom_client.KiwoomBlocked):
            await kiwoom_client._post("/api/dostk/ordr", "kt10000", {})
        assert route.call_count == 0
    assert not any(a.startswith("kt") for a in kiwoom_client.ALLOWED_API_IDS)


# ───────── API ─────────

@pytest.fixture
def client(monkeypatch):
    monkeypatch.setenv("DART_API_KEY", "k")
    monkeypatch.setenv("KIWOOM_APP_KEY", "ak")
    monkeypatch.setenv("KIWOOM_SECRET_KEY", "sk")
    monkeypatch.setenv("APP_TOKEN", "")
    get_settings.cache_clear()
    cache.clear()
    kiwoom_token.token = None
    monkeypatch.setattr(rate_limiter, "min_interval", 0.0)
    monkeypatch.setattr(rate_limiter, "penalize", lambda s: None)
    corp_index.load(parse_corp_code_xml((FX / "corpCode.xml").read_bytes()))
    yield TestClient(create_app(start_background=False))
    get_settings.cache_clear()
    kiwoom_token.token = None


def _company():
    respx.get(f"{DART}/company.json").mock(return_value=httpx.Response(200, json={
        "status": "000", "corp_name": "삼성전자", "stock_code": "005930", "stock_name": "삼성전자", "corp_cls": "Y"}))


def _token():
    respx.post(f"{KW}/oauth2/token").mock(return_value=httpx.Response(200, json={"token": "t", "expires_dt": "20991231235959"}))


@respx.mock
def test_candles_minute_from_kiwoom(client):
    _company(); _token()
    route = respx.post(f"{KW}/api/dostk/chart").mock(return_value=httpx.Response(200, json={
        "return_code": 0, "stk_min_pole_chart_qry": [
            {"cntr_tm": f"20261111{9 + i // 60:02d}{i % 60:02d}00", "cur_prc": str(70000 + i), "trde_qty": "1"} for i in range(40)]}))
    body = client.get("/api/v1/corps/00126380/stock/candles?period=m5").json()
    assert body["source"] == "KIWOOM" and body["period"] == "m5" and len(body["candles"]) == 40
    req = json.loads(route.calls.last.request.content)
    assert route.calls.last.request.headers["api-id"] == "ka10080" and req["tic_scope"] == "5"


@respx.mock
def test_candles_rate_limited_then_ok(client):
    _company(); _token()
    ok = httpx.Response(200, json={"return_code": 0, "stk_dt_pole_chart_qry": [
        {"dt": (date.today() - timedelta(days=i)).strftime("%Y%m%d"), "cur_prc": "100", "trde_qty": "1"} for i in range(30)]})
    route = respx.post(f"{KW}/api/dostk/chart").mock(side_effect=[
        httpx.Response(429), httpx.Response(200, json={"return_code": 5, "return_msg": "[1700:허용된 요청 개수를 초과하였습니다]"}), ok])
    body = client.get("/api/v1/corps/00126380/stock/candles?period=D").json()
    assert body["source"] == "KIWOOM" and len(body["candles"]) == 30 and route.call_count == 3


@respx.mock
def test_monthly_candles_fallback_without_kiwoom(client, monkeypatch):
    monkeypatch.setenv("KIWOOM_APP_KEY", "")
    get_settings.cache_clear()
    _company()
    respx.get("https://apis.data.go.kr/1160100/service/GetStockSecuritiesInfoService/getStockPriceInfo").mock(
        return_value=httpx.Response(500))
    today = date.today()
    lines = ",".join(
        f'["{(today - timedelta(days=i)).strftime("%Y%m%d")}", 100, 110, 90, {100 + i}, 10, 0]' for i in range(0, 120))
    respx.get("https://api.finance.naver.com/siseJson.naver").mock(return_value=httpx.Response(200, text=f"[['날짜'],{lines}]"))
    body = client.get("/api/v1/corps/00126380/stock/candles?period=M&count=30").json()
    assert body["source"] == "NAVER" and 3 <= len(body["candles"]) <= 5
    # 분봉은 키움이 없으면 안내
    r = client.get("/api/v1/corps/00126380/stock/candles?period=m1")
    assert r.status_code == 503


@respx.mock
def test_flows_partial_failure_keeps_others(client):
    _company(); _token()

    def stkinfo(request):
        api = request.headers["api-id"]
        if api == "ka10059":
            return httpx.Response(200, json={"return_code": 0, "stk_invsr_orgn": [
                {"dt": "20261111", "ind_invsr": "-10", "frgnr_invsr": "7", "orgn": "3"}]})
        return httpx.Response(200, json={"return_code": 1, "return_msg": "조회 실패"})

    respx.post(f"{KW}/api/dostk/stkinfo").mock(side_effect=stkinfo)
    respx.post(f"{KW}/api/dostk/mrkcond").mock(return_value=httpx.Response(200, json={
        "return_code": 0, "stk_daly_prm_trde_trnsn": [{"dt": "20261111", "prm_netprps_amt": "-50"}]}))
    respx.post(f"{KW}/api/dostk/shsa").mock(return_value=httpx.Response(200, json={
        "return_code": 0, "shrts_trnsn": [{"dt": "20261111", "shrts_qty": "100", "trde_wght": "1.5"}]}))
    body = client.get("/api/v1/corps/00126380/stock/flows").json()
    assert body["investors"][0]["foreign"] == 7 and body["program"][0]["net_amt"] == -50
    assert body["short"][0]["short_qty"] == 100 and body["credit"] == []
    assert any("신용" in w for w in body["warnings"])
    assert body["summary"]["d5"]["individual"] == -10


@respx.mock
def test_rankings_with_disclosure_tags(client):
    _token()
    respx.post(f"{KW}/api/dostk/rkinfo").mock(return_value=httpx.Response(200, json={"return_code": 0, "pred_pre_flu_rt_upper": [
        {"stk_cd": "000660", "stk_nm": "SK하이닉스", "cur_prc": "+200000", "pred_pre": "+20000", "flu_rt": "+11.11", "now_trde_qty": "999"},
        {"stk_cd": "005380", "stk_nm": "현대차", "cur_prc": "+250000", "pred_pre": "+5000", "flu_rt": "+2.04", "now_trde_qty": "10"},
    ]}))
    respx.get(f"{DART}/list.json").mock(return_value=httpx.Response(200, json={"status": "000", "total_page": 1, "list": [
        {"rcept_no": "20261111000123", "rcept_dt": "20261111", "report_nm": "단일판매ㆍ공급계약체결", "stock_code": "000660", "corp_code": "00164779", "flr_nm": "SK하이닉스"},
        {"rcept_no": "20261110000050", "rcept_dt": "20261110", "report_nm": "주요사항보고서(자기주식취득결정)", "stock_code": "000660", "corp_code": "00164779", "flr_nm": "SK하이닉스"},
        {"rcept_no": "20261110000051", "rcept_dt": "20261110", "report_nm": "감사보고서", "stock_code": "", "corp_code": "01234567", "flr_nm": "비상장"},
    ]}))
    body = client.get("/api/v1/market/rankings?kind=rise&market=kospi").json()
    first = body["items"][0]
    assert first["rank"] == 1 and first["corp_code"] == "00164779" and first["tags"] == ["공급계약", "자사주"]
    assert first["disclosures"][0]["title"] == "단일판매ㆍ공급계약체결" and first["volume"] == 999
    assert body["items"][1]["tags"] == [] and body["kind_label"] == "상승률"
    rk = next(c.request for c in respx.calls if c.request.url.path.endswith("rkinfo"))
    assert rk.headers["api-id"] == "ka10027" and json.loads(rk.content)["mrkt_tp"] == "001"


def test_rankings_require_kiwoom(client, monkeypatch):
    monkeypatch.setenv("KIWOOM_APP_KEY", "")
    get_settings.cache_clear()
    r = client.get("/api/v1/market/rankings")
    assert r.status_code == 503 and r.json()["error"]["code"] == "KIWOOM_DISABLED"


# ───────── 실시간 중계 ─────────

class FakeKiwoomWs:
    def __init__(self, incoming: list):
        self.sent: list[dict] = []
        self.incoming: asyncio.Queue = asyncio.Queue()
        for m in incoming:
            self.incoming.put_nowait(m)
        self.closed = False

    async def send(self, raw: str):
        self.sent.append(json.loads(raw))

    async def recv(self):
        return await self.incoming.get()

    async def close(self):
        self.closed = True


async def test_hub_login_reg_ping_real_remove(monkeypatch):
    monkeypatch.setenv("KIWOOM_APP_KEY", "ak")
    monkeypatch.setenv("KIWOOM_SECRET_KEY", "sk")
    get_settings.cache_clear()
    kiwoom_token.token, kiwoom_token.expires_at = "tok", 4102444800
    fake = FakeKiwoomWs([json.dumps({"trnm": "LOGIN", "return_code": 0})])

    async def connect(url):
        assert url == "wss://api.kiwoom.com:10000/api/dostk/websocket"
        return fake

    hub = RealtimeHub(connector=connect)
    got: list[dict] = []

    async def send(m):
        got.append(m)

    await hub.add_client(1, send)
    assert got[0] == {"type": "status", "mode": "kiwoom"}
    assert await hub.subscribe(1, ["005930", "bad!", "005930"]) == ["005930"]
    for _ in range(50):
        await asyncio.sleep(0.01)
        if any(m.get("trnm") == "REG" for m in fake.sent):
            break
    assert fake.sent[0] == {"trnm": "LOGIN", "token": "tok"}
    reg = next(m for m in fake.sent if m["trnm"] == "REG")
    assert reg["data"] == [{"item": ["005930"], "type": ["0B", "0D"]}]
    ping = json.dumps({"trnm": "PING"})
    fake.incoming.put_nowait(ping)
    fake.incoming.put_nowait(json.dumps({"trnm": "REAL", "data": [
        {"type": "0B", "item": "005930", "values": {"10": "+70500", "11": "+100", "12": "+0.14"}},
        {"type": "0B", "item": "000660", "values": {"10": "+1"}},  # 구독하지 않은 종목
    ]}))
    for _ in range(50):
        await asyncio.sleep(0.01)
        if any(m.get("type") == "trade" for m in got):
            break
    assert {"trnm": "PING"} in fake.sent
    trades = [m for m in got if m.get("type") == "trade"]
    assert len(trades) == 1 and trades[0]["price"] == 70500
    await hub.unsubscribe(1, ["005930"])
    assert any(m["trnm"] == "REMOVE" and m["data"][0]["item"] == ["005930"] for m in fake.sent)
    await hub.remove_client(1)
    for _ in range(100):
        await asyncio.sleep(0.05)
        if fake.closed:
            break
    assert fake.closed and hub.refcount == {}
    get_settings.cache_clear()
    kiwoom_token.token = None


async def test_hub_polling_without_kiwoom(monkeypatch):
    monkeypatch.setenv("KIWOOM_APP_KEY", "")
    get_settings.cache_clear()

    async def quote(code):
        return {"price": 1000, "change": -10, "change_rate": -0.99, "direction": "DOWN", "volume": 5, "source": "NAVER"}

    hub = RealtimeHub(connector=None, quote_loader=quote)
    got: list[dict] = []

    async def send(m):
        got.append(m)

    await hub.add_client(7, send)
    assert got[0]["mode"] == "polling"
    await hub.subscribe(7, ["005930"])
    for _ in range(50):
        await asyncio.sleep(0.02)
        if any(m.get("type") == "trade" for m in got):
            break
    t = next(m for m in got if m.get("type") == "trade")
    assert t["code"] == "005930" and t["price"] == 1000 and t["polled"] is True
    await hub.remove_client(7)
    get_settings.cache_clear()


def test_hub_limits_codes_per_client():
    async def run():
        hub = RealtimeHub(connector=None, quote_loader=None)

        async def send(m):
            pass

        hub._ensure_running = lambda: asyncio.sleep(0)  # 연결 시작 없이 개수 제한만 확인
        await hub.add_client(1, send)
        now = await hub.subscribe(1, [f"00000{i}" for i in range(9)])
        assert len(now) == 5 and hub.refcount["000000"] == 1
    asyncio.run(run())


def test_ws_endpoint_requires_app_token(monkeypatch):
    monkeypatch.setenv("APP_TOKEN", "secret")
    get_settings.cache_clear()
    c = TestClient(create_app(start_background=False))
    from starlette.websockets import WebSocketDisconnect
    with pytest.raises(WebSocketDisconnect):
        with c.websocket_connect("/api/v1/realtime") as ws:
            ws.receive_text()
    get_settings.cache_clear()
