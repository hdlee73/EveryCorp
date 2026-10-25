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
import androidx.compose.material3.Surface
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import io.github.hdlee73.everycorp.ui.theme.AppColors
import io.github.hdlee73.everycorp.ui.theme.StockColors

/** 흰 카드(FSS Insights 스타일): 둥근 모서리 + 옅은 테두리 + 얕은 그림자. */
@Composable
fun SectionCard(modifier: Modifier = Modifier, title: String? = null, content: @Composable () -> Unit) {
    Surface(
        modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        shadowElevation = 1.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (title != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.width(4.dp).height(16.dp).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp)))
                    Text(title, Modifier.padding(start = 8.dp), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                }
            }
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
    val (bg, fg) = when (text) {
        "유가증권", "상장" -> Color(0xFFDCEBFC) to Color(0xFF1A5FB4)
        "코스닥" -> Color(0xFFDDF3E6) to Color(0xFF1B7A43)
        "코넥스" -> Color(0xFFEBE3FA) to Color(0xFF6A3FC0)
        "외감" -> Color(0xFFFCEFD5) to Color(0xFF9A5B00)
        "기타" -> Color(0xFFE8EAED) to Color(0xFF4F5661)
        else -> if (listed) MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
    }
    Box(modifier.background(bg, RoundedCornerShape(5.dp)).padding(horizontal = 7.dp, vertical = 2.dp)) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = fg, fontWeight = FontWeight.Bold)
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

/**
 * 공시 원문 열기. 그 링크를 처리하는 전용 앱(예: DART 앱)이 있으면 그 앱으로, 없으면 앱 안 브라우저로 연다.
 * 브라우저로 새지 않도록 Android 11+ 의 REQUIRE_NON_BROWSER 로 먼저 시도한다.
 */
fun openDisclosure(context: Context, d: io.github.hdlee73.everycorp.data.api.dto.DisclosureDto) {
    val url = d.url.ifBlank { d.mobileUrl }
    if (android.os.Build.VERSION.SDK_INT >= 30) {
        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse(url))
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_REQUIRE_NON_BROWSER)
        if (runCatching { context.startActivity(intent) }.isSuccess) return
    }
    openInAppBrowser(context, url)
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


// ───────── FSS Insights 스타일 공통 요소 ─────────

/** 화면 맨 위 머리말: 큰 제목과 한 줄 설명. */
@Composable
fun LargeTitle(title: String, modifier: Modifier = Modifier, subtitle: String? = null, trailing: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {}) {
    val shape = RoundedCornerShape(bottomStart = 26.dp, bottomEnd = 26.dp)
    Box(
        modifier.fillMaxWidth().clip(shape)
            .background(Brush.verticalGradient(listOf(AppColors.heroStart, AppColors.heroEnd)))
            .drawBehind {
                drawCircle(Color.White.copy(alpha = 0.16f), radius = size.height * 0.95f, center = Offset(size.width * 0.96f, -size.height * 0.15f))
                drawCircle(Color.White.copy(alpha = 0.08f), radius = size.height * 0.5f, center = Offset(size.width * 0.72f, size.height * 1.05f))
            },
    ) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 6.dp, top = 6.dp, bottom = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.headlineMedium, color = AppColors.onHero, maxLines = 2)
                if (subtitle != null) Text(subtitle, Modifier.padding(top = 2.dp), style = MaterialTheme.typography.bodyMedium, color = AppColors.onHero.copy(alpha = 0.88f), maxLines = 2)
            }
            CompositionLocalProvider(LocalContentColor provides AppColors.onHero) { trailing() }
        }
    }
}

/** 상태 표시줄까지 이어지는 그라데이션 상단 바(뒤로가기 + 제목 + 오른쪽 동작). */
@Composable
fun GradientTopBar(title: String, onBack: (() -> Unit)?, modifier: Modifier = Modifier, badge: String? = null, trailing: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {}) {
    Column(modifier.fillMaxWidth().background(Brush.horizontalGradient(listOf(AppColors.heroStart, AppColors.heroEnd)))) {
        Spacer(Modifier.windowInsetsTopHeight(WindowInsets.statusBars))
        Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로", tint = AppColors.onHero) }
            else Spacer(Modifier.width(16.dp))
            // 회사명은 잘리지 않게 두 줄까지 보여 주고, 시장 배지는 이름 아래에 둔다.
            Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge, color = AppColors.onHero, maxLines = 2)
                if (badge != null) Text(
                    badge, Modifier.padding(top = 2.dp).background(AppColors.onHero.copy(alpha = 0.1f), RoundedCornerShape(5.dp)).padding(horizontal = 7.dp, vertical = 1.dp),
                    style = MaterialTheme.typography.labelSmall, color = AppColors.onHero, fontWeight = FontWeight.Bold,
                )
            }
            CompositionLocalProvider(LocalContentColor provides AppColors.onHero) { trailing() }
        }
    }
}

/** 선택한 항목 아래에 굵은 밑줄이 붙는 글자 탭. */
@Composable
fun UnderlineTabs(labels: List<String>, selectedIndex: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val active = MaterialTheme.colorScheme.onBackground
    val inactive = MaterialTheme.colorScheme.onSurfaceVariant
    val accent = AppColors.accent
    Column(modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer)) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(22.dp)) {
            labels.forEachIndexed { i, label ->
                val selected = i == selectedIndex
                val underline = if (selected) accent else Color.Transparent
                Text(
                    label,
                    Modifier
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onSelect(i) }
                        .drawBehind { drawRect(underline, Offset(0f, size.height - 3.dp.toPx()), Size(size.width, 3.dp.toPx())) }
                        .padding(top = 12.dp, bottom = 11.dp),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                    color = if (selected) active else inactive,
                    maxLines = 1,
                )
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

/** 둥근 흰색 검색창. */
@Composable
fun SearchPill(value: String, onValueChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier, focusRequester: androidx.compose.ui.focus.FocusRequester? = null, onSearch: () -> Unit = {}) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        shadowElevation = 2.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        BasicTextField(
            value = value, onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp).padding(start = 14.dp, end = 4.dp).then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier),
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSearch() }),
            decorationBox = { inner ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Filled.Search, contentDescription = null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
                    Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                        if (value.isEmpty()) Text(placeholder, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                        inner()
                    }
                    if (value.isNotEmpty()) IconButton(onClick = { onValueChange("") }, Modifier.size(40.dp)) {
                        Icon(Icons.Filled.Close, contentDescription = "입력 지우기", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            },
        )
    }
}

/** 둥근 알약 모양 칩(필터·바로가기). */
@Composable
fun PillChip(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, selected: Boolean = false) {
    Surface(
        onClick = onClick, modifier = modifier, shape = RoundedCornerShape(50),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.35f) else MaterialTheme.colorScheme.outlineVariant),
    ) {
        Text(
            label, Modifier.padding(horizontal = 14.dp, vertical = 7.dp), style = MaterialTheme.typography.labelLarge, maxLines = 1,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** 기업명 첫 글자를 담은 둥근 아바타. */
@Composable
fun InitialAvatar(name: String, tint: Color, modifier: Modifier = Modifier) {
    Box(modifier.size(42.dp).background(tint.copy(alpha = 0.16f), CircleShape), contentAlignment = Alignment.Center) {
        Text(name.trim().firstOrNull()?.toString().orEmpty(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = tint)
    }
}

/** 구역 사이를 나누는 작은 제목. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier, action: (@Composable androidx.compose.foundation.layout.RowScope.() -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(start = 4.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.secondary)
        action?.invoke(this)
    }
}
