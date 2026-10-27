package io.github.hdlee73.everycorp.report

import io.github.hdlee73.everycorp.data.api.dto.AuditReportDto
import io.github.hdlee73.everycorp.data.report.SummaryHwpxWriter
import io.github.hdlee73.everycorp.data.report.SummaryPdfInput
import io.github.hdlee73.everycorp.data.report.SummarySection
import io.github.hdlee73.everycorp.data.report.yearEndAuditReports
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

class SummaryHwpxTest {
    private fun report(label: String, kind: String, auditor: String) = AuditReportDto(
        id = label + kind, title = "t", kind = kind, consolidated = true, period = "2025-12", auditor = auditor, opinion = "적정의견",
        periodLabel = label, rcpNo = "1", dcmNo = "2", pdfUrl = "", pdfProxyPath = "", viewerUrl = "", fileName = "f.pdf",
    )

    private val reports = listOf(
        report("2025.12 결산", "AUDIT", "삼일회계법인"),
        report("2025.06 반기", "REVIEW", "반기검토법인"),
        report("2025.09 분기", "REVIEW", "분기검토법인"),
    )

    private fun input(sections: Set<SummarySection>) = SummaryPdfInput(
        corpName = "테스트 & 기업", badge = "상장", overview = null, debt = null, quote = null, chart = null, auditReports = reports,
        appName = "Every Corp", versionName = "1.10.0", author = "이현덕 (hdlee73@gmail.com)", generatedAt = "2026.10.27 09:00", sections = sections,
    )

    private fun build(sections: Set<SummarySection>): Map<String, ByteArray> {
        val out = ByteArrayOutputStream()
        // 단위 테스트의 작업 폴더는 app/ 모듈이다.
        SummaryHwpxWriter { name -> File("src/main/assets/hwpx/$name").readBytes() }.write(input(sections), out)
        val files = linkedMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(out.toByteArray())).use { z ->
            var e: ZipEntry? = z.nextEntry
            while (e != null) { files[e.name] = z.readBytes(); e = z.nextEntry }
        }
        return files
    }

    @Test fun yearEndKeepsOnlyAnnualAudit() {
        assertEquals(listOf("2025.12 결산"), yearEndAuditReports(reports).map { it.periodLabel })
    }

    @Test fun packageHasMimetypeFirstAndWellFormedXml() {
        val files = build(SummarySection.entries.toSet())
        assertEquals("mimetype", files.keys.first())
        assertEquals("application/hwp+zip", String(files.getValue("mimetype")))
        for (name in listOf("version.xml", "META-INF/container.xml", "Contents/content.hpf", "Contents/header.xml", "Contents/section0.xml")) {
            DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(ByteArrayInputStream(files.getValue(name)))
        }
    }

    @Test fun sectionHasEscapedTextAndOnlyYearEndAudit() {
        val s = String(build(SummarySection.entries.toSet()).getValue("Contents/section0.xml"), Charsets.UTF_8)
        assertTrue(s.contains("테스트 &amp; 기업"))
        assertTrue(s.contains("삼일회계법인"))
        assertFalse(s.contains("반기검토법인"))
        assertFalse(s.contains("분기검토법인"))
    }

    @Test fun unselectedSectionsAreLeftOut() {
        val s = String(build(setOf(SummarySection.Overview)).getValue("Contents/section0.xml"), Charsets.UTF_8)
        assertTrue(s.contains("1. 기업개황"))
        assertFalse(s.contains("감사의견"))
        assertFalse(s.contains("채권"))
    }
}
