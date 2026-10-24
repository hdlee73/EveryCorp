package io.github.hdlee73.everycorp.ui.alerts

import io.github.hdlee73.everycorp.data.api.dto.InsiderStakeDto
import io.github.hdlee73.everycorp.data.api.dto.MajorStakeDto
import io.github.hdlee73.everycorp.ui.common.Fmt
import java.util.Locale

/** 지분 변동 한 줄 설명 만들기(순수 함수, 단위 테스트 대상). */
object StakeText {
    private fun signed(v: Long) = (if (v > 0) "+" else "") + String.format(Locale.US, "%,d", v)

    private fun ratio(v: Double?) = if (v == null) null else String.format(Locale.US, "%.2f%%", v)

    private fun ratioChange(v: Double?) = if (v == null) null else String.format(Locale.US, "%+.2f%%p", v)

    /** 취득·처분 구분. 변동수량이 없으면 null. */
    fun direction(change: Long?): String? = when {
        change == null || change == 0L -> null
        change > 0 -> "취득"
        else -> "처분"
    }

    fun insider(i: InsiderStakeDto): String {
        val who = if (i.position.isNotBlank() && i.position != "-") "${i.reporter} (${i.position})" else i.reporter
        val change = i.sharesChange?.let { "${direction(it).orEmpty()} ${signed(it)}주" }
        val hold = ratio(i.ratio)?.let { "보유 $it" }
        return listOfNotNull(who, change, hold).joinToString(" · ")
    }

    fun major(m: MajorStakeDto): String {
        val hold = ratio(m.ratio)?.let { "보유 $it" + (ratioChange(m.ratioChange)?.let { c -> " ($c)" } ?: "") }
        val kind = m.reason.takeIf { it.isNotBlank() && it != "-" } ?: m.reportType.takeIf { it.isNotBlank() && it != "-" }
        return listOfNotNull(m.reporter, "5% 대량보유", kind, hold).joinToString(" · ")
    }

    fun date(raw: String) = Fmt.date(raw)
}
