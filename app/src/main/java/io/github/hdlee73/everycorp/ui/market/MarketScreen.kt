package io.github.hdlee73.everycorp.ui.market

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.hdlee73.everycorp.EveryCorpApp
import io.github.hdlee73.everycorp.data.api.dto.RankingItemDto
import io.github.hdlee73.everycorp.data.api.dto.RankingsDto
import io.github.hdlee73.everycorp.data.repo.CorpRepository
import io.github.hdlee73.everycorp.ui.AppViewModel
import io.github.hdlee73.everycorp.ui.common.Fmt
import io.github.hdlee73.everycorp.ui.common.PillChip
import io.github.hdlee73.everycorp.ui.common.TabHeader
import io.github.hdlee73.everycorp.ui.common.UiState
import io.github.hdlee73.everycorp.ui.common.UiStateContent
import io.github.hdlee73.everycorp.ui.common.directionColor
import io.github.hdlee73.everycorp.ui.common.openDisclosure
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

enum class RankKind(val param: String, val label: String) {
    Volume("volume", "거래량"),
    Value("value", "거래대금"),
    Rise("rise", "상승률"),
    Fall("fall", "하락률"),
    Surge("surge", "거래량급증"),
    Foreign("foreign", "외인연속"),
    Inst("inst", "외인·기관"),
}
enum class RankMarket(val param: String, val label: String) { All("all", "전체"), Kospi("kospi", "코스피"), Kosdaq("kosdaq", "코스닥") }

/** 랭킹 스크리너 상태. 30초 주기 조용한 갱신(서버도 30초 캐시). */
class MarketViewModel(private val repo: CorpRepository) : ViewModel() {
    var kind by mutableStateOf(RankKind.Volume); private set
    var market by mutableStateOf(RankMarket.All); private set
    var onlyTagged by mutableStateOf(false); private set
    var tagFilter by mutableStateOf<String?>(null); private set
    var state by mutableStateOf<UiState<RankingsDto>>(UiState.Idle); private set
    var indices by mutableStateOf<List<io.github.hdlee73.everycorp.data.api.dto.MarketIndexDto>>(emptyList()); private set
    private var job: Job? = null
    private var idxJob: Job? = null

    fun select(k: RankKind) { if (k != kind) { kind = k; load() } }
    fun select(m: RankMarket) { if (m != market) { market = m; load() } }
    fun toggleTagged() { onlyTagged = !onlyTagged }
    fun selectTag(t: String?) { tagFilter = if (tagFilter == t) null else t }

    fun load(silent: Boolean = false) {
        job?.cancel()
        if (!silent || state !is UiState.Success) state = UiState.Loading
        val k = kind; val m = market
        job = viewModelScope.launch {
            repo.rankings(k.param, m.param)
                .onSuccess { state = UiState.Success(it) }
                .onFailure { if (!silent || state !is UiState.Success) state = UiState.Error(it.message ?: "순위를 불러오지 못했습니다.") }
        }
        loadIndices()
    }

    fun loadIndices() {
        idxJob?.cancel()
        idxJob = viewModelScope.launch {
            repo.marketIndices().onSuccess { if (it.indices.isNotEmpty()) indices = it.indices }
        }
    }

    /** 화면에 보일 목록(공시 있는 종목만·태그 필터 적용). 순수 함수라 테스트한다. */
    fun visible(items: List<RankingItemDto>): List<RankingItemDto> = filterRankings(items, onlyTagged, tagFilter)
}

fun filterRankings(items: List<RankingItemDto>, onlyTagged: Boolean, tag: String?): List<RankingItemDto> =
    items.filter { (!onlyTagged || it.tags.isNotEmpty()) && (tag == null || tag in it.tags) }

