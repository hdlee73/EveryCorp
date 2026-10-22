package io.github.hdlee73.everycorp.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.hdlee73.everycorp.data.api.dto.CorpItemDto
import io.github.hdlee73.everycorp.ui.AppViewModel
import io.github.hdlee73.everycorp.ui.common.Badge
import io.github.hdlee73.everycorp.ui.common.EmptyBox
import io.github.hdlee73.everycorp.ui.common.SectionCard
import io.github.hdlee73.everycorp.ui.common.UiState
import io.github.hdlee73.everycorp.ui.common.UiStateContent

@Composable
fun SearchScreen(vm: AppViewModel) {
    Column(Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("에브리코프", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("기업정보 · 공시 · 주가 · 채권", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = vm::openInfo) { Icon(Icons.Filled.Info, contentDescription = "앱 정보") }
        }
        OutlinedTextField(
            value = vm.query,
            onValueChange = vm::onQuery,
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
            placeholder = { Text("기업명 또는 초성 (예: 삼성전자, ㅅㅅㅈㅈ)") },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            trailingIcon = {
                if (vm.query.isNotEmpty()) IconButton(onClick = { vm.onQuery("") }) { Icon(Icons.Filled.Close, contentDescription = "지우기") }
            },
            singleLine = true,
            shape = MaterialTheme.shapes.large,
        )
        if (vm.query.isBlank()) HomeLists(vm) else SearchResults(vm)
    }
}

@Composable
private fun SearchResults(vm: AppViewModel) {
    UiStateContent(vm.results, onRetry = { vm.onQuery(vm.query) }) { list ->
        if (list.isEmpty()) EmptyBox("검색 결과가 없습니다. 기업명을 다시 확인해 주세요.")
        else LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
            items(list, key = { it.corpCode }) { CorpRow(it) { vm.openCompany(it) } }
        }
    }
}

@Composable
private fun HomeLists(vm: AppViewModel) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
        if (vm.favorites.isEmpty() && vm.recents.isEmpty()) {
            item { EmptyBox("기업명을 검색해 보세요. 상장사와 비상장 외감법인 모두 검색됩니다. 자주 보는 기업은 ★로 저장할 수 있습니다.") }
        }
        if (vm.favorites.isNotEmpty()) {
            item { Text("관심기업", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 4.dp)) }
            items(vm.favorites, key = { "f" + it.corpCode }) { CorpRow(it) { vm.openCompany(it) } }
        }
        if (vm.recents.isNotEmpty()) {
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("최근 본 기업", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    TextButton(onClick = vm::clearRecents) { Text("지우기") }
                }
            }
            items(vm.recents, key = { "r" + it.corpCode }) { CorpRow(it) { vm.openCompany(it) } }
        }
    }
}

@Composable
fun CorpRow(corp: CorpItemDto, onClick: () -> Unit) {
    SectionCard(Modifier.clickable(onClick = onClick)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(corp.corpName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                val sub = listOfNotNull(corp.stockCode, corp.corpNameEng).joinToString(" · ")
                if (sub.isNotBlank()) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Badge(corp.badge.ifBlank { if (corp.isListed) "상장" else "외감" }, listed = corp.isListed)
        }
    }
}
