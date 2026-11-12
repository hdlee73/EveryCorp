package io.github.hdlee73.everycorp.ui.company

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.TextButton
import androidx.compose.ui.text.style.TextAlign
import io.github.hdlee73.everycorp.data.api.dto.OrderLevelDto
import io.github.hdlee73.everycorp.data.api.dto.OrderbookDto
import io.github.hdlee73.everycorp.ui.common.CandleChart
import io.github.hdlee73.everycorp.ui.common.MovingAverageLegend
import io.github.hdlee73.everycorp.ui.common.Overlay
import io.github.hdlee73.everycorp.ui.common.PillChip
import io.github.hdlee73.everycorp.ui.common.SubIndicator
import io.github.hdlee73.everycorp.ui.theme.StockColors
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.Checkbox
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import io.github.hdlee73.everycorp.ui.common.ChartMarker
import io.github.hdlee73.everycorp.ui.common.openDisclosure
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import kotlinx.coroutines.delay
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import io.github.hdlee73.everycorp.data.api.dto.IntradayDto
import io.github.hdlee73.everycorp.data.api.dto.QuoteDto
import io.github.hdlee73.everycorp.ui.common.EmptyBox
import io.github.hdlee73.everycorp.ui.common.Fmt
import io.github.hdlee73.everycorp.ui.common.IntradaySparkline
import io.github.hdlee73.everycorp.ui.common.LabelValue
import io.github.hdlee73.everycorp.ui.common.MarkerKind
import io.github.hdlee73.everycorp.ui.common.StatGrid
import io.github.hdlee73.everycorp.ui.common.UiState
import io.github.hdlee73.everycorp.ui.common.SectionCard
import io.github.hdlee73.everycorp.ui.common.UiStateContent
import io.github.hdlee73.everycorp.ui.common.directionColor

@Composable
fun StockTab(vm: CompanyViewModel) {
    if (!vm.corp.isListed) {
        Column(Modifier.padding(16.dp)) { EmptyBox("비상장 법인은 주가 정보가 없습니다.") }
        return
    }
    // 이 탭이 화면에 보이는 동안(앱이 전면일 때)만 실시간 연결을 유지한다. 앱을 내리면 연결을 닫는다.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(vm.corp.corpCode) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { vm.realtimeFlow().collect { vm.onRealtime(it) } }
    }
    // 실시간 연결이 없을 때만 5초마다 현재가를 직접 묻는다.
    LaunchedEffect(vm.corp.corpCode) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { while (true) { vm.pollQuoteIfNeeded(); delay(5_000) } }
    }
    LaunchedEffect(vm.corp.corpCode) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { while (true) { vm.refreshIntraday(); delay(30_000) } }
    }
    // 분봉은 1분마다 조용히 다시 받는다.
    LaunchedEffect(vm.corp.corpCode, vm.candlePeriod) {
        if (vm.candlePeriod.startsWith("m")) lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) { delay(60_000); vm.loadCandles(silent = true) }
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        UiStateContent(vm.quote, onRetry = null) { q -> QuoteCard(q, vm.intraday, vm.realtimeMode) }
        OrderbookCard(vm.orderbook, onRetry = vm::loadOrderbook)
        CandleCard(vm)
        UiStateContent(vm.chart, onRetry = vm::loadChart) { ch ->
            if (ch.range52w.high != null || ch.range52w.low != null) SectionCard(title = "52주 최고·최저") {
                StatGrid(
                    listOf(
                        "최고" to ch.range52w.high?.let { Fmt.price(it) },
                        "최고일" to ch.range52w.highDate?.let { Fmt.date(it) },
                        "출처" to ch.source.ifBlank { null },
                        "최저" to ch.range52w.low?.let { Fmt.price(it) },
                        "최저일" to ch.range52w.lowDate?.let { Fmt.date(it) },
                    ),
                )
            }
        }
    }
}

private val PERIODS = listOf("m1" to "1분", "m5" to "5분", "m15" to "15분", "m60" to "60분", "D" to "일", "W" to "주", "M" to "월")

