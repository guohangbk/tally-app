package io.github.guohangbk.tally.ui.installment

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.guohangbk.tally.data.db.entity.CategoryEntity
import io.github.guohangbk.tally.data.db.entity.InstallmentPlanEntity
import io.github.guohangbk.tally.data.repository.LedgerListItem
import io.github.guohangbk.tally.di.AppGraph
import io.github.guohangbk.tally.domain.formatRateBp
import io.github.guohangbk.tally.domain.model.CategoryType
import io.github.guohangbk.tally.domain.model.FeeMode
import io.github.guohangbk.tally.domain.model.InstallmentDraft
import io.github.guohangbk.tally.domain.model.InstallmentEditDraft
import io.github.guohangbk.tally.domain.model.InstallmentPeriod
import io.github.guohangbk.tally.domain.model.InstallmentPlanView
import io.github.guohangbk.tally.domain.model.InstallmentStatus
import io.github.guohangbk.tally.domain.model.RepaymentMethod
import io.github.guohangbk.tally.domain.model.TallyError
import io.github.guohangbk.tally.domain.parseAmount
import io.github.guohangbk.tally.domain.parseRateBp
import io.github.guohangbk.tally.domain.schedule.InstallmentSchedule
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Locale

data class InstallmentUiState(
    val items: List<InstallmentPlanView> = emptyList(),
    val busy: Boolean = false,
    val notice: String? = null,
    val error: String? = null,
)

/** 新建页的两种场景：普通分期按期均摊；房贷按 LPR + 加点计息。 */
enum class InstallmentScenario {
    PLAIN,
    MORTGAGE,
}

/**
 * 新建与编辑共用一份表单状态。编辑时 [planId] 非空：金额、期数、首期入账日只读，
 * 房贷多出改利率区（并入这一页，不再有独立的改利率页）。
 */
