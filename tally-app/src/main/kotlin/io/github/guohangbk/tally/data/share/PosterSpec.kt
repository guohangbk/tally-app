package io.github.guohangbk.tally.data.share

import io.github.guohangbk.tally.domain.Period
import io.github.guohangbk.tally.domain.PeriodRange
import io.github.guohangbk.tally.domain.formatAmount
import io.github.guohangbk.tally.domain.model.CategoryTotal
import io.github.guohangbk.tally.domain.model.BudgetPeriod
import io.github.guohangbk.tally.domain.model.BudgetSummary
import io.github.guohangbk.tally.domain.model.DailyTotal
import io.github.guohangbk.tally.domain.model.StatsGranularity
import io.github.guohangbk.tally.domain.model.StatisticsSnapshot
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 海报上的一对「标签 · 值」，值已经排好版（含符号、含脱敏点串）。 */
data class PosterStat(val label: String, val value: String)

/** 分类排行的一行：条长相对第一名归一，所以最小的分类也看得见。 */
data class PosterLine(val label: String, val value: String, val ratio: Float, val lead: Boolean)

/** 趋势柱：ratio 是相对最高一根的高度，0 也要画一根矮桩——空缺看着像数据没了。 */
data class PosterBar(val ratio: Float, val highlight: Boolean)

/**
 * 一张海报的全部内容与文案。**渲染器只认这个结构，不再算任何数**：
 * 金额、环比、占比、均值一律在 [PosterSpec.build] 里从统计结果取现成的，
 * 脱敏也是在这里换成点串——于是「预览画出来的」与「分享发出去的」必然是同一张。
 */
