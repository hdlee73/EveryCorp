package io.github.hdlee73.everycorp.ui.company

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.hdlee73.everycorp.data.api.dto.NewsDto
import io.github.hdlee73.everycorp.data.api.dto.NewsItemDto
import io.github.hdlee73.everycorp.ui.common.EmptyBox
import io.github.hdlee73.everycorp.ui.common.Fmt
import io.github.hdlee73.everycorp.ui.common.SectionCard
import io.github.hdlee73.everycorp.ui.common.UiStateContent
import io.github.hdlee73.everycorp.ui.common.openInAppBrowser

@Composable
fun NewsTab(vm: CompanyViewModel) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        UiStateContent(vm.news, onRetry = vm::loadNews) { n -> NewsBody(n) }
    }
}

@Composable
private fun NewsBody(n: NewsDto) {
    val sourceLabel = if (n.source == "NAVER") "네이버 뉴스" else "구글 뉴스"
    Text("“${n.query}” 최신 뉴스 · 출처 $sourceLabel", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (n.items.isEmpty()) EmptyBox("최근 뉴스가 없습니다.")
    n.items.forEach { NewsRow(it) }
}

@Composable
private fun NewsRow(item: NewsItemDto) {
    val context = LocalContext.current
    SectionCard(Modifier.clickable { openInAppBrowser(context, item.url) }) {
        Text(item.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 3, overflow = TextOverflow.Ellipsis)
        if (item.summary.isNotBlank()) {
            Text(item.summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        val meta = listOfNotNull(item.publisher.ifBlank { null }, Fmt.relativeTime(item.publishedAt)).joinToString(" · ")
        if (meta.isNotBlank()) Text(meta, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
