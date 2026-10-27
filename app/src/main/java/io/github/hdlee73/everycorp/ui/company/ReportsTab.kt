package io.github.hdlee73.everycorp.ui.company

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.hdlee73.everycorp.data.api.dto.AuditReportDto
import io.github.hdlee73.everycorp.data.pdf.PdfDownloader
import io.github.hdlee73.everycorp.data.pdf.PdfSupport
import io.github.hdlee73.everycorp.ui.common.EmptyBox
import io.github.hdlee73.everycorp.ui.common.Fmt
import io.github.hdlee73.everycorp.ui.common.SectionCard
import io.github.hdlee73.everycorp.ui.common.UiStateContent
import io.github.hdlee73.everycorp.ui.common.openInAppBrowser

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportsTab(vm: CompanyViewModel) {
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        var annual by rememberSaveable { mutableStateOf(true) }
        UiStateContent(vm.reports, onRetry = { vm.loadReports(refresh = true) }, modifier = Modifier.padding(top = 16.dp)) { r ->
            val annualList = r.reports.filter { it.kind == "AUDIT" }
            val reviewList = r.reports.filter { it.kind != "AUDIT" }
            val shown = if (annual) annualList else reviewList
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 16.dp)) {
                item {
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        SegmentedButton(selected = annual, onClick = { annual = true }, shape = SegmentedButtonDefaults.itemShape(0, 2)) { Text("연간 감사 (${annualList.size})") }
                        SegmentedButton(selected = !annual, onClick = { annual = false }, shape = SegmentedButtonDefaults.itemShape(1, 2)) { Text("분·반기 검토 (${reviewList.size})") }
                    }
                }
                if (shown.isEmpty()) item { EmptyBox("최근 3년(${Fmt.date(r.from)} ~ ${Fmt.date(r.to)}) ${if (annual) "연간 감사보고서" else "분·반기 검토보고서"}가 없습니다.") }
                if (r.partial) item {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("일부 보고서를 확인하지 못했을 수 있습니다.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        Button(onClick = { vm.loadReports(refresh = true) }) { Text("다시 불러오기") }
                    }
                }
                items(shown, key = { it.id }) { ReportCard(vm, it) }
            }
        }
    }
}

@Composable
private fun ReportCard(vm: CompanyViewModel, rep: AuditReportDto) {
    val context = LocalContext.current
    val state = vm.pdfStates[rep.id]
    // 버튼을 누르면 먼저 저장할 위치·파일 이름을 고르게 한다.
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        if (uri != null) vm.download(rep, uri, context.contentResolver)
    }
    SectionCard {
        Text(rep.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Text(
            listOf(rep.periodLabel, if (rep.kind == "AUDIT") "감사" else "검토", if (rep.consolidated) "연결" else "별도")
                .filter { it.isNotBlank() }.joinToString(" · "),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val who = if (rep.kind == "AUDIT") "감사인" else "검토인"
        val what = if (rep.kind == "AUDIT") "의견" else "결론"
        Text(
            "$who ${rep.auditor.ifBlank { "확인 불가" }} · $what ${rep.opinion.ifBlank { "확인 불가" }}",
            style = MaterialTheme.typography.bodyMedium,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when (state) {
                is PdfUi.Done -> Button(onClick = {
                    val intent = PdfDownloader.viewIntent(context, state.uri)
                    if (intent != null) context.startActivity(intent) else Toast.makeText(context, "PDF 뷰어 앱이 없습니다. Download/EveryCorp 폴더에 저장되었습니다.", Toast.LENGTH_LONG).show()
                }) { Text("PDF 열기") }
                is PdfUi.Downloading -> Button(onClick = {}, enabled = false) { Text(if (state.viaProxy) "서버 경유 받는 중…" else "받는 중…") }
                else -> Button(onClick = { pick.launch(PdfSupport.safeFileName(rep.fileName, rep.title)) }) { Text("PDF 저장") }
            }
            OutlinedButton(onClick = { openInAppBrowser(context, rep.viewerUrl) }) { Text("DART 원문") }
        }
        (state as? PdfUi.Failed)?.let { Text(it.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }
}
