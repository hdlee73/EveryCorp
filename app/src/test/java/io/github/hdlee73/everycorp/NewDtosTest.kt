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

    @Test fun opinionsDecode() {
        val r = json.decodeFromString<AuditReportsResponse>(
            """{"opinions":{"report_name":"사업보고서 (2025.12)","items":[{"term":"제57기(당기)","auditor":"삼정회계법인","opinion":"적정"}],"source":"s"},"reports":[]}""",
        )
        assertEquals("적정", r.opinions?.items?.first()?.opinion)
    }

    @Test fun shareholdersDecode() {
        val s = json.decodeFromString<ShareholdersDto>(
            """{"available":true,"holders":[{"name":"이재용","relation":"본인","end_shares":97414196,"end_ratio":1.63}],"changes":[{"date":"2020.10.25","name":"이재용","cause":"상속"}]}""",
        )
        assertEquals(97_414_196L, s.holders.first().endShares)
        assertEquals("상속", s.changes.first().cause)
    }
}
