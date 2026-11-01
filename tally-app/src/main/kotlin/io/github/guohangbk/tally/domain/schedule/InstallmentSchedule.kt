package io.github.guohangbk.tally.domain.schedule

import io.github.guohangbk.tally.data.db.entity.InstallmentPlanEntity
import io.github.guohangbk.tally.domain.model.FeeMode
import io.github.guohangbk.tally.domain.model.InstallmentDraft
import io.github.guohangbk.tally.domain.model.InstallmentPeriod
import io.github.guohangbk.tally.domain.model.RateSegment
import io.github.guohangbk.tally.domain.model.RepaymentMethod
import io.github.guohangbk.tally.domain.model.TallyError
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 分期排期。创建时一次算清每期金额与尾差，之后只按 `generated_periods` 往前推进，
 * 因此「各期合计 = 总额」在创建时就成立，而不是生成完再补。
 *
 * 三种排期：
 * - 普通分期：本金按期均摊，手续费可并入每期或单独成最后一期；
 * - 房贷等额本息：每期还款额相同，本金部分 = 还款额 − 当期利息，末期用剩余本金收口；
 * - 房贷等额本金：每期本金相同，利息按剩余本金计，末期吃掉分币尾差。
 *
 * 利率变动只影响「尚未入账」的期：变更段从 `generated_periods + 1` 起生效，
 * 已生成账单的金额原样保留。等额本息在每个变更段起点按剩余本金重算月供。
 */
object InstallmentSchedule {
    const val MAX_PERIODS = 480

    /** 年利率上限 30%，防止误把 BP 当百分数填出天文数字。 */
    const val MAX_RATE_BP = 3000

    private val MC = MathContext(24, RoundingMode.HALF_UP)

    data class Computation(
        val principalMinor: Long,
        val feeMinor: Long,
        val totalMinor: Long,
        val perPeriodMinor: Long,
        val tailMinor: Long,
        val feeMode: FeeMode,
        val intervalMonths: Int,
        val periods: List<InstallmentPeriod>,
        val repaymentMethod: RepaymentMethod = RepaymentMethod.PLAIN,
        val annualRateBp: Int = 0,
        val interestMinor: Long = 0,
    )

    fun compute(draft: InstallmentDraft, zone: ZoneId = ZoneId.systemDefault()): Computation {
        if (draft.principalMinor <= 0L) throw TallyError.AmountOutOfRange
        if (draft.periodCount !in 1..MAX_PERIODS) throw TallyError.InstallmentPeriodInvalid
        if (draft.feeMinor < 0L) throw TallyError.AmountOutOfRange
        if (draft.intervalMonths !in 1..12) throw TallyError.InstallmentPeriodInvalid
        return when (draft.repaymentMethod) {
            RepaymentMethod.PLAIN -> computePlain(draft, zone)
            RepaymentMethod.EQUAL_PAYMENT, RepaymentMethod.EQUAL_PRINCIPAL -> computeMortgage(draft, zone)
        }
    }

    private fun computePlain(draft: InstallmentDraft, zone: ZoneId): Computation {
        val total = draft.principalMinor + draft.feeMinor
        val amortized = when (draft.feeMode) {
            FeeMode.INCLUDED -> total
            FeeMode.SEPARATE -> draft.principalMinor
        }
        val per = amortized / draft.periodCount
        if (per <= 0L) throw TallyError.InstallmentAmountTooSmall
        val tail = amortized - per * (draft.periodCount - 1)

        val firstDate = Instant.ofEpochMilli(draft.firstOccurredAt).atZone(zone).toLocalDate()
        val interval = draft.intervalMonths
        val feeAsExtraPeriod = draft.feeMode == FeeMode.SEPARATE && draft.feeMinor > 0L
        val periods = buildList {
            for (index in 1..draft.periodCount) {
                add(
                    InstallmentPeriod(
                        index = index,
                        dueAt = dueDate(firstDate, index, zone, interval),
                        amountMinor = if (index == draft.periodCount) tail else per,
                    ),
                )
            }
            if (feeAsExtraPeriod) {
                add(
                    InstallmentPeriod(
                        index = draft.periodCount + 1,
                        dueAt = dueDate(firstDate, draft.periodCount + 1, zone, interval),
                        amountMinor = draft.feeMinor,
                        isFee = true,
                    ),
                )
            }
        }
        return Computation(
            principalMinor = draft.principalMinor,
            feeMinor = draft.feeMinor,
            totalMinor = total,
            perPeriodMinor = per,
            tailMinor = tail,
            feeMode = draft.feeMode,
            intervalMonths = interval,
            periods = periods,
        )
    }

