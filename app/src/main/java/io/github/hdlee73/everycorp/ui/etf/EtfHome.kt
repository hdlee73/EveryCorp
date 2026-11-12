package io.github.hdlee73.everycorp.ui.etf

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import io.github.hdlee73.everycorp.data.api.dto.EtfOverlapDto
import io.github.hdlee73.everycorp.data.repo.CorpRepository
import io.github.hdlee73.everycorp.ui.AppViewModel
import io.github.hdlee73.everycorp.ui.common.Dest
import io.github.hdlee73.everycorp.ui.common.EmptyBox
import io.github.hdlee73.everycorp.ui.common.Fmt
import io.github.hdlee73.everycorp.ui.common.GradientTopBar
import io.github.hdlee73.everycorp.ui.common.PillChip
import io.github.hdlee73.everycorp.ui.common.SearchPill
import io.github.hdlee73.everycorp.ui.common.SectionCard
import io.github.hdlee73.everycorp.ui.common.TabHeader
import io.github.hdlee73.everycorp.ui.common.UiState
import io.github.hdlee73.everycorp.ui.common.UiStateContent
import io.github.hdlee73.everycorp.ui.common.toUiState
import io.github.hdlee73.everycorp.ui.theme.HeroPalettes
import io.github.hdlee73.everycorp.ui.theme.LocalHeroPalette
import androidx.compose.runtime.CompositionLocalProvider
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

/** ETF 메뉴 첫 화면 상태: 검색(입력 멈춘 뒤 호출)과 '두 ETF 비교' 고르기. */
class EtfHomeViewModel(private val repo: CorpRepository) : ViewModel() {
    var query by mutableStateOf(""); private set
    var results by mutableStateOf<UiState<List<EtfItemDto>>>(UiState.Idle); private set
    var compareMode by mutableStateOf(false); private set
    var first by mutableStateOf<EtfItemDto?>(null); private set
    private var job: Job? = null

    fun onQuery(q: String) {
        query = q
        job?.cancel()
        if (q.isBlank()) { results = UiState.Idle; return }
        results = UiState.Loading
        job = viewModelScope.launch {
            delay(250)
            results = repo.searchEtfs(q).toUiState()
        }
    }

    fun toggleCompare() { compareMode = !compareMode; first = null }

    /** 비교 모드에서 고른 ETF. 두 번째를 고르면 (첫째, 둘째)를 돌려주고 고르기를 처음으로 되돌린다. */
    fun pick(item: EtfItemDto): Pair<EtfItemDto, EtfItemDto>? {
        val (nextFirst, pair) = comparePick(first, item)
        first = nextFirst
        return pair
    }
}

/** 비교 고르기 한 단계(순수 함수): (다음 '첫 번째' 선택, 완성된 쌍). 같은 것을 다시 누르면 선택 취소. */
fun comparePick(first: EtfItemDto?, item: EtfItemDto): Pair<EtfItemDto?, Pair<EtfItemDto, EtfItemDto>?> = when {
    first == null -> item to null
    first.code == item.code -> null to null
    else -> null to (first to item)
}

