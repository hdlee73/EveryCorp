package io.github.hdlee73.everycorp.ui.alerts

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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

private const val MAX_CORPS = 20

/** 관심기업의 최근 DART 공시를 날짜순으로 모아 보여 준다(새 공시 푸시 알림은 앱 정보에서 켜고 끈다). */
@Composable
fun AlertsScreen(vm: AppViewModel) {
    val context = LocalContext.current
    val favorites = vm.favorites.take(MAX_CORPS)
    val state by produceState<UiState<List<AlertItem>>>(UiState.Loading, favorites) {
        value = UiState.Loading
        val all = coroutineScope {
            favorites.map { corp -> async { vm.repository.recentDisclosures(corp.corpCode).getOrDefault(emptyList()).map { AlertItem(corp, it) } } }.awaitAll()
        }.flatten()
        value = UiState.Success(all.sortedWith(compareByDescending<AlertItem> { it.disclosure.date }.thenByDescending { it.disclosure.time ?: "" }).take(60))
    }
    Column(Modifier.fillMaxSize()) {
        TabHeader("알림", "관심기업의 최근 공시")
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
