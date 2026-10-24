package io.github.hdlee73.everycorp.ui.compare

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.ui.platform.LocalContext
import io.github.hdlee73.everycorp.ui.ExportState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.hdlee73.everycorp.ui.AppViewModel
import io.github.hdlee73.everycorp.ui.common.Dest
import io.github.hdlee73.everycorp.ui.common.GradientTopBar
import io.github.hdlee73.everycorp.ui.common.SectionCard
import io.github.hdlee73.everycorp.ui.common.UiState
import io.github.hdlee73.everycorp.ui.common.UiStateContent
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/** 관심기업 2~4곳의 최근 사업연도 핵심 지표를 나란히 보여 준다(기업 개요의 5개년 추이 값 사용). */
@Composable
fun CompareScreen(vm: AppViewModel, d: Dest.Compare) {
    val state by produceState<UiState<List<CompareColumn>>>(UiState.Loading, d) {
        value = UiState.Loading
        value = UiState.Success(
            coroutineScope {
                d.corps.map { c -> async { CompareLogic.column(c.corpName, vm.repository.overview(c.corpCode).getOrNull()) } }.awaitAll()
            },
        )
    }
    Column(Modifier.fillMaxSize()) {
        val context = LocalContext.current
        val cols = (state as? UiState.Success)?.data
        GradientTopBar(title = "기업 비교", onBack = { vm.back() }, trailing = {
            if (vm.pdfExport is ExportState.Working) CircularProgressIndicator(Modifier.padding(12.dp).size(24.dp), strokeWidth = 2.dp)
            else if (cols != null) IconButton(onClick = { vm.exportComparePdf(context.applicationContext, cols) }) {
                Icon(Icons.Filled.PictureAsPdf, contentDescription = "비교 리포트 PDF")
            }
        })
        UiStateContent(state, onRetry = null, modifier = Modifier.padding(16.dp)) { cols ->
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SectionCard {
                    Row(Modifier.fillMaxWidth()) {
                        Text("", Modifier.weight(1.2f))
                        cols.forEach { c ->
                            Column(Modifier.weight(1f)) {
                                Text(c.name, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, textAlign = TextAlign.End, maxLines = 2, modifier = Modifier.fillMaxWidth())
                                Text(
                                    c.year?.let { "${it}년" } ?: "재무 없음", style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.End, modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    CompareLogic.ROWS.forEach { (key, label) ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            Text(label, Modifier.weight(1.2f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            cols.forEach { c ->
                                Text(CompareLogic.format(key, c.values[key]), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.End)
                            }
                        }
                    }
                }
                Text(
                    "출처: DART 사업보고서(각 기업 최근 사업연도, 연결 우선) · 기업마다 기준 연도가 다를 수 있습니다. " +
                        "PER·PBR은 연말 종가로 직접 계산한 추정치이며 비상장·재무 미공시 기업은 '-'로 표시됩니다.",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
