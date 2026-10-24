package io.github.hdlee73.everycorp.data

import io.github.hdlee73.everycorp.data.api.NetworkModule
import io.github.hdlee73.everycorp.data.api.dto.AuditReportsResponse
import io.github.hdlee73.everycorp.data.api.dto.AuditScreeningDto
import io.github.hdlee73.everycorp.data.api.dto.CapitalScreeningDto
import io.github.hdlee73.everycorp.data.api.dto.ChartDto
import io.github.hdlee73.everycorp.data.api.dto.CorpSearchResponse
import io.github.hdlee73.everycorp.data.api.dto.DebtSecuritiesDto
import io.github.hdlee73.everycorp.data.api.dto.DisclosuresResponse
import io.github.hdlee73.everycorp.data.api.dto.OverviewDto
import io.github.hdlee73.everycorp.data.api.dto.QuoteDto
import kotlinx.serialization.decodeFromString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** docs/API_SPEC.md 의 JSON 예시가 DTO 로 그대로 읽히는지 확인(서버-앱 계약 테스트). */
class DtoParsingTest {
    private val json = NetworkModule.json

    @Test fun screening() {
        val a = json.decodeFromString<AuditScreeningDto>(
            """{"status":"collecting","progress":{"done":10,"total":2600},"updated_at":1,"year":2025,"checked":10,
              "items":[{"corp_code":"1","corp_name":"예시","stock_code":"","period":"2025-12","opinion":"한정","auditor":"가나다","scope":""}]}""",
        )
        assertEquals("collecting", a.status)
        assertEquals(2600, a.progress.total)
        assertEquals("한정", a.items[0].opinion)
        val c = json.decodeFromString<CapitalScreeningDto>(
            """{"status":"ready","year":2025,"report_name":"사업보고서","items":[{"corp_code":"2","corp_name":"예시바이오","period":"2025-12",
              "assets":18800000000,"liabilities":20500000000,"equity":-1700000000,"capital":4000000000,"kind":"완전잠식","fs_div":"CFS"}]}""",
        )
        assertEquals(-1_700_000_000L, c.items[0].equity)
        assertEquals("완전잠식", c.items[0].kind)
    }

    @Test fun search() {
        val r = json.decodeFromString<CorpSearchResponse>(
            """{"query":"삼성전자","count":2,"items":[
              {"corp_code":"00126380","corp_name":"삼성전자","corp_name_eng":"SAMSUNG","stock_code":"005930","listing":"LISTED","badge":"상장"},
              {"corp_code":"00258801","corp_name":"삼성전자서비스","corp_name_eng":null,"stock_code":null,"listing":"UNLISTED","badge":"외감"}]}""",
        )
        assertTrue(r.items[0].isListed)
        assertFalse(r.items[1].isListed)
        assertEquals("외감", r.items[1].badge)
    }

    @Test fun overviewListed() {
        val o = json.decodeFromString<OverviewDto>(
            """{"corp_code":"00126380","corp_name":"삼성전자","listing":"LISTED","market":"KOSPI","ceo_name":"전영현, 노태문","unknown_field":1,
              "largest_shareholder":{"available":true,"name":"삼성생명보험㈜","related_count":15,"total_ratio":20.07,
                "display":"최대주주 삼성생명보험㈜ 외 특수관계인 15명 (합산 지분율 20.1%)","holders":[{"name":"삼성생명보험㈜","relation":"최대주주 본인","shares":508157148,"ratio":8.51}],
                "basis":{"report_name":"반기보고서 (2026.06)","rcept_no":"1","bsns_year":"2026","reprt_code":"11012","period_end":"2026-06"}},
              "financials":{"available":true,"fs_div":"CFS","fs_label":"연결","revenue_label":"영업수익","currency":"KRW",
                "balance_sheet":{"total_assets":{"label":"자산총계","amount":514531948000000,"display":"514조 5,319억원","prev_amount":1,"prev2_amount":null},
                  "total_liabilities":{"label":"부채총계"},"total_equity":{"label":"자본총계"}},
                "income_statement":{"revenue":{"label":"영업수익","amount":80123000000000,"display":"80조 1,230억원"},
                  "operating_income":{"label":"영업이익"},"net_income":{"label":"당기순이익"}}}}""",
        )
        assertTrue(o.isListed)
        assertEquals("영업수익", o.financials.revenueLabel)
        assertEquals("영업수익", o.financials.incomeStatement?.revenue?.label)
        assertEquals(514_531_948_000_000L, o.financials.balanceSheet?.totalAssets?.amount)
        assertEquals(15, o.largestShareholder.relatedCount)
    }

