from datetime import date

from app.parsers.bond_data import as_list, summarize_outstanding


def item(isin, name, kind, bal, expr, basdt="20261022", rate="2.5"):
    return {"isinCd": isin, "isinCdNm": name, "scrsItmsKcdNm": kind, "bondBal": bal, "bondExprDt": expr,
            "basDt": basdt, "bondSrfcInrt": rate}


def test_groups_and_filters():
    items = [
        item("A", "에스케이하이닉스 300-1", "일반회사채", "100000000000", "20281022"),
        item("B", "에스케이하이닉스 300-2", "일반회사채", "50000000000", "20271022"),
        item("C", "만기지남", "일반회사채", "70000000000", "20200101"),
        item("D", "잔액없음", "일반회사채", "0", "20300101"),
        item("E", "CP 1", "기업어음증권", "30000000000", "20270301", rate="0"),
    ]
    s = summarize_outstanding(items, date(2026, 10, 23))
    assert [x["key"] for x in s] == ["COMMERCIAL_PAPER", "CORP_BOND"]
    bond = s[1]
    assert bond["total"] == 150_000_000_000 and bond["total_display"] == "1,500억원"
    assert [r["label"].split(" · ")[0] for r in bond["rows"]] == ["에스케이하이닉스 300-2", "에스케이하이닉스 300-1"]
    assert "만기 2027.10.22" in bond["rows"][0]["label"] and "2.5%" in bond["rows"][0]["label"]
    assert "%" not in s[0]["rows"][0]["label"]


def test_latest_basdt_per_isin_and_single_dict():
    items = [item("A", "x", "일반회사채", "10", "20281022", basdt="20261001"),
             item("A", "x", "일반회사채", "20", "20281022", basdt="20261020")]
    assert summarize_outstanding(items, date(2026, 10, 23))[0]["total"] == 20
    assert as_list({"isinCd": "A"}) == [{"isinCd": "A"}] and as_list("") == [] and as_list(None) == []
