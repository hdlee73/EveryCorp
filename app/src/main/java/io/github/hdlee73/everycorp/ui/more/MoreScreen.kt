package io.github.hdlee73.everycorp.ui.more

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.hdlee73.everycorp.BuildConfig
import io.github.hdlee73.everycorp.data.update.CheckState
import io.github.hdlee73.everycorp.ui.AppViewModel
import io.github.hdlee73.everycorp.ui.common.SectionCard
import io.github.hdlee73.everycorp.ui.common.TabHeader

@Composable
fun MoreScreen(vm: AppViewModel) {
    val st = vm.updateStatus
    Column(Modifier.fillMaxSize()) {
        TabHeader("더보기")
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionCard(Modifier.clickable { vm.openInfo() }) {
                Text("앱 정보 · 업데이트", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Every Corp v${BuildConfig.VERSION_NAME} · ${BuildConfig.AUTHOR_NAME} (${BuildConfig.AUTHOR_EMAIL})",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    if (st.state == CheckState.AVAILABLE) "새 버전 ${st.latest?.version} 이(가) 있습니다." else "업데이트 확인, 자동 업데이트 알림, 공시 알림 설정",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            SectionCard {
                Text("ETF", style = MaterialTheme.typography.titleMedium)
                Text("ETF는 기업 검색 화면에서 ETF명을 검색해 이용합니다.", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
