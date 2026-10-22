package io.github.hdlee73.everycorp.ui.company

import androidx.compose.foundation.layout.Arrangement
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
        s.rows.forEach { RowBlock(it) }
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
