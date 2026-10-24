package io.github.hdlee73.everycorp.ui

import io.github.hdlee73.everycorp.ui.common.MarkerKind
import io.github.hdlee73.everycorp.ui.common.classifyDisclosure
import io.github.hdlee73.everycorp.ui.common.markerIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DisclosureMarkersTest {
    @Test fun classifiesByTitle() {
        assertEquals(MarkerKind.Periodic, classifyDisclosure("분기보고서 (2026.03)"))
        assertEquals(MarkerKind.Periodic, classifyDisclosure("[기재정정]사업보고서 (2025.12)"))
        assertEquals(MarkerKind.Major, classifyDisclosure("주요사항보고서(전환사채권발행결정)"))
        assertEquals(MarkerKind.Stake, classifyDisclosure("주식등의대량보유상황보고서(일반)"))
        assertEquals(MarkerKind.Stake, classifyDisclosure("임원ㆍ주요주주특정증권등소유상황보고서"))
        assertNull(classifyDisclosure("연결감사보고서 (2025.12)"))
        assertNull(classifyDisclosure("기업설명회(IR)개최"))
    }

    @Test fun markerFallsToNextTradingDay() {
        val dates = listOf("20260105", "20260106", "20260108")
        assertEquals(1, markerIndex(dates, "20260106"))
        assertEquals(2, markerIndex(dates, "20260107"))
        assertNull(markerIndex(dates, "20251231"))
        assertNull(markerIndex(dates, "20260109"))
    }
}
