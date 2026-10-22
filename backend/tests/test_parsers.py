from pathlib import Path

from app.parsers import dart_viewer as dv
from app.parsers import kind as kp
from app.parsers import price as pp
from app.parsers.dart_overview import (
    PeriodicReport,
    bsns_year_candidates,
    parse_periodic_reports,
    reprt_code_for,
    revenue_label,
    summarize_financials,
    summarize_shareholders,
)
from app.services.disclosures_service import merge_disclosures
from app.services.reports_service import merge_reports
from app.utils.money import format_krw, parse_amount

FX = Path(__file__).parent / "fixtures"


# ───────── 금액 ─────────

def test_format_krw():
    assert format_krw(514_531_948_000_000) == "514조 5,319억원"
    assert format_krw(345_600_000_000) == "3,456억원"
    assert format_krw(820_000_000 - 1) == "8억원"
    assert format_krw(82_000_000) == "82백만원"
    assert format_krw(-1_500_000_000_000) == "-1조 5,000억원"
    assert format_krw(None) is None


def test_parse_amount():
    assert parse_amount("1,234") == 1234
    assert parse_amount("-1,234") == -1234
    assert parse_amount("(1,234)") == -1234
    assert parse_amount("-") is None
    assert parse_amount("") is None


# ───────── 정기보고서 ─────────

def test_periodic_reports_latest_first_and_amendment_wins():
    items = [
        {"report_nm": "사업보고서 (2025.12)", "rcept_no": "20260310000100", "rcept_dt": "20260310"},
        {"report_nm": "[기재정정]사업보고서 (2025.12)", "rcept_no": "20260401000200", "rcept_dt": "20260401"},
        {"report_nm": "반기보고서 (2026.06)", "rcept_no": "20260814000300", "rcept_dt": "20260814"},
        {"report_nm": "분기보고서 (2026.03)", "rcept_no": "20260515000400", "rcept_dt": "20260515"},
        {"report_nm": "주요사항보고서(자기주식취득결정)", "rcept_no": "20260101000500", "rcept_dt": "20260101"},
    ]
    reps = parse_periodic_reports(items)
    assert [r.kind for r in reps] == ["반기", "분기", "사업"]
    assert reps[2].rcept_no == "20260401000200"


def test_reprt_code_quarters_by_fiscal_month():
    q = lambda m: PeriodicReport("분기", 2026, m, "x", "", "")  # noqa: E731
    assert reprt_code_for(q(3), 12) == "11013"
    assert reprt_code_for(q(9), 12) == "11014"
    # 3월 결산: 1분기 = 6월, 3분기 = 12월
    assert reprt_code_for(q(6), 3) == "11013"
    assert reprt_code_for(q(12), 3) == "11014"
    assert bsns_year_candidates(q(6), 3) == ["2026", "2025"]


# ───────── 최대주주 ─────────

SH_ROWS = [
    {"nm": "삼성생명보험㈜", "relate": "최대주주 본인", "stock_knd": "보통주", "trmend_posesn_stock_co": "508,157,148", "trmend_posesn_stock_qota_rt": "8.51"},
    {"nm": "삼성물산㈜", "relate": "계열회사", "stock_knd": "보통주", "trmend_posesn_stock_co": "298,818,100", "trmend_posesn_stock_qota_rt": "5.01"},
    {"nm": "홍라희", "relate": "특수관계인", "stock_knd": "보통주", "trmend_posesn_stock_co": "1", "trmend_posesn_stock_qota_rt": "1.64"},
    {"nm": "홍라희", "relate": "특수관계인", "stock_knd": "우선주", "trmend_posesn_stock_co": "1", "trmend_posesn_stock_qota_rt": "0.10"},
    {"nm": "계", "relate": "-", "stock_knd": "보통주", "trmend_posesn_stock_co": "1", "trmend_posesn_stock_qota_rt": "20.07"},
    {"nm": "계", "relate": "-", "stock_knd": "우선주", "trmend_posesn_stock_co": "1", "trmend_posesn_stock_qota_rt": "0.91"},
]


