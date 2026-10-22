package io.github.hdlee73.everycorp.ui.info

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.hdlee73.everycorp.BuildConfig
import io.github.hdlee73.everycorp.data.update.CheckState
import io.github.hdlee73.everycorp.ui.AppViewModel
import io.github.hdlee73.everycorp.ui.common.GradientTopBar
import io.github.hdlee73.everycorp.ui.common.LabelValue
import io.github.hdlee73.everycorp.ui.common.SectionCard
import io.github.hdlee73.everycorp.ui.common.openInAppBrowser

@Composable
fun AppInfoScreen(vm: AppViewModel) {
    val context = LocalContext.current
    val st = vm.updateStatus
    Column(Modifier.fillMaxSize().navigationBarsPadding()) {
        GradientTopBar(title = "앱 정보", onBack = { vm.back() })
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionCard {
                Text("Every Corp 기업정보", style = MaterialTheme.typography.titleLarge)
                LabelValue("버전", BuildConfig.VERSION_NAME)
                LabelValue("만든이", "${BuildConfig.AUTHOR_NAME} (${BuildConfig.AUTHOR_EMAIL})")
                LabelValue("소스", "github.com/${BuildConfig.GITHUB_REPO}")
            }
            SectionCard(title = "업데이트") {
                Text(
                    when (st.state) {
                        CheckState.IDLE -> "아직 확인하지 않았습니다."
                        CheckState.CHECKING -> "확인 중…"
                        CheckState.LATEST -> "최신 버전입니다."
                        CheckState.AVAILABLE -> "새 버전 ${st.latest?.version} 이(가) 있습니다."
                        CheckState.FAILED -> "업데이트 정보를 가져오지 못했습니다."
                    },
                    style = MaterialTheme.typography.bodyLarge,
                )
                st.latest?.takeIf { it.notes.isNotBlank() }?.let {
                    Text("변경 내용", style = MaterialTheme.typography.titleSmall)
                    Text(it.notes, style = MaterialTheme.typography.bodyMedium)
                }
                Button(onClick = { vm.checkUpdate(manual = true) }, enabled = st.state != CheckState.CHECKING) { Text("업데이트 확인") }
                if (st.state == CheckState.AVAILABLE) Button(onClick = vm::openUpdateDialog) { Text("지금 업데이트") }
                st.latest?.releaseUrl?.let { url -> OutlinedButton(onClick = { openInAppBrowser(context, url) }) { Text("릴리스 페이지") } }
            }
            SectionCard(title = "데이터 출처") {
                Text(
                    "금융감독원 전자공시시스템(DART) · 한국거래소 KIND/KRX · 키움증권 REST API · 네이버 금융 · 공공데이터포털(금융위원회, 한국예탁결제원).\n" +
                        "제공 정보는 지연되거나 오류가 있을 수 있으며, 투자 판단의 책임은 이용자에게 있습니다.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}
