package io.github.hdlee73.everycorp.ui.screening

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.hdlee73.everycorp.data.api.dto.AuditScreeningDto
import io.github.hdlee73.everycorp.data.api.dto.CapitalScreeningDto
import io.github.hdlee73.everycorp.data.api.dto.ConvertibleScreeningDto
import io.github.hdlee73.everycorp.data.api.dto.CorpItemDto
import io.github.hdlee73.everycorp.ui.AppViewModel
import io.github.hdlee73.everycorp.ui.common.Dest
import io.github.hdlee73.everycorp.ui.common.EmptyBox
import io.github.hdlee73.everycorp.ui.common.Fmt
import io.github.hdlee73.everycorp.ui.common.GradientTopBar
import io.github.hdlee73.everycorp.ui.common.PillChip
import io.github.hdlee73.everycorp.ui.common.ScreeningKind
import io.github.hdlee73.everycorp.ui.common.SectionCard
import io.github.hdlee73.everycorp.ui.common.SectionLabel
import io.github.hdlee73.everycorp.ui.common.TabHeader
import io.github.hdlee73.everycorp.ui.common.UiState
import io.github.hdlee73.everycorp.ui.common.UiStateContent
import io.github.hdlee73.everycorp.ui.common.openInAppBrowser
import io.github.hdlee73.everycorp.ui.theme.StockColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate

private val REPORTS = listOf("11011" to "사업보고서", "11012" to "반기보고서", "11013" to "1분기", "11014" to "3분기")

