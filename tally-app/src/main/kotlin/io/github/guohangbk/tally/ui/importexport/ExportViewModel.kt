package io.github.guohangbk.tally.ui.importexport

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.guohangbk.tally.data.repository.LedgerListItem
import io.github.guohangbk.tally.di.AppGraph
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ExportUiState(
    val ledgers: List<LedgerListItem> = emptyList(),
)

class ExportViewModel(private val graph: AppGraph) : ViewModel() {
    val state: StateFlow<ExportUiState> = graph.ledgerRepository.items
        .map { ExportUiState(ledgers = it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ExportUiState())

    init {
        viewModelScope.launch {
            graph.ledgerRepository.bootstrap()
        }
    }
}
