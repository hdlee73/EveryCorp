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
import io.github.hdlee73.everycorp.data.api.dto.OverviewDto
import io.github.hdlee73.everycorp.ui.common.FinancialTable
import io.github.hdlee73.everycorp.ui.common.Fmt
import io.github.hdlee73.everycorp.ui.common.KeyValueTable
import io.github.hdlee73.everycorp.ui.common.SectionCard
import io.github.hdlee73.everycorp.ui.common.UiStateContent

@Composable
fun OverviewTab(vm: CompanyViewModel) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        UiStateContent(vm.overview, onRetry = vm::loadOverview) { o -> OverviewBody(o, o.badge.ifBlank { vm.corp.badge }) }
    }
}

@Composable
private fun OverviewBody(o: OverviewDto, badge: String) {
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
        Text("출처: DART 정기보고서 · 단위 ${tables.unit} (1조원 이상은 조원 병기)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FinancialTable(tables.income)
    }
}
