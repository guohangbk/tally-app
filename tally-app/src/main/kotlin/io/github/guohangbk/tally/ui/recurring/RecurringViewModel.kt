package io.github.guohangbk.tally.ui.recurring

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.guohangbk.tally.data.db.entity.CategoryEntity
import io.github.guohangbk.tally.data.db.entity.RecurringRuleEntity
import io.github.guohangbk.tally.data.repository.LedgerListItem
import io.github.guohangbk.tally.di.AppGraph
import io.github.guohangbk.tally.domain.formatAmount
import io.github.guohangbk.tally.domain.model.BillType
import io.github.guohangbk.tally.domain.model.CategoryType
import io.github.guohangbk.tally.domain.model.RecurrenceEnd
import io.github.guohangbk.tally.domain.model.RecurrenceSpec
import io.github.guohangbk.tally.domain.model.RecurrenceUnit
import io.github.guohangbk.tally.domain.model.RecurringDraft
import io.github.guohangbk.tally.domain.model.RecurringPeriodRow
import io.github.guohangbk.tally.domain.model.RecurringRuleView
import io.github.guohangbk.tally.domain.model.RecurringStatus
import io.github.guohangbk.tally.domain.model.TallyError
import io.github.guohangbk.tally.domain.parseAmount
import io.github.guohangbk.tally.domain.schedule.RecurrenceSchedule
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class RecurringUiState(
    val items: List<RecurringRuleView> = emptyList(),
    val pendingTotal: Int = 0,
    val pendingAmountMinor: Long = 0,
    val busy: Boolean = false,
    val notice: String? = null,
    val error: String? = null,
)

/** 新建周期任务的表单状态；与列表状态分开，避免互相触发重组。 */
data class RecurringFormState(
    val loaded: Boolean = false,
    /** 非空表示在改一条已有规则，而不是新建。 */
    val ruleId: String? = null,
    val name: String = "",
    val type: BillType = BillType.EXPENSE,
    val amountText: String = "",
    val ledgerId: String? = null,
    val ledgers: List<LedgerListItem> = emptyList(),
    val categories: List<CategoryEntity> = emptyList(),
    val categoryL1Id: String? = null,
    val categoryL2Id: String? = null,
    val spec: RecurrenceSpec = RecurrenceSpec(RecurrenceUnit.MONTHLY, dayOfMonth = 1),
    val startAt: Long = System.currentTimeMillis(),
    val endMode: EndMode = EndMode.NEVER,
    val endDate: Long? = null,
    val endCount: String = "12",
    val note: String = "",
    val error: String? = null,
    val saving: Boolean = false,
    val saved: Boolean = false,
) {
    val amountMinor: Long? get() = parseAmount(amountText)
    val canSave: Boolean
        get() = amountMinor != null && categoryL1Id != null && ledgerId != null && name.isNotBlank()

    /** 启用前预览的 3 期，和真正生成走同一个策略，避免「预览好看、落库两样」。 */
    val preview: List<Long>
        get() = RecurrenceSchedule.previewOccurrences(
            spec = spec,
            startAt = startAt,
            endAt = if (endMode == EndMode.ON_DATE) endDate else null,
            maxPeriods = if (endMode == EndMode.AFTER_COUNT) endCount.toIntOrNull() else null,
            count = PREVIEW_PERIODS,
        )
}

private const val PREVIEW_PERIODS = 3

enum class EndMode { NEVER, ON_DATE, AFTER_COUNT }

/**
 * 「明细」弹层的整体状态：规则 + 已生成的期次。
 *
 * [loading] 期间不把空列表交给界面——否则弹层第一帧会先画一句「还没有生成过账单」，
 * 查询回来再跳成几十行，就是那个看得见的闪。空态只代表「真的没有」，不代表「还没查」。
 */
data class RecurringDetailState(
    val view: RecurringRuleView,
    val periods: List<RecurringPeriodRow> = emptyList(),
    val loading: Boolean = false,
)

class RecurringViewModel(private val graph: AppGraph) : ViewModel() {
    private val local = MutableStateFlow(RecurringUiState())

