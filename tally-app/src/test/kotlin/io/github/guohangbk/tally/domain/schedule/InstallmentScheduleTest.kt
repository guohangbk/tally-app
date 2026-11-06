package io.github.guohangbk.tally.domain.schedule

import io.github.guohangbk.tally.domain.model.FeeMode
import io.github.guohangbk.tally.domain.model.InstallmentDraft
import io.github.guohangbk.tally.domain.model.RateSegment
import io.github.guohangbk.tally.domain.model.RepaymentMethod
import io.github.guohangbk.tally.support.EntityFixtures
import org.junit.jupiter.api.Test
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** 排期是纯算法，主流程里最该被自动化守住的一块：各期合计守恒、尾差收口、利率段生效。 */
class InstallmentScheduleTest {

    private val zone = ZoneId.of("Asia/Shanghai")

    private fun draft(
        principal: Long = 1_000_000L,
        count: Int = 12,
        fee: Long = 0L,
        feeMode: FeeMode = FeeMode.INCLUDED,
        method: RepaymentMethod = RepaymentMethod.PLAIN,
        rateBp: Int = 0,
    ) = InstallmentDraft(
        name = "测试", ledgerId = "l", categoryL1Id = null,
        principalMinor = principal, periodCount = count,
        firstOccurredAt = EntityFixtures.T0,
        feeMinor = fee, feeMode = feeMode, repaymentMethod = method, annualRateBp = rateBp,
    )

    @Test
    fun `普通分期的各期合计等于本金加手续费`() {
        val c = InstallmentSchedule.compute(draft(principal = 1_000_001L, fee = 999L), zone)
        assertEquals(12, c.periods.size)
        assertEquals(1_001_000L, c.periods.sumOf { it.amountMinor })
        assertEquals(c.totalMinor, c.periods.sumOf { it.amountMinor })
    }

    @Test
    fun `手续费单独成期时多一期且总额守恒`() {
        val c = InstallmentSchedule.compute(
            draft(principal = 12_000L, count = 3, fee = 600L, feeMode = FeeMode.SEPARATE), zone,
        )
        assertEquals(4, c.periods.size)
        assertEquals(12_600L, c.periods.sumOf { it.amountMinor })
        assertTrue(c.periods.last().isFee)
        assertEquals(600L, c.periods.last().amountMinor)
    }

    @Test
    fun `尾差落在最后一期`() {
        val c = InstallmentSchedule.compute(draft(principal = 100L, count = 3), zone)
        assertEquals(listOf(33L, 33L, 34L), c.periods.map { it.amountMinor })
    }

    @Test
    fun `等额本金每期本金相同且利息递减`() {
        val c = InstallmentSchedule.compute(
            draft(principal = 1_200_000L, count = 12, method = RepaymentMethod.EQUAL_PRINCIPAL, rateBp = 300), zone,
        )
        assertEquals(12, c.periods.size)
        assertEquals(1_200_000L, c.periods.sumOf { it.principalMinor ?: 0L })
        val interest = c.periods.map { it.interestMinor ?: 0L }
        assertTrue(interest.zipWithNext().all { (a, b) -> a >= b }, "利息应逐期不增")
        assertEquals(c.interestMinor, interest.sum())
        assertEquals(c.totalMinor, c.periods.sumOf { it.amountMinor })
    }

    @Test
    fun `等额本息各期还款额一致`() {
        val c = InstallmentSchedule.compute(
            draft(principal = 1_000_000L, count = 12, method = RepaymentMethod.EQUAL_PAYMENT, rateBp = 300), zone,
        )
        val amounts = c.periods.map { it.amountMinor }
        assertTrue(amounts.dropLast(1).zipWithNext().all { (a, b) -> a == b }, "除末期收口外月供应相同")
        assertEquals(c.totalMinor, amounts.sum())
    }

    @Test
    fun `利率变更只从指定期生效且已入账期不受影响`() {
        assertEquals(300, InstallmentSchedule.rateAt(300, emptyList(), 1))
        val segments = listOf(RateSegment(fromPeriod = 6, annualRateBp = 280))
        assertEquals(300, InstallmentSchedule.rateAt(300, segments, 5))
        assertEquals(280, InstallmentSchedule.rateAt(300, segments, 6))
        assertEquals(280, InstallmentSchedule.rateAt(300, segments, 99))

        val plan = EntityFixtures.installmentPlan().copy(generatedPeriods = 5)
        val changed = InstallmentSchedule.withRate(plan, 275)
        assertEquals(
            listOf(RateSegment(6, 275)),
            InstallmentSchedule.parseSegments(changed.rateChangesJson),
        )
        // 前 5 期金额与改利率之前完全一致。
        val before = InstallmentSchedule.periods(plan, zone).take(5).map { it.amountMinor }
        val after = InstallmentSchedule.periods(changed, zone).take(5).map { it.amountMinor }
        assertEquals(before, after)
    }

    @Test
    fun `同一期重复改利率只留最后一次`() {
        val plan = EntityFixtures.installmentPlan().copy(generatedPeriods = 5)
        val changed = InstallmentSchedule.withRate(InstallmentSchedule.withRate(plan, 260), 275)
        assertEquals(1, InstallmentSchedule.parseSegments(changed.rateChangesJson).size)
        assertEquals(275, InstallmentSchedule.parseSegments(changed.rateChangesJson).single().annualRateBp)
    }

    @Test
    fun `非法参数明确拒绝`() {
        assertFailsWith<Exception> { InstallmentSchedule.compute(draft(principal = 0L), zone) }
        assertFailsWith<Exception> { InstallmentSchedule.compute(draft(count = 0), zone) }
        assertFailsWith<Exception> {
            InstallmentSchedule.compute(
                draft(count = 481, method = RepaymentMethod.EQUAL_PRINCIPAL), zone,
            )
        }
        assertFailsWith<Exception> {
            InstallmentSchedule.withRate(EntityFixtures.installmentPlan(), 9_999)
        }
    }

    @Test
    fun `损坏的利率段 JSON 不炸只回退到基础利率`() {
        assertEquals(emptyList(), InstallmentSchedule.parseSegments("not-json"))
        assertEquals(300, InstallmentSchedule.rateAt(300, InstallmentSchedule.parseSegments("not-json"), 1))
    }
}
