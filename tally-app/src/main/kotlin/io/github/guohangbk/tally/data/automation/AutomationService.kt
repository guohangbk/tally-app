package io.github.guohangbk.tally.data.automation

import androidx.room.withTransaction
import io.github.guohangbk.tally.data.db.TallyDatabase
import io.github.guohangbk.tally.data.db.entity.InstallmentPlanEntity
import io.github.guohangbk.tally.data.db.entity.RecurringRuleEntity
import io.github.guohangbk.tally.data.repository.BillRepository
import io.github.guohangbk.tally.data.repository.InstallmentRepository
import io.github.guohangbk.tally.data.repository.RecurringRepository
import io.github.guohangbk.tally.domain.model.AutomationEntry
import io.github.guohangbk.tally.domain.model.AutomationOutcome
import io.github.guohangbk.tally.domain.model.BillSource
import io.github.guohangbk.tally.domain.model.BillType
import io.github.guohangbk.tally.domain.model.CatchUpResult
import io.github.guohangbk.tally.domain.model.DueOccurrence
import io.github.guohangbk.tally.domain.model.GeneratedBillSpec
import io.github.guohangbk.tally.domain.schedule.InstallmentSchedule
import java.time.LocalDate
import java.time.ZoneId

/**
 * 周期与分期的统一补执行入口。
 *
 * 设计要点：
 * 1. 自动补执行有条数阈值，超过阈值只汇报不落库，避免启动时静默改写上百笔账单；
 * 2. **一个任务一个事务**：账单与它自己的游标一起提交，别的任务失败不牵连它。
 *    整批一个事务时，一条坏规则会把所有到期任务一起回滚，然后每天重试、每天静默失败；
 * 3. 幂等键 (rule|plan, period_index) 保证重复触发不会多记；
 * 4. 每个任务都留下一条 [AutomationEntry]，通知层据此说话（「房租已记入」而不是「4 笔已记入」）。
 */
