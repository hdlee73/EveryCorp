package io.github.hdlee73.everycorp.data.log

import android.content.Context
import android.os.Build
import io.github.hdlee73.everycorp.BuildConfig
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * 앱이 비정상 종료되면 원인(스택 트레이스)을 앱 전용 폴더(files/logs/crash.txt)에 남긴다.
 * 마켓을 거치지 않고 배포하므로 사용자가 '앱 정보 → 오류 기록'에서 내용을 보고 공유할 수 있게 한다.
 * 개인정보 보호: 토큰·키처럼 보이는 값과 긴 숫자(계좌번호 형태)는 기록 전에 가린다. 앱은 계좌 정보를 다루지 않지만 혹시 섞여도 남지 않게 한다.
 */
object CrashLog {
    private const val DIR = "logs"
    private const val FILE = "crash.txt"
    private const val MAX_BYTES = 256 * 1024

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { write(app, "CRASH (${thread.name})", error) }
            previous?.uncaughtException(thread, error)
        }
    }

    /** 앱이 죽지 않는 치명적 오류도 남길 때 쓴다. */
    fun record(context: Context, title: String, error: Throwable) {
        runCatching { write(context.applicationContext, title, error) }
    }

    fun file(context: Context): File = File(File(context.filesDir, DIR), FILE)

    fun read(context: Context): String = runCatching { file(context).takeIf { it.exists() }?.readText().orEmpty() }.getOrDefault("")

    fun clear(context: Context) { runCatching { file(context).delete() } }

    @Synchronized
    private fun write(context: Context, title: String, error: Throwable) {
        val f = file(context)
        f.parentFile?.mkdirs()
        // 너무 커지면 뒤쪽 절반만 남긴다.
        if (f.exists() && f.length() > MAX_BYTES) {
            val keep = f.readText().takeLast(MAX_BYTES / 2)
            f.writeText(keep)
        }
        val now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
        val text = buildString {
            append("===== ").append(now).append(' ').append(title).append(" =====\n")
            append("앱 ").append(BuildConfig.VERSION_NAME).append(" (").append(BuildConfig.VERSION_CODE).append(")")
            append(" · Android ").append(Build.VERSION.RELEASE).append(" (SDK ").append(Build.VERSION.SDK_INT).append(")")
            append(" · ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n')
            append(redact(error.stackTraceToString().take(16_000))).append("\n\n")
        }
        f.appendText(text)
    }

    private val SECRET = Regex("(?i)(bearer\\s+|token[\"'=: ]+|appkey[\"'=: ]+|secretkey[\"'=: ]+|x-app-token[\"'=: ]+)[A-Za-z0-9._\\-]+")
    private val LONG_DIGITS = Regex("(?<![\\d.])\\d{2,6}-\\d{2,6}-\\d{2,8}(?![\\d.])|(?<![\\d.])\\d{10,}(?![\\d.])")

    /** 토큰·키 값과 계좌번호처럼 보이는 숫자(하이픈 묶음, 10자리 이상)를 가린다. */
    fun redact(s: String): String = s.replace(SECRET) { it.groupValues[1] + "***" }.replace(LONG_DIGITS, "***")
}
