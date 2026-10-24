from app.parsers import stake as sp


def test_parse_major_sorts_latest_first_and_handles_blanks():
    rows = [
        {"rcept_no": "20260101000001", "rcept_dt": "2026-01-01", "repror": "가나자산운용", "stkqy": "1,200,000", "stkqy_irds": "-", "stkrt": "5.12", "stkrt_irds": "0.80", "report_tp": "일반", "report_resn": "신규보고"},
        {"rcept_no": "20260301000002", "rcept_dt": "2026-03-01", "repror": "다라펀드", "stkqy": "900,000", "stkqy_irds": "-100,000", "stkrt": "-", "stkrt_irds": "-0.5"},
    ]
    out = sp.parse_major(rows)
    assert [o["reporter"] for o in out] == ["다라펀드", "가나자산운용"]
    assert out[0]["shares_change"] == -100000 and out[0]["ratio"] is None and out[0]["ratio_change"] == -0.5
    assert out[1]["shares"] == 1200000 and out[1]["shares_change"] is None and out[1]["date"] == "20260101"


def test_parse_insider():
    rows = [{"rcept_no": "1", "rcept_dt": "20260210", "repror": "홍길동", "isu_exctv_ofcps": "대표이사", "isu_main_shrholdr": "-",
             "sp_stock_lmp_cnt": "52,000", "sp_stock_lmp_irds_cnt": "12,000", "sp_stock_lmp_rate": "2.31", "sp_stock_lmp_irds_rate": "0.53"}]
    o = sp.parse_insider(rows)[0]
    assert o["position"] == "대표이사" and o["shares_change"] == 12000 and o["ratio"] == 2.31
