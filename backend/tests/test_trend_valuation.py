from app.parsers.dart_overview import _valuation, build_trend
from app.parsers.price import year_end_closes


def rows(eps, ni_parent, eq_parent, year_tag="thstrm_amount"):
    def r(sj, aid, nm, v):
        return {"sj_div": sj, "account_id": aid, "account_nm": nm, year_tag: str(v), "frmtrm_amount": str(v), "bfefrmtrm_amount": str(v)}
    return [
        r("IS", "ifrs-full_Revenue", "매출액", 1000), r("IS", "dart_OperatingIncomeLoss", "영업이익", 100),
        r("IS", "ifrs-full_ProfitLoss", "당기순이익", 120), r("IS", "ifrs-full_BasicEarningsLossPerShare", "기본주당이익", eps),
        r("IS", "ifrs-full_ProfitLossAttributableToOwnersOfParent", "지배기업 소유주지분", ni_parent),
        r("BS", "ifrs-full_Assets", "자산총계", 5000), r("BS", "ifrs-full_Liabilities", "부채총계", 1000),
        r("BS", "ifrs-full_Equity", "자본총계", 4000), r("BS", "ifrs-full_EquityAttributableToOwnersOfParent", "지배기업 소유주지분", eq_parent),
    ]


def test_valuation_math():
    # 주식수 = 100/5 = 20, BPS = 400/20 = 20, 종가 100 → PER 20, PBR 5
    per, pbr = _valuation({"eps": 5, "ni_parent": 100, "equity_parent": 400}, 100)
    assert per == 20.0 and pbr == 5.0
    assert _valuation({"eps": -3, "ni_parent": -10, "equity_parent": 400}, 100) == (None, None)
    assert _valuation({"eps": 5}, None) == (None, None)


def test_trend_includes_eps_per_pbr():
    t = build_trend(rows(5, 100, 400), 2025, None, None, None, closes={2023: 100, 2024: 100, 2025: 100})
    keys = {s["key"]: s["values"] for s in t["series"]}
    assert keys["eps"] == [5, 5, 5] and keys["per"] == [20.0] * 3 and keys["pbr"] == [5.0] * 3


def test_trend_without_closes_has_no_valuation_series():
    t = build_trend(rows(5, 100, 400), 2025, None, None, None)
    assert "per" not in {s["key"] for s in t["series"]} and "eps" in {s["key"] for s in t["series"]}


def test_year_end_closes():
    rows_ = [{"date": "2024-12-30", "close": 90}, {"date": "2024-12-31", "close": 95}, {"date": "2025-03-01", "close": 100}]
    assert year_end_closes(rows_, [2024, 2025, 2026]) == {2024: 95, 2025: 100}
