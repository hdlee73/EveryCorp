package io.github.hdlee73.everycorp.ui.info

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.hdlee73.everycorp.BuildConfig
import io.github.hdlee73.everycorp.EveryCorpApp
import io.github.hdlee73.everycorp.data.update.CheckState
import io.github.hdlee73.everycorp.ui.AppViewModel
import io.github.hdlee73.everycorp.ui.common.GradientTopBar
import io.github.hdlee73.everycorp.ui.common.LabelValue
import io.github.hdlee73.everycorp.ui.common.SectionCard
import io.github.hdlee73.everycorp.ui.common.openInAppBrowser
import io.github.hdlee73.everycorp.data.log.CrashLog

private const val HOMEPAGE = "https://hdlee73.github.io/Home/"

private val MANUAL = listOf(
    "1. 기업 검색" to "첫 화면에서 회사명·종목코드를 입력해 고릅니다. 상장/외감 구분이 함께 보입니다.",
    "2. 기업 화면 탭" to "기업개황 · 감사보고서(PDF) · 공시(DART+KIND) · 주가현황 · 수급 · 채권 · 뉴스. 위쪽 탭을 누르거나 뒤로가기로 직전 탭에 돌아갑니다.",
    "3. 주가현황" to "현재가는 실시간(● 실시간, 서버가 키움 실시간 시세를 중계)으로 바뀌고, 호가는 눌러서 5/10호가를 바꿉니다. 차트는 1분~월봉, 이동평균·볼린저·거래량·RSI·MACD를 칩으로 켜고 끕니다. 두 손가락으로 확대, 가로로 끌어 과거로 이동합니다. 별은 공시일(DART 주요사항·정기보고서·KIND)이며 누르면 공시 제목이 보입니다.",
    "4. 수급" to "외국인·기관·개인 순매수, 프로그램 매매, 공매도, 신용잔고를 최근 20거래일로 봅니다.",
    "5. 시장 랭킹" to "메뉴 → 시장 랭킹. 거래량·거래대금·상승률·하락률 순위에 최근 3일 DART 공시를 #태그로 붙입니다. '공시 있는 종목만'이나 태그를 눌러 거르고, 종목을 누르면 기업 화면으로 갑니다.",
    "6. 관심·알림·스크리닝" to "별표로 관심기업에 넣으면 새 공시 알림을 받을 수 있고, 스크리닝에서 감사의견 비적정·자본잠식·CB·BW 목록을 봅니다.",
    "7. 업데이트" to "앱 정보에서 업데이트를 확인하거나, 자동 확인으로 새 버전을 안내받습니다.",
)

@Composable
fun AppInfoScreen(vm: AppViewModel) {
    val context = LocalContext.current
    val st = vm.updateStatus
    Column(Modifier.fillMaxSize()) {
        GradientTopBar(title = "앱 정보", onBack = { vm.back() })
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionCard {
                Text("Every Corp 기업정보", style = MaterialTheme.typography.titleLarge)
                LabelValue("버전", BuildConfig.VERSION_NAME)
                LabelValue("만든이", "${BuildConfig.AUTHOR_NAME} with Claude")
                LabelValue("이메일", BuildConfig.AUTHOR_EMAIL)
                LabelValue("홈페이지", HOMEPAGE)
                LabelValue("소스", "github.com/${BuildConfig.GITHUB_REPO}")
                OutlinedButton(onClick = { openInAppBrowser(context, HOMEPAGE) }) { Text("홈페이지 열기") }
            }
            SectionCard(title = "사용 방법") {
                MANUAL.forEach { (title, body) ->
                    Text(title, style = MaterialTheme.typography.titleSmall)
                    Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
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
            SectionCard(title = "알림") {
                var notifyOn by remember { mutableStateOf(EveryCorpApp.container.prefs.notifyDisclosures) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("관심기업 새 공시 알림", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "관심기업에 등록한 기업의 새 DART 공시를 30분마다 확인해 알려줍니다.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = notifyOn, onCheckedChange = { notifyOn = it; EveryCorpApp.container.prefs.notifyDisclosures = it })
                }
            }
            SectionCard(title = "오류 기록") {
                var log by remember { mutableStateOf(CrashLog.read(context)) }
                Text(
                    if (log.isBlank()) "기록된 오류가 없습니다." else "앱이 비정상 종료된 기록이 있습니다. 공유해 주시면 원인을 찾는 데 도움이 됩니다. (토큰·긴 숫자는 가려서 저장)",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (log.isNotBlank()) {
                    Text(log.takeLast(1500), style = MaterialTheme.typography.labelSmall, maxLines = 12)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            val send = android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain")
                                .putExtra(android.content.Intent.EXTRA_SUBJECT, "Every Corp 오류 기록 v${BuildConfig.VERSION_NAME}")
                                .putExtra(android.content.Intent.EXTRA_TEXT, log.takeLast(60_000))
                            runCatching { context.startActivity(android.content.Intent.createChooser(send, "오류 기록 보내기")) }
                        }) { Text("공유") }
                        OutlinedButton(onClick = { CrashLog.clear(context); log = "" }) { Text("지우기") }
                    }
                }
            }
            SectionCard(title = "데이터 출처") {
                Text(
                    "금융감독원 전자공시시스템(DART) · 한국거래소 KIND/KRX · 키움증권 REST API · 네이버 금융 · 공공데이터포털(금융위원회, 한국예탁결제원).\n" +
                        "키움증권 REST API는 시세·차트·호가·수급·순위 조회에만 쓰며, 계좌·잔고·주문 정보는 조회하거나 저장하지 않습니다. 키움 앱키는 서버에만 있습니다.\n" +
                        "제공 정보는 지연되거나 오류가 있을 수 있으며, 투자 판단의 책임은 이용자에게 있습니다.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}