    private fun computeMortgage(draft: InstallmentDraft, zone: ZoneId): Computation {
        if (draft.annualRateBp !in 0..MAX_RATE_BP) throw TallyError.InstallmentRateInvalid
        val firstDate = Instant.ofEpochMilli(draft.firstOccurredAt).atZone(zone).toLocalDate()
        val periods = mortgagePeriods(
            principalMinor = draft.principalMinor,
            periodCount = draft.periodCount,
            method = draft.repaymentMethod,
            annualRateBp = draft.annualRateBp,
            segments = emptyList(),
            firstDate = firstDate,
            zone = zone,
        )
        return Computation(
            principalMinor = draft.principalMinor,
            feeMinor = 0L,
            totalMinor = periods.sumOf { it.amountMinor },
            perPeriodMinor = periods.first().amountMinor,
            tailMinor = periods.last().amountMinor,
            feeMode = FeeMode.INCLUDED,
            intervalMonths = 1,
            periods = periods,
            repaymentMethod = draft.repaymentMethod,
            annualRateBp = draft.annualRateBp,
            interestMinor = periods.sumOf { it.interestMinor ?: 0L },
        )
    }

    /** 已落库计划的实际排期；旧数据缺列时按「本金 = 总额、无手续费」处理。 */
    fun periods(plan: InstallmentPlanEntity, zone: ZoneId = ZoneId.systemDefault()): List<InstallmentPeriod> {
        val method = runCatching { RepaymentMethod.valueOf(plan.repaymentMethod) }
            .getOrDefault(RepaymentMethod.PLAIN)
        val firstDate = Instant.ofEpochMilli(plan.firstOccurredAt).atZone(zone).toLocalDate()
        if (method != RepaymentMethod.PLAIN) {
            return mortgagePeriods(
                principalMinor = plan.principalMinor.takeIf { it > 0L } ?: plan.totalMinor,
                periodCount = plan.periodCount,
                method = method,
                annualRateBp = plan.annualRateBp,
                segments = parseSegments(plan.rateChangesJson),
                firstDate = firstDate,
                zone = zone,
            )
        }
        val feeMode = runCatching { FeeMode.valueOf(plan.feeMode) }.getOrDefault(FeeMode.INCLUDED)
        val interval = plan.intervalMonths.coerceAtLeast(1)
        val feeAsExtraPeriod = feeMode == FeeMode.SEPARATE && plan.feeMinor > 0L
        return buildList {
            for (index in 1..plan.periodCount) {
                add(
                    InstallmentPeriod(
                        index = index,
                        dueAt = dueDate(firstDate, index, zone, interval),
                        amountMinor = if (index == plan.periodCount) plan.tailMinor else plan.perPeriodMinor,
                    ),
                )
            }
            if (feeAsExtraPeriod) {
                add(
                    InstallmentPeriod(
                        index = plan.periodCount + 1,
                        dueAt = dueDate(firstDate, plan.periodCount + 1, zone, interval),
                        amountMinor = plan.feeMinor,
                        isFee = true,
                    ),
                )
            }
        }
    }

    fun rateAt(plan: InstallmentPlanEntity, index: Int): Int =
        rateAt(plan.annualRateBp, parseSegments(plan.rateChangesJson), index)

    fun rateAt(baseBp: Int, segments: List<RateSegment>, index: Int): Int =
        segments.lastOrNull { it.fromPeriod <= index }?.annualRateBp ?: baseBp

    /**
     * 追加一条利率变更：从下一期待生成的那期起生效；同一期重复改只留最后一次。
     * 已入账的期不在变更范围内，因此重算后的历史金额与账单一致。
     */
    fun withRate(plan: InstallmentPlanEntity, annualRateBp: Int): InstallmentPlanEntity {
        if (annualRateBp !in 0..MAX_RATE_BP) throw TallyError.InstallmentRateInvalid
        val from = plan.generatedPeriods + 1
        val segments = parseSegments(plan.rateChangesJson)
            .filter { it.fromPeriod < from } + RateSegment(from, annualRateBp)
        return plan.copy(rateChangesJson = encodeSegments(segments))
    }

    fun parseSegments(json: String?): List<RateSegment> {
        if (json.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(json)
            (0 until array.length()).mapNotNull { position ->
                val item = array.optJSONObject(position) ?: return@mapNotNull null
                val from = item.optInt("from", 0)
                val bp = item.optInt("bp", -1)
                if (from >= 1 && bp in 0..MAX_RATE_BP) RateSegment(from, bp) else null
            }.sortedBy { it.fromPeriod }
        }.getOrDefault(emptyList())
    }

