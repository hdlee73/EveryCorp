from app.parsers import etf_pdf as xp

CORP = {"005930": ("00126380", "삼성전자"), "000660": ("00164779", "SK하이닉스"), "035420": ("00266961", "NAVER")}
ETFS = {"069500": "KODEX 200", "114260": "KODEX 국고채3년", "360750": "TIGER 미국S&P500", "999990": "KODEX 순환 테스트"}


def m():
    return xp.Matcher(CORP, ETFS)


def test_isin_roundtrip():
    assert xp.kr_isin("069500") == "KR7069500007"
    assert xp.kr_isin("005930") == "KR7005930003"
    assert xp.isin_check_digit("US037833100") == 5
    assert xp.short_of("KR7005930003") == "005930"
    assert xp.short_of("A005930") == "005930"
    assert xp.short_of("US0378331005") == ""


def test_manager_from_krx_then_brand():
    assert xp.manager_of("KODEX 200", "삼성자산운용") == ("삼성자산운용", "krx")
    assert xp.manager_of("KBSTAR 200") == ("KB자산운용", "brand")
    assert xp.manager_of("RISE 200")[0] == "KB자산운용"
    assert xp.manager_of("이상한ETF") == ("", "")
    assert xp.manager_key("삼성자산운용(주)") == xp.manager_key("삼성자산운용")


def test_matching_correction():
    mt = m()
    assert mt.match("005930", "삼성전자")["kind"] == "STOCK"
    isin = mt.match("KR7000660001", "SK하이닉스")
    assert isin["kind"] == "STOCK" and isin["match"] == "isin" and isin["corp_code"] == "00164779"
    pref = mt.match("005935", "삼성전자우")
    assert pref["kind"] == "STOCK" and pref["match"] == "pref" and pref["stock_code"] == "005935" and pref["corp_code"] == "00126380"
    # 코드 없이 이름만(띄어쓰기·(주) 차이)
    byname = mt.match("", "(주) NAVER")
    assert byname["kind"] == "STOCK" and byname["match"] == "name"
    # 채권형 ETF 이름이 '국고채'여도 코드로 ETF 가 먼저 확정된다
    assert mt.match("114260", "KODEX 국고채3년")["kind"] == "ETF"
    assert mt.match("KRD010010001", "원화예금")["kind"] == "CASH"
    assert mt.match("", "설정현금액")["kind"] == "CASH"
    assert mt.match("KR4101V30008", "코스피200 F 202612")["kind"] == "DERIVATIVE"
    assert mt.match("KR103502GE97", "국고02750-2706")["kind"] == "BOND"
    assert mt.match("US0378331005", "APPLE INC")["kind"] == "FOREIGN"
    assert mt.match("123456", "없는회사")["kind"] == "UNMATCHED"


def test_parse_krx_pdf_and_fill_weights():
    data = {"output": [
        {"COMPST_ISU_CD": "005930", "COMPST_ISU_NM": "삼성전자", "COMPST_ISU_CU1_SHRS": "1,000", "VALU_AMT": "60,000,000", "COMPST_AMT": "60,000,000", "COMPST_RTO": "-"},
        {"COMPST_ISU_CD": "000660", "COMPST_ISU_NM": "SK하이닉스", "COMPST_ISU_CU1_SHRS": "200", "VALU_AMT": "40,000,000", "COMPST_AMT": "", "COMPST_RTO": ""},
    ]}
    rows = xp.fill_weights(xp.parse_krx_pdf(data))
    assert rows[0]["qty"] == 1000 and rows[0]["value"] == 60_000_000
    assert [r["weight"] for r in rows] == [60.0, 40.0]