data class PosterSpec(
    val eyebrow: String,
    val periodTitle: String,
    val subline: String,
    val expenseLabel: String,
    val expenseValue: String,
    val change: String?,
    val stats: List<PosterStat>,
    val rankingTitle: String?,
    val ranking: List<PosterLine>,
    val trendTitle: String?,
    val bars: List<PosterBar>,
    /** 年维度那根月均虚线在柱区里的相对高度；其余维度为 null。 */
    val averageRatio: Float?,
    val trendCaption: String?,
    val budgetText: String?,
    val budgetRatio: Float?,
    val budgetOver: Boolean,
    val budgetTitle: String = "预算",
    /** 这段账没有 AI 小结时为 null：整段不画，不拿口径句冒充结论。 */
    val digest: String?,
    val footer: String,
) {
    companion object {
        private const val MASK = "••••"
        private const val MAX_CATEGORIES = 8
        private val monthTitleFormatter = DateTimeFormatter.ofPattern("yyyy 年 M 月", java.util.Locale.CHINA)
        private val dayFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd", java.util.Locale.CHINA)

        /**
         * 组装一张海报该说的话。
         *
         * @param grouping 千分位开关跟着用户的显示偏好走——海报是他手机里的账，不是另一种格式规范。
         * @param masked 脱敏：凡能反推出现金的都隐，比例与结构保留（「结构对比」正是隐去金额后还想表达的）。
         */
        fun build(
            snapshot: StatisticsSnapshot,
            ledgerName: String,
            rangeLabel: String,
            digestLine: String?,
            budget: BudgetSummary?,
            grouping: Boolean,
            masked: Boolean,
        ): PosterSpec {
            val granularity = snapshot.granularity
            val expense = snapshot.summary.expenseMinor
            val income = snapshot.summary.incomeMinor
            val incomeOnly = expense <= 0L && income > 0L
            val ranking: List<CategoryTotal> =
                (if (incomeOnly) snapshot.incomeRanking else snapshot.categoryRanking)
                    .filter { it.amountMinor > 0L }
                    .take(MAX_CATEGORIES)
            val topAmount = ranking.maxOfOrNull { it.amountMinor } ?: 0L

            // 桶序列：月=每天，年=每月，区间=按分桶。未来段不画，0 也留一根矮桩。
            val buckets = snapshot.dailyTrend.filterNot { it.isFuture }
            val series: List<DailyTotal> = if (incomeOnly) {
                buckets.map { it.copy(expenseMinor = it.incomeMinor) }
            } else {
                buckets
            }
            val peak = series.maxOfOrNull { it.expenseMinor } ?: 0L
            val highestIndex = series.indices.maxByOrNull { series[it].expenseMinor } ?: -1

            // 年报里「最贵的月份是 8 月」只有在确实是按月分桶时才成立。
            val yearMode = granularity == StatsGranularity.YEAR
            val highestCaption: String? = if (yearMode && highestIndex >= 0 && peak > 0L) {
                val month = Instant.ofEpochMilli(series[highestIndex].dayStart)
                    .atZone(ZoneId.systemDefault()).toLocalDate().monthValue
                "最贵的月份是 " + month + " 月 · " + money(peak, grouping, masked)
            } else {
                null
            }

            val averageWord = when {
                yearMode -> "月均"
                granularity == StatsGranularity.RANGE && snapshot.bucket != null -> "每桶均"
                else -> "日均"
            }
            val changeSentence = snapshot.expenseChangePercent?.let {
                val word = when {
                    yearMode -> "比去年"
                    granularity == StatsGranularity.RANGE -> "比上期"
                    else -> "比上月"
                }
                word + " " + (if (it >= 0) "+" else "−") + percentText(kotlin.math.abs(it)) + "%"
            }

            // 预算段只读「总额」那一条：分类预算不进海报，否则一段里塞四五条进度，读的人只看到颜色。
            val budgetUsage = budget?.total?.takeIf { it.item.isActive }
            val budgetText: String? = budgetUsage?.let { usage ->
                buildString {
                    append(if (yearMode) "年度预算 " else "总预算 ")
                    append(money(usage.item.amountMinor, grouping, masked))
                    append(" · 已用 ").append((usage.ratio * 100).toInt()).append('%')
                    if (!masked && usage.remainingDays > 0) append(" · 剩 ").append(usage.remainingDays).append(" 天")
                }
            }

            return PosterSpec(
                eyebrow = "TALLY · " + when {
                    yearMode -> "年度账单"
                    granularity == StatsGranularity.RANGE -> "区间账单"
                    else -> "月度账单"
                },
                periodTitle = rangeLabel,
                subline = "$ledgerName · ${snapshot.summary.billCount} 笔",
                expenseLabel = if (incomeOnly) "收入" else "支出",
                expenseValue = money(if (incomeOnly) income else expense, grouping, masked, symbol = true),
                change = changeSentence,
                stats = listOf(
                    PosterStat(if (incomeOnly) "支出" else "收入", money(if (incomeOnly) expense else income, grouping, masked)),
                    PosterStat(
                        "结余",
                        if (masked) MASK else signedMoney(snapshot.summary.balanceMinor, grouping),
                    ),
                    PosterStat(averageWord, money(snapshot.averageExpenseMinor, grouping, masked)),
                ),
                rankingTitle = if (ranking.isEmpty()) null else if (incomeOnly) "收入排行" else "分类排行",
                ranking = ranking.map { item ->
                    PosterLine(
                        label = item.name + " · " + (item.share * 100).toInt() + "%",
                        value = money(item.amountMinor, grouping, masked),
                        ratio = if (topAmount > 0) (item.amountMinor.toFloat() / topAmount).coerceIn(0.02f, 1f) else 0f,
                        lead = item.amountMinor == topAmount,
                    )
                },
                trendTitle = if (series.any { it.expenseMinor > 0 }) {
                    if (yearMode) "十二个月" else if (granularity == StatsGranularity.RANGE) "趋势 · 按桶" else "趋势 · 按日"
                } else {
                    null
                },
                bars = series.mapIndexed { index, day ->
                    PosterBar(
                        ratio = if (peak > 0) (day.expenseMinor.toFloat() / peak).coerceIn(0.04f, 1f) else 0.04f,
                        highlight = index == highestIndex && peak > 0L,
                    )
                },
                averageRatio = if (yearMode && peak > 0 && snapshot.averageExpenseMinor > 0) {
                    (snapshot.averageExpenseMinor.toFloat() / peak).coerceIn(0f, 1f)
                } else {
                    null
                },
                trendCaption = highestCaption,
                budgetText = budgetText,
                budgetRatio = budgetUsage?.ratio?.coerceAtLeast(0f),
                budgetOver = budgetUsage?.isOver == true,
                digest = digestLine?.let { if (masked) maskDigits(it) else it },
                footer = footerLine(),
            )
        }

        /**
         * 结算长图：一本账从第一笔到最后一笔的整段，抬头换成账本名与跨度。
         *
         * 走的还是这套字段——排行条就是分类占比条，预算条还是预算条，
         * 渲染器一句都不用改；区别只在抬头写法与不再讲环比 / 趋势（整本账没有「上期」）。
         */
        fun buildSettlement(
            snapshot: StatisticsSnapshot,
            ledgerName: String,
            spanLabel: String,
            dayCount: Int,
            monthCount: Int,
            budgetLimitMinor: Long?,
            grouping: Boolean,
            masked: Boolean,
        ): PosterSpec {
            val expense = snapshot.summary.expenseMinor
            val ranking = snapshot.categoryRanking
                .filter { it.amountMinor > 0L }
                .take(MAX_CATEGORIES)
            val topAmount = ranking.maxOfOrNull { it.amountMinor } ?: 0L
            val limit = budgetLimitMinor?.takeIf { it > 0L }
            val budgetRatio = limit?.let { expense.toFloat() / it }
            val budgetText = limit?.let { amount ->
                val delta = expense - amount
                buildString {
                    append("预算 ").append(money(amount, grouping, masked))
                    append(" · ")
                    append(
                        when {
                            delta > 0L -> "已超支 +" + money(delta, grouping, masked)
                            delta < 0L -> "剩余 " + money(-delta, grouping, masked)
                            else -> "正好用完"
                        },
                    )
                }
            }

            return PosterSpec(
                eyebrow = "TALLY · 账本结算",
                periodTitle = ledgerName.ifBlank { "账本" },
                subline = spanLabel,
                expenseLabel = "支出",
                expenseValue = money(expense, grouping, masked, symbol = true),
                change = null,
                stats = listOf(
                    PosterStat("笔数", "${snapshot.summary.billCount} 笔"),
                    PosterStat("日均", money(if (dayCount > 0) expense / dayCount else 0L, grouping, masked)),
                    PosterStat("月均", money(if (monthCount > 0) expense / monthCount else 0L, grouping, masked)),
                ),
                rankingTitle = if (ranking.isEmpty()) null else "分类占比",
                ranking = ranking.map { item ->
                    PosterLine(
                        label = item.name + " · " + (item.share * 100).toInt() + "%",
                        value = money(item.amountMinor, grouping, masked),
                        ratio = if (topAmount > 0) (item.amountMinor.toFloat() / topAmount).coerceIn(0.02f, 1f) else 0f,
                        lead = item.amountMinor == topAmount,
                    )
                },
                trendTitle = null,
                bars = emptyList(),
                averageRatio = null,
                trendCaption = null,
                budgetText = budgetText,
                budgetRatio = budgetRatio,
                budgetOver = limit != null && expense > limit,
                budgetTitle = "预算对比",
                digest = null,
                footer = footerLine(),
            )
        }

        /** 落款：两张海报同一句，日期取生成当天。 */
        private fun footerLine(): String = "由 Tally 塔莉生成 · " +
            Instant.ofEpochMilli(System.currentTimeMillis()).atZone(ZoneId.systemDefault()).toLocalDate().format(dayFormatter)

        /** 元为单位、带千分位；脱敏时换成点串，宽度靠排版对齐而不是保留位数。 */
        private fun money(minor: Long, grouping: Boolean, masked: Boolean, symbol: Boolean = false): String {
            if (masked) return if (symbol) "¥" + MASK else MASK
            val text = formatAmount(minor, grouping)
            return if (symbol) "¥" + text else text
        }

        private fun signedMoney(minor: Long, grouping: Boolean): String =
            if (minor >= 0) "+" + formatAmount(minor, grouping) else "−" + formatAmount(-minor, grouping)

        /** 百分比只留一位小数，整数值不带小数点：海报上「+18%」比「+18.0%」好读。 */
        private fun percentText(value: Float): String {
            val tenths = kotlin.math.round(value * 10).toInt()
            return if (tenths % 10 == 0) (tenths / 10).toString() else "${tenths / 10}.${tenths % 10}"
        }

        /**
         * 结论文案里的数字一律盖掉。口径句本身就写着「剔除 2 笔 1,413.00；待报销 0 笔 0.00」，
         * AI 正文更可能直接报金额——脱敏若只盖 B/C/D 段，这一段会把现金从句子里漏出去。
         */
        private fun maskDigits(text: String): String {
            if (text.none(Char::isDigit)) return text
            val out = StringBuilder(text.length)
            var index = 0
            while (index < text.length) {
                if (text[index].isDigit()) {
                    var end = index
                    // 数字连同它中间的分隔符一起盖：1,413.00 是一个整体，不是三段。
                    while (end < text.length && (text[end].isDigit() ||
                            (text[end] == ',' || text[end] == '.' || text[end] == '，') &&
                            end + 1 < text.length && text[end + 1].isDigit())
                    ) end++
                    out.append(MASK)
                    index = end
                } else {
                    out.append(text[index])
                    index++
                }
            }
            return out.toString()
        }
    }
}

/** 海报标题行那句期间：与统计页顶栏同一套措辞，两处不该各造一个说法。 */
fun posterPeriodLabel(
    granularity: StatsGranularity,
    range: PeriodRange,
    startDay: Int,
): String = when (granularity) {
    StatsGranularity.MONTH -> Period.label(range, startDay)
    StatsGranularity.YEAR -> Period.toDate(range.startAt).year.toString() + " 年"
    StatsGranularity.RANGE -> Period.rangeLabel(range.startAt, range.endAtExclusive)
}

/** 预算段读的周期：年维度读「每年预算」那一组，其余读每月——与统计页预算段同源。 */
fun budgetPeriodOf(granularity: StatsGranularity): BudgetPeriod =
    if (granularity == StatsGranularity.YEAR) BudgetPeriod.YEARLY else BudgetPeriod.MONTHLY
