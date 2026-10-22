package io.github.hdlee73.everycorp.ui.company

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.hdlee73.everycorp.EveryCorpApp
import io.github.hdlee73.everycorp.ui.AppViewModel
import io.github.hdlee73.everycorp.ui.common.Badge
import io.github.hdlee73.everycorp.ui.common.CompanyTab
import io.github.hdlee73.everycorp.ui.common.Dest

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompanyScreen(app: AppViewModel, dest: Dest.Company) {
    val corp = dest.corp
    val vm: CompanyViewModel = viewModel(
        key = "company-${corp.corpCode}",
        factory = viewModelFactory {
            initializer {
                val c = EveryCorpApp.container
                CompanyViewModel(corp, c.repository, c.pdf)
            }
        },
    )
    val tab = dest.current
    LaunchedEffect(corp.corpCode, tab) { vm.ensure(tab) }

    Column(Modifier.fillMaxSize().systemBarsPadding()) {
        TopAppBar(
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(corp.corpName, style = MaterialTheme.typography.titleLarge, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
                    Badge(corp.badge.ifBlank { if (corp.isListed) "상장" else "외감" }, listed = corp.isListed, modifier = Modifier.padding(start = 8.dp))
                }
            },
            navigationIcon = { IconButton(onClick = { app.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로") } },
            actions = {
                val fav = app.isFavorite(corp)
                IconButton(onClick = { app.toggleFavorite(corp) }) {
                    Icon(if (fav) Icons.Filled.Star else Icons.Filled.StarBorder, contentDescription = if (fav) "관심기업 해제" else "관심기업 추가")
                }
            },
        )
        val tabs = CompanyTab.entries
        ScrollableTabRow(selectedTabIndex = tabs.indexOf(tab), edgePadding = 8.dp, containerColor = MaterialTheme.colorScheme.background) {
            tabs.forEach { t -> Tab(selected = t == tab, onClick = { app.selectTab(t) }, text = { Text(t.label, maxLines = 1) }) }
        }
        when (tab) {
            CompanyTab.Overview -> OverviewTab(vm)
            CompanyTab.Reports -> ReportsTab(vm)
            CompanyTab.Disclosures -> DisclosuresTab(vm)
            CompanyTab.Stock -> StockTab(vm)
            CompanyTab.Debt -> DebtTab(vm)
        }
    }
}