def test_parse_krx_daily_and_basic():
    daily = xp.parse_krx_daily_all({"OutBlock_1": [{"BAS_DD": "20261111", "ISU_CD": "069500", "ISU_NM": "KODEX 200", "NAV": "87,206.45", "INVSTASST_NETASST_TOTAMT": "8,720,645,000,000", "LIST_SHRS": "100,000,000", "TDD_CLSPRC": "87,045"}]})
    assert daily["069500"]["net_assets"] == 8_720_645_000_000 and daily["069500"]["listed_shares"] == 100_000_000
    basic = xp.parse_krx_basic({"output": [{"ISU_CD": "KR7069500007", "ISU_SRT_CD": "069500", "ISU_ABBRV": "KODEX 200", "COM_ABBRV": "삼성자산운용", "ETF_TOT_FEE": "0.150", "LIST_DD": "2002/10/14"}]})
    assert basic["069500"]["manager"] == "삼성자산운용" and basic["069500"]["total_fee"] == 0.15 and basic["069500"]["listed_date"] == "2002-10-14"


def test_parse_naver_holdings_nested():
    data = {"result": {"etfTop10MajorConstituentAssets": [{"itemCode": "005930", "itemName": "삼성전자", "etfWeight": "25.3%"}]}, "totalFee": 0.15}
    rows = xp.parse_naver_holdings(data)
    assert rows[0]["weight"] == 25.3 and rows[0]["code"] == "005930"
    assert xp.parse_naver_fee(data) == 0.15


def _h(rows):
    return xp.match_holdings([{"code": c, "name": n, "weight": w} for c, n, w in rows], m())


def test_overlap_excludes_cash_and_sums_min():
    a = _h([("005930", "삼성전자", 30), ("000660", "SK하이닉스", 20), ("", "원화예금", 50)])
    b = _h([("KR7005930003", "삼성전자", 10), ("000660", "SK하이닉스", 25), ("035420", "NAVER", 65)])
    ov = xp.overlap(a, b)
    assert ov["overlap_weight"] == 30.0 and ov["common_count"] == 2
    assert ov["common"][0]["name"] == "SK하이닉스"


def test_own_vs_other_etf_weight():
    h = _h([("069500", "KODEX 200", 40), ("360750", "TIGER 미국S&P500", 35), ("005930", "삼성전자", 25)])
    lookup = lambda code, name="": xp.manager_of(name or ETFS.get(code, ""))[0]  # noqa: E731
    s = xp.etf_holdings_summary(h, "삼성자산운용", lookup)
    assert s["own_weight"] == 40 and s["other_weight"] == 35
    assert s["own"][0]["code"] == "069500"


def test_cycles_and_holders():
    edges = {"A": ["B"], "B": ["C", "A"], "C": ["A"]}
    cycles = xp.find_cycles("A", edges)
    assert ["A", "B", "A"] in cycles and ["A", "B", "C", "A"] in cycles
    assert xp.find_cycles("A", {"A": ["B"], "B": []}) == []
    store = {
        "999990": xp.compact(_h([("069500", "KODEX 200", 12.5)])),
        "111110": xp.compact(_h([("069500", "KODEX 200", 3)])),
        "069500": xp.compact(_h([("005930", "삼성전자", 30)])),
    }
    assert store["999990"]["etfs"] == ["069500"]
    held = xp.holders_of("E:069500", store)
    assert [x["code"] for x in held] == ["999990", "111110"]
    sim = xp.most_similar("999990", store)
    assert sim and sim[0]["code"] == "111110" and sim[0]["overlap_weight"] == 3


# ───────── 서비스(원천 호출은 가짜로 바꿔 끼움) ─────────
import pytest  # noqa: E402

from app.cache import cache  # noqa: E402
from app.services import etf_portfolio_service as ps  # noqa: E402
from app.services.corp_index import CorpEntry, corp_index  # noqa: E402

