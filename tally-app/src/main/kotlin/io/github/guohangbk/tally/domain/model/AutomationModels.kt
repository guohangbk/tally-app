package io.github.guohangbk.tally.domain.model

import io.github.guohangbk.tally.data.db.entity.BillEntity
import io.github.guohangbk.tally.data.db.entity.InstallmentPlanEntity
import io.github.guohangbk.tally.data.db.entity.RecurringRuleEntity
import io.github.guohangbk.tally.domain.schedule.InstallmentSchedule

/** 周期单位。复杂规则（每两周、工作日）暂不支持。 */
enum class RecurrenceUnit {
    DAILY,
    WEEKLY,
    MONTHLY,
    LAST_DAY_OF_MONTH,
    YEARLY,
}

/**
 * 周期描述。`dayOfMonth` 对 MONTHLY / YEARLY 生效，`dayOfWeek` 使用 ISO 1..7（周一为 1）。
 * 月末规则不读取 `dayOfMonth`，永远取当月最后一天。
 */
data class RecurrenceSpec(
    val unit: RecurrenceUnit,
    val dayOfMonth: Int? = null,
    val dayOfWeek: Int? = null,
    val monthOfYear: Int? = null,
) {
    val summary: String
        get() = when (unit) {
            RecurrenceUnit.DAILY -> "每日"
            RecurrenceUnit.WEEKLY -> "每周${weekdayName(dayOfWeek)}"
            RecurrenceUnit.MONTHLY -> "每月 ${dayOfMonth ?: 1} 日"
            RecurrenceUnit.LAST_DAY_OF_MONTH -> "每月最后一天"
            RecurrenceUnit.YEARLY -> "每年 ${monthOfYear ?: 1} 月 ${dayOfMonth ?: 1} 日"
        }

    companion object {
        fun weekdayName(dayOfWeek: Int?): String = when (dayOfWeek) {
            1 -> "一"
            2 -> "二"
            3 -> "三"
            4 -> "四"
            5 -> "五"
            6 -> "六"
            7 -> "日"
            else -> "一"
        }
    }
}

/** 周期任务结束方式；UI 三选一，落库为 endAt / maxPeriods 的组合。 */
sealed interface RecurrenceEnd {
    data object Never : RecurrenceEnd
    data class OnDate(val epochMillis: Long) : RecurrenceEnd
    data class AfterCount(val count: Int) : RecurrenceEnd
}

data class RecurringDraft(
    val name: String,
    val ledgerId: String,
    val categoryL1Id: String?,
    val categoryL2Id: String? = null,
    val type: BillType = BillType.EXPENSE,
    val amountMinor: Long,
    val note: String? = null,
    val spec: RecurrenceSpec = RecurrenceSpec(RecurrenceUnit.MONTHLY, dayOfMonth = 1),
    val startAt: Long = System.currentTimeMillis(),
    val end: RecurrenceEnd = RecurrenceEnd.Never,
)

/** 列表项把分类路径、账本名与待补笔数一起算好，UI 不再各自拼装。 */
data class RecurringRuleView(
    val rule: RecurringRuleEntity,
    val ledgerName: String,
    val categoryPath: String,
    val pendingCount: Int,
    val endDescription: String,
    /** 卡片头像用所属分类的图标，而不是一个跟内容无关的循环图标。 */
    val categoryIcon: String? = null,
) {
    val spec: RecurrenceSpec
        get() = RecurrenceSpec(
            unit = runCatching { RecurrenceUnit.valueOf(rule.frequency) }
                .getOrDefault(RecurrenceUnit.MONTHLY),
            dayOfMonth = rule.dayOfMonth,
            dayOfWeek = rule.dayOfWeek,
            monthOfYear = rule.monthOfYear,
        )

    val status: RecurringStatus
        get() = runCatching { RecurringStatus.valueOf(rule.status) }.getOrDefault(RecurringStatus.ACTIVE)
}

/**
 * 周期任务「明细」弹层里的一行：这一期落在哪天、多少钱。
 * 期次是排期里的真实编号；删掉的那一期仍占一行（[deleted]），回收站也清掉后连日期都不剩。
 */
data class RecurringPeriodRow(
    val billId: String,
    /** 期次（从 1 起）；库里没记期号的老数据为 0，界面按顺序补号。 */
    val index: Int,
    val occurredAt: Long,
    val amountMinor: Long,
    val isIncome: Boolean,
    val note: String?,
    /** 这一期已经从账上删掉：灰着占位，金额不进合计。 */
    val deleted: Boolean = false,
)

