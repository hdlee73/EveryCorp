package io.github.hdlee73.everycorp.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.util.Locale

/** 도넛 조각 색(라이트·다크 모두에서 구분되는 중간 톤). 마지막 '기타'는 회색. */
val DonutColors = listOf(
    Color(0xFF2F7BEA), Color(0xFF3EB489), Color(0xFFF2A93B), Color(0xFFE5566D), Color(0xFF8E6CEF),
    Color(0xFF29B6D1), Color(0xFFB57A4A), Color(0xFFD86FC1), Color(0xFF7FA243),
)
private val OtherColor = Color(0xFF9AA3AE)

fun donutColor(index: Int, label: String): Color = if (label.startsWith("기타")) OtherColor else DonutColors[index % DonutColors.size]

/**
 * 비중 도넛 + 범례. [slices]는 (이름, 비중%) 목록이며 합이 100이 아니어도 합 기준으로 나눠 그린다.
 * 각 조각 각도는 slices 가 바뀔 때만 다시 계산한다.
 */
@Composable
fun DonutChart(slices: List<Pair<String, Double>>, modifier: Modifier = Modifier, diameter: Dp = 150.dp, centerText: String? = null) {
    val positive = remember(slices) { slices.filter { it.second > 0 } }
    val total = remember(positive) { positive.sumOf { it.second } }
    if (positive.isEmpty() || total <= 0.0) {
        Text("비중 자료가 없습니다.", modifier, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    val sweeps = remember(positive, total) { positive.map { (it.second / total * 360.0).toFloat() } }
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(Modifier.size(diameter), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(diameter)) {
                val stroke = size.minDimension * 0.22f
                val inset = stroke / 2
                val arcSize = Size(size.width - stroke, size.height - stroke)
                var start = -90f
                sweeps.forEachIndexed { i, sw ->
                    // 조각 사이 아주 얇은 틈(0.6°)으로 경계를 보이게 한다. 너무 작은 조각은 틈 없이 그린다.
                    val gap = if (sw > 2f) 0.6f else 0f
                    drawArc(
                        color = donutColor(i, positive[i].first), startAngle = start + gap / 2, sweepAngle = sw - gap,
                        useCenter = false, topLeft = Offset(inset, inset), size = arcSize, style = Stroke(width = stroke),
                    )
                    start += sw
                }
            }
            if (centerText != null) Text(centerText, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            positive.forEachIndexed { i, (label, w) ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.size(9.dp).background(donutColor(i, label), CircleShape))
                    Text(label, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(String.format(Locale.US, "%.1f%%", w), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
