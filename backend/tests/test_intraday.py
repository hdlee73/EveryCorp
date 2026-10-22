from app.parsers import price as pp


def test_kiwoom_minutes_sorted_and_signs_stripped():
    rows = [
        {"cntr_tm": "20261022090200", "cur_prc": "-71100", "trde_qty": "1200"},
        {"cntr_tm": "20261022090100", "cur_prc": "+71000", "trde_qty": "3400"},
        {"cntr_tm": "20261021153000", "cur_prc": "70500", "trde_qty": "10"},
    ]
    pts = pp.parse_kiwoom_minutes(rows)
    assert [(p["date"], p["time"], p["price"]) for p in pts] == [
        ("2026-10-21", "15:30", 70500), ("2026-10-22", "09:01", 71000), ("2026-10-22", "09:02", 71100)]
    assert [p["time"] for p in pp.latest_session(pts)] == ["09:01", "09:02"]


def test_naver_fchart_minutes():
    xml = ('<protocol><chartdata><item data="202610220901|71000|71100|70900|71050|1234" />'
           '<item data="202610220902|71050|71200|71000|71100|2345" /></chartdata></protocol>')
    pts = pp.parse_naver_fchart_minutes(xml)
    assert pts[1] == {"date": "2026-10-22", "time": "09:02", "price": 71100, "volume": 2345}
