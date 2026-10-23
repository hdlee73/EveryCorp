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

/** 당일 주가 추이 미니 차트. 가로축은 장 시간(09:00~15:30) 고정이라 장중에는 오른쪽이 비어 있다. 점선은 전일 종가이고 그 위는 빨강, 아래는 파랑. */
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
        // 전일 종가 위는 빨강, 아래는 파랑. 기준선을 가로지르는 구간은 교차점에서 나눠 색을 바꾼다.
        // 전일 종가를 모르면 0.4% 이상 되돌릴 때마다 오름(빨강)/내림(파랑)으로 나눈다.
        if (base != null) {
            for (i in 1 until points.size) {
                val x0 = x(points[i - 1].time); val x1 = x(points[i].time)
                val p0 = prices[i - 1]; val p1 = prices[i]
                fun seg(xa: Float, pa: Float, xb: Float, pb: Float, up: Boolean) =
                    drawLine(if (up) StockColors.up else StockColors.down, Offset(xa, y(pa)), Offset(xb, y(pb)), strokeWidth = 3f)
                if ((p0 - base) * (p1 - base) < 0f) {
                    val t = (base - p0) / (p1 - p0)
                    val xm = x0 + (x1 - x0) * t
                    seg(x0, p0, xm, base, p0 > base)
                    seg(xm, base, x1, p1, p1 > base)
                } else seg(x0, p0, x1, p1, (p0 + p1) / 2f >= base)
            }
        } else {
            val pivots = swingPivots(prices, 0.004f)
            for (k in 0 until pivots.size - 1) {
                val a = pivots[k]; val b = pivots[k + 1]
                val seg = Path()
                for (i in a..b) { val px = x(points[i].time); val py = y(prices[i]); if (i == a) seg.moveTo(px, py) else seg.lineTo(px, py) }
                drawPath(seg, if (prices[b] >= prices[a]) StockColors.up else StockColors.down, style = Stroke(width = 3f))
            }
        }
        drawCircle(if (base != null && prices.last() < base) StockColors.down else if (base != null) StockColors.up else lineColor, radius = 5f, center = Offset(lastX, y(prices.last())))
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

/** 시가·고가·저가·거래량 등을 작은 3열 칸으로 정리한 표(9개 항목이 3줄). 값이 없는 칸은 숨긴다. */
@Composable
fun StatGrid(items: List<Pair<String, String?>>, modifier: Modifier = Modifier, columns: Int = 3) {
    val shown = items.filter { !it.second.isNullOrBlank() }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        shown.chunked(columns).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { (label, value) -> StatTile(label, value.orEmpty(), Modifier.weight(1f)) }
                repeat(columns - row.size) { Box(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun StatTile(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(
        modifier, shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.6f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
    ) {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    }
}
