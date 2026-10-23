package io.github.hdlee73.everycorp.ui.etf

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.hdlee73.everycorp.data.api.dto.ChartDto
import io.github.hdlee73.everycorp.data.api.dto.EtfDetailDto
import io.github.hdlee73.everycorp.data.repo.CorpRepository
import io.github.hdlee73.everycorp.ui.common.UiState
import io.github.hdlee73.everycorp.ui.common.toUiState
import kotlinx.coroutines.launch

class EtfViewModel(val code: String, private val repo: CorpRepository) : ViewModel() {
    var detail by mutableStateOf<UiState<EtfDetailDto>>(UiState.Idle); private set
    var chart by mutableStateOf<UiState<ChartDto>>(UiState.Idle); private set

    fun load() {
        detail = UiState.Loading
        viewModelScope.launch { detail = repo.etf(code).toUiState() }
        loadChart()
    }

    fun loadChart() {
        chart = UiState.Loading
        viewModelScope.launch { chart = repo.etfChart(code).toUiState() }
    }

    /** 화면이 보이는 동안 주기적으로 부르는 조용한 갱신(실패해도 이전 값을 유지). */
    suspend fun refreshQuiet() {
        repo.etf(code).onSuccess { detail = UiState.Success(it) }
    }
}
