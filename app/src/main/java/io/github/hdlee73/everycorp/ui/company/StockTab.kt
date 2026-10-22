package io.github.hdlee73.everycorp.ui.company

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import io.github.hdlee73.everycorp.data.api.dto.QuoteDto
import io.github.hdlee73.everycorp.ui.common.EmptyBox
import io.github.hdlee73.everycorp.ui.common.Fmt
import io.github.hdlee73.everycorp.ui.common.LabelValue
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
    // 이 탭이 화면에 보이는 동안(앱이 전면일 때)만 5초 간격으로 현재가를 갱신한다.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(vm.corp.corpCode) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { vm.quoteFlow().collect { vm.onQuote(it) } }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        UiStateContent(vm.quote, onRetry = null) { q -> QuoteCard(q) }
        UiStateContent(vm.chart, onRetry = vm::loadChart) { ch ->
            val q = (vm.quote as? io.github.hdlee73.everycorp.ui.common.UiState.Success)?.data
            val color = directionColor(q?.direction ?: "FLAT")
            SectionCard(title = "최근 1년 (일별 종가)") {
                if (ch.points.size < 2) Text("차트 데이터가 부족합니다.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                else PriceLineChart(ch.points, ch.range52w.high, ch.range52w.low, color)
            }
            SectionCard(title = "52주 범위") {
                LabelValue("52주 최고", ch.range52w.high?.let { Fmt.price(it) + "원" + (ch.range52w.highDate?.let { d -> " (${Fmt.date(d)})" } ?: "") })
                LabelValue("52주 최저", ch.range52w.low?.let { Fmt.price(it) + "원" + (ch.range52w.lowDate?.let { d -> " (${Fmt.date(d)})" } ?: "") })
            }
        }
    }
}

@Composable
private fun QuoteCard(q: QuoteDto) {
    val color = directionColor(q.direction)
    SectionCard(title = q.name.ifBlank { "현재가" }) {
        Text(Fmt.price(q.price) + "원", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold, color = color)
        Text(Fmt.change(q.change, q.changeRate, q.direction), style = MaterialTheme.typography.titleMedium, color = color)
        LabelValue("시가", q.open?.let { Fmt.price(it) })
        LabelValue("고가", q.high?.let { Fmt.price(it) })
        LabelValue("저가", q.low?.let { Fmt.price(it) })
        LabelValue("거래량", q.volume?.let { Fmt.number(it) })
        q.per?.let { LabelValue("PER", String.format(java.util.Locale.US, "%.2f", it)) }
        q.pbr?.let { LabelValue("PBR", String.format(java.util.Locale.US, "%.2f", it)) }
        q.roe?.let { LabelValue("ROE", String.format(java.util.Locale.US, "%.2f%%", it)) }
        val note = buildString {
            append("출처 ").append(q.source.ifBlank { "-" })
            if (q.delayed) append(" · 지연 시세(전일 기준 등)")
            q.marketStatus?.let { append(" · ").append(it) }
            q.fetchedAt?.let { append(" · ").append(it.replace('T', ' ').take(19)) }
        }
        Text(note, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
