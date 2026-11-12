package io.github.hdlee73.everycorp.ui.company

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.hdlee73.everycorp.data.api.dto.FlowSumDto
import io.github.hdlee73.everycorp.data.api.dto.FlowsDto
import io.github.hdlee73.everycorp.ui.common.EmptyBox
import io.github.hdlee73.everycorp.ui.common.Fmt
import io.github.hdlee73.everycorp.ui.common.PillChip
import io.github.hdlee73.everycorp.ui.common.SectionCard
import io.github.hdlee73.everycorp.ui.common.UiStateContent
import io.github.hdlee73.everycorp.ui.theme.StockColors
import java.util.Locale

/** 수급 탭: 외국인·기관·개인 순매수, 프로그램 매매, 공매도, 신용잔고(키움 조회 전용). */
@Composable
fun FlowsTab(vm: CompanyViewModel) {
    if (!vm.corp.isListed) {
        Column(Modifier.padding(16.dp)) { EmptyBox("비상장 법인은 수급 정보가 없습니다.") }
        return
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        UiStateContent(vm.flows, onRetry = vm::loadFlows) { f -> FlowsContent(f, onRefresh = vm::loadFlows) }
    }
}

@Composable
private fun FlowsContent(f: FlowsDto, onRefresh: () -> Unit) {
    SectionCard(title = "누적 순매수 요약") {
        SummaryRow("최근 5일", f.summary.d5)
        SummaryRow("최근 20일", f.summary.d20)
        Text("주체별은 순매수 수량(주), 프로그램은 순매수 금액(백만원). 양수=순매수(빨강), 음수=순매도(파랑).",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (f.investors.isNotEmpty()) InvestorCard(f)
    if (f.program.isNotEmpty()) SectionCard(title = "프로그램 매매 (백만원)") {
        NetBars(f.program.take(20).reversed().map { it.date to (it.netAmt ?: 0L) })
        TableHeader(listOf("일자", "매수", "매도", "순매수"))
        f.program.take(10).forEach { r -> TableRow(listOf(Fmt.date(r.date).drop(2), Fmt.number(r.buyAmt), Fmt.number(r.sellAmt), signed(r.netAmt)), last = r.netAmt) }
    }
    if (f.short.isNotEmpty()) SectionCard(title = "공매도") {
        NetBars(f.short.take(20).reversed().map { it.date to (it.shortQty ?: 0L) }, single = StockColors.down)
        TableHeader(listOf("일자", "공매도량", "비중", "평균가"))
        f.short.take(10).forEach { r ->
            TableRow(listOf(Fmt.date(r.date).drop(2), Fmt.number(r.shortQty), r.shortRatio?.let { String.format(Locale.US, "%.2f%%", it) } ?: "-", Fmt.price(r.shortAvgPrice)))
        }
        Text("비중 = 그날 거래량 중 공매도 수량 비율. 공매도 잔고(미상환)는 아닙니다.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (f.credit.isNotEmpty()) SectionCard(title = "신용잔고 (융자)") {
        NetBars(f.credit.take(20).reversed().map { it.date to (it.balance ?: 0L) }, single = Color(0xFFF59E0B))
        TableHeader(listOf("일자", "신규", "상환", "잔고", "잔고율"))
        f.credit.take(10).forEach { r ->
            TableRow(listOf(Fmt.date(r.date).drop(2), Fmt.number(r.new), Fmt.number(r.repay), Fmt.number(r.balance), r.balanceRate?.let { String.format(Locale.US, "%.2f%%", it) } ?: "-"))
        }
        Text("수량(주) 기준. 잔고율 = 상장주식수 대비 신용융자 잔고 비율.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    f.warnings.forEach { Text("⚠ $it", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("출처 키움증권 REST API(시세 조회 전용) · 10분 캐시", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = onRefresh) { Text("새로고침") }
    }
}

private enum class Who(val label: String, val color: Color) {
    Foreign("외국인", Color(0xFF2563EB)), Institution("기관", Color(0xFF10B981)), Individual("개인", Color(0xFFF59E0B))
}

@Composable
private fun InvestorCard(f: FlowsDto) {
    var who by remember { mutableStateOf(Who.Foreign) }
    SectionCard(title = "투자자별 순매수 (주)") {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Who.entries.forEach { w -> PillChip(w.label, onClick = { who = w }, selected = who == w) }
        }
        val rows = f.investors.take(20).reversed()
        NetBars(rows.map { r -> r.date to (when (who) { Who.Foreign -> r.foreign; Who.Institution -> r.institution; Who.Individual -> r.individual } ?: 0L) })
        CumulativeLine(rows.map { r -> when (who) { Who.Foreign -> r.foreign; Who.Institution -> r.institution; Who.Individual -> r.individual } ?: 0L }, who.color)
        Text("막대 = 일별 순매수, 선 = 20일 누적", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TableHeader(listOf("일자", "외국인", "기관", "개인"))
        f.investors.take(10).forEach { r ->
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                Cell(Fmt.date(r.date).drop(2))
                Cell(signed(r.foreign), color = signColor(r.foreign))
                Cell(signed(r.institution), color = signColor(r.institution))
                Cell(signed(r.individual), color = signColor(r.individual))
            }
        }
    }
}

@Composable
private fun SummaryRow(label: String, s: FlowSumDto) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(64.dp), style = MaterialTheme.typography.labelLarge)
        Stat("외국인", s.foreign)
        Stat("기관", s.institution)
        Stat("개인", s.individual)
        Stat("프로그램", s.programAmt)
    }
}

