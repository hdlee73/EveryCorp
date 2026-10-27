package io.github.hdlee73.everycorp.ui.favorites

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import io.github.hdlee73.everycorp.ui.ExportState
import androidx.compose.ui.unit.dp
import io.github.hdlee73.everycorp.data.api.dto.CorpItemDto
import io.github.hdlee73.everycorp.ui.AppViewModel
import io.github.hdlee73.everycorp.ui.common.Badge
import io.github.hdlee73.everycorp.ui.common.EmptyBox
import io.github.hdlee73.everycorp.ui.common.InitialAvatar
import io.github.hdlee73.everycorp.ui.common.PillChip
import io.github.hdlee73.everycorp.ui.common.SectionCard
import io.github.hdlee73.everycorp.ui.common.TabHeader
import io.github.hdlee73.everycorp.ui.theme.AppColors

/** 관심기업 목록. 그룹 칩으로 나눠 보고, 행의 메뉴에서 그룹 이동·해제한다. */
@Composable
fun FavoritesScreen(vm: AppViewModel) {
    var selected by remember { mutableStateOf<String?>(null) } // null = 전체
    var adding by remember { mutableStateOf(false) }
    var comparing by remember { mutableStateOf(false) }
    var picked by remember { mutableStateOf(listOf<CorpItemDto>()) }
    val shown = vm.favorites.filter { selected == null || vm.groupOf[it.corpCode] == selected }
    Column(Modifier.fillMaxSize()) {
        TabHeader("관심 기업", "★로 저장한 기업을 그룹별로 모아 봅니다")
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PillChip("전체 ${vm.favorites.size}", onClick = { selected = null }, selected = selected == null)
            vm.groupNames.forEach { g ->
                PillChip("$g ${vm.favorites.count { vm.groupOf[it.corpCode] == g }}", onClick = { selected = g }, selected = selected == g)
            }
            PillChip("＋ 그룹", onClick = { adding = true })
            PillChip(if (comparing) "선택 취소" else "비교·PDF", onClick = { comparing = !comparing; picked = emptyList() }, selected = comparing)
        }
        if (comparing) Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            val context = LocalContext.current
            Text("기업을 고르세요 (${picked.size}/8)", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { vm.openCompare(picked); comparing = false; picked = emptyList() }, enabled = picked.size in 2..4) { Text("비교(2~4)") }
            TextButton(
                onClick = { vm.exportBatchPdf(context.applicationContext, picked); comparing = false; picked = emptyList() },
                enabled = picked.isNotEmpty() && vm.pdfExport !is ExportState.Working,
            ) { Text("요약 PDF") }
        }
        if (shown.isEmpty()) {
            EmptyBox(
                if (vm.favorites.isEmpty()) "기업 화면의 ★를 누르면 여기에 모입니다. 새 공시 알림도 관심기업 기준으로 옵니다."
                else "이 그룹에 담긴 기업이 없습니다. 기업 행의 ⋮ 메뉴에서 그룹을 지정해 주세요.",
                Modifier.padding(horizontal = 16.dp),
            )
        } else LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp)) {
            items(shown, key = { it.corpCode }) { corp ->
                FavoriteRow(
                    vm, corp, picking = comparing, picked = corp in picked,
                    onPick = { picked = if (corp in picked) picked - corp else if (picked.size < 8) picked + corp else picked },
                )
            }
        }
    }
    if (adding) AddGroupDialog(onDismiss = { adding = false }, onAdd = { vm.addGroup(it); adding = false })
}

@Composable
private fun FavoriteRow(vm: AppViewModel, corp: CorpItemDto, picking: Boolean, picked: Boolean, onPick: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val group = vm.groupOf[corp.corpCode]
    SectionCard(Modifier.clickable { if (picking) onPick() else vm.openCompany(corp) }) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (picking) Checkbox(checked = picked, onCheckedChange = { onPick() })
            InitialAvatar(corp.corpName, if (corp.isListed) MaterialTheme.colorScheme.primary else AppColors.accent)
            Column(Modifier.weight(1f)) {
                Text(corp.corpName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 2)
                val sub = listOfNotNull(corp.stockCode?.takeIf { it.isNotBlank() }, group).joinToString(" · ")
                if (sub.isNotBlank()) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
            corp.badge.ifBlank { if (corp.isListed) "" else "외감" }.let { b -> if (b.isNotBlank()) Badge(b, listed = corp.isListed) }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "그룹·삭제") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("그룹 없음") }, onClick = { vm.setGroup(corp, null); menu = false })
                    vm.groupNames.forEach { g -> DropdownMenuItem(text = { Text(g) }, onClick = { vm.setGroup(corp, g); menu = false }) }
                    DropdownMenuItem(text = { Text("관심 해제") }, onClick = { vm.toggleFavorite(corp); menu = false })
                }
            }
        }
    }
}

@Composable
private fun AddGroupDialog(onDismiss: () -> Unit, onAdd: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("새 그룹") },
        text = { OutlinedTextField(name, { name = it.take(12) }, singleLine = true, placeholder = { Text("예: 반도체, 내 포트폴리오") }) },
        confirmButton = { TextButton(onClick = { onAdd(name) }, enabled = name.isNotBlank()) { Text("추가") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}
