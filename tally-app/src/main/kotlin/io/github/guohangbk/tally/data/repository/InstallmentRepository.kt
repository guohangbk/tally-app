package io.github.guohangbk.tally.data.repository

import io.github.guohangbk.tally.data.db.dao.BillDao
import io.github.guohangbk.tally.data.db.dao.CategoryDao
import io.github.guohangbk.tally.data.db.dao.InstallmentPlanDao
import io.github.guohangbk.tally.data.db.dao.LedgerDao
import io.github.guohangbk.tally.data.db.entity.InstallmentPlanEntity
import io.github.guohangbk.tally.data.preferences.TallyPreferences
import io.github.guohangbk.tally.domain.model.BillType
import io.github.guohangbk.tally.domain.model.InstallmentDraft
import io.github.guohangbk.tally.domain.model.InstallmentEditDraft
import io.github.guohangbk.tally.domain.model.InstallmentPeriod
import io.github.guohangbk.tally.domain.model.InstallmentPlanView
import io.github.guohangbk.tally.domain.model.InstallmentStatus
import io.github.guohangbk.tally.domain.model.RepaymentMethod
import io.github.guohangbk.tally.domain.model.TallyError
import io.github.guohangbk.tally.domain.schedule.InstallmentSchedule
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/**
 * 分期计划：创建时一次算清排期，之后只按已生成期数推进。
 * 计划创建后不提供修改入口；删除或提前结清都不会删除已生成账单。
 */
