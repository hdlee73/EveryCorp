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



def test_convertible_item_prefers_report_balance():
    rec = {"bonds": [
        {"kind": "CB", "tm": "3", "issued": "20230301", "maturity": "20280301", "amount": 10_000_000_000},
        {"kind": "BW", "tm": "1", "issued": "20200101", "maturity": "20250101", "amount": 5_000_000_000},  # 만기 지남
        {"kind": "CB", "tm": "4", "issued": "20260801", "maturity": "20310801", "amount": 3_000_000_000},  # 보고서 이후 납입
    ], "report": {"CB": {"total": 7_000_000_000, "base_date": "20260630", "rows": [
        {"name": "3회차 CB", "tm": "3회", "issued": "20230301", "maturity": "20280301", "amount": 7_000_000_000}]},
        "BW": {"total": 0, "base_date": "20260630", "rows": []}, "period": "2026-06", "rcp_no": "20260814000001"}}
    it = sc.convertible_item(rec, "20261024")
    assert it["source"] == "정기보고서" and it["base_date"] == "20260630"
    assert it["cb_amount"] == 10_000_000_000 and it["bw_amount"] == 0  # 보고서 7십억 + 이후 발행 3십억
    assert [(b["tm"], b["basis"]) for b in it["bonds"]] == [("3회", "정기보고서"), ("4", "발행결정 추정")]
    assert it["next_maturity"] == "20280301" and it["count"] == 2


def test_convertible_item_falls_back_to_decisions():
    rec = {"bonds": [{"kind": "BW", "tm": "1", "issued": "20260101", "maturity": "20300101", "amount": 2_000_000_000}], "report": None}
    it = sc.convertible_item(rec, "20261024")
    assert it["source"] == "발행결정 추정" and it["bw_amount"] == 2_000_000_000
    assert sc.convertible_item({"bonds": [], "report": None}, "20261024") is None


def test_job_pauses_after_repeated_failures():
    import asyncio
    import time

    key = "t_breaker"
    sc._cooldown.pop(key, None)

    async def failing():
        for _ in range(sc._MAX_FAILS):
            sc._note(key, False)

    async def run():
        sc._ensure_job(key, failing)
        await sc._jobs[key]
        assert sc._cooldown[key] > time.time()
        started = []

        async def again():
            started.append(1)

        sc._ensure_job(key, again)  # 쉬는 동안은 다시 시작하지 않는다
        assert started == []

    asyncio.run(run())
    sc._cooldown.pop(key, None)


def test_collection_only_starts_on_request(tmp_path, monkeypatch):
    import asyncio

    monkeypatch.setattr(sc, "_dir", lambda: str(tmp_path))
    started = []

    async def fake_collect(key, year):
        started.append(key)

    monkeypatch.setattr(sc, "_collect_convertible", fake_collect)
    sc._jobs.pop("cbbw_2099", None)

    async def run():
        r = await sc.convertible_bonds(2099)
        assert r["status"] == "empty" and started == []
        await sc.convertible_bonds(2099, start=True)
        await asyncio.sleep(0.05)
        assert started == ["cbbw_2099"]

    asyncio.run(run())


def test_auditor_in_text_ignores_registration_and_spaces():
    assert sc.auditor_in_text("정안회계법인", "독립된 감사인의 감사보고서 ... 정안 회계법인 대표이사 홍길동")
    assert sc.auditor_in_text("동진공인회계사감사반(제387호)", "동진공인회계사 감사반")
    assert not sc.auditor_in_text("서현회계법인", "정안회계법인 대표이사")
    assert sc.auditor_in_text("", "아무 글")


def test_current_term_uses_latest_term_number():
    rows = [
        {"term": "제30기\n(당기)", "auditor": "정안회계법인", "opinion": "의견거절", "scope": ""},
        {"term": "제29기\n(당기)", "auditor": "서현회계법인", "opinion": "의견거절", "scope": ""},
        {"term": "제28기\n(전기)", "auditor": "서현회계법인", "opinion": "적정의견", "scope": ""},
    ]
    assert [i["auditor"] for i in sc.current_term_items(rows)] == ["정안회계법인"]


def test_convertible_item_fixes_wrong_report_unit():
    rec = {"bonds": [{"kind": "CB", "tm": "1", "issued": "20250101", "maturity": "20290101", "amount": 1_500_000_000}],
           "report": {"CB": {"total": 150_000_000 * 100_000_000, "base_date": "20260630", "rows": [
               {"name": "1회 CB", "tm": "1회", "issued": "20250101", "maturity": "20290101", "amount": 150_000_000 * 100_000_000}]},
                      "BW": None, "period": "2026-06", "rcp_no": "1"}}
    it = sc.convertible_item(rec, "20261024")
    assert it["cb_amount"] == 150_000_000  # 억원으로 잘못 곱한 값을 되돌림(발행 15억 이하)

