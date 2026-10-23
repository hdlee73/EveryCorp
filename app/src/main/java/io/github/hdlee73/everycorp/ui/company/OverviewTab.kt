package io.github.hdlee73.everycorp.ui.company

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import io.github.hdlee73.everycorp.data.api.dto.TrendDto
import io.github.hdlee73.everycorp.ui.theme.StockColors
import java.util.Locale
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.hdlee73.everycorp.data.api.dto.OverviewDto
import io.github.hdlee73.everycorp.data.api.dto.ShareholdersDto
import io.github.hdlee73.everycorp.ui.common.FinancialTable
import io.github.hdlee73.everycorp.ui.common.Fmt
import io.github.hdlee73.everycorp.ui.common.KeyValueTable
import io.github.hdlee73.everycorp.ui.common.SectionCard
import io.github.hdlee73.everycorp.ui.common.TrendBars
import io.github.hdlee73.everycorp.ui.common.TrendLine
import io.github.hdlee73.everycorp.ui.common.TrendValueTable
import io.github.hdlee73.everycorp.ui.common.UiState
import io.github.hdlee73.everycorp.ui.common.UiStateContent

@Composable
fun OverviewTab(vm: CompanyViewModel) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        UiStateContent(vm.overview, onRetry = vm::loadOverview) { o -> OverviewBody(o, o.badge.ifBlank { vm.corp.badge }, vm.shareholders) }
    }
}

@Composable
private fun OverviewBody(o: OverviewDto, badge: String, holders: UiState<ShareholdersDto>) {
    SectionCard(title = "기업 개요") {
        KeyValueTable(
            listOf(
                "구분" to badge.ifBlank { null },
                "대표이사" to o.ceoName,
                "영문명" to o.corpNameEng,
                "종목코드" to o.stockCode,
                "설립일" to o.established?.let(Fmt::date),
                "결산월" to o.fiscalMonth?.let { "${it}월" },
                "주소" to o.address,
                "전화" to o.phone,
                "홈페이지" to o.homepage,
            ),
        )
    }
    val sh = o.largestShareholder
    SectionCard(title = "최대주주") {
        if (sh.available) {
            Text(sh.display, style = MaterialTheme.typography.titleMedium)
            sh.basis?.let { Text("${it.reportName} 기준", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        } else Text(sh.display.ifBlank { "최대주주 정보를 찾지 못했습니다." }, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (holders is UiState.Success && holders.data.available) HoldersCard(holders.data)
    val f = o.financials
    val tables = f.tables
    if (!f.available || tables == null) {
        SectionCard(title = "재무현황") {
            Text(f.message ?: "공시된 재무 정보가 없습니다. (비상장 법인은 감사보고서 PDF 로 확인하세요)", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    val source = "${f.fsLabel.orEmpty()}재무제표 · ${f.latestBasis?.reportName ?: f.basis?.reportName.orEmpty()} 기준 · 단위 ${tables.unit}"
    SectionCard(title = "재무상태 비교") {
        Text(source, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FinancialTable(tables.balance)
        Text("부채비율 = 부채총계 ÷ 자본총계", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    SectionCard(title = "손익현황") {
        Text("출처: DART 정기보고서 · 단위 ${tables.unit}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FinancialTable(tables.income)
    }
    f.trend?.let { t ->
        SectionCard(title = "최근 5개년 추이") {
            Text("출처: DART 사업보고서 · ${f.fsLabel.orEmpty()}재무제표 · 단위 억원 (막대를 누르면 수치 표시)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TrendBars(t)
            TrendLine(t, "debt_ratio")
            if (t.series.any { it.key in listOf("eps", "per", "pbr") }) {
                Text("주당·배수 지표", style = MaterialTheme.typography.titleSmall)
                TrendValueTable(t, listOf("eps", "per", "pbr"))
                Text(
                    "EPS는 DART 공시값입니다. PER = 연말 종가 ÷ EPS, PBR = 연말 종가 ÷ BPS(지배주주 자본 ÷ 가중평균 주식수)로 직접 계산한 추정치라 키움·증권사 표시값과 다를 수 있습니다.",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun HoldersCard(h: ShareholdersDto) {
    // 보통주·우선주 등 종류별로 행이 따로 오므로 같은 사람이 여러 번 나올 수 있다. 종류를 함께 보여 준다.
    val showKind = h.holders.map { it.stockKind }.filter { it.isNotBlank() }.distinct().size > 1
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val line = MaterialTheme.colorScheme.outlineVariant
    fun pct(v: Double?) = if (v == null) "-" else String.format(Locale.US, "%.2f%%", v)
    fun num(v: Long?) = if (v == null) "-" else String.format(Locale.US, "%,d", v)
    SectionCard(title = if (h.estimated) "최대주주 및 특수관계인 (추정)" else "최대주주 및 특수관계인") {
        h.basis?.let { Text("${it.reportName} 기준 · 기말 · 주식수(주) / 지분율", style = MaterialTheme.typography.bodySmall, color = muted) }
        Column(Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("성명", Modifier.weight(1.6f), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                Text(if (showKind) "관계·종류" else "관계", Modifier.weight(1.2f), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                Text("주식수", Modifier.weight(1.3f), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.End)
                Text("지분율", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.End)
            }
            h.holders.forEach { x ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(x.name, Modifier.weight(1.6f), style = MaterialTheme.typography.bodyMedium)
                    Column(Modifier.weight(1.2f)) {
                        Text(x.relation.ifBlank { "-" }, style = MaterialTheme.typography.bodySmall, color = muted)
                        if (showKind && x.stockKind.isNotBlank()) Text(x.stockKind, style = MaterialTheme.typography.labelSmall, color = muted)
                    }
                    Text(num(x.endShares), Modifier.weight(1.3f), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.End)
                    Text(pct(x.endRatio), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.End)
                }
                HorizontalDivider(color = line.copy(alpha = 0.5f))
            }
            h.totals.ifEmpty { listOfNotNull(h.total) }.forEach { t ->
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(if (t.stockKind.isNotBlank()) "합계 · ${t.stockKind}" else "합계", Modifier.weight(2.8f), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                    Text(num(t.endShares), Modifier.weight(1.3f), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, textAlign = TextAlign.End)
                    Text(pct(t.endRatio), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, textAlign = TextAlign.End)
                }
            }
        }
        Text(h.source, style = MaterialTheme.typography.labelSmall, color = muted)
    }
}
