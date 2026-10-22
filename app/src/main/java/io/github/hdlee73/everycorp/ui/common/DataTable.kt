package io.github.hdlee73.everycorp.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.hdlee73.everycorp.data.api.dto.FinTableDto
import io.github.hdlee73.everycorp.ui.theme.StockColors

private val LabelWidth = 84.dp
private val CellWidth = 92.dp

/** 항목 | 값 두 칸짜리 표(기업 개요처럼 이름-값 목록). 빈 값은 줄 자체를 숨긴다. */
@Composable
fun KeyValueTable(rows: List<Pair<String, String?>>, modifier: Modifier = Modifier) {
    val shown = rows.filter { !it.second.isNullOrBlank() }
    Column(modifier.fillMaxWidth()) {
        shown.forEachIndexed { i, (k, v) ->
            if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
            Row(Modifier.fillMaxWidth().padding(vertical = 9.dp), verticalAlignment = Alignment.Top) {
                Text(k, Modifier.width(LabelWidth), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(v.orEmpty(), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            }
        }
    }
}

/**
 * 기간 비교표. 첫 열은 항목명(고정), 나머지는 기간 열. 금액은 억원, 1조 이상은 조원을 아래에 함께 보여 준다.
 * 열이 많으면 가로로 밀어서 볼 수 있다.
 */
@Composable
fun FinancialTable(table: FinTableDto, modifier: Modifier = Modifier) {
    if (table.columns.isEmpty()) return
    val scroll = rememberScrollState()
    Row(modifier.fillMaxWidth()) {
        Column(Modifier.width(LabelWidth)) {
            HeaderCell("구분", null, LabelWidth, TextAlign.Start)
            table.rows.forEach { r -> RowLabel(r.label) }
        }
        Column(Modifier.weight(1f).horizontalScroll(scroll)) {
            Row {
                table.columns.forEach { c -> HeaderCell(c.label, c.sub, CellWidth, TextAlign.End) }
            }
            table.rows.forEach { r ->
                Row {
                    table.columns.indices.forEach { i -> AmountCell(r.values.getOrNull(i), r.unit) }
                }
            }
        }
    }
}

@Composable
private fun HeaderCell(label: String, sub: String?, width: Dp, align: TextAlign) {
    Column(
        Modifier.width(width).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(horizontal = 8.dp, vertical = 8.dp),
        horizontalAlignment = if (align == TextAlign.End) Alignment.End else Alignment.Start,
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, textAlign = align, maxLines = 1)
        Text(sub ?: " ", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = align, maxLines = 1)
    }
}

@Composable
private fun RowLabel(label: String) {
    Box(Modifier.width(LabelWidth).height(ROW_HEIGHT).padding(horizontal = 8.dp), contentAlignment = Alignment.CenterStart) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}

private val ROW_HEIGHT = 48.dp

@Composable
private fun AmountCell(value: Double?, unit: String?) {
    Column(
        Modifier.width(CellWidth).height(ROW_HEIGHT).padding(horizontal = 8.dp),
        horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.Center,
    ) {
        if (unit == "%") {
            Text(if (value == null) "-" else Fmt.percent(value), style = MaterialTheme.typography.bodyMedium, maxLines = 1)
        } else {
            val color = if (value != null && value < 0) StockColors.down else MaterialTheme.colorScheme.onSurface
            Text(Fmt.eok(value), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = color, maxLines = 1)
            Fmt.jo(value)?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1) }
        }
    }
}
