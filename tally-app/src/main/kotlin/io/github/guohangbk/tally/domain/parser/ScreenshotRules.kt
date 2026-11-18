package io.github.guohangbk.tally.domain.parser

import io.github.guohangbk.tally.domain.model.BillType
import io.github.guohangbk.tally.domain.ingest.findOrderNo
import io.github.guohangbk.tally.domain.model.DraftField
import io.github.guohangbk.tally.domain.model.TimePrecision
import io.github.guohangbk.tally.domain.parseAmount
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 支付截图的字段抽取。端侧 OCR 出的文本先由这里拆成
 * 金额 / 收支 / 商户 / 日期时间 / 支付方式 / 订单号，AI 视觉只是在这一步之上的增强。
 *
 * 抽不出来的字段一律留空并记进 [ScreenshotFields.missingFields]：
 * 草稿页要显示「以下字段需你确认」，而不是拿一个猜出来的值冒充识别结果。
 */
data class ScreenshotFields(
    val amountMinor: Long?,
    val type: BillType,
    val occurredAt: Long?,
    val timePrecision: TimePrecision,
    val merchant: String?,
    val paymentMethod: String?,
    val orderNo: String?,
    val rawText: String,
    val missingFields: Set<DraftField>,
) {
    /** 订单号或图片指纹命中已有账单时，页面要提示而不是静默跳过。 */
    val hasOrderNo: Boolean get() = !orderNo.isNullOrBlank()
}

object ScreenshotRules {
    /** 抬头行数：支付宝/微信的「支付成功 / 退款成功 / 收款到账」都出现在最前面两三行。 */
    private const val HEADER_LINES = 3

    /** 总额标签：截图里同时出现「优惠 / 立减 / 商品价」时，只有这几个才是这一笔的金额。 */
    val totalLabels = listOf("实付款", "实付", "实际支付", "支付金额", "付款金额", "订单金额", "应付", "合计", "总计")

    /**
     * 终值标签：一笔订单里**唯一**的那个数。
     * 「订单金额 / 应付 / 合计」只是同一笔的另一个字段，滴滴那类截图会把两行都印出来，
     * 拿它们当分块边界，一张单笔截图就会裂成两笔（真机上撞见过）。
     */
    private val finalLabels = listOf("实付款", "实付", "实际支付", "支付金额", "付款金额")