def test_shareholder_summary():
    s = summarize_shareholders(SH_ROWS)
    assert s["available"]
    assert s["name"] == "삼성생명보험㈜"
    assert s["related_count"] == 2
    assert s["total_ratio"] == 20.07
    assert s["display"] == "최대주주 삼성생명보험㈜ 외 특수관계인 2명 (합산 지분율 20.1%)"


def test_shareholder_summary_without_total_row():
    rows = [r for r in SH_ROWS if r["nm"] != "계"]
    s = summarize_shareholders(rows)
    assert s["total_ratio"] == 15.16


def test_shareholder_empty():
    assert summarize_shareholders([])["available"] is False


# ───────── 재무 ─────────

def _row(sj, nm, amt, aid="-x"):
    return {"sj_div": sj, "account_nm": nm, "account_id": aid, "thstrm_amount": amt, "frmtrm_amount": "1", "currency": "KRW"}


def test_financials_manufacturing():
    rows = [
        _row("BS", "자산총계", "514,531,948,000,000", "ifrs-full_Assets"),
        _row("BS", "부채총계", "112,339,878,000,000", "ifrs-full_Liabilities"),
        _row("BS", "자본총계", "402,192,070,000,000", "ifrs-full_Equity"),
        _row("IS", "매출액", "300,870,903,000,000", "ifrs-full_Revenue"),
        _row("IS", "영업이익", "32,725,961,000,000", "dart_OperatingIncomeLoss"),
        _row("IS", "당기순이익", "34,451,351,000,000", "ifrs-full_ProfitLoss"),
    ]
    f = summarize_financials(rows)
    assert f["revenue_label"] == "매출액"
    assert f["balance_sheet"]["total_assets"]["display"] == "514조 5,319억원"
    assert f["income_statement"]["net_income"]["amount"] == 34_451_351_000_000


def test_financials_financial_company_uses_operating_revenue_label():
    rows = [
        _row("BS", "자산총계", "700,000,000,000,000"),
        _row("CIS", "영업수익", "80,000,000,000,000"),
        _row("CIS", "영업이익", "6,000,000,000,000"),
        _row("CIS", "당기순이익(손실)", "4,500,000,000,000"),
    ]
    f = summarize_financials(rows)
    assert f["revenue_label"] == "영업수익"
    assert f["income_statement"]["revenue"]["label"] == "영업수익"
    assert f["income_statement"]["net_income"]["amount"] == 4_500_000_000_000


def test_revenue_label_by_industry_when_missing():
    assert revenue_label(None, "649") == "영업수익"
    assert revenue_label(None, "264") == "매출액"
    assert revenue_label("순영업수익") == "영업수익"


# ───────── DART 뷰어 ─────────

def test_viewer_annual_attachments():
    html = (FX / "dart_viewer_annual.html").read_text(encoding="utf-8")
    assert dv.parse_main_dcm_no(html) == "10363855"
    docs = [d for d in dv.parse_attachments(html) if dv.is_audit_or_review(d.title)]
    assert [(d.title, d.dcm_no) for d in docs] == [("감사보고서", "10363870"), ("연결감사보고서", "10363871")]
    assert dv.classify("연결감사보고서") == ("AUDIT", True)
    assert dv.pdf_url("20250311001085", "10363870") == (
        "https://dart.fss.or.kr/pdf/download/pdf.do?rcp_no=20250311001085&dcm_no=10363870"
    )


def test_viewer_half_review_reports():
    html = (FX / "dart_viewer_half.html").read_text(encoding="utf-8")
    docs = [d for d in dv.parse_attachments(html) if dv.is_audit_or_review(d.title)]
    assert [dv.classify(d.title) for d in docs] == [("REVIEW", False), ("REVIEW", True)]


def test_viewer_standalone_dcm():
    html = (FX / "dart_viewer_standalone.html").read_text(encoding="utf-8")
    assert dv.parse_main_dcm_no(html) == "10400100"
    assert dv.parse_attachments(html) == []