@Composable
fun ScreeningScreen(vm: AppViewModel) {
    val thisYear = LocalDate.now().year
    val years = (thisYear downTo thisYear - 6).toList()
    var year by remember { mutableStateOf(thisYear - 1) }
    var reprt by remember { mutableStateOf("11011") }
    Column(Modifier.fillMaxSize()) {
        TabHeader("스크리닝", "조건에 맞는 회사를 결산연도별로 모아 봅니다")
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionLabel("결산(사업)연도")
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                years.forEach { y -> PillChip("${y}년", onClick = { year = y }, selected = year == y) }
            }
            SectionCard(Modifier.clickable { vm.openScreeningResult(ScreeningKind.Audit, year) }) {
                Text("감사의견 비적정", style = MaterialTheme.typography.titleMedium)
                Text(
                    "한정·부적정·의견거절 · 결산연월, 회사명, 감사의견, 감사인. 사업보고서 제출 법인(비상장 포함) 전체가 대상입니다.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            SectionCard {
                Text("자본잠식", style = MaterialTheme.typography.titleMedium)
                Text(
                    "자본총계가 자본금보다 작은 회사 · 자산·부채·자기자본·자본금(억원). 보고서를 골라 주세요.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    REPORTS.forEach { (code, label) -> PillChip(label, onClick = { reprt = code }, selected = reprt == code) }
                }
                PillChip("${year}년 ${REPORTS.first { it.first == reprt }.second} 보기", onClick = { vm.openScreeningResult(ScreeningKind.Capital, year, reprt) }, selected = true)
            }
            SectionCard(Modifier.clickable { vm.openScreeningResult(ScreeningKind.Convertible, year) }) {
                Text("CB·BW 발행잔액", style = MaterialTheme.typography.titleMedium)
                Text(
                    "상장사 중 전환사채·신주인수권부사채가 상환되지 않고 만기 전인 회사 · 사업보고서 채무증권 발행실적 기준(권면총액, 억원).",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 결과 표 한 줄. [corp]를 누르면 기업 화면으로 이동한다. */
private data class TableRow(val corp: CorpItemDto, val cells: List<String>, val tintColumn: Int = -1, val tint: Color = Color.Unspecified, val rceptNo: String = "")

private data class ResultTable(
    val status: String, val done: Int, val total: Int, val checked: Int, val updatedAt: Long?,
    val headers: List<String>, val widths: List<Dp>, val rows: List<TableRow>, val note: String,
)

private fun corpOf(code: String, name: String, stock: String) =
    CorpItemDto(corpCode = code, corpName = name, stockCode = stock.ifBlank { null }, listing = if (stock.isNotBlank()) "LISTED" else "UNLISTED")

private fun period(p: String) = p.replace('-', '.')

private fun eok(v: Long?) = Fmt.eok(v?.toDouble())

private fun auditTable(d: AuditScreeningDto) = ResultTable(
    d.status, d.progress.done, d.progress.total, d.checked, d.updatedAt,
    listOf("결산", "회사명", "감사의견", "감사인", "구분"), listOf(84.dp, 150.dp, 90.dp, 130.dp, 90.dp),
    d.items.map { TableRow(corpOf(it.corpCode, it.corpName, it.stockCode), listOf(period(it.period), it.corpName, it.opinion, it.auditor, it.scope), 2, StockColors.up, it.rceptNo) },
    "당기 감사의견이 한정·부적정·의견거절인 회사만 표시합니다. 행을 누르면 DART 감사보고서가 열립니다.",
)

private fun capitalTable(d: CapitalScreeningDto) = ResultTable(
    d.status, d.progress.done, d.progress.total, d.checked, d.updatedAt,
    listOf("결산", "회사명", "자산", "부채", "자기자본", "자본금", "구분"), listOf(84.dp, 150.dp, 72.dp, 72.dp, 80.dp, 72.dp, 76.dp),
    d.items.map {
        TableRow(
            corpOf(it.corpCode, it.corpName, it.stockCode),
            listOf(period(it.period), it.corpName, eok(it.assets), eok(it.liabilities), eok(it.equity), eok(it.capital), it.kind),
            4, if (it.kind == "완전잠식") StockColors.up else StockColors.down,
        )
    },
    "단위 억원 · 자본총계 < 자본금 · 연결재무제표 우선(없으면 별도). 완전잠식은 빨강, 부분잠식은 파랑.",
)

private fun convertibleTable(d: ConvertibleScreeningDto) = ResultTable(
    d.status, d.progress.done, d.progress.total, d.checked, d.updatedAt,
    listOf("결산", "회사명", "CB", "BW", "합계", "가까운 만기", "건수"), listOf(84.dp, 150.dp, 72.dp, 72.dp, 76.dp, 92.dp, 48.dp),
    d.items.map {
        TableRow(
            corpOf(it.corpCode, it.corpName, it.stockCode),
            listOf(period(it.period), it.corpName, eok(it.cbAmount), eok(it.bwAmount), eok(it.total),
                it.nextMaturity.let { m -> if (m.length == 8) "${m.take(4)}.${m.substring(4, 6)}.${m.takeLast(2)}" else m }, it.count.toString()),
            4, StockColors.up,
        )
    },
    "단위 억원 · 발행 당시 권면총액 기준이라 전환·조기상환이 반영된 실제 잔액과 다를 수 있습니다. 상환 표시가 없고 만기가 지나지 않은 건만 합산.",
)

@Composable
fun ScreeningResultScreen(vm: AppViewModel, d: Dest.ScreeningResult) {
    // 서버가 수집 중이면 완료될 때까지 몇 초마다 다시 불러온다.
    val state by produceState<UiState<ResultTable>>(UiState.Loading, d) {
        value = UiState.Loading
        while (true) {
            val r = when (d.kind) {
                ScreeningKind.Audit -> vm.repository.screeningAudit(d.year).map(::auditTable)
                ScreeningKind.Capital -> vm.repository.screeningCapital(d.year, d.reprt).map(::capitalTable)
                ScreeningKind.Convertible -> vm.repository.screeningConvertible(d.year).map(::convertibleTable)
            }
            val t = r.getOrNull()
            if (t == null) { value = UiState.Error(r.exceptionOrNull()?.message ?: "오류가 발생했습니다."); return@produceState }
            value = UiState.Success(t)
            if (t.status == "ready") return@produceState
            delay(5_000)
        }
    }
    val title = when (d.kind) {
        ScreeningKind.Audit -> "감사의견 비적정 · ${d.year}년"
        ScreeningKind.Capital -> "자본잠식 · ${d.year}년 ${REPORTS.first { it.first == d.reprt }.second}"
        ScreeningKind.Convertible -> "CB·BW · ${d.year}년"
    }
    Column(Modifier.fillMaxSize()) {
        GradientTopBar(title = title, onBack = { vm.back() })
        UiStateContent(state, onRetry = null) { t -> ResultBody(vm, t) }
    }
}

@Composable
private fun ResultBody(vm: AppViewModel, t: ResultTable) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var opening by remember { mutableStateOf(false) }
    // 감사의견 행은 DART 감사보고서로, 그 밖의 행은 기업 화면으로 연결한다.
    val onRow: (TableRow) -> Unit = { r ->
        if (r.rceptNo.isBlank()) vm.openCompany(r.corp)
        else if (!opening) {
            opening = true
            scope.launch {
                vm.repository.screeningAuditLink(r.rceptNo)
                    .onSuccess { openInAppBrowser(context, it.url) }
                    .onFailure { vm.openCompany(r.corp) }
                opening = false
            }
        }
    }
    Column(Modifier.fillMaxSize()) {
        val collecting = t.status != "ready"
        Text(
            if (collecting) "서버가 DART에서 수집 중입니다 (${t.done}/${t.total}). 완료되면 자동으로 갱신되며, 그 전 결과는 일부입니다."
            else "${t.checked}개 법인 확인 · ${t.rows.size}건 · ${t.note}",
            Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (t.rows.isEmpty()) {
            EmptyBox(if (collecting) "아직 집계된 항목이 없습니다." else "해당하는 회사가 없습니다.", Modifier.padding(horizontal = 16.dp))
            return
        }
        val total = t.widths.fold(0.dp) { a, b -> a + b }
        Box(Modifier.weight(1f).horizontalScroll(rememberScrollState())) {
            LazyColumn(Modifier.width(total + 32.dp), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)) {
                item {
                    Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer).padding(vertical = 8.dp)) {
                        t.headers.forEachIndexed { i, h -> Cell(h, t.widths[i], i, bold = true, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
                itemsIndexed(t.rows, key = { i, r -> "$i${r.corp.corpCode}" }) { _, r ->
                    Row(Modifier.fillMaxWidth().clickable { onRow(r) }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        r.cells.forEachIndexed { i, c ->
                            Cell(c, t.widths[i], i, color = if (i == r.tintColumn) r.tint else MaterialTheme.colorScheme.onSurface, bold = i == r.tintColumn)
                        }
                    }
                }
            }
        }
    }
}

/** 첫 두 열(결산·회사명)은 왼쪽 정렬, 나머지 글자·숫자는 오른쪽 정렬. */
@Composable
private fun Cell(text: String, width: Dp, index: Int, bold: Boolean = false, color: Color = Color.Unspecified) {
    Text(
        text.ifBlank { "-" }, Modifier.width(width).padding(horizontal = 4.dp), style = MaterialTheme.typography.bodySmall,
        fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal, color = color,
        textAlign = if (index < 2) TextAlign.Start else TextAlign.End, maxLines = 2,
    )
}
