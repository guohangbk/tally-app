package io.github.guohangbk.tally.ui.ledger

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.guohangbk.tally.data.preferences.TallySettings
import io.github.guohangbk.tally.data.repository.LedgerOverviewCore
import io.github.guohangbk.tally.data.repository.OverviewStats
import io.github.guohangbk.tally.di.AppGraph
import io.github.guohangbk.tally.domain.Period
import io.github.guohangbk.tally.domain.PeriodRange
import io.github.guohangbk.tally.domain.model.BillListItem
import io.github.guohangbk.tally.domain.model.CategoryTotal
import io.github.guohangbk.tally.domain.model.TallyError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 概览卡下方的四张预设模板。全是固定查询，不经过 AI，也不跳搜索页。 */
enum class OverviewQuestion(val chip: String) {
    NET("收入和结余"),
    LARGEST("最大一笔"),
    MONTHLY("每月平均"),
    CATEGORY("某分类花了多少"),
}

data class LedgerOverviewUiState(
    val loaded: Boolean = false,
    /** 统计快照是否已到：未到时先出账本骨架，金额与分类用占位，不拿 0 冒充结果。 */
    val statsLoaded: Boolean = false,
    /** 打开期间账本被删或已不存在：页面只留一句说明，不再假装还有数据。 */
    val missing: Boolean = false,
    val ledgerId: String = "",
    val ledgerName: String = "",
    val ledgerIconKey: String = "ledger",
    val isDefault: Boolean = false,
    val isArchived: Boolean = false,
    val billCount: Int = 0,
    val totalExpenseMinor: Long = 0L,
    val incomeMinor: Long = 0L,
    val balanceMinor: Long = 0L,
    val spanStartAt: Long? = null,
    /** 非默认账本取最后一笔的日期；默认账本取今天——「至今」才是它真正的右端。 */
    val spanEndAt: Long? = null,
    val spanDays: Int = 0,
    val dailyAverageMinor: Long = 0L,
    val months: Int = 0,
    val categories: List<CategoryTotal> = emptyList(),
    val recent: List<BillListItem> = emptyList(),
    val largestExpense: BillListItem? = null,
    /** 这本账累计设过的总额预算（各周期段的总额行相加）；没设过为 null，预算对比整段不出现。 */
    val budgetLimitMinor: Long? = null,
    val question: OverviewQuestion = OverviewQuestion.NET,
    val selectedCategoryId: String? = null,
    val categoryPickerOpen: Boolean = false,
    val settleDialogVisible: Boolean = false,
    val settleSheetVisible: Boolean = false,
    val settings: TallySettings = TallySettings.DEFAULT,
    val error: String? = null,
) {
    val grouping: Boolean get() = settings.showThousandsSeparator

    val canSettle: Boolean get() = loaded && !missing && !isDefault && !isArchived

    /** 快速问答里当前问到的分类：没选过就取排行第一。 */
    val questionCategory: CategoryTotal?
        get() = categories.firstOrNull { it.categoryId == selectedCategoryId } ?: categories.firstOrNull()

    val largestShare: Float
        get() = largestExpense?.let { item ->
            if (totalExpenseMinor > 0L) item.bill.amountMinor.toFloat() / totalExpenseMinor else 0f
        } ?: 0f

    val monthlyAverageMinor: Long
        get() = if (months > 0) totalExpenseMinor / months else 0L

    /** 结算结果与长图共用：超支时是正数，结余时是负数。 */
    val budgetDeltaMinor: Long? get() = budgetLimitMinor?.let { totalExpenseMinor - it }
}

