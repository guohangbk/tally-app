package io.github.guohangbk.tally.domain.model

import io.github.guohangbk.tally.support.EntityFixtures
import org.junit.jupiter.api.Test
import java.time.ZoneId
import kotlin.test.assertEquals

/**
 * 分期卡片的对外口径：一切以「账上真实存在的账单」为准，不能用生成水位。
 * 这是坑 3 的回归网——用户删掉某一期之后，进度、已入账期数、已还金额都要跟着回落。
 */
class InstallmentPlanViewTest {

    private val zone = ZoneId.of("Asia/Shanghai")
    private val basePlan = EntityFixtures.installmentPlan().copy(generatedPeriods = 5)

    private fun periods(count: Int = 360, amount: Long = 5_000L) =
        (1..count).map { InstallmentPeriod(index = it, dueAt = EntityFixtures.T0 + it, amountMinor = amount) }

    private fun view(deposited: Set<Int>, generated: Int = 5) = InstallmentPlanView(
        plan = basePlan.copy(generatedPeriods = generated),
        ledgerName = "日常账本",
        categoryPath = "住房",
        periods = periods(),
        depositedPeriods = deposited,
    )

    @Test
    fun `删掉第 5 期后已入账期数与进度回落到 4`() {
        val all = view(deposited = setOf(1, 2, 3, 4, 5))
        val afterDelete = view(deposited = setOf(1, 2, 3, 4))

        assertEquals(5, all.depositedCount)
        assertEquals(4, afterDelete.depositedCount)
        assertEquals(4f / 360f, afterDelete.progress)
        assertEquals(1, afterDelete.missingPeriods, "水位 5 但只有 4 笔，缺的那一期要报出来")
    }

    @Test
    fun `已还金额只算真实入账的期`() {
        assertEquals(4 * 5_000L, view(deposited = setOf(1, 2, 3, 4)).paidMinor)
        assertEquals(5 * 5_000L, view(deposited = setOf(1, 2, 3, 4, 5)).paidMinor)
    }

    @Test
    fun `生成水位不参与对外数字`() {
        // 水位故意抬到 9，账上只有 2 笔：所有对外数字仍按 2 笔算。
        val v = view(deposited = setOf(1, 2), generated = 9)
        assertEquals(2, v.depositedCount)
        assertEquals(2 * 5_000L, v.paidMinor)
        assertEquals(2f / 360f, v.progress)
        assertEquals(7, v.missingPeriods)
        assertEquals(360 - 9, v.remainingPeriods)
        assertEquals(10, v.nextPeriod?.index)
    }

    @Test
    fun `空账本没有除零`() {
        val v = InstallmentPlanView(
            plan = basePlan, ledgerName = "L", categoryPath = "C",
            periods = emptyList(), depositedPeriods = emptySet(),
        )
        assertEquals(0f, v.progress)
        assertEquals(0, v.depositedCount)
    }

    @Test
    fun `房贷口径与当前利率来自计划`() {
        val v = view(deposited = setOf(1, 2, 3, 4, 5))
        assertEquals(true, v.isMortgage)
        assertEquals(RepaymentMethod.EQUAL_PRINCIPAL, v.method)
        assertEquals(InstallmentStatus.ACTIVE, v.status)
        // 第 6 期起利率从 300bp 改成 280bp（fixture 的 rateChangesJson）。
        assertEquals(280, v.currentRateBp)
    }

    @Test
    fun `剩余本金只算未生成的期`() {
        val withPrincipal = InstallmentPlanView(
            plan = basePlan.copy(generatedPeriods = 2),
            ledgerName = "L", categoryPath = "C",
            periods = (1..4).map {
                InstallmentPeriod(index = it, dueAt = 0L, amountMinor = 100, principalMinor = 10L)
            },
            depositedPeriods = setOf(1, 2),
        )
        assertEquals(20L, withPrincipal.remainingPrincipalMinor)
    }

    @Test
    fun `应还日按月推进且不依赖系统默认时区`() {
        val first = java.time.LocalDate.of(2026, 1, 31)
        val due = io.github.guohangbk.tally.domain.schedule.InstallmentSchedule
            .dueDate(first, 2, zone, 1)
        assertEquals(
            java.time.LocalDate.of(2026, 2, 28),
            java.time.Instant.ofEpochMilli(due).atZone(zone).toLocalDate(),
        )
    }
}
