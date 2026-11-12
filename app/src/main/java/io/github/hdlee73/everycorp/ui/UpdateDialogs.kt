package io.github.hdlee73.everycorp.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.DialogProperties
import io.github.hdlee73.everycorp.BuildConfig

/** 업데이트 팝업(강제 업데이트면 닫을 수 없음)과 업데이트 후 "새 소식" 팝업. */
@Composable
fun UpdateDialogs(vm: AppViewModel) {
    val st = vm.updateStatus
    if (vm.showUpdateDialog && st.latest != null) {
        val forced = st.forced
        AlertDialog(
            onDismissRequest = vm::dismissUpdateDialog,
            properties = DialogProperties(dismissOnBackPress = !forced, dismissOnClickOutside = !forced),
            title = { Text(if (forced) "필수 업데이트" else "새 버전 ${st.latest.version}") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    if (forced) Text("이 버전은 더 이상 지원되지 않습니다. 업데이트 후 이용해 주세요.\n", color = MaterialTheme.colorScheme.error)
                    Text("현재 ${BuildConfig.VERSION_NAME} → ${st.latest.version}", style = MaterialTheme.typography.labelLarge)
                    if (st.latest.notes.isNotBlank()) Text("\n" + st.latest.notes)
                    when (val i = vm.install) {
                        InstallState.Opened -> Text(
                            "\n브라우저에서 내려받기가 시작됩니다. 다 받으면 알림(또는 다운로드 폴더)의 APK 를 눌러 설치해 주세요. " +
                                "같은 서명의 정식 APK 만 기존 앱 위에 설치됩니다.",
                        )
                        is InstallState.Error -> Text("\n" + i.message, color = MaterialTheme.colorScheme.error)
                        InstallState.None -> {}
                    }
                }
            },
            confirmButton = { TextButton(onClick = vm::startInstall) { Text(if (vm.install == InstallState.Opened) "다시 내려받기" else "내려받기") } },
            dismissButton = if (forced) null else ({ TextButton(onClick = vm::dismissUpdateDialog) { Text("나중에") } }),
        )
    }
    vm.whatsNew?.let {
        AlertDialog(
            onDismissRequest = vm::dismissWhatsNew,
            title = { Text("업데이트되었습니다") },
            text = { Text("버전 $it 로 업데이트되었습니다. 변경 내용은 앱 정보 화면에서 확인할 수 있습니다.") },
            confirmButton = { TextButton(onClick = vm::dismissWhatsNew) { Text("확인") } },
        )
    }
}
