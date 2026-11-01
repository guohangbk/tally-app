package io.github.guohangbk.tally.data.repository

import io.github.guohangbk.tally.data.db.dao.CategoryDao
import io.github.guohangbk.tally.data.db.dao.LedgerDao
import io.github.guohangbk.tally.data.db.dao.RecurringRuleDao
import io.github.guohangbk.tally.data.db.entity.RecurringRuleEntity
import io.github.guohangbk.tally.data.preferences.TallyPreferences
import io.github.guohangbk.tally.domain.model.BillType
import io.github.guohangbk.tally.domain.model.CatchUpPlan
import io.github.guohangbk.tally.domain.model.DueOccurrence
import io.github.guohangbk.tally.domain.model.RecurrenceEnd
import io.github.guohangbk.tally.domain.model.RecurrenceSpec
import io.github.guohangbk.tally.domain.model.RecurrenceUnit
import io.github.guohangbk.tally.domain.model.RecurringDraft
import io.github.guohangbk.tally.domain.model.RecurringRuleView
import io.github.guohangbk.tally.domain.model.RecurringStatus
import io.github.guohangbk.tally.domain.model.TallyError
import io.github.guohangbk.tally.domain.schedule.RecurrenceSchedule
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/**
 * 周期任务：规则只描述「怎么重复」，补执行与幂等由 AutomationService 统一处理。
 * 修改只影响尚未生成的账单，已生成账单不回溯。
 */
