package io.github.hdlee73.everycorp.data.pdf

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Environment
import androidx.core.content.ContextCompat
import io.github.hdlee73.everycorp.BuildConfig
import io.github.hdlee73.everycorp.data.api.dto.AuditReportDto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

sealed interface PdfEvent {
    /** 다운로드를 시작했다. [viaProxy]=true 면 직접 받기가 실패해 서버 중계로 다시 받는 중. */
    data class Started(val viaProxy: Boolean) : PdfEvent
    data class Done(val uri: Uri, val fileName: String, val viaProxy: Boolean, val downloadId: Long) : PdfEvent
    data class Failed(val message: String) : PdfEvent
}

/**
 * 감사/검토보고서 PDF 를 기기에 저장(`Download/EveryCorp/`)하고 열람할 수 있게 한다.
 *
 * 1) DART 주소(`pdf_url`)를 DownloadManager 로 직접 받는다(Referer·UA 지정).
 * 2) 받은 파일이 `%PDF-` 로 시작하지 않거나 실패하면 지우고 서버 중계(`pdf_proxy_path`, X-App-Token)로 한 번 더 받는다.
 * 3) 그래도 실패하면 [PdfEvent.Failed].
 */
class PdfDownloader(
    context: Context,
    private val baseUrl: String = BuildConfig.API_BASE_URL,
    private val appToken: String = BuildConfig.APP_TOKEN,
) {
    private val app = context.applicationContext
    private val dm = app.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

    fun download(report: AuditReportDto): Flow<PdfEvent> = callbackFlow {
        val fileName = PdfSupport.safeFileName(report.fileName, report.title)
        val viaProxy = AtomicBoolean(false)
        val currentId = AtomicLong(enqueue(report, fileName, proxy = false))
        trySend(PdfEvent.Started(viaProxy = false))

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
                if (id != currentId.get()) return
                launch(Dispatchers.IO) {
                    val uri = successfulPdfUri(id)
                    when {
                        uri != null -> {
                            trySend(PdfEvent.Done(uri, fileName, viaProxy.get(), id))
                            close()
                        }
                        !viaProxy.get() -> {
                            dm.remove(id)
                            viaProxy.set(true)
                            currentId.set(enqueue(report, fileName, proxy = true))
                            trySend(PdfEvent.Started(viaProxy = true))
                        }
                        else -> {
                            dm.remove(id)
                            trySend(PdfEvent.Failed("PDF를 내려받지 못했습니다. 잠시 후 다시 시도하거나 DART 원문 보기를 이용해 주세요."))
                            close()
                        }
                    }
                }
            }
        }
        // DownloadManager 완료 방송은 시스템이 보내므로 exported 로 등록해야 받는다.
        ContextCompat.registerReceiver(
            app, receiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), ContextCompat.RECEIVER_EXPORTED,
        )
        awaitClose { runCatching { app.unregisterReceiver(receiver) } }
    }

    private fun enqueue(report: AuditReportDto, fileName: String, proxy: Boolean): Long {
        val url = if (proxy) PdfSupport.proxyUrl(baseUrl, report.pdfProxyPath) else report.pdfUrl
        val request = DownloadManager.Request(Uri.parse(url))
            .setTitle(fileName)
            .setDescription(report.title)
            .setMimeType("application/pdf")
            .addRequestHeader("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124.0 Mobile Safari/537.36")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "EveryCorp/$fileName")
        if (proxy) {
            if (appToken.isNotBlank()) request.addRequestHeader("X-App-Token", appToken)
        } else {
            request.addRequestHeader("Referer", report.viewerUrl)
        }
        return dm.enqueue(request)
    }

    /** 성공했고 내용이 PDF 이면 열 수 있는 content:// 주소, 아니면 null. */
    private fun successfulPdfUri(id: Long): Uri? {
        dm.query(DownloadManager.Query().setFilterById(id))?.use { c ->
            if (!c.moveToFirst()) return null
            val status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            if (status != DownloadManager.STATUS_SUCCESSFUL) return null
        } ?: return null
        val uri = dm.getUriForDownloadedFile(id) ?: return null
        val head = runCatching {
            app.contentResolver.openInputStream(uri)?.use { s -> ByteArray(8).also { s.read(it) } }
        }.getOrNull() ?: return null
        return if (PdfSupport.isPdf(head)) uri else null
    }

    /** 받아 둔 임시 파일을 지운다(사용자가 고른 위치로 복사한 뒤). */
    fun discard(downloadId: Long) { runCatching { dm.remove(downloadId) } }

    companion object {
        /** 저장된 PDF 를 기기의 PDF 뷰어로 연다. 뷰어 앱이 없으면 null(호출 쪽에서 안내). */
        fun viewIntent(context: Context, uri: Uri): Intent? {
            val intent = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/pdf")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            return intent.takeIf { it.resolveActivity(context.packageManager) != null }
        }
    }
}
