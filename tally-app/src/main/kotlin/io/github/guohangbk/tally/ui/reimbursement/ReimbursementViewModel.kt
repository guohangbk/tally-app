package io.github.guohangbk.tally.ui.reimbursement

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.guohangbk.tally.data.repository.LedgerListItem
import io.github.guohangbk.tally.di.AppGraph
import io.github.guohangbk.tally.domain.model.BillListItem
import io.github.guohangbk.tally.domain.model.ReimburseStatus
import io.github.guohangbk.tally.domain.model.ReimbursementSnapshot
import io.github.guohangbk.tally.domain.model.TallyError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class ReimburseTab { PENDING, DONE }

data class ReimbursementUiState(
    val ledgerId: String? = null,
    val ledgers: List<LedgerListItem> = emptyList(),
    val snapshot: ReimbursementSnapshot = ReimbursementSnapshot(),
    val enabled: Boolean = false,
    val busy: Boolean = false,
    val notice: String? = null,
    val error: String? = null,
    val tab: ReimburseTab = ReimburseTab.PENDING,
    val selectionMode: Boolean = false,
    val selected: List<BillListItem> = emptyList(),
    val showConfirm: Boolean = false,
) {
    val pendingMinor: Long get() = snapshot.pendingMinor
    val doneMinor: Long get() = snapshot.doneMinor
    val selectedMinor: Long get() = selected.sumOf { it.bill.amountMinor }
    /** 金额分组沿用用户在设置里的偏好。 */
    val settingsGrouping: Boolean get() = true
}

/**
 * P14 报销管理：纯状态流，只改状态与报销日期，不生成账单、不改账单金额。
 * 状态本身会改统计归属：待报销照常计入支出，确认报销后那几笔不再计入本月支出。
 * 「报销记录」（is_reimbursement）是另一种账，统计层已排除，不在这一页出现。
 */
class ReimbursementViewModel(private val graph: AppGraph) : ViewModel() {
    private val ledgerId = MutableStateFlow<String?>(null)
    private val local = MutableStateFlow(ReimbursementUiState())

    private val snapshot = ledgerId.flatMapLatest { graph.reimbursementRepository.observe(it) }

    val state: StateFlow<ReimbursementUiState> = combine(
        combine(snapshot, ledgerId, graph.ledgerRepository.items) { snap, ledger, ledgers ->
            Triple(snap, ledger, ledgers.filter { !it.ledger.isArchived })
        },
        combine(local, graph.preferences.settings) { current, settings -> current to settings.reimburseEnabled },
    ) { (snap, ledger, ledgers), (current, enabled) ->
        current.copy(
            ledgerId = ledger,
            ledgers = ledgers,
            snapshot = snap,
            enabled = enabled,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReimbursementUiState())

    init {
        viewModelScope.launch {
            graph.ledgerRepository.bootstrap()
            ledgerId.value = graph.billRepository.defaultLedgerId()
        }
    }

    fun selectLedger(id: String?) {
        ledgerId.value = id
        exitSelection()
    }

    fun selectTab(tab: ReimburseTab) {
        local.value = local.value.copy(tab = tab)
        exitSelection()
    }

    /** 长按某一行唤出选择态，并把这一行勾上——勾选框不是默认摆着的，是长按出来的。 */
    fun enterSelection(item: BillListItem) {
        val current = local.value.selected
        local.value = local.value.copy(
            selectionMode = true,
            selected = if (current.any { it.bill.id == item.bill.id }) current else current + item,
        )
    }

    fun exitSelection() {
        local.value = local.value.copy(selectionMode = false, selected = emptyList())
    }

    fun toggle(item: BillListItem) {
        val current = local.value.selected
        local.value = local.value.copy(
            selected = if (current.any { it.bill.id == item.bill.id }) {
                current.filterNot { it.bill.id == item.bill.id }
            } else {
                current + item
            },
        )
    }

    fun selectAllPending() {
        local.value = local.value.copy(selectionMode = true, selected = state.value.snapshot.pending)
    }

    fun openConfirm() {
        if (local.value.selected.isNotEmpty()) local.value = local.value.copy(showConfirm = true)
    }

    fun closeConfirm() {
        local.value = local.value.copy(showConfirm = false)
    }

    /** 单行确认：与批量确认同一条口径，只是不经过确认弹层。 */
    fun confirmOne(item: BillListItem) {
        runAction(null) {
            val changed = graph.reimbursementRepository.setStatusMany(listOf(item.bill.id), ReimburseStatus.DONE)
            exitSelection()
            "已标记 $changed 笔为已报销 · 本月支出已减去这一笔"
        }
    }

    /** 确认报销：整批转已报销并记日期，之后这些账不再计入本月支出。 */
    fun confirmReimburse() {
        val ids = local.value.selected.map { it.bill.id }
        if (ids.isEmpty()) return
        runAction(null) {
            val changed = graph.reimbursementRepository.setStatusMany(ids, ReimburseStatus.DONE)
            local.value = local.value.copy(
                selectionMode = false,
                selected = emptyList(),
                showConfirm = false,
                tab = ReimburseTab.DONE,
            )
            "已标记 $changed 笔为已报销 · 本月支出已减去这些账"
        }
    }

    fun undo(item: BillListItem) = runAction("已改回待报销 · 原报销日期仍留档") {
        graph.reimbursementRepository.setStatus(item.bill.id, ReimburseStatus.PENDING)
    }

    fun clearNotice() {
        local.value = local.value.copy(notice = null, error = null)
    }

    private fun runAction(successMessage: String?, block: suspend () -> Unit) =
        runAction({ block(); successMessage })

    private fun runAction(block: suspend () -> String?) {
        viewModelScope.launch {
            local.value = local.value.copy(busy = true, error = null)
            try {
                val notice = block()
                local.value = local.value.copy(busy = false, notice = notice)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: TallyError) {
                local.value = local.value.copy(busy = false, error = error.message)
            } catch (error: Exception) {
                local.value = local.value.copy(busy = false, error = error.message ?: "操作没有完成")
            }
        }
    }
}
