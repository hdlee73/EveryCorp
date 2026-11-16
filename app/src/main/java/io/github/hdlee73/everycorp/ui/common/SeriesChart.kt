package io.github.hdlee73.everycorp.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 꺾은선 한 줄. [values]는 [SeriesLineChart]의 dates 와 같은 길이(빈 날은 null → 선이 끊긴다). */
@Immutable
data class ChartSeries(val label: String, val color: Color, val values: List<Double?>)

/**
 * 범주형 색(고정 순서, 순환 금지). 8번째 이후는 만들지 않고 '기타'로 묶는다.
 * 밝은/어두운 화면 각각에 맞춘 단계(데이터 시각화 기본 팔레트).
 */
object SeriesColors {
    private val light = listOf(0xFF2A78D6, 0xFFEB6834, 0xFF1BAF7A, 0xFFEDA100, 0xFFE87BA4, 0xFF008300, 0xFF6250D6).map { Color(it) }
    private val dark = listOf(0xFF3987E5, 0xFFD95926, 0xFF199E70, 0xFFC98500, 0xFFD55181, 0xFF0CA30C, 0xFF9085E9).map { Color(it) }
    /** '기타'·합계 같은 묶음 계열. */
    val other = Color(0xFF8A94A3)
    const val MAX = 7

    @Composable
    fun at(i: Int): Color {
        val list = if (isSystemInDarkTheme()) dark else light
        return list.getOrNull(i) ?: other
    }
}

/**
 * 날짜축 공통의 여러 꺾은선(한 개의 y축). 누르거나 끌면 그날 모든 계열 값을 위에 보여 준다.
 * 2개 이상이면 범례를 붙인다(색만으로 구분하지 않도록 이름을 함께 표시).
 */
@Composable
fun SeriesLineChart(
    dates: List<String>,
    series: List<ChartSeries>,
    format: (Double) -> String,
    modifier: Modifier = Modifier,
    height: Dp = 200.dp,
    hint: String = "차트를 누르거나 끌어 날짜별 값 확인",
) {
    val n = dates.size
    val bounds = remember(series) {
        val all = series.flatMap { s -> s.values.filterNotNull() }
        if (all.isEmpty()) null else {
            val lo = all.min(); val hi = all.max()
            val pad = ((hi - lo).takeIf { it > 0 } ?: kotlin.math.abs(hi).coerceAtLeast(1.0)) * 0.08
            (lo - pad) to (hi + pad)
        }
    }
    if (n < 2 || bounds == null) {
        Text(
            if (n < 2) "추이를 그리려면 이틀 이상의 자료가 필요합니다." else "표시할 값이 없습니다.",
            modifier, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    var selected by remember(dates, series) { mutableStateOf<Int?>(null) }
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val surface = MaterialTheme.colorScheme.surface
    val (minV, maxV) = bounds
    val span = (maxV - minV).takeIf { it > 0 } ?: 1.0

    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val sel = selected
        Text(
            if (sel == null) hint
            else Fmt.date(dates[sel]) + "  " + series.joinToString("  ·  ") { s -> (if (series.size > 1) "${s.label} " else "") + (s.values.getOrNull(sel)?.let(format) ?: "-") },
            style = MaterialTheme.typography.labelMedium, color = labelColor, maxLines = 4,
        )
        Canvas(
            Modifier.fillMaxWidth().height(height)
                .pointerInput(n) { detectTapGestures { o -> selected = indexAt(o.x, size.width.toFloat(), n) } }
                .pointerInput(n) { detectDragGestures(onDrag = { c, _ -> selected = indexAt(c.position.x, size.width.toFloat(), n) }) },
        ) {
            val left = 0f
            val w = size.width
            val h = size.height
            fun x(i: Int) = left + w * i / (n - 1)
            fun y(v: Double) = (h * (1.0 - (v - minV) / span)).toFloat()
            // 가로 기준선 3개(위·가운데·아래)와 값 라벨
            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { textSize = 10.sp.toPx(); color = labelColor.toArgb() }
            listOf(maxV - span * 0.08 / 1.16, (minV + maxV) / 2, minV + span * 0.08 / 1.16).forEach { v ->
                val yy = y(v)
                drawLine(gridColor.copy(alpha = 0.6f), Offset(0f, yy), Offset(w, yy), strokeWidth = 1f)
                drawContext.canvas.nativeCanvas.drawText(format(v), 4f, (yy - 6f).coerceAtLeast(paint.textSize), paint)
            }
            series.forEach { s ->
                val path = Path()
                var open = false
                s.values.forEachIndexed { i, v ->
                    if (v == null || i >= n) { open = false; return@forEachIndexed }
                    if (!open) { path.moveTo(x(i), y(v)); open = true } else path.lineTo(x(i), y(v))
                }
                drawPath(path, s.color, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
            sel?.let { i ->
                drawLine(labelColor.copy(alpha = 0.7f), Offset(x(i), 0f), Offset(x(i), h), strokeWidth = 1.5f)
                series.forEach { s ->
                    s.values.getOrNull(i)?.let { v ->
                        drawCircle(surface, radius = 6.dp.toPx() / 2 + 2f, center = Offset(x(i), y(v)))
                        drawCircle(s.color, radius = 4.dp.toPx() / 2 + 1f, center = Offset(x(i), y(v)))
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth()) {
            Text(Fmt.date(dates.first()), style = MaterialTheme.typography.labelSmall, color = labelColor)
            Box(Modifier.weight(1f))
            Text(Fmt.date(dates.last()), style = MaterialTheme.typography.labelSmall, color = labelColor)
        }
        if (series.size > 1) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                series.forEach { s ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(10.dp).background(s.color, RoundedCornerShape(2.dp)))
                        Text(" " + s.label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
        }
    }
}

private fun indexAt(px: Float, width: Float, n: Int): Int =
    if (width <= 0f) 0 else ((px / width) * (n - 1) + 0.5f).toInt().coerceIn(0, n - 1)

/** 차트 값 축약 표기: 원 → "12.3조" / "4,567억". */
object StatFmt {
    fun won(v: Double): String {
        val eok = v / 1e8
        return when {
            kotlin.math.abs(eok) >= 10_000 -> String.format(java.util.Locale.US, "%,.1f조", eok / 10_000)
            kotlin.math.abs(eok) >= 1 -> String.format(java.util.Locale.US, "%,.0f억", eok)
            else -> String.format(java.util.Locale.US, "%,.2f억", eok)
        }
    }

    /** 부호를 붙인 금액 변화. */
    fun wonSigned(v: Double): String = (if (v > 0) "+" else if (v < 0) "−" else "") + won(kotlin.math.abs(v))

    fun count(v: Double): String = String.format(java.util.Locale.US, "%,d", Math.round(v))

    fun countSigned(v: Long): String = when {
        v > 0 -> String.format(java.util.Locale.US, "+%,d", v)
        v < 0 -> String.format(java.util.Locale.US, "−%,d", -v)
        else -> "0"
    }

    fun pctSigned(v: Double?): String = if (v == null) "-" else String.format(java.util.Locale.US, "%+.1f%%", v)
}
