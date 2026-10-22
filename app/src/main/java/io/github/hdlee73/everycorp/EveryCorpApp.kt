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
}

class EveryCorpApp : Application() {
    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }

    companion object {
        lateinit var container: AppContainer
            private set
    }
}
