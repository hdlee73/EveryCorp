package io.github.hdlee73.everycorp

import android.app.Application
import android.content.Context
import io.github.hdlee73.everycorp.data.api.NetworkModule
import io.github.hdlee73.everycorp.data.kind.KindClient
import io.github.hdlee73.everycorp.data.pdf.PdfDownloader
import io.github.hdlee73.everycorp.data.prefs.AppPrefs
import io.github.hdlee73.everycorp.data.repo.CorpRepository
import io.github.hdlee73.everycorp.data.update.UpdateInstaller
import io.github.hdlee73.everycorp.data.update.UpdateRepository

/** 직접 만든 가벼운 DI 컨테이너(Hilt 없이 충분한 규모). */
class AppContainer(context: Context) {
    val http = NetworkModule.okHttp()
    private val api = NetworkModule.api(NetworkModule.retrofit(client = http))
    // KIND 는 국내 IP 에서만 열리므로 앱이 직접 호출한다(서버 토큰 인터셉터를 거치지 않는 별도 클라이언트).
    private val kindClient = KindClient(okhttp3.OkHttpClient.Builder().connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS).readTimeout(30, java.util.concurrent.TimeUnit.SECONDS).build())
    val repository = CorpRepository(api, kindClient)
    val updates = UpdateRepository(api, http)
    val installer = UpdateInstaller(context, http)
    val pdf = PdfDownloader(context)
    val prefs = AppPrefs(context)
    /** 실시간 시세·호가(서버 WebSocket 중계). */
    val realtime = io.github.hdlee73.everycorp.data.realtime.RealtimeClient(http)
}

class EveryCorpApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // 비정상 종료 원인을 기기 안 파일로 남긴다(앱 정보 → 오류 기록).
        io.github.hdlee73.everycorp.data.log.CrashLog.install(this)
        container = AppContainer(this)
    }

    companion object {
        lateinit var container: AppContainer
            private set
    }
}
