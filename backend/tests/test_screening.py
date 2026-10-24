from app.services import screening_service as sc


def test_opinion_classification():
    assert sc.is_clean_opinion("적정") and sc.is_clean_opinion("적정의견")
    for o in ("한정", "부적정", "의견거절", "부적정의견"):
        assert sc.is_flagged_opinion(o)
    assert not sc.is_flagged_opinion("적정") and not sc.is_flagged_opinion("-") and not sc.is_flagged_opinion("")


def _row(cc, fs, acct, amt, dt="2025.12.31 현재"):
    return {"corp_code": cc, "fs_div": fs, "sj_div": "BS", "account_nm": acct, "thstrm_amount": amt, "thstrm_dt": dt}


def test_parse_multi_acnt_prefers_consolidated():
    rows = [
        _row("1", "OFS", "자본총계", "10"), _row("1", "OFS", "자본금", "50"),
        _row("1", "CFS", "자본총계", "-5"), _row("1", "CFS", "자본금", "50"),
        _row("2", "OFS", "자산총계", "1,000"), _row("2", "OFS", "자본총계", "-"),
        {"corp_code": "1", "fs_div": "CFS", "sj_div": "IS", "account_nm": "매출액", "thstrm_amount": "9"},
    ]
    got = sc.parse_multi_acnt(rows)
    assert got["1"]["fs_div"] == "CFS" and got["1"]["equity"] == -5 and got["1"]["capital"] == 50
    assert got["1"]["period"] == "2025-12"
    assert got["2"]["assets"] == 1000 and got["2"]["equity"] is None


def test_impairment_kind():
    assert sc.impairment_kind(-1, 50) == "완전잠식"
    assert sc.impairment_kind(20, 50) == "부분잠식"
    assert sc.impairment_kind(50, 50) is None
    assert sc.impairment_kind(None, 50) is None and sc.impairment_kind(10, 0) is None


def test_current_term_items_drops_prior_terms():
    rows = [
        {"term": "제 12 기 (당기)", "opinion": "한정의견", "auditor": "성현회계법인", "scope": ""},
        {"term": "제 11 기 (전기)", "opinion": "한정의견", "auditor": "성현회계법인", "scope": ""},
        {"term": "제 10 기 (전전기)", "opinion": "한정의견", "auditor": "성현회계법인", "scope": ""},
    ]
    assert len(sc.current_term_items(rows)) == 1
    two = [{"term": "당기", "opinion": "의견거절", "auditor": "신한", "scope": "연결"},
           {"term": "당기", "opinion": "의견거절", "auditor": "한울", "scope": "별도"},
           {"term": "전기", "opinion": "적정", "auditor": "한울", "scope": ""}]
    assert [i["auditor"] for i in sc.current_term_items(two)] == ["신한", "한울"]
    # 당기 표기가 없으면 첫 기수만
    plain = [{"term": "A", "opinion": "적정", "auditor": "x", "scope": ""}, {"term": "B", "opinion": "적정", "auditor": "y", "scope": ""}]
    assert [i["auditor"] for i in sc.current_term_items(plain)] == ["x"]
