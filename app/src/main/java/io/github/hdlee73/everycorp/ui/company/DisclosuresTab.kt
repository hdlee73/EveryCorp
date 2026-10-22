package io.github.hdlee73.everycorp.ui.company

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import io.github.hdlee73.everycorp.data.api.dto.DisclosureDto
import io.github.hdlee73.everycorp.data.repo.DisclosurePeriod
import io.github.hdlee73.everycorp.data.repo.DisclosureSource
import io.github.hdlee73.everycorp.ui.common.EmptyBox
import io.github.hdlee73.everycorp.ui.common.Fmt
import io.github.hdlee73.everycorp.ui.common.LoadingBox
import io.github.hdlee73.everycorp.ui.common.SectionCard
import io.github.hdlee73.everycorp.ui.common.SourceBadge
import io.github.hdlee73.everycorp.ui.common.UiState
import io.github.hdlee73.everycorp.ui.common.UiStateContent
import io.github.hdlee73.everycorp.ui.common.openInAppBrowser

@Composable
fun DisclosuresTab(vm: CompanyViewModel) {
    val listState = rememberLazyListState()
    val nearEnd by remember { derivedStateOf { val l = listState.layoutInfo; (l.visibleItemsInfo.lastOrNull()?.index ?: 0) >= l.totalItemsCount - 4 } }
    LaunchedEffect(nearEnd, vm.items.size) { if (nearEnd && vm.items.isNotEmpty()) vm.loadMore() }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DisclosurePeriod.entries.forEach { p -> FilterChip(selected = vm.period == p, onClick = { vm.setPeriod(p) }, label = { Text(p.label) }) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DisclosureSource.entries.forEach { s -> FilterChip(selected = vm.source == s, onClick = { vm.setSource(s) }, label = { Text(s.label) }) }
            }
            OutlinedTextField(
                value = vm.search, onValueChange = vm::setSearch, modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("공시 제목 검색") }, singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { vm.submitSearch() }),
            )
            (vm.disclosureState as? UiState.Success)?.let {
                Text("DART ${vm.counts.dart}건 · KIND ${vm.counts.kind}건", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        UiStateContent(vm.disclosureState, onRetry = vm::reloadDisclosures, modifier = Modifier.padding(horizontal = 16.dp)) {
            LazyColumn(
                state = listState, modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            ) {
                vm.warnings.forEach { w -> item { Text(w, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) } }
                if (vm.items.isEmpty()) item { EmptyBox("조건에 맞는 공시가 없습니다. 기간을 늘려 보세요.") }
                items(vm.items, key = { it.id }) { DisclosureRow(it) }
                if (vm.loadingMore) item { LoadingBox() }
            }
        }
    }
}

@Composable
private fun DisclosureRow(d: DisclosureDto) {
    val context = LocalContext.current
    SectionCard(Modifier.clickable { openInAppBrowser(context, d.mobileUrl.ifBlank { d.url }) }) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SourceBadge(d.source)
            Text(Fmt.date(d.date) + (d.time?.let { " $it" } ?: ""), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(d.title, style = MaterialTheme.typography.bodyLarge)
        val sub = listOfNotNull(d.submitter, d.remark).filter { it.isNotBlank() }.joinToString(" · ")
        if (sub.isNotBlank()) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
