package io.github.hdlee73.everycorp.ui.company

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.hdlee73.everycorp.data.api.dto.AuditReportDto
import io.github.hdlee73.everycorp.data.api.dto.AuditReportsResponse
import io.github.hdlee73.everycorp.data.api.dto.ChartDto
import io.github.hdlee73.everycorp.data.api.dto.CorpItemDto
import io.github.hdlee73.everycorp.data.api.dto.DebtSecuritiesDto
import io.github.hdlee73.everycorp.data.api.dto.DisclosureCounts
import io.github.hdlee73.everycorp.data.api.dto.DisclosureDto
import io.github.hdlee73.everycorp.data.api.dto.NewsDto
import io.github.hdlee73.everycorp.data.api.dto.OverviewDto
import io.github.hdlee73.everycorp.data.api.dto.QuoteDto
import io.github.hdlee73.everycorp.data.pdf.PdfDownloader
import io.github.hdlee73.everycorp.data.pdf.PdfEvent
import io.github.hdlee73.everycorp.data.repo.CorpRepository
import io.github.hdlee73.everycorp.data.repo.DisclosurePeriod
import io.github.hdlee73.everycorp.data.repo.DisclosureSource
import io.github.hdlee73.everycorp.ui.common.CompanyTab
import io.github.hdlee73.everycorp.ui.common.UiState
import io.github.hdlee73.everycorp.ui.common.toUiState
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

sealed interface PdfUi {
    data class Downloading(val viaProxy: Boolean) : PdfUi
    data class Done(val uri: Uri) : PdfUi
    data class Failed(val message: String) : PdfUi
}

/** 기업 하나의 5개 탭 데이터. 탭을 처음 열 때 한 번만 불러온다. */
class CompanyViewModel(
    val corp: CorpItemDto,
    private val repo: CorpRepository,
    private val pdf: PdfDownloader,
) : ViewModel() {
    var overview by mutableStateOf<UiState<OverviewDto>>(UiState.Idle); private set
    var reports by mutableStateOf<UiState<AuditReportsResponse>>(UiState.Idle); private set
    var debt by mutableStateOf<UiState<DebtSecuritiesDto>>(UiState.Idle); private set
    var chart by mutableStateOf<UiState<ChartDto>>(UiState.Idle); private set
    var quote by mutableStateOf<UiState<QuoteDto>>(UiState.Idle); private set
    var news by mutableStateOf<UiState<NewsDto>>(UiState.Idle); private set

    val pdfStates = mutableStateMapOf<String, PdfUi>()

    // 공시 목록
    var period by mutableStateOf(DisclosurePeriod.Default); private set
    var source by mutableStateOf(DisclosureSource.All); private set
    var search by mutableStateOf(""); private set
    var items by mutableStateOf<List<DisclosureDto>>(emptyList()); private set
    var counts by mutableStateOf(DisclosureCounts()); private set
    var warnings by mutableStateOf<List<String>>(emptyList()); private set
    var hasNext by mutableStateOf(false); private set
    var disclosureState by mutableStateOf<UiState<Unit>>(UiState.Idle); private set
    var loadingMore by mutableStateOf(false); private set
    private var page = 1
    private var disclosureJob: Job? = null

    fun ensure(tab: CompanyTab) {
        when (tab) {
            CompanyTab.Overview -> if (overview is UiState.Idle) loadOverview()
            CompanyTab.Reports -> if (reports is UiState.Idle) loadReports()
            CompanyTab.Disclosures -> if (disclosureState is UiState.Idle) reloadDisclosures()
            CompanyTab.Stock -> if (chart is UiState.Idle && corp.isListed) loadChart()
            CompanyTab.Debt -> if (debt is UiState.Idle) loadDebt()
            CompanyTab.News -> if (news is UiState.Idle) loadNews()
        }
    }

    fun loadOverview() { overview = UiState.Loading; viewModelScope.launch { overview = repo.overview(corp.corpCode).toUiState() } }
    fun loadReports() { reports = UiState.Loading; viewModelScope.launch { reports = repo.auditReports(corp.corpCode).toUiState() } }
    fun loadDebt() { debt = UiState.Loading; viewModelScope.launch { debt = repo.debtSecurities(corp.corpCode).toUiState() } }
    fun loadNews() { news = UiState.Loading; viewModelScope.launch { news = repo.news(corp.corpCode).toUiState() } }
    fun loadChart() { chart = UiState.Loading; viewModelScope.launch { chart = repo.chart(corp.corpCode).toUiState() } }

    /** 현재가 폴링 결과 반영. 실패해도 이전 값은 남겨 화면이 깜박이지 않게 한다. */
    fun onQuote(r: Result<QuoteDto>) {
        r.onSuccess { quote = UiState.Success(it) }
            .onFailure { if (quote !is UiState.Success) quote = UiState.Error(it.message ?: "시세를 불러오지 못했습니다.") }
    }

    fun quoteFlow() = repo.quotePolling(corp.corpCode)

    // ───── 공시 ─────
    fun changePeriod(p: DisclosurePeriod) { if (p != period) { period = p; reloadDisclosures() } }
    fun changeSource(s: DisclosureSource) { if (s != source) { source = s; reloadDisclosures() } }
    fun changeSearch(q: String) { search = q }
    fun submitSearch() = reloadDisclosures()

    fun reloadDisclosures() {
        disclosureJob?.cancel()
        page = 1
        disclosureState = UiState.Loading
        disclosureJob = viewModelScope.launch {
            repo.disclosures(corp.corpCode, period, source, query = search, page = 1)
                .onSuccess { r ->
                    items = r.items; counts = r.counts; warnings = r.warnings; hasNext = r.hasNext
                    disclosureState = UiState.Success(Unit)
                }
                .onFailure { disclosureState = UiState.Error(it.message ?: "공시를 불러오지 못했습니다.") }
        }
    }

    fun loadMore() {
        if (!hasNext || loadingMore) return
        loadingMore = true
        viewModelScope.launch {
            repo.disclosures(corp.corpCode, period, source, query = search, page = page + 1)
                .onSuccess { r -> page += 1; items = items + r.items; hasNext = r.hasNext }
            loadingMore = false
        }
    }

    // ───── PDF ─────
    fun download(report: AuditReportDto) {
        if (pdfStates[report.id] is PdfUi.Downloading) return
        pdfStates[report.id] = PdfUi.Downloading(false)
        viewModelScope.launch {
            pdf.download(report).collect { e ->
                pdfStates[report.id] = when (e) {
                    is PdfEvent.Started -> PdfUi.Downloading(e.viaProxy)
                    is PdfEvent.Done -> PdfUi.Done(e.uri)
                    is PdfEvent.Failed -> PdfUi.Failed(e.message)
                }
            }
        }
    }
}