@Composable
private fun Stat(label: String, v: Long?) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(signed(v), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = signColor(v))
    }
}

@Composable
private fun TableHeader(cols: List<String>) {
    Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
        cols.forEach { Cell(it, color = MaterialTheme.colorScheme.onSurfaceVariant, header = true) }
    }
}

@Composable
private fun TableRow(cols: List<String>, last: Long? = null) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        cols.forEachIndexed { i, c -> Cell(c, color = if (i == cols.lastIndex && last != null) signColor(last) else MaterialTheme.colorScheme.onSurface) }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.Cell(text: String, color: Color = MaterialTheme.colorScheme.onSurface, header: Boolean = false) {
    Text(text, Modifier.weight(1f), style = if (header) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelMedium, color = color, textAlign = TextAlign.End, maxLines = 1)
}

/** 일별 막대(0 기준 위·아래). [single]이 있으면 모두 양수 막대로 그 색만 쓴다. */
@Composable
private fun NetBars(points: List<Pair<String, Long>>, single: Color? = null, height: Dp = 90.dp) {
    if (points.isEmpty()) return
    Canvas(Modifier.fillMaxWidth().height(height).padding(vertical = 4.dp)) {
        val maxAbs = points.maxOf { kotlin.math.abs(it.second) }.coerceAtLeast(1)
        val slot = size.width / points.size
        val bw = slot * 0.62f
        val zero = if (single != null) size.height else size.height / 2
        val scale = if (single != null) size.height else size.height / 2
        drawLine(Color.Gray.copy(alpha = 0.35f), Offset(0f, zero), Offset(size.width, zero), strokeWidth = 1f)
        points.forEachIndexed { i, (_, v) ->
            val h = scale * kotlin.math.abs(v) / maxAbs
            val c = single ?: if (v >= 0) StockColors.up else StockColors.down
            val top = if (single != null || v >= 0) zero - h else zero
            drawRect(c.copy(alpha = 0.75f), Offset(i * slot + (slot - bw) / 2, top), Size(bw, h.coerceAtLeast(1f)))
        }
    }
}

/** 누적합 선(가장 오래된 날부터). */
@Composable
private fun CumulativeLine(values: List<Long>, color: Color) {
    if (values.size < 2) return
    val acc = values.runningReduce { a, b -> a + b }
    Canvas(Modifier.fillMaxWidth().height(56.dp)) {
        val maxV = acc.max().toFloat(); val minV = acc.min().toFloat()
        val span = (maxV - minV).takeIf { it > 0f } ?: 1f
        val slot = size.width / acc.size
        fun y(v: Long) = size.height * (1f - (v - minV) / span)
        if (minV < 0 && maxV > 0) drawLine(Color.Gray.copy(alpha = 0.35f), Offset(0f, y(0)), Offset(size.width, y(0)), strokeWidth = 1f)
        for (i in 1 until acc.size) {
            drawLine(color, Offset((i - 1) * slot + slot / 2, y(acc[i - 1])), Offset(i * slot + slot / 2, y(acc[i])), strokeWidth = 4f)
        }
    }
}

private fun signed(v: Long?): String = when {
    v == null -> "-"
    v > 0 -> "+" + Fmt.number(v)
    else -> Fmt.number(v)
}

private fun signColor(v: Long?): Color = when {
    v == null || v == 0L -> StockColors.flat
    v > 0 -> StockColors.up
    else -> StockColors.down
}
