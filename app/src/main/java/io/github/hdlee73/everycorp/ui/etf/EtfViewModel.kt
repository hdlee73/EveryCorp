package io.github.hdlee73.everycorp.ui.etf

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.hdlee73.everycorp.data.api.dto.ChartDto
import io.github.hdlee73.everycorp.data.api.dto.EtfDetailDto
import io.github.hdlee73.everycorp.data.api.dto.EtfHoldingDto
import io.github.hdlee73.everycorp.data.api.dto.EtfPortfolioDto
import io.github.hdlee73.everycorp.data.api.dto.EtfRelationsDto
import io.github.hdlee73.everycorp.data.repo.CorpRepository
import io.github.hdlee73.everycorp.ui.common.UiState
import io.github.hdlee73.everycorp.ui.common.toUiState
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** 구성종목 표 필터. */
enum class HoldingFilter(val label: String) { All("전체"), Stock("주식"), Etf("ETF"), Other("채권·현금·파생"), Unmatched("미매칭") }

fun filterHoldings(rows: List<EtfHoldingDto>, f: HoldingFilter): List<EtfHoldingDto> = when (f) {
    HoldingFilter.All -> rows
    HoldingFilter.Stock -> rows.filter { it.kind == "STOCK" }
    HoldingFilter.Etf -> rows.filter { it.kind == "ETF" }
    HoldingFilter.Other -> rows.filter { it.kind in setOf("BOND", "CASH", "DERIVATIVE", "FOREIGN") }
    HoldingFilter.Unmatched -> rows.filter { it.kind == "UNMATCHED" }
}

class EtfViewModel(val code: String, private val repo: CorpRepository) : ViewModel() {
    var detail by mutableStateOf<UiState<EtfDetailDto>>(UiState.Idle); private set
    var chart by mutableStateOf<UiState<ChartDto>>(UiState.Idle); private set
    var portfolio by mutableStateOf<UiState<EtfPortfolioDto>>(UiState.Idle); private set
    var relations by mutableStateOf<UiState<EtfRelationsDto>>(UiState.Idle); private set
    var filter by mutableStateOf(HoldingFilter.All); private set
    private var portfolioJob: Job? = null
    private var relationsJob: Job? = null

    fun load() {
        detail = UiState.Loading
        viewModelScope.launch { detail = repo.etf(code).toUiState() }
        loadChart()
    }

    fun loadChart() {
        chart = UiState.Loading
        viewModelScope.launch { chart = repo.etfChart(code).toUiState() }
    }

    /** 탭을 처음 열 때만 부른다(이미 받았거나 받는 중이면 무시). [force]는 다시 시도 버튼용. */
    fun loadPortfolio(force: Boolean = false) {
        if (!force && (portfolio is UiState.Success || portfolioJob?.isActive == true)) return
        portfolio = UiState.Loading
        portfolioJob = viewModelScope.launch { portfolio = repo.etfPortfolio(code).toUiState() }
    }

    fun loadRelations(force: Boolean = false) {
        if (!force && (relations is UiState.Success || relationsJob?.isActive == true)) return
        relations = UiState.Loading
        relationsJob = viewModelScope.launch { relations = repo.etfRelations(code).toUiState() }
    }

    fun select(f: HoldingFilter) { filter = f }

    /** 화면이 보이는 동안 주기적으로 부르는 조용한 갱신(실패해도 이전 값을 유지). */
    suspend fun refreshQuiet() {
        repo.etf(code).onSuccess { detail = UiState.Success(it) }
    }
}