@Composable
private fun CandleCard(vm: CompanyViewModel) {
    SectionCard(title = "차트") {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            PERIODS.forEach { (p, label) -> PillChip(label, onClick = { vm.selectPeriod(p) }, selected = vm.candlePeriod == p) }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Overlay.entries.forEach { o -> PillChip(o.label, onClick = { vm.toggleOverlay(o) }, selected = o in vm.overlays) }
            Text("|", Modifier.padding(horizontal = 2.dp, vertical = 6.dp), color = MaterialTheme.colorScheme.outlineVariant)
            SubIndicator.entries.forEach { s -> PillChip(s.label, onClick = { vm.selectSub(if (vm.subIndicator == s) null else s) }, selected = vm.subIndicator == s) }
        }
        when (val st = vm.candles) {
            is UiState.Success -> {
                val data = st.data
                if (data.candles.size < 2) Text("차트 데이터가 부족합니다.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                else {
                    val shown = vm.candleMarkers.filter { it.kind in vm.markerKinds }
                    CandleChart(data.candles, data.period, vm.overlays, vm.subIndicator, shown)
                    if (Overlay.MA in vm.overlays || Overlay.BB in vm.overlays) {
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            MovingAverageLegend.filter { (label, _) -> if (label.startsWith("MA")) Overlay.MA in vm.overlays else Overlay.BB in vm.overlays }
                                .forEach { (label, color) -> Text("━ $label", style = MaterialTheme.typography.labelSmall, color = color) }
                        }
                    }
                    MarkerFilter(vm)
                    if (shown.isNotEmpty()) MarkerList(shown)
                    val note = buildString {
                        append("출처 ").append(data.source.ifBlank { "-" }).append(" · 수정주가")
                        data.warning?.let { append(" · ").append(it) }
                    }
                    Text(note, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            is UiState.Error -> {
                Text(st.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = { vm.loadCandles() }) { Text("다시 시도") }
            }
            else -> Row(Modifier.fillMaxWidth().padding(24.dp), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp) }
        }
    }
}

