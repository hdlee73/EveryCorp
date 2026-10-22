package io.github.hdlee73.everycorp.ui.common

sealed interface UiState<out T> {
    data object Idle : UiState<Nothing>
    data object Loading : UiState<Nothing>
    data class Success<T>(val data: T) : UiState<T>
    data class Error(val message: String) : UiState<Nothing>
}

fun <T> Result<T>.toUiState(): UiState<T> =
    fold(onSuccess = { UiState.Success(it) }, onFailure = { UiState.Error(it.message ?: "오류가 발생했습니다.") })
