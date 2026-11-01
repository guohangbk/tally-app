package io.github.guohangbk.tally.ui.assistant

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.guohangbk.tally.data.ai.AiAnalysisOutcome
import io.github.guohangbk.tally.data.ai.AiAvailability
import io.github.guohangbk.tally.di.AppGraph
import io.github.guohangbk.tally.domain.model.AiReport
import io.github.guohangbk.tally.domain.model.StatsGranularity
import io.github.guohangbk.tally.domain.model.StatsRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 统计页那颗胶囊的状态。全是初始值时就代表还没点过——
 * 这一屏在用户点之前不该知道小莉存不存在（AC-S-1）。
 */
data class AssistantAnalysisUiState(
    val report: AiReport? = null,
    val working: Boolean = false,
    val availability: AiAvailability? = null,
    val notice: String? = null,
    val error: String? = null,
)

/**
 * 点一次算一次：[open] 之前没有任何查询、没有任何 AI 文案（AC-S-1）；
 * [cancel] 收掉正在跑的那次计算，不留半截结果（AC-S-3）。
 * 报告按 (账本, 周期, 输入摘要哈希) 复用，数据没变就不二次计费（AC-S-2）。
 */
class AssistantAnalysisViewModel(private val graph: AppGraph) : ViewModel() {
    private val state = MutableStateFlow(AssistantAnalysisUiState())
    val uiState: StateFlow<AssistantAnalysisUiState> = state.asStateFlow()

    private var job: Job? = null
    /** 统计页那一次查询的同一份请求：换区间/换账本后再点，报告必须跟着换。 */
    private var request: StatsRequest = StatsRequest(
        ledgerId = null,
        periodStart = 0L,
        granularity = StatsGranularity.MONTH,
    )

    /** 弹层打开：读可用性与缓存，命中就直接显示，没命中且模型可用才发起这一次计算。 */
    fun open(request: StatsRequest) {
        this.request = request
        job?.cancel()
        job = viewModelScope.launch(Dispatchers.IO) {
            try {
                val availability = graph.aiGateway.availability()
                val cached = graph.aiAnalysisService.cached(request)
                state.update { it.copy(availability = availability, report = cached, notice = null, error = null) }
                if (cached == null && availability.showsEntry) runAnalysis(force = false)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                state.update { it.copy(error = failure.message ?: "这个周期的数据读不出来") }
            }
        }
    }

    fun analyze(force: Boolean) {
        if (state.value.working) return
        job = viewModelScope.launch(Dispatchers.IO) { runAnalysis(force) }
    }

    /** 生成中关掉弹层就是取消：不保留半截结果，也不留「正在分析」的假状态（AC-S-3）。 */
    fun cancel() {
        job?.cancel()
        job = null
        state.update { it.copy(working = false) }
    }

    private suspend fun runAnalysis(force: Boolean) {
        state.update { it.copy(working = true, error = null, notice = null) }
        try {
            val outcome = graph.aiAnalysisService.analyze(
                request = request,
                includeNotes = false,
                force = force,
            )
            when (outcome) {
                is AiAnalysisOutcome.Generated -> state.update { it.copy(working = false, report = outcome.report) }
                is AiAnalysisOutcome.FromCache -> state.update { it.copy(working = false, report = outcome.report) }
                is AiAnalysisOutcome.NothingToAnalyze -> state.update { it.copy(working = false, notice = outcome.reason) }
                is AiAnalysisOutcome.Failed -> state.update { it.copy(working = false, error = outcome.reason) }
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            state.update { it.copy(working = false, error = failure.message ?: "小莉暂时联系不上") }
        }
    }
}
