package io.github.hdlee73.everycorp.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.hdlee73.everycorp.data.api.dto.CandleDto
import io.github.hdlee73.everycorp.ui.theme.StockColors

/** 가격 위에 겹쳐 그리는 지표. */
enum class Overlay(val label: String) { MA("이동평균"), BB("볼린저") }

/** 아래 보조 영역 지표. */
enum class SubIndicator(val label: String) { Volume("거래량"), RSI("RSI"), MACD("MACD") }

/** 이동평균 기간과 색(5·20·60·120). */
private val MA_SPECS = listOf(5 to Color(0xFFF59E0B), 20 to Color(0xFFE11D48), 60 to Color(0xFF10B981), 120 to Color(0xFF6366F1))
private val BB_COLOR = Color(0xFF8B5CF6)

/** 차트 계산 결과(캔들이 바뀔 때만 다시 계산). */
private class Computed(candles: List<CandleDto>) {
    val closes = candles.map { it.c.toDouble() }
    val ma = MA_SPECS.map { (n, c) -> Triple(n, c, Indicators.sma(closes, n)) }
    val bb = Indicators.bollinger(closes)
    val rsi = Indicators.rsi(closes)
    val macd = Indicators.macd(closes)
    val days = candles.map { it.day }
}

/**
 * Canvas 캔들 차트(외부 라이브러리 없음). 두 손가락으로 확대·축소, 끌어서 과거로 이동, 누르면 해당 캔들·공시 표시.
 * [period]는 서버 period 값(m1…, D, W, M). 상승(종가≥시가)은 빨강, 하락은 파랑.
 */
@Composable
fun CandleChart(
    candles: List<CandleDto>,
    period: String,
    overlays: Set<Overlay>,
    sub: SubIndicator?,
    markers: List<ChartMarker>,
    modifier: Modifier = Modifier,
    onMarkersSelected: (List<ChartMarker>) -> Unit = {},
) {
    if (candles.size < 2) return
    val comp = remember(candles) { Computed(candles) }
    val placed = remember(candles, markers, period) {
        markers.mapNotNull { m -> candleMarkerIndex(comp.days, m.date, period)?.let { m to it } }
    }
    val defaultVisible = 70.coerceAtMost(candles.size)
    var visible by remember(candles.size, period) { mutableIntStateOf(defaultVisible) }
    var endIndex by remember(candles.size, period) { mutableIntStateOf(candles.lastIndex) }
    var selected by remember(candles, period) { mutableStateOf<Int?>(null) }
    var selectedMarkers by remember(candles, markers, period) { mutableStateOf<List<ChartMarker>>(emptyList()) }

    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val intraday = period.startsWith("m")

    val start = (endIndex - visible + 1).coerceAtLeast(0)
    val end = endIndex.coerceIn(start, candles.lastIndex)

    Column(modifier) {
        val sel = selected?.let { candles.getOrNull(it) }
        val sm = selectedMarkers
        Text(
            when {
                sm.isNotEmpty() -> "${Fmt.date(sm.first().date)} 공시 ${sm.size}건\n" + sm.joinToString("\n") { "· [${it.kind.label}] ${it.title}" }
                sel != null -> candleLabel(sel, intraday)
                else -> "두 손가락으로 확대·축소, 끌어서 이동 · 별을 누르면 공시 내용"
            },
            style = MaterialTheme.typography.labelMedium,
            color = sm.firstOrNull()?.kind?.color ?: labelColor,
            maxLines = 8,
        )
        val pricePanel = Modifier.fillMaxWidth().height(230.dp)
            .pointerInput(candles, period, placed) {
                detectTapGestures { o ->
                    // start/end 는 상태(visible, endIndex)에서 바로 계산해 확대·이동 뒤에도 맞게 한다.
                    val e = endIndex.coerceIn(0, candles.lastIndex)
                    val s0 = (e - visible + 1).coerceAtLeast(0)
                    val slot = size.width.toFloat() * PRICE_AREA / (e - s0 + 1)
                    val i = (s0 + (o.x / slot).toInt()).coerceIn(s0, e)
                    selected = i
                    val hit = placed.filter { it.second == i }.map { it.first }
                    selectedMarkers = hit
                    onMarkersSelected(hit)
                }
            }
            .pointerInput(candles.size, period) {
                // 두 손가락: 확대·축소. 한 손가락: 가로로 끌 때만 이동(세로로 끌면 화면 스크롤에 양보).
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    var horizontal: Boolean? = null
                    var acc = Offset.Zero
                    var dragAccum = 0f
                    do {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.size >= 2) {
                            val zoom = event.calculateZoom()
                            if (zoom != 1f) visible = (visible / zoom).toInt().coerceIn(20.coerceAtMost(candles.size), candles.size)
                            event.changes.forEach { it.consume() }
                        } else if (pressed.size == 1) {
                            val ch = pressed[0]
                            val d = ch.positionChange()
                            if (horizontal == null) {
                                acc += d
                                if (acc.getDistance() > viewConfiguration.touchSlop) horizontal = kotlin.math.abs(acc.x) > kotlin.math.abs(acc.y)
                            }
                            if (horizontal == true) {
                                val slot = size.width.toFloat() * PRICE_AREA / visible
                                dragAccum += d.x
                                val shift = (dragAccum / slot).toInt()
                                if (shift != 0) {
                                    dragAccum -= shift * slot
                                    endIndex = (endIndex - shift).coerceIn((visible - 1).coerceAtMost(candles.lastIndex), candles.lastIndex)
                                }
                                ch.consume()
                            }
                        }
                    } while (event.changes.any { it.pressed })
                }
            }
        Canvas(pricePanel) {
            drawPricePanel(candles, comp, start, end, overlays, placed, selected, sm, gridColor, labelColor, intraday)
        }
        if (sub != null) {
            Spacer(Modifier.height(4.dp))
            Text(sub.label + subLegend(sub), style = MaterialTheme.typography.labelSmall, color = labelColor)
            Canvas(Modifier.fillMaxWidth().height(86.dp)) {
                drawSubPanel(candles, comp, start, end, sub, selected, gridColor, labelColor)
            }
        }
    }
}

