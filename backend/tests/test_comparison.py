from app.parsers.dart_overview import build_comparison_tables, cumulative_months


def _row(sj, nm, aid, th, fr=None, bf=None, add=None):
    return {"sj_div": sj, "account_nm": nm, "account_id": aid, "thstrm_amount": th, "frmtrm_amount": fr,
            "bfefrmtrm_amount": bf, "thstrm_add_amount": add}


QUARTER = [
    _row("BS", "자산총계", "ifrs-full_Assets", "1,000", "900"),
    _row("BS", "부채총계", "ifrs-full_Liabilities", "400", "300"),
    _row("BS", "자본총계", "ifrs-full_Equity", "600", "600"),
    _row("IS", "매출액", "ifrs-full_Revenue", "50", add="100"),
    _row("IS", "영업이익", "dart_OperatingIncomeLoss", "10", add="20"),
    _row("IS", "당기순이익", "ifrs-full_ProfitLoss", "8", add="15"),
]
ANNUAL = [
    _row("BS", "자산총계", "ifrs-full_Assets", "900", "800", "700"),
    _row("BS", "부채총계", "ifrs-full_Liabilities", "300", "300", "200"),
    _row("BS", "자본총계", "ifrs-full_Equity", "600", "500", "500"),
    _row("IS", "매출액", "ifrs-full_Revenue", "400", "350", "300"),
    _row("IS", "영업이익", "dart_OperatingIncomeLoss", "40", "30", "20"),
    _row("IS", "당기순이익", "ifrs-full_ProfitLoss", "30", "25", "15"),
]


def test_cumulative_months():
    assert cumulative_months(6, 12) == 6
    assert cumulative_months(6, 3) == 3
    assert cumulative_months(12, 3) == 9
    assert cumulative_months(3, 12) == 3


def test_tables_with_half_year_report():
    t = build_comparison_tables(QUARTER, "반기", 2026, 6, ANNUAL, 2025, 12, None)
    bal = t["balance"]
    assert [c["label"] for c in bal["columns"]] == ["2026.06", "2025.12", "2024.12", "2023.12"]
    assert bal["columns"][0]["sub"] == "반기말"
    assert bal["rows"][0]["values"] == [1000, 900, 800, 700]
    assert bal["rows"][3]["values"] == [66.7, 50.0, 60.0, 40.0]      # 부채비율 = 부채/자본
    inc = t["income"]
    assert [c["label"] for c in inc["columns"]] == ["분기손익", "누적손익", "2025년 연간", "2024년 연간", "2023년 연간"]
    assert inc["columns"][0]["sub"] == "2026.04~06" and inc["columns"][1]["sub"] == "2026.01~06"
    assert inc["rows"][0]["values"] == [50, 100, 400, 350, 300]
    assert inc["rows"][2]["values"] == [8, 15, 30, 25, 15]


def test_tables_when_latest_is_annual():
    t = build_comparison_tables(ANNUAL, "사업", 2025, 12, ANNUAL, 2025, 12, None)
    assert [c["label"] for c in t["balance"]["columns"]] == ["2025.12", "2024.12", "2023.12"]
    assert [c["label"] for c in t["income"]["columns"]] == ["2025년 연간", "2024년 연간", "2023년 연간"]


def test_first_quarter_cumulative_falls_back_to_quarter_amount():
    q1 = [dict(r, thstrm_add_amount=None) for r in QUARTER]
    t = build_comparison_tables(q1, "분기", 2026, 3, ANNUAL, 2025, 12, None)
    assert t["income"]["rows"][0]["values"][:2] == [50, 50]


def test_trend_five_years_merges_two_reports():
    from app.parsers.dart_overview import build_trend
    recent = [
        _row("BS", "자산총계", "ifrs-full_Assets", "900", "800", "700"),
        _row("BS", "부채총계", "ifrs-full_Liabilities", "300", "300", "200"),
        _row("BS", "자본총계", "ifrs-full_Equity", "600", "500", "500"),
        _row("IS", "매출액", "ifrs-full_Revenue", "400", "350", "300"),
        _row("IS", "영업이익", "dart_OperatingIncomeLoss", "40", "30", "20"),
        _row("IS", "당기순이익", "ifrs-full_ProfitLoss", "30", "25", "15"),
    ]
    older = [
        _row("BS", "자산총계", "ifrs-full_Assets", "700", "600", "500"),
        _row("BS", "부채총계", "ifrs-full_Liabilities", "200", "200", "100"),
        _row("BS", "자본총계", "ifrs-full_Equity", "500", "400", "400"),
        _row("IS", "매출액", "ifrs-full_Revenue", "999", "250", "200"),   # 2023 값은 최근 보고서(300)가 우선
        _row("IS", "영업이익", "dart_OperatingIncomeLoss", "9", "10", "5"),
        _row("IS", "당기순이익", "ifrs-full_ProfitLoss", "9", "8", "3"),
    ]
    t = build_trend(recent, 2025, older, 2023, None)
    assert t["years"] == [2021, 2022, 2023, 2024, 2025]
    by = {s["key"]: s["values"] for s in t["series"]}
    assert by["revenue"] == [200, 250, 300, 350, 400]
    assert by["debt_ratio"][-1] == 50.0 and by["debt_ratio"][0] == 25.0


def test_trend_none_when_single_year_only():
    from app.parsers.dart_overview import build_trend
    assert build_trend(None, None, None, None) is None
