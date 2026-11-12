package io.github.hdlee73.everycorp.ui.etf

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.hdlee73.everycorp.data.api.dto.CorpItemDto
import io.github.hdlee73.everycorp.data.api.dto.EtfHoldingDto
import io.github.hdlee73.everycorp.data.api.dto.EtfItemDto
import io.github.hdlee73.everycorp.data.api.dto.EtfRelationsDto
import io.github.hdlee73.everycorp.data.api.dto.EtfWeightRefDto
import io.github.hdlee73.everycorp.ui.AppViewModel
import io.github.hdlee73.everycorp.ui.common.DonutChart
import io.github.hdlee73.everycorp.ui.common.PillChip
import io.github.hdlee73.everycorp.ui.common.SectionCard
import io.github.hdlee73.everycorp.ui.common.UiStateContent
import java.util.Locale

/** ETF 화면 숫자 표기. 순수 함수라 단위 테스트한다. */
object EtfFmt {
    /** 원 → "1조 2,345억원" / "8,720억원" / "3.2억원". */
    fun eok(won: Long): String {
        val eok = won / 1e8
        return when {
            eok >= 10_000 -> {
                val jo = (eok / 10_000).toLong()
                val rest = Math.round(eok - jo * 10_000.0)
                if (rest == 0L) String.format(Locale.US, "%,d조원", jo) else String.format(Locale.US, "%,d조 %,d억원", jo, rest)
            }
            eok >= 100 -> String.format(Locale.US, "%,.0f억원", eok)
            else -> String.format(Locale.US, "%,.1f억원", eok)
        }
    }

    /** 0.0900 → "0.09%", 0.45 → "0.45%". 소수 넷째 자리까지, 끝의 0 은 뗀다. */
    fun fee(v: Double): String = String.format(Locale.US, "%.4f", v).trimEnd('0').trimEnd('.') + "%"

    fun weight(v: Double?): String = if (v == null) "-" else String.format(Locale.US, "%.2f%%", v)

    fun withSource(value: String, source: String?): String = if (source.isNullOrBlank()) value else "$value ($source)"

    fun matchLabel(match: String): String = when (match) {
        "code" -> "코드 일치"
        "isin" -> "ISIN 변환"
        "pref" -> "우선주→보통주 회사"
        "name" -> "이름 대조"
        else -> ""
    }
}

private const val PAGE = 100

/** 구성종목을 눌렀을 때 이동: 주식은 기업 화면, ETF 는 그 ETF 화면. 그 밖(현금·채권·미매칭)은 이동 없음. */
private fun openHolding(app: AppViewModel, h: EtfHoldingDto): (() -> Unit)? = when {
    h.kind == "STOCK" && !h.corpCode.isNullOrBlank() -> {
        { app.openCompany(CorpItemDto(corpCode = h.corpCode.orEmpty(), corpName = h.corpName ?: h.name, stockCode = h.stockCode, listing = "LISTED")) }
    }
    h.kind == "ETF" && !h.etfCode.isNullOrBlank() -> {
        { app.openEtf(EtfItemDto(code = h.etfCode.orEmpty(), name = h.etfName ?: h.name)) }
    }
    else -> null
}

