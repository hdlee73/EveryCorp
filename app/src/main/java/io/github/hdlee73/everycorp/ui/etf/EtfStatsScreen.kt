package io.github.hdlee73.everycorp.ui.etf

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.hdlee73.everycorp.EveryCorpApp
import io.github.hdlee73.everycorp.data.api.dto.EtfItemDto
import io.github.hdlee73.everycorp.data.api.dto.EtfManagerRowDto
import io.github.hdlee73.everycorp.data.api.dto.EtfRankItemDto
import io.github.hdlee73.everycorp.data.api.dto.EtfRankingDto
import io.github.hdlee73.everycorp.data.api.dto.EtfStatsCoverageDto
import io.github.hdlee73.everycorp.data.api.dto.EtfStatsOverviewDto
import io.github.hdlee73.everycorp.data.repo.CorpRepository
import io.github.hdlee73.everycorp.ui.AppViewModel
import io.github.hdlee73.everycorp.ui.common.ChartSeries
import io.github.hdlee73.everycorp.ui.common.Dest
import io.github.hdlee73.everycorp.ui.common.EmptyBox
import io.github.hdlee73.everycorp.ui.common.EtfStatsTab
import io.github.hdlee73.everycorp.ui.common.EtfTab
import io.github.hdlee73.everycorp.ui.common.Fmt
import io.github.hdlee73.everycorp.ui.common.PillChip
import io.github.hdlee73.everycorp.ui.common.SectionCard
import io.github.hdlee73.everycorp.ui.common.SeriesColors
import io.github.hdlee73.everycorp.ui.common.SeriesLineChart
import io.github.hdlee73.everycorp.ui.common.StatFmt
import io.github.hdlee73.everycorp.ui.common.TabHeader
import io.github.hdlee73.everycorp.ui.common.UiState
import io.github.hdlee73.everycorp.ui.common.UiStateContent
import io.github.hdlee73.everycorp.ui.common.UnderlineTabs
import io.github.hdlee73.everycorp.ui.common.toUiState
import io.github.hdlee73.everycorp.ui.theme.StockColors
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** 통계 기간(달력일). */
enum class StatPeriod(val label: String, val days: Int) { M1("1개월", 30), M3("3개월", 90), M6("6개월", 180), Y1("1년", 365) }

/** 차트·표에 쓰는 지표. */
enum class StatMetric(val label: String) { NetAssets("순자산총액"), MarketCap("시가총액"), Count("ETF 수") }

/** 순위 정렬. [key]는 서버 sort 값. */
enum class RankSort(val label: String, val key: String) {
    NetAssets("순자산", "net_assets"),
    MarketCap("시가총액", "market_cap"),
    Change("순자산 증감", "net_assets_change"),
    ChangePct("증감률", "net_assets_change_pct"),
}

/** 전체 합계 계열(순수 함수). */
fun totalValues(o: EtfStatsOverviewDto, m: StatMetric): List<Double?> = when (m) {
    StatMetric.NetAssets -> o.total.netAssets.map { it.toDouble() }
    StatMetric.MarketCap -> o.total.marketCap.map { it.toDouble() }
    StatMetric.Count -> o.total.count.map { it.toDouble() }
}

/** 운용사 계열 (이름, 값). 서버 순서(최신 순자산 큰 순, 마지막이 '기타')를 그대로 쓴다. */
fun managerValues(o: EtfStatsOverviewDto, m: StatMetric): List<Pair<String, List<Double?>>> = o.managers.map { s ->
    s.name to when (m) {
        StatMetric.NetAssets -> s.netAssets.map { it.toDouble() }
        StatMetric.MarketCap -> s.marketCap.map { it.toDouble() }
        StatMetric.Count -> s.count.map { it.toDouble() }
    }
}

fun metricFormat(m: StatMetric): (Double) -> String = if (m == StatMetric.Count) StatFmt::count else StatFmt::won

/** 첫 값 대비 마지막 값 변화 (차이, %). 값이 둘 미만이면 null. */
fun firstLastChange(values: List<Double?>): Pair<Double, Double?>? {
    val v = values.filterNotNull()
    if (v.size < 2) return null
    val d = v.last() - v.first()
    return d to (if (v.first() != 0.0) d / v.first() * 100 else null)
}