class AutomationService(
    private val database: TallyDatabase,
    private val recurringRepository: RecurringRepository,
    private val installmentRepository: InstallmentRepository,
    private val billRepository: BillRepository,
    private val zoneId: () -> ZoneId = ZoneId::systemDefault,
) {
    /** 待补明细保留实体本身：只报笔数的话，超阈值那条通知就说不清是谁的账。 */
    data class Pending(
        val recurring: List<Pair<RecurringRuleEntity, List<DueOccurrence>>>,
        val installments: List<Pair<InstallmentPlanEntity, List<Int>>>,
    ) {
        val total: Int
            get() = recurring.sumOf { it.second.size } + installments.sumOf { it.second.size }
        val isEmpty: Boolean get() = total == 0
    }

    suspend fun pending(today: LocalDate = LocalDate.now(zoneId())): Pending =
        Pending(
            recurring = recurringRepository.duePlans(today),
            installments = installmentRepository.duePlans(today),
        )

    /**
     * 启动、回到前台与 WorkManager 共用的自动补执行。
     * 待补总数超过 [autoLimit] 时一条都不落库，返回 PENDING 明细等待用户在周期页确认。
     */
    suspend fun catchUp(autoLimit: Int = AUTO_LIMIT): CatchUpResult {
        val pending = pending()
        if (pending.isEmpty) {
            runCatching { database.withTransaction { recurringRepository.finishExhausted() } }
            return CatchUpResult()
        }
        if (pending.total > autoLimit) {
            return CatchUpResult(pendingTasks(pending, AutomationOutcome.PENDING))
        }
        return executeAll(pending)
    }

    /** 用户确认后执行全部待补账；单个任务一个事务，失败的那条进 [AutomationOutcome.FAILED]。 */
    suspend fun executeAll(): CatchUpResult = executeAll(pending())

    private suspend fun executeAll(pending: Pending): CatchUpResult {
        if (pending.isEmpty) {
            database.withTransaction { recurringRepository.finishExhausted() }
            return CatchUpResult()
        }
        val entries = mutableListOf<AutomationEntry>()
        for ((rule, occurrences) in pending.recurring) {
            entries += runCatching { transaction { executeRule(rule, occurrences) } }
                .getOrElse { cause -> listOf(taskOf(rule, occurrences).entry(AutomationOutcome.FAILED, problem = describe(cause))) }
        }
        for ((plan, indexes) in pending.installments) {
            entries += runCatching { transaction { executePlan(plan, indexes) } }
                .getOrElse { cause -> listOf(taskOf(plan, indexes).entry(AutomationOutcome.FAILED, problem = describe(cause))) }
        }
        database.withTransaction { recurringRepository.finishExhausted() }
        return CatchUpResult(entries)
    }

    suspend fun executeRecurring(ruleId: String): Int {
        val today = LocalDate.now(zoneId())
        val (rule, occurrences) = recurringRepository.duePlan(ruleId, today) ?: return 0
        return transaction { executeRule(rule, occurrences) }.sumOf { it.count }
    }

    suspend fun executeInstallment(planId: String): Int {
        val today = LocalDate.now(zoneId())
        val (plan, indexes) = installmentRepository.duePlan(planId, today) ?: return 0
        return transaction { executePlan(plan, indexes) }.sumOf { it.count }
    }

    /** 返回空列表表示「这一期早就记过了」，不是失败——幂等键命中，静默跳过。 */
    private suspend fun transaction(block: suspend () -> List<AutomationEntry>): List<AutomationEntry> =
        database.withTransaction { block() }

    private suspend fun executeRule(rule: RecurringRuleEntity, occurrences: List<DueOccurrence>): List<AutomationEntry> {
        val type = runCatching { BillType.valueOf(rule.type) }.getOrDefault(BillType.EXPENSE)
        var count = 0
        var amount = 0L
        var lastDueAt = 0L
        var billId: String? = null
        occurrences.forEach { occurrence ->
            val id = billRepository.insertGenerated(
                GeneratedBillSpec(
                    ledgerId = rule.ledgerId,
                    categoryL1Id = rule.categoryL1Id,
                    categoryL2Id = rule.categoryL2Id,
                    type = type,
                    amountMinor = rule.amountMinor,
                    occurredAt = occurrence.dueAt,
                    note = rule.note,
                    source = BillSource.RECURRING,
                    periodIndex = occurrence.periodIndex,
                    recurrenceRuleId = rule.id,
                ),
            )
            if (id != null) {
                count++
                amount += rule.amountMinor
                lastDueAt = occurrence.dueAt
                billId = id
            }
        }
        // 游标照推进：这一期已经有人在（幂等键命中就是记过了），不推进等于明天再试一遍。
        recurringRepository.advance(rule, occurrences)
        if (count == 0) return emptyList()
        return listOf(
            TaskDue(BillSource.RECURRING, rule.id, rule.name, rule.ledgerId, count, amount, lastDueAt)
                .entry(AutomationOutcome.GENERATED, billId = billId),
        )
    }

    private suspend fun executePlan(plan: InstallmentPlanEntity, indexes: List<Int>): List<AutomationEntry> {
        val type = runCatching { BillType.valueOf(plan.type) }.getOrDefault(BillType.EXPENSE)
        val periods = InstallmentSchedule.periods(plan, zoneId()).associateBy { it.index }
        var count = 0
        var amount = 0L
        var lastDueAt = 0L
        var billId: String? = null
        indexes.forEach { index ->
            val period = periods[index] ?: return@forEach
            val id = billRepository.insertGenerated(
                GeneratedBillSpec(
                    ledgerId = plan.ledgerId,
                    categoryL1Id = plan.categoryL1Id,
                    categoryL2Id = plan.categoryL2Id,
                    type = type,
                    amountMinor = period.amountMinor,
                    occurredAt = period.dueAt,
                    note = if (period.isFee) "${plan.name} · 手续费" else plan.name,
                    source = BillSource.INSTALLMENT,
                    periodIndex = index,
                    installmentPlanId = plan.id,
                ),
            )
            if (id != null) {
                count++
                amount += period.amountMinor
                lastDueAt = period.dueAt
                billId = id
            }
        }
        installmentRepository.advance(plan, indexes.size)
        if (count == 0) return emptyList()
        return listOf(
            TaskDue(BillSource.INSTALLMENT, plan.id, plan.name, plan.ledgerId, count, amount, lastDueAt)
                .entry(AutomationOutcome.GENERATED, billId = billId),
        )
    }

    /** 没落库（待确认）与执行失败用的都是「原来到期的那几期」，口径与 [executePlan] 的入参一致。 */
    private fun pendingTasks(pending: Pending, outcome: AutomationOutcome): List<AutomationEntry> =
        pending.recurring.map { (rule, occurrences) -> taskOf(rule, occurrences).entry(outcome) } +
            pending.installments.map { (plan, indexes) -> taskOf(plan, indexes).entry(outcome) }

    private fun taskOf(rule: RecurringRuleEntity, occurrences: List<DueOccurrence>): TaskDue = TaskDue(
        source = BillSource.RECURRING,
        taskId = rule.id,
        name = rule.name,
        ledgerId = rule.ledgerId,
        count = occurrences.size,
        amountMinor = rule.amountMinor * occurrences.size,
        occurredAt = occurrences.last().dueAt,
    )

    private fun taskOf(plan: InstallmentPlanEntity, indexes: List<Int>): TaskDue {
        val wanted = indexes.toSet()
        val periods = InstallmentSchedule.periods(plan, zoneId()).filter { it.index in wanted }
        return TaskDue(
            source = BillSource.INSTALLMENT,
            taskId = plan.id,
            name = plan.name,
            ledgerId = plan.ledgerId,
            count = periods.size,
            amountMinor = periods.sumOf { it.amountMinor },
            occurredAt = periods.maxOfOrNull { it.dueAt } ?: plan.firstOccurredAt,
        )
    }

    /** 异常给人看的那一句：Room 的 cause 链条很深，最里层的消息才有信息量。 */
    private fun describe(cause: Throwable): String =
        (generateSequence(cause) { it.cause }.last().message ?: cause.javaClass.simpleName)

    /** 「哪个任务、这次几期、多少钱、最新一期哪天」——三种结果共用这一段描述。 */
    private data class TaskDue(
        val source: BillSource,
        val taskId: String,
        val name: String,
        val ledgerId: String,
        val count: Int,
        val amountMinor: Long,
        val occurredAt: Long,
    ) {
        fun entry(outcome: AutomationOutcome, billId: String? = null, problem: String? = null) = AutomationEntry(
            outcome = outcome,
            source = source,
            taskId = taskId,
            name = name,
            ledgerId = ledgerId,
            count = count,
            amountMinor = amountMinor,
            occurredAt = occurredAt,
            billId = billId,
            problem = problem,
        )
    }

    companion object {
        /** 补执行超过 100 笔先汇总确认。 */
        const val AUTO_LIMIT = 100
    }
}