    /**
     * 待补笔数直接从规则列表派生：新建任务、补记、暂停都会让 Flow 重新发射，
     * 不需要额外的刷新调用，也不会出现「卡片显示待补 1 但顶部横幅没有」的错位。
     */
    val state: StateFlow<RecurringUiState> = combine(
        graph.recurringRepository.items,
        local,
    ) { items, current ->
        current.copy(
            items = items,
            pendingTotal = items.sumOf { it.pendingCount },
            pendingAmountMinor = items.sumOf { it.rule.amountMinor * it.pendingCount },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RecurringUiState())

    /**
     * 「明细」弹层：当前看的是哪一条任务，以及它已经生成的那些期。
     * 打开时查一次即可——这一页的规则不会在自己脸上被改，改完关掉再开就是新的。
     *
     * 规则和数据放在同一个 StateFlow 里：先置 loading=true 再异步取，取回后一次性
     * 换掉。界面据此在加载中只画骨架，不会把「空」误读成「没有账单」。
     */
    private val detailState = MutableStateFlow<RecurringDetailState?>(null)
    val detail: StateFlow<RecurringDetailState?> = detailState

    fun openDetail(view: RecurringRuleView) {
        detailState.value = RecurringDetailState(view, loading = true)
        viewModelScope.launch {
            val rows = graph.billRepository.recurringPeriods(view.rule.id)
            detailState.value = RecurringDetailState(view, periods = alignPeriods(rows, view.rule.executedPeriods))
        }
    }

    fun closeDetail() {
        detailState.value = null
    }

    fun catchUpAll() = runAction { graph.automationService.executeAll().generated to "已补记" }

    fun catchUp(ruleId: String) = runAction {
        graph.automationService.executeRecurring(ruleId) to "已补记"
    }

    fun setStatus(id: String, status: RecurringStatus) = runAction(
        successMessage = when (status) {
            RecurringStatus.PAUSED -> "任务已暂停，已生成的账单保留"
            RecurringStatus.ACTIVE -> "任务已恢复，暂停期间不补账"
            RecurringStatus.FINISHED -> "任务已结束，已生成的账单保留"
        },
    ) {
        graph.recurringRepository.setStatus(id, status)
        null
    }

    fun delete(id: String) = runAction(successMessage = "任务已删除，已生成的账单保留") {
        graph.recurringRepository.delete(id)
        null
    }

    fun clearNotice() {
        local.value = local.value.copy(notice = null, error = null)
    }

    /**
     * 统一动作壳：成功时用动作自己的文案，补账类动作带上笔数。
     * [block] 返回 null 表示不需要追加笔数说明。
     */
    private fun runAction(
        successMessage: String = "已完成",
        block: suspend () -> Pair<Int, String>?,
    ) {
        viewModelScope.launch {
            local.value = local.value.copy(busy = true, error = null)
            try {
                val result = block()
                local.value = local.value.copy(
                    busy = false,
                    notice = result?.let { (count, prefix) -> "$prefix $count 笔账单" } ?: successMessage,
                )
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

/**
 * 明细按排期期号对齐：删掉的那一期也占一行（灰着），后面的期号才不会凭空往前跳。
 * 回收站里还留着的行有真实日期；回收站也清掉的期次只剩期号。
 * 期号为 0 的老数据对不上编号，原样返回，不硬补。
 */
private fun alignPeriods(rows: List<RecurringPeriodRow>, executedPeriods: Int): List<RecurringPeriodRow> {
    if (executedPeriods <= 0 || rows.any { it.index <= 0 }) return rows
    val byIndex = rows.associateBy { it.index }
    val top = maxOf(executedPeriods, rows.maxOfOrNull { it.index } ?: 0)
    return (top downTo 1).map { index ->
        byIndex[index] ?: RecurringPeriodRow(
            billId = "",
            index = index,
            occurredAt = 0L,
            amountMinor = 0L,
            isIncome = false,
            note = null,
            deleted = true,
        )
    }
}

/** 新建 / 修改周期任务的独立表单 ViewModel。[ruleId] 非空时把已有规则填进来。 */
class RecurringFormViewModel(
    private val graph: AppGraph,
    private val ruleId: String? = null,
) : ViewModel() {
    private val local = MutableStateFlow(RecurringFormState(ruleId = ruleId))

    val state: StateFlow<RecurringFormState> = combine(
        local,
        graph.ledgerRepository.items,
    ) { current, ledgers ->
        current.copy(ledgers = ledgers.filter { !it.ledger.isArchived })
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RecurringFormState(ruleId = ruleId))

    init {
        viewModelScope.launch {
            graph.ledgerRepository.bootstrap()
            if (ruleId != null) {
                prefill(ruleId)
                return@launch
            }
            val ledgerId = graph.billRepository.defaultLedgerId()
            local.value = local.value.copy(
                loaded = true,
                ledgerId = ledgerId,
                categories = graph.billRepository.activeCategories(CategoryType.EXPENSE),
                name = "",
            )
        }
    }

    private suspend fun prefill(id: String) {
        val rule = graph.recurringRepository.find(id)
        if (rule == null) {
            local.value = local.value.copy(loaded = true, error = "这条周期任务已经不在了")
            return
        }
        val type = runCatching { BillType.valueOf(rule.type) }.getOrDefault(BillType.EXPENSE)
        val categories = graph.billRepository.activeCategories(
            if (type == BillType.INCOME) CategoryType.INCOME else CategoryType.EXPENSE,
        )
        local.value = local.value.copy(
            loaded = true,
            ruleId = id,
            name = rule.name,
            type = type,
            amountText = formatAmount(rule.amountMinor, grouping = false),
            ledgerId = rule.ledgerId,
            categories = categories,
            categoryL1Id = rule.categoryL1Id,
            categoryL2Id = rule.categoryL2Id,
            spec = RecurrenceSpec(
                unit = runCatching { RecurrenceUnit.valueOf(rule.frequency) }.getOrDefault(RecurrenceUnit.MONTHLY),
                dayOfMonth = rule.dayOfMonth,
                dayOfWeek = rule.dayOfWeek,
                monthOfYear = rule.monthOfYear,
            ),
            startAt = rule.startAt,
            endMode = when {
                rule.endAt != null -> EndMode.ON_DATE
                rule.maxPeriods != null -> EndMode.AFTER_COUNT
                else -> EndMode.NEVER
            },
            endDate = rule.endAt,
            endCount = (rule.maxPeriods ?: 12).toString(),
            note = rule.note.orEmpty(),
        )
    }

    fun setName(value: String) = update { copy(name = value) }

    fun setAmount(value: String) = update { copy(amountText = value) }

    fun setNote(value: String) = update { copy(note = value) }

    fun setType(type: BillType) {
        viewModelScope.launch {
            val categoryType = if (type == BillType.INCOME) CategoryType.INCOME else CategoryType.EXPENSE
            local.value = local.value.copy(
                type = type,
                categoryL1Id = null,
                categoryL2Id = null,
                categories = graph.billRepository.activeCategories(categoryType),
            )
        }
    }

    fun setLedger(id: String) = update { copy(ledgerId = id) }

    fun selectCategory(l1Id: String, l2Id: String?) = update { copy(categoryL1Id = l1Id, categoryL2Id = l2Id) }

    fun setUnit(unit: RecurrenceUnit) = update {
        copy(spec = spec.copy(unit = unit))
    }

    fun setDayOfMonth(day: Int) = update { copy(spec = spec.copy(dayOfMonth = day.coerceIn(1, 31))) }

    fun setDayOfWeek(day: Int) = update { copy(spec = spec.copy(dayOfWeek = day.coerceIn(1, 7))) }

    fun setMonthOfYear(month: Int) = update { copy(spec = spec.copy(monthOfYear = month.coerceIn(1, 12))) }

    fun setStartAt(epochMillis: Long) = update { copy(startAt = epochMillis) }

    /** 切到「截止日期」时先把默认值填成开始日，界面上就不会出现一个点不开的「未设置」。 */
    fun setEndMode(mode: EndMode) = update {
        copy(endMode = mode, endDate = if (mode == EndMode.ON_DATE) endDate ?: startAt else endDate)
    }

    fun setEndDate(epochMillis: Long) = update { copy(endDate = epochMillis) }

    fun setEndCount(value: String) = update { copy(endCount = value.filter(Char::isDigit)) }

    fun clearError() = update { copy(error = null) }

    fun save() {
        val current = local.value
        val amount = current.amountMinor
        val ledgerId = current.ledgerId
        when {
            current.name.isBlank() -> return update { copy(error = "给任务起个名字，例如「房租」") }
            amount == null -> return update { copy(error = "请输入有效金额") }
            ledgerId == null -> return update { copy(error = "请先设置默认账本") }
            current.categoryL1Id == null -> return update { copy(error = "请选择分类") }
        }
        val end = when (current.endMode) {
            EndMode.NEVER -> RecurrenceEnd.Never
            EndMode.ON_DATE -> RecurrenceEnd.OnDate(current.endDate ?: current.startAt)
            EndMode.AFTER_COUNT -> RecurrenceEnd.AfterCount(current.endCount.toIntOrNull() ?: 12)
        }
        viewModelScope.launch {
            update { copy(saving = true, error = null) }
            try {
                val draft = RecurringDraft(
                    name = current.name,
                    ledgerId = ledgerId,
                    categoryL1Id = current.categoryL1Id,
                    categoryL2Id = current.categoryL2Id,
                    type = current.type,
                    amountMinor = amount ?: 0L,
                    note = current.note,
                    spec = current.spec,
                    startAt = current.startAt,
                    end = end,
                )
                // 已有规则走 update：已生成的账单不动，下次执行按新规则重算。
                if (current.ruleId != null) {
                    graph.recurringRepository.update(current.ruleId, draft)
                } else {
                    graph.recurringRepository.create(draft)
                }
                update { copy(saving = false, saved = true) }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: TallyError) {
                update { copy(saving = false, error = error.message) }
            } catch (error: Exception) {
                update { copy(saving = false, error = error.message ?: "保存失败，请重试") }
            }
        }
    }

    private fun update(block: RecurringFormState.() -> RecurringFormState) {
        local.value = local.value.block()
    }
}

/** 列表页需要展示「下次执行」的格式，放在一处避免各页各写一份。 */
fun RecurringRuleEntity.nextDueText(): String =
    io.github.guohangbk.tally.ui.common.formatDate(nextDueAt).takeLast(5)
