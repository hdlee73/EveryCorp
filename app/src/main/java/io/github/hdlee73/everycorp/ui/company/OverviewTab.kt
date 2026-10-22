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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.hdlee73.everycorp.data.api.dto.AmountDto
import io.github.hdlee73.everycorp.data.api.dto.OverviewDto
import io.github.hdlee73.everycorp.ui.common.Fmt
import io.github.hdlee73.everycorp.ui.common.LabelValue
import io.github.hdlee73.everycorp.ui.common.SectionCard
import io.github.hdlee73.everycorp.ui.common.UiStateContent

@Composable
fun OverviewTab(vm: CompanyViewModel) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        UiStateContent(vm.overview, onRetry = vm::loadOverview) { o -> OverviewBody(o) }
    }
}

@Composable
private fun OverviewBody(o: OverviewDto) {
    SectionCard(title = "기업 개요") {
        LabelValue("대표이사", o.ceoName, emphasize = true)
        LabelValue("영문명", o.corpNameEng)
        LabelValue("종목코드", o.stockCode)
        LabelValue("설립일", o.established?.let(Fmt::date))
        LabelValue("결산월", o.fiscalMonth?.let { "${it}월" })
        LabelValue("주소", o.address)
        LabelValue("전화", o.phone)
        LabelValue("홈페이지", o.homepage)
    }
    val sh = o.largestShareholder
    SectionCard(title = "최대주주") {
        if (sh.available) {
            Text(sh.display, style = MaterialTheme.typography.titleMedium)
            sh.basis?.let { Text("${it.reportName} 기준", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        } else Text("최대주주 정보를 찾지 못했습니다.", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    val f = o.financials
    SectionCard(title = "재무현황 ${f.fsLabel?.let { "($it)" }.orEmpty()}") {
        if (!f.available) {
            Text(f.message ?: "공시된 재무 정보가 없습니다. (비상장 법인은 감사보고서 PDF 로 확인하세요)", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            f.basis?.let { Text("${it.reportName} 기준 · 단위 원", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            f.balanceSheet?.let {
                AmountRow("자산총계", it.totalAssets)
                AmountRow("부채총계", it.totalLiabilities)
                AmountRow("자본총계", it.totalEquity)
            }
            f.incomeStatement?.let {
                AmountRow(f.revenueLabel, it.revenue)
                AmountRow("영업이익", it.operatingIncome)
                AmountRow("당기순이익", it.netIncome)
            }
        }
    }
}

@Composable
private fun AmountRow(label: String, a: AmountDto) {
    LabelValue(label, a.display ?: a.amount?.let { Fmt.number(it) })
    a.prevAmount?.let {
        Text("전기 ${Fmt.number(it)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp))
    }
}
