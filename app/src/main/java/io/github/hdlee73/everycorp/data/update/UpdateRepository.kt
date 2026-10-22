package io.github.hdlee73.everycorp.data.update

import io.github.hdlee73.everycorp.BuildConfig
import io.github.hdlee73.everycorp.data.api.EveryCorpApi
import io.github.hdlee73.everycorp.data.api.dto.LatestReleaseDto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

enum class CheckState { IDLE, CHECKING, LATEST, AVAILABLE, FAILED }

data class UpdateStatus(
    val state: CheckState = CheckState.IDLE,
    val latest: LatestReleaseDto? = null,
    /** 서버가 정한 최소 지원 버전보다 낮으면 true — 닫을 수 없는 업데이트 팝업을 띄운다. */
    val forced: Boolean = false,
)

/**
 * 업데이트 확인. 서버(/app/meta)를 먼저 보고, 서버가 죽어 있으면 GitHub Releases 를 직접 본다.
 * 릴리스 출처는 항상 이 레포(BuildConfig.GITHUB_REPO) 하나로 고정한다.
 */
class UpdateRepository(
    private val api: EveryCorpApi,
    private val http: OkHttpClient,
    private val currentVersion: String = BuildConfig.VERSION_NAME,
    private val repo: String = BuildConfig.GITHUB_REPO,
) {
    private val _status = MutableStateFlow(UpdateStatus())
    val status: StateFlow<UpdateStatus> = _status

    suspend fun check(): UpdateStatus {
        _status.value = _status.value.copy(state = CheckState.CHECKING)
        val result = runCatching {
            val meta = api.appMeta(currentVersion)
            val latest = meta.latest
            UpdateStatus(
                state = if (latest != null && UpdateLogic.isNewer(latest.version, currentVersion)) CheckState.AVAILABLE else CheckState.LATEST,
                latest = latest,
                forced = meta.forceUpdate,
            )
        }.recoverCatching {
            val latest = fetchFromGithub() ?: error("릴리스 정보를 가져오지 못했습니다.")
            UpdateStatus(
                state = if (UpdateLogic.isNewer(latest.version, currentVersion)) CheckState.AVAILABLE else CheckState.LATEST,
                latest = latest,
            )
        }.getOrElse { UpdateStatus(state = CheckState.FAILED) }
        _status.value = result
        return result
    }

    private suspend fun fetchFromGithub(): LatestReleaseDto? = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("https://api.github.com/repos/$repo/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) null else UpdateLogic.parseGithubRelease(r.body?.string().orEmpty())
        }
    }
}
