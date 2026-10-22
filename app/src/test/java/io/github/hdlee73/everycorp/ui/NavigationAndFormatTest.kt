package io.github.hdlee73.everycorp.ui

import io.github.hdlee73.everycorp.data.api.dto.CorpItemDto
import io.github.hdlee73.everycorp.data.prefs.CorpListCodec
import io.github.hdlee73.everycorp.ui.common.CompanyTab
import io.github.hdlee73.everycorp.ui.common.Dest
import io.github.hdlee73.everycorp.ui.common.Fmt
import io.github.hdlee73.everycorp.ui.common.NavState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationAndFormatTest {
    private val samsung = CorpItemDto("00126380", "삼성전자", stockCode = "005930", listing = "LISTED", badge = "상장")

    @Test fun backGoesToPreviousTabThenPreviousScreenThenExit() {
        var s = NavState().openCompany(samsung).selectTab(CompanyTab.Reports).selectTab(CompanyTab.Stock)
        assertEquals(CompanyTab.Stock, (s.top as Dest.Company).current)

        s = s.back()!!                       // 주가 → 감사·검토
        assertEquals(CompanyTab.Reports, (s.top as Dest.Company).current)
        s = s.back()!!                       // 감사·검토 → 기업개황
        assertEquals(CompanyTab.Overview, (s.top as Dest.Company).current)
        s = s.back()!!                       // 기업 화면 → 검색 홈
        assertEquals(Dest.Home, s.top)
        assertNull(s.back())                 // 첫 화면에서만 종료
        assertFalse(s.canGoBack)
    }

    @Test fun infoScreenReturnsToPreviousPageWithTabKept() {
        val s = NavState().openCompany(samsung).selectTab(CompanyTab.Disclosures).openInfo()
        assertEquals(Dest.Info, s.top)
        val back = s.back()!!
        assertEquals(CompanyTab.Disclosures, (back.top as Dest.Company).current)
    }

    @Test fun goHomeJumpsToSearchInOneStep() {
        val s = NavState().openCompany(samsung).selectTab(CompanyTab.Stock).openInfo().goHome()
        assertEquals(Dest.Home, s.top)
        assertFalse(s.canGoBack)
        assertEquals(s, s.goHome())
    }

    @Test fun revisitingTabDoesNotRepeatInHistory() {
        val s = NavState().openCompany(samsung)
            .selectTab(CompanyTab.Reports).selectTab(CompanyTab.Stock).selectTab(CompanyTab.Reports)
        val tabs = (s.top as Dest.Company).tabs
        assertEquals(listOf(CompanyTab.Overview, CompanyTab.Stock, CompanyTab.Reports), tabs)
    }

    @Test fun formats() {
        assertEquals("71,000", Fmt.price(71000))
        assertEquals("-", Fmt.price(null))
        assertEquals("▲ 1,500 (+2.16%)", Fmt.change(1500, 2.16, "UP"))
        assertEquals("▼ 500 (-0.70%)", Fmt.change(-500, -0.70, "DOWN"))
        assertEquals("- 0 (0.00%)", Fmt.change(0, 0.0, "FLAT"))
        assertEquals("2026.03.10", Fmt.date("20260310"))
        assertEquals("2026.03.10", Fmt.date("2026-03-10"))
        assertEquals("20.1%", Fmt.percent(20.07))
    }

    @Test fun recentListKeepsOrderAndDedupes() {
        val hynix = CorpItemDto("00164779", "SK하이닉스", listing = "LISTED")
        var list = CorpListCodec.pushFront(emptyList(), samsung, 3)
        list = CorpListCodec.pushFront(list, hynix, 3)
        list = CorpListCodec.pushFront(list, samsung, 3)
        assertEquals(listOf("00126380", "00164779"), list.map { it.corpCode })
        assertEquals(list, CorpListCodec.decode(CorpListCodec.encode(list)))
        assertTrue(CorpListCodec.decode("not json").isEmpty())
    }

    @Test fun networkMessageExplainsUnconfiguredServer() {
        val dev = io.github.hdlee73.everycorp.data.api.NetworkModule.networkMessage("http://10.0.2.2:8000")
        assertTrue(dev.contains("설정되지 않은"))
        val prod = io.github.hdlee73.everycorp.data.api.NetworkModule.networkMessage("https://api.example.org")
        assertTrue(prod.contains("api.example.org"))
    }
}