    fun encodeSegments(segments: List<RateSegment>): String? {
        if (segments.isEmpty()) return null
        val array = JSONArray()
        segments.sortedBy { it.fromPeriod }.forEach { segment ->
            array.put(JSONObject().put("from", segment.fromPeriod).put("bp", segment.annualRateBp))
        }
        return array.toString()
    }

    /** 第 [index] 期（1 起）的应入账日；`LocalDate.plusMonths` 会自动把 31 日压到当月最后一天。 */
    fun dueDate(first: LocalDate, index: Int, zone: ZoneId, intervalMonths: Int = 1): Long =
        first.plusMonths((index - 1).toLong() * intervalMonths)
            .atStartOfDay(zone)
            .toInstant()
            .toEpochMilli()

    private fun mortgagePeriods(
        principalMinor: Long,
        periodCount: Int,
        method: RepaymentMethod,
        annualRateBp: Int,
        segments: List<RateSegment>,
        firstDate: LocalDate,
        zone: ZoneId,
    ): List<InstallmentPeriod> {
        if (periodCount !in 1..MAX_PERIODS) throw TallyError.InstallmentPeriodInvalid
        if (principalMinor < periodCount) throw TallyError.InstallmentAmountTooSmall
        if (annualRateBp !in 0..MAX_RATE_BP) throw TallyError.InstallmentRateInvalid
        val boundaries = segments.map { it.fromPeriod }.toSet()
        val fixedPrincipal = if (method == RepaymentMethod.EQUAL_PRINCIPAL) principalMinor / periodCount else 0L
        var balance = principalMinor
        var payment = if (method == RepaymentMethod.EQUAL_PAYMENT) {
            monthlyPayment(balance, periodCount, rateAt(annualRateBp, segments, 1))
        } else {
            0L
        }
        if (method == RepaymentMethod.EQUAL_PAYMENT && payment <= 0L) throw TallyError.InstallmentAmountTooSmall

        return buildList {
            for (index in 1..periodCount) {
                val rate = rateAt(annualRateBp, segments, index)
                if (method == RepaymentMethod.EQUAL_PAYMENT && (index == 1 || index in boundaries)) {
                    payment = monthlyPayment(balance, periodCount - index + 1, rate)
                }
                val interest = monthlyInterest(balance, rate)
                // 每期至少留 1 分钱本金给后面各期，末期再用剩余本金收口。
                val reserve = (periodCount - index).toLong()
                val principalPart = when {
                    index == periodCount -> balance
                    method == RepaymentMethod.EQUAL_PRINCIPAL -> fixedPrincipal.coerceAtMost(balance - reserve)
                    else -> (payment - interest).coerceAtMost(balance - reserve)
                }
                if (principalPart <= 0L) throw TallyError.InstallmentAmountTooSmall
                balance -= principalPart
                add(
                    InstallmentPeriod(
                        index = index,
                        dueAt = dueDate(firstDate, index, zone),
                        amountMinor = principalPart + interest,
                        principalMinor = principalPart,
                        interestMinor = interest,
                    ),
                )
            }
        }
    }

    /** 等额本息月供：P·r·(1+r)^n / ((1+r)^n − 1)，按分四舍五入。 */
    private fun monthlyPayment(balanceMinor: Long, months: Int, annualRateBp: Int): Long {
        if (months <= 0) return balanceMinor
        if (annualRateBp <= 0) return balanceMinor / months
        val rate = monthlyRate(annualRateBp)
        val factor = BigDecimal.ONE.add(rate).pow(months, MC)
        return BigDecimal(balanceMinor)
            .multiply(rate)
            .multiply(factor)
            .divide(factor.subtract(BigDecimal.ONE), MC)
            .setScale(0, RoundingMode.HALF_UP)
            .toLong()
    }

    private fun monthlyInterest(balanceMinor: Long, annualRateBp: Int): Long {
        if (annualRateBp <= 0 || balanceMinor <= 0L) return 0L
        return BigDecimal(balanceMinor)
            .multiply(monthlyRate(annualRateBp))
            .setScale(0, RoundingMode.HALF_UP)
            .toLong()
    }

    /** BP（万分之一）→ 月利率：bp / 10000 / 12。 */
    private fun monthlyRate(annualRateBp: Int): BigDecimal =
        BigDecimal(annualRateBp).divide(BigDecimal(120_000), MC)
}
