package io.github.hdlee73.everycorp.ui.company

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import io.github.hdlee73.everycorp.BuildConfig
import io.github.hdlee73.everycorp.data.pdf.PdfSupport
import io.github.hdlee73.everycorp.data.report.SummaryPdfInput
import io.github.hdlee73.everycorp.data.report.SummaryChartImage
import io.github.hdlee73.everycorp.data.report.SummaryHwpxWriter
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
import io.github.hdlee73.everycorp.data.api.dto.CandlesDto
import io.github.hdlee73.everycorp.data.api.dto.FlowsDto
import io.github.hdlee73.everycorp.data.api.dto.OrderbookDto
import io.github.hdlee73.everycorp.data.api.dto.RealtimeMessageDto
import io.github.hdlee73.everycorp.data.realtime.RealtimeClient
import io.github.hdlee73.everycorp.ui.common.MarkerKind
import io.github.hdlee73.everycorp.ui.common.Overlay
import io.github.hdlee73.everycorp.ui.common.SubIndicator
import io.github.hdlee73.everycorp.ui.common.toKindMarkers
import io.github.hdlee73.everycorp.ui.common.toPeriodicMarkers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
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
    private val realtime: RealtimeClient? = null,
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
    /** 마지막으로 만든 요약 문서 형식("PDF" | "HWPX"). 완료 알림 문구에 쓴다. */
    var summaryFormat by mutableStateOf("PDF"); private set

    /** [hwpx]가 true 면 한글(HWPX) 문서로 [target]에 저장한다(저장 위치는 반드시 고른 곳). */
    fun exportSummaryPdf(context: Context, target: Uri? = null, sections: Set<SummarySection> = SummarySection.entries.toSet(), hwpx: Boolean = false) {
        if (summaryPdf is PdfUi.Downloading) return
        summaryPdf = PdfUi.Downloading(false)
        summaryFormat = if (hwpx) "HWPX" else "PDF"
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
                        out.use {
                            if (hwpx) SummaryHwpxWriter { name -> context.assets.open("hwpx/$name").use { s -> s.readBytes() } }.write(input, it, input.chart?.let { c -> SummaryChartImage.render(c) })
                            else SummaryPdfWriter().write(input, it)
                        }
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
            CompanyTab.Stock -> if (corp.isListed) {
                if (chart is UiState.Idle) loadChart()
                if (candles is UiState.Idle) loadCandles(candlePeriod)
                if (orderbook is UiState.Idle) loadOrderbook()
            }
            CompanyTab.Flows -> if (flows is UiState.Idle && corp.isListed) loadFlows()
            CompanyTab.Debt -> if (debt is UiState.Idle) loadDebt()
            CompanyTab.News -> if (news is UiState.Idle) loadNews()
        }
    }

    fun loadOverview() { overview = UiState.Loading; viewModelScope.launch { overview = repo.overview(corp.corpCode).toUiState() } }
    fun loadShareholders() { shareholders = UiState.Loading; viewModelScope.launch { shareholders = repo.shareholders(corp.corpCode).toUiState() } }
    /** [refresh]가 true 면 서버 저장분을 건너뛰고 DART에서 다시 읽는다. */
    fun loadReports(refresh: Boolean = false) { reports = UiState.Loading; viewModelScope.launch { reports = repo.auditReports(corp.corpCode, refresh = refresh).toUiState() } }
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
            // 캔들 차트용: DART 주요사항 + DART 정기보고서 + KIND 거래소 공시(폰에서 직접)
            val periodic = repo.disclosures(corp.corpCode, DisclosurePeriod.Y1, DisclosureSource.Dart, category = DisclosureCategory.Periodic).getOrNull()?.items.orEmpty()
            val kindItems = repo.kindDisclosures(corp, DisclosurePeriod.Y1).getOrNull().orEmpty()
            candleMarkers = markers + toPeriodicMarkers(periodic) + toKindMarkers(kindItems, markers)
        }
    }

    // ───── 키움: 실시간 시세·호가, 캔들, 수급(조회 전용) ─────
    /** kiwoom(실시간) | polling(서버가 3초마다 현재가) | null(연결 안 됨 → 앱이 5초 폴링) */
    var realtimeMode by mutableStateOf<String?>(null); private set
    var orderbook by mutableStateOf<UiState<OrderbookDto>>(UiState.Idle); private set
    var candlePeriod by mutableStateOf("D"); private set
    var candles by mutableStateOf<UiState<CandlesDto>>(UiState.Idle); private set
    private val candleCache = mutableMapOf<String, CandlesDto>()
    private var candleJob: Job? = null
    var overlays by mutableStateOf(setOf(Overlay.MA)); private set
    var subIndicator by mutableStateOf<SubIndicator?>(SubIndicator.Volume); private set
    var markerKinds by mutableStateOf(MarkerKind.entries.toSet()); private set
    /** 캔들 차트에 찍을 수 있는 최근 1년 공시(종류 구분). */
    var candleMarkers by mutableStateOf<List<ChartMarker>>(emptyList()); private set
    var flows by mutableStateOf<UiState<FlowsDto>>(UiState.Idle); private set

    fun realtimeFlow(): Flow<RealtimeMessageDto> {
        val code = corp.stockCode?.takeIf { it.length == 6 } ?: return emptyFlow()
        return realtime?.stream(code) ?: emptyFlow()
    }

    fun onRealtime(m: RealtimeMessageDto) {
        when (m.type) {
            "status" -> realtimeMode = m.mode
            "disconnected" -> realtimeMode = null
            "trade" -> applyTrade(m)
            "orderbook" -> if (m.asks.isNotEmpty() || m.bids.isNotEmpty()) {
                orderbook = UiState.Success(OrderbookDto(corp.stockCode.orEmpty(), "KIWOOM", m.asks, m.bids, m.totalAsk, m.totalBid, m.time))
            }
        }
    }

    private fun applyTrade(m: RealtimeMessageDto) {
        val price = m.price ?: return
        val prev = (quote as? UiState.Success)?.data
        val base = prev ?: QuoteDto(stockCode = corp.stockCode.orEmpty(), name = corp.corpName)
        quote = UiState.Success(
            base.copy(
                price = price,
                change = m.change ?: base.change,
                changeRate = m.changeRate ?: base.changeRate,
                direction = m.direction ?: base.direction,
                volume = m.accVolume ?: base.volume,
                open = m.open ?: base.open,
                high = m.high ?: base.high,
                low = m.low ?: base.low,
                source = if (realtimeMode == "kiwoom") "KIWOOM 실시간" else base.source,
                fetchedAt = java.time.LocalDateTime.now().withNano(0).toString(),
            ),
        )
        // 일봉 차트의 오늘 캔들을 체결가로 갱신(새 캔들은 만들지 않는다).
        val cs = (candles as? UiState.Success)?.data ?: return
        if (cs.period != "D") return
        val last = cs.candles.lastOrNull() ?: return
        if (last.t != java.time.LocalDate.now().toString()) return
        val upd = last.copy(c = price, h = maxOf(last.h, price), l = minOf(last.l, price), v = m.accVolume ?: last.v)
        candles = UiState.Success(cs.copy(candles = cs.candles.dropLast(1) + upd))
    }

    /** 실시간 연결이 없을 때만 앱이 직접 현재가를 묻는다(서버 캐시 5초). */
    suspend fun pollQuoteIfNeeded() {
        if (realtimeMode != null && quote is UiState.Success) return
        onQuote(repo.quote(corp.corpCode))
    }

    fun loadOrderbook() {
        orderbook = UiState.Loading
        viewModelScope.launch { orderbook = repo.orderbook(corp.corpCode).toUiState() }
    }

    fun selectPeriod(p: String) {
        if (p == candlePeriod) return
        candlePeriod = p
        loadCandles(p)
    }

    /** [silent]면 로딩 표시 없이 갱신(분봉 1분 주기). */
    fun loadCandles(p: String = candlePeriod, silent: Boolean = false) {
        candleJob?.cancel()
        val cached = candleCache[p]
        if (!silent) candles = cached?.let { UiState.Success(it) } ?: UiState.Loading
        candleJob = viewModelScope.launch {
            repo.candles(corp.corpCode, p)
                .onSuccess { candleCache[p] = it; if (candlePeriod == p) candles = UiState.Success(it) }
                .onFailure { if (candlePeriod == p && (!silent || candles !is UiState.Success)) candles = UiState.Error(it.message ?: "차트를 불러오지 못했습니다.") }
        }
    }

    fun toggleOverlay(o: Overlay) { overlays = if (o in overlays) overlays - o else overlays + o }
    fun selectSub(s: SubIndicator?) { subIndicator = s }
    fun toggleMarkerKind(k: MarkerKind) { markerKinds = if (k in markerKinds) markerKinds - k else markerKinds + k }

    fun loadFlows() {
        flows = UiState.Loading
        viewModelScope.launch { flows = repo.flows(corp.corpCode).toUiState() }
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