/**
 * 账本概览：固定的一本账，不是可换区间的统计工具。
 *
 * 金额合计与分类占比一律走 `BillRepository.observeStatistics` 的区间口径，
 * 跨度、最近几笔、最大一笔是这本账自己的索引查询；预算只读总额行，不掺分类预算。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LedgerOverviewViewModel(
    private val graph: AppGraph,
    private val ledgerId: String,
) : ViewModel() {

    private data class Overlay(
        val question: OverviewQuestion = OverviewQuestion.NET,
        val selectedCategoryId: String? = null,
        val pickerOpen: Boolean = false,
        val settleDialog: Boolean = false,
        val settleSheet: Boolean = false,
        val error: String? = null,
    )

    private val overlay = MutableStateFlow(Overlay())

    /**
     * 身份/跨度/笔数/最近几笔/最大一笔：一次订阅、两处消费（本页卡片与下面的 [stats]）。
     * 不 share 的话 combine 与 flatMapLatest 会各订阅一次，同一批查询白跑两遍。
     */
    private val core: Flow<LedgerOverviewCore?> =
        graph.ledgerRepository.observeOverview(ledgerId)
            .flowOn(Dispatchers.IO)
            .shareIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), replay = 1)

    /**
     * 整本账当作一个区间来统计。概览不要上期/去年同期、不要趋势桶，
     * 所以走 `observeOverviewStats` 的瘦查询，而不是统计页的整份快照。
     *
     * 先发一个 null 表示「还没算完」：`combine` 要等每条流都出过值才会产出，
     * 不先占位的话，账本卡与问答都得等统计跑完才能出现。
     */
    private val stats: Flow<OverviewStats?> = core.flatMapLatest { current ->
        val start = current?.span?.startAt
        val end = current?.span?.endAt
        if (start == null || end == null) {
            flowOf<OverviewStats?>(OverviewStats(0L, 0L, 0L, emptyList()))
        } else {
            graph.billRepository.observeOverviewStats(
                ledgerId,
                PeriodRange(start, Period.shiftDays(end, 1)),
            ).map<OverviewStats, OverviewStats?> { it }
        }
    }.onStart { emit(null) }

    /** 结算的「预算」= 这本账各周期段总额行之和：项目跨了几个月，预算也该是那几个月之和。 */
    private val budgetLimit: Flow<Long?> = graph.budgetRepository.observeItems(ledgerId)
        .map { items ->
            items.filter { it.categoryId == null }
                .sumOf { it.amountMinor }
                .takeIf { it > 0L }
        }
        .flowOn(Dispatchers.IO)

    val state: StateFlow<LedgerOverviewUiState> = combine(
        core,
        stats,
        budgetLimit,
        graph.preferences.settings,
        overlay,
    ) { current, stats, budget, settings, over ->
        if (current == null) {
            LedgerOverviewUiState(
                loaded = true,
                missing = true,
                ledgerId = ledgerId,
                settings = settings,
                question = over.question,
                categoryPickerOpen = over.pickerOpen,
                error = over.error,
            )
        } else {
            val ledger = current.ledger
            val start = current.span.startAt
            val lastBill = current.span.endAt
            val today = System.currentTimeMillis()
            val end = if (ledger.isDefault) maxOf(lastBill ?: today, today) else lastBill
            val days = if (start != null && end != null) Period.daysBetween(start, end) + 1 else 0
            val months = if (start != null && end != null) monthsInclusive(start, end) else 0
            val total = stats?.expenseMinor ?: 0L
            LedgerOverviewUiState(
                loaded = true,
                statsLoaded = stats != null,
                ledgerId = ledger.id,
                ledgerName = ledger.name,
                ledgerIconKey = ledger.iconKey,
                isDefault = ledger.isDefault,
                isArchived = ledger.isArchived,
                billCount = current.billCount,
                totalExpenseMinor = total,
                incomeMinor = stats?.incomeMinor ?: 0L,
                balanceMinor = stats?.balanceMinor ?: 0L,
                spanStartAt = start,
                spanEndAt = end,
                spanDays = days,
                dailyAverageMinor = if (days > 0) total / days else 0L,
                months = months,
                categories = stats?.categories.orEmpty(),
                recent = current.recent,
                largestExpense = current.largestExpense,
                budgetLimitMinor = budget,
                question = over.question,
                selectedCategoryId = over.selectedCategoryId,
                categoryPickerOpen = over.pickerOpen,
                settleDialogVisible = over.settleDialog,
                settleSheetVisible = over.settleSheet,
                settings = settings,
                error = over.error,
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LedgerOverviewUiState(ledgerId = ledgerId))

    /** 月度与「每月平均」都按跨到的自然月算：07-03 ～ 08-12 是两个月，不是 1.3 个月。 */
    private fun monthsInclusive(startAt: Long, endAt: Long): Int {
        val start = Period.toDate(startAt)
        val end = Period.toDate(endAt)
        return (end.year - start.year) * 12 + end.monthValue - start.monthValue + 1
    }

    fun ask(question: OverviewQuestion) {
        overlay.value = overlay.value.copy(question = question, pickerOpen = false)
    }

    /** 点「某分类花了多少」先弹选择器；选完直接留在分类这一问上。 */
    fun openCategoryPicker() {
        overlay.value = overlay.value.copy(question = OverviewQuestion.CATEGORY, pickerOpen = true)
    }

    fun pickCategory(categoryId: String?) {
        overlay.value = overlay.value.copy(
            question = OverviewQuestion.CATEGORY,
            selectedCategoryId = categoryId,
            pickerOpen = false,
        )
    }

    fun closeCategoryPicker() {
        overlay.value = overlay.value.copy(pickerOpen = false)
    }

    fun requestSettle() {
        val current = state.value
        if (!current.canSettle) return
        overlay.value = overlay.value.copy(settleDialog = true, error = null)
    }

    fun dismissSettleDialog() {
        overlay.value = overlay.value.copy(settleDialog = false)
    }

    /** 确认结算即锁定账本；结果 sheet 只从这里出。 */
    fun confirmSettle() {
        overlay.value = overlay.value.copy(settleDialog = false)
        viewModelScope.launch {
            try {
                graph.ledgerRepository.setArchived(ledgerId, archived = true)
                overlay.value = overlay.value.copy(settleSheet = true)
            } catch (tallyError: TallyError) {
                overlay.value = overlay.value.copy(error = tallyError.message)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                overlay.value = overlay.value.copy(error = failure.message ?: "结算没有完成，请稍后重试")
            }
        }
    }

    fun dismissSettleSheet() {
        overlay.value = overlay.value.copy(settleSheet = false)
    }

    fun dismissError() {
        overlay.value = overlay.value.copy(error = null)
    }
}