@Composable
private fun MarkerFilter(vm: CompanyViewModel) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("공시 별", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        MarkerKind.entries.forEach { k ->
            val n = vm.candleMarkers.count { it.kind == k }
            PillChip("★ ${k.label} $n", onClick = { vm.toggleMarkerKind(k) }, selected = k in vm.markerKinds)
        }
    }
    Text("최근 1년 공시일 기준 · 주·월봉은 공시일이 속한 구간, 분봉은 같은 날에만 표시", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** 매도 10호가(위, 파랑)·매수 10호가(아래, 빨강)와 잔량 막대. 기본은 5단계, 누르면 10단계. */
@Composable
private fun OrderbookCard(state: UiState<OrderbookDto>, onRetry: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    when (state) {
        is UiState.Success -> SectionCard(title = "호가") {
            val ob = state.data
            val depth = if (expanded) 10 else 5
            val asks = ob.asks.take(depth)
            val bids = ob.bids.take(depth)
            val maxQty = (asks + bids).maxOfOrNull { it.qty }?.coerceAtLeast(1) ?: 1
            Column(Modifier.fillMaxWidth().clickable { expanded = !expanded }) {
                asks.reversed().forEach { OrderRow(it, maxQty, ask = true) }
                HorizontalDivider(Modifier.padding(vertical = 2.dp), color = MaterialTheme.colorScheme.outlineVariant)
                bids.forEach { OrderRow(it, maxQty, ask = false) }
            }
            val note = buildString {
                append("총잔량 매도 ").append(Fmt.number(ob.totalAsk)).append(" · 매수 ").append(Fmt.number(ob.totalBid))
                ob.time?.let { append(" · ").append(it) }
                append(if (expanded) " · 눌러서 5호가" else " · 눌러서 10호가")
            }
            Text(note, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        is UiState.Error -> SectionCard(title = "호가") {
            Text(state.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = onRetry) { Text("다시 시도") }
        }
        else -> Unit
    }
}

@Composable
private fun OrderRow(level: OrderLevelDto, maxQty: Long, ask: Boolean) {
    val color = if (ask) StockColors.down else StockColors.up
    Row(Modifier.fillMaxWidth().height(24.dp), verticalAlignment = Alignment.CenterVertically) {
        // 매도 잔량은 왼쪽, 매수 잔량은 오른쪽(일반 HTS 배치)
        Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.CenterEnd) {
            if (ask) {
                Box(Modifier.fillMaxHeight(0.7f).fillMaxWidth((level.qty.toFloat() / maxQty).coerceIn(0.02f, 1f)).background(color.copy(alpha = 0.15f)))
                Text(Fmt.number(level.qty), Modifier.padding(end = 6.dp), style = MaterialTheme.typography.labelMedium)
            }
        }
        Text(Fmt.price(level.price), Modifier.width(96.dp), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = color, textAlign = TextAlign.Center)
        Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.CenterStart) {
            if (!ask) {
                Box(Modifier.fillMaxHeight(0.7f).fillMaxWidth((level.qty.toFloat() / maxQty).coerceIn(0.02f, 1f)).background(color.copy(alpha = 0.15f)))
                Text(Fmt.number(level.qty), Modifier.padding(start = 6.dp), style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

/** "공시 내용 바로 보기" 체크: 켜면 주요사항보고서 공시일·공시명 목록을 차트 아래에 보이고, 끄면 숨긴다. 마지막 선택을 기억한다. */
@Composable
private fun MarkerList(markers: List<ChartMarker>) {
    val context = LocalContext.current
    var show by remember {
        mutableStateOf(runCatching { context.getSharedPreferences("stock_tab", Context.MODE_PRIVATE).getBoolean("marker_list", true) }.getOrDefault(true))
    }
    Row(
        Modifier.fillMaxWidth().clickable {
            show = !show
            runCatching { context.getSharedPreferences("stock_tab", Context.MODE_PRIVATE).edit().putBoolean("marker_list", show).apply() }
        },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = show, onCheckedChange = null)
        Text("공시 내용 바로 보기", Modifier.padding(start = 8.dp), style = MaterialTheme.typography.bodyMedium)
    }
    if (show) markers.sortedByDescending { it.date }.forEach { m ->
        Row(
            Modifier.fillMaxWidth().clickable(enabled = m.disclosure != null) { m.disclosure?.let { openDisclosure(context, it) } }.padding(vertical = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("★", color = m.kind.color)
            Text(Fmt.date(m.date), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(m.title, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun QuoteCard(q: QuoteDto, intraday: UiState<IntradayDto>, realtimeMode: String?) {
    val color = directionColor(q.direction)
    SectionCard(title = q.name.ifBlank { "현재가" }) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                Text(Fmt.price(q.price) + "원", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = color)
                Text(Fmt.change(q.change, q.changeRate, q.direction), style = MaterialTheme.typography.titleSmall, color = color)
            }
            Column(Modifier.width(150.dp)) {
                val pts = (intraday as? UiState.Success)?.data?.points.orEmpty()
                when {
                    pts.size >= 2 -> {
                        IntradaySparkline(pts, q.price?.let { it - q.change }, color, Modifier.fillMaxWidth().height(76.dp))
                        Text("당일 추이", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    intraday is UiState.Error -> Text("당일 추이 없음", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    else -> Text("당일 추이 불러오는 중", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        StatGrid(
            listOf(
                "시가" to q.open?.let { Fmt.price(it) },
                "고가" to q.high?.let { Fmt.price(it) },
                "저가" to q.low?.let { Fmt.price(it) },
                "거래량" to q.volume?.let { Fmt.number(it) },
                "PER" to q.per?.let { String.format(java.util.Locale.US, "%.2f", it) },
                "PBR" to q.pbr?.let { String.format(java.util.Locale.US, "%.2f", it) },
                "EPS" to q.eps?.let { Fmt.price(it.toLong()) },
                "BPS" to q.bps?.let { Fmt.price(it.toLong()) },
                "ROE" to q.roe?.let { String.format(java.util.Locale.US, "%.2f%%", it) },
            ),
        )
        val note = buildString {
            append(when (realtimeMode) { "kiwoom" -> "● 실시간 · "; "polling" -> "◐ 3초 갱신 · "; else -> "○ 5초 갱신 · " })
            append("출처 ").append(q.source.ifBlank { "-" })
            if (q.delayed) append(" · 지연 시세(전일 기준 등)")
            q.marketStatus?.let { append(" · ").append(it) }
            q.fetchedAt?.let { append(" · ").append(it.replace('T', ' ').take(19)) }
        }
        Text(note, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