    private val finalLabelRegex = Regex(
        finalLabels.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) },
    )

    private val totalLabelRegex = Regex(
        totalLabels.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) },
    )

    /** 这些行里的数字是折扣或商品明细，不是这一笔付出去的钱。 */
    private val discountWords = listOf("优惠", "立减", "满减", "红包", "折扣", "抵扣", "免息", "券")

    private val anyAmount = Regex("""[¥￥]\s*([0-9][0-9,，]*(?:\.[0-9]{1,2})?)""")
    private val bareAmount = Regex("""(?m)^\s*([0-9]{1,3}(?:,[0-9]{3})*\.[0-9]{2})\s*$""")
    private val amountAfterLabel = Regex(
        """(?:实付款|实付|实际支付|支付金额|付款金额|订单金额|应付|合计|总计)\s*[:：]?\s*[¥￥]?\s*([0-9][0-9,，]*(?:\.[0-9]{1,2})?)""",
    )

    /**
     * 日期写法。[yearGroup] 为 null 表示原文没写年份，按采集当年补。
     *
     * 分组下标逐条写死：以前把「年-月-日 时:分」和「月-日 时:分」塞进同一套下标里读，
     * 于是**没有年份的写法把月份当成了年份**，`10-14 21:06` 必定解析失败——
     * 而省年份正是微信 / 支付宝截图里最常见的写法。
     */
    private class DatePattern(
        val regex: Regex,
        val yearGroup: Int?,
        val monthGroup: Int,
        val dayGroup: Int,
        val hourGroup: Int? = null,
        val minuteGroup: Int? = null,
    ) {
        fun hasClock(match: MatchResult): Boolean =
            hourGroup?.let { match.groupValues.getOrNull(it)?.isNotEmpty() } == true
    }

    /** 顺序即优先级：带年份的两条最可靠，没年份的两条只在能明确看出是日期时才认。 */
    private val datePatterns = listOf(
        // 2026-11-13 21:06 / 2026-11-13T21:06:30 / 2026/11/13
        DatePattern(Regex("""(\d{4})\s*[-/]\s*(\d{1,2})\s*[-/]\s*(\d{1,2})(?:[ T]+(\d{1,2})\s*[:：](\d{2}))?"""), 1, 2, 3, 4, 5),
        // 2026年11月13日 21:06
        DatePattern(Regex("""(\d{4})\s*年\s*(\d{1,2})\s*月\s*(\d{1,2})\s*[日号]?(?:[ T]*(\d{1,2})\s*[:：](\d{2}))?"""), 1, 2, 3, 4, 5),
        // 11月13日 21:06（没年份，但「月/日」字样够明确）
        DatePattern(Regex("""(\d{1,2})\s*月\s*(\d{1,2})\s*[日号]?(?:[ T]*(\d{1,2})\s*[:：](\d{2}))?"""), null, 1, 2, 3, 4),
        // 11-13 21:06（既没年份也没「月日」，必须紧跟时刻，否则订单号、期数里的「1-2」都会被当成日期）
        DatePattern(Regex("""(\d{1,2})\s*[-/]\s*(\d{1,2})\s+(\d{1,2})\s*[:：](\d{2})"""), null, 1, 2, 3, 4),
    )

    private val labeled = Regex("""(?:商户|收款方|商品|商品说明|订单标题|付款方)\s*[:：]\s*([^\n]{1,40})""")
    private val paymentRegex = Regex("""(?:支付方式|付款方式使用|使用|付款方式)\s*[:：]?\s*([^\n]{1,20})""")
        /** 「收款方」是商户标签，不能当成收钱的信号，所以这里只认完整的进账说法。 */
    private val incomeMarkers = listOf("已退款", "退款成功", "退款已到账", "收款成功", "已存入", "转入", "工资", "奖金", "收益", "利息", "分红")
    private val paymentMethodWords = listOf(
        "余额宝", "花呗", "零钱", "零钱通", "银行卡", "信用卡", "储蓄卡", "微信支付", "支付宝", "余额", "数字人民币",
    )

    fun extract(
        rawText: String,
        nowMillis: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): ScreenshotFields {
        return extractAll(rawText, nowMillis, zone).firstOrNull() ?: ScreenshotFields(
            amountMinor = null,
            type = BillType.EXPENSE,
            occurredAt = null,
            timePrecision = TimePrecision.DATE,
            merchant = null,
            paymentMethod = null,
            orderNo = findOrderNo(rawText),
            rawText = rawText.replace("\r", "\n").trim(),
            missingFields = buildSet {
                add(DraftField.AMOUNT)
                add(DraftField.DATE)
                add(DraftField.CATEGORY)
            },
        )
    }

    /** 一张图里的多个订单块：每个总额标签各自成账；拆不开时交给 AI 整理文本。 */
    fun extractAll(
        rawText: String,
        nowMillis: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<ScreenshotFields> {
        val text = rawText.replace("\r", "\n").trim()
        val blocks = orderBlocks(text)
        return (blocks.ifEmpty { listOf(text) }).map { block -> extractBlock(block, nowMillis, zone) }
    }

    /**
     * 本机的拆分规则认不出「这一篇里有几笔」时才交给 AI：
     * **一个终值标签都没有、金额却有好几个**——没有标签就无从判断那几个数是一笔还是几笔。
     *
     * 反过来，只要出现终值标签，本机就拆得动：标签只有一个就是一笔（哪怕同时印着「订单金额」），
     * 有两个以上才按它切开。这条以前写成「标签多于一个就是拆不开」，滴滴那类
     * 「订单金额 + 实付款」的单笔截图因此每张都被白问一次 AI。
     */
    fun needsLlm(rawText: String): Boolean {
        val text = rawText.replace("\r", "\n")
        if (finalLabelRegex.containsMatchIn(text)) return false
        val amounts = anyAmount.findAll(text).count() + bareAmount.findAll(text).count()
        return totalLabelCount(text) == 0 && amounts > 1
    }

    /** 「实付款」不能被「实付款 / 实付」重复算两次，否则每张普通截图都会误走 AI。 */
    fun totalLabelCount(rawText: String): Int =
        totalLabelRegex.findAll(rawText.replace("\r", "\n")).count()

    private fun extractBlock(text: String, nowMillis: Long, zone: ZoneId): ScreenshotFields {
        val amount = pickAmount(text)
        // 取第一条**能解析成真实日期**的写法：匹配上了但月/日越界（把期数当日期）要往下一条继续试，
        // 而不是就此认定「这篇没有日期」。
        val dateHit = datePatterns.firstNotNullOfOrNull { pattern ->
            pattern.regex.find(text)?.let { match ->
                toMillis(match, pattern, nowMillis, zone)?.let { millis -> DateHit(millis, pattern.hasClock(match)) }
            }
        }
        val occurredAt = dateHit?.millis
        val merchant = labeled.find(text)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }
            ?: guessMerchant(text)
        val paymentMethod = paymentRegex.find(text)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }
            ?: paymentMethodWords.firstOrNull { text.contains(it) }
        val orderNo = findOrderNo(text)
        // 收支方向只看抬头几行：截图里任何一段文字（包括别人写在图里的「归类到工资」）
        // 都不该把一笔支出翻成收入。
        val header = text.lineSequence().take(HEADER_LINES).joinToString(" ")
        val type = if (incomeMarkers.any { header.contains(it) }) BillType.INCOME else BillType.EXPENSE
        val hasTime = dateHit?.hasClock == true

        val missing = buildSet {
            if (amount == null) add(DraftField.AMOUNT)
            if (occurredAt == null) add(DraftField.DATE)
            add(DraftField.CATEGORY) // 截图里没有分类信息，必须靠关键词表或 AI 建议
        }

        return ScreenshotFields(
            amountMinor = amount,
            type = type,
            occurredAt = occurredAt,
            timePrecision = if (hasTime) TimePrecision.DATETIME else TimePrecision.DATE,
            merchant = merchant,
            paymentMethod = paymentMethod,
            orderNo = orderNo,
            rawText = text,
            missingFields = missing,
        )
    }

    /**
     * 微信/支付宝的账单列表按「终值标签」切开；单笔截图仍是一个整块。
     * 终值标签只出现一次（哪怕同时还有「订单金额」）时一律当单笔——那是同一笔的两个字段。
     */
    private fun orderBlocks(text: String): List<String> {
        val lines = text.lines()
        fun startsOf(regex: Regex): List<Int> = lines.mapIndexedNotNull { index, line ->
            index.takeIf { regex.containsMatchIn(line) }
        }
        val finalStarts = startsOf(finalLabelRegex)
        val starts = when {
            finalStarts.size >= 2 -> finalStarts
            finalStarts.isNotEmpty() -> return emptyList()
            else -> startsOf(totalLabelRegex)
        }
        if (starts.size < 2) return emptyList()
        return starts.mapIndexed { index, start ->
            val end = starts.getOrNull(index + 1) ?: lines.size
            lines.subList(start, end).joinToString("\n").trim()
        }.filter { it.isNotBlank() }
    }

    /**
     * 一张截图里常同时出现「商品金额 / 优惠 -3.00 / 实付 35.00」三四个数字，
     * 以前取第一个 ¥ 就会把优惠额当成付款额（用户看到的「3 元」就是这么来的）。
     * 顺序是：带总额标签的 > 标签行之后第一个 ¥ > 去掉折扣行后最大的 ¥ > 整行纯金额。
     */
    private fun pickAmount(text: String): Long? {
        amountAfterLabel.find(text)?.groupValues?.getOrNull(1)?.let { parseAmount(it) }?.let { return it }

        val lines = text.lines()
        val labelIndex = lines.indexOfFirst { line -> totalLabelRegex.containsMatchIn(line) }
        if (labelIndex >= 0) {
            lines.drop(labelIndex).firstNotNullOfOrNull { line ->
                anyAmount.find(line)?.groupValues?.getOrNull(1)?.let { parseAmount(it) }
            }?.let { return it }
        }

        val paidCandidates = lines
            .filterNot { line -> discountWords.any { line.contains(it) } }
            .mapNotNull { line -> anyAmount.find(line)?.groupValues?.getOrNull(1)?.let { parseAmount(it) } }
        if (paidCandidates.isNotEmpty()) return paidCandidates.max()

        return bareAmount.findAll(text).mapNotNull { parseAmount(it.groupValues[1]) }.maxOrNull()
    }

    /** 没有「商户：」标签时，取金额之后第一条不是纯数字的短行，这是支付宝/微信截图的常见排版。 */
    private fun guessMerchant(text: String): String? {
        val lines = text.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
        val amountIndex = lines.indexOfFirst { it.contains("¥") || it.contains("￥") }
        val candidates = if (amountIndex >= 0) lines.drop(amountIndex + 1) else lines
        return candidates.firstOrNull { line ->
            line.length in 2..24 && !line.contains(Regex("""[0-9]+[.,][0-9]{2}""")) &&
                !line.contains(":") && !incomeMarkers.any { line.contains(it) }
        }
    }

    /** 认出来的日期；时刻只在原文真写了时才为 true，不拿 00:00 冒充。 */
    private data class DateHit(val millis: Long, val hasClock: Boolean)

    private fun toMillis(match: MatchResult, pattern: DatePattern, nowMillis: Long, zone: ZoneId): Long? {
        fun group(index: Int?): String? = index?.let { match.groupValues.getOrNull(it) }?.takeIf { it.isNotEmpty() }
        val month = group(pattern.monthGroup)?.toIntOrNull() ?: return null
        val day = group(pattern.dayGroup)?.toIntOrNull() ?: return null
        val hour = group(pattern.hourGroup)?.toIntOrNull() ?: 0
        val minute = group(pattern.minuteGroup)?.toIntOrNull() ?: 0
        // 只有「10-14 21:06」这种没写年份的写法才补当前年；跨年截图按 PRD 仍算最近这一笔。
        val year = group(pattern.yearGroup)?.toIntOrNull() ?: Instant.ofEpochMilli(nowMillis).atZone(zone).year
        return runCatching {
            LocalDateTime.of(year, month, day, hour, minute).atZone(zone).toInstant().toEpochMilli()
        }.getOrNull()
    }

}
