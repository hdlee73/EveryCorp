package io.github.hdlee73.everycorp.data.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import io.github.hdlee73.everycorp.data.api.dto.LatestReleaseDto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest

/**
 * 새 APK 를 내려받아 **검증한 뒤에만** 설치 화면을 연다.
 * 검증 ① SHA-256 이 릴리스의 .sha256 과 같은지 ② 패키지명이 같고 서명 인증서가 지금 설치된 앱과 같은지.
 * (서명키는 GitHub Secrets 의 비공개 키뿐이라, 다른 사람이 만든 APK 는 ②에서 걸러진다 — docs/ARCHITECTURE.md 3.2)
 */
class UpdateInstaller(private val context: Context, private val http: OkHttpClient) {
    private val dir get() = File(context.cacheDir, "app_updates").apply { mkdirs() }

    fun canInstall(): Boolean = context.packageManager.canRequestPackageInstalls()

    fun openInstallPermissionSettings() {
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    /** [onProgress] 는 0..1 (크기를 모르면 -1). 실패하면 예외. */
    suspend fun downloadAndVerify(info: LatestReleaseDto, onProgress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        val url = info.apkUrl ?: error("릴리스에 APK 파일이 없습니다.")
        dir.listFiles()?.forEach { it.delete() }
        val out = File(dir, "everycorp-v${info.version}.apk")
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            http.newCall(Request.Builder().url(url).build()).execute().use { r ->
                if (!r.isSuccessful) error("내려받기 실패 (HTTP ${r.code})")
                val body = r.body ?: error("빈 응답입니다.")
                val total = body.contentLength().takeIf { it > 0 } ?: info.apkSize ?: -1L
                var done = 0L
                body.byteStream().use { input ->
                    out.outputStream().use { os ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            os.write(buf, 0, n)
                            digest.update(buf, 0, n)
                            done += n
                            onProgress(if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else -1f)
                        }
                    }
                }
                if (total > 0 && done != total) error("내려받은 파일 크기가 맞지 않습니다.")
            }
            verifySha256(info, digest.digest().joinToString("") { "%02x".format(it) })
            verifySignature(out)
            out
        } catch (e: Exception) {
            out.delete()
            throw e
        }
    }

    private fun verifySha256(info: LatestReleaseDto, actual: String) {
        val shaUrl = info.sha256Url ?: error("릴리스에 체크섬(.sha256)이 없어 설치를 중단했습니다.")
        val text = http.newCall(Request.Builder().url(shaUrl).build()).execute().use { it.body?.string().orEmpty() }
        val expected = UpdateLogic.parseSha256(text) ?: error("체크섬 파일을 해석하지 못했습니다.")
        if (!expected.equals(actual, ignoreCase = true)) error("체크섬이 일치하지 않아 설치를 중단했습니다.")
    }

    @Suppress("DEPRECATION")
    private fun verifySignature(apk: File) {
        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val archive = pm.getPackageArchiveInfo(apk.path, flags) ?: error("APK 를 읽을 수 없습니다.")
        if (archive.packageName != context.packageName) error("다른 앱의 APK 여서 설치를 중단했습니다.")
        val installed = pm.getPackageInfo(context.packageName, flags)
        fun sigs(p: android.content.pm.PackageInfo): Set<String> =
            (if (Build.VERSION.SDK_INT >= 28) p.signingInfo?.apkContentsSigners else p.signatures)
                ?.map { MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).joinToString("") { b -> "%02x".format(b) } }
                ?.toSet().orEmpty()
        val a = sigs(archive)
        if (a.isEmpty() || a != sigs(installed)) error("서명이 달라 설치를 중단했습니다.")
    }

    fun install(apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", apk)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}
