package io.github.hdlee73.everycorp.ui.company

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import android.content.Intent
import android.widget.Toast
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.hdlee73.everycorp.EveryCorpApp
import io.github.hdlee73.everycorp.ui.AppViewModel
import io.github.hdlee73.everycorp.ui.common.CompanyTab
import io.github.hdlee73.everycorp.ui.common.Dest
import io.github.hdlee73.everycorp.ui.common.GradientTopBar
import io.github.hdlee73.everycorp.ui.common.UnderlineTabs
import io.github.hdlee73.everycorp.ui.theme.HeroPalettes
import io.github.hdlee73.everycorp.ui.theme.LocalHeroPalette

/** 탭마다 머리말 색을 달리해(FSS Insights 방식) 어느 탭인지 한눈에 구분되게 한다. */
fun CompanyTab.palette() = when (this) {
    CompanyTab.Overview -> HeroPalettes.Sky
    CompanyTab.Reports -> HeroPalettes.Violet
    CompanyTab.Disclosures -> HeroPalettes.Indigo
    CompanyTab.Stock -> HeroPalettes.Teal
    CompanyTab.Debt -> HeroPalettes.Amber
    CompanyTab.News -> HeroPalettes.Rose
}

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

    val context = LocalContext.current
    val summary = vm.summaryPdf
    LaunchedEffect(summary) {
        when (summary) {
            is PdfUi.Done -> {
                val send = Intent(Intent.ACTION_SEND).setType("application/pdf")
                    .putExtra(Intent.EXTRA_STREAM, summary.uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                context.startActivity(Intent.createChooser(send, "요약 PDF 공유·열기").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                vm.clearSummaryPdf()
            }
            is PdfUi.Failed -> { Toast.makeText(context, summary.message, Toast.LENGTH_LONG).show(); vm.clearSummaryPdf() }
            else -> Unit
        }
    }

    CompositionLocalProvider(LocalHeroPalette provides tab.palette()) {
        Column(Modifier.fillMaxSize().navigationBarsPadding()) {
            GradientTopBar(
                title = corp.corpName,
                onBack = { app.back() },
                badge = corp.badge.ifBlank { if (corp.isListed) "상장" else "외감" },
                trailing = {
                    if (summary is PdfUi.Downloading) {
                        CircularProgressIndicator(Modifier.padding(12.dp).size(24.dp), strokeWidth = 2.dp)
                    } else {
                        IconButton(onClick = { vm.exportSummaryPdf(context.applicationContext) }) {
                            Icon(Icons.Filled.PictureAsPdf, contentDescription = "요약 PDF 만들기")
                        }
                    }
                    IconButton(onClick = app::openSearch) { Icon(Icons.Filled.Search, contentDescription = "기업 검색") }
                    val fav = app.isFavorite(corp)
                    IconButton(onClick = { app.toggleFavorite(corp) }) {
                        Icon(if (fav) Icons.Filled.Star else Icons.Filled.StarBorder, contentDescription = if (fav) "관심기업 해제" else "관심기업 추가")
                    }
                },
            )
            val tabs = CompanyTab.entries
            UnderlineTabs(tabs.map { it.label }, tabs.indexOf(tab), onSelect = { app.selectTab(tabs[it]) })
            when (tab) {
                CompanyTab.Overview -> OverviewTab(vm)
                CompanyTab.Reports -> ReportsTab(vm)
                CompanyTab.Disclosures -> DisclosuresTab(vm)
                CompanyTab.Stock -> StockTab(vm)
                CompanyTab.Debt -> DebtTab(vm)
                CompanyTab.News -> NewsTab(vm)
            }
        }
    }
}
