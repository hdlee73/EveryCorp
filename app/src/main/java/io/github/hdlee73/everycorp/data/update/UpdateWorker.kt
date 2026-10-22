package io.github.hdlee73.everycorp.data.update

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
import io.github.hdlee73.everycorp.BuildConfig
import io.github.hdlee73.everycorp.R
import io.github.hdlee73.everycorp.data.api.NetworkModule
import java.util.concurrent.TimeUnit

/** 앱을 열지 않아도 새 버전이 나오면 12시간 안에 알림을 보낸다. 같은 버전은 한 번만 알린다. */
class UpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        runCatching {
            val client = NetworkModule.okHttp()
            val status = UpdateRepository(NetworkModule.api(NetworkModule.retrofit(client = client)), client).check()
            val latest = status.latest
            if (status.state == CheckState.AVAILABLE && latest != null) notifyOnce(applicationContext, latest.version)
        }
        return Result.success()
    }

    private fun notifyOnce(context: Context, version: String) {
        val prefs = context.getSharedPreferences("update_worker", Context.MODE_PRIVATE)
        if (prefs.getString("notified", null) == version) return
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "앱 업데이트", NotificationManager.IMPORTANCE_HIGH))
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
        val open = PendingIntent.getActivity(context, 0, launch, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        nm.notify(
            1001,
            NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle("EveryCorp 새 버전 $version")
                .setContentText("눌러서 앱을 열고 업데이트하세요.")
                .setContentIntent(open)
                .setAutoCancel(true)
                .build(),
        )
        prefs.edit().putString("notified", version).apply()
    }

    companion object {
        private const val CHANNEL = "app_update"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<UpdateWorker>(12, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniquePeriodicWork("update_check", ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
