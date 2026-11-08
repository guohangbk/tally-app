package io.github.guohangbk.tally.ui.ledger

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.guohangbk.tally.data.preferences.TallySettings
import io.github.guohangbk.tally.data.repository.DeleteDisposition
import io.github.guohangbk.tally.data.repository.LedgerListItem
import io.github.guohangbk.tally.di.AppGraph
import io.github.guohangbk.tally.domain.model.TallyError
import io.github.guohangbk.tally.ui.design.IconLibrary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class LedgerDialogKind {
    CREATE,
    EDIT,
    DELETE,
}

data class LedgerDialog(
    val kind: LedgerDialogKind,
    val ledgerId: String?,
)

data class LedgerUiState(
    val loaded: Boolean = false,
    val items: List<LedgerListItem> = emptyList(),
    val totalBillCount: Int = 0,
    val settings: TallySettings = TallySettings.DEFAULT,
    val dialog: LedgerDialog? = null,
    val error: String? = null,
) {
    val active: List<LedgerListItem> get() = items.filter { !it.ledger.isArchived }
    val archived: List<LedgerListItem> get() = items.filter { it.ledger.isArchived }

    fun itemOf(ledgerId: String?): LedgerListItem? =
        ledgerId?.let { id -> items.firstOrNull { it.ledger.id == id } }
}

class LedgerViewModel(private val graph: AppGraph) : ViewModel() {
    private val dialogState = MutableStateFlow<LedgerDialog?>(null)
    private val errorState = MutableStateFlow<String?>(null)

    /** 仓储的 combine 块内同步读取聚合查询，Room 禁止主线程访问，整条上游必须在 IO 上跑。 */
    private val ledgerItems = graph.ledgerRepository.items.flowOn(Dispatchers.IO)

    val state: StateFlow<LedgerUiState> = combine(
        combine(ledgerItems, graph.database.billDao().observeTotalCount(), graph.preferences.settings) {
            items, totalBillCount, settings -> Triple(items, totalBillCount, settings)
        },
        dialogState,
        errorState,
    ) { (items, totalBillCount, settings), dialog, error ->
        LedgerUiState(
            loaded = true,
            items = items,
            totalBillCount = totalBillCount,
            settings = settings,
            dialog = dialog,
            error = error,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LedgerUiState())

    init {
        runOperation { graph.ledgerRepository.bootstrap() }
    }

    fun openDialog(kind: LedgerDialogKind, ledgerId: String? = null) {
        errorState.value = null
        dialogState.value = LedgerDialog(kind, ledgerId)
    }

    fun dismissDialog() {
        dialogState.value = null
        errorState.value = null
    }

    fun dismissError() {
        errorState.value = null
    }

    /** 落库前过校验器：账本图标只认图标库键域，不落首字/emoji 兜底。 */
    fun create(name: String, iconKey: String) {
        if (!IconLibrary.isKnown(iconKey)) {
            errorState.value = "图标不在图标库里，请从图标库选择"
            return
        }
        runOperation(onSaved = ::dismissDialog) {
            graph.ledgerRepository.create(name = name, iconKey = iconKey)
        }
    }

    /** 编辑入口一次改三样：名称、图标与默认身份。图标只认图标库键域。 */
    fun update(ledgerId: String, name: String, iconKey: String, makeDefault: Boolean) {
        if (!IconLibrary.isKnown(iconKey)) {
            errorState.value = "图标不在图标库里，请从图标库选择"
            return
        }
        runOperation(onSaved = ::dismissDialog) {
            graph.ledgerRepository.updateProfile(ledgerId, name, iconKey)
            if (makeDefault) graph.ledgerRepository.setDefault(ledgerId)
        }
    }

    fun setArchived(ledgerId: String, archived: Boolean) {
        runOperation { graph.ledgerRepository.setArchived(ledgerId, archived) }
    }

    /** 处置方式一律交给仓储判定，UI 不自己放行。 */
    fun delete(ledgerId: String, disposition: DeleteDisposition) {
        runOperation(onSaved = ::dismissDialog) {
            graph.ledgerRepository.delete(ledgerId, disposition)
        }
    }

    private fun runOperation(onSaved: () -> Unit = {}, operation: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                operation()
                onSaved()
            } catch (tallyError: TallyError) {
                errorState.value = tallyError.message
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                errorState.value = failure.message ?: "操作没有完成，请稍后重试"
            }
        }
    }
}