class EtfStatsViewModel(private val repo: CorpRepository) : ViewModel() {
    var period by mutableStateOf(StatPeriod.Y1); private set
    var metric by mutableStateOf(StatMetric.NetAssets); private set
    var overview by mutableStateOf<UiState<EtfStatsOverviewDto>>(UiState.Idle); private set

    var sort by mutableStateOf(RankSort.NetAssets); private set
    var rankPeriod by mutableStateOf(StatPeriod.M1); private set
    var manager by mutableStateOf(""); private set
    var ranking by mutableStateOf<UiState<EtfRankingDto>>(UiState.Idle); private set

    private var overviewJob: Job? = null
    private var rankingJob: Job? = null

    fun loadOverview(force: Boolean = false) {
        if (!force && (overview is UiState.Success || overviewJob?.isActive == true)) return
        overviewJob?.cancel()
        overview = UiState.Loading
        val days = period.days
        overviewJob = viewModelScope.launch { overview = repo.etfStatsOverview(days).toUiState() }
    }

    fun loadRanking(force: Boolean = false) {
        if (!force && (ranking is UiState.Success || rankingJob?.isActive == true)) return
        rankingJob?.cancel()
        ranking = UiState.Loading
        val (s, p, m) = Triple(sort.key, rankPeriod.days, manager)
        rankingJob = viewModelScope.launch { ranking = repo.etfStatsRanking(s, p, m).toUiState() }
    }

    fun selectPeriod(p: StatPeriod) { if (p != period) { period = p; loadOverview(force = true) } }
    fun selectMetric(m: StatMetric) { metric = m }
    fun selectSort(s: RankSort) { if (s != sort) { sort = s; loadRanking(force = true) } }
    fun selectRankPeriod(p: StatPeriod) { if (p != rankPeriod) { rankPeriod = p; loadRanking(force = true) } }
    fun selectManager(m: String) { if (m != manager) { manager = m; loadRanking(force = true) } }
}

@Composable
fun EtfStatsScreen(app: AppViewModel, dest: Dest.EtfStats) {
    val vm: EtfStatsViewModel = viewModel(key = "etf-stats", factory = viewModelFactory { initializer { EtfStatsViewModel(EveryCorpApp.container.repository) } })
    val tab = dest.current
    LaunchedEffect(tab) {
        when (tab) {
            EtfStatsTab.Total, EtfStatsTab.Managers -> vm.loadOverview()
            EtfStatsTab.Ranking -> vm.loadRanking()
        }
    }
    Column(Modifier.fillMaxSize()) {
        TabHeader("ETF 통계", "운용사별·일자별 ETF 수, 순자산총액·시가총액 추이와 순위")
        val tabs = EtfStatsTab.entries
        UnderlineTabs(tabs.map { it.label }, tabs.indexOf(tab), onSelect = { app.selectEtfStatsTab(tabs[it]) })
        when (tab) {
            EtfStatsTab.Total -> ScrollBody { TotalTab(vm) }
            EtfStatsTab.Managers -> ScrollBody {
                ManagersTab(vm) { name -> vm.selectManager(name); app.selectEtfStatsTab(EtfStatsTab.Ranking) }
            }
            EtfStatsTab.Ranking -> RankingTab(vm) { app.openEtf(EtfItemDto(it.code, it.name), EtfTab.Stats) }
        }
    }
}

@Composable
private fun ScrollBody(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { content() }
}

@Composable
fun <T> ChipRow(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { o -> PillChip(label(o), onClick = { onSelect(o) }, selected = o == selected) }
    }
}

