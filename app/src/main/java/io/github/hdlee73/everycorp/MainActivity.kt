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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.hdlee73.everycorp.data.update.UpdateWorker
import io.github.hdlee73.everycorp.ui.AppViewModel
import io.github.hdlee73.everycorp.ui.UpdateDialogs
import io.github.hdlee73.everycorp.ui.common.Dest
import io.github.hdlee73.everycorp.ui.company.CompanyScreen
import io.github.hdlee73.everycorp.ui.home.SearchScreen
import io.github.hdlee73.everycorp.ui.info.AppInfoScreen
import io.github.hdlee73.everycorp.ui.theme.EveryCorpTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        UpdateWorker.schedule(this)
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

    when (val d = vm.nav.top) {
        Dest.Home -> SearchScreen(vm)
        Dest.Info -> AppInfoScreen(vm)
        is Dest.Company -> CompanyScreen(vm, d)
    }
    UpdateDialogs(vm)
}