@Composable
internal fun PortfolioTab(app: AppViewModel, vm: EtfViewModel) {
    UiStateContent(vm.portfolio, onRetry = { vm.loadPortfolio(force = true) }) { p ->
        SectionCard(title = "구성종목 요약") {
            Text(
                "기준일 ${p.date.ifBlank { "-" }} · ${p.count}종목 · 비중 합계 ${EtfFmt.weight(p.totalWeight)}",
                style = MaterialTheme.typography.bodyMedium,
            )
            if (p.partial) Text("상위 구성종목만 받았습니다(전체 PDF 아님). 비중 합계가 100%보다 작습니다.", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
            if (p.unmatchedCount > 0) Text("미매칭 ${p.unmatchedCount}종목: 코드·이름으로 상장사·ETF 를 찾지 못한 항목입니다.", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
            val slices = remember(p.donut) { p.donut.map { it.label to it.weight } }
            DonutChart(slices, Modifier.fillMaxWidth().padding(top = 8.dp), centerText = "상위 비중")
            if (p.byKind.isNotEmpty()) {
                Text("자산 종류별", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp))
                Text(p.byKind.joinToString("  ·  ") { "${it.label} ${EtfFmt.weight(it.weight)}" }, style = MaterialTheme.typography.bodySmall)
            }
            Text("출처: ${p.source}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        SectionCard(title = "구성종목(PDF)") {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                HoldingFilter.entries.forEach { f -> PillChip(f.label, onClick = { vm.select(f) }, selected = vm.filter == f) }
            }
            val rows = remember(p.holdings, vm.filter) { filterHoldings(p.holdings, vm.filter) }
            var shown by rememberSaveable(p.code, vm.filter) { mutableIntStateOf(PAGE) }
            if (rows.isEmpty()) Text("해당하는 항목이 없습니다.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
            rows.take(shown).forEachIndexed { i, h ->
                if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                HoldingRow(h, openHolding(app, h))
            }
            if (rows.size > shown) TextButton(onClick = { shown += PAGE }) { Text("더 보기 (${rows.size - shown}개 남음)") }
            Text(p.note, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

@Composable
private fun HoldingRow(h: EtfHoldingDto, onClick: (() -> Unit)?) {
    val unmatched = h.kind == "UNMATCHED"
    Row(
        Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                h.displayName, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = if (onClick != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
            val sub = listOfNotNull(
                (h.stockCode ?: h.etfCode ?: h.code).takeIf { it.isNotBlank() },
                h.kindLabel,
                EtfFmt.matchLabel(h.match).takeIf { it.isNotBlank() },
                h.qty?.let { String.format(Locale.US, "%,.0f주", it) },
            ).joinToString(" · ")
            Text(sub, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = if (unmatched) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(EtfFmt.weight(h.weight), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 8.dp))
    }
}

@Composable
internal fun RelationsTab(app: AppViewModel, vm: EtfViewModel, me: EtfItemDto) {
    UiStateContent(vm.relations, onRetry = { vm.loadRelations(force = true) }) { r -> RelationsContent(app, r, me) }
}

@Composable
private fun RelationsContent(app: AppViewModel, r: EtfRelationsDto, me: EtfItemDto) {
    val open = { code: String, name: String -> app.openEtf(EtfItemDto(code = code, name = name)) }
    val eh = r.etfHoldings
    SectionCard(title = "편입한 ETF (자사·타사)") {
        Text("운용사: ${r.manager.ifBlank { "확인 못 함" }}", style = MaterialTheme.typography.bodySmall)
        Text(
            "자사 ETF 편입비중 ${EtfFmt.weight(eh.ownWeight)} · 타사 ETF ${EtfFmt.weight(eh.otherWeight)}",
            style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
        )
        if (eh.own.isEmpty() && eh.other.isEmpty()) Text("구성종목 중 ETF 가 없습니다.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        eh.own.forEach { RefRow(it, "자사") { open(it.code, it.name) } }
        eh.other.forEach { RefRow(it, "타사") { open(it.code, it.name) } }
        if (r.partial) Text("상위 구성종목만으로 계산해 실제보다 작을 수 있습니다.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
    }
    SectionCard(title = "순환투자 점검") {
        if (r.cycles.isEmpty() && r.mutual.isEmpty()) {
            Text("편입 ETF 를 4단계까지 따라가도 이 ETF 로 되돌아오는 경로가 없습니다.", style = MaterialTheme.typography.bodySmall)
        }
        if (r.mutual.isNotEmpty()) {
            Text("서로 편입(상호보유)", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.error)
            r.mutual.forEach { m -> LinkText("${m.name} (${m.code})") { open(m.code, m.name) } }
        }
        r.cycles.forEach { path ->
            Text(
                "순환 경로: " + path.joinToString(" → ") { it.name.ifBlank { it.code } },
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
    SectionCard(title = "이 ETF 를 담은 ETF") {
        if (r.heldBy.isEmpty()) Text("서버에 저장된 ETF 중 이 ETF 를 편입한 곳이 없습니다.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        r.heldBy.forEach { h ->
            RefRow(EtfWeightRefDto(h.code, h.name, h.manager, h.weight), if (h.own) "자사" else "타사") { open(h.code, h.name) }
        }
    }
    SectionCard(title = "구성이 비슷한 ETF (중복도)") {
        if (r.similar.isEmpty()) Text("겹치는 종목이 있는 ETF 를 찾지 못했습니다.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        r.similar.forEach { s ->
            Row(Modifier.fillMaxWidth().clickable { app.openEtfOverlap(me, EtfItemDto(code = s.code, name = s.name)) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(s.name, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${s.code} · ${s.manager.ifBlank { "-" }} · 공통 ${s.commonCount}종목", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(EtfFmt.weight(s.overlapWeight), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
            }
        }
        Text("누르면 두 ETF 의 공통 종목과 비중을 비교합니다.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    val cov = r.coverage
    Text(
        "분석 범위: 서버에 저장된 ETF ${cov.stored}개" + (if (cov.crawledOn.isNotBlank()) " (전체 수집 ${cov.crawledOn})" else "") +
            (if (cov.crawling) " · 수집 중 ${cov.progress.done}/${cov.progress.total}" else "") + "\n" + r.note,
        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun RefRow(r: EtfWeightRefDto, tag: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(r.name.ifBlank { r.code }, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${r.code} · $tag · ${r.manager.ifBlank { "운용사 미확인" }}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(EtfFmt.weight(r.weight), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun LinkText(text: String, onClick: () -> Unit) {
    Text(text, Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
}