/** 一次应生成但尚未生成的周期账单。periodIndex 从 1 开始，与任务 ID 组成幂等键。 */
data class DueOccurrence(
    val periodIndex: Int,
    val dueAt: Long,
)

data class CatchUpPlan(
    val ruleId: String,
    val ruleName: String,
    val amountMinor: Long,
    val occurrences: List<DueOccurrence>,
) {
    val count: Int get() = occurrences.size
    val totalMinor: Long get() = amountMinor * occurrences.size
}

/** 一次补执行里，单个任务的落点。三条路互斥，所以只用一个枚举区分。 */
enum class AutomationOutcome {
    /** 已自动生成账单。 */
    GENERATED,

    /** 到期但没执行：超过自动阈值，等用户在周期页确认。 */
    PENDING,

    /** 尝试执行但失败，事务已回滚，游标没动。 */
    FAILED,
}

/**
 * 单个任务在这次补执行里的结果。
 *
 * 明细必须从执行现场带出来，不能事后重算：提醒发生时游标已经推进，
 * 再查一遍只会看到「没有待补账」。UI 只用汇总计数，通知层按条目说话。
 */
data class AutomationEntry(
    val outcome: AutomationOutcome,
    val source: BillSource,
    val taskId: String,
    val name: String,
    val ledgerId: String,
    /** 这次涉及几期。 */
    val count: Int,
    /** 这几期合计。 */
    val amountMinor: Long,
    /** 最新一期的应入账日；「10 月期」「应还 10 月 15 日」由它来说。 */
    val occurredAt: Long,
    /** 已记入时最新一笔的 id，通知点达直接落它的详情；其余为 null。 */
    val billId: String? = null,
    /** 失败原因，只有 [AutomationOutcome.FAILED] 才有值。 */
    val problem: String? = null,
)

/**
 * 补执行结果。`deferred > 0` 表示待补笔数超过自动阈值，已暂停等待用户确认；
 * 自动路径不静默改写上百笔账单。
 */
data class CatchUpResult(
    val entries: List<AutomationEntry> = emptyList(),
) {
    fun entriesOf(outcome: AutomationOutcome): List<AutomationEntry> = entries.filter { it.outcome == outcome }

    val generated: Int get() = countOf(AutomationOutcome.GENERATED)
    val deferred: Int get() = countOf(AutomationOutcome.PENDING)
    val needsConfirmation: Boolean get() = deferred > 0
    val isEmpty: Boolean get() = entries.isEmpty()

    private fun countOf(outcome: AutomationOutcome): Int = entriesOf(outcome).sumOf { it.count }
}

/** 手续费处理方式：并入每期，或单独成最后一期。 */
enum class FeeMode {
    INCLUDED,
    SEPARATE,
}

/**
 * 还款方式。PLAIN 是普通分期（按期均摊，不计息）；房贷用等额本息或等额本金，
 * 两者都按固定年利率计息，利率变动记在 [RateSegment] 里。
 */
enum class RepaymentMethod {
    PLAIN,
    EQUAL_PAYMENT,
    EQUAL_PRINCIPAL,
}

/** 利率变更段：从第 [fromPeriod] 期起按 [annualRateBp]（万分之一）计息。 */
data class RateSegment(
    val fromPeriod: Int,
    val annualRateBp: Int,
)

data class InstallmentDraft(
    val name: String,
    val ledgerId: String,
    val categoryL1Id: String?,
    val categoryL2Id: String? = null,
    val principalMinor: Long,
    val periodCount: Int,
    val firstOccurredAt: Long,
    val feeMinor: Long = 0L,
    val feeMode: FeeMode = FeeMode.INCLUDED,
    val note: String? = null,
    val intervalMonths: Int = 1,
    val repaymentMethod: RepaymentMethod = RepaymentMethod.PLAIN,
    val annualRateBp: Int = 0,
)

/** 修改已创建的分期计划：只动账本、名称、分类与备注，期数与利率不动。 */
data class InstallmentEditDraft(
    val name: String,
    val ledgerId: String,
    val categoryL1Id: String?,
    val categoryL2Id: String? = null,
    val note: String? = null,
)

