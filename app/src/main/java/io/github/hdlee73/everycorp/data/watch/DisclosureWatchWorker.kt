package io.github.hdlee73.everycorp.data.watch

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.hdlee73.everycorp.R
import io.github.hdlee73.everycorp.data.api.NetworkModule
import io.github.hdlee73.everycorp.data.api.dto.DisclosureDto
import io.github.hdlee73.everycorp.data.prefs.AppPrefs
import java.util.concurrent.TimeUnit

/** 지난 확인 이후 처음 보는 공시만 골라내는 순수 로직. 기준이 없던 첫 확인은 알리지 않고 기준만 저장한다. */
object DisclosureDiff {
    const val KEEP = 30

    /** @return 새 공시(최신순 유지). [seen]이 null(첫 확인)이면 빈 목록. */
    fun newItems(seen: Set<String>?, latest: List<DisclosureDto>): List<DisclosureDto> =
        if (seen == null) emptyList() else latest.filter { it.id !in seen }

    /** 다음 확인에 쓸 기준: 이번 목록 + 기존 기준을 합쳐 최근 [KEEP]개. */
    fun nextSeen(seen: Set<String>?, latest: List<DisclosureDto>): Set<String> =
        (latest.map { it.id } + (seen ?: emptySet())).distinct().take(KEEP).toSet()
}

/** 관심기업의 새 DART 공시를 30분마다 확인해 알림으로 알린다(앱을 열지 않아도 동작, 설정에서 끌 수 있음). */
class DisclosureWatchWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val prefs = AppPrefs(applicationContext)
        if (!prefs.notifyDisclosures) return Result.success()
        val favorites = prefs.favorites.take(MAX_CORPS)
        if (favorites.isEmpty()) return Result.success()

        val api = NetworkModule.api(NetworkModule.retrofit(client = NetworkModule.okHttp()))
        val store = applicationContext.getSharedPreferences("disclosure_watch", Context.MODE_PRIVATE)
        var notified = 0
        for (corp in favorites) {
            val items = runCatching { api.disclosures(corp.corpCode, years = 1, source = "dart", size = 20).items }.getOrNull() ?: continue
            val key = "seen_${corp.corpCode}"
            val seen = store.getStringSet(key, null)?.toSet()
            val fresh = DisclosureDiff.newItems(seen, items)
            store.edit().putStringSet(key, DisclosureDiff.nextSeen(seen, items)).apply()
            if (fresh.isNotEmpty() && notified < MAX_NOTIFICATIONS) {
                notify(applicationContext, corp.corpCode.hashCode(), corp.corpName, fresh)
                notified++
            }
        }
        return Result.success()
    }

    private fun notify(context: Context, id: Int, corpName: String, fresh: List<DisclosureDto>) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "관심기업 새 공시", NotificationManager.IMPORTANCE_DEFAULT))
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
        val open = PendingIntent.getActivity(context, id, launch, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val first = fresh.first().title
        val text = if (fresh.size > 1) "$first 외 ${fresh.size - 1}건" else first
        nm.notify(
            id,
            NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle("$corpName 새 공시")
                .setContentText(text)
                .setContentIntent(open)
                .setAutoCancel(true)
                .build(),
        )
    }

    companion object {
        private const val CHANNEL = "disclosure_watch"
        private const val MAX_CORPS = 20
        private const val MAX_NOTIFICATIONS = 5

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<DisclosureWatchWorker>(30, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniquePeriodicWork("disclosure_watch", ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