@Composable
fun MarketScreen(app: AppViewModel) {
    val vm: MarketViewModel = viewModel(factory = viewModelFactory { initializer { MarketViewModel(EveryCorpApp.container.repository) } })
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(Unit) { if (vm.state is UiState.Idle) vm.load() }
    LaunchedEffect(vm.kind, vm.market) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { while (true) { delay(30_000); vm.load(silent = true) } }
    }
    val context = androidx.compose.ui.platform.LocalContext.current
    Column(Modifier.fillMaxSize()) {
        TabHeader("시장 랭킹", "거래량·수급·급증 순위에 최근 3일 DART 공시를 태그로 붙여 봅니다")
        if (vm.indices.isNotEmpty()) {
            IndexBar(vm.indices)
        }
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                RankKind.entries.forEach { k -> PillChip(k.label, onClick = { vm.select(k) }, selected = vm.kind == k) }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                RankMarket.entries.forEach { m -> PillChip(m.label, onClick = { vm.select(m) }, selected = vm.market == m) }
                PillChip("공시 있는 종목만", onClick = vm::toggleTagged, selected = vm.onlyTagged)
                PillChip("↻ 새로고침", onClick = { vm.load(silent = false) })
            }
        }
        UiStateContent(vm.state, onRetry = { vm.load() }, modifier = Modifier.padding(16.dp)) { data ->
            val tags = data.items.flatMap { it.tags }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }
            Column {
                if (tags.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    tags.forEach { (t, n) -> PillChip("#$t $n", onClick = { vm.selectTag(t) }, selected = vm.tagFilter == t) }
                }
                data.warnings.forEach { Text("⚠ $it", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error) }
                val rows = vm.visible(data.items)
                LazyColumn(Modifier.fillMaxSize()) {
                    if (rows.isEmpty()) item {
                        Text("조건에 맞는 종목이 없습니다.", Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    items(rows, key = { it.stockCode }) { r ->
                        RankRow(r, data.kind, onOpen = { r.toCorp()?.let(app::openCompany) }, onDisclosure = { d -> openDisclosure(context, d) })
                        HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    }
                    item {
                        Text(
                            "출처 키움증권 REST API(지수·순위, 30초 갱신, ETF·ETN 제외) · DART 최근 3일 공시. 투자 판단의 책임은 이용자에게 있습니다.",
                            Modifier.padding(16.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun IndexBar(indices: List<io.github.hdlee73.everycorp.data.api.dto.MarketIndexDto>) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        indices.forEach { idx ->
            val color = directionColor(idx.direction)
            Surface(
                Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
            ) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(idx.name, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                        val chg = idx.changeRate?.let { String.format(Locale.US, "%+.2f%%", it) } ?: ""
                        if (chg.isNotBlank()) Text(chg, style = MaterialTheme.typography.labelSmall, color = color, fontWeight = FontWeight.SemiBold)
                    }
                    Text(
                        idx.price?.let { String.format(Locale.US, "%,.2f", it) } ?: "-",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = color,
                    )
                    if (idx.rising != null || idx.falling != null) {
                        Text(
                            "상승 ${idx.rising ?: 0} · 하락 ${idx.falling ?: 0}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RankRow(r: RankingItemDto, kind: String, onOpen: () -> Unit, onDisclosure: (io.github.hdlee73.everycorp.data.api.dto.DisclosureDto) -> Unit) {
    val color = directionColor(r.direction)
    Column(Modifier.fillMaxWidth().clickable(enabled = r.corpCode != null, onClick = onOpen).padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${r.rank}", Modifier.width(30.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Column(Modifier.weight(1f)) {
                Text(r.name.ifBlank { r.corpName }, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val sub = when (kind) {
                    "value" -> "거래대금 " + (r.tradeValue?.let { Fmt.number(it / 100) + "억" } ?: "-")
                    "surge" -> listOfNotNull(
                        r.surgeRate?.let { String.format(Locale.US, "급증 %+.1f%%", it) },
                        "거래량 " + Fmt.number(r.volume),
                    ).joinToString(" · ")
                    "foreign" -> listOfNotNull(
                        r.contDays?.let { "${it}일 연속" },
                        r.netQty?.let { "순매수 " + (if (it > 0) "+" else "") + Fmt.number(it) },
                    ).ifEmpty { listOf("거래량 " + Fmt.number(r.volume)) }.joinToString(" · ")
                    "inst" -> listOfNotNull(
                        r.netQty?.let { "순매수 " + (if (it > 0) "+" else "") + Fmt.number(it) },
                        "거래량 " + Fmt.number(r.volume),
                    ).joinToString(" · ")
                    else -> "거래량 " + Fmt.number(r.volume)
                }
                Text("${r.stockCode} · $sub", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(Fmt.price(r.price), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = color)
                Text(r.changeRate?.let { String.format(Locale.US, "%+.2f%%", it) } ?: "-", style = MaterialTheme.typography.labelMedium, color = color)
            }
        }
        if (r.tags.isNotEmpty()) {
            Row(Modifier.padding(start = 30.dp, top = 6.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                r.tags.forEach { t ->
                    Surface(shape = RoundedCornerShape(6.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                        Text("#$t", Modifier.padding(horizontal = 6.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
                    }
                }
            }
            r.disclosures.take(2).forEach { d ->
                Text(
                    "${Fmt.date(d.date).drop(5)} ${d.title}", Modifier.padding(start = 30.dp, top = 2.dp).clickable { onDisclosure(d) },
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
