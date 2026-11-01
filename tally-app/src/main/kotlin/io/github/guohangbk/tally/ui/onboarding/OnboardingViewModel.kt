package io.github.guohangbk.tally.ui.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.guohangbk.tally.di.AppGraph
import io.github.guohangbk.tally.domain.model.TallyError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class OnboardingUiState(
    val ledgerName: String? = null,
    val preparing: Boolean = true,
    val starting: Boolean = false,
    val error: String? = null,
) {
    val busy: Boolean get() = preparing || starting

    /** 默认账本就绪后才放行「直接从零开始」，避免引导完成后首页没有账本。 */
    val canStart: Boolean get() = ledgerName != null && !busy
}

class OnboardingViewModel(private val graph: AppGraph) : ViewModel() {
    private val ledgerNameState = MutableStateFlow<String?>(null)
    private val preparingState = MutableStateFlow(false)
    private val startingState = MutableStateFlow(false)
    private val errorState = MutableStateFlow<String?>(null)

    val state: StateFlow<OnboardingUiState> = combine(
        ledgerNameState,
        preparingState,
        startingState,
        errorState,
    ) { ledgerName, preparing, starting, error ->
        OnboardingUiState(
            ledgerName = ledgerName,
            preparing = preparing,
            starting = starting,
            error = error,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), OnboardingUiState())

    init {
        prepareWorkspace()
    }

    /** 默认账本 + 默认分类树；仓储侧幂等，失败后点「重新准备」是安全的。 */
    fun prepareWorkspace() {
        runWork(preparingState) {
            ledgerNameState.value = graph.ledgerRepository.bootstrap().name
        }
    }

    /** 先把引导完成标记落库，再交给导航；落库失败就留在本页就地提示。 */
    fun startFromScratch(onReady: () -> Unit) {
        runWork(startingState, onSuccess = onReady) {
            if (ledgerNameState.value == null) {
                ledgerNameState.value = graph.ledgerRepository.bootstrap().name
            }
            graph.preferences.completeOnboarding()
        }
    }

    fun dismissError() {
        errorState.value = null
    }

    private fun runWork(
        busy: MutableStateFlow<Boolean>,
        onSuccess: () -> Unit = {},
        work: suspend () -> Unit,
    ) {
        if (busy.value) return
        viewModelScope.launch {
            busy.value = true
            errorState.value = null
            try {
                work()
                onSuccess()
            } catch (tallyError: TallyError) {
                errorState.value = tallyError.message
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                errorState.value = failure.message ?: "这一步没有完成，请重试。"
            } finally {
                busy.value = false
            }
        }
    }
}
