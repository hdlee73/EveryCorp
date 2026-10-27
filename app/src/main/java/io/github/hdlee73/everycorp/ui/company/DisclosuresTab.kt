package io.github.hdlee73.everycorp.ui.company

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
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
import io.github.hdlee73.everycorp.data.repo.DisclosureCategory
import io.github.hdlee73.everycorp.data.repo.DisclosurePeriod
import io.github.hdlee73.everycorp.data.repo.DisclosureSource
import io.github.hdlee73.everycorp.ui.common.EmptyBox
import io.github.hdlee73.everycorp.ui.common.Fmt
import io.github.hdlee73.everycorp.ui.common.LoadingBox
import io.github.hdlee73.everycorp.ui.common.SectionCard
import io.github.hdlee73.everycorp.ui.common.SourceBadge
import io.github.hdlee73.everycorp.ui.common.UiState
import io.github.hdlee73.everycorp.ui.common.UiStateContent
import io.github.hdlee73.everycorp.ui.common.openDisclosure

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DisclosuresTab(vm: CompanyViewModel) {
    val listState = rememberLazyListState()
    val nearEnd by remember { derivedStateOf { val l = listState.layoutInfo; (l.visibleItemsInfo.lastOrNull()?.index ?: 0) >= l.totalItemsCount - 4 } }
    LaunchedEffect(nearEnd, vm.items.size) { if (nearEnd && vm.items.isNotEmpty()) vm.loadMore() }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DisclosurePeriod.entries.forEach { p -> FilterChip(selected = vm.period == p, onClick = { vm.changePeriod(p) }, label = { Text(p.label) }) }
            }
            // 공시 종류(한 덩어리 버튼)와 출처(이름표가 붙은 작은 칩 상자)를 모양으로 구분한다.
            Text("공시 종류", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                val cats = DisclosureCategory.entries
                cats.forEachIndexed { i, c ->
                    SegmentedButton(
                        selected = vm.category == c, onClick = { vm.changeCategory(c) },
                        shape = SegmentedButtonDefaults.itemShape(index = i, count = cats.size),
                        label = { Text(c.label, maxLines = 1, style = MaterialTheme.typography.labelMedium) },
                    )
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 4.dp).border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp)).padding(horizontal = 10.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("출처", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                DisclosureSource.entries.forEach { s ->
                    FilterChip(selected = vm.source == s, onClick = { vm.changeSource(s) }, label = { Text(s.label, style = MaterialTheme.typography.labelSmall) }, modifier = Modifier.height(28.dp))
                }
            }
            OutlinedTextField(
                value = vm.search, onValueChange = vm::changeSearch, modifier = Modifier.fillMaxWidth(),
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
    SectionCard(Modifier.clickable { openDisclosure(context, d) }) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SourceBadge(d.source)
            Text(Fmt.date(d.date) + (d.time?.let { " $it" } ?: ""), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(d.title, style = MaterialTheme.typography.bodyLarge)
        val sub = listOfNotNull(d.submitter, d.remark).filter { it.isNotBlank() }.joinToString(" · ")
        if (sub.isNotBlank()) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
