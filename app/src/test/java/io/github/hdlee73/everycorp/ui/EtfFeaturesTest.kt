package io.github.hdlee73.everycorp.ui

import io.github.hdlee73.everycorp.data.api.NetworkModule
import io.github.hdlee73.everycorp.data.api.dto.EtfDetailDto
import io.github.hdlee73.everycorp.data.api.dto.EtfHoldingDto
import io.github.hdlee73.everycorp.data.api.dto.EtfItemDto
import io.github.hdlee73.everycorp.data.api.dto.EtfOverlapDto
import io.github.hdlee73.everycorp.data.api.dto.EtfPortfolioDto
import io.github.hdlee73.everycorp.data.api.dto.EtfRelationsDto
import io.github.hdlee73.everycorp.ui.common.Dest
import io.github.hdlee73.everycorp.ui.common.EtfTab
import io.github.hdlee73.everycorp.ui.common.NavState
import io.github.hdlee73.everycorp.ui.etf.EtfFmt
import io.github.hdlee73.everycorp.ui.etf.HoldingFilter
import io.github.hdlee73.everycorp.ui.etf.comparePick
import io.github.hdlee73.everycorp.ui.etf.filterHoldings
import kotlinx.serialization.decodeFromString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EtfFeaturesTest {
    private val json = NetworkModule.json
    private val kodex = EtfItemDto("069500", "KODEX 200")
    private val tiger = EtfItemDto("102110", "TIGER 200")

    @Test fun etfTabsBackGoesToPreviousTabThenScreen() {
        val base = NavState().openTab(Dest.EtfHome)
        var s = base.openEtf(kodex).selectEtfTab(EtfTab.Portfolio).selectEtfTab(EtfTab.Relations)
        assertEquals(EtfTab.Relations, (s.top as Dest.Etf).current)
        s = s.back()!!
        assertEquals(EtfTab.Portfolio, (s.top as Dest.Etf).current)
        s = s.back()!!
        assertEquals(EtfTab.Basics, (s.top as Dest.Etf).current)
        s = s.back()!!
        assertEquals(Dest.EtfHome, s.top)
        // 관련성 탭으로 바로 연 ETF 는 뒤로가기 한 번에 기초자료 탭
        val direct = base.openEtf(kodex, EtfTab.Relations)
        assertEquals(EtfTab.Basics, (direct.back()!!.top as Dest.Etf).current)
    }

    @Test fun overlapScreenStacksOnTop() {
        val s = NavState().openEtf(kodex).openEtfOverlap(kodex, tiger)
        assertTrue(s.top is Dest.EtfOverlap)
        assertTrue(s.back()!!.top is Dest.Etf)
    }

    @Test fun comparePickFlow() {
        val (f1, p1) = comparePick(null, kodex)
        assertEquals(kodex, f1); assertNull(p1)
        val (f2, p2) = comparePick(kodex, kodex)
        assertNull(f2); assertNull(p2)
        val (f3, p3) = comparePick(kodex, tiger)
        assertNull(f3); assertEquals(kodex to tiger, p3)
    }

    @Test fun formats() {
        assertEquals("1조 2,345억원", EtfFmt.eok(1_234_500_000_000))
        assertEquals("8,720억원", EtfFmt.eok(872_000_000_000))
        assertEquals("3.2억원", EtfFmt.eok(320_000_000))
        assertEquals("2조원", EtfFmt.eok(2_000_000_000_000))
        assertEquals("0.09%", EtfFmt.fee(0.0900))
        assertEquals("0.45%", EtfFmt.fee(0.45))
        assertEquals("-", EtfFmt.weight(null))
    }

    @Test fun holdingFilters() {
        val rows = listOf(
            EtfHoldingDto(name = "삼성전자", kind = "STOCK"), EtfHoldingDto(name = "KODEX 200", kind = "ETF"),
            EtfHoldingDto(name = "원화예금", kind = "CASH"), EtfHoldingDto(name = "??", kind = "UNMATCHED"),
        )
        assertEquals(4, filterHoldings(rows, HoldingFilter.All).size)
        assertEquals(listOf("삼성전자"), filterHoldings(rows, HoldingFilter.Stock).map { it.name })
        assertEquals(listOf("원화예금"), filterHoldings(rows, HoldingFilter.Other).map { it.name })
        assertEquals(listOf("??"), filterHoldings(rows, HoldingFilter.Unmatched).map { it.name })
    }

    @Test fun displayNameUsesHoldingNameForPreferred() {
        val pref = EtfHoldingDto(code = "005935", name = "삼성전자우", kind = "STOCK", match = "pref", corpName = "삼성전자")
        assertEquals("삼성전자우", pref.displayName)
        val common = EtfHoldingDto(code = "KR7005930003", name = "삼성전자보통주", kind = "STOCK", match = "isin", corpName = "삼성전자")
        assertEquals("삼성전자", common.displayName)
    }

    @Test fun serverJsonDecodes() {
        val d = json.decodeFromString<EtfDetailDto>(
            """{"code":"069500","name":"KODEX 200","daily":[],"basics":{"base_date":"2026-11-11","net_assets":8720645000000,"net_assets_computed":false,"listed_shares":100000000,"nav_krx":87206.45,"manager":"삼성자산운용","manager_source":"KRX 기본정보","total_fee":0.15,"checks":[{"label":"NAV 키움 ↔ KRX","ok":true,"detail":"x"}],"source":"KRX"}}""",
        )
        assertEquals(8_720_645_000_000L, d.basics?.netAssets)
        assertEquals("삼성자산운용", d.basics?.manager)
        val p = json.decodeFromString<EtfPortfolioDto>(
            """{"code":"999990","date":"2026-11-11","source":"KRX","partial":false,"count":2,"total_weight":100.0,"unmatched_count":1,
               "by_kind":[{"kind":"STOCK","label":"주식","weight":60.0}],"donut":[{"label":"삼성전자","weight":60.0}],
               "holdings":[{"code":"005930","name":"삼성전자","weight":60.0,"kind":"STOCK","kind_label":"주식","match":"code","corp_code":"00126380","corp_name":"삼성전자","stock_code":"005930"},
                           {"name":"알수없음","weight":40.0,"kind":"UNMATCHED","kind_label":"미매칭"}],"note":"n"}""",
        )
        assertEquals("00126380", p.holdings.first().corpCode)
        assertEquals("미매칭", p.holdings[1].kindLabel)
        val r = json.decodeFromString<EtfRelationsDto>(
            """{"code":"999990","manager":"삼성자산운용","etf_holdings":{"own_weight":40.0,"other_weight":30.0,"own":[{"code":"069500","name":"KODEX 200","manager":"삼성자산운용","weight":40.0}],"other":[]},
               "cycles":[[{"code":"999990","name":"A"},{"code":"069500","name":"B"},{"code":"999990","name":"A"}]],"mutual":[{"code":"069500","name":"B"}],
               "held_by":[{"code":"069500","name":"B","manager":"삼성자산운용","weight":5.0,"own":true}],"similar":[{"code":"111110","name":"C","manager":"","overlap_weight":3.0,"common_count":1}],
               "coverage":{"stored":3,"crawled_on":"","crawling":false,"progress":{"done":0,"total":0}},"partial":false,"note":""}""",
        )
        assertEquals(40.0, r.etfHoldings.ownWeight, 0.0)
        assertEquals(3, r.cycles.first().size)
        assertTrue(r.heldBy.first().own)
        val o = json.decodeFromString<EtfOverlapDto>(
            """{"a":{"code":"999990","name":"A"},"b":{"code":"069500","name":"B"},"overlap_weight":20.0,"common_count":1,"count_a":3,"count_b":2,"common":[{"key":"S:005930","name":"삼성전자","weight_a":20.0,"weight_b":95.0}],"note":""}""",
        )
        assertEquals(95.0, o.common.first().weightB, 0.0)
    }
}
