package io.github.hdlee73.everycorp.ui.company

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import io.github.hdlee73.everycorp.BuildConfig
import io.github.hdlee73.everycorp.data.pdf.PdfSupport
import io.github.hdlee73.everycorp.data.report.SummaryPdfInput
import io.github.hdlee73.everycorp.data.report.SummaryPdfWriter
import io.github.hdlee73.everycorp.data.report.SummarySection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
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
import io.github.hdlee73.everycorp.data.api.dto.IntradayDto
import io.github.hdlee73.everycorp.data.api.dto.OverviewDto
import io.github.hdlee73.everycorp.data.api.dto.QuoteDto
import io.github.hdlee73.everycorp.data.api.dto.ShareholdersDto
import io.github.hdlee73.everycorp.data.pdf.PdfDownloader
import io.github.hdlee73.everycorp.data.pdf.PdfEvent
import io.github.hdlee73.everycorp.data.repo.CorpRepository
import io.github.hdlee73.everycorp.data.kind.DisclosureMerge
import io.github.hdlee73.everycorp.data.repo.DisclosureCategory
import io.github.hdlee73.everycorp.data.repo.DisclosurePeriod
import io.github.hdlee73.everycorp.data.repo.DisclosureSource
import io.github.hdlee73.everycorp.ui.common.CompanyTab
import io.github.hdlee73.everycorp.ui.common.ChartMarker
import io.github.hdlee73.everycorp.ui.common.UiState
import io.github.hdlee73.everycorp.ui.common.toMarkers
import io.github.hdlee73.everycorp.ui.common.toUiState
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

sealed interface PdfUi {
    /** 사용자가 고른 위치에 저장을 마쳤다. */
    data class Saved(val uri: Uri) : PdfUi
    data class Downloading(val viaProxy: Boolean) : PdfUi
    data class Done(val uri: Uri) : PdfUi
    data class Failed(val message: String) : PdfUi
}

/** 기업 하나의 5개 탭 데이터. 탭을 처음 열 때 한 번만 불러온다. */
/** 차트 공시 마커용으로 불러올 DART 공시 페이지 수(페이지당 50건). */
private const val MARKER_PAGES = 3

