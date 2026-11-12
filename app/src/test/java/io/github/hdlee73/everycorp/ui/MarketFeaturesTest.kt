package io.github.hdlee73.everycorp.ui

import io.github.hdlee73.everycorp.data.api.NetworkModule
import io.github.hdlee73.everycorp.data.api.dto.DisclosureDto
import io.github.hdlee73.everycorp.data.api.dto.FlowsDto
import io.github.hdlee73.everycorp.data.api.dto.RankingItemDto
import io.github.hdlee73.everycorp.data.api.dto.RankingsDto
import io.github.hdlee73.everycorp.data.log.CrashLog
import io.github.hdlee73.everycorp.data.realtime.RealtimeClient
import io.github.hdlee73.everycorp.ui.common.ChartMarker
import io.github.hdlee73.everycorp.ui.common.CompanyTab
import io.github.hdlee73.everycorp.ui.common.Indicators
import io.github.hdlee73.everycorp.ui.common.MarkerKind
import io.github.hdlee73.everycorp.ui.common.candleMarkerIndex
import io.github.hdlee73.everycorp.ui.common.isPeriodicReport
import io.github.hdlee73.everycorp.ui.common.toKindMarkers
import io.github.hdlee73.everycorp.ui.market.filterRankings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MarketFeaturesTest {
    private fun near(a: Double?, b: Double, eps: Double = 1e-6) = assertTrue("$a vs $b", a != null && kotlin.math.abs(a - b) < eps)

    @Test fun smaAndEma() {
        val v = listOf(1.0, 2.0, 3.0, 4.0, 5.0)
        assertEquals(listOf(null, null, 2.0, 3.0, 4.0), Indicators.sma(v, 3))
        val e = Indicators.ema(v, 3)
        assertNull(e[1]); near(e[2], 2.0); near(e[3], 3.0); near(e[4], 4.0)
    }

    @Test fun bollingerOfConstantIsFlat() {
        val b = Indicators.bollinger(List(25) { 100.0 })
        assertNull(b.upper[18]); near(b.upper[19], 100.0); near(b.lower[24], 100.0); near(b.middle[24], 100.0)
    }

    @Test fun rsiExtremes() {
        val up = Indicators.rsi((1..20).map { it.toDouble() })
        near(up[14], 100.0); assertNull(up[13])
        val down = Indicators.rsi((20 downTo 1).map { it.toDouble() })
        near(down[19], 0.0)
        val flat = Indicators.rsi(List(20) { 5.0 })
        near(flat[15], 50.0)
    }

    @Test fun rsiKnownValue() {
        // 상승·하락이 같은 폭으로 번갈아 나오면 RSI 는 50 근처
        val v = (0 until 40).map { if (it % 2 == 0) 100.0 else 101.0 }
        val r = Indicators.rsi(v)
        assertTrue(r[39]!! in 45.0..55.0)
    }

    @Test fun macdOfLinearTrendIsPositiveAndConverges() {
        val v = (1..80).map { it.toDouble() }
        val m = Indicators.macd(v)
        assertNull(m.macd[24]); assertTrue(m.macd[25] != null)
        assertTrue(m.macd[79]!! > 0)
        assertNull(m.signal[32]); assertTrue(m.signal[33] != null)
        assertTrue(kotlin.math.abs(m.hist[79]!!) < 0.01)
    }

    @Test fun candleMarkerPlacement() {
        val daily = listOf("20261103", "20261104", "20261106")
        assertEquals(2, candleMarkerIndex(daily, "20261105", "D"))
        val weekly = listOf("20261026", "20261102", "20261109")
        assertEquals(1, candleMarkerIndex(weekly, "20261105", "W"))
        assertEquals(2, candleMarkerIndex(weekly, "20261113", "W"))
        assertNull(candleMarkerIndex(weekly, "20261125", "W"))
        assertNull(candleMarkerIndex(weekly, "20261001", "W"))
        val monthly = listOf("20260901", "20261001", "20261102")
        assertEquals(1, candleMarkerIndex(monthly, "20261031", "M"))
        val minutes = listOf("20261111", "20261111", "20261112")
        assertEquals(2, candleMarkerIndex(minutes, "20261112", "m5"))
        assertNull(candleMarkerIndex(minutes, "20261110", "m5"))
    }

    private fun d(source: String, date: String, title: String) = DisclosureDto(id = "$source-$date-$title", source = source, date = date, title = title, url = "u", mobileUrl = "m")

    @Test fun kindMarkersSkipDartDuplicates() {
        val dart = listOf(ChartMarker("20261110", MarkerKind.Major, "주요사항보고서(유상증자결정)"))
        val kind = toKindMarkers(listOf(d("KIND", "2026-11-10", "유상증자결정"), d("KIND", "2026-11-10", "기업설명회(IR)개최"), d("DART", "2026-11-10", "x")), dart)
        assertEquals(listOf("기업설명회(IR)개최"), kind.map { it.title })
        assertTrue(isPeriodicReport("[기재정정]사업보고서 (2025.12)"))
        assertFalse(isPeriodicReport("주요사항보고서(유상증자결정)"))
    }

    @Test fun rankingFilterAndCorpMapping() {
        val a = RankingItemDto(rank = 1, stockCode = "000660", corpCode = "00164779", corpName = "SK하이닉스", tags = listOf("공급계약"))
        val b = RankingItemDto(rank = 2, stockCode = "005380", corpCode = null, name = "현대차")
        assertEquals(listOf(a), filterRankings(listOf(a, b), onlyTagged = true, tag = null))
        assertEquals(listOf(a), filterRankings(listOf(a, b), onlyTagged = false, tag = "공급계약"))
        assertEquals(2, filterRankings(listOf(a, b), false, null).size)
        val corp = a.toCorp()!!
        assertTrue(corp.isListed && corp.stockCode == "000660")
        assertNull(b.toCorp())
    }

    @Test fun parsesServerJson() {
        val r = NetworkModule.json.decodeFromString(RankingsDto.serializer(), """{"kind":"rise","kind_label":"상승률","items":[{"rank":1,"stock_code":"000660","corp_code":"00164779","corp_name":"SK하이닉스","price":200000,"change_rate":11.1,"direction":"UP","tags":["공급계약"],"disclosures":[{"id":"DART-1","source":"DART","date":"2026-11-11","title":"단일판매ㆍ공급계약체결","url":"u","mobile_url":"m","tag":"공급계약"}],"unknown":1}]}""")
        assertEquals("단일판매ㆍ공급계약체결", r.items[0].disclosures[0].title)
        assertEquals(200000L, r.items[0].price)
        val f = NetworkModule.json.decodeFromString(FlowsDto.serializer(), """{"investors":[{"date":"2026-11-11","foreign":-3000,"individual":null}],"credit":[{"date":"2026-11-11","new":10,"balance":250000,"balance_rate":0.42}],"summary":{"d5":{"foreign":-3000},"d20":{}}}""")
        assertEquals(-3000L, f.investors[0].foreign)
        assertEquals(10L, f.credit[0].new)
        assertEquals(-3000L, f.summary.d5.foreign)
    }

    @Test fun realtimeMessagesParse() {
        val t = RealtimeClient.parse("""{"type":"trade","code":"005930","price":70500,"change":-100,"change_rate":-0.14,"direction":"DOWN","acc_volume":123}""")!!
        assertEquals(70500L, t.price); assertEquals("DOWN", t.direction)
        val ob = RealtimeClient.parse("""{"type":"orderbook","code":"005930","asks":[{"price":70600,"qty":10}],"bids":[{"price":70500,"qty":20}],"total_ask":10,"total_bid":20}""")!!
        assertEquals(70600L, ob.asks[0].price)
        assertNull(RealtimeClient.parse("not json"))
        assertEquals("""{"action":"subscribe","codes":["005930"]}""", RealtimeClient.subscribeMessage("005930\"]}"))
        assertEquals("https://x.example/api/v1/realtime", RealtimeClient.realtimeUrl("https://x.example/"))
    }

    @Test fun crashLogRedactsSecretsAndAccountLikeNumbers() {
        val s = CrashLog.redact("Authorization: Bearer abc.def-123 token=xyz 계좌 1234-56-789012 id 12345678901 line Foo.kt:123")
        assertFalse(s.contains("abc.def-123")); assertFalse(s.contains("xyz"))
        assertFalse(s.contains("1234-56-789012")); assertFalse(s.contains("12345678901"))
        assertTrue(s.contains("Foo.kt:123"))
    }

    @Test fun flowsTabIsAfterStock() {
        val tabs = CompanyTab.entries
        assertEquals(tabs.indexOf(CompanyTab.Stock) + 1, tabs.indexOf(CompanyTab.Flows))
    }
}