@Composable
fun EtfHomeScreen(app: AppViewModel) {
    val vm: EtfHomeViewModel = viewModel(key = "etf-home", factory = viewModelFactory { initializer { EtfHomeViewModel(EveryCorpApp.container.repository) } })
    Column(Modifier.fillMaxSize()) {
        TabHeader("ETF", "순자산·괴리율, 구성종목(PDF), 자사 ETF 편입·순환투자·중복도")
        SearchPill(vm.query, vm::onQuery, placeholder = "ETF 이름·코드·기초지수", modifier = Modifier.padding(horizontal = 16.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PillChip(if (vm.compareMode) "비교 고르기 끝내기" else "두 ETF 비교", onClick = vm::toggleCompare, selected = vm.compareMode)
            if (vm.compareMode) {
                Text(
                    vm.first?.let { "① ${it.name} → 두 번째 ETF 를 고르세요" } ?: "첫 번째 ETF 를 고르세요",
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        when (val st = vm.results) {
            UiState.Idle -> Text(
                "ETF 를 검색해 고르면 기초자료 · 구성종목(PDF) · ETF 관련성 탭을 볼 수 있습니다.\n'두 ETF 비교'를 켜고 두 개를 차례로 고르면 구성종목 중복도를 계산합니다.",
                Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            else -> UiStateContent(st, onRetry = { vm.onQuery(vm.query) }) { list ->
                if (list.isEmpty()) EmptyBox("검색 결과가 없습니다. 이름이나 코드를 다시 확인해 주세요.", Modifier.padding(16.dp))
                else LazyColumn(Modifier.fillMaxSize()) {
                    items(list, key = { it.code }) { e ->
                        val picked = vm.compareMode && vm.first?.code == e.code
                        EtfListRow(e, picked) {
                            if (!vm.compareMode) app.openEtf(e)
                            else vm.pick(e)?.let { (a, b) -> app.openEtfOverlap(a, b) }
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    }
                }
            }
        }
    }
}

@Composable
private fun EtfListRow(e: EtfItemDto, picked: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                (if (picked) "① " else "") + e.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = if (picked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
            Text(listOf(e.code, e.indexName).filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(Fmt.price(e.price), style = MaterialTheme.typography.titleSmall)
            Text(e.nav?.let { String.format(Locale.US, "NAV %,.0f", it) } ?: "", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ───────── 두 ETF 중복도 ─────────

class EtfOverlapViewModel(private val a: String, private val b: String, private val repo: CorpRepository) : ViewModel() {
    var state by mutableStateOf<UiState<EtfOverlapDto>>(UiState.Idle); private set

    fun load() {
        state = UiState.Loading
        viewModelScope.launch { state = repo.etfOverlap(a, b).toUiState() }
    }
}

@Composable
fun EtfOverlapScreen(app: AppViewModel, dest: Dest.EtfOverlap) {
    val vm: EtfOverlapViewModel = viewModel(
        key = "etf-overlap-${dest.a.code}-${dest.b.code}",
        factory = viewModelFactory { initializer { EtfOverlapViewModel(dest.a.code, dest.b.code, EveryCorpApp.container.repository) } },
    )
    LaunchedEffect(dest.a.code, dest.b.code) { if (vm.state is UiState.Idle) vm.load() }
    CompositionLocalProvider(LocalHeroPalette provides HeroPalettes.Teal) {
        Column(Modifier.fillMaxSize().navigationBarsPadding()) {
            GradientTopBar(title = "ETF 구성 중복도", onBack = { app.back() }, badge = "ETF")
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                UiStateContent(vm.state, onRetry = vm::load) { o ->
                    SectionCard(title = "중복도") {
                        Text(EtfFmt.weight(o.overlapWeight), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        Text("공통 ${o.commonCount}종목 · A ${o.countA}종목 · B ${o.countB}종목", style = MaterialTheme.typography.bodySmall)
                        Text("A ${o.a.name} (${o.a.code}, ${o.a.date})", Modifier.clickable { app.openEtf(EtfItemDto(o.a.code, o.a.name)) }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                        Text("B ${o.b.name} (${o.b.code}, ${o.b.date})", Modifier.clickable { app.openEtf(EtfItemDto(o.b.code, o.b.name)) }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                        if (o.a.partial || o.b.partial) Text("한쪽 이상이 상위 구성종목만 받은 자료라 실제 중복도와 다를 수 있습니다.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                        Text(o.note, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    SectionCard(title = "공통 종목") {
                        Row(Modifier.fillMaxWidth()) {
                            Head("종목", 1.6f, TextAlign.Start); Head("A 비중", 0.7f); Head("B 비중", 0.7f)
                        }
                        if (o.common.isEmpty()) Text("겹치는 종목이 없습니다.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        o.common.take(200).forEach { c ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                                Cell(c.name, 1.6f, TextAlign.Start); Cell(EtfFmt.weight(c.weightA), 0.7f); Cell(EtfFmt.weight(c.weightB), 0.7f)
                            }
                        }
                        if (o.common.size > 200) Text("상위 200종목만 표시했습니다.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun RowScope.Head(text: String, weight: Float, align: TextAlign = TextAlign.End) =
    Text(text, Modifier.weight(weight), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, textAlign = align, maxLines = 1)

@Composable
private fun RowScope.Cell(text: String, weight: Float, align: TextAlign = TextAlign.End) =
    Text(text, Modifier.weight(weight), style = MaterialTheme.typography.bodySmall, textAlign = align, maxLines = 1, overflow = TextOverflow.Ellipsis)
