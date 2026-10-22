from app.parsers.dart_shareholders import summarize_changes, summarize_holders


def test_holders_and_total():
    rows = [
        {"nm": "이재용", "relate": "본인", "stock_knd": "의결권 있는 주식", "bsis_posesn_stock_co": "97,414,196",
         "bsis_posesn_stock_qota_rt": "1.63", "trmend_posesn_stock_co": "97,414,196", "trmend_posesn_stock_qota_rt": "1.63", "rm": "-"},
        {"nm": "삼성생명보험", "relate": "특수관계인", "stock_knd": "의결권 있는 주식", "bsis_posesn_stock_co": "508,157,148",
         "bsis_posesn_stock_qota_rt": "8.51", "trmend_posesn_stock_co": "508,157,148", "trmend_posesn_stock_qota_rt": "8.51", "rm": "-"},
        {"nm": "계", "relate": "-", "stock_knd": "-", "bsis_posesn_stock_co": "1,000", "bsis_posesn_stock_qota_rt": "20.0",
         "trmend_posesn_stock_co": "1,000", "trmend_posesn_stock_qota_rt": "20.0", "rm": "-"},
    ]
    s = summarize_holders(rows)
    assert [h["name"] for h in s["holders"]] == ["이재용", "삼성생명보험"]
    assert s["holders"][0]["end_shares"] == 97_414_196 and s["holders"][0]["note"] == ""
    assert s["total"]["end_ratio"] == 20.0
    assert s["largest"] == "삼성생명보험"


def test_changes_skip_blank_names():
    rows = [
        {"change_on": "2020.10.25", "mxmm_shrholdr_nm": "이건희", "posesn_stock_co": "1", "qota_rt": "0.5", "change_cause": "별세", "rm": "-"},
        {"change_on": "-", "mxmm_shrholdr_nm": "-", "posesn_stock_co": "-", "qota_rt": "-", "change_cause": "-", "rm": "-"},
    ]
    c = summarize_changes(rows)
    assert len(c) == 1 and c[0]["cause"] == "별세" and c[0]["ratio"] == 0.5
