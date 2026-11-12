package io.github.hdlee73.everycorp.data.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import io.github.hdlee73.everycorp.data.api.dto.LatestReleaseDto
import java.io.File

/**
 * 새 버전 APK 를 **브라우저로** 내려받게 안내한다.
 *
 * 예전에는 앱이 직접 APK 를 받아 설치 화면을 열었지만(REQUEST_INSTALL_PACKAGES 권한 필요),
 * "자기 자신을 내려받아 설치하는 앱"은 Play 프로텍트가 위험 신호로 보는 패턴이라 v1.13.1 부터 권한을 뺐다.
 * 설치는 브라우저 다운로드 → 시스템 설치 화면이 맡는다. 서명이 다른 APK 는 안드로이드가 업데이트 설치를 거부하므로
 * 다른 사람이 만든 APK 로 바꿔치기되는 위험은 OS 가 막는다(docs/ARCHITECTURE.md 3.2).
 */
class UpdateInstaller(private val context: Context) {

    init {
        // 이전 버전이 앱 캐시에 받아 둔 APK 가 남아 있으면 지운다(용량만 차지).
        runCatching { File(context.cacheDir, "app_updates").deleteRecursively() }
    }

    /** 브라우저로 APK(없으면 릴리스 페이지)를 연다. 열 수 있는 앱이 없으면 false. */
    fun openDownload(info: LatestReleaseDto): Boolean {
        val url = UpdateLogic.downloadUrl(info) ?: return false
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            .addCategory(Intent.CATEGORY_BROWSABLE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(intent) }.isSuccess
    }
}
