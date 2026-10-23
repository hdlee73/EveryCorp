package io.github.hdlee73.everycorp

import io.github.hdlee73.everycorp.data.api.NetworkModule
import io.github.hdlee73.everycorp.data.api.dto.AuditReportsResponse
import io.github.hdlee73.everycorp.data.api.dto.FinancialsDto
import io.github.hdlee73.everycorp.data.api.dto.ShareholdersDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import kotlinx.serialization.decodeFromString
import org.junit.Test

class NewDtosTest {
    private val json = NetworkModule.json

    @Test fun trendDecodes() {
        val f = json.decodeFromString<FinancialsDto>(
            """{"available":true,"trend":{"unit":"원","years":[2024,2025],"series":[{"key":"revenue","label":"매출액","values":[100.0,null]}]}}""",
        )
        assertEquals(listOf(2024, 2025), f.trend?.years)
        assertNull(f.trend?.series?.first()?.values?.get(1))
    }

    @Test fun reportOpinionDecodes() {
        val r = json.decodeFromString<AuditReportsResponse>(
            """{"reports":[{"id":"1-2","title":"반기검토보고서","kind":"REVIEW","rcp_no":"1","dcm_no":"2","auditor":"삼정회계법인","opinion":"적정","pdf_url":"u","pdf_proxy_path":"p","viewer_url":"v","file_name":"f.pdf"}]}""",
        )
        assertEquals("적정", r.reports.first().opinion)
        assertEquals("삼정회계법인", r.reports.first().auditor)
    }

    @Test fun shareholdersDecode() {
        val s = json.decodeFromString<ShareholdersDto>(
            """{"available":true,"holders":[{"name":"이재용","relation":"본인","end_shares":97414196,"end_ratio":1.63}],"totals":[{"name":"계","stock_kind":"우선주","end_ratio":3.4}],"changes":[{"date":"2020.10.25","name":"이재용","cause":"상속"}]}""",
        )
        assertEquals(97_414_196L, s.holders.first().endShares)
        assertEquals("상속", s.changes.first().cause)
        assertEquals("우선주", s.totals.first().stockKind)
    }
}
