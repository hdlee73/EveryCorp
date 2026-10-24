package io.github.hdlee73.everycorp.ui.common

import androidx.compose.ui.graphics.Color
import io.github.hdlee73.everycorp.data.api.dto.DisclosureDto

/** 주가 차트에 점으로 찍는 공시 종류. */
enum class MarkerKind(val label: String, val color: Color) {
    Periodic("정기", Color(0xFF2F6BFF)),
    Major("주요사항", Color(0xFFE5372C)),
    Stake("지분", Color(0xFFF59E0B)),
}

/** [date]는 하이픈 없는 YYYYMMDD. */
data class ChartMarker(val date: String, val kind: MarkerKind, val title: String)

private val PERIODIC = Regex("(사업|반기|분기)보고서")

/** 공시 제목 → 마커 종류. 차트가 지저분해지지 않게 세 종류 외의 공시는 null(표시 안 함). */
fun classifyDisclosure(title: String): MarkerKind? {
    val t = title.replace(" ", "")
    return when {
        "대량보유" in t || "주요주주특정증권" in t -> MarkerKind.Stake
        PERIODIC.containsMatchIn(t) -> MarkerKind.Periodic
        "주요사항보고서" in t -> MarkerKind.Major
        else -> null
    }
}

fun toMarkers(items: List<DisclosureDto>): List<ChartMarker> =
    items.mapNotNull { d ->
        val kind = classifyDisclosure(d.title) ?: return@mapNotNull null
        ChartMarker(d.date.replace("-", ""), kind, d.title.trim())
    }

/** 마커 날짜가 거래일이 아니면 그 다음 거래일 위치에 찍는다. 차트 범위 밖이면 null. [dates]는 YYYYMMDD 오름차순. */
fun markerIndex(dates: List<String>, markerDate: String): Int? {
    if (dates.isEmpty() || markerDate < dates.first() || markerDate > dates.last()) return null
    val i = dates.indexOfFirst { it >= markerDate }
    return i.takeIf { it >= 0 }
}