def test_merge_reports_prefers_standalone():
    base = {"kind": "AUDIT", "consolidated": False, "period": "2025-12", "rcp_no": "1", "rcept_dt": "20260310"}
    a = {**base, "origin": "ATTACHMENT", "id": "a"}
    s = {**base, "origin": "STANDALONE", "id": "s", "rcept_dt": "20260301"}
    r = {**base, "kind": "REVIEW", "period": "2026-06", "origin": "ATTACHMENT", "id": "r", "rcept_dt": "20260814"}
    out = merge_reports([a, s, r])
    assert [x["id"] for x in out] == ["r", "s"]


# ───────── KIND ─────────

def test_kind_parser():
    rows = kp.parse_details((FX / "kind_details.html").read_text(encoding="utf-8"))
    assert len(rows) == 3
    assert rows[0].acptno == "20261021000812"
    assert rows[0].date == "2026-10-21" and rows[0].time == "17:32"
    assert rows[0].company_code == "00593"
    assert rows[0].title == "기업설명회(IR) 개최(안내공시)"
    assert rows[2].docno == "12345"
    assert kp.kind_company_code("005930") == "00593"
    assert "acptno=20261021000812" in kp.viewer_url(rows[0].acptno)


def test_merge_disclosures_sorted_and_dedupe():
    dart = [
        {"id": "DART-20261008000100", "source": "DART", "date": "2026-10-08", "time": None, "title": "연결재무제표기준영업(잠정)실적(공정공시)"},
        {"id": "DART-20261001000100", "source": "DART", "date": "2026-10-01", "time": None, "title": "임원ㆍ주요주주특정증권등소유상황보고서"},
    ]
    kind = [
        {"id": "KIND-20261021000812", "source": "KIND", "date": "2026-10-21", "time": "17:32", "title": "기업설명회(IR) 개최(안내공시)"},
        {"id": "KIND-20261008000045", "source": "KIND", "date": "2026-10-08", "time": "08:45", "title": "연결재무제표기준영업(잠정)실적(공정공시)"},
    ]
    merged = merge_disclosures(dart, kind, dedupe=False)
    assert [x["id"] for x in merged][0] == "KIND-20261021000812"
    assert len(merged) == 4
    deduped = merge_disclosures(dart, kind, dedupe=True)
    assert len(deduped) == 3 and "KIND-20261008000045" not in {x["id"] for x in deduped}


# ───────── 시세 ─────────

def test_naver_new_and_old_format():
    new = pp.parse_naver_realtime({"datas": [{"stockName": "삼성전자", "closePrice": "71,000", "compareToPreviousClosePrice": "1,500",
                                             "compareToPreviousPrice": {"code": "2"}, "fluctuationsRatio": "2.16", "marketStatus": "OPEN"}]})
    assert new["price"] == 71000 and new["direction"] == "UP" and new["change_rate"] == 2.16
    old = pp.parse_naver_realtime({"result": {"areas": [{"datas": [{"nm": "삼성전자", "nv": 70000, "cv": 300, "cr": 0.43, "rf": "5"}]}]}})
    assert old["change"] == -300 and old["direction"] == "DOWN"


def test_daily_parsers_and_range():
    rows = pp.parse_datagokr_items({"response": {"body": {"items": {"item": [
        {"basDt": "20261020", "clpr": "71000", "mkp": "70500", "hipr": "71500", "lopr": "70000", "trqu": "100"},
        {"basDt": "20261017", "clpr": "70000", "mkp": "69000", "hipr": "88800", "lopr": "68000", "trqu": "100"},
    ]}}}})
    assert rows[0]["date"] == "2026-10-17"
    rng = pp.summarize_range(rows)
    assert rng == {"high": 88800, "high_date": "2026-10-17", "low": 68000, "low_date": "2026-10-17"}
    sise = pp.parse_naver_sise_json("[['날짜', '시가', '고가', '저가', '종가', '거래량', '외국인소진율'],\n"
                                    '["20261020", 70500, 71500, 70000, 71000, 1234, 50.1],\n]')
    assert sise == [{"date": "2026-10-20", "open": 70500, "high": 71500, "low": 70000, "close": 71000, "volume": 1234}]


