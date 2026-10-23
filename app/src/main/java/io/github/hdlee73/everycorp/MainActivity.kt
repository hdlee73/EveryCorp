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
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Search
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
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.hdlee73.everycorp.data.update.UpdateWorker
import io.github.hdlee73.everycorp.data.watch.DisclosureWatchWorker
import io.github.hdlee73.everycorp.ui.AppViewModel
import io.github.hdlee73.everycorp.ui.UpdateDialogs
import io.github.hdlee73.everycorp.ui.common.Dest
import io.github.hdlee73.everycorp.ui.company.CompanyScreen
import io.github.hdlee73.everycorp.ui.etf.EtfScreen
import io.github.hdlee73.everycorp.ui.home.SearchScreen
import io.github.hdlee73.everycorp.ui.info.AppInfoScreen
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
                is Dest.Company -> CompanyScreen(vm, d)
                is Dest.Etf -> EtfScreen(vm, d)
            }
        }
        // 첫 화면과 메뉴에서만 하단 바를 보여 준다(기업·ETF 화면은 자체 상단 바와 탭 사용).
        if (top == Dest.Home || top == Dest.Info) NavigationBar(containerColor = MaterialTheme.colorScheme.background) {
            NavigationBarItem(
                selected = top == Dest.Home, onClick = { vm.openSearch() },
                icon = { Icon(Icons.Filled.Search, contentDescription = null) }, label = { Text("기업 검색") },
            )
            NavigationBarItem(
                selected = top == Dest.Info, onClick = { vm.openInfo() },
                icon = { Icon(Icons.Filled.Info, contentDescription = null) }, label = { Text("앱 정보") },
            )
        }
    }
    UpdateDialogs(vm)
}