class InstallmentRepository(
    private val planDao: InstallmentPlanDao,
    private val ledgerDao: LedgerDao,
    private val categoryDao: CategoryDao,
    /** 只读用途：查这一期到底有没有真实账单（生成水位不等于账上有钱）。 */
    private val billDao: BillDao,
    private val preferences: TallyPreferences,
    private val now: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val zoneId: () -> ZoneId = ZoneId::systemDefault,
) {
    val items: Flow<List<InstallmentPlanView>> = combine(
        planDao.observeAll(),
        ledgerDao.observeActive(),
        categoryDao.observeForNames(),
        billDao.observeDepositedPlanPeriods(),
    ) { plans, ledgers, categories, deposited ->
        val ledgerNames = ledgers.associate { it.id to it.name }
        val categoryNames = categories.associateBy { it.id }
        val depositedByPlan = deposited.groupBy({ it.planId }, { it.periodIndex })
            .mapValues { (_, indexes) -> indexes.toSet() }
        plans.map { plan ->
            InstallmentPlanView(
                plan = plan,
                ledgerName = ledgerNames[plan.ledgerId] ?: "未知账本",
                categoryPath = categoryPath(categoryNames[plan.categoryL1Id], categoryNames[plan.categoryL2Id]),
                categoryIconKey = categoryNames[plan.categoryL2Id]?.iconKey
                    ?: categoryNames[plan.categoryL1Id]?.iconKey,
                periods = InstallmentSchedule.periods(plan, zoneId()),
                depositedPeriods = depositedByPlan[plan.id].orEmpty(),
            )
        }
    }

    suspend fun find(id: String): InstallmentPlanEntity? = planDao.findById(id)

    suspend fun create(draft: InstallmentDraft): InstallmentPlanEntity {
        requireTargets(
            ledgerDao = ledgerDao,
            categoryDao = categoryDao,
            ledgerId = draft.ledgerId,
            categoryL1Id = draft.categoryL1Id,
            categoryL2Id = draft.categoryL2Id,
            type = BillType.EXPENSE,
        )
        val name = draft.name.trim()
        if (name.isEmpty()) throw TallyError.EmptyName
        val computation = InstallmentSchedule.compute(draft, zoneId())
        val timestamp = now()
        val plan = InstallmentPlanEntity(
            id = newId(),
            ledgerId = draft.ledgerId,
            name = name,
            note = draft.note,
            type = BillType.EXPENSE.name,
            principalMinor = computation.principalMinor,
            feeMinor = computation.feeMinor,
            feeMode = computation.feeMode.name,
            totalMinor = computation.totalMinor,
            periodCount = draft.periodCount,
            perPeriodMinor = computation.perPeriodMinor,
            tailMinor = computation.tailMinor,
            categoryL1Id = draft.categoryL1Id,
            categoryL2Id = draft.categoryL2Id,
            firstOccurredAt = draft.firstOccurredAt,
            intervalMonths = computation.intervalMonths,
            repaymentMethod = computation.repaymentMethod.name,
            annualRateBp = computation.annualRateBp,
            generatedPeriods = 0,
            status = InstallmentStatus.ACTIVE.name,
            createdAt = timestamp,
            updatedAt = timestamp,
            deviceId = preferences.deviceId(),
        )
        planDao.upsert(plan)
        return plan
    }

    /**
     * 改已创建的分期：只动账本、名称、分类与备注。期数、利率、手续费与首期入账日都不动——
     * 那些一变，已生成的账单就与计划对不上，而账单是已经落在账本里的东西。
     */
    suspend fun update(id: String, draft: InstallmentEditDraft): InstallmentPlanEntity {
        val plan = planDao.findById(id) ?: throw TallyError.InstallmentPlanNotFound
        requireTargets(
            ledgerDao = ledgerDao,
            categoryDao = categoryDao,
            ledgerId = draft.ledgerId,
            categoryL1Id = draft.categoryL1Id,
            categoryL2Id = draft.categoryL2Id,
            type = BillType.EXPENSE,
        )
        val name = draft.name.trim()
        if (name.isEmpty()) throw TallyError.EmptyName
        val updated = plan.copy(
            name = name,
            ledgerId = draft.ledgerId,
            categoryL1Id = draft.categoryL1Id,
            categoryL2Id = draft.categoryL2Id,
            note = draft.note,
            updatedAt = now(),
            version = plan.version + 1,
        )
        planDao.update(updated)
        return updated
    }

    /** 到期但尚未入账的期（含单独成期的手续费），按日期升序。 */
    suspend fun duePlans(today: LocalDate): List<Pair<InstallmentPlanEntity, List<Int>>> =
        planDao.findActive().mapNotNull { plan ->
            val indexes = dueIndexes(plan, today)
            if (indexes.isEmpty()) null else plan to indexes
        }

    suspend fun duePlan(planId: String, today: LocalDate): Pair<InstallmentPlanEntity, List<Int>>? {
        val plan = planDao.findById(planId) ?: return null
        if (plan.status != InstallmentStatus.ACTIVE.name) return null
        val indexes = dueIndexes(plan, today)
        return if (indexes.isEmpty()) null else plan to indexes
    }

    fun dueIndexes(plan: InstallmentPlanEntity, today: LocalDate): List<Int> {
        val endOfToday = today.plusDays(1).atStartOfDay(zoneId()).toInstant().toEpochMilli()
        return InstallmentSchedule.periods(plan, zoneId())
            .filter { it.index > plan.generatedPeriods && it.dueAt < endOfToday }
            .map { it.index }
    }

    /** 推进已生成期数；全部入账后计划自动结束。 */
    suspend fun advance(plan: InstallmentPlanEntity, generatedCount: Int) {
        val totalPeriods = InstallmentSchedule.periods(plan, zoneId()).size
        val generated = (plan.generatedPeriods + generatedCount).coerceAtMost(totalPeriods)
        val status = if (generated >= totalPeriods) InstallmentStatus.FINISHED else InstallmentStatus.ACTIVE
        planDao.advance(plan.id, generated, status.name, now())
    }

    /** 提前结清：未生成的未来期不再生成，已生成账单保留。 */
    suspend fun settleEarly(id: String) {
        val plan = planDao.findById(id) ?: throw TallyError.InstallmentPlanNotFound
        if (plan.status != InstallmentStatus.ACTIVE.name) throw TallyError.InstallmentSettled
        planDao.advance(id, plan.generatedPeriods, InstallmentStatus.SETTLED_EARLY.name, now())
    }

    /**
     * 房贷改利率：变更段从下一期待生成的那期起生效，已入账账单不动。
     * 等额本息按剩余本金与剩余期数重算月供，等额本金只影响后续利息；
     * 总额与每期列随之更新，保证 `Σ各期 = 总额` 继续成立。
     */
    suspend fun updateRate(id: String, annualRateBp: Int): InstallmentPlanEntity {
        if (annualRateBp !in 0..InstallmentSchedule.MAX_RATE_BP) throw TallyError.InstallmentRateInvalid
        val plan = planDao.findById(id) ?: throw TallyError.InstallmentPlanNotFound
        val method = runCatching { RepaymentMethod.valueOf(plan.repaymentMethod) }
            .getOrDefault(RepaymentMethod.PLAIN)
        if (method == RepaymentMethod.PLAIN) throw TallyError.InstallmentRateNotEditable
        if (plan.status != InstallmentStatus.ACTIVE.name) throw TallyError.InstallmentSettled
        val totalPeriods = InstallmentSchedule.periods(plan, zoneId()).size
        if (plan.generatedPeriods >= totalPeriods) throw TallyError.InstallmentSettled

        val withRate = InstallmentSchedule.withRate(plan, annualRateBp)
        val periods = InstallmentSchedule.periods(withRate, zoneId())
        val updated = withRate.copy(
            totalMinor = periods.sumOf { it.amountMinor },
            perPeriodMinor = periods.first().amountMinor,
            tailMinor = periods.last().amountMinor,
            updatedAt = now(),
            version = plan.version + 1,
        )
        planDao.update(updated)
        return updated
    }

    /** 改利率页的即时预览：同一套重算逻辑，保存前就能看到新月供与剩余利息。 */
    fun previewRate(plan: InstallmentPlanEntity, annualRateBp: Int): List<InstallmentPeriod>? =
        runCatching {
            InstallmentSchedule.periods(InstallmentSchedule.withRate(plan, annualRateBp), zoneId())
        }.getOrNull()

    suspend fun delete(id: String) {
        planDao.findById(id) ?: throw TallyError.InstallmentPlanNotFound
        planDao.softDelete(id, now())
    }

    fun periodDate(plan: InstallmentPlanEntity, index: Int): Long =
        InstallmentSchedule.dueDate(
            first = Instant.ofEpochMilli(plan.firstOccurredAt).atZone(zoneId()).toLocalDate(),
            index = index,
            zone = zoneId(),
            intervalMonths = plan.intervalMonths,
        )
}