def test_krx_snapshot_parse():
    from app.services.krx_client import parse_snapshot
    data = {"OutBlock_1": [
        {"BAS_DD": "20261021", "ISU_CD": "KR7005930003", "ISU_NM": "삼성전자", "TDD_CLSPRC": "71,000", "CMPPREVDD_PRC": "-500",
         "FLUC_RT": "-0.70", "TDD_OPNPRC": "71,500", "TDD_HGPRC": "71,800", "TDD_LWPRC": "70,800", "ACC_TRDVOL": "123"}]}
    q = parse_snapshot(data, "005930")
    assert q["price"] == 71000 and q["direction"] == "DOWN" and q["base_date"] == "20261021"
    assert parse_snapshot(data, "000660") is None


def test_debt_section_summary():
    from app.parsers.dart_debt import summarize_section
    rows = [
        {"rcept_no": "1", "remndr_exprtn1": "공모", "remndr_exprtn2": "-", "yy1_below": "1,000", "yy1_excess_yy2_below": "500", "sm": "1,500", "isu_lmt": "-", "remndr_lmt": "-"},
        {"rcept_no": "1", "remndr_exprtn1": "사모", "remndr_exprtn2": "-", "yy1_below": "200", "yy1_excess_yy2_below": "-", "sm": "200"},
        {"rcept_no": "1", "remndr_exprtn1": "합계", "remndr_exprtn2": "-", "yy1_below": "1,200", "yy1_excess_yy2_below": "500", "sm": "1,700"},
    ]
    s = summarize_section("CORP_BOND", "회사채", rows)
    assert s["total"] == 1700 and s["total_display"] == "1,700"
    assert [r["label"] for r in s["rows"]] == ["공모", "사모", "합계"]
    assert s["rows"][0]["buckets"][0] == {"key": "yy1_below", "label": "1년 이하", "amount": 1000}
    assert s["rows"][1]["buckets"][1]["amount"] is None
    assert s["rows"][2]["is_total"]
    # 합계 행이 없으면 행 합
    assert summarize_section("X", "x", rows[:2])["total"] == 1700


def test_viewer_attachments_real_server_format():
    """2026-10 실서버: option value 는 경로 없는 'rcpNo=…&dcmNo=…', 텍스트는 '2026.08.14&nbsp; 반기검토보고서'."""
    html = (
        '<select id="att" onchange="changeAtt(this.value)">'
        '<option value="null">+첨부선택+</option>'
        '<option value="rcpNo=20260814003699&amp;dcmNo=11538148" > 2026.08.14&nbsp; 반기검토보고서 </option>'
        '<option value="rcpNo=20260814003699&amp;dcmNo=11538150" > 2026.08.14&nbsp; 반기연결검토보고서 </option>'
        '<option value="rcpNo=20260814003699&amp;dcmNo=11538149" > 2026.08.14&nbsp; 자기주식보고서 </option>'
        '</select>'
    )
    docs = dv.parse_attachments(html)
    assert [(d.title, d.dcm_no) for d in docs] == [
        ("반기검토보고서", "11538148"), ("반기연결검토보고서", "11538150"), ("자기주식보고서", "11538149")]
    review = [d for d in docs if dv.is_audit_or_review(d.title)]
    assert [dv.classify(d.title) for d in review] == [("REVIEW", False), ("REVIEW", True)]


def test_audit_opinion_summary():
    from app.services.reports_service import summarize_opinions
    rows = [
        {"bsns_year": "제57기(당기)", "adtor": "삼정회계법인", "adt_opinion": "적정", "emphs_matter": "-"},
        {"bsns_year": "제56기(전기)", "adtor": "삼정회계법인", "adt_opinion": "적정"},
        {"bsns_year": "제55기(전전기)", "adtor": "-", "adt_opinion": "-"},
    ]
    assert summarize_opinions(rows) == [
        {"term": "제57기(당기)", "auditor": "삼정회계법인", "opinion": "적정"},
        {"term": "제56기(전기)", "auditor": "삼정회계법인", "opinion": "적정"},
    ]
