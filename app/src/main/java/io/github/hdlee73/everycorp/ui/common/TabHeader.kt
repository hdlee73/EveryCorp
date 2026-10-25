package io.github.hdlee73.everycorp.ui.common

import androidx.compose.runtime.Composable

/** 메뉴 서랍 화면의 머리말: 큰 제목(상태 표시줄 여백은 위 메뉴 바가 맡는다). */
@Composable
fun TabHeader(title: String, subtitle: String? = null) {
    LargeTitle(title = title, subtitle = subtitle)
}
