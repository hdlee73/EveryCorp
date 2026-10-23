package io.github.hdlee73.everycorp.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import io.github.hdlee73.everycorp.ui.theme.StockColors
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import io.github.hdlee73.everycorp.data.api.dto.ChartPointDto

/**
 * 외부 라이브러리 없이 Canvas 로 그리는 라인 차트(종가).
 * - 손가락으로 누르거나 끌면 해당 일자의 종가를 보여준다.
 * - [high]/[low]는 52주 최고/최저 기준선으로 점선과 가격·일자 라벨을 그래프 안에 표시.
 */
@Composable
fun PriceLineChart(points: List<ChartPointDto>, high: Long?, low: Long?, lineColor: Color, modifier: Modifier = Modifier, highDate: String? = null, lowDate: String? = null) {
    val data = remember(points) { points.filter { it.close != null } }
    if (data.size < 2) return
    var selected by remember(data) { mutableStateOf<Int?>(null) }
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant

    val closes = data.map { it.close!!.toFloat() }
    val maxV = maxOf(closes.max(), high?.toFloat() ?: closes.max())
    val minV = minOf(closes.min(), low?.toFloat() ?: closes.min())
    val span = (maxV - minV).takeIf { it > 0f } ?: 1f

    Column(modifier) {
        val sel = selected?.let { data.getOrNull(it) }
        Text(
            if (sel != null) "${Fmt.date(sel.date)}  ${Fmt.price(sel.close)}원" else "차트를 눌러 일별 종가 확인",
            style = MaterialTheme.typography.labelMedium,
            color = labelColor,
        )
        Canvas(
            Modifier.fillMaxWidth().height(200.dp)
                .pointerInput(data) { detectTapGestures { o -> selected = indexAt(o.x, size.width.toFloat(), data.size) } }
                .pointerInput(data) { detectDragGestures(onDragEnd = {}, onDrag = { c, _ -> selected = indexAt(c.position.x, size.width.toFloat(), data.size) }) },
        ) {
            fun x(i: Int) = size.width * i / (data.size - 1)
            fun y(v: Float) = size.height * (1f - (v - minV) / span)
            // 가로 기준선(52주 최고/최저)
            val dash = PathEffect.dashPathEffect(floatArrayOf(10f, 10f))
            listOfNotNull(high, low).forEach { v ->
                drawLine(gridColor, Offset(0f, y(v.toFloat())), Offset(size.width, y(v.toFloat())), strokeWidth = 2f, pathEffect = dash)
            }
            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { textSize = 11.sp.toPx() }
            // 라벨은 해당 일자 근방에 놓는다. 일자를 못 찾으면 종가 최고/최저 위치로 대신한다.
            fun idxOf(date: String?, fallback: Int) = date?.let { d -> data.indexOfFirst { it.date == d }.takeIf { it >= 0 } } ?: fallback
            fun label(text: String, cx: Float, py: Float, above: Boolean, color: Color) {
                paint.color = color.toArgb()
                val w = paint.measureText(text)
                val tx = (cx - w / 2).coerceIn(4f, (size.width - w - 4f).coerceAtLeast(4f))
                val ty = if (above) py - 14f else py + paint.textSize + 10f
                drawContext.canvas.nativeCanvas.drawText(text, tx, ty.coerceIn(paint.textSize, size.height - 4f), paint)
                drawCircle(color, radius = 7f, center = Offset(cx, py))
            }
            high?.let { v ->
                val i = idxOf(highDate, closes.indices.maxByOrNull { closes[it] } ?: 0)
                label("최고 ${Fmt.price(v)}원" + (highDate?.let { " (${Fmt.date(it)})" } ?: ""), x(i), y(v.toFloat()), above = true, color = StockColors.up)
            }
            low?.let { v ->
                val i = idxOf(lowDate, closes.indices.minByOrNull { closes[it] } ?: 0)
                label("최저 ${Fmt.price(v)}원" + (lowDate?.let { " (${Fmt.date(it)})" } ?: ""), x(i), y(v.toFloat()), above = false, color = StockColors.down)
            }
            val line = Path().apply {
                closes.forEachIndexed { i, v -> if (i == 0) moveTo(x(i), y(v)) else lineTo(x(i), y(v)) }
            }
            val fill = Path().apply {
                addPath(line); lineTo(size.width, size.height); lineTo(0f, size.height); close()
            }
            drawPath(fill, Brush.verticalGradient(listOf(lineColor.copy(alpha = 0.18f), Color.Transparent)))
            drawPath(line, lineColor, style = Stroke(width = 4f))
            selected?.let { i ->
                drawLine(labelColor, Offset(x(i), 0f), Offset(x(i), size.height), strokeWidth = 2f)
                drawCircle(lineColor, radius = 9f, center = Offset(x(i), y(closes[i])))
            }
        }
    }
}

private fun indexAt(px: Float, width: Float, n: Int): Int =
    ((px / width) * (n - 1)).toInt().coerceIn(0, n - 1)

/**
 * 지그재그 피벗 인덱스. 직전 극값에서 [pct] 이상 반대로 움직이면 방향 전환으로 보고 그 극값을 피벗으로 확정한다.
 * 첫·끝 인덱스는 항상 포함하므로 인접 피벗 사이 구간마다 상승/하락 색을 하나씩 정하면 된다.
 */
internal fun swingPivots(v: List<Float>, pct: Float): List<Int> {
    if (v.size < 2) return listOf(0)
    val out = mutableListOf(0)
    var dir = 0
    var lo = 0; var hi = 0   // 방향이 정해지기 전 최저·최고 위치
    var ext = 0              // 방향이 정해진 뒤 현재 구간의 극값 위치
    for (i in 1 until v.size) {
        when (dir) {
            0 -> {
                if (v[i] < v[lo]) lo = i
                if (v[i] > v[hi]) hi = i
                if (v[i] >= v[lo] * (1 + pct)) { if (lo != 0) out.add(lo); dir = 1; ext = i }
                else if (v[i] <= v[hi] * (1 - pct)) { if (hi != 0) out.add(hi); dir = -1; ext = i }
            }
            1 -> if (v[i] >= v[ext]) ext = i else if (v[i] <= v[ext] * (1 - pct)) { out.add(ext); dir = -1; ext = i }
            else -> if (v[i] <= v[ext]) ext = i else if (v[i] >= v[ext] * (1 + pct)) { out.add(ext); dir = 1; ext = i }
        }
    }
    if (out.last() != v.lastIndex) out.add(v.lastIndex)
    return out.distinct()
}
