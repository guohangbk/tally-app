package io.github.guohangbk.tally.data.repository

import io.github.guohangbk.tally.data.db.dao.BudgetDao
import io.github.guohangbk.tally.data.db.dao.CategoryDao
import io.github.guohangbk.tally.data.db.entity.BudgetEntity
import io.github.guohangbk.tally.data.preferences.TallyPreferences
import io.github.guohangbk.tally.domain.Period
import io.github.guohangbk.tally.domain.PeriodRange
import io.github.guohangbk.tally.domain.model.BudgetDraft
import io.github.guohangbk.tally.domain.model.BudgetItem
import io.github.guohangbk.tally.domain.model.BudgetPeriod
import io.github.guohangbk.tally.domain.model.BudgetSummary
import io.github.guohangbk.tally.domain.model.BudgetUsage
import io.github.guohangbk.tally.domain.model.budgetUsage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.time.ZoneId
import java.util.UUID

/**
 * 预算。两条不变量：
 *
 * 1. **已用金额只有一处算**：一律来自 `BillRepository.observeNetExpenseByCategory`——那是统计页分类排行
 *    用的同一份聚合（退款冲原单分类、不计入收支排除、报销待处理占位）。预算层自己再算一遍，迟早和统计对不上。
 * 2. **周期边界不存日期**：一律按当前每月起始日现算，改了起始日旧预算照样对得上。
 *
 * 总额预算的已用是「所有一级分类净支出之和」，与统计页的支出同数——不另设一套口径。
 */
