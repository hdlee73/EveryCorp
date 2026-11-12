package io.github.hdlee73.everycorp.ui.common

import androidx.compose.ui.graphics.Color
import io.github.hdlee73.everycorp.data.api.dto.DisclosureDto

/** 주가 차트에 별로 찍는 공시 종류. 1년 라인 차트는 주요사항보고서만, 캔들 차트는 사용자가 종류를 골라 켠다. */
enum class MarkerKind(val label: String, val color: Color) {
    Major("DART 주요사항", Color(0xFFF5B301)),
    Periodic("DART 정기보고서", Color(0xFF8B5CF6)),
    Kind("KIND 거래소공시", Color(0xFF14B8A6)),
}

/** [date]는 하이픈 없는 YYYYMMDD. */
data class ChartMarker(val date: String, val kind: MarkerKind, val title: String, val disclosure: DisclosureDto? = null)

/** 공시 제목 → 마커 종류. 주요사항보고서(정정 접두어 허용)가 아니면 null(표시 안 함). */
fun classifyDisclosure(title: String): MarkerKind? =
    if (title.trim().replace(" ", "").replace(Regex("^(\\[[^\\]]+\\])+"), "").startsWith("주요사항보고서")) MarkerKind.Major else null

/** 사업·반기·분기보고서(정정 접두어 허용). */
fun isPeriodicReport(title: String): Boolean =
    title.trim().replace(" ", "").replace(Regex("^(\\[[^\\]]+\\])+"), "").let { t ->
        t.startsWith("사업보고서") || t.startsWith("반기보고서") || t.startsWith("분기보고서")
    }

/** DART 정기보고서 → 보라 별. */
fun toPeriodicMarkers(items: List<DisclosureDto>): List<ChartMarker> =
    items.filter { isPeriodicReport(it.title) }.map { ChartMarker(it.date.replace("-", ""), MarkerKind.Periodic, it.title.trim(), it) }

/** KIND 거래소 공시 → 청록 별. DART 주요사항과 같은 날 같은 제목(공백 무시)이면 중복이라 뺀다. */
fun toKindMarkers(items: List<DisclosureDto>, dart: List<ChartMarker> = emptyList()): List<ChartMarker> {
    fun norm(t: String) = t.replace(Regex("^(\\[[^\\]]+\\])+"), "").replace(Regex("[\\s()（）]"), "")
    val seen = dart.map { it.date to norm(it.title).removePrefix("주요사항보고서") }.toSet()
    return items.filter { it.source == "KIND" }.mapNotNull { d ->
        val date = d.date.replace("-", "")
        if (date.length != 8) return@mapNotNull null
        if ((date to norm(d.title)) in seen) return@mapNotNull null
        ChartMarker(date, MarkerKind.Kind, d.title.trim(), d)
    }
}

fun toMarkers(items: List<DisclosureDto>): List<ChartMarker> =
    items.mapNotNull { d ->
        val kind = classifyDisclosure(d.title) ?: return@mapNotNull null
        ChartMarker(d.date.replace("-", ""), kind, d.title.trim(), d)
    }

/** 마커 날짜가 거래일이 아니면 그 다음 거래일 위치에 찍는다. 차트 범위 밖이면 null. [dates]는 YYYYMMDD 오름차순. */
fun markerIndex(dates: List<String>, markerDate: String): Int? {
    if (dates.isEmpty() || markerDate < dates.first() || markerDate > dates.last()) return null
    val i = dates.indexOfFirst { it >= markerDate }
    return i.takeIf { it >= 0 }
}

/**
 * 캔들 차트용 마커 위치. [days]는 각 캔들의 시작일(YYYYMMDD) 오름차순.
 * - 일봉: 공시일이 휴장일이면 다음 거래일 캔들([markerIndex]와 같음).
 * - 주·월봉: 공시일을 포함하는 구간(시작일이 공시일 이하인 마지막 캔들).
 * - 분봉: 같은 날 캔들이 있을 때만 그날 첫 캔들.
 */
fun candleMarkerIndex(days: List<String>, markerDate: String, period: String): Int? {
    if (days.isEmpty()) return null
    return when {
        period == "D" -> markerIndex(days, markerDate)
        period == "W" || period == "M" -> {
            if (markerDate < days.first()) return null
            val i = days.indexOfLast { it <= markerDate }
            // 마지막 캔들보다 한참 뒤(다음 구간)면 범위 밖
            val limit = if (period == "W") 7 else 31
            if (i == days.lastIndex && dayGap(days.last(), markerDate) >= limit) null else i.takeIf { it >= 0 }
        }
        else -> days.indexOfFirst { it == markerDate }.takeIf { it >= 0 }
    }
}

private fun dayGap(a: String, b: String): Long = runCatching {
    val f = java.time.format.DateTimeFormatter.BASIC_ISO_DATE
    java.time.temporal.ChronoUnit.DAYS.between(java.time.LocalDate.parse(a, f), java.time.LocalDate.parse(b, f))
}.getOrDefault(0L)
