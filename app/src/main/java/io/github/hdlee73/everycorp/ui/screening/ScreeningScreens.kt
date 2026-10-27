package io.github.hdlee73.everycorp.ui.screening

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
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
    val year = LocalDate.now().year - 1
    Column(Modifier.fillMaxSize()) {
        TabHeader("스크리닝", "조건에 맞는 회사를 결산연도별로 모아 봅니다")
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                "스크리닝은 서버가 DART에서 전체 상장사(감사의견은 비상장 포함)를 하나씩 조회해 모으는 작업이라 처음엔 수십 분 이상 걸리고 서버 부담이 큽니다. 그래서 결과 화면에서 \"수집 시작\"을 눌렀을 때만 수집하며, 한 번 모은 결과는 서버에 저장되어 다음부터 바로 보입니다. 앱을 꺼도 수집은 계속됩니다. 결산연도는 각 화면 위쪽에서 고릅니다.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SectionCard(Modifier.clickable { vm.openScreeningResult(ScreeningKind.Audit, year) }) {
                Text("감사의견 비적정", style = MaterialTheme.typography.titleMedium)
                Text(
                    "한정·부적정·의견거절 · 결산연월, 회사명, 감사의견, 감사인. 사업보고서 제출 법인(비상장 포함) 전체가 대상입니다.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            SectionCard(Modifier.clickable { vm.openScreeningResult(ScreeningKind.Capital, year) }) {
                Text("자본잠식", style = MaterialTheme.typography.titleMedium)
                Text(
                    "자본총계가 자본금보다 작은 회사 · 자산·부채·자기자본·자본금(억원). 보고서(사업·반기·분기)는 화면 위쪽에서 고릅니다.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            SectionCard(Modifier.clickable { vm.openScreeningResult(ScreeningKind.Convertible, year) }) {
                Text("CB·BW 발행잔액", style = MaterialTheme.typography.titleMedium)
                Text(
                    "CB·BW 발행결정 공시를 낸 상장사를 찾고, 가장 최근 정기보고서(사업·반기·분기)의 미상환 전환사채·신주인수권부사채 표로 잔액을 확인합니다(억원). 표를 읽지 못하면 발행결정 권면총액으로 추정해 '추정'으로 표시합니다. DART 호출이 많아 처음 수집은 오래 걸립니다.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 결과 표 한 줄. [corp]를 누르면 기업 화면으로 이동한다. */
private data class TableRow(val corp: CorpItemDto, val cells: List<String>, val tintColumn: Int = -1, val tint: Color = Color.Unspecified, val rceptNo: String = "", val auditor: String = "", val bonds: List<BondLine> = emptyList(), val sub: String = "")

/** CB·BW 회차 상세 한 줄. amount는 원. */
private data class BondLine(val kind: String, val tm: String, val issued: String, val maturity: String, val amount: Long, val estimated: Boolean)

private data class ResultTable(
    val status: String, val done: Int, val total: Int, val checked: Int, val updatedAt: Long?,
    val headers: List<String>, val widths: List<Dp>, val rows: List<TableRow>, val note: String,
    val paused: Boolean = false, val hint: String = "", val legend: String = "",
)

private fun corpOf(code: String, name: String, stock: String) =
    CorpItemDto(corpCode = code, corpName = name, stockCode = stock.ifBlank { null }, listing = if (stock.isNotBlank()) "LISTED" else "UNLISTED")

private fun period(p: String) = p.replace('-', '.')

private fun ymd(s: String) = if (s.length >= 8) "${s.take(4)}.${s.substring(4, 6)}.${s.substring(6, 8)}" else s

private fun eok(v: Long?) = Fmt.eok(v?.toDouble())

private fun auditTable(d: AuditScreeningDto) = ResultTable(
    d.status, d.progress.done, d.progress.total, d.checked, d.updatedAt,
    listOf("결산", "DART코드", "상장구분", "회사명", "감사의견", "감사인", "구분"), listOf(84.dp, 80.dp, 72.dp, 150.dp, 90.dp, 130.dp, 90.dp),
    d.items.map { TableRow(corpOf(it.corpCode, it.corpName, it.stockCode), listOf(period(it.period), it.corpCode, it.badge, it.corpName, it.opinion, it.auditor, it.scope), 4, StockColors.up) },
    "당기 감사의견이 한정·부적정·의견거절인 회사만 표시합니다. 행을 누르면 기업개황이 열립니다(감사보고서는 기업 화면의 감사보고서 탭).",
).copy(paused = d.paused)

private fun capitalTable(d: CapitalScreeningDto) = ResultTable(
    d.status, d.progress.done, d.progress.total, d.checked, d.updatedAt,
    listOf("결산", "DART코드", "상장구분", "회사명", "자산", "부채", "자기자본", "자본금", "구분"), listOf(84.dp, 80.dp, 72.dp, 150.dp, 72.dp, 72.dp, 80.dp, 72.dp, 76.dp),
    d.items.map {
        TableRow(
            corpOf(it.corpCode, it.corpName, it.stockCode),
            listOf(period(it.period), it.corpCode, it.badge, it.corpName, eok(it.assets), eok(it.liabilities), eok(it.equity), eok(it.capital), it.kind),
            6, if (it.kind == "완전잠식") StockColors.up else StockColors.down,
        )
    },
    "단위 억원 · 자본총계 < 자본금 · 연결재무제표 우선(없으면 별도). 완전잠식은 빨강, 부분잠식은 파랑.",
).copy(paused = d.paused)

private fun convertibleTable(d: ConvertibleScreeningDto) = ResultTable(
    d.status, d.progress.done, d.progress.total, d.checked, d.updatedAt,
    listOf("기준일", "DART코드", "상장구분", "회사명", "CB", "BW", "합계", "건수", "근거"), listOf(88.dp, 80.dp, 72.dp, 150.dp, 72.dp, 72.dp, 76.dp, 48.dp, 56.dp),
    d.items.map {
        val base = ymd(it.baseDate).ifBlank { period(it.period) }
        TableRow(
            corpOf(it.corpCode, it.corpName, it.stockCode),
            listOf(base, it.corpCode, it.badge, it.corpName, eok(it.cbAmount), eok(it.bwAmount), eok(it.total), it.count.toString(),
                if (it.source == "정기보고서") "보고서" else "추정"),
            6, StockColors.up,
            bonds = it.bonds.map { b -> BondLine(b.kind, b.tm.ifBlank { b.name.ifBlank { "-" } }, ymd(b.issued), ymd(b.maturity), b.amount, b.basis != "정기보고서") },
            sub = "기준일 $base · 근거 ${if (it.source == "정기보고서") "보고서" else "추정"} · 단위 억원",
        )
    },
    "단위 억원 · 근거 '보고서'는 최근 정기보고서의 기준일 현재 미상환 사채 표, '추정'은 발행결정 권면총액(전환·조기상환 미반영)입니다.",
).copy(
    paused = d.paused,
    hint = "회사 행을 누르면 회차별 상세 표(회차·발행일·만기일·미상환 잔액)를 볼 수 있습니다.",
    legend = CONVERTIBLE_LEGEND,
)

private const val CONVERTIBLE_LEGEND = "근거란?\n보고서: 정기보고서(사업·반기·분기)의 '미상환 잔액' 표에서 읽은 값으로, 기준일 현재 실제 잔액입니다.\n추정: 보고서에서 읽지 못해 발행결정 공시의 권면총액으로 계산한 값입니다. 이후의 전환·상환·행사가 빠져 있어 실제보다 클 수 있습니다."

@Composable
fun ScreeningResultScreen(vm: AppViewModel, d: Dest.ScreeningResult) {
    // 서버가 수집 중이면 완료될 때까지 몇 초마다 다시 불러온다.
    // 수집은 사용자가 "수집 시작"을 눌러야 시작된다. 누르기 전에는 저장된 결과만 한 번 읽는다.
    // 연도(자본잠식은 보고서도)는 이 화면 안에서 고른다. 처음 값은 이동할 때 받은 값.
    var year by remember(d) { mutableStateOf(d.year) }
    var reprt by remember(d) { mutableStateOf(d.reprt) }
    var started by remember(d, year, reprt) { mutableStateOf(false) }
    val state by produceState<UiState<ResultTable>>(UiState.Loading, d, year, reprt, started) {
        value = UiState.Loading
        while (true) {
            val r = when (d.kind) {
                ScreeningKind.Audit -> vm.repository.screeningAudit(year, started).map(::auditTable)
                ScreeningKind.Capital -> vm.repository.screeningCapital(year, reprt, started).map(::capitalTable)
                ScreeningKind.Convertible -> vm.repository.screeningConvertible(year, started).map(::convertibleTable)
            }
            val t = r.getOrNull()
            if (t == null) { value = UiState.Error(r.exceptionOrNull()?.message ?: "오류가 발생했습니다."); return@produceState }
            value = UiState.Success(t)
            if (t.status == "ready" || t.paused) return@produceState
            if (t.status != "collecting" && !started) return@produceState
            delay(5_000)
        }
    }
    val title = when (d.kind) {
        ScreeningKind.Audit -> "감사의견 비적정 · ${year}년"
        ScreeningKind.Capital -> "자본잠식 · ${year}년 ${REPORTS.first { it.first == reprt }.second}"
        ScreeningKind.Convertible -> "CB·BW · ${year}년"
    }
    Column(Modifier.fillMaxSize()) {
        GradientTopBar(title = title, onBack = { vm.back() })
        UiStateContent(state, onRetry = null) { t -> ResultBody(vm, t, d, year, reprt, onYear = { year = it }, onReprt = { reprt = it }, onStart = { started = true }) }
    }
}

@Composable
private fun ResultBody(vm: AppViewModel, t: ResultTable, d: Dest.ScreeningResult, year: Int, reprt: String, onYear: (Int) -> Unit, onReprt: (String) -> Unit, onStart: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var opening by remember { mutableStateOf(false) }
    var exporting by remember { mutableStateOf(false) }
    var detail by remember { mutableStateOf<TableRow?>(null) }
    val exportExcel: () -> Unit = {
        if (!exporting) {
            exporting = true
            android.widget.Toast.makeText(context, "엑셀을 만드는 중입니다. 법인등록번호를 조회해 시간이 걸릴 수 있습니다.", android.widget.Toast.LENGTH_SHORT).show()
            scope.launch {
                val kind = when (d.kind) { ScreeningKind.Audit -> "audit"; ScreeningKind.Capital -> "capital"; ScreeningKind.Convertible -> "convertible" }
                vm.repository.screeningExport(kind, year, reprt)
                    .onSuccess { bytes ->
                        val name = "EveryCorp_${d.kind.label}_$year".replace(Regex("[^0-9A-Za-z가-힣._-]"), "_") + ".xlsx"
                        val uri = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            val dir = java.io.File(context.cacheDir, "reports").apply { mkdirs() }
                            val file = java.io.File(dir, name).apply { writeBytes(bytes) }
                            androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.files", file)
                        }
                        val send = android.content.Intent(android.content.Intent.ACTION_SEND)
                            .setType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                            .putExtra(android.content.Intent.EXTRA_STREAM, uri)
                            .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        context.startActivity(android.content.Intent.createChooser(send, "엑셀 저장·공유").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                    .onFailure { android.widget.Toast.makeText(context, it.message ?: "엑셀을 만들지 못했습니다.", android.widget.Toast.LENGTH_LONG).show() }
                exporting = false
            }
        }
    }
    // CB·BW 는 회차 상세 표(거기서 기업개황으로), 그 밖의 행은 바로 기업개황으로 연결한다.
    val onRow: (TableRow) -> Unit = { r -> if (r.bonds.isNotEmpty()) detail = r else vm.openCompany(r.corp) }
    detail?.let { r -> BondDetailDialog(r, onClose = { detail = null }, onCompany = { detail = null; vm.openCompany(r.corp) }) }
    Column(Modifier.fillMaxSize()) {
        val collecting = t.status != "ready"
        val running = t.status == "collecting"
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            YearPicker(year, onYear)
            if (d.kind == ScreeningKind.Capital) REPORTS.forEach { (code, label) -> PillChip(label, onClick = { onReprt(code) }, selected = reprt == code) }
            if (t.rows.isNotEmpty()) PillChip(if (exporting) "엑셀 만드는 중…" else "엑셀로 내보내기", onClick = exportExcel, selected = false)
        }
        Text(
            when {
                running -> "서버가 DART에서 수집 중입니다 (${t.done}/${t.total}). 앱을 꺼도 서버에서 계속되고, 그 전 결과는 일부입니다."
                t.paused -> "DART 연결이 계속 실패해 서버가 수집을 잠시 멈췄습니다. 30분쯤 뒤 다시 눌러 주세요. 지금 보이는 건 일부입니다."
                collecting -> "아직 전체를 수집하지 않았습니다. 보이는 결과는 일부입니다."
                else -> "${t.checked}개 법인 확인 · ${t.rows.size}건 · ${t.note}"
            },
            Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (collecting && !running && !t.paused) {
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "수집은 서버가 DART에서 전체 상장사를 조회하는 작업이라 오래 걸리고 호출을 많이 씁니다. 필요할 때만 시작해 주세요.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                PillChip(if (t.checked > 0) "이어서 수집" else "수집 시작", onClick = onStart, selected = true)
            }
        }
        if (t.hint.isNotBlank()) Text(
            "💡 ${t.hint}", Modifier.padding(horizontal = 16.dp, vertical = 4.dp).fillMaxWidth().background(MaterialTheme.colorScheme.tertiaryContainer, androidx.compose.foundation.shape.RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 6.dp),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onTertiaryContainer,
        )
        if (t.legend.isNotBlank()) Text(
            t.legend, Modifier.padding(horizontal = 16.dp, vertical = 4.dp).fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer, androidx.compose.foundation.shape.RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 6.dp),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (t.rows.isEmpty()) {
            EmptyBox(if (collecting) "아직 집계된 항목이 없습니다." else "해당하는 회사가 없습니다.", Modifier.padding(horizontal = 16.dp))
            return
        }
        val total = t.widths.fold(0.dp) { a, b -> a + b }
        // 제목 행은 LazyColumn 밖에 두어 세로로 스크롤해도 그대로 보이고, 가로로는 목록과 함께 움직인다.
        Box(Modifier.weight(1f).horizontalScroll(rememberScrollState())) {
            Column(Modifier.width(total + 32.dp).fillMaxHeight()) {
                Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(horizontal = 16.dp, vertical = 8.dp)) {
                    t.headers.forEachIndexed { i, h -> Cell(h, t.widths[i], i, bold = true, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)) {
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
}

/** 결산연도 선택(최근 7개년). */
@Composable
private fun YearPicker(year: Int, onYear: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val thisYear = LocalDate.now().year
    Box {
        PillChip("연도 ${year} ▾", onClick = { open = true }, selected = true)
        androidx.compose.material3.DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            (thisYear downTo thisYear - 6).forEach { y ->
                androidx.compose.material3.DropdownMenuItem(text = { Text("${y}년") }, onClick = { open = false; onYear(y) })
            }
        }
    }
}

/** CB·BW 회차별 미상환 잔액 표(억원). */
@Composable
private fun BondDetailDialog(r: TableRow, onClose: () -> Unit, onCompany: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onClose,
        title = { Text("${r.corp.corpCode} · ${r.corp.corpName}", style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(r.sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val ws = listOf(36.dp, 44.dp, 74.dp, 74.dp, 64.dp)
                Column(Modifier.horizontalScroll(rememberScrollState())) {
                    Row(Modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(vertical = 6.dp)) {
                        listOf("구분", "회차", "발행일", "만기일", "미상환").forEachIndexed { i, h -> Cell(h, ws[i], if (i < 4) 0 else 9, bold = true, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                    r.bonds.forEach { b ->
                        Row(Modifier.padding(vertical = 5.dp)) {
                            Cell(b.kind, ws[0], 0); Cell(b.tm, ws[1], 0); Cell(b.issued, ws[2], 0); Cell(b.maturity, ws[3], 0)
                            Cell(eok(b.amount) + if (b.estimated) "*" else "", ws[4], 9)
                        }
                    }
                    Row(Modifier.background(MaterialTheme.colorScheme.surfaceContainer).padding(vertical = 6.dp)) {
                        Cell("합계", ws[0] + ws[1] + ws[2] + ws[3], 0, bold = true)
                        Cell(eok(r.bonds.sumOf { it.amount }), ws[4], 9, bold = true)
                    }
                }
                if (r.bonds.any { it.estimated }) Text(
                    "* 추정: 발행결정 권면총액 기준이라 실제 잔액과 다를 수 있습니다.",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { androidx.compose.material3.TextButton(onClick = onCompany) { Text("기업 보기") } },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onClose) { Text("닫기") } },
    )
}

/** 앞 세 열(결산·DART코드·회사명)은 왼쪽 정렬, 나머지 글자·숫자는 오른쪽 정렬. */
@Composable
private fun Cell(text: String, width: Dp, index: Int, bold: Boolean = false, color: Color = Color.Unspecified) {
    Text(
        text.ifBlank { "-" }, Modifier.width(width).padding(horizontal = 4.dp), style = MaterialTheme.typography.bodySmall,
        fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal, color = color,
        textAlign = if (index < 4) TextAlign.Start else TextAlign.End, maxLines = 2,
    )
}
