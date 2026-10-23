package io.github.hdlee73.everycorp.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.hdlee73.everycorp.AppContainer
import io.github.hdlee73.everycorp.BuildConfig
import io.github.hdlee73.everycorp.data.api.dto.CorpItemDto
import io.github.hdlee73.everycorp.data.api.dto.EtfItemDto
import io.github.hdlee73.everycorp.data.prefs.CorpListCodec
import io.github.hdlee73.everycorp.data.update.CheckState
import io.github.hdlee73.everycorp.data.update.UpdateStatus
import io.github.hdlee73.everycorp.ui.common.CompanyTab
import io.github.hdlee73.everycorp.ui.common.NavState
import io.github.hdlee73.everycorp.ui.common.UiState
import io.github.hdlee73.everycorp.ui.common.toUiState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

sealed interface InstallState {
    data object None : InstallState
    data class Downloading(val progress: Float) : InstallState
    data class Error(val message: String) : InstallState
    data object NeedPermission : InstallState
}

/** 앱 전체 상태: 화면 이동, 검색, 즐겨찾기·최근, 업데이트. */
class AppViewModel(private val c: AppContainer) : ViewModel() {
    var nav by mutableStateOf(NavState())
        private set
    var query by mutableStateOf("")
        private set
    var results by mutableStateOf<UiState<List<CorpItemDto>>>(UiState.Idle)
        private set
    /** ETF 검색 결과. 실패(키움 미설정 등)해도 기업 검색은 그대로 두고 빈 목록으로 둔다. */
    var etfResults by mutableStateOf<List<EtfItemDto>>(emptyList())
        private set
    var searchFocusTick by mutableStateOf(0)
        private set
    var favorites by mutableStateOf(c.prefs.favorites)
        private set
    var recents by mutableStateOf(c.prefs.recents)
        private set

    var updateStatus by mutableStateOf(UpdateStatus())
        private set
    var showUpdateDialog by mutableStateOf(false)
        private set
    var install by mutableStateOf<InstallState>(InstallState.None)
        private set
    var whatsNew by mutableStateOf<String?>(null)
        private set

    private var searchJob: Job? = null

    init {
        val last = c.prefs.lastSeenVersion
        if (last != null && last != BuildConfig.VERSION_NAME) whatsNew = BuildConfig.VERSION_NAME
        c.prefs.lastSeenVersion = BuildConfig.VERSION_NAME
        checkUpdate(manual = false)
    }

    // ───── 이동 ─────
    fun back(): Boolean {
        val prev = nav.back() ?: return false
        nav = prev
        return true
    }

    fun openCompany(corp: CorpItemDto) {
        nav = nav.openCompany(corp)
        recents = CorpListCodec.pushFront(recents, corp, 10).also { c.prefs.recents = it }
    }

    fun openEtf(item: EtfItemDto) { nav = nav.openEtf(item) }

    fun openInfo() { nav = nav.openInfo() }

    /** 기업 화면에서 검색 아이콘 한 번으로 첫 화면으로 돌아와 입력창에 커서를 둔다. */
    fun openSearch() {
        nav = nav.goHome()
        onQuery("")
        searchFocusTick++
    }

    fun selectTab(tab: CompanyTab) { nav = nav.selectTab(tab) }

    // ───── 검색 ─────
    fun onQuery(q: String) {
        query = q
        searchJob?.cancel()
        if (q.isBlank()) { results = UiState.Idle; etfResults = emptyList(); return }
        results = UiState.Loading
        searchJob = viewModelScope.launch {
            delay(250) // 입력이 멈춘 뒤에만 호출(서버·API 한도 보호)
            results = c.repository.search(q).toUiState()
            etfResults = c.repository.searchEtfs(q).getOrDefault(emptyList())
        }
    }

    fun clearRecents() { recents = emptyList(); c.prefs.recents = emptyList() }

    fun isFavorite(corp: CorpItemDto) = favorites.any { it.corpCode == corp.corpCode }

    fun toggleFavorite(corp: CorpItemDto) {
        favorites = if (isFavorite(corp)) favorites.filterNot { it.corpCode == corp.corpCode }
        else CorpListCodec.pushFront(favorites, corp, 50)
        c.prefs.favorites = favorites
    }

    // ───── 업데이트 ─────
    fun checkUpdate(manual: Boolean) {
        viewModelScope.launch {
            updateStatus = c.updates.check()
            val avail = updateStatus.state == CheckState.AVAILABLE
            val dismissed = c.prefs.dismissedUpdate == updateStatus.latest?.version
            if (avail && (manual || updateStatus.forced || !dismissed)) showUpdateDialog = true
        }
    }

    fun dismissUpdateDialog() {
        if (updateStatus.forced) return // 강제 업데이트는 닫을 수 없다
        updateStatus.latest?.version?.let { c.prefs.dismissedUpdate = it }
        showUpdateDialog = false
    }

    fun openUpdateDialog() { showUpdateDialog = true }

    fun dismissWhatsNew() { whatsNew = null }

    fun startInstall() {
        val latest = updateStatus.latest ?: return
        if (!c.installer.canInstall()) {
            install = InstallState.NeedPermission
            c.installer.openInstallPermissionSettings()
            return
        }
        install = InstallState.Downloading(-1f)
        viewModelScope.launch {
            runCatching { c.installer.downloadAndVerify(latest) { p -> install = InstallState.Downloading(p) } }
                .onSuccess { install = InstallState.None; c.installer.install(it) }
                .onFailure { install = InstallState.Error(it.message ?: "업데이트에 실패했습니다.") }
        }
    }
}
