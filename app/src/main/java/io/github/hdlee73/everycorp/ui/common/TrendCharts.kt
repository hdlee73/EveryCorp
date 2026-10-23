package io.github.hdlee73.everycorp.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.hdlee73.everycorp.data.api.dto.TrendDto
import kotlin.math.abs

/** 5개 연도 매출·영업이익·순이익 묶음 막대(억원). 막대를 누르면 그 해 수치를 위에 보여준다. */
@Composable
fun TrendBars(trend: TrendDto, modifier: Modifier = Modifier) {
    val series = trend.series.filter { it.key in setOf("revenue", "operating_income", "net_income") && it.values.any { v -> v != null } }
    val years = trend.years
    if (series.isEmpty() || years.isEmpty()) return
    val colors = listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.tertiary, MaterialTheme.colorScheme.secondary)
    val zeroColor = MaterialTheme.colorScheme.outlineVariant
    var selected by remember(trend) { mutableStateOf<Int?>(years.size - 1) }

    val all = series.flatMap { s -> s.values.map { (it ?: 0.0) / 1e8 } }
    val maxV = maxOf(all.max(), 0.0)
    val minV = minOf(all.min(), 0.0)
    val span = (maxV - minV).takeIf { it > 0.0 } ?: 1.0

    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            series.forEachIndexed { i, s ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Box(Modifier.size(10.dp).background(colors[i % colors.size]))
                    Text(s.label, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        selected?.let { idx ->
            Text(
                "${years[idx]}년  " + series.joinToString(" · ") { "${it.label} ${Fmt.eok(it.values.getOrNull(idx))}억" },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Canvas(
            Modifier.fillMaxWidth().height(160.dp).pointerInput(trend) {
                detectTapGestures { o -> selected = (o.x / (size.width.toFloat() / years.size)).toInt().coerceIn(0, years.size - 1) }
            },
        ) {
            val groupW = size.width / years.size
            val barW = groupW * 0.7f / series.size
            fun y(v: Double) = (size.height * (maxV - v) / span).toFloat()
            val y0 = y(0.0)
            drawLine(zeroColor, Offset(0f, y0), Offset(size.width, y0), strokeWidth = 2f)
            years.indices.forEach { yi ->
                series.forEachIndexed { si, s ->
                    val v = (s.values.getOrNull(yi) ?: return@forEachIndexed) / 1e8
                    val top = minOf(y(v), y0)
                    val h = abs(y(v) - y0).coerceAtLeast(1f)
                    val c = colors[si % colors.size]
                    drawRect(if (selected == yi) c else c.copy(alpha = 0.6f), Offset(yi * groupW + groupW * 0.15f + si * barW, top), Size(barW, h))
                }
            }
        }
        YearAxis(years)
    }
}

/** 부채비율·EPS·PER·PBR 꺾은선. 점을 누르면 해당 연도 값을 보여준다. */
@Composable
fun TrendLine(trend: TrendDto, key: String, modifier: Modifier = Modifier) {
    val s = trend.series.firstOrNull { it.key == key } ?: return
    val years = trend.years
    if (years.size < 2 || s.values.none { it != null }) return
    val line = MaterialTheme.colorScheme.primary
    var selected by remember(trend) { mutableStateOf<Int?>(years.size - 1) }
    val vals = s.values.map { it?.toFloat() }
    val maxV = vals.filterNotNull().max()
    val minV = minOf(vals.filterNotNull().min(), 0f)
    val span = (maxV - minV).takeIf { it > 0f } ?: 1f

    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            selected?.let { i -> "${years[i]}년  ${s.label} ${vals.getOrNull(i)?.let { trendValue(key, it) } ?: "-"}" } ?: s.label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Canvas(
            Modifier.fillMaxWidth().height(110.dp).pointerInput(trend) {
                detectTapGestures { o -> selected = Math.round(o.x / size.width.toFloat() * (years.size - 1)).coerceIn(0, years.size - 1) }
            },
        ) {
            fun x(i: Int) = size.width * 0.04f + size.width * 0.92f * i / (years.size - 1)
            fun y(v: Float) = size.height * 0.08f + size.height * 0.84f * (1f - (v - minV) / span)
            val path = Path()
            var started = false
            vals.forEachIndexed { i, v ->
                if (v == null) return@forEachIndexed
                if (!started) { path.moveTo(x(i), y(v)); started = true } else path.lineTo(x(i), y(v))
            }
            drawPath(path, line, style = Stroke(width = 4f))
            vals.forEachIndexed { i, v ->
                if (v != null) drawCircle(if (selected == i) line else line.copy(alpha = 0.6f), if (selected == i) 9f else 6f, Offset(x(i), y(v)))
            }
        }
        YearAxis(years)
    }
}

private fun trendValue(key: String, v: Float): String = when (key) {
    "eps" -> String.format(java.util.Locale.US, "%,d원", v.toLong())
    "per", "pbr" -> String.format(java.util.Locale.US, "%.2f배", v)
    else -> String.format(java.util.Locale.US, "%.1f%%", v)
}

@Composable
private fun YearAxis(years: List<Int>) {
    Row(Modifier.fillMaxWidth()) {
        years.forEach { y ->
            Text("$y", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
    }
}