data class InstallmentFormState(
    val planId: String? = null,
    val loaded: Boolean = false,
    /** 编辑进来时计划已经不在了：页面给空态，不把仓储读失败当成保存失败弹出来。 */
    val missing: Boolean = false,
    val scenario: InstallmentScenario = InstallmentScenario.PLAIN,
    val repaymentMethod: RepaymentMethod = RepaymentMethod.EQUAL_PAYMENT,
    val name: String = "",
    val principalText: String = "",
    val feeText: String = "",
    val feeMode: FeeMode = FeeMode.INCLUDED,
    val periodText: String = "12",
    val intervalMonths: Int = 1,
    val lprText: String = "",
    val bpText: String = "0",
    val ledgerId: String? = null,
    val ledgers: List<LedgerListItem> = emptyList(),
    val categories: List<CategoryEntity> = emptyList(),
    val categoryL1Id: String? = null,
    val categoryL2Id: String? = null,
    val firstOccurredAt: Long = System.currentTimeMillis(),
    val note: String = "",
    val originalPlan: InstallmentPlanEntity? = null,
    val originalPeriods: List<InstallmentPeriod> = emptyList(),
    val error: String? = null,
    val saving: Boolean = false,
    val saved: Boolean = false,
) {
    val isEditing: Boolean get() = planId != null
    val isMortgage: Boolean get() = scenario == InstallmentScenario.MORTGAGE
    val status: InstallmentStatus get() = originalPlan?.let {
        runCatching { InstallmentStatus.valueOf(it.status) }.getOrDefault(InstallmentStatus.ACTIVE)
    } ?: InstallmentStatus.ACTIVE
    /** 已结清的计划不能再改利率。 */
    val rateEditable: Boolean get() = isMortgage && status == InstallmentStatus.ACTIVE

    val principalMinor: Long? get() = parseAmount(principalText)
    val feeMinor: Long? get() = feeText.takeIf { it.isNotBlank() }?.let(::parseAmount) ?: 0L
    val periodCount: Int? get() = periodText.toIntOrNull()?.takeIf { it in 1..InstallmentSchedule.MAX_PERIODS }

    val bpAdjust: Int? get() = when {
        bpText.isBlank() -> 0
        else -> bpText.trim().removePrefix("+").toIntOrNull()?.takeIf { it in -500..500 }
    }

    /** 有效年利率（BP）= LPR + 加点；普通分期恒为 0。 */
    val annualRateBp: Int? get() = if (!isMortgage) {
        0
    } else {
        val lpr = parseRateBp(lprText) ?: return null
        val adjust = bpAdjust ?: return null
        (lpr + adjust).takeIf { it in 0..InstallmentSchedule.MAX_RATE_BP }
    }

    val currentRateBp: Int get() = originalPlan?.let {
        InstallmentSchedule.rateAt(it, it.generatedPeriods + 1)
    } ?: 0

    /** 实时预览；任何输入不合法时返回 null，保存按钮同步置灰。 */
    val preview: InstallmentSchedule.Computation?
        get() {
            if (isEditing) return null
            val principal = principalMinor ?: return null
            val periods = periodCount ?: return null
            val ledger = ledgerId ?: return null
            val rate = annualRateBp ?: return null
            val fee = if (isMortgage) 0L else feeMinor ?: return null
            return runCatching {
                InstallmentSchedule.compute(
                    InstallmentDraft(
                        name = name.ifBlank { if (isMortgage) "房贷" else "分期" },
                        ledgerId = ledger,
                        categoryL1Id = categoryL1Id,
                        categoryL2Id = categoryL2Id,
                        principalMinor = principal,
                        periodCount = periods,
                        firstOccurredAt = firstOccurredAt,
                        feeMinor = fee,
                        feeMode = feeMode,
                        intervalMonths = if (isMortgage) 1 else intervalMonths,
                        repaymentMethod = if (isMortgage) repaymentMethod else RepaymentMethod.PLAIN,
                        annualRateBp = rate,
                    ),
                )
            }.getOrNull()
        }

    /** 编辑态改利率后的每期明细：只用来算「改后每期 / 改前 / 差额」，不落库。 */
    val ratePreviewPeriods: List<InstallmentPeriod>?
        get() {
            if (!rateEditable) return null
            val plan = originalPlan ?: return null
            val rate = annualRateBp ?: return null
            return runCatching { InstallmentSchedule.periods(InstallmentSchedule.withRate(plan, rate)) }.getOrNull()
        }

    val generatedPeriods: Int get() = originalPlan?.generatedPeriods ?: 0
    val originalNextMinor: Long?
        get() = originalPeriods.firstOrNull { it.index > generatedPeriods }?.amountMinor
    val previewNextMinor: Long?
        get() = ratePreviewPeriods?.firstOrNull { it.index > generatedPeriods }?.amountMinor
    val previewInterestMinor: Long
        get() = ratePreviewPeriods.orEmpty().filter { it.index > generatedPeriods }.sumOf { it.interestMinor ?: 0L }
    val currentInterestMinor: Long
        get() = originalPeriods.filter { it.index > generatedPeriods }.sumOf { it.interestMinor ?: 0L }
    val rateChanged: Boolean
        get() = rateEditable && annualRateBp != null && annualRateBp != currentRateBp

    val canSave: Boolean
        get() = when {
            name.isBlank() || categoryL1Id == null || ledgerId == null -> false
            isEditing -> !isMortgage || annualRateBp != null
            else -> preview != null
        }
}

class InstallmentViewModel(private val graph: AppGraph) : ViewModel() {
    private val local = MutableStateFlow(InstallmentUiState())