data class InstallmentPeriod(
    val index: Int,
    val dueAt: Long,
    val amountMinor: Long,
    val isFee: Boolean = false,
    /** 房贷才有拆分；普通分期的本金与手续费不拆。 */
    val principalMinor: Long? = null,
    val interestMinor: Long? = null,
)

data class InstallmentPlanView(
    val plan: InstallmentPlanEntity,
    val ledgerName: String,
    val categoryPath: String,
    /** 计划所选分类的图标；二级优先，没有二级就用一级，缺失时卡片退回通用分期图标。 */
    val categoryIconKey: String? = null,
    val periods: List<InstallmentPeriod>,
    /**
     * 账上**真的**有账单的那些期号（有效账单，不含回收站）。
     *
     * 与 [plan.generatedPeriods] 不是一回事：那是个只增不减的**生成水位**，记录"生成到第几期了"。
     * 用户把某一期的账单删掉之后水位不会回退，只看水位就会报"已入账"，而账本里并没有那笔钱——
     * 用户以为记过了，汇总却少算。**所以对外的"已入账"一律以本集合为准**，
     * 水位只留给排期本身（下一期是哪期、还剩几期）。
     */
    val depositedPeriods: Set<Int> = emptySet(),
) {
    val status: InstallmentStatus
        get() = runCatching { InstallmentStatus.valueOf(plan.status) }.getOrDefault(InstallmentStatus.ACTIVE)

    val method: RepaymentMethod
        get() = runCatching { RepaymentMethod.valueOf(plan.repaymentMethod) }
            .getOrDefault(RepaymentMethod.PLAIN)
    val isMortgage: Boolean get() = method != RepaymentMethod.PLAIN

    val totalMinor: Long get() = plan.totalMinor
    val generatedPeriods: Int get() = plan.generatedPeriods
    /** 对外显示的"已入账期数"：账上真实存在的账单条数，不是生成水位。 */
    val depositedCount: Int get() = depositedPeriods.size
    /** 有账单生成过、但其中一些已经被删掉的期数——弹层要把这件事说出来。 */
    val missingPeriods: Int
        get() = periods.count { it.index <= plan.generatedPeriods && it.index !in depositedPeriods }
    val remainingPeriods: Int get() = (periods.size - plan.generatedPeriods).coerceAtLeast(0)
    /** 卡片进度条：按**账上真实入账**的期数算，不按生成水位——被删掉的期不该算作已还。 */
    val progress: Float
        get() = if (periods.isEmpty()) 0f else depositedCount.toFloat() / periods.size

    /** 当前生效年利率（BP）：改过利率时取下一条变更段。 */
    val currentRateBp: Int get() = InstallmentSchedule.rateAt(plan, plan.generatedPeriods + 1)
    val remainingPrincipalMinor: Long
        get() = periods.drop(plan.generatedPeriods).sumOf { it.principalMinor ?: 0L }
    val totalInterestMinor: Long get() = periods.sumOf { it.interestMinor ?: 0L }
    val nextPeriod: InstallmentPeriod? get() = periods.firstOrNull { it.index > plan.generatedPeriods }
    /** 已还金额 = 账上真实存在的那几期的合计（被删掉的期不算进去，否则这笔钱是虚的）。 */
    val paidMinor: Long get() = periods.filter { it.index in depositedPeriods }.sumOf { it.amountMinor }
}

/** 报销管理页的三组数据：待报销、已报销、钱迹「报销记录」独立系列。 */
data class ReimbursementSnapshot(
    val pending: List<BillListItem> = emptyList(),
    val done: List<BillListItem> = emptyList(),
    val records: List<BillListItem> = emptyList(),
) {
    val pendingMinor: Long get() = pending.sumOf { it.bill.amountMinor }
    val doneMinor: Long get() = done.sumOf { it.bill.amountMinor }
    val recordMinor: Long get() = records.sumOf { it.bill.amountMinor }
}

/** 账单详情里「关联账单」解析后的跳转目标。 */
data class LinkedBill(
    val bill: BillEntity,
    val title: String,
)

/** 周期/分期写入账单的统一入参；两个自动化引擎只负责算日期与金额。 */
data class GeneratedBillSpec(
    val ledgerId: String,
    val categoryL1Id: String?,
    val categoryL2Id: String?,
    val type: BillType,
    val amountMinor: Long,
    val occurredAt: Long,
    val note: String?,
    val source: BillSource,
    val periodIndex: Int,
    val recurrenceRuleId: String? = null,
    val installmentPlanId: String? = null,
)