class CompanyViewModel(
    val corp: CorpItemDto,
    private val repo: CorpRepository,
    private val pdf: PdfDownloader,
) : ViewModel() {
    var overview by mutableStateOf<UiState<OverviewDto>>(UiState.Idle); private set
    var reports by mutableStateOf<UiState<AuditReportsResponse>>(UiState.Idle); private set
    var debt by mutableStateOf<UiState<DebtSecuritiesDto>>(UiState.Idle); private set
    var chart by mutableStateOf<UiState<ChartDto>>(UiState.Idle); private set
    /** 주가 차트에 찍는 최근 1년 공시(정기·주요사항·지분). 실패해도 차트는 그대로 보인다. */
    var markers by mutableStateOf<List<ChartMarker>>(emptyList()); private set
    var quote by mutableStateOf<UiState<QuoteDto>>(UiState.Idle); private set
    var shareholders by mutableStateOf<UiState<ShareholdersDto>>(UiState.Idle); private set
    var news by mutableStateOf<UiState<NewsDto>>(UiState.Idle); private set
    var intraday by mutableStateOf<UiState<IntradayDto>>(UiState.Idle); private set

    val pdfStates = mutableStateMapOf<String, PdfUi>()

    /** 개황·채권·주가현황 요약 PDF 만들기 상태. null 이면 대기. */
    var summaryPdf by mutableStateOf<PdfUi?>(null); private set

    fun clearSummaryPdf() { summaryPdf = null }

    /** 필요한 데이터를 (아직 없으면) 불러와 A4 PDF 로 만들고 캐시 폴더에 저장한다. */
    fun exportSummaryPdf(context: Context, target: Uri? = null, sections: Set<SummarySection> = SummarySection.entries.toSet()) {
        if (summaryPdf is PdfUi.Downloading) return
        summaryPdf = PdfUi.Downloading(false)
        viewModelScope.launch {
            try {
                // 고르지 않은 항목은 불러오지 않는다.
                val needOverview = SummarySection.Overview in sections || SummarySection.Financials in sections
                val ov = if (needOverview) ((overview as? UiState.Success)?.data ?: repo.overview(corp.corpCode).getOrNull()) else null
                val dt = if (SummarySection.Debt in sections) ((debt as? UiState.Success)?.data ?: repo.debtSecurities(corp.corpCode).getOrNull()) else null
                val stock = SummarySection.Stock in sections && corp.isListed
                val q = if (stock) repo.quote(corp.corpCode).getOrNull() else null
                val ch = if (stock) ((chart as? UiState.Success)?.data ?: repo.chart(corp.corpCode).getOrNull()) else null
                val ar = if (SummarySection.Audit in sections) ((reports as? UiState.Success)?.data ?: repo.auditReports(corp.corpCode).getOrNull())?.reports else null
                val input = SummaryPdfInput(
                    corpName = corp.corpName, badge = corp.badge, overview = ov, debt = dt, quote = q, chart = ch, auditReports = ar,
                    appName = "Every Corp", versionName = BuildConfig.VERSION_NAME,
                    author = "${BuildConfig.AUTHOR_NAME} (${BuildConfig.AUTHOR_EMAIL})",
                    generatedAt = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy.MM.dd HH:mm")),
                    sections = sections,
                )
                if (target != null) {  // 사용자가 고른 위치에 바로 쓴다
                    withContext(Dispatchers.IO) {
                        val out = context.contentResolver.openOutputStream(target) ?: error("선택한 위치에 저장할 수 없습니다.")
                        out.use { SummaryPdfWriter().write(input, it) }
                    }
                    summaryPdf = PdfUi.Saved(target)
                    return@launch
                }
                val uri = withContext(Dispatchers.IO) {
                    val dir = File(context.cacheDir, "reports").apply { mkdirs() }
                    val file = File(dir, PdfSupport.safeFileName("${corp.corpName}_요약", "summary"))
                    file.outputStream().use { SummaryPdfWriter().write(input, it) }
                    FileProvider.getUriForFile(context, "${context.packageName}.files", file)
                }
                summaryPdf = PdfUi.Done(uri)
            } catch (e: Exception) {
                summaryPdf = PdfUi.Failed(e.message ?: "PDF를 만들지 못했습니다.")
            }
        }
    }

    // 공시 목록: DART 는 서버, KIND 는 앱이 직접 불러와 합친다.
    var period by mutableStateOf(DisclosurePeriod.Default); private set
    var source by mutableStateOf(DisclosureSource.All); private set
    var category by mutableStateOf(DisclosureCategory.All); private set
    var search by mutableStateOf(""); private set
    var dartItems by mutableStateOf<List<DisclosureDto>>(emptyList()); private set
    private var kindAll by mutableStateOf<List<DisclosureDto>>(emptyList())
    private var kindLoadedPeriod: DisclosurePeriod? = null
    var dartCount by mutableStateOf(0); private set
    var warnings by mutableStateOf<List<String>>(emptyList()); private set
    var hasNext by mutableStateOf(false); private set
    var disclosureState by mutableStateOf<UiState<Unit>>(UiState.Idle); private set
    var loadingMore by mutableStateOf(false); private set
    private var page = 1
    private var disclosureJob: Job? = null

    private val showDart get() = source != DisclosureSource.Kind
    private val showKind get() = source != DisclosureSource.Dart && corp.isListed

    private val kindItems: List<DisclosureDto>
        get() = if (showKind && category != DisclosureCategory.Major) DisclosureMerge.filterKind(kindAll, search, category == DisclosureCategory.Periodic) else emptyList()

    /** 화면에 보이는 공시(DART + KIND, 최신순). */
    val items: List<DisclosureDto> get() = DisclosureMerge.merge(if (showDart) dartItems else emptyList(), kindItems)
    val counts: DisclosureCounts get() = DisclosureCounts(dart = dartCount, kind = kindItems.size, total = dartCount + kindItems.size)

    fun ensure(tab: CompanyTab) {
        when (tab) {
            CompanyTab.Overview -> { if (overview is UiState.Idle) loadOverview(); if (shareholders is UiState.Idle) loadShareholders() }
            CompanyTab.Reports -> if (reports is UiState.Idle) loadReports()
            CompanyTab.Disclosures -> if (disclosureState is UiState.Idle) reloadDisclosures()
            CompanyTab.Stock -> if (chart is UiState.Idle && corp.isListed) loadChart()
            CompanyTab.Debt -> if (debt is UiState.Idle) loadDebt()
            CompanyTab.News -> if (news is UiState.Idle) loadNews()
        }
    }

    fun loadOverview() { overview = UiState.Loading; viewModelScope.launch { overview = repo.overview(corp.corpCode).toUiState() } }
    fun loadShareholders() { shareholders = UiState.Loading; viewModelScope.launch { shareholders = repo.shareholders(corp.corpCode).toUiState() } }
    fun loadReports() { reports = UiState.Loading; viewModelScope.launch { reports = repo.auditReports(corp.corpCode).toUiState() } }
    fun loadDebt() { debt = UiState.Loading; viewModelScope.launch { debt = repo.debtSecurities(corp.corpCode).toUiState() } }
    fun loadNews() { news = UiState.Loading; viewModelScope.launch { news = repo.news(corp.corpCode).toUiState() } }
    /** 당일 분봉. 처음엔 로딩 표시, 이후 갱신은 조용히(실패해도 이전 그래프 유지). */
    suspend fun refreshIntraday() {
        if (intraday is UiState.Idle) intraday = UiState.Loading
        repo.intraday(corp.corpCode)
            .onSuccess { intraday = UiState.Success(it) }
            .onFailure { if (intraday !is UiState.Success) intraday = UiState.Error(it.message ?: "당일 추이를 불러오지 못했습니다.") }
    }

    fun loadChart() {
        chart = UiState.Loading
        viewModelScope.launch { chart = repo.chart(corp.corpCode).toUiState() }
        viewModelScope.launch {
            val all = mutableListOf<DisclosureDto>()
            for (p in 1..MARKER_PAGES) {
                val r = repo.disclosures(corp.corpCode, DisclosurePeriod.Y1, DisclosureSource.Dart, category = DisclosureCategory.Major, page = p).getOrNull() ?: break
                all += r.items
                if (!r.hasNext) break
            }
            markers = toMarkers(all)
        }
    }

    /** 현재가 폴링 결과 반영. 실패해도 이전 값은 남겨 화면이 깜박이지 않게 한다. */
    fun onQuote(r: Result<QuoteDto>) {
        r.onSuccess { quote = UiState.Success(it) }
            .onFailure { if (quote !is UiState.Success) quote = UiState.Error(it.message ?: "시세를 불러오지 못했습니다.") }
    }

    fun quoteFlow() = repo.quotePolling(corp.corpCode)

    // ───── 공시 ─────
    fun changePeriod(p: DisclosurePeriod) { if (p != period) { period = p; reloadDisclosures() } }
    fun changeSource(s: DisclosureSource) { if (s != source) { source = s; reloadDisclosures() } }
    fun changeCategory(c: DisclosureCategory) { if (c != category) { category = c; reloadDisclosures() } }
    fun changeSearch(q: String) { search = q }
    fun submitSearch() = reloadDisclosures()

    fun reloadDisclosures() {
        disclosureJob?.cancel()
        page = 1
        disclosureState = UiState.Loading
        disclosureJob = viewModelScope.launch {
            val dart = if (showDart) async { repo.disclosures(corp.corpCode, period, DisclosureSource.Dart, query = search, category = category, page = 1) } else null
            val kindNeeded = showKind && kindLoadedPeriod != period
            val kind = if (kindNeeded) async { repo.kindDisclosures(corp, period) } else null

            val warn = mutableListOf<String>()
            val dartResult = dart?.await()
            dartResult?.onSuccess { r -> dartItems = r.items; dartCount = r.counts.dart; hasNext = r.hasNext; warn += r.warnings.filterNot { it.contains("KIND") } }
            if (dart == null) { dartItems = emptyList(); dartCount = 0; hasNext = false }
            kind?.await()?.onSuccess { kindAll = it; kindLoadedPeriod = period }
                ?.onFailure { kindAll = emptyList(); kindLoadedPeriod = null; warn += (it.message ?: "KIND 공시를 불러오지 못했습니다.") }
            warnings = warn
            disclosureState = when {
                dartResult != null && dartResult.isFailure && (!showKind || kindAll.isEmpty()) ->
                    UiState.Error(dartResult.exceptionOrNull()?.message ?: "공시를 불러오지 못했습니다.")
                else -> UiState.Success(Unit)
            }
        }
    }

    fun loadMore() {
        if (!hasNext || loadingMore || !showDart) return
        loadingMore = true
        viewModelScope.launch {
            repo.disclosures(corp.corpCode, period, DisclosureSource.Dart, query = search, category = category, page = page + 1)
                .onSuccess { r -> page += 1; dartItems = dartItems + r.items; hasNext = r.hasNext }
            loadingMore = false
        }
    }

    // ───── PDF ─────
    /** [target]이 있으면 받은 PDF를 사용자가 고른 위치로 복사하고 임시 파일은 지운다. */
    fun download(report: AuditReportDto, target: Uri? = null, resolver: android.content.ContentResolver? = null) {
        if (pdfStates[report.id] is PdfUi.Downloading) return
        pdfStates[report.id] = PdfUi.Downloading(false)
        viewModelScope.launch {
            pdf.download(report).collect { e ->
                pdfStates[report.id] = when (e) {
                    is PdfEvent.Started -> PdfUi.Downloading(e.viaProxy)
                    is PdfEvent.Done -> if (target != null && resolver != null) {
                        runCatching {
                            withContext(Dispatchers.IO) {
                                resolver.openInputStream(e.uri)!!.use { input -> resolver.openOutputStream(target)!!.use { input.copyTo(it) } }
                            }
                            pdf.discard(e.downloadId)
                            PdfUi.Done(target)
                        }.getOrElse { PdfUi.Failed("선택한 위치에 저장하지 못했습니다. (${it.message ?: "오류"})") }
                    } else PdfUi.Done(e.uri)
                    is PdfEvent.Failed -> PdfUi.Failed(e.message)
                }
            }
        }
    }
}
