package io.github.hdlee73.everycorp.ui.etf

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.hdlee73.everycorp.EveryCorpApp
import io.github.hdlee73.everycorp.data.api.dto.EtfBasicsDto
import io.github.hdlee73.everycorp.data.api.dto.EtfDailyDto
import io.github.hdlee73.everycorp.data.api.dto.EtfDetailDto
import io.github.hdlee73.everycorp.ui.AppViewModel
import io.github.hdlee73.everycorp.ui.common.Dest
import io.github.hdlee73.everycorp.ui.common.EtfTab
import io.github.hdlee73.everycorp.ui.common.UnderlineTabs
import io.github.hdlee73.everycorp.ui.common.Fmt
import io.github.hdlee73.everycorp.ui.common.GradientTopBar
import io.github.hdlee73.everycorp.ui.common.KeyValueTable
import io.github.hdlee73.everycorp.ui.common.PriceLineChart
import io.github.hdlee73.everycorp.ui.common.SectionCard
import io.github.hdlee73.everycorp.ui.common.StatGrid
import io.github.hdlee73.everycorp.ui.common.UiState
import io.github.hdlee73.everycorp.ui.common.UiStateContent
import io.github.hdlee73.everycorp.ui.common.VolumeBarChart
import io.github.hdlee73.everycorp.ui.common.directionColor
import io.github.hdlee73.everycorp.ui.theme.HeroPalettes
import io.github.hdlee73.everycorp.ui.theme.LocalHeroPalette
import kotlinx.coroutines.delay
import java.util.Locale

private const val REFRESH_MS = 10_000L

@Composable
fun EtfScreen(app: AppViewModel, dest: Dest.Etf) {
    val item = dest.item
    val tab = dest.current
    val vm: EtfViewModel = viewModel(
        key = "etf-${item.code}",
        factory = viewModelFactory { initializer { EtfViewModel(item.code, EveryCorpApp.container.repository) } },
    )
    LaunchedEffect(item.code) { if (vm.detail is UiState.Idle) vm.load() }
    LaunchedEffect(item.code, tab) {
        when (tab) {
            EtfTab.Portfolio -> vm.loadPortfolio()
            EtfTab.Relations -> vm.loadRelations()
            EtfTab.Basics -> Unit
        }
    }
    // 기초자료 탭이 보이는 동안만 현재가를 주기적으로 갱신한다(서버가 5초 캐시).
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(item.code, tab) {
        if (tab != EtfTab.Basics) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) { delay(REFRESH_MS); vm.refreshQuiet() }
        }
    }

    CompositionLocalProvider(LocalHeroPalette provides HeroPalettes.Teal) {
        Column(Modifier.fillMaxSize().navigationBarsPadding()) {
            GradientTopBar(
                title = item.name,
                onBack = { app.back() },
                badge = "ETF",
                trailing = { IconButton(onClick = app::openSearch) { Icon(Icons.Filled.Search, contentDescription = "검색") } },
            )
            val tabs = EtfTab.entries
            UnderlineTabs(tabs.map { it.label }, tabs.indexOf(tab), onSelect = { app.selectEtfTab(tabs[it]) })
            // 탭마다 스크롤 위치를 따로 둔다(탭을 바꿔도 다른 탭 위치가 섞이지 않게).
            val scroll = rememberSaveable(item.code, tab, saver = ScrollState.Saver) { ScrollState(0) }
            Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when (tab) {
                    EtfTab.Basics -> BasicsTab(vm)
                    EtfTab.Portfolio -> PortfolioTab(app, vm)
                    EtfTab.Relations -> RelationsTab(app, vm, item)
                }
            }
        }
    }
}

