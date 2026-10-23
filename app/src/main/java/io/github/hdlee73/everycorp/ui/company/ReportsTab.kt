package io.github.hdlee73.everycorp.ui.company

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.hdlee73.everycorp.data.api.dto.AuditReportDto
import io.github.hdlee73.everycorp.data.pdf.PdfDownloader
import io.github.hdlee73.everycorp.ui.common.EmptyBox
import io.github.hdlee73.everycorp.ui.common.Fmt
import io.github.hdlee73.everycorp.ui.common.SectionCard
import io.github.hdlee73.everycorp.ui.common.UiStateContent
import io.github.hdlee73.everycorp.ui.common.openInAppBrowser

@Composable
fun ReportsTab(vm: CompanyViewModel) {
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        UiStateContent(vm.reports, onRetry = vm::loadReports, modifier = Modifier.padding(top = 16.dp)) { r ->
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 16.dp)) {
                if (r.reports.isEmpty()) item { EmptyBox("최근 3년(${Fmt.date(r.from)} ~ ${Fmt.date(r.to)}) 감사·검토보고서가 없습니다.") }
                if (r.partial) item { Text("일부 보고서를 확인하지 못했을 수 있습니다. 새로고침해 주세요.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                items(r.reports, key = { it.id }) { ReportCard(vm, it) }
            }
        }
    }
}

@Composable
private fun ReportCard(vm: CompanyViewModel, rep: AuditReportDto) {
    val context = LocalContext.current
    val state = vm.pdfStates[rep.id]
    SectionCard {
        Text(rep.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Text(
            listOf(rep.periodLabel, if (rep.kind == "AUDIT") "감사" else "검토", if (rep.consolidated) "연결" else "별도")
                .filter { it.isNotBlank() }.joinToString(" · "),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (rep.auditor.isNotBlank() || rep.opinion.isNotBlank()) {
            Text(
                listOf("감사인 ${rep.auditor}".takeIf { rep.auditor.isNotBlank() }, "의견 ${rep.opinion}".takeIf { rep.opinion.isNotBlank() })
                    .filterNotNull().joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when (state) {
                is PdfUi.Done -> Button(onClick = {
                    val intent = PdfDownloader.viewIntent(context, state.uri)
                    if (intent != null) context.startActivity(intent) else Toast.makeText(context, "PDF 뷰어 앱이 없습니다. Download/EveryCorp 폴더에 저장되었습니다.", Toast.LENGTH_LONG).show()
                }) { Text("PDF 열기") }
                is PdfUi.Downloading -> Button(onClick = {}, enabled = false) { Text(if (state.viaProxy) "서버 경유 받는 중…" else "받는 중…") }
                else -> Button(onClick = { vm.download(rep) }) { Text("PDF 다운로드") }
            }
            OutlinedButton(onClick = { openInAppBrowser(context, rep.viewerUrl) }) { Text("DART 원문") }
        }
        (state as? PdfUi.Failed)?.let { Text(it.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }
}
