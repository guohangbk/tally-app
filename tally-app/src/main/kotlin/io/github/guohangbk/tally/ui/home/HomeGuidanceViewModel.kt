package io.github.guohangbk.tally.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.guohangbk.tally.data.ai.AssistantEntry
import io.github.guohangbk.tally.di.AppGraph
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 首页只浮两种气泡：教手势的 G-01，和小莉第一次配通后的 G-02。 */
enum class HomeCoach { FanGuide, AssistantReady }

data class HomeGuidanceUiState(
    val coach: HomeCoach? = null,
    val sheetOpen: Boolean = false,
    val assistantEntry: AssistantEntry = AssistantEntry.Settings,
)

/**
 * 首页的一次性引导。
 *
 * 首次进首页不浮：欢迎/导入刚收尾，信息已经够多。第二次回来才教三条手势（G-01），
 * 且只教一次；小莉第一次配置成功后的那次回首页，换成 G-02 先说「已经就绪」。
 * 调用方每次进页只叫一次 [onHomeShown]，进页次数由偏好里的计数累积。
 */
class HomeGuidanceViewModel(private val graph: AppGraph) : ViewModel() {
    private val coach = MutableStateFlow<HomeCoach?>(null)
    private val sheetOpen = MutableStateFlow(false)

    val state: StateFlow<HomeGuidanceUiState> = combine(
        coach,
        sheetOpen,
        graph.aiConfigRepository.observeAvailability().map { it.entry },
    ) { current, open, entry ->
        HomeGuidanceUiState(coach = current, sheetOpen = open, assistantEntry = entry)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeGuidanceUiState())

    fun onHomeShown() {
        viewModelScope.launch {
            val visit = graph.preferences.registerHomeVisit()
            val entry = graph.aiConfigRepository.availability().entry
            // 刚配通的那一次优先说「小莉已经就绪」：手势可以以后从「更多手势」再学。
            if (entry == AssistantEntry.Chat && graph.preferences.consumeAssistantReadyHint()) {
                coach.value = HomeCoach.AssistantReady
                return@launch
            }
            if (visit >= 2 && !graph.preferences.fanGuideSeen()) {
                // 露过就算看过：自动收起、点走、直接离开都不该让它在下次回来时重播。
                graph.preferences.markFanGuideSeen()
                coach.value = HomeCoach.FanGuide
            }
        }
    }

    fun dismissCoach() {
        coach.value = null
    }

    fun openMoreGestures() {
        coach.value = null
        sheetOpen.value = true
    }

    fun closeMoreGestures() {
        sheetOpen.value = false
    }
}
