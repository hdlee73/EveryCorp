package io.github.hdlee73.everycorp.ui.compare

import io.github.hdlee73.everycorp.data.api.dto.OverviewDto
import io.github.hdlee73.everycorp.ui.common.Fmt
import java.util.Locale

/** 비교표 한 열(기업 하나). [year]는 값이 속한 최근 사업연도, 재무 정보가 없으면 null. */
data class CompareColumn(val name: String, val year: Int?, val values: Map<String, Double?>)

object CompareLogic {
    /** (키, 표시 이름). 키는 서버 추이 시리즈 키 + 계산값(operating_margin). */
    val ROWS = listOf(
        "revenue" to "매출액(억원)",
        "operating_income" to "영업이익(억원)",
        "net_income" to "당기순이익(억원)",
        "operating_margin" to "영업이익률",
        "debt_ratio" to "부채비율",
        "eps" to "EPS(원)",
        "per" to "PER(배)",
        "pbr" to "PBR(배)",
    )

    /** 개요의 5개년 추이에서 가장 최근 연도 값을 뽑는다. 영업이익률 = 영업이익 ÷ 매출액. */
    fun column(name: String, overview: OverviewDto?): CompareColumn {
        val t = overview?.financials?.trend ?: return CompareColumn(name, null, emptyMap())
        val last = t.years.lastIndex
        if (last < 0) return CompareColumn(name, null, emptyMap())
        fun v(key: String) = t.series.firstOrNull { it.key == key }?.values?.getOrNull(last)
        val revenue = v("revenue")
        val operating = v("operating_income")
        val margin = if (revenue != null && operating != null && revenue != 0.0) operating / revenue * 100 else null
        val values = ROWS.map { it.first }.associateWith { if (it == "operating_margin") margin else v(it) }
        return CompareColumn(name, t.years[last], values)
    }

    fun format(key: String, v: Double?): String = when {
        v == null -> "-"
        key == "revenue" || key == "operating_income" || key == "net_income" -> Fmt.eok(v)
        key == "operating_margin" || key == "debt_ratio" -> String.format(Locale.US, "%.1f%%", v)
        key == "eps" -> String.format(Locale.US, "%,d", Math.round(v))
        else -> String.format(Locale.US, "%.2f", v)
    }
}
