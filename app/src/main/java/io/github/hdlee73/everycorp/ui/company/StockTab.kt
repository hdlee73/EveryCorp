package io.github.hdlee73.everycorp.ui.company

import android.content.Context
import androidx.compose.foundation.clickable
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
import io.github.hdlee73.everycorp.ui.common.VolumeBarChart
import io.github.hdlee73.everycorp.ui.common.UiState
import io.github.hdlee73.everycorp.ui.common.PriceLineChart
import io.github.hdlee73.everycorp.ui.common.SectionCard
import io.github.hdlee73.everycorp.ui.common.UiStateContent
import io.github.hdlee73.everycorp.ui.common.directionColor

@Composable
fun StockTab(vm: CompanyViewModel) {
    if (!vm.corp.isListed) {
        Column(Modifier.padding(16.dp)) { EmptyBox("비상장 법인은 주가 정보가 없습니다.") }
        return
    }
    // 이 탭이 화면에 보이는 동안(앱이 전면일 때)만 현재가는 5초, 당일 추이는 30초 간격으로 갱신한다.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(vm.corp.corpCode) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { vm.quoteFlow().collect { vm.onQuote(it) } }
    }
    LaunchedEffect(vm.corp.corpCode) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { while (true) { vm.refreshIntraday(); delay(30_000) } }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        UiStateContent(vm.quote, onRetry = null) { q -> QuoteCard(q, vm.intraday) }
        UiStateContent(vm.chart, onRetry = vm::loadChart) { ch ->
            val q = (vm.quote as? UiState.Success)?.data
            val color = directionColor(q?.direction ?: "FLAT")
            SectionCard(title = "최근 1년 (일별 종가·거래량)") {
                if (ch.points.size < 2) Text("차트 데이터가 부족합니다.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                else {
                    PriceLineChart(ch.points, ch.range52w.high, ch.range52w.low, color, highDate = ch.range52w.highDate, lowDate = ch.range52w.lowDate, markers = vm.markers)
                    if (vm.markers.isNotEmpty()) MarkerLegend()
                    if (vm.markers.isNotEmpty()) MarkerList(vm.markers)
                    Text("거래량", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    VolumeBarChart(ch.points, Modifier.fillMaxWidth().height(72.dp))
                }
            }
        }
    }
}

@Composable
private fun MarkerLegend() {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        MarkerKind.entries.forEach { k ->
            Text("★ ${k.label}", style = MaterialTheme.typography.labelSmall, color = Color(0xFFB07F00))
        }
        Text("공시일 기준 · 별을 누르면 어떤 공시인지 표시", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
private fun QuoteCard(q: QuoteDto, intraday: UiState<IntradayDto>) {
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
            append("출처 ").append(q.source.ifBlank { "-" })
            if (q.delayed) append(" · 지연 시세(전일 기준 등)")
            q.marketStatus?.let { append(" · ").append(it) }
            q.fetchedAt?.let { append(" · ").append(it.replace('T', ' ').take(19)) }
        }
        Text(note, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
