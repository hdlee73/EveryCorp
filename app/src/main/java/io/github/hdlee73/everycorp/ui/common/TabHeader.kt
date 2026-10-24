package io.github.hdlee73.everycorp.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.hdlee73.everycorp.ui.theme.AppColors

/** 하단 바 탭 화면의 머리말: 상태 표시줄 여백 + 큰 제목. */
@Composable
fun TabHeader(title: String, subtitle: String? = null) {
    Column {
        Spacer(Modifier.fillMaxWidth().background(AppColors.heroStart).windowInsetsTopHeight(WindowInsets.statusBars))
        LargeTitle(title = title, subtitle = subtitle)
    }
}
