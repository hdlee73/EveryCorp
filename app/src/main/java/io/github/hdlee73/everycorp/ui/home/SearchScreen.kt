package io.github.hdlee73.everycorp.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.hdlee73.everycorp.data.api.dto.CorpItemDto
import io.github.hdlee73.everycorp.data.api.dto.EtfItemDto
import io.github.hdlee73.everycorp.ui.AppViewModel
import io.github.hdlee73.everycorp.ui.common.Badge
import io.github.hdlee73.everycorp.ui.common.EmptyBox
import io.github.hdlee73.everycorp.ui.common.InitialAvatar
import io.github.hdlee73.everycorp.ui.common.LargeTitle
import io.github.hdlee73.everycorp.ui.common.PillChip
import io.github.hdlee73.everycorp.ui.common.SearchPill
import io.github.hdlee73.everycorp.ui.common.SectionCard
import io.github.hdlee73.everycorp.ui.common.SectionLabel
import io.github.hdlee73.everycorp.ui.common.UiStateContent
import io.github.hdlee73.everycorp.ui.theme.AppColors

@Composable
fun SearchScreen(vm: AppViewModel) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(vm.searchFocusTick) { if (vm.searchFocusTick > 0) runCatching { focus.requestFocus() } }
    Column(Modifier.fillMaxSize().navigationBarsPadding()) {
        Spacer(Modifier.fillMaxWidth().background(AppColors.heroStart).windowInsetsTopHeight(WindowInsets.statusBars))
        LargeTitle(
            title = "Every Corp",
            subtitle = "기업개황 · 감사보고서 · 공시 · 주가 · 채권을 한곳에서",
            trailing = { IconButton(onClick = vm::openInfo) { Icon(Icons.Filled.Info, contentDescription = "앱 정보") } },
        )
        SearchPill(
            value = vm.query, onValueChange = vm::onQuery, placeholder = "기업명 또는 초성 (예: 삼성전자, ㅅㅅㅈㅈ)",
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp),
            focusRequester = focus,
        )
        // 바로가기는 사용자가 ★로 저장한 관심기업만 보여 준다(없으면 칩 줄 자체를 숨긴다).
        if (vm.favorites.isNotEmpty()) Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) { vm.favorites.forEach { c -> PillChip("★ " + c.corpName, onClick = { vm.openCompany(c) }) } }
        else Spacer(Modifier.padding(top = 6.dp))
        if (vm.query.isBlank()) HomeLists(vm) else SearchResults(vm)
    }
}

@Composable
private fun SearchResults(vm: AppViewModel) {
    UiStateContent(vm.results, onRetry = { vm.onQuery(vm.query) }, modifier = Modifier.padding(horizontal = 16.dp)) { list ->
        if (list.isEmpty() && vm.etfResults.isEmpty()) EmptyBox("검색 결과가 없습니다. 기업명·ETF명을 다시 확인해 주세요.", Modifier.padding(horizontal = 16.dp))
        else LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp)) {
            items(list, key = { it.corpCode }) { CorpRow(it) { vm.openCompany(it) } }
            if (vm.etfResults.isNotEmpty()) {
                item { SectionLabel("ETF") }
                items(vm.etfResults, key = { "etf" + it.code }) { EtfRow(it) { vm.openEtf(it) } }
            }
        }
    }
}

@Composable
private fun HomeLists(vm: AppViewModel) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp)) {
        if (vm.favorites.isEmpty() && vm.recents.isEmpty()) {
            item { EmptyBox("기업명을 검색해 보세요. 상장사와 비상장 외감법인 모두 검색됩니다. 자주 보는 기업은 ★로 저장하면 이 화면 위쪽에 바로가기로 나타납니다.") }
        }
        if (vm.recents.isNotEmpty()) {
            item { SectionLabel("최근 본 기업") { TextButton(onClick = vm::clearRecents) { Text("지우기") } } }
            items(vm.recents, key = { "r" + it.corpCode }) { CorpRow(it) { vm.openCompany(it) } }
        }
    }
}

@Composable
fun CorpRow(corp: CorpItemDto, onClick: () -> Unit) {
    SectionCard(Modifier.clickable(onClick = onClick)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            InitialAvatar(corp.corpName, if (corp.isListed) MaterialTheme.colorScheme.primary else AppColors.accent)
            Column(Modifier.weight(1f)) {
                Text(corp.corpName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1)
                val sub = listOfNotNull(corp.stockCode, corp.corpNameEng).joinToString(" · ")
                if (sub.isNotBlank()) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
            Badge(corp.badge.ifBlank { if (corp.isListed) "상장" else "외감" }, listed = corp.isListed)
        }
    }
}

@Composable
private fun EtfRow(etf: EtfItemDto, onClick: () -> Unit) {
    SectionCard(Modifier.clickable(onClick = onClick)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            InitialAvatar(etf.name, MaterialTheme.colorScheme.tertiary)
            Column(Modifier.weight(1f)) {
                Text(etf.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1)
                val sub = listOf(etf.code, etf.indexName).filter { it.isNotBlank() }.joinToString(" · ")
                if (sub.isNotBlank()) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
            Badge("ETF", listed = true)
        }
    }
}