def test_capital_collection_retries_failed_batches(tmp_path, monkeypatch):
    import asyncio

    monkeypatch.setattr(sc, "_dir", lambda: str(tmp_path))
    monkeypatch.setattr(sc, "_MULTI_BATCH", 2)
    monkeypatch.setattr(sc, "_CAPITAL_RETRY_WAIT", 0)
    monkeypatch.setattr(sc, "_listed_codes", lambda: ["00000001", "00000002", "00000003", "00000004"])

    async def no_filings(year):
        return {}

    calls: dict[str, int] = {}

    async def fake_get_json(path, **kw):
        first = kw["corp_code"].split(",")[0]
        calls[first] = calls.get(first, 0) + 1
        if first == "00000003" and calls[first] == 1:
            raise RuntimeError("DART 연결 끊김")  # 첫 시도만 실패
        return {"list": [{"corp_code": cc, "sj_div": "BS", "fs_div": "CFS", "account_nm": "자본총계", "thstrm_amount": "10",
                          "thstrm_dt": "2025.12.31"} for cc in kw["corp_code"].split(",")]}

    monkeypatch.setattr(sc, "_annual_filings", no_filings)
    monkeypatch.setattr(sc.dart_client, "get_json", fake_get_json)
    sc._fails.pop("capital_t", None)
    asyncio.run(sc._collect_capital("capital_t", 2025, "11011"))
    saved = sc._load("capital_t")
    assert saved["complete"] is True and len(saved["done"]) == 4 and len(saved["records"]) == 4
    assert calls["00000003"] == 2  # 실패한 묶음만 다시 조회


def test_audit_reports_partial_not_cached(monkeypatch):
    """일부 실패한(partial) 감사보고서 결과는 캐시하지 않아 새로고침하면 다시 조회한다."""
    import asyncio
    from app.cache import cache
    from app.services import reports_service as rs

    cache.clear()
    calls = []

    async def fake(corp_code, years):
        calls.append(1)
        return {"partial": len(calls) == 1, "reports": []}

    monkeypatch.setattr(rs, "_load_audit_reports", fake)

    async def run():
        first = await rs.get_audit_reports("00000001")
        second = await rs.get_audit_reports("00000001")
        third = await rs.get_audit_reports("00000001")
        return first, second, third

    first, second, third = asyncio.run(run())
    assert first["partial"] and not second["partial"] and not third["partial"]
    assert len(calls) == 2  # 두 번째 결과(정상)부터 캐시
    cache.clear()


def test_listed_code_but_not_listed_cls_is_external_audit():
    from app.services import market_service as ms
    from app.services.corp_index import CorpEntry

    e = CorpEntry("00999999", "코리아센터", "", "290510", "")
    ms._market.pop("290510", None)
    assert ms.badge_of(e) == ""
    ms.remember("290510", "E")
    try:
        assert ms.badge_of(e) == "외감"
    finally:
        ms._not_listed.discard("290510")


def test_badge_is_blank_not_listed_when_market_unknown():
    from app.services import market_service as ms
    from app.services.corp_index import CorpEntry

    e = CorpEntry("00999998", "가상", "", "123456", "")
    ms._market.pop("123456", None)
    ms._not_listed.discard("123456")
    assert ms.badge_of(e) == ""


def test_viewer_failure_falls_back_to_list_only_card():
    import asyncio
    from app.services import reports_service as rs

    async def boom(_rcp):
        raise RuntimeError("blocked")

    orig = rs.fetch_viewer_html
    rs.fetch_viewer_html = boom
    try:
        it = {"report_nm": "감사보고서 (2025.12)", "rcept_no": "20260320000001", "rcept_dt": "20260320"}
        out = asyncio.run(rs._standalone("테스트", it))
        att = asyncio.run(rs._attachments("테스트", {"report_nm": "반기보고서 (2026.06)", "rcept_no": "20260814000001", "rcept_dt": "20260814"}))
    finally:
        rs.fetch_viewer_html = orig
    assert out[0]["dcm_no"] == "" and out[0]["pdf_url"] == "" and out[0]["origin"] == "LIST" and "blocked" in out[0]["_err"]
    assert att[0]["kind"] == "REVIEW" and att[0]["origin"] == "LIST"
