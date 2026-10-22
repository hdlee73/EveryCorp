package io.github.hdlee73.everycorp.data

import io.github.hdlee73.everycorp.data.api.dto.DisclosureDto
import io.github.hdlee73.everycorp.data.kind.DisclosureMerge
import io.github.hdlee73.everycorp.data.kind.KindParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KindTest {
    private val html = """
        <table><tbody>
        <tr>
          <td class="first txc">1</td><td class="txc">2026-10-21 17:32</td>
          <td><a href="#company" onclick="companysummary_open('00593'); return false;" id="companysum">삼성전자</a></td>
          <td><a href="#viewer" onclick="openDisclsViewer('20261021000812',''); return false;" title="기업설명회(IR) 개최(안내공시)">기업설명회(IR) 개최(안내공시)</a></td>
          <td class="txc">삼성전자</td>
        </tr>
        <tr>
          <td>2</td><td>2026-10-07 16:00</td>
          <td><a onclick="companysummary_open('02838'); return false;">삼성전자서비스</a></td>
          <td><a onclick="openDisclsViewer('20261007000999','12345'); return false;">타회사 &amp; 공시</a></td>
          <td>삼성전자서비스</td>
        </tr>
        </tbody></table>
    """.trimIndent()

    @Test fun parsesRowsAndFiltersOtherCompanies() {
        val rows = KindParser.parse(html)
        assertEquals(2, rows.size)
        val mine = KindParser.onlyCompany(rows, "005930", "삼성전자")
        assertEquals(1, mine.size)
        val d = mine.single()
        assertEquals("KIND-20261021000812", d.id)
        assertEquals("2026-10-21", d.date); assertEquals("17:32", d.time)
        assertEquals("기업설명회(IR) 개최(안내공시)", d.title)
        assertTrue(d.url.contains("acptno=20261021000812"))
        assertEquals("타회사 & 공시", rows[1].dto.title)
    }

    private fun item(id: String, date: String, title: String, time: String? = null) =
        DisclosureDto(id = id, source = id.substringBefore('-'), date = date, time = time, title = title, url = "u", mobileUrl = "m")

    @Test fun periodicFilterAndMerge() {
        assertTrue(DisclosureMerge.isPeriodic("[기재정정]반기보고서 (2026.06)"))
        assertFalse(DisclosureMerge.isPeriodic("주요사항보고서(자기주식취득결정)"))
        val dart = listOf(item("DART-3", "2026-03-10", "사업보고서 (2025.12)"), item("DART-1", "2026-10-01", "분기보고서 (2026.09)"))
        val kind = listOf(item("KIND-2", "2026-10-01", "주요공시", "09:00"), item("KIND-9", "2026-05-01", "분기보고서 (2026.03)"))
        assertEquals(listOf("KIND-2", "DART-1", "KIND-9", "DART-3"), DisclosureMerge.merge(dart, kind).map { it.id })
        assertEquals(listOf("KIND-9"), DisclosureMerge.filterKind(kind, "", periodicOnly = true).map { it.id })
        assertEquals(listOf("KIND-2"), DisclosureMerge.filterKind(kind, "주요", periodicOnly = false).map { it.id })
    }
}
