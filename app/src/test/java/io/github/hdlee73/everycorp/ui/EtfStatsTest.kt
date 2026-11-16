package io.github.hdlee73.everycorp.ui

import io.github.hdlee73.everycorp.data.api.NetworkModule
import io.github.hdlee73.everycorp.data.api.dto.EtfRankingDto
import io.github.hdlee73.everycorp.data.api.dto.EtfStatsDto
import io.github.hdlee73.everycorp.data.api.dto.EtfStatsOverviewDto
import io.github.hdlee73.everycorp.ui.common.Dest
import io.github.hdlee73.everycorp.ui.common.EtfStatsTab
import io.github.hdlee73.everycorp.ui.common.NavState
import io.github.hdlee73.everycorp.ui.common.StatFmt
import io.github.hdlee73.everycorp.ui.etf.EtfSeriesMetric
import io.github.hdlee73.everycorp.ui.etf.StatMetric
import io.github.hdlee73.everycorp.ui.etf.etfSeriesValues
import io.github.hdlee73.everycorp.ui.etf.firstLastChange
import io.github.hdlee73.everycorp.ui.etf.managerValues
import io.github.hdlee73.everycorp.ui.etf.totalValues
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EtfStatsTest {
    private val json = NetworkModule.json

    private val overviewJson = """
        {"dates":["2026-11-12","2026-11-13"],
         "total":{"count":[3,4],"net_assets":[155000000000,214000000000],"market_cap":[150000000000,210000000000]},
         "managers":[{"name":"삼성자산운용","count":[1,1],"net_assets":[100000000000,120000000000],"market_cap":[1,2]},
                     {"name":"기타","count":[2,3],"net_assets":[55000000000,94000000000],"market_cap":[3,4]}],
         "manager_table":[{"name":"삼성자산운용","count":1,"net_assets":120000000000,"market_cap":2,"share":56.07,"count_change":0,"net_assets_change":20000000000}],
         "coverage":{"first_date":"2026-11-12","last_date":"2026-11-13","days":2,"source":"KRX Open API","backfill":"failed",
                     "progress":{"done":0,"total":0},"since_first_collect":true,"collecting":false,"note":"n"},
         "note":"x"}
    """.trimIndent()

    @Test fun overviewParsesAndBuildsSeries() {
        val o = json.decodeFromString(EtfStatsOverviewDto.serializer(), overviewJson)
        assertTrue(o.coverage.sinceFirstCollect)
        assertEquals(listOf(3.0, 4.0), totalValues(o, StatMetric.Count))
        assertEquals(214_000_000_000.0, totalValues(o, StatMetric.NetAssets).last())
        val m = managerValues(o, StatMetric.NetAssets)
        assertEquals(listOf("삼성자산운용", "기타"), m.map { it.first })
        val (d, pct) = firstLastChange(totalValues(o, StatMetric.Count))!!
        assertEquals(1.0, d, 0.0)
        assertEquals(33.33, pct!!, 0.01)
        assertNull(firstLastChange(listOf(null, 1.0)))
    }

    @Test fun rankingAndEtfStatsParse() {
        val r = json.decodeFromString(
            EtfRankingDto.serializer(),
            """{"date":"2026-11-13","base_date":"","total":1,"items":[{"rank":1,"code":"069500","name":"KODEX 200","manager":"삼성자산운용","net_assets":150,"market_cap":null,"new":true,"net_assets_change":null}],"managers":["삼성자산운용"]}""",
        )
        assertTrue(r.items.first().isNew)
        assertNull(r.items.first().marketCap)
        val s = json.decodeFromString(
            EtfStatsDto.serializer(),
            """{"code":"069500","dates":["2026-11-12","2026-11-13"],"nav":[1.5,null],"net_assets":[100,120],"listed_shares":[10,12],
               "changes":[{"label":"1주","net_assets":{"from":100,"to":120,"diff":20,"pct":20.0},"market_cap":null,"listed_shares":{"from":10,"to":12,"diff":2,"pct":20.0}}],"rank":3,"rank_total":900}""",
        )
        assertEquals(listOf(1.5, null), etfSeriesValues(s, EtfSeriesMetric.Nav))
        assertEquals(listOf(10.0, 12.0), etfSeriesValues(s, EtfSeriesMetric.Shares))
        assertEquals(20.0, s.changes.first().netAssets!!.diff, 0.0)
        assertNull(s.changes.first().marketCap)
    }

    @Test fun statsTabsBackGoesToPreviousTab() {
        var s = NavState().openTab(Dest.EtfStats()).selectEtfStatsTab(EtfStatsTab.Managers).selectEtfStatsTab(EtfStatsTab.Ranking)
        assertEquals(EtfStatsTab.Ranking, (s.top as Dest.EtfStats).current)
        s = s.back()!!
        assertEquals(EtfStatsTab.Managers, (s.top as Dest.EtfStats).current)
        s = s.back()!!.back()!!
        assertEquals(Dest.Home, s.top)
        assertNull(s.back())
        val direct = NavState().openEtfStats(EtfStatsTab.Ranking)
        assertEquals(EtfStatsTab.Total, (direct.back()!!.top as Dest.EtfStats).current)
    }

    @Test fun formats() {
        assertEquals("21.4조", StatFmt.won(21_400_000_000_000.0))
        assertEquals("4,567억", StatFmt.won(456_700_000_000.0))
        assertEquals("−1.0조", StatFmt.wonSigned(-1_000_000_000_000.0))
        assertEquals("+12", StatFmt.countSigned(12))
        assertEquals("+20.0%", StatFmt.pctSigned(20.0))
    }
}