PDFS = {
    xp.kr_isin("999990"): [  # 자사 ETF(069500) 와 타사 ETF(360750), 주식 하나, 미매칭 하나
        {"COMPST_ISU_CD": "069500", "COMPST_ISU_NM": "KODEX 200", "COMPST_RTO": "40"},
        {"COMPST_ISU_CD": "360750", "COMPST_ISU_NM": "TIGER 미국S&P500", "COMPST_RTO": "30"},
        {"COMPST_ISU_CD": "005930", "COMPST_ISU_NM": "삼성전자", "COMPST_RTO": "20"},
        {"COMPST_ISU_CD": "", "COMPST_ISU_NM": "알수없는자산", "COMPST_RTO": "10"},
    ],
    xp.kr_isin("069500"): [  # 069500 이 다시 999990 을 담아 순환
        {"COMPST_ISU_CD": "999990", "COMPST_ISU_NM": "KODEX 순환 테스트", "COMPST_RTO": "5"},
        {"COMPST_ISU_CD": "005930", "COMPST_ISU_NM": "삼성전자", "COMPST_RTO": "95"},
    ],
    xp.kr_isin("360750"): [{"COMPST_ISU_CD": "US0378331005", "COMPST_ISU_NM": "APPLE INC", "COMPST_RTO": "100"}],
}


@pytest.fixture
def fake_sources(monkeypatch, tmp_path):
    cache.clear()
    corp_index.load([CorpEntry("00126380", "삼성전자", "", "005930", "20260101")])
    ps.store.items.clear()
    ps._matcher_cache.update(m=None, at=0.0, n=0)
    ps._krx_pdf_down_until = 0.0
    ps._basic_failed_at = 0.0
    monkeypatch.setattr(ps, "STORE_PATH", str(tmp_path / "etf_pdf.json"))

    async def krx_data(form):
        if form["bld"].endswith("05001"):
            return {"output": PDFS.get(form["isuCd"], [])}
        if form["bld"].endswith("04601"):
            return {"output": [{"ISU_CD": xp.kr_isin(c), "ISU_SRT_CD": c, "ISU_ABBRV": n, "COM_ABBRV": xp.manager_of(n)[0]} for c, n in ETFS.items()]}
        return {"output": []}

    monkeypatch.setattr(ps, "_krx_data", krx_data)
    monkeypatch.setattr(ps, "kiwoom_enabled", lambda: False)
    monkeypatch.setattr(ps, "krx_enabled", lambda: False)
    yield
    ps.store.items.clear()


async def test_portfolio_and_relations(fake_sources):
    p = await ps.get_portfolio("999990")
    assert p["count"] == 4 and p["unmatched_count"] == 1 and not p["partial"]
    kinds = {h["name"]: h["kind"] for h in p["holdings"]}
    assert kinds["삼성전자"] == "STOCK" and kinds["KODEX 200"] == "ETF" and kinds["알수없는자산"] == "UNMATCHED"
    assert p["donut"][0]["weight"] == 40

    r = await ps.get_relations("999990")
    assert r["manager"] == "삼성자산운용"
    assert r["etf_holdings"]["own_weight"] == 40 and r["etf_holdings"]["other_weight"] == 30
    assert [c["code"] for c in r["cycles"][0]] == ["999990", "069500", "999990"]
    assert [x["code"] for x in r["mutual"]] == ["069500"]
    assert r["held_by"][0]["code"] == "069500" and r["held_by"][0]["own"] is True

    ov = await ps.get_overlap("999990", "069500")
    assert ov["common_count"] == 1 and ov["overlap_weight"] == 20


async def test_krx_down_falls_back_to_naver(fake_sources, monkeypatch):
    async def down(form):
        raise ps.UpstreamError("KRX", "로그인 요구")

    async def naver(code):
        return {"etfTop10MajorConstituentAssets": [{"itemCode": "005930", "itemName": "삼성전자", "etfWeight": 30.0}]}

    monkeypatch.setattr(ps, "_krx_data", down)
    monkeypatch.setattr(ps, "_naver", naver)
    p = await ps.get_portfolio("069500")
    assert p["partial"] and p["source"] == ps.SOURCE_NAVER and p["holdings"][0]["corp_code"] == "00126380"
