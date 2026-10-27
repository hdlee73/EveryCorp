package io.github.hdlee73.everycorp.ui.company

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import io.github.hdlee73.everycorp.data.report.SummarySection
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import io.github.hdlee73.everycorp.ui.common.UiState
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import android.content.Intent
import android.widget.Toast
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.hdlee73.everycorp.EveryCorpApp
import io.github.hdlee73.everycorp.ui.AppViewModel
import io.github.hdlee73.everycorp.ui.common.CompanyTab
import io.github.hdlee73.everycorp.ui.common.Dest
import io.github.hdlee73.everycorp.ui.common.GradientTopBar
import io.github.hdlee73.everycorp.ui.common.PillChip
import io.github.hdlee73.everycorp.ui.common.UnderlineTabs
import io.github.hdlee73.everycorp.ui.theme.HeroPalettes
import io.github.hdlee73.everycorp.ui.theme.LocalHeroPalette

/** 탭마다 머리말 색을 달리해(FSS Insights 방식) 어느 탭인지 한눈에 구분되게 한다. */
fun CompanyTab.palette() = when (this) {
    CompanyTab.Overview -> HeroPalettes.Sky
    CompanyTab.Reports -> HeroPalettes.Violet
    CompanyTab.Disclosures -> HeroPalettes.Indigo
    CompanyTab.Stock -> HeroPalettes.Teal
    CompanyTab.Debt -> HeroPalettes.Amber
    CompanyTab.News -> HeroPalettes.Rose
}

