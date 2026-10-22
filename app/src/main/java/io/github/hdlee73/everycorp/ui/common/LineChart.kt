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
import io.github.hdlee73.everycorp.data.api.dto.ChartPointDto

/**
 * 외부 라이브러리 없이 Canvas 로 그리는 라인 차트(종가).
 * - 손가락으로 누르거나 끌면 해당 일자의 종가를 보여준다.
 * - [high]/[low]는 52주 최고/최저 기준선으로 점선 표시.
 */
@Composable
fun PriceLineChart(points: List<ChartPointDto>, high: Long?, low: Long?, lineColor: Color, modifier: Modifier = Modifier) {
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
