package io.github.hdlee73.everycorp.ui

import android.content.Intent
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext

/** 앱 전체 PDF 만들기(비교 리포트·일괄 PDF) 진행 알림과 완료 시 공유 창 열기. */
@Composable
fun PdfExportEffect(vm: AppViewModel) {
    val context = LocalContext.current
    val st = vm.pdfExport
    LaunchedEffect(st) {
        when (st) {
            is ExportState.Working -> Toast.makeText(context, "${st.label} PDF를 만드는 중입니다…", Toast.LENGTH_SHORT).show()
            is ExportState.Done -> {
                val send = Intent(Intent.ACTION_SEND).setType("application/pdf")
                    .putExtra(Intent.EXTRA_STREAM, st.uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                context.startActivity(Intent.createChooser(send, "${st.label} PDF 공유·열기").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                vm.clearPdfExport()
            }
            is ExportState.Failed -> { Toast.makeText(context, st.message, Toast.LENGTH_LONG).show(); vm.clearPdfExport() }
            ExportState.Idle -> Unit
        }
    }
}