@Composable
private fun BasicsTab(vm: EtfViewModel) {
    UiStateContent(vm.detail, onRetry = vm::load) { d ->
        QuoteCard(d)
        InfoCard(d)
        ChecksCard(d.basics)
        NavTable(d.daily)
    }
    UiStateContent(vm.chart, onRetry = vm::loadChart) { ch ->
        val q = (vm.detail as? UiState.Success)?.data?.quote
        SectionCard(title = "최근 1년 (일별 종가·거래량)") {
            if (ch.points.size < 2) Text("차트 데이터가 부족합니다.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            else {
                PriceLineChart(ch.points, ch.range52w.high, ch.range52w.low, directionColor(q?.direction ?: "FLAT"), highDate = ch.range52w.highDate, lowDate = ch.range52w.lowDate)
                Text("거래량", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                VolumeBarChart(ch.points, Modifier.fillMaxWidth().height(72.dp))
            }
        }
    }
}

@Composable
private fun QuoteCard(d: EtfDetailDto) {
    val q = d.quote
    SectionCard(title = d.name.ifBlank { "현재가" }) {
        if (q == null) {
            Text("현재가를 불러오지 못했습니다.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@SectionCard
        }
        val color = directionColor(q.direction)
        Text(Fmt.price(q.price) + "원", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = color)
        Text(Fmt.change(q.change, q.changeRate, q.direction), style = MaterialTheme.typography.titleSmall, color = color)
        val latest = d.daily.firstOrNull()
        StatGrid(
            listOf(
                "시가" to q.open?.let { Fmt.price(it) },
                "고가" to q.high?.let { Fmt.price(it) },
                "저가" to q.low?.let { Fmt.price(it) },
                "거래량" to q.volume?.let { Fmt.number(it) },
                "NAV(최근)" to latest?.nav?.let { String.format(Locale.US, "%,.2f", it) },
                "괴리율(최근)" to latest?.disparity?.let { String.format(Locale.US, "%+.2f%%", it) },
            ),
        )
        Text(
            "출처 ${q.source.ifBlank { "-" }}" + (q.fetchedAt?.let { " · " + it.replace('T', ' ').take(19) } ?: ""),
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun InfoCard(d: EtfDetailDto) {
    val b = d.basics
    SectionCard(title = "ETF 기초자료") {
        KeyValueTable(
            listOf(
                "종목코드" to d.code + (b?.isin?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""),
                "순자산총액" to b?.netAssets?.let { EtfFmt.eok(it) + if (b?.netAssetsComputed == true) " (NAV×좌수 계산)" else "" },
                "상장좌수" to b?.listedShares?.let { Fmt.number(it) + "좌" },
                "시가총액" to b?.marketCap?.let { EtfFmt.eok(it) },
                "운용사" to b?.manager?.takeIf { it.isNotBlank() }?.let { m -> EtfFmt.withSource(m, b?.managerSource) },
                "총보수" to b?.totalFee?.let { EtfFmt.withSource("연 " + EtfFmt.fee(it), b?.feeSource) },
                "기초지수" to d.indexName,
                "과세유형" to d.taxType,
                "상장일" to b?.listedDate?.takeIf { it.isNotBlank() },
                "복제방법" to b?.replication?.takeIf { it.isNotBlank() },
                "액면가" to d.parValue?.let { Fmt.price(it) + "원" },
            ),
        )
        val src = listOfNotNull(b?.source?.takeIf { it.isNotBlank() }, b?.baseDate?.takeIf { it.isNotBlank() }?.let { "기준일 $it" }).joinToString(" · ")
        Text(
            if (b == null) "순자산·상장좌수·보수는 KRX 자료를 받지 못해 비어 있습니다." else "출처: ${src.ifBlank { "KRX" }}",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 키움 자료와 KRX 자료를 맞대어 본 결과(NAV·종목명·순자산 계산). */
@Composable
private fun ChecksCard(b: EtfBasicsDto?) {
    val checks = b?.checks.orEmpty()
    if (checks.isEmpty()) return
    SectionCard(title = "자료 대조 (키움 ↔ KRX)") {
        checks.forEach { c ->
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (c.ok) "일치" else "차이", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold,
                    color = if (c.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                Column(Modifier.weight(1f)) {
                    Text(c.label, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                    Text(c.detail, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun NavTable(rows: List<EtfDailyDto>) {
    if (rows.isEmpty()) return
    SectionCard(title = "일별 NAV·괴리율·추적오차") {
        Text("괴리율 = (종가 − NAV) ÷ NAV. 플러스는 NAV보다 비싸게(프리미엄), 마이너스는 싸게(할인) 거래된 것입니다.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.fillMaxWidth()) {
            HeaderText("일자", 1.1f, TextAlign.Start)
            HeaderText("종가", 0.9f)
            HeaderText("NAV", 1f)
            HeaderText("괴리율", 0.8f)
            HeaderText("추적오차", 0.8f)
        }
        rows.forEach { r ->
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                CellText(Fmt.date(r.date).drop(2), 1.1f, TextAlign.Start)
                CellText(Fmt.price(r.close), 0.9f)
                CellText(r.nav?.let { String.format(Locale.US, "%,.0f", it) } ?: "-", 1f)
                CellText(r.disparity?.let { String.format(Locale.US, "%+.2f%%", it) } ?: "-", 0.8f)
                CellText(r.trackingError?.let { String.format(Locale.US, "%.2f%%", it) } ?: "-", 0.8f)
            }
        }
        Text("출처: 키움 REST API(ETF 일별추이). 추적오차율은 소수점 없는 응답을 100으로 나눠 표시한 값입니다.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun RowScope.HeaderText(text: String, weight: Float, align: TextAlign = TextAlign.End) =
    Text(text, Modifier.weight(weight), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, textAlign = align, maxLines = 1)

@Composable
private fun RowScope.CellText(text: String, weight: Float, align: TextAlign = TextAlign.End) =
    Text(text, Modifier.weight(weight), style = MaterialTheme.typography.bodySmall, textAlign = align, maxLines = 1, softWrap = false)