/** 수집 범위 안내. 과거 자료가 없거나 받는 중이면 눈에 띄게. */
@Composable
fun CoverageNote(c: EtfStatsCoverageDto, modifier: Modifier = Modifier) {
    val warn = c.days == 0 || c.sinceFirstCollect || c.backfill == "running"
    Text(
        (if (c.sinceFirstCollect) "ⓘ 오늘부터 누적: " else "ⓘ ") + c.note,
        modifier, style = MaterialTheme.typography.labelSmall,
        color = if (warn) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun TotalTab(vm: EtfStatsViewModel) {
    ChipRow(StatPeriod.entries, vm.period, { it.label }, vm::selectPeriod)
    ChipRow(StatMetric.entries, vm.metric, { it.label }, vm::selectMetric)
    UiStateContent(vm.overview, onRetry = { vm.loadOverview(force = true) }) { o ->
        CoverageNote(o.coverage)
        if (o.dates.isEmpty()) EmptyBox("아직 저장된 ETF 일별 자료가 없습니다.") else TotalBody(vm, o)
    }
}

@Composable
private fun TotalBody(vm: EtfStatsViewModel, o: EtfStatsOverviewDto) {
    run {
        val values = remember(o, vm.metric) { totalValues(o, vm.metric) }
        val fmt = metricFormat(vm.metric)
        SectionCard(title = "전체 ETF ${vm.metric.label}") {
            Text(values.lastOrNull()?.let(fmt) ?: "-", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            firstLastChange(values)?.let { (d, pct) ->
                Text(
                    "${Fmt.date(o.dates.first())} 대비 " + (if (vm.metric == StatMetric.Count) StatFmt.countSigned(Math.round(d)) else StatFmt.wonSigned(d)) + " (${StatFmt.pctSigned(pct)})",
                    style = MaterialTheme.typography.bodySmall, color = changeColor(d),
                )
            }
            SeriesLineChart(o.dates, listOf(ChartSeries(vm.metric.label, SeriesColors.at(0), values)), fmt)
        }
        DailyTable(o)
        Text(o.note, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun DailyTable(o: EtfStatsOverviewDto) {
    var all by remember(o) { mutableStateOf(false) }
    SectionCard(title = "일자별 합계") {
        Row(Modifier.fillMaxWidth()) {
            Head("날짜", 1.1f, TextAlign.Start); Head("ETF 수", 0.7f); Head("순자산", 1.1f); Head("시가총액", 1.1f)
        }
        val idx = o.dates.indices.reversed().toList()
        (if (all) idx else idx.take(30)).forEach { i ->
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                Cell(Fmt.date(o.dates[i]), 1.1f, TextAlign.Start)
                Cell(o.total.count.getOrNull(i)?.let { StatFmt.count(it.toDouble()) } ?: "-", 0.7f)
                Cell(o.total.netAssets.getOrNull(i)?.let { StatFmt.won(it.toDouble()) } ?: "-", 1.1f)
                Cell(o.total.marketCap.getOrNull(i)?.let { StatFmt.won(it.toDouble()) } ?: "-", 1.1f)
            }
        }
        if (idx.size > 30) PillChip(if (all) "최근 30일만" else "전체 ${idx.size}일 보기", onClick = { all = !all })
    }
}

@Composable
private fun ManagersTab(vm: EtfStatsViewModel, onManager: (String) -> Unit) {
    ChipRow(StatPeriod.entries, vm.period, { it.label }, vm::selectPeriod)
    ChipRow(StatMetric.entries, vm.metric, { it.label }, vm::selectMetric)
    UiStateContent(vm.overview, onRetry = { vm.loadOverview(force = true) }) { o ->
        CoverageNote(o.coverage)
        if (o.dates.isEmpty()) EmptyBox("아직 저장된 ETF 일별 자료가 없습니다.") else ManagersBody(vm, o, onManager)
    }
}

@Composable
private fun ManagersBody(vm: EtfStatsViewModel, o: EtfStatsOverviewDto, onManager: (String) -> Unit) {
    run {
        val named = remember(o, vm.metric) { managerValues(o, vm.metric) }
        val series = named.mapIndexed { i, (name, v) -> ChartSeries(name, if (name == "기타") SeriesColors.other else SeriesColors.at(i), v) }
        SectionCard(title = "운용사별 ${vm.metric.label} 추이") {
            SeriesLineChart(o.dates, series, metricFormat(vm.metric), height = 240.dp)
            Text("상위 ${SeriesColors.MAX}개 운용사(최신 순자산 기준) 외에는 '기타'로 묶었습니다.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        SectionCard(title = "운용사 현황 (${Fmt.date(o.dates.last())}, ${Fmt.date(o.dates.first())} 대비)") {
            Row(Modifier.fillMaxWidth()) {
                Head("운용사", 1.4f, TextAlign.Start); Head("ETF 수", 0.8f); Head("순자산·점유율", 1.3f)
            }
            o.managerTable.forEach { r -> ManagerRow(r) { onManager(r.name) } }
            Text("운용사를 누르면 그 운용사 ETF 순위를 봅니다.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ManagerRow(r: EtfManagerRowDto, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(r.name, Modifier.weight(1.4f), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Column(Modifier.weight(0.8f), horizontalAlignment = Alignment.End) {
            Text(StatFmt.count(r.count.toDouble()), style = MaterialTheme.typography.bodySmall)
            Text(StatFmt.countSigned(r.countChange.toLong()), style = MaterialTheme.typography.labelSmall, color = changeColor(r.countChange.toDouble()))
        }
        Column(Modifier.weight(1.3f), horizontalAlignment = Alignment.End) {
            Text(StatFmt.won(r.netAssets.toDouble()) + (r.share?.let { " · " + Fmt.percent(it) } ?: ""), style = MaterialTheme.typography.bodySmall)
            Text(StatFmt.wonSigned(r.netAssetsChange.toDouble()), style = MaterialTheme.typography.labelSmall, color = changeColor(r.netAssetsChange.toDouble()))
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
}

@Composable
private fun RankingTab(vm: EtfStatsViewModel, onOpen: (EtfRankItemDto) -> Unit) {
    val st = vm.ranking
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item(key = "filters") {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ChipRow(RankSort.entries, vm.sort, { it.label }, vm::selectSort)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("증감 기준 ", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    ChipRow(StatPeriod.entries, vm.rankPeriod, { it.label + " 전" }, vm::selectRankPeriod)
                }
                val managers = (st as? UiState.Success)?.data?.managers.orEmpty()
                if (managers.isNotEmpty() || vm.manager.isNotEmpty()) {
                    ChipRow(listOf("") + managers.ifEmpty { listOf(vm.manager) }, vm.manager, { it.ifEmpty { "전체 운용사" } }, vm::selectManager)
                }
            }
        }
        when (st) {
            is UiState.Success -> {
                val r = st.data
                item(key = "head") {
                    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        CoverageNote(r.coverage)
                        if (r.date.isNotEmpty()) Text(
                            "기준일 ${Fmt.date(r.date)}" + (if (r.baseDate.isNotEmpty()) " · 비교일 ${Fmt.date(r.baseDate)}" else " · 비교할 과거 자료 없음") + " · ${r.total}개" +
                                (if (r.items.size < r.total) " 중 상위 ${r.items.size}개" else ""),
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
                if (r.items.isEmpty()) item(key = "empty") { EmptyBox("표시할 ETF 가 없습니다.", Modifier.padding(16.dp)) }
                items(r.items, key = { it.code }) { e ->
                    RankRow(e, vm.sort) { onOpen(e) }
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                }
                item(key = "note") { Text(r.note, Modifier.padding(16.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            else -> item(key = "state") { UiStateContent(st, onRetry = { vm.loadRanking(force = true) }) {} }
        }
    }
}

@Composable
private fun RankRow(e: EtfRankItemDto, sort: RankSort, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("${e.rank}", Modifier.width(36.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
        Column(Modifier.weight(1f)) {
            Text(e.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(listOf(e.code, e.manager).filter { it.isNotBlank() }.joinToString(" · ") + if (e.isNew) " · 신규" else "", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Column(horizontalAlignment = Alignment.End) {
            val main = if (sort == RankSort.MarketCap) e.marketCap else e.netAssets
            Text(main?.let { StatFmt.won(it.toDouble()) } ?: "-", style = MaterialTheme.typography.titleSmall)
            val chg = if (sort == RankSort.MarketCap) e.marketCapChange else e.netAssetsChange
            Text(
                when {
                    e.isNew -> "신규 상장"
                    chg == null -> (if (sort == RankSort.MarketCap) "시가총액" else "순자산")
                    else -> StatFmt.wonSigned(chg.toDouble()) + (if (sort != RankSort.MarketCap) " (${StatFmt.pctSigned(e.netAssetsChangePct)})" else "")
                },
                style = MaterialTheme.typography.labelSmall, color = chg?.let { changeColor(it.toDouble()) } ?: MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 국내 관례: 증가 빨강 · 감소 파랑. */
@Composable
fun changeColor(v: Double): Color = when {
    v > 0 -> StockColors.up
    v < 0 -> StockColors.down
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
private fun RowScope.Head(text: String, weight: Float, align: TextAlign = TextAlign.End) =
    Text(text, Modifier.weight(weight), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, textAlign = align, maxLines = 1)

@Composable
private fun RowScope.Cell(text: String, weight: Float, align: TextAlign = TextAlign.End) =
    Text(text, Modifier.weight(weight), style = MaterialTheme.typography.bodySmall, textAlign = align, maxLines = 1, overflow = TextOverflow.Ellipsis)