class RecurringRepository(
    private val ruleDao: RecurringRuleDao,
    private val ledgerDao: LedgerDao,
    private val categoryDao: CategoryDao,
    private val preferences: TallyPreferences,
    private val now: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val zoneId: () -> ZoneId = ZoneId::systemDefault,
) {
    val items: Flow<List<RecurringRuleView>> = combine(
        ruleDao.observeAll(),
        ledgerDao.observeActive(),
        categoryDao.observeForNames(),
    ) { rules, ledgers, categories ->
        val ledgerNames = ledgers.associate { it.id to it.name }
        val categoryNames = categories.associateBy { it.id }
        val today = LocalDate.now(zoneId())
        rules.map { rule ->
            val spec = rule.spec()
            RecurringRuleView(
                rule = rule,
                ledgerName = ledgerNames[rule.ledgerId] ?: "未知账本",
                categoryPath = categoryPath(categoryNames[rule.categoryL1Id], categoryNames[rule.categoryL2Id]),
                pendingCount = if (rule.status == RecurringStatus.ACTIVE.name) {
                    dueOccurrences(rule, today).size
                } else {
                    0
                },
                endDescription = rule.endDescription(),
                categoryIcon = categoryNames[rule.categoryL2Id]?.iconKey
                    ?: categoryNames[rule.categoryL1Id]?.iconKey,
            )
        }
    }

    suspend fun find(id: String): RecurringRuleEntity? = ruleDao.findById(id)

    suspend fun create(draft: RecurringDraft): RecurringRuleEntity {
        requireTargets(
            ledgerDao = ledgerDao,
            categoryDao = categoryDao,
            ledgerId = draft.ledgerId,
            categoryL1Id = draft.categoryL1Id,
            categoryL2Id = draft.categoryL2Id,
            type = draft.type,
        )
        if (draft.amountMinor <= 0L) throw TallyError.AmountOutOfRange
        val name = draft.name.trim()
        if (name.isEmpty()) throw TallyError.EmptyName

        val startDate = Instant.ofEpochMilli(draft.startAt).atZone(zoneId()).toLocalDate()
        val firstDue = RecurrenceSchedule.firstOnOrAfter(draft.spec, startDate)
        val (endAt, maxPeriods) = when (val end = draft.end) {
            RecurrenceEnd.Never -> null to null
            is RecurrenceEnd.OnDate -> end.epochMillis to null
            is RecurrenceEnd.AfterCount -> null to end.count.coerceAtLeast(1)
        }
        val timestamp = now()
        val rule = RecurringRuleEntity(
            id = newId(),
            ledgerId = draft.ledgerId,
            name = name,
            type = draft.type.name,
            amountMinor = draft.amountMinor,
            categoryL1Id = draft.categoryL1Id,
            categoryL2Id = draft.categoryL2Id,
            note = draft.note?.trim()?.ifEmpty { null },
            frequency = draft.spec.unit.name,
            dayOfMonth = draft.spec.dayOfMonth,
            dayOfWeek = draft.spec.dayOfWeek,
            monthOfYear = draft.spec.monthOfYear,
            startAt = draft.startAt,
            endAt = endAt,
            maxPeriods = maxPeriods,
            nextDueAt = firstDue.atStartOfDay(zoneId()).toInstant().toEpochMilli(),
            createdAt = timestamp,
            updatedAt = timestamp,
            deviceId = preferences.deviceId(),
        )
        ruleDao.upsert(rule)
        return rule
    }

    /** 允许修改金额、分类、备注与结束方式；周期本身固定，避免已生成账单与规则错位。 */
    suspend fun update(id: String, amountMinor: Long, categoryL1Id: String?, categoryL2Id: String?, note: String?) {
        val rule = ruleDao.findById(id) ?: throw TallyError.RecurringRuleNotFound
        requireTargets(
            ledgerDao = ledgerDao,
            categoryDao = categoryDao,
            ledgerId = rule.ledgerId,
            categoryL1Id = categoryL1Id,
            categoryL2Id = categoryL2Id,
            type = runCatching { BillType.valueOf(rule.type) }.getOrDefault(BillType.EXPENSE),
        )
        if (amountMinor <= 0L) throw TallyError.AmountOutOfRange
        ruleDao.update(
            rule.copy(
                amountMinor = amountMinor,
                categoryL1Id = categoryL1Id,
                categoryL2Id = categoryL2Id,
                note = note?.trim()?.ifEmpty { null },
                updatedAt = now(),
                version = rule.version + 1,
            ),
        )
    }

    /**
     * 整条规则可改：名称、金额、分类、周期与起止。
     * 已生成的账单不动；下次执行按新规则从今天之后重算，所以修改只对后面生效。
     */
    suspend fun update(id: String, draft: RecurringDraft): RecurringRuleEntity {
        val rule = ruleDao.findById(id) ?: throw TallyError.RecurringRuleNotFound
        requireTargets(
            ledgerDao = ledgerDao,
            categoryDao = categoryDao,
            ledgerId = draft.ledgerId,
            categoryL1Id = draft.categoryL1Id,
            categoryL2Id = draft.categoryL2Id,
            type = draft.type,
        )
        if (draft.amountMinor <= 0L) throw TallyError.AmountOutOfRange
        val name = draft.name.trim()
        if (name.isEmpty()) throw TallyError.EmptyName

        val today = LocalDate.now(zoneId())
        val startDate = Instant.ofEpochMilli(draft.startAt).atZone(zoneId()).toLocalDate()
        val anchor = if (startDate.isAfter(today)) startDate else today
        val nextDue = RecurrenceSchedule.firstOnOrAfter(draft.spec, anchor)
        val (endAt, maxPeriods) = when (val end = draft.end) {
            RecurrenceEnd.Never -> null to null
            is RecurrenceEnd.OnDate -> end.epochMillis to null
            is RecurrenceEnd.AfterCount -> null to end.count.coerceAtLeast(1)
        }
        val updated = rule.copy(
            ledgerId = draft.ledgerId,
            name = name,
            type = draft.type.name,
            amountMinor = draft.amountMinor,
            categoryL1Id = draft.categoryL1Id,
            categoryL2Id = draft.categoryL2Id,
            note = draft.note?.trim()?.ifEmpty { null },
            frequency = draft.spec.unit.name,
            dayOfMonth = draft.spec.dayOfMonth,
            dayOfWeek = draft.spec.dayOfWeek,
            monthOfYear = draft.spec.monthOfYear,
            startAt = draft.startAt,
            endAt = endAt,
            maxPeriods = maxPeriods,
            nextDueAt = nextDue.atStartOfDay(zoneId()).toInstant().toEpochMilli(),
            updatedAt = now(),
            version = rule.version + 1,
        )
        ruleDao.update(updated)
        return updated
    }

    /**
     * 暂停保留游标；恢复时把游标推到今天之后，暂停期间不补账。
     */
    suspend fun setStatus(id: String, status: RecurringStatus) {
        val rule = ruleDao.findById(id) ?: throw TallyError.RecurringRuleNotFound
        val timestamp = now()
        if (status == RecurringStatus.ACTIVE && rule.status == RecurringStatus.PAUSED.name) {
            val today = LocalDate.now(zoneId())
            val cursor = Instant.ofEpochMilli(rule.nextDueAt).atZone(zoneId()).toLocalDate()
            if (cursor.isBefore(today)) {
                val first = RecurrenceSchedule.firstOnOrAfter(rule.spec(), today)
                ruleDao.advance(
                    id = id,
                    nextDueAt = first.atStartOfDay(zoneId()).toInstant().toEpochMilli(),
                    executedAt = rule.lastExecutedAt ?: 0L,
                    executedPeriods = rule.executedPeriods,
                    now = timestamp,
                )
            }
        }
        ruleDao.setStatus(id, status.name, timestamp)
    }

    /** 删除任务不删除已生成账单。 */
    suspend fun delete(id: String) {
        ruleDao.findById(id) ?: throw TallyError.RecurringRuleNotFound
        ruleDao.softDelete(id, now())
    }

    /** 供补执行使用：所有到期但尚未生成的执行日。 */
    suspend fun duePlans(today: LocalDate): List<Pair<RecurringRuleEntity, List<DueOccurrence>>> =
        ruleDao.findActive().mapNotNull { rule ->
            val occurrences = dueOccurrences(rule, today)
            if (occurrences.isEmpty()) null else rule to occurrences
        }

    suspend fun duePlan(ruleId: String, today: LocalDate): Pair<RecurringRuleEntity, List<DueOccurrence>>? {
        val rule = ruleDao.findById(ruleId) ?: return null
        if (rule.status != RecurringStatus.ACTIVE.name) return null
        val occurrences = dueOccurrences(rule, today)
        return if (occurrences.isEmpty()) null else rule to occurrences
    }

    /** 纯计算，不触碰 IO；列表 Flow 的 combine 变换里也能直接调用。 */
    fun dueOccurrences(rule: RecurringRuleEntity, today: LocalDate): List<DueOccurrence> =
        RecurrenceSchedule.dueOccurrences(
            spec = rule.spec(),
            nextDueAt = rule.nextDueAt,
            executedPeriods = rule.executedPeriods,
            maxPeriods = rule.maxPeriods,
            endAt = rule.endAt,
            today = today,
            zone = zoneId(),
        )

    /** 生成成功后推进游标；期数达到上限或超过截止日时自动结束。 */
    suspend fun advance(rule: RecurringRuleEntity, occurrences: List<DueOccurrence>) {
        if (occurrences.isEmpty()) return
        val timestamp = now()
        val executed = rule.executedPeriods + occurrences.size
        val last = occurrences.last()
        val nextDate = RecurrenceSchedule.next(rule.spec(), Instant.ofEpochMilli(last.dueAt).atZone(zoneId()).toLocalDate())
        val nextDueAt = nextDate.atStartOfDay(zoneId()).toInstant().toEpochMilli()
        val endedByCount = rule.maxPeriods != null && executed >= rule.maxPeriods
        val endedByDate = rule.endAt != null && nextDueAt > rule.endAt
        ruleDao.advance(rule.id, nextDueAt, timestamp, executed, timestamp)
        if (endedByCount || endedByDate) {
            ruleDao.setStatus(rule.id, RecurringStatus.FINISHED.name, timestamp)
        }
    }

    /** 已到结束条件但没有待补账时，直接把任务标记结束。 */
    suspend fun finishIfExhausted(rule: RecurringRuleEntity) {
        val endedByCount = rule.maxPeriods != null && rule.executedPeriods >= rule.maxPeriods
        val endedByDate = rule.endAt != null && rule.nextDueAt > rule.endAt
        if (endedByCount || endedByDate) {
            ruleDao.setStatus(rule.id, RecurringStatus.FINISHED.name, now())
        }
    }

    /** 启动补执行后统一收尾：把已到结束条件但仍标记为进行中的任务结束掉。 */
    suspend fun finishExhausted() {
        ruleDao.findActive().forEach { finishIfExhausted(it) }
    }

    suspend fun catchUpPlan(rule: RecurringRuleEntity, occurrences: List<DueOccurrence>): CatchUpPlan =
        CatchUpPlan(
            ruleId = rule.id,
            ruleName = rule.name,
            amountMinor = rule.amountMinor,
            occurrences = occurrences,
        )
}

internal fun RecurringRuleEntity.spec(): RecurrenceSpec = RecurrenceSpec(
    unit = runCatching { RecurrenceUnit.valueOf(frequency) }.getOrDefault(RecurrenceUnit.MONTHLY),
    dayOfMonth = dayOfMonth,
    dayOfWeek = dayOfWeek,
    monthOfYear = monthOfYear,
)

internal fun RecurringRuleEntity.endDescription(): String = when {
    maxPeriods != null -> "共 $maxPeriods 期"
    endAt != null -> "截止 ${Instant.ofEpochMilli(endAt).atZone(ZoneId.systemDefault()).toLocalDate()}"
    else -> "永不结束"
}
