package io.github.hdlee73.everycorp.ui.common

import java.util.Locale

/** 화면 표시용 문자열 만들기(순수 함수, 단위 테스트 대상). */
object Fmt {
    fun price(v: Long?): String = if (v == null) "-" else String.format(Locale.US, "%,d", v)

    fun number(v: Long?): String = price(v)

    /** 등락 표시: 상승 "▲ 500 (+0.70%)", 하락 "▼ 500 (-0.70%)", 보합 "- 0 (0.00%)". 부호는 방향 기호가 대신 보여주므로 금액은 절댓값. */
    fun change(change: Long, rate: Double, direction: String): String {
        val abs = kotlin.math.abs(change)
        val r = kotlin.math.abs(rate)
        return when (direction) {
            "UP" -> String.format(Locale.US, "▲ %,d (+%.2f%%)", abs, r)
            "DOWN" -> String.format(Locale.US, "▼ %,d (-%.2f%%)", abs, r)
            else -> String.format(Locale.US, "- %,d (%.2f%%)", abs, r)
        }
    }

    /** "20260310" → "2026.03.10", "2026-03-10" → "2026.03.10" */
    fun date(raw: String): String {
        val d = raw.replace("-", "")
        return if (d.length == 8) "${d.substring(0, 4)}.${d.substring(4, 6)}.${d.substring(6, 8)}" else raw
    }

    /** 파일 크기 → "7.7MB" */
    fun fileSize(bytes: Long?): String =
        if (bytes == null || bytes <= 0) "" else String.format(Locale.US, "%.1fMB", bytes / 1024.0 / 1024.0)

    /** 지분율 등 백분율 */
    fun percent(v: Double?): String = if (v == null) "-" else String.format(Locale.US, "%.1f%%", v)

    /** 원 → 억원(반올림, 천 단위 구분). 예: 514531948000000 → "5,145,319" */
    fun eok(won: Double?): String = if (won == null) "-" else String.format(Locale.US, "%,d", Math.round(won / 100_000_000.0))

    /** 1조 이상이면 "514.5조원", 미만이면 null(억원만 표시). */
    fun jo(won: Double?): String? =
        if (won == null || kotlin.math.abs(won) < 1_000_000_000_000.0) null else String.format(Locale.US, "%,.1f조원", won / 1_000_000_000_000.0)
}
