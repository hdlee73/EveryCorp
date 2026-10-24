package io.github.hdlee73.everycorp.ui.alerts

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Row
import io.github.hdlee73.everycorp.ui.common.PillChip
import io.github.hdlee73.everycorp.ui.common.openInAppBrowser
import io.github.hdlee73.everycorp.ui.theme.StockColors
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.hdlee73.everycorp.data.api.dto.CorpItemDto
import io.github.hdlee73.everycorp.data.api.dto.DisclosureDto
import io.github.hdlee73.everycorp.ui.AppViewModel
import io.github.hdlee73.everycorp.ui.common.EmptyBox
import io.github.hdlee73.everycorp.ui.common.Fmt
import io.github.hdlee73.everycorp.ui.common.SectionCard
import io.github.hdlee73.everycorp.ui.common.TabHeader
import io.github.hdlee73.everycorp.ui.common.UiState
import io.github.hdlee73.everycorp.ui.common.UiStateContent
import io.github.hdlee73.everycorp.ui.common.openDisclosure
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

private data class AlertItem(val corp: CorpItemDto, val disclosure: DisclosureDto)

/** 지분 변동 목록의 한 줄. [kind]는 "대량보유" 또는 "임원·주요주주". */
private data class StakeItem(val corp: CorpItemDto, val kind: String, val date: String, val rceptNo: String, val text: String, val change: Long?)

private const val MAX_CORPS = 20

/** 관심기업의 최근 DART 공시를 날짜순으로 모아 보여 준다(새 공시 푸시 알림은 앱 정보에서 켜고 끈다). */
@Composable
fun AlertsScreen(vm: AppViewModel) {
    val context = LocalContext.current
    var tab by remember { mutableStateOf(0) } // 0 공시, 1 지분 변동
    val favorites = vm.favorites.take(MAX_CORPS)
    val state by produceState<UiState<List<AlertItem>>>(UiState.Loading, favorites) {
        value = UiState.Loading
        val all = coroutineScope {
            favorites.map { corp -> async { vm.repository.recentDisclosures(corp.corpCode).getOrDefault(emptyList()).map { AlertItem(corp, it) } } }.awaitAll()
        }.flatten()
        value = UiState.Success(all.sortedWith(compareByDescending<AlertItem> { it.disclosure.date }.thenByDescending { it.disclosure.time ?: "" }).take(60))
    }
    val stakes by produceState<UiState<List<StakeItem>>>(UiState.Idle, favorites, tab) {
        if (tab != 1) return@produceState
        value = UiState.Loading
        val all = coroutineScope {
            favorites.map { corp ->
                async {
                    val r = vm.repository.stakeChanges(corp.corpCode).getOrNull()
                    r?.major.orEmpty().map { StakeItem(corp, "대량보유", it.date, it.rceptNo, StakeText.major(it), it.sharesChange) } +
                        r?.insiders.orEmpty().map { StakeItem(corp, "임원·주요주주", it.date, it.rceptNo, StakeText.insider(it), it.sharesChange) }
                }
            }.awaitAll()
        }.flatten()
        value = UiState.Success(all.sortedByDescending { it.date }.take(80))
    }
    Column(Modifier.fillMaxSize()) {
        TabHeader("알림", "관심기업의 최근 공시와 지분 변동")
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PillChip("공시", onClick = { tab = 0 }, selected = tab == 0)
            PillChip("지분 변동", onClick = { tab = 1 }, selected = tab == 1)
        }
        if (tab == 1) {
            StakeList(favorites.isEmpty(), stakes, context)
            return@Column
        }
        if (favorites.isEmpty()) {
            EmptyBox("관심기업이 없습니다. 기업 화면에서 ★를 누르면 새 공시가 여기에 모입니다.", Modifier.padding(horizontal = 16.dp, vertical = 16.dp))
        } else UiStateContent(state, onRetry = null, modifier = Modifier.padding(horizontal = 16.dp)) { list ->
            if (list.isEmpty()) EmptyBox("최근 공시가 없습니다.")
            else LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(top = 12.dp, bottom = 24.dp)) {
                items(list, key = { it.corp.corpCode + it.disclosure.id }) { a ->
                    SectionCard(Modifier.clickable { openDisclosure(context, a.disclosure) }) {
                        Text("${a.corp.corpName} · ${Fmt.date(a.disclosure.date)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(a.disclosure.title, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        }
    }
}

@Composable
private fun StakeList(noFavorites: Boolean, state: UiState<List<StakeItem>>, context: android.content.Context) {
    if (noFavorites) {
        EmptyBox("관심기업이 없습니다. 기업 화면에서 ★를 누르면 대량보유(5%)와 임원·주요주주 지분 변동이 여기에 모입니다.", Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        return
    }
    UiStateContent(state, onRetry = null, modifier = Modifier.padding(horizontal = 16.dp)) { list ->
        if (list.isEmpty()) EmptyBox("최근 지분 변동 공시가 없습니다.")
        else LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(top = 4.dp, bottom = 24.dp)) {
            itemsIndexed(list, key = { i, s -> "$i${s.corp.corpCode}${s.rceptNo}" }) { _, s ->
                SectionCard(Modifier.clickable { if (s.rceptNo.isNotBlank()) openInAppBrowser(context, "https://dart.fss.or.kr/dsaf001/main.do?rcpNo=${s.rceptNo}") }) {
                    Text("${s.corp.corpName} · ${s.kind} · ${StakeText.date(s.date)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        s.text, style = MaterialTheme.typography.bodyLarge,
                        color = when { (s.change ?: 0L) > 0 -> StockColors.up; (s.change ?: 0L) < 0 -> StockColors.down; else -> MaterialTheme.colorScheme.onSurface },
                    )
                }
            }
        }
    }
}