@Composable
fun CompanyScreen(app: AppViewModel, dest: Dest.Company) {
    val corp = dest.corp
    val vm: CompanyViewModel = viewModel(
        key = "company-${corp.corpCode}",
        factory = viewModelFactory {
            initializer {
                val c = EveryCorpApp.container
                CompanyViewModel(corp, c.repository, c.pdf)
            }
        },
    )
    val tab = dest.current
    LaunchedEffect(corp.corpCode, tab) { vm.ensure(tab) }

    val context = LocalContext.current
    val summary = vm.summaryPdf
    // 요약 PDF 버튼: 저장할 위치를 먼저 고른 뒤 그 위치에 만든다.
    var sections by remember { mutableStateOf(loadPdfSections(context)) }
    var chooseSections by remember { mutableStateOf(false) }
    val pickSummary = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/pdf"),
    ) { uri -> if (uri != null) vm.exportSummaryPdf(context.applicationContext, uri, sections) }
    // 한글(HWPX)은 표준 MIME 이 없어 일반 파일 형식으로 두고 확장자(.hwpx)를 파일 이름에 붙인다.
    val pickHwpx = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri -> if (uri != null) vm.exportSummaryPdf(context.applicationContext, uri, sections, hwpx = true) }
    if (chooseSections) PdfSectionsDialog(
        corpName = corp.corpName, selected = sections,
        onCancel = { chooseSections = false },
        onConfirm = { picked, hwpx ->
            sections = picked; savePdfSections(context, picked); chooseSections = false
            if (hwpx) pickHwpx.launch("${io.github.hdlee73.everycorp.data.pdf.PdfSupport.safeFileName("${corp.corpName}_요약", "summary").removeSuffix(".pdf")}.hwpx")
            else pickSummary.launch(io.github.hdlee73.everycorp.data.pdf.PdfSupport.safeFileName("${corp.corpName}_요약", "summary"))
        },
    )
    LaunchedEffect(summary) {
        when (summary) {
            is PdfUi.Done -> {
                val send = Intent(Intent.ACTION_SEND).setType("application/pdf")
                    .putExtra(Intent.EXTRA_STREAM, summary.uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                context.startActivity(Intent.createChooser(send, "요약 PDF 공유·열기").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                vm.clearSummaryPdf()
            }
            is PdfUi.Saved -> {
                if (vm.summaryFormat == "HWPX") {
                    Toast.makeText(context, "선택한 위치에 한글(HWPX) 문서를 저장했습니다. 한컴오피스 등으로 열 수 있습니다.", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(context, "선택한 위치에 요약 PDF를 저장했습니다.", Toast.LENGTH_SHORT).show()
                    io.github.hdlee73.everycorp.data.pdf.PdfDownloader.viewIntent(context, summary.uri)?.let { context.startActivity(it) }
                }
                vm.clearSummaryPdf()
            }
            is PdfUi.Failed -> { Toast.makeText(context, summary.message, Toast.LENGTH_LONG).show(); vm.clearSummaryPdf() }
            else -> Unit
        }
    }

    CompositionLocalProvider(LocalHeroPalette provides tab.palette()) {
        Column(Modifier.fillMaxSize().navigationBarsPadding()) {
            GradientTopBar(
                title = corp.corpName,
                onBack = { app.back() },
                // 기업개황(DART corp_cls)의 구분을 우선한다. 시장을 모르면 "상장" 같은 추정 값 없이 비운다.
                badge = ((vm.overview as? UiState.Success)?.data?.badge).orEmpty().ifBlank { corp.badge.ifBlank { if (corp.isListed) "" else "외감" } }.ifBlank { null },
                trailing = {
                    if (tab == CompanyTab.Reports) {
                        IconButton(onClick = { vm.loadReports(refresh = true) }) { Icon(Icons.Filled.Refresh, contentDescription = "감사보고서 새로 불러오기") }
                    }
                    if (summary is PdfUi.Downloading) {
                        CircularProgressIndicator(Modifier.padding(12.dp).size(24.dp), strokeWidth = 2.dp)
                    } else {
                        IconButton(onClick = { chooseSections = true }) {
                            Icon(Icons.Filled.PictureAsPdf, contentDescription = "요약 PDF 만들기")
                        }
                    }
                    IconButton(onClick = app::openSearch) { Icon(Icons.Filled.Search, contentDescription = "기업 검색") }
                    val fav = app.isFavorite(corp)
                    IconButton(onClick = { app.toggleFavorite(corp) }) {
                        Icon(if (fav) Icons.Filled.Star else Icons.Filled.StarBorder, contentDescription = if (fav) "관심기업 해제" else "관심기업 추가")
                    }
                },
            )
            val tabs = CompanyTab.entries
            UnderlineTabs(tabs.map { it.label }, tabs.indexOf(tab), onSelect = { app.selectTab(tabs[it]) })
            when (tab) {
                CompanyTab.Overview -> OverviewTab(vm)
                CompanyTab.Reports -> ReportsTab(vm)
                CompanyTab.Disclosures -> DisclosuresTab(vm)
                CompanyTab.Stock -> StockTab(vm)
                CompanyTab.Debt -> DebtTab(vm)
                CompanyTab.News -> NewsTab(vm)
            }
        }
    }
}


private const val PDF_PREFS = "pdf_sections"

/** 마지막에 고른 PDF 항목. 처음에는 전부. */
private fun loadPdfSections(context: Context): Set<SummarySection> {
    val saved = runCatching { context.getSharedPreferences(PDF_PREFS, Context.MODE_PRIVATE).getStringSet("picked", null) }.getOrNull()
        ?: return SummarySection.entries.toSet()
    return SummarySection.entries.filter { it.name in saved }.toSet()
}

private fun savePdfSections(context: Context, picked: Set<SummarySection>) {
    runCatching { context.getSharedPreferences(PDF_PREFS, Context.MODE_PRIVATE).edit().putStringSet("picked", picked.map { it.name }.toSet()).apply() }
}

/** 요약 PDF에 넣을 항목을 고르는 창. 확인하면 저장 위치 선택으로 이어진다. */
@Composable
private fun PdfSectionsDialog(corpName: String, selected: Set<SummarySection>, onCancel: () -> Unit, onConfirm: (Set<SummarySection>, Boolean) -> Unit) {
    var picked by remember { mutableStateOf(selected) }
    var hwpx by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("저장할 항목") },
        text = {
            Column {
                Text("$corpName · 선택한 항목만 A4 요약본에 담깁니다", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                SummarySection.entries.forEach { sec ->
                    Row(
                        Modifier.fillMaxWidth().clickable { picked = if (sec in picked) picked - sec else picked + sec },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = sec in picked, onCheckedChange = { picked = if (it) picked + sec else picked - sec })
                        Text(sec.label)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("형식", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    PillChip("PDF", onClick = { hwpx = false }, selected = !hwpx)
                    PillChip("한글(HWPX)", onClick = { hwpx = true }, selected = hwpx)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    TextButton(onClick = { picked = SummarySection.entries.toSet() }) { Text("전체 선택") }
                    TextButton(onClick = { picked = emptySet() }) { Text("전체 해제") }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(picked, hwpx) }, enabled = picked.isNotEmpty()) { Text("저장 위치 선택…") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("취소") } },
    )
}
