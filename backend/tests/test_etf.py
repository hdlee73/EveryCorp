from app.parsers.etf import parse_etf_daily, parse_etf_info, parse_etf_list, search_etfs

LIST_ROWS = [
    {"stk_cd": "069500", "stk_nm": "KODEX 200", "close_pric": "-87045", "pre_rt": "-0.03", "nav": "+87206.45", "trde_qty": "100", "trace_idex_nm": "KOSPI200"},
    {"stk_cd": "153270", "stk_nm": "KIWOOM 코스피100", "close_pric": "-66600", "pre_rt": "-0.03", "nav": "+67646.03", "trde_qty": "2354", "trace_idex_nm": "KOSPI100"},
    {"stk_cd": "", "stk_nm": "빈 행"},
]


def test_list_parses_and_skips_blank():
    items = parse_etf_list(LIST_ROWS)
    assert [i["code"] for i in items] == ["069500", "153270"]
    assert items[0]["price"] == 87045 and items[0]["nav"] == 87206.45 and items[0]["change_rate"] == -0.03


def test_search_by_name_code_and_index():
    items = parse_etf_list(LIST_ROWS)
    assert [i["code"] for i in search_etfs(items, "kodex")] == ["069500"]
    assert [i["code"] for i in search_etfs(items, "코스피 100")] == ["153270"]
    assert [i["code"] for i in search_etfs(items, "0695")] == ["069500"]
    assert [i["code"] for i in search_etfs(items, "KOSPI200")] == ["069500"]
    assert search_etfs(items, "  ") == []


def test_info():
    d = {"stk_nm": "KODEX 200", "etfobjt_idex_nm": "KOSPI200", "wonju_pric": "10", "etftxon_type": "비과세"}
    assert parse_etf_info(d) == {"name": "KODEX 200", "index_name": "KOSPI200", "par_value": 10, "tax_type": "비과세"}


def test_daily_sorted_latest_first_and_tracking_scaled():
    rows = [
        {"cntr_dt": "20261022", "cur_prc": "-86525", "pre_rt": "-1.20", "trde_qty": "5", "nav": "+86400.10", "navetfdispty_rt": "+0.14", "trace_eor_rt": "0.72"},
        {"cntr_dt": "20261023", "cur_prc": "+87045", "pre_rt": "+0.60", "trde_qty": "31053159", "nav": "+87206.45", "navetfdispty_rt": "-0.19", "trace_eor_rt": "69"},
        {"cntr_dt": "", "cur_prc": "1"},
    ]
    d = parse_etf_daily(rows)
    assert [r["date"] for r in d] == ["2026-10-23", "2026-10-22"]
    assert d[0]["close"] == 87045 and d[0]["disparity"] == -0.19 and d[0]["tracking_error"] == 0.69
    assert d[1]["tracking_error"] == 0.72
