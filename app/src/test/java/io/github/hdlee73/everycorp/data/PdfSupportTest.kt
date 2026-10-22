package io.github.hdlee73.everycorp.data

import io.github.hdlee73.everycorp.data.pdf.PdfSupport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfSupportTest {
    @Test fun magic() {
        assertTrue(PdfSupport.isPdf("%PDF-1.7\n".toByteArray()))
        assertFalse(PdfSupport.isPdf("<!DOCTYPE html>".toByteArray()))
        assertFalse(PdfSupport.isPdf(ByteArray(2)))
    }

    @Test fun fileName() {
        assertEquals("삼성전자_2025-12_연결감사보고서.pdf", PdfSupport.safeFileName("삼성전자_2025-12_연결감사보고서.pdf"))
        assertEquals("a_b_c.pdf", PdfSupport.safeFileName("a/b:c"))
        assertEquals("report.pdf", PdfSupport.safeFileName("  ///  "))
    }

    @Test fun proxyUrl() {
        assertEquals("https://api.example.com/api/v1/reports/pdf?rcp_no=1", PdfSupport.proxyUrl("https://api.example.com/", "/api/v1/reports/pdf?rcp_no=1"))
    }
}
