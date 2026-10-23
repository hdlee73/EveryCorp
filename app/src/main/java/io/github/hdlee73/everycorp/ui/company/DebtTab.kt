package io.github.hdlee73.everycorp.ui.company

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.hdlee73.everycorp.data.api.dto.DebtRowDto
import io.github.hdlee73.everycorp.data.api.dto.DebtSecuritiesDto
import io.github.hdlee73.everycorp.data.api.dto.DebtSectionDto
import io.github.hdlee73.everycorp.ui.common.EmptyBox
import io.github.hdlee73.everycorp.ui.common.Fmt
import io.github.hdlee73.everycorp.ui.common.LabelValue
import io.github.hdlee73.everycorp.ui.common.SectionCard
import io.github.hdlee73.everycorp.ui.common.UiStateContent
import io.github.hdlee73.everycorp.ui.common.openInAppBrowser

@Composable
fun DebtTab(vm: CompanyViewModel) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        UiStateContent(vm.debt, onRetry = vm::loadDebt) { d -> DebtBody(d) }
    }
}

@Composable
private fun DebtBody(d: DebtSecuritiesDto) {
    val context = LocalContext.current
    if (!d.available) {
        EmptyBox(d.message ?: "공시된 채권·단기증권 발행잔액 정보가 없습니다.")
    } else {
        d.basis?.let { Text("${it.reportName} 기준 (${it.periodEnd})", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (d.unitNote.isNotBlank()) Text(d.unitNote, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (d.source.isNotBlank()) Text(d.source, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        d.sections.forEach { SectionBlock(it) }
        if (d.missing.isNotEmpty()) {
            Text("이번 보고서에 잔액 표가 없는 항목: ${d.missing.joinToString(", ")}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    d.viewerUrl?.let { url -> OutlinedButton(onClick = { openInAppBrowser(context, url) }) { Text("DART 원문 보기") } }
}

@Composable
private fun SectionBlock(s: DebtSectionDto) {
    SectionCard(title = s.title) {
        Text(s.totalDisplay ?: s.total?.let { Fmt.number(it) } ?: "-", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        if (s.rows.isNotEmpty() && s.rows.all { it.maturity != null }) BondTable(s) else s.rows.forEach { RowBlock(it) }
    }
}

/** 종목별 잔액 표: 종목 | 만기일 | 이율 | 잔액(억원). 금액은 원 단위로 받아 억원으로 보여 준다. */
@Composable
private fun BondTable(s: DebtSectionDto) {
    @Composable
    fun Line(name: String, maturity: String, rate: String, amount: String, header: Boolean = false, bold: Boolean = false) {
        val style = if (header) MaterialTheme.typography.labelMedium else MaterialTheme.typography.bodyMedium
        val weight = if (header || bold) FontWeight.SemiBold else FontWeight.Normal
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(name, Modifier.weight(2.4f), style = style, fontWeight = weight, maxLines = 2)
            Text(maturity, Modifier.weight(1.3f), style = style, fontWeight = weight, textAlign = TextAlign.Center, maxLines = 1)
            Text(rate, Modifier.weight(0.8f), style = style, fontWeight = weight, textAlign = TextAlign.End, maxLines = 1)
            Text(amount, Modifier.weight(1.3f), style = style, fontWeight = weight, textAlign = TextAlign.End, maxLines = 1)
        }
    }
    Column(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(horizontal = 4.dp)) {
            Line("종목", "만기일", "이율(%)", "잔액(억원)", header = true)
        }
        s.rows.forEachIndexed { i, r ->
            if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
            Column(Modifier.padding(horizontal = 4.dp)) {
                Line(r.name.orEmpty(), r.maturity.orEmpty(), r.rate.orEmpty().ifBlank { "-" }, Fmt.eok(r.total?.toDouble()))
            }
        }
        HorizontalDivider()
        Column(Modifier.padding(horizontal = 4.dp)) {
            Line("합계", "", "", Fmt.eok(s.total?.toDouble()), bold = true)
        }
    }
}

@Composable
private fun RowBlock(r: DebtRowDto) {
    LabelValue(r.label, r.total?.let { Fmt.number(it) }, emphasize = r.isTotal)
    r.buckets.filter { it.amount != null }.forEach { b ->
        LabelValue("  ${b.label}", Fmt.number(b.amount), modifier = Modifier.padding(start = 8.dp))
    }
    r.issueLimit?.let { LabelValue("  발행한도", Fmt.number(it)) }
    r.remainingLimit?.let { LabelValue("  잔여한도", Fmt.number(it)) }
}
