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
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import io.github.hdlee73.everycorp.ui.theme.AppColors
import io.github.hdlee73.everycorp.ui.theme.HeroPalette
import io.github.hdlee73.everycorp.ui.theme.HeroPalettes
import io.github.hdlee73.everycorp.ui.theme.LocalHeroPalette
import kotlinx.coroutines.launch
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
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.hdlee73.everycorp.data.update.UpdateWorker
import io.github.hdlee73.everycorp.data.watch.DisclosureWatchWorker
import io.github.hdlee73.everycorp.ui.AppViewModel
import io.github.hdlee73.everycorp.ui.PdfExportEffect
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

/** 왼쪽 서랍 메뉴 항목. 앱 정보 화면은 더보기 묶음에 속한다. */
private enum class MenuItem(val label: String, val icon: ImageVector, val dest: Dest, val palette: HeroPalette) {
    Search("기업 검색", Icons.Filled.Search, Dest.Home, HeroPalettes.Sky),
    Favorites("관심", Icons.Filled.Star, Dest.Favorites, HeroPalettes.Rose),
    Screening("스크리닝", Icons.Filled.FilterAlt, Dest.Screening, HeroPalettes.Indigo),
    Alerts("알림", Icons.Filled.Notifications, Dest.Alerts, HeroPalettes.Coral),
    More("더보기", Icons.Filled.MoreHoriz, Dest.More, HeroPalettes.Slate),
    Info("앱 정보", Icons.Filled.Info, Dest.Info, HeroPalettes.Slate);

    companion object {
        fun of(d: Dest): MenuItem? = entries.firstOrNull { it.dest == d }
    }
}

@Composable
private fun Root() {
    val vm: AppViewModel = viewModel(factory = viewModelFactory { initializer { AppViewModel(EveryCorpApp.container) } })

    // 업데이트 알림 권한(Android 13+). 거절해도 앱 사용에는 영향 없다.
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) { if (Build.VERSION.SDK_INT >= 33) permission.launch(Manifest.permission.POST_NOTIFICATIONS) }

    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    // 뒤로가기 버튼·제스처: 서랍이 열려 있으면 닫고, 아니면 직전 화면/탭으로 이동. 더 갈 곳이 없을 때만 앱 종료(핸들러 비활성).
    BackHandler(enabled = drawerState.isOpen) { scope.launch { drawerState.close() } }
    BackHandler(enabled = vm.nav.canGoBack && !drawerState.isOpen) { vm.back() }

    // 머리말이 밝은 하늘색 계열이라 상태 표시줄 아이콘은 라이트 테마에서 어둡게 한다.
    val darkTheme = androidx.compose.foundation.isSystemInDarkTheme()
    val view = LocalView.current
    SideEffect {
        (view.context as? android.app.Activity)?.window?.let { WindowCompat.getInsetsController(it, view).isAppearanceLightStatusBars = !darkTheme }
    }
    val top = vm.nav.top
    val item = MenuItem.of(top)
    // 서랍 메뉴 화면(검색·관심·스크리닝·알림·더보기)에서만 위 메뉴 바를 보여 준다. 기업·ETF·스크리닝 결과·앱 정보는 자체 상단 바를 쓴다.
    val showMenuBar = item != null && item != MenuItem.Info

    fun go(m: MenuItem) {
        when (m) {
            MenuItem.Search -> vm.openSearch()
            MenuItem.Info -> vm.openInfo()
            else -> vm.openTab(m.dest)
        }
        scope.launch { drawerState.close() }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = showMenuBar || drawerState.isOpen,
        drawerContent = {
            ModalDrawerSheet {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
                    Column(Modifier.padding(start = 28.dp, end = 16.dp, top = 28.dp, bottom = 12.dp)) {
                        Text("Every Corp", style = MaterialTheme.typography.headlineSmall)
                        Text("v${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    listOf(
                        null to listOf(MenuItem.Search, MenuItem.Favorites, MenuItem.Screening, MenuItem.Alerts),
                        "더보기" to listOf(MenuItem.More, MenuItem.Info),
                    ).forEachIndexed { index, (title, items) ->
                        if (index > 0) HorizontalDivider(Modifier.padding(horizontal = 28.dp, vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
                        if (title != null) Text(title, Modifier.padding(start = 28.dp, top = 4.dp, bottom = 4.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        items.forEach { m ->
                            NavigationDrawerItem(
                                icon = { Icon(m.icon, contentDescription = null) }, label = { Text(m.label) },
                                selected = item == m, onClick = { go(m) }, modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
                            )
                        }
                    }
                }
            }
        },
    ) {
        CompositionLocalProvider(LocalHeroPalette provides (item?.palette ?: LocalHeroPalette.current)) {
            Column(Modifier.fillMaxSize()) {
                if (showMenuBar) {
                    Spacer(Modifier.fillMaxWidth().background(AppColors.heroStart).windowInsetsTopHeight(WindowInsets.statusBars))
                    Row(Modifier.fillMaxWidth().background(AppColors.heroStart).height(44.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Filled.Menu, contentDescription = "메뉴 열기", tint = AppColors.onHero)
                        }
                        Text("Every Corp", color = AppColors.onHero, maxLines = 1, style = MaterialTheme.typography.titleMedium)
                    }
                }
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
            }
        }
    }
    UpdateDialogs(vm)
    PdfExportEffect(vm)
}