    @Test fun overviewUnlistedWithoutFinancials() {
        val o = json.decodeFromString<OverviewDto>(
            """{"corp_code":"00258801","corp_name":"삼성전자서비스","listing":"UNLISTED","market":"OTHER",
              "largest_shareholder":{"available":false,"display":"안내","holders":[]},
              "financials":{"available":false,"message":"감사보고서 탭에서 확인하세요."}}""",
        )
        assertFalse(o.isListed)
        assertFalse(o.largestShareholder.available)
        assertNull(o.financials.balanceSheet)
    }

    @Test fun auditReports() {
        val r = json.decodeFromString<AuditReportsResponse>(
            """{"corp_code":"00126380","count":1,"partial":false,"reports":[{"id":"a-b","title":"연결감사보고서","kind":"AUDIT","consolidated":true,
              "period":"2025-12","period_label":"2025.12 결산","rcept_dt":"20260310","rcp_no":"20260310000100","dcm_no":"10363871","origin":"ATTACHMENT",
              "source_report_name":"사업보고서 (2025.12)","pdf_url":"https://dart.fss.or.kr/pdf/download/pdf.do?rcp_no=20260310000100&dcm_no=10363871",
              "pdf_proxy_path":"/api/v1/reports/pdf?rcp_no=20260310000100&dcm_no=10363871","viewer_url":"https://dart.fss.or.kr/dsaf001/main.do?rcpNo=20260310000100&dcmNo=10363871",
              "file_name":"삼성전자_2025-12_연결감사보고서.pdf"}]}""",
        )
        assertEquals("10363871", r.reports[0].dcmNo)
        assertTrue(r.reports[0].pdfUrl.contains("pdf/download/pdf.do?rcp_no=20260310000100&dcm_no=10363871"))
    }

    @Test fun disclosures() {
        val r = json.decodeFromString<DisclosuresResponse>(
            """{"corp_code":"00126380","years":1,"source":"all","kind_supported":true,"counts":{"dart":212,"kind":187,"total":399},"page":1,"size":50,"has_next":true,
              "items":[{"id":"KIND-1","source":"KIND","date":"2026-10-21","time":"17:32","title":"기업설명회(IR) 개최","url":"u","mobile_url":"m"},
                       {"id":"DART-2","source":"DART","date":"2026-10-21","time":null,"title":"t","submitter":null,"remark":null,"url":"u","mobile_url":"m"}],"warnings":[]}""",
        )
        assertTrue(r.hasNext)
        assertEquals(listOf("KIND", "DART"), r.items.map { it.source })
        assertNull(r.items[1].time)
    }

    @Test fun quoteAndChart() {
        val q = json.decodeFromString<QuoteDto>(
            """{"stock_code":"005930","name":"삼성전자","price":71000,"change":-500,"change_rate":-0.7,"direction":"DOWN","week52_high":88800,"source":"KIWOOM","fetched_at":"x"}""",
        )
        assertEquals("DOWN", q.direction)
        assertEquals(-500L, q.change)
        assertFalse(q.delayed)
        val c = json.decodeFromString<ChartDto>(
            """{"stock_code":"005930","points":[{"date":"2026-10-21","close":71500,"high":72000,"low":70900,"volume":1}],
              "range_52w":{"high":88800,"high_date":"2026-07-15","low":49900,"low_date":"2025-11-14"},"last_date":"2026-10-21"}""",
        )
        assertEquals(88800L, c.range52w.high)
        assertEquals(1, c.points.size)
    }

    @Test fun debtSecurities() {
        val d = json.decodeFromString<DebtSecuritiesDto>(
            """{"corp_code":"00126380","available":true,"unit_note":"n","basis":{"report_name":"사업보고서 (2025.12)","reprt_code":"11011"},
              "sections":[{"key":"CORP_BOND","title":"회사채","total":1700,"total_display":"1,700","rows":[
                {"label":"합계","is_total":true,"total":1700,"issue_limit":null,"buckets":[{"key":"yy1_below","label":"1년 이하","amount":1200},{"key":"x","label":"x","amount":null}]}]}],
              "missing":["단기사채"],"viewer_url":"https://dart.fss.or.kr/dsaf001/main.do?rcpNo=1"}""",
        )
        assertTrue(d.available)
        assertEquals("회사채", d.sections.single().title)
        assertEquals(1200L, d.sections[0].rows[0].buckets[0].amount)
        assertNull(d.sections[0].rows[0].buckets[1].amount)
        assertEquals(listOf("단기사채"), d.missing)
    }
}
