package io.github.hdlee73.everycorp

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.hdlee73.everycorp.data.update.UpdateWorker
import io.github.hdlee73.everycorp.data.watch.DisclosureWatchWorker
import io.github.hdlee73.everycorp.ui.AppViewModel
import io.github.hdlee73.everycorp.ui.UpdateDialogs
import io.github.hdlee73.everycorp.ui.common.Dest
import io.github.hdlee73.everycorp.ui.alerts.AlertsScreen
import io.github.hdlee73.everycorp.ui.company.CompanyScreen
import io.github.hdlee73.everycorp.ui.etf.EtfScreen
import io.github.hdlee73.everycorp.ui.compare.CompareScreen
import io.github.hdlee73.everycorp.ui.favorites.FavoritesScreen
import io.github.hdlee73.everycorp.ui.home.SearchScreen
import io.github.hdlee73.everycorp.ui.info.AppInfoScreen
import io.github.hdlee73.everycorp.ui.more.MoreScreen
import io.github.hdlee73.everycorp.ui.screening.ScreeningResultScreen
import io.github.hdlee73.everycorp.ui.screening.ScreeningScreen
import io.github.hdlee73.everycorp.ui.theme.EveryCorpTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        UpdateWorker.schedule(this)
        DisclosureWatchWorker.schedule(this)
        setContent {
            EveryCorpTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { Root() }
            }
        }
    }
}

/** 하단 바 5칸. 앱 정보 화면은 더보기 탭 아래에 속한다. */
private enum class BottomTab(val label: String, val icon: ImageVector, val dest: Dest) {
    Search("기업 검색", Icons.Filled.Search, Dest.Home),
    Favorites("관심", Icons.Filled.Star, Dest.Favorites),
    Screening("스크리닝", Icons.Filled.FilterAlt, Dest.Screening),
    Alerts("알림", Icons.Filled.Notifications, Dest.Alerts),
    More("더보기", Icons.Filled.MoreHoriz, Dest.More);

    companion object {
        fun of(d: Dest): BottomTab? = when (d) {
            Dest.Home -> Search
            Dest.Favorites -> Favorites
            Dest.Screening -> Screening
            Dest.Alerts -> Alerts
            Dest.More, Dest.Info -> More
            else -> null
        }
    }
}

@Composable
private fun Root() {
    val vm: AppViewModel = viewModel(factory = viewModelFactory { initializer { AppViewModel(EveryCorpApp.container) } })

    // 업데이트 알림 권한(Android 13+). 거절해도 앱 사용에는 영향 없다.
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) { if (Build.VERSION.SDK_INT >= 33) permission.launch(Manifest.permission.POST_NOTIFICATIONS) }

    // 뒤로가기 버튼·제스처: 직전 화면/탭으로 이동. 더 갈 곳이 없을 때만 앱 종료(핸들러 비활성).
    BackHandler(enabled = vm.nav.canGoBack) { vm.back() }

    // 머리말이 배경색과 같으므로 상태 표시줄 아이콘은 테마에 맞춘다.
    val darkTheme = androidx.compose.foundation.isSystemInDarkTheme()
    val view = LocalView.current
    SideEffect {
        (view.context as? android.app.Activity)?.window?.let { WindowCompat.getInsetsController(it, view).isAppearanceLightStatusBars = !darkTheme }
    }
    val top = vm.nav.top
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f)) {
            when (val d = top) {
                Dest.Home -> SearchScreen(vm)
                Dest.Info -> AppInfoScreen(vm)
                Dest.Favorites -> FavoritesScreen(vm)
                Dest.Screening -> ScreeningScreen(vm)
                Dest.Alerts -> AlertsScreen(vm)
                Dest.More -> MoreScreen(vm)
                is Dest.Compare -> CompareScreen(vm, d)
                is Dest.ScreeningResult -> ScreeningResultScreen(vm, d)
                is Dest.Company -> CompanyScreen(vm, d)
                is Dest.Etf -> EtfScreen(vm, d)
            }
        }
        // 기업·ETF·스크리닝 결과 화면은 자체 상단 바를 쓰므로 하단 바는 최상위 탭 화면에서만 보여 준다.
        val tab = BottomTab.of(top)
        if (tab != null) NavigationBar(containerColor = MaterialTheme.colorScheme.background) {
            BottomTab.entries.forEach { t ->
                NavigationBarItem(
                    selected = t == tab, onClick = { if (t == BottomTab.Search) vm.openSearch() else vm.openTab(t.dest) },
                    icon = { Icon(t.icon, contentDescription = null) }, label = { Text(t.label) },
                )
            }
        }
    }
    UpdateDialogs(vm)
}
