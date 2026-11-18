package io.github.guohangbk.tally.domain.ai

import io.github.guohangbk.tally.data.db.entity.BillEntity
import io.github.guohangbk.tally.domain.model.BillListItem
import io.github.guohangbk.tally.domain.model.BillSummary
import io.github.guohangbk.tally.domain.model.CategoryTotal
import io.github.guohangbk.tally.domain.model.StatisticsSnapshot
import io.github.guohangbk.tally.domain.model.StatsGranularity
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/** 摘要素材：分类笔数直接取统计结果，不再为它整段拉一遍账单。 */
class MonthlyDigestBuilderTest {

    private fun statistics(averageMinor: Long) = StatisticsSnapshot(
        monthStart = 0L,
        summary = BillSummary(billCount = 2, expenseMinor = 20_000),
        categoryRanking = listOf(
            CategoryTotal(
                categoryId = "food",
                name = "餐饮",
                amountMinor = 20_000,
                share = 1f,
                billCount = 7,
            ),
        ),
        dailyTrend = emptyList(),
        previousExpenseMinor = 0L,
        expenseChangePercent = null,
        granularity = StatsGranularity.MONTH,
        averageExpenseMinor = averageMinor,
    )

    private fun expense(id: String, amountMinor: Long, categoryId: String = "food") = BillListItem(
        bill = BillEntity(
            id = id,
            ledgerId = "ledger",
            categoryL1Id = categoryId,
            categoryL2Id = null,
            type = "EXPENSE",
            amountMinor = amountMinor,
            occurredAt = 0L,
            createdAt = 0L,
            updatedAt = 0L,
            deviceId = "device",
        ),
        ledgerName = "日常账本",
        categoryL1Name = "餐饮",
        categoryL2Name = null,
    )

    @Test
    fun `分类笔数取统计结果`() {
        val digest = MonthlyDigestBuilder.build(
            statistics = statistics(averageMinor = 1_000),
            largeExpenses = emptyList(),
            ledgerName = "日常账本",
            excludePendingReimburse = false,
            budget = null,
            periodKey = "2026-11-01_2026-11-17",
        )
        assertEquals(7, digest.categories.single().count)
    }

    @Test
    fun `大额支出只保留过门槛的`() {
        val digest = MonthlyDigestBuilder.build(
            statistics = statistics(averageMinor = 1_000),
            largeExpenses = listOf(expense("big", 9_000), expense("small", 5_000)),
            ledgerName = "日常账本",
            excludePendingReimburse = false,
            budget = null,
            periodKey = "2026-11-01_2026-11-17",
        )
        assertEquals(listOf(9_000L), digest.largeExpenses.map { it.amountMinor })
    }
}
