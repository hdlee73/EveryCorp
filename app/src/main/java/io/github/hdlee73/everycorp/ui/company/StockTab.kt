package io.github.hdlee73.everycorp.ui.company

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
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
                    PriceLineChart(ch.points, ch.range52w.high, ch.range52w.low, color)
                    Text("거래량", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    VolumeBarChart(ch.points, Modifier.fillMaxWidth().height(72.dp))
                }
            }
            SectionCard(title = "52주 범위") {
                StatGrid(
                    listOf(
                        "52주 최고" to ch.range52w.high?.let { Fmt.price(it) + "원" },
                        "52주 최저" to ch.range52w.low?.let { Fmt.price(it) + "원" },
                        "최고가 일자" to ch.range52w.highDate?.let { Fmt.date(it) },
                        "최저가 일자" to ch.range52w.lowDate?.let { Fmt.date(it) },
                    ),
                )
            }
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
