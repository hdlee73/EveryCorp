package io.github.hdlee73.everycorp.ui.common

import io.github.hdlee73.everycorp.data.api.dto.CorpItemDto

enum class CompanyTab(val label: String) {
    Overview("기업개황"),
    Reports("감사보고서"),
    Disclosures("공시"),
    Stock("주가현황"),
    Debt("채권 등 발행잔액"),
}

sealed interface Dest {
    data object Home : Dest
    data object Info : Dest

    /** [tabs]는 이 기업 화면 안에서 방문한 탭 기록(맨 뒤가 현재 탭). 뒤로가기는 직전 탭으로 돌아간다. */
    data class Company(val corp: CorpItemDto, val tabs: List<CompanyTab> = listOf(CompanyTab.Overview)) : Dest {
        val current: CompanyTab get() = tabs.last()
    }
}

/**
 * 화면 이동 기록. 안드로이드 뒤로가기(버튼·제스처)는 [back]이 돌려주는 직전 상태로 이동하고,
 * null(= 더 돌아갈 곳이 없는 첫 화면)일 때만 앱이 종료된다.
 */
data class NavState(val stack: List<Dest> = listOf(Dest.Home)) {
    val top: Dest get() = stack.last()

    fun openCompany(corp: CorpItemDto): NavState = copy(stack = stack + Dest.Company(corp))

    /** 어느 화면에서든 한 번에 첫 화면(검색)으로. 이미 첫 화면이면 그대로. */
    fun goHome(): NavState = if (stack.size == 1) this else NavState()

    fun openInfo(): NavState = if (top == Dest.Info) this else copy(stack = stack + Dest.Info)

    fun selectTab(tab: CompanyTab): NavState {
        val t = top as? Dest.Company ?: return this
        if (t.current == tab) return this
        // 이미 방문한 탭이면 기록에서 빼고 맨 뒤로 보낸다(뒤로가기 시 같은 탭이 연속되지 않게).
        val tabs = t.tabs.filterNot { it == tab } + tab
        return copy(stack = stack.dropLast(1) + t.copy(tabs = tabs))
    }

    fun back(): NavState? {
        val t = top
        if (t is Dest.Company && t.tabs.size > 1) return copy(stack = stack.dropLast(1) + t.copy(tabs = t.tabs.dropLast(1)))
        if (stack.size > 1) return copy(stack = stack.dropLast(1))
        return null
    }

    val canGoBack: Boolean get() = back() != null
}