class BudgetRepository(
    private val budgetDao: BudgetDao,
    private val categoryDao: CategoryDao,
    private val billRepository: BillRepository,
    private val preferences: TallyPreferences,
    private val now: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val zoneId: () -> ZoneId = ZoneId::systemDefault,
) {
    /**
     * 某个账本的全部预算（含关了提醒的，按创建序）。Room 那张流要一路透传下去，库里改了界面才跟着变。
     * 内容相同就不重发：下游整页都挂在这路上，空转一次就是把预算总览重画一次。
     */
    fun observeItems(ledgerId: String): Flow<List<BudgetItem>> = flow {
        budgetDao.observeByLedger(ledgerId).collect { rows -> emit(rows.map { toItem(it) }) }
    }.distinctUntilChanged().flowOn(Dispatchers.Default)

    suspend fun find(id: String): BudgetItem? = budgetDao.findById(id)?.let { toItem(it) }

    /** 同账本 + 同周期 + 同段 + 同范围已存在的那条：新建入口要点开它而不是再攒一条。 */
    suspend fun sameScope(ledgerId: String, period: BudgetPeriod, periodStart: Long, categoryId: String?): BudgetItem? =
        budgetDao.findSameScope(ledgerId, period.storage, periodStart, categoryId)?.let { toItem(it) }

    /**
     * 保存：同类（同账本 + 同周期 + 同范围）已存在就更新那一条，不产生第二条。
     * 金额 0 与阈值越界在这里拦掉，界面不该放行。
     */
    suspend fun save(draft: BudgetDraft): BudgetItem {
        val existing = draft.id?.let { budgetDao.findById(it) }
            ?: budgetDao.findSameScope(draft.ledgerId, draft.period.storage, draft.periodStart, draft.categoryId)
        val entity = BudgetEntity(
            id = existing?.id ?: newId(),
            ledgerId = draft.ledgerId,
            categoryId = draft.categoryId,
            period = draft.period.storage,
            periodStart = draft.periodStart,
            autoRenew = draft.autoRenew,
            amountMinor = draft.amountMinor.coerceAtLeast(0L),
            alertRatio = draft.alertRatio.coerceIn(1, 100),
            isActive = draft.isActive,
            createdAt = existing?.createdAt ?: now(),
            updatedAt = now(),
            deletedAt = null,
            deviceId = preferences.deviceId(),
            version = (existing?.version ?: 1).coerceAtLeast(1),
        )
        budgetDao.upsert(entity)
        return toItem(entity)
    }

    /** 提醒开关：关掉不删除，历史仍在、进度照算，只是这条不再发提醒。 */
    suspend fun setActive(id: String, active: Boolean): Boolean =
        budgetDao.setActive(id, active, now()) > 0

    /** 删除一条预算：软删（进备份的 deleted_at），账单一根都不动——预算只是规则，不是数据。 */
    suspend fun delete(id: String): Boolean = budgetDao.softDelete(id, now()) > 0

    /**
     * 某个账本在某周期的预算全貌：总额一条打头，分类若干。关了提醒的仍出现在列表与进度里（只打「提醒已关闭」标签），
     * 开关只影响提醒，不影响「这条钱花了多少」——首页聚合与速览同一个口径。
     *
     * [rangeAnchor] 给统计页翻到某年 / 某月时用：那一段的边界与那一行的归属按锚点现算，
     * 但「已过几天」永远按今天算——历史段取整段、未来段不出现，日均与剩余天数才不会是 1 天。
     */
    fun observeSummary(ledgerId: String, period: BudgetPeriod, startDay: Int, rangeAnchor: Long? = null): Flow<BudgetSummary> {
        val anchor = rangeAnchor ?: now()
        val range = periodRange(period, startDay, anchor)
        return combine(
            observeItems(ledgerId),
            billRepository.observeNetExpenseByCategory(ledgerId, range),
        ) { items, byCategory ->
            // 只列那一段的配置：翻了月 / 年，旧一段的行自动离开本页。
            buildSummary(items.filter { it.periodStart == range.startAt }, byCategory, period, range, now())
        }
            // 账单表任何一次写入都会让那一路重发一份聚合：内容没变就不往界面上推，
            // 否则在预算页记一笔账都会把整页重画一遍（2026-10-28 走查：「页面闪一下就成没有预算了」）。
            .distinctUntilChanged()
            .flowOn(Dispatchers.Default)
    }

    /** 提醒评估：所有未归档账本、处于激活状态、且已经跨过阈值或超支的预算。 */
    suspend fun activeAlerts(startDay: Int): List<BudgetUsage> {
        val timestamp = now()
        return budgetDao.findAllActive().groupBy { it.period }.flatMap { (periodName, rows) ->
            val period = BudgetPeriod.from(periodName)
            val range = periodRange(period, startDay, timestamp)
            rows.filter { it.periodStart == range.startAt }.groupBy { it.ledgerId }.flatMap { (ledgerId, ledgerRows) ->
                val byCategory = billRepository.observeNetExpenseByCategory(ledgerId, range).first()
                ledgerRows.map { entity ->
                    val item = toItem(entity)
                    val used = if (item.isTotal) byCategory.values.sum() else byCategory[item.categoryId.orEmpty()] ?: 0L
                    budgetUsage(item, used, range, elapsedDays(range, timestamp), range.lengthDays(zoneId()))
                }
            }
        }
    }

    private fun buildSummary(
        items: List<BudgetItem>,
        byCategory: Map<String, Long>,
        period: BudgetPeriod,
        range: PeriodRange,
        timestamp: Long,
    ): BudgetSummary {
        val elapsed = elapsedDays(range, timestamp)
        val totalDays = range.lengthDays()
        val total = items.firstOrNull { it.isTotal && it.period == period }
            ?.let { budgetUsage(it, byCategory.values.sum(), range, elapsed, totalDays) }
        val categories = items.filter { it.period == period && !it.isTotal }
            .map { budgetUsage(it, byCategory[it.categoryId.orEmpty()] ?: 0L, range, elapsed, totalDays) }
        return BudgetSummary(period = period, range = range, elapsedDays = elapsed, total = total, categories = categories)
    }

    /** 周期边界：月随每月起始日滚动，年从 1 月起算，两者互不干扰。 */
    fun periodRange(period: BudgetPeriod, startDay: Int, timestamp: Long = now()): PeriodRange =
        when (period) {
            BudgetPeriod.MONTHLY -> Period.monthRange(timestamp, startDay)
            BudgetPeriod.YEARLY -> Period.yearRange(timestamp)
        }

    /** 已过几天：翻到历史周期按整段天数算，当前周期按到今天。 */
    private fun elapsedDays(range: PeriodRange, timestamp: Long): Int {
        val today = java.time.Instant.ofEpochMilli(timestamp).atZone(zoneId()).toLocalDate()
        val start = java.time.Instant.ofEpochMilli(range.startAt).atZone(zoneId()).toLocalDate()
        val end = java.time.Instant.ofEpochMilli(range.endAtExclusive).atZone(zoneId()).toLocalDate()
        return when {
            today < start || today >= end -> range.lengthDays(zoneId())
            else -> Period.daysBetween(range.startAt, timestamp, zoneId()) + 1
        }
    }

    /** 分类被归档 / 删掉之后预算行仍要显示分类原名：查不到就退回空名，不让界面出现「未分类」。 */
    private suspend fun toItem(entity: BudgetEntity): BudgetItem {
        val category = entity.categoryId?.let { categoryDao.findById(it) }
        return BudgetItem(
            id = entity.id,
            ledgerId = entity.ledgerId,
            categoryId = entity.categoryId,
            categoryName = category?.name,
            categoryIconKey = category?.iconKey,
            period = BudgetPeriod.from(entity.period),
            periodStart = entity.periodStart,
            autoRenew = entity.autoRenew,
            amountMinor = entity.amountMinor,
            alertRatio = entity.alertRatio,
            isActive = entity.isActive,
        )
    }

    /**
     * 每月预算自动续期：把上一段开了 `auto_renew` 的启用行，复制成当前段的行（金额·阈值·分类·启用态都带上）。
     * 已存在同段同范围的不覆盖；幂等靠调用方的「本月已跑过」游标保证（见 ReminderCoordinator），
     * 所以删光本月预算后不会又自动长回来。复制出的是一条独立的新行，改本月不影响往月。
     */
    suspend fun rolloverMonthly(startDay: Int, timestamp: Long) {
        val current = Period.monthRange(timestamp, startDay)
        val prev = Period.monthRange(Period.shift(current.startAt, -1), startDay)
        val sources = budgetDao.findAutoRenewInPeriod(BudgetPeriod.MONTHLY.storage, prev.startAt)
        sources.forEach { src ->
            val exists = budgetDao.findSameScope(src.ledgerId, BudgetPeriod.MONTHLY.storage, current.startAt, src.categoryId)
            if (exists == null) {
                budgetDao.upsert(
                    src.copy(id = newId(), periodStart = current.startAt, createdAt = now(), updatedAt = now(), deletedAt = null, version = 1),
                )
            }
        }
    }

    /**
     * 预算历史：本账本、本节奏最近的若干段，逐段算最终用量与完成（历史段的支出用当期真实账单，额度用那一段当时设的）。
     * 只认 `periodStart > 0` 的真实周期段：v13→v14 迁移给旧行补的默认 0 会折成「1970年1月」的幽灵段，必须滤掉。
     */
    fun observeHistory(ledgerId: String, period: BudgetPeriod, startDay: Int, limit: Int = 12): Flow<List<BudgetSummary>> =
        observeItems(ledgerId)
            .flatMapLatest { items ->
                val starts = items.filter { it.period == period && it.periodStart > 0L }.map { it.periodStart }.distinct().sortedDescending().take(limit)
                if (starts.isEmpty()) flowOf(emptyList())
                else flow {
                    emit(
                        starts.mapNotNull { start ->
                            val range = periodRange(period, startDay, start)
                            val scoped = items.filter { it.period == period && it.periodStart == start }
                            if (scoped.isEmpty()) null
                            else buildSummary(scoped, billRepository.observeNetExpenseByCategory(ledgerId, range).first(), period, range, now())
                        },
                    )
                }
            }
            .distinctUntilChanged()
            .flowOn(Dispatchers.Default)
}
