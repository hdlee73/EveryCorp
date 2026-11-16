package io.github.hdlee73.everycorp.ui.etf

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.hdlee73.everycorp.data.api.dto.EtfStatsDto
import io.github.hdlee73.everycorp.data.api.dto.EtfValueChangeDto
import io.github.hdlee73.everycorp.ui.AppViewModel
import io.github.hdlee73.everycorp.ui.common.ChartSeries
import io.github.hdlee73.everycorp.ui.common.EtfStatsTab
import io.github.hdlee73.everycorp.ui.common.Fmt
import io.github.hdlee73.everycorp.ui.common.LabelValue
import io.github.hdlee73.everycorp.ui.common.PillChip
import io.github.hdlee73.everycorp.ui.common.SectionCard
import io.github.hdlee73.everycorp.ui.common.SeriesColors
import io.github.hdlee73.everycorp.ui.common.SeriesLineChart
import io.github.hdlee73.everycorp.ui.common.StatFmt
import io.github.hdlee73.everycorp.ui.common.UiStateContent

/** ETF 한 종목의 일별 계열. */
enum class EtfSeriesMetric(val label: String) { NetAssets("순자산총액"), MarketCap("시가총액"), Shares("상장좌수"), Nav("NAV") }

fun etfSeriesValues(s: EtfStatsDto, m: EtfSeriesMetric): List<Double?> = when (m) {
    EtfSeriesMetric.NetAssets -> s.netAssets.map { it?.toDouble() }
    EtfSeriesMetric.MarketCap -> s.marketCap.map { it?.toDouble() }
    EtfSeriesMetric.Shares -> s.listedShares.map { it?.toDouble() }
    EtfSeriesMetric.Nav -> s.nav
}

private fun seriesFormat(m: EtfSeriesMetric): (Double) -> String = when (m) {
    EtfSeriesMetric.NetAssets, EtfSeriesMetric.MarketCap -> StatFmt::won
    EtfSeriesMetric.Shares -> StatFmt::count
    EtfSeriesMetric.Nav -> { v -> String.format(java.util.Locale.US, "%,.2f", v) }
}

/** ETF 화면 '순자산 추이' 탭. */
@Composable
fun EtfStatsTabContent(app: AppViewModel, vm: EtfViewModel) {
    ChipRow(StatPeriod.entries, vm.statsPeriod, { it.label }, vm::selectStatsPeriod)
    ChipRow(EtfSeriesMetric.entries, vm.statsMetric, { it.label }, vm::selectStatsMetric)
    UiStateContent(vm.stats, onRetry = { vm.loadStats(force = true) }) { s ->
        CoverageNote(s.coverage)
        val values = remember(s, vm.statsMetric) { etfSeriesValues(s, vm.statsMetric) }
        val fmt = seriesFormat(vm.statsMetric)
        SectionCard(title = "${vm.statsMetric.label} 추이") {
            Text(values.lastOrNull { it != null }?.let(fmt) ?: "-", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            SeriesLineChart(s.dates, listOf(ChartSeries(vm.statsMetric.label, SeriesColors.at(0), values)), fmt)
        }
        SectionCard(title = "순위 (${s.dates.lastOrNull()?.let(Fmt::date) ?: "-"} 순자산 기준)") {
            LabelValue("전체 ETF 중", s.rank?.let { "${it}위 / ${s.rankTotal}개" })
            if (s.manager.isNotBlank()) LabelValue("${s.manager} 안에서", s.managerRank?.let { "${it}위 / ${s.managerTotal}개" })
            PillChip("전체 ETF 순위 보기", onClick = { app.openEtfStats(EtfStatsTab.Ranking) })
        }
        if (s.changes.isNotEmpty()) SectionCard(title = "기간별 변화") {
            Row(Modifier.fillMaxWidth()) {
                H("기간", 0.6f, TextAlign.Start); H("순자산 증감", 1.3f); H("증감률", 0.8f); H("좌수 증감", 1.1f)
            }
            s.changes.forEach { c ->
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                    C(c.label, 0.6f, TextAlign.Start)
                    ChangeCell(c.netAssets, 1.3f) { StatFmt.wonSigned(it.diff) }
                    ChangeCell(c.netAssets, 0.8f) { StatFmt.pctSigned(it.pct) }
                    ChangeCell(c.listedShares, 1.1f) { StatFmt.countSigned(Math.round(it.diff)) }
                }
            }
            Text("좌수 증감은 설정(+)·환매(−)로 늘거나 준 상장좌수입니다. 순자산 증감에는 가격 변동도 들어 있습니다.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(s.note, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun RowScope.ChangeCell(c: EtfValueChangeDto?, weight: Float, text: (EtfValueChangeDto) -> String) =
    Text(
        c?.let(text) ?: "-", Modifier.weight(weight), style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.End, maxLines = 1,
        color = c?.let { changeColor(it.diff) } ?: MaterialTheme.colorScheme.onSurfaceVariant,
    )

@Composable
private fun RowScope.H(text: String, weight: Float, align: TextAlign = TextAlign.End) =
    Text(text, Modifier.weight(weight), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, textAlign = align, maxLines = 1)

@Composable
private fun RowScope.C(text: String, weight: Float, align: TextAlign = TextAlign.End) =
    Text(text, Modifier.weight(weight), style = MaterialTheme.typography.bodySmall, textAlign = align, maxLines = 1, overflow = TextOverflow.Ellipsis)