/** 가격 영역 오른쪽에 가격 눈금을 둘 자리(폭의 12%). */
private const val PRICE_AREA = 0.88f

private fun subLegend(sub: SubIndicator) = when (sub) {
    SubIndicator.Volume -> ""
    SubIndicator.RSI -> " (14) · 70 과열 / 30 침체"
    SubIndicator.MACD -> " (12,26,9) · 막대 = MACD−시그널"
}

private fun candleLabel(c: CandleDto, intraday: Boolean): String {
    val t = if (intraday) c.t.replace('T', ' ') else Fmt.date(c.t)
    return "$t  시 ${Fmt.price(c.o)} 고 ${Fmt.price(c.h)} 저 ${Fmt.price(c.l)} 종 ${Fmt.price(c.c)} · 거래량 ${Fmt.number(c.v)}"
}

private fun DrawScope.drawPricePanel(
    candles: List<CandleDto>, comp: Computed, start: Int, end: Int, overlays: Set<Overlay>,
    placed: List<Pair<ChartMarker, Int>>, selected: Int?, selectedMarkers: List<ChartMarker>,
    gridColor: Color, labelColor: Color, intraday: Boolean,
) {
    val n = end - start + 1
    val w = size.width * PRICE_AREA
    val slot = w / n
    val markerSpace = if (placed.any { it.second in start..end }) 26f else 0f
    var hi = Double.MIN_VALUE
    var lo = Double.MAX_VALUE
    for (i in start..end) {
        hi = maxOf(hi, candles[i].h.toDouble()); lo = minOf(lo, candles[i].l.toDouble())
        if (Overlay.BB in overlays) { comp.bb.upper[i]?.let { hi = maxOf(hi, it) }; comp.bb.lower[i]?.let { lo = minOf(lo, it) } }
        if (Overlay.MA in overlays) comp.ma.forEach { (_, _, v) -> v[i]?.let { hi = maxOf(hi, it); lo = minOf(lo, it) } }
    }
    val pad = (hi - lo).takeIf { it > 0 }?.times(0.06) ?: (hi * 0.01).coerceAtLeast(1.0)
    hi += pad; lo -= pad
    val top = 6f
    val bottom = size.height - 18f - markerSpace
    fun y(v: Double) = (top + (bottom - top) * (1 - (v - lo) / (hi - lo))).toFloat()
    fun x(i: Int) = (i - start) * slot + slot / 2

    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { textSize = 10.sp.toPx(); color = labelColor.toArgb() }
    // 가로 눈금 4줄 + 오른쪽 가격
    for (k in 0..3) {
        val v = lo + (hi - lo) * (k + 0.5) / 4
        val yy = y(v)
        drawLine(gridColor.copy(alpha = 0.6f), Offset(0f, yy), Offset(w, yy), strokeWidth = 1f)
        drawContext.canvas.nativeCanvas.drawText(Fmt.price(Math.round(v)), w + 6f, yy + paint.textSize / 3, paint)
    }
    // 아래 날짜 3개
    listOf(start, (start + end) / 2, end).distinct().forEach { i ->
        val t = candles[i].t
        val text = if (intraday) t.substring(5).replace('T', ' ') else t.substring(2)
        val tw = paint.measureText(text)
        drawContext.canvas.nativeCanvas.drawText(text, (x(i) - tw / 2).coerceIn(0f, w - tw), size.height - 4f, paint)
    }
    // 볼린저밴드
    if (Overlay.BB in overlays) {
        listOf(comp.bb.upper, comp.bb.middle, comp.bb.lower).forEachIndexed { k, series ->
            drawSeries(series, start, end, ::x, ::y, BB_COLOR.copy(alpha = if (k == 1) 0.5f else 0.85f), 2f,
                if (k == 1) PathEffect.dashPathEffect(floatArrayOf(8f, 8f)) else null)
        }
    }
    // 캔들
    val bodyW = (slot * 0.7f).coerceIn(1.5f, 22f)
    for (i in start..end) {
        val c = candles[i]
        val up = c.c >= c.o
        val color = if (up) StockColors.up else StockColors.down
        drawLine(color, Offset(x(i), y(c.h.toDouble())), Offset(x(i), y(c.l.toDouble())), strokeWidth = 1.5f)
        val t = y(maxOf(c.o, c.c).toDouble())
        val b = y(minOf(c.o, c.c).toDouble())
        drawRect(color, topLeft = Offset(x(i) - bodyW / 2, t), size = Size(bodyW, (b - t).coerceAtLeast(1.5f)))
    }
    // 이동평균
    if (Overlay.MA in overlays) comp.ma.forEach { (_, color, v) -> drawSeries(v, start, end, ::x, ::y, color, 2.2f, null) }
    // 공시 별(캔들 아래)
    placed.filter { it.second in start..end }.groupBy { it.second }.forEach { (i, list) ->
        list.map { it.first.kind }.distinct().forEachIndexed { k, kind ->
            val on = list.any { it.first in selectedMarkers }
            val cy = bottom + 12f
            val cx = x(i) + k * 9f
            val star = starPath(Offset(cx, cy), if (on) 12f else 8f)
            drawPath(star, kind.color)
            if (on) drawLine(kind.color.copy(alpha = 0.6f), Offset(cx, top), Offset(cx, bottom), strokeWidth = 1.5f)
        }
    }
    selected?.takeIf { it in start..end }?.let { i ->
        drawLine(labelColor, Offset(x(i), top), Offset(x(i), bottom), strokeWidth = 1.5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
    }
}

private fun DrawScope.drawSeries(
    values: List<Double?>, start: Int, end: Int, x: (Int) -> Float, y: (Double) -> Float,
    color: Color, width: Float, effect: PathEffect?,
) {
    val p = Path()
    var started = false
    for (i in start..end) {
        val v = values[i]
        if (v == null) { started = false; continue }
        if (!started) { p.moveTo(x(i), y(v)); started = true } else p.lineTo(x(i), y(v))
    }
    drawPath(p, color, style = Stroke(width = width, pathEffect = effect))
}

private fun DrawScope.drawSubPanel(
    candles: List<CandleDto>, comp: Computed, start: Int, end: Int, sub: SubIndicator, selected: Int?,
    gridColor: Color, labelColor: Color,
) {
    val n = end - start + 1
    val w = size.width * PRICE_AREA
    val slot = w / n
    fun x(i: Int) = (i - start) * slot + slot / 2
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { textSize = 9.sp.toPx(); color = labelColor.toArgb() }
    val h = size.height
    when (sub) {
        SubIndicator.Volume -> {
            val maxV = (start..end).maxOf { candles[it].v }.coerceAtLeast(1)
            val bw = (slot * 0.7f).coerceIn(1.5f, 22f)
            for (i in start..end) {
                val c = candles[i]
                val bh = h * c.v / maxV
                drawRect((if (c.c >= c.o) StockColors.up else StockColors.down).copy(alpha = 0.55f), Offset(x(i) - bw / 2, h - bh), Size(bw, bh))
            }
            drawContext.canvas.nativeCanvas.drawText(Fmt.number(maxV), w + 4f, paint.textSize, paint)
        }
        SubIndicator.RSI -> {
            fun y(v: Double) = (h * (1 - v / 100)).toFloat()
            listOf(30.0, 70.0).forEach { g ->
                drawLine(gridColor, Offset(0f, y(g)), Offset(w, y(g)), strokeWidth = 1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
                drawContext.canvas.nativeCanvas.drawText(g.toInt().toString(), w + 4f, y(g) + paint.textSize / 3, paint)
            }
            drawSeries(comp.rsi, start, end, ::x, ::y, Color(0xFF7C3AED), 2.2f, null)
        }
        SubIndicator.MACD -> {
            val vals = (start..end).flatMap { listOfNotNull(comp.macd.macd[it], comp.macd.signal[it], comp.macd.hist[it]) }
            if (vals.isEmpty()) return
            val m = vals.maxOf { kotlin.math.abs(it) }.takeIf { it > 0 } ?: 1.0
            fun y(v: Double) = (h / 2 - (h / 2 - 4) * v / m).toFloat()
            drawLine(gridColor, Offset(0f, y(0.0)), Offset(w, y(0.0)), strokeWidth = 1f)
            val bw = (slot * 0.6f).coerceIn(1.5f, 18f)
            for (i in start..end) {
                val v = comp.macd.hist[i] ?: continue
                val top = minOf(y(v), y(0.0))
                drawRect((if (v >= 0) StockColors.up else StockColors.down).copy(alpha = 0.5f), Offset(x(i) - bw / 2, top), Size(bw, kotlin.math.abs(y(v) - y(0.0)).coerceAtLeast(1f)))
            }
            drawSeries(comp.macd.macd, start, end, ::x, ::y, Color(0xFFE11D48), 2f, null)
            drawSeries(comp.macd.signal, start, end, ::x, ::y, Color(0xFF2563EB), 2f, null)
        }
    }
    selected?.takeIf { it in start..end }?.let { i -> drawLine(labelColor, Offset(x(i), 0f), Offset(x(i), h), strokeWidth = 1f) }
}

/** 이동평균 범례 문구(색 구분은 화면에서). */
val MovingAverageLegend: List<Pair<String, Color>> = MA_SPECS.map { (n, c) -> "MA$n" to c } + ("볼린저(20,2)" to BB_COLOR)
