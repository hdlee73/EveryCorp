package io.github.hdlee73.everycorp.ui.common

import android.content.Context
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.hdlee73.everycorp.ui.theme.StockColors

/** 흰 카드(FSS Insights 스타일). */
@Composable
fun SectionCard(modifier: Modifier = Modifier, title: String? = null, content: @Composable () -> Unit) {
    Card(
        modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (title != null) Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            content()
        }
    }
}

@Composable
fun LabelValue(label: String, value: String?, modifier: Modifier = Modifier, emphasize: Boolean = false) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            value?.takeIf { it.isNotBlank() } ?: "-",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (emphasize) FontWeight.Bold else FontWeight.Normal,
            modifier = Modifier.padding(start = 16.dp),
        )
    }
}

@Composable
fun Badge(text: String, listed: Boolean, modifier: Modifier = Modifier) {
    val (bg, fg) = if (listed) MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
    else MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
    Box(modifier.background(bg, RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 2.dp)) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = fg)
    }
}

@Composable
fun SourceBadge(source: String, modifier: Modifier = Modifier) {
    val (bg, fg) = when (source) {
        "DART" -> Color(0xFFDCEBFC) to Color(0xFF1A5FB4)
        "KIND" -> Color(0xFFFCEDE5) to Color(0xFFA8461A)
        else -> MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
    }
    Box(modifier.background(bg, RoundedCornerShape(6.dp)).padding(horizontal = 7.dp, vertical = 2.dp)) {
        Text(source, style = MaterialTheme.typography.labelSmall, color = fg, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun LoadingBox(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}

@Composable
fun ErrorBox(message: String, onRetry: (() -> Unit)?, modifier: Modifier = Modifier) {
    SectionCard(modifier) {
        Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        if (onRetry != null) Button(onClick = onRetry) { Text("다시 시도") }
    }
}

@Composable
fun EmptyBox(message: String, modifier: Modifier = Modifier) {
    SectionCard(modifier) { Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
}

fun directionColor(direction: String): Color = when (direction) {
    "UP" -> StockColors.up
    "DOWN" -> StockColors.down
    else -> StockColors.flat
}

/** 공시 원문·릴리스 페이지 등을 앱 안 브라우저(Chrome Custom Tabs)로 연다. */
fun openInAppBrowser(context: Context, url: String) {
    runCatching { CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(context, Uri.parse(url)) }
}

/** UiState 를 공통 방식(로딩/오류/성공)으로 그린다. */
@Composable
fun <T> UiStateContent(state: UiState<T>, onRetry: (() -> Unit)?, modifier: Modifier = Modifier, content: @Composable (T) -> Unit) {
    when (state) {
        UiState.Idle, UiState.Loading -> LoadingBox(modifier)
        is UiState.Error -> ErrorBox(state.message, onRetry, modifier)
        is UiState.Success -> content(state.data)
    }
}
