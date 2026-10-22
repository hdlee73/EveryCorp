package io.github.hdlee73.everycorp.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
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
        val busy = vm.install is InstallState.Downloading
        AlertDialog(
            onDismissRequest = { if (!busy) vm.dismissUpdateDialog() },
            properties = DialogProperties(dismissOnBackPress = !forced && !busy, dismissOnClickOutside = !forced && !busy),
            title = { Text(if (forced) "필수 업데이트" else "새 버전 ${st.latest.version}") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    if (forced) Text("이 버전은 더 이상 지원되지 않습니다. 업데이트 후 이용해 주세요.\n", color = MaterialTheme.colorScheme.error)
                    Text("현재 ${BuildConfig.VERSION_NAME} → ${st.latest.version}", style = MaterialTheme.typography.labelLarge)
                    if (st.latest.notes.isNotBlank()) Text("\n" + st.latest.notes)
                    when (val i = vm.install) {
                        is InstallState.Downloading -> {
                            Text("\n내려받아 검증하는 중…")
                            if (i.progress >= 0f) LinearProgressIndicator(progress = { i.progress }, modifier = Modifier.fillMaxWidth())
                            else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                        is InstallState.Error -> Text("\n" + i.message, color = MaterialTheme.colorScheme.error)
                        InstallState.NeedPermission -> Text("\n'이 출처 허용'을 켠 뒤 돌아와 다시 눌러 주세요.")
                        InstallState.None -> {}
                    }
                }
            },
            confirmButton = { TextButton(onClick = vm::startInstall, enabled = !busy) { Text("업데이트") } },
            dismissButton = if (forced) null else ({ TextButton(onClick = vm::dismissUpdateDialog, enabled = !busy) { Text("나중에") } }),
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