    val state: StateFlow<InstallmentUiState> = combine(
        graph.installmentRepository.items,
        local,
    ) { items, current -> current.copy(items = items) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), InstallmentUiState())

    fun settleEarly(id: String) = runAction("已提前结清，未生成的期数不再生成") {
        graph.installmentRepository.settleEarly(id)
        null
    }

    fun delete(id: String) = runAction("计划已删除，已生成的账单保留") {
        graph.installmentRepository.delete(id)
        null
    }

    fun generateDue(planId: String) = runAction("没有到期的分期账单") {
        "已补记 ${graph.automationService.executeInstallment(planId)} 笔分期账单"
    }

    fun clearNotice() {
        local.value = local.value.copy(notice = null, error = null)
    }

    /** 成功文案由动作决定：补账类返回实际笔数，其余返回 null 用兜底文案。 */
    private fun runAction(fallbackMessage: String, block: suspend () -> String?) {
        viewModelScope.launch {
            local.value = local.value.copy(busy = true, error = null)
            try {
                val message = block() ?: fallbackMessage
                local.value = local.value.copy(busy = false, notice = message)
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
 * 新建与编辑共用：新建时 [planId] 为 null。编辑只放开名称、账本、分类、备注与房贷利率；
 * 金额、期数、首期入账日一变，已经生成的账单就与计划对不上。
 */
class InstallmentFormViewModel(
    private val graph: AppGraph,
    private val planId: String? = null,
) : ViewModel() {
    private val local = MutableStateFlow(InstallmentFormState(planId = planId))

    val state: StateFlow<InstallmentFormState> = combine(
        local,
        graph.ledgerRepository.items,
    ) { current, ledgers -> current.copy(ledgers = ledgers.filter { !it.ledger.isArchived }) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), InstallmentFormState(planId))

    init {
        viewModelScope.launch {
            graph.ledgerRepository.bootstrap()
            val categories = graph.billRepository.activeCategories(CategoryType.EXPENSE)
            val plan = planId?.let { graph.installmentRepository.find(it) }
            if (planId != null && plan == null) {
                local.value = local.value.copy(loaded = true, missing = true)
                return@launch
            }
            if (plan == null) {
                local.value = local.value.copy(
                    loaded = true,
                    ledgerId = graph.billRepository.defaultLedgerId(),
                    categories = categories,
                )
                return@launch
            }
            val method = runCatching { RepaymentMethod.valueOf(plan.repaymentMethod) }
                .getOrDefault(RepaymentMethod.PLAIN)
            val rate = InstallmentSchedule.rateAt(plan, plan.generatedPeriods + 1)
            local.value = local.value.copy(
                loaded = true,
                scenario = if (method == RepaymentMethod.PLAIN) InstallmentScenario.PLAIN else InstallmentScenario.MORTGAGE,
                repaymentMethod = method,
                name = plan.name,
                principalText = minorToText(plan.principalMinor),
                periodText = plan.periodCount.toString(),
                firstOccurredAt = plan.firstOccurredAt,
                ledgerId = plan.ledgerId,
                categories = categories,
                categoryL1Id = plan.categoryL1Id,
                categoryL2Id = plan.categoryL2Id,
                note = plan.note.orEmpty(),
                feeText = if (plan.feeMinor > 0L) minorToText(plan.feeMinor) else "",
                feeMode = runCatching { FeeMode.valueOf(plan.feeMode) }.getOrDefault(FeeMode.INCLUDED),
                intervalMonths = plan.intervalMonths,
                lprText = formatRateBp(rate).removeSuffix("%"),
                bpText = "0",
                originalPlan = plan,
                originalPeriods = InstallmentSchedule.periods(plan),
            )
        }
    }

    fun setName(value: String) = update { copy(name = value, error = null) }

    fun setScenario(value: InstallmentScenario) = if (isEditing()) Unit else update {
        copy(scenario = value, error = null)
    }

    fun setRepaymentMethod(value: RepaymentMethod) = if (isEditing()) Unit else update {
        copy(repaymentMethod = value)
    }

    fun setPrincipal(value: String) = if (isEditing()) Unit else update { copy(principalText = value) }

    fun setFee(value: String) = if (isEditing()) Unit else update { copy(feeText = value) }

    fun setFeeMode(mode: FeeMode) = if (isEditing()) Unit else update { copy(feeMode = mode) }

    fun setPeriods(value: String) = if (isEditing()) Unit else update { copy(periodText = value.filter(Char::isDigit).take(3)) }

    fun setInterval(months: Int) = if (isEditing()) Unit else update { copy(intervalMonths = months) }

    fun setLpr(value: String) = update {
        copy(lprText = value.filter { it.isDigit() || it == '.' || it == '%' }.take(6), error = null)
    }

    fun setBp(value: String) = update {
        copy(bpText = value.filter { it.isDigit() || it == '-' || it == '+' }.take(5), error = null)
    }

    fun setNote(value: String) = update { copy(note = value) }

    fun setFirstOccurredAt(epochMillis: Long) = if (isEditing()) Unit else update {
        copy(firstOccurredAt = epochMillis)
    }

    fun selectCategory(l1Id: String, l2Id: String?) = update {
        copy(categoryL1Id = l1Id, categoryL2Id = l2Id, error = null)
    }

    fun setLedger(id: String) = update { copy(ledgerId = id, error = null) }

    fun clearError() = update { copy(error = null) }

    fun save() {
        if (isEditing()) saveEdit() else saveCreate()
    }

    private fun saveCreate() {
        val current = local.value
        val preview = current.preview
        val ledgerId = current.ledgerId
        val principal = current.principalMinor
        val fee = current.feeMinor
        val periods = current.periodCount
        val rate = current.annualRateBp
        when {
            current.name.isBlank() -> return update { copy(error = "给分期计划起个名字") }
            principal == null -> return update { copy(error = "请输入有效的总金额") }
            periods == null -> return update { copy(error = "期数需在 1 到 ${InstallmentSchedule.MAX_PERIODS} 之间") }
            current.isMortgage && rate == null -> return update { copy(error = "请输入有效的 LPR 与加点") }
            ledgerId == null -> return update { copy(error = "请先设置默认账本") }
            current.categoryL1Id == null -> return update { copy(error = "请选择分类") }
            preview == null -> return update { copy(error = if (current.isMortgage) "金额不足以按这个利率分成这么多期" else "金额不足以分成这么多期") }
            fee == null -> return update { copy(error = "手续费格式不正确") }
        }
        viewModelScope.launch {
            update { copy(saving = true, error = null) }
            try {
                graph.installmentRepository.create(
                    InstallmentDraft(
                        name = current.name,
                        ledgerId = ledgerId,
                        categoryL1Id = current.categoryL1Id,
                        categoryL2Id = current.categoryL2Id,
                        principalMinor = principal,
                        periodCount = periods,
                        firstOccurredAt = current.firstOccurredAt,
                        feeMinor = if (current.isMortgage) 0L else fee,
                        feeMode = current.feeMode,
                        note = current.note,
                        intervalMonths = if (current.isMortgage) 1 else current.intervalMonths,
                        repaymentMethod = if (current.isMortgage) current.repaymentMethod else RepaymentMethod.PLAIN,
                        annualRateBp = rate ?: 0,
                    ),
                )
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

    private fun saveEdit() {
        val current = local.value
        val id = current.planId ?: return
        val ledgerId = current.ledgerId
        val rate = current.annualRateBp
        when {
            current.name.isBlank() -> return update { copy(error = "给分期计划起个名字") }
            ledgerId == null -> return update { copy(error = "请先设置默认账本") }
            current.categoryL1Id == null -> return update { copy(error = "请选择分类") }
            current.rateEditable && rate == null -> return update { copy(error = "请输入有效的 LPR 与加点") }
        }
        viewModelScope.launch {
            update { copy(saving = true, error = null) }
            try {
                graph.installmentRepository.update(
                    id,
                    InstallmentEditDraft(
                        name = current.name,
                        ledgerId = ledgerId,
                        categoryL1Id = current.categoryL1Id,
                        categoryL2Id = current.categoryL2Id,
                        note = current.note.ifBlank { null },
                    ),
                )
                if (current.rateChanged && rate != null) {
                    graph.installmentRepository.updateRate(id, rate)
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

    private fun isEditing(): Boolean = local.value.isEditing

    private fun update(block: InstallmentFormState.() -> InstallmentFormState) {
        local.value = local.value.block()
    }
}

private fun minorToText(minor: Long): String = String.format(Locale.CHINA, "%.2f", minor / 100.0)
