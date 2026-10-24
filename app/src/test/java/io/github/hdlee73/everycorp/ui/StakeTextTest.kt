package io.github.hdlee73.everycorp.ui

import io.github.hdlee73.everycorp.data.api.dto.InsiderStakeDto
import io.github.hdlee73.everycorp.data.api.dto.MajorStakeDto
import io.github.hdlee73.everycorp.ui.alerts.StakeText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StakeTextTest {
    @Test fun insiderLine() {
        val i = InsiderStakeDto(reporter = "홍길동", position = "대표이사", sharesChange = 12_000, ratio = 2.31)
        assertEquals("홍길동 (대표이사) · 취득 +12,000주 · 보유 2.31%", StakeText.insider(i))
        assertEquals("처분", StakeText.direction(-5))
        assertNull(StakeText.direction(0))
    }

    @Test fun majorLine() {
        val m = MajorStakeDto(reporter = "가나자산운용", reason = "신규보고", ratio = 5.12, ratioChange = 0.8)
        assertEquals("가나자산운용 · 5% 대량보유 · 신규보고 · 보유 5.12% (+0.80%p)", StakeText.major(m))
    }
}
