package io.github.hdlee73.everycorp.ui

import io.github.hdlee73.everycorp.data.api.dto.FinancialsDto
import io.github.hdlee73.everycorp.data.api.dto.OverviewDto
import io.github.hdlee73.everycorp.data.api.dto.TrendDto
import io.github.hdlee73.everycorp.data.api.dto.TrendSeriesDto
import io.github.hdlee73.everycorp.ui.compare.CompareLogic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CompareLogicTest {
    private fun overview(trend: TrendDto?) =
        OverviewDto(corpCode = "1", corpName = "예시", financials = FinancialsDto(available = true, trend = trend))

    @Test fun takesLatestYearAndComputesMargin() {
        val t = TrendDto(
            years = listOf(2024, 2025),
            series = listOf(
                TrendSeriesDto("revenue", "매출액", listOf(1e11, 2e11)),
                TrendSeriesDto("operating_income", "영업이익", listOf(5e9, 2e10)),
                TrendSeriesDto("debt_ratio", "부채비율(%)", listOf(40.0, 35.5)),
            ),
        )
        val c = CompareLogic.column("예시", overview(t))
        assertEquals(2025, c.year)
        assertEquals(10.0, c.values["operating_margin"]!!, 1e-9)
        assertEquals("2,000", CompareLogic.format("revenue", c.values["revenue"]))
        assertEquals("35.5%", CompareLogic.format("debt_ratio", c.values["debt_ratio"]))
        assertEquals("-", CompareLogic.format("per", c.values["per"]))
    }

    @Test fun missingTrendGivesEmptyColumn() {
        val c = CompareLogic.column("비상장", overview(null))
        assertNull(c.year)
        assertEquals(emptyMap<String, Double?>(), c.values)
    }
}
