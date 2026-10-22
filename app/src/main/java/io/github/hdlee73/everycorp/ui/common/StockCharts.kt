package io.github.hdlee73.everycorp.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.hdlee73.everycorp.data.api.dto.ChartPointDto
import io.github.hdlee73.everycorp.data.api.dto.IntradayPointDto
import io.github.hdlee73.everycorp.ui.theme.StockColors

private const val SESSION_START = 9 * 60          // 09:00
private const val SESSION_MINUTES = 6 * 60 + 30   // 09:00 ~ 15:30

/** "09:31" → 장 시작 후 경과 분(0~390). 해석 못 하면 null. */
fun sessionMinute(time: String): Int? {
    val p = time.split(":")
    val h = p.getOrNull(0)?.toIntOrNull() ?: return null
    val m = p.getOrNull(1)?.toIntOrNull() ?: return null
    return (h * 60 + m - SESSION_START).coerceIn(0, SESSION_MINUTES)
}

/** 당일 주가 추이 미니 차트. 가로축은 장 시간(09:00~15:30) 고정이라 장중에는 오른쪽이 비어 있다. 점선은 전일 종가. */
@Composable
fun IntradaySparkline(points: List<IntradayPointDto>, prevClose: Long?, lineColor: Color, modifier: Modifier = Modifier) {
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    Canvas(modifier) {
        val prices = points.map { it.price.toFloat() }
        if (prices.size < 2) return@Canvas
        val base = prevClose?.toFloat()
        val maxV = maxOf(prices.max(), base ?: prices.max())
        val minV = minOf(prices.min(), base ?: prices.min())
        val span = (maxV - minV).takeIf { it > 0f } ?: 1f
        fun x(t: String) = size.width * (sessionMinute(t) ?: 0) / SESSION_MINUTES
        fun y(v: Float) = size.height * 0.08f + size.height * 0.84f * (1f - (v - minV) / span)
        if (base != null) drawLine(gridColor, Offset(0f, y(base)), Offset(size.width, y(base)), strokeWidth = 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f)))
        val line = Path()
        points.forEachIndexed { i, p -> if (i == 0) line.moveTo(x(p.time), y(p.price.toFloat())) else line.lineTo(x(p.time), y(p.price.toFloat())) }
        val lastX = x(points.last().time)
        val fill = Path().apply { addPath(line); lineTo(lastX, size.height); lineTo(x(points.first().time), size.height); close() }
        drawPath(fill, Brush.verticalGradient(listOf(lineColor.copy(alpha = 0.18f), Color.Transparent)))
        drawPath(line, lineColor, style = Stroke(width = 3f))
        drawCircle(lineColor, radius = 5f, center = Offset(lastX, y(prices.last())))
    }
}

/** 일별 거래량 막대. 종가가 전일보다 오른 날은 빨강, 내린 날은 파랑(국내 관례). */
@Composable
fun VolumeBarChart(points: List<ChartPointDto>, modifier: Modifier = Modifier) {
    val data = points.filter { it.volume != null }
    if (data.size < 2) return
    Canvas(modifier) {
        val maxV = data.maxOf { it.volume!! }.toFloat().takeIf { it > 0f } ?: 1f
        val w = size.width / data.size
        data.forEachIndexed { i, p ->
            val prev = data.getOrNull(i - 1)?.close
            val up = prev == null || (p.close ?: prev) >= prev
            val h = size.height * (p.volume!! / maxV)
            drawRect(
                (if (up) StockColors.up else StockColors.down).copy(alpha = 0.75f),
                topLeft = Offset(i * w, size.height - h), size = Size((w * 0.8f).coerceAtLeast(1f), h),
            )
        }
    }
}

/** 시가·고가·저가·거래량 등을 2열 칸으로 정리한 표. 값이 없는 칸은 숨긴다. */
@Composable
fun StatGrid(items: List<Pair<String, String?>>, modifier: Modifier = Modifier) {
    val shown = items.filter { !it.second.isNullOrBlank() }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        shown.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { (label, value) -> StatTile(label, value.orEmpty(), Modifier.weight(1f)) }
                if (row.size == 1) Box(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun StatTile(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(
        modifier, shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.6f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 9.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    }
}
