package io.github.guohangbk.tally.domain.parser

import io.github.guohangbk.tally.domain.model.BillType
import io.github.guohangbk.tally.domain.model.DraftField
import io.github.guohangbk.tally.domain.model.TimePrecision
import io.github.guohangbk.tally.domain.parseAmount
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 支付截图的字段抽取。端侧 OCR 出的文本先由这里拆成
 * 金额 / 收支 / 商户 / 日期时间 / 支付方式 / 订单号，小莉视觉只是在这一步之上的增强。
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

    private val amountRegexes = listOf(
        Regex("""[¥￥]\s*([0-9][0-9,，]*(?:\.[0-9]{1,2})?)"""),
        Regex("""(?:实付款|实付|支付金额|金额|总计|合计)\s*[:：]?\s*[¥￥]?\s*([0-9][0-9,，]*(?:\.[0-9]{1,2})?)"""),
        // ML Kit 常把 ¥ 单独丢成一行「126.00」，所以再兜一档「整行只有两位小数金额」的写法。
        Regex("""(?m)^\s*([0-9]{1,3}(?:,[0-9]{3})*\.[0-9]{2})\s*$"""),
    )

    /** 总额标签：截图里同时出现「优惠 / 立减 / 商品价」时，只有这几个才是这一笔的金额。 */
    private val totalLabels = listOf("实付款", "实付", "实际支付", "支付金额", "付款金额", "订单金额", "应付", "合计", "总计")

    /** 这些行里的数字是折扣或商品明细，不是这一笔付出去的钱。 */
    private val discountWords = listOf("优惠", "立减", "满减", "红包", "折扣", "抵扣", "免息", "券")

    private val anyAmount = Regex("""[¥￥]\s*([0-9][0-9,，]*(?:\.[0-9]{1,2})?)""")
    private val bareAmount = Regex("""(?m)^\s*([0-9]{1,3}(?:,[0-9]{3})*\.[0-9]{2})\s*$""")
    private val amountAfterLabel = Regex(
        """(?:实付款|实付|实际支付|支付金额|付款金额|订单金额|应付|合计|总计)\s*[:：]?\s*[¥￥]?\s*([0-9][0-9,，]*(?:\.[0-9]{1,2})?)""",
    )

    private val dateTimeRegexes = listOf(
        Regex("""(\d{4})-(\d{1,2})-(\d{1,2})[ T](\d{1,2}):(\d{2})(?::(\d{2}))?"""),
        Regex("""(\d{4})年(\d{1,2})月(\d{1,2})日\s*(\d{1,2}):(\d{2})"""),
        Regex("""(\d{1,2})[-月](\d{1,2})[日号]?\s+(\d{1,2}):(\d{2})"""),
        Regex("""(\d{4})-(\d{1,2})-(\d{1,2})"""),
    )

    private val labeled = Regex("""(?:商户|收款方|商品|商品说明|订单标题|付款方)\s*[:：]\s*([^\n]{1,40})""")
    private val paymentRegex = Regex("""(?:支付方式|付款方式使用|使用|付款方式)\s*[:：]?\s*([^\n]{1,20})""")
    private val orderNoRegex = Regex("""(?:商户订单号|订单号|交易单号|订单编号)\s*[:：]?\s*([A-Za-z0-9_\-]{6,32})""")

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
        val text = rawText.replace("\r", "\n").trim()
        val amount = pickAmount(text)
        val whenMatch = dateTimeRegexes.firstNotNullOfOrNull { regex -> regex.find(text)?.takeIf { it.groupValues.size > 3 } }
        val occurredAt = whenMatch?.let { toMillis(it, nowMillis, zone) }
        val merchant = labeled.find(text)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }
            ?: guessMerchant(text)
        val paymentMethod = paymentRegex.find(text)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }
            ?: paymentMethodWords.firstOrNull { text.contains(it) }
        val orderNo = orderNoRegex.find(text)?.groupValues?.getOrNull(1)?.trim()
        // 收支方向只看抬头几行：截图里任何一段文字（包括别人写在图里的「归类到工资」）
        // 都不该把一笔支出翻成收入。
        val header = text.lineSequence().take(HEADER_LINES).joinToString(" ")
        val type = if (incomeMarkers.any { header.contains(it) }) BillType.INCOME else BillType.EXPENSE
        val hasTime = whenMatch != null && whenMatch.groupValues.size >= 6 && whenMatch.groupValues[4].isNotEmpty()

        val missing = buildSet {
            if (amount == null) add(DraftField.AMOUNT)
            if (occurredAt == null) add(DraftField.DATE)
            add(DraftField.CATEGORY) // 截图里没有分类信息，必须靠关键词表或小莉建议
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
     * 一张截图里常同时出现「商品金额 / 优惠 -3.00 / 实付 35.00」三四个数字，
     * 以前取第一个 ¥ 就会把优惠额当成付款额（用户看到的「3 元」就是这么来的）。
     * 顺序是：带总额标签的 > 标签行之后第一个 ¥ > 去掉折扣行后最大的 ¥ > 整行纯金额。
     */
    private fun pickAmount(text: String): Long? {
        amountAfterLabel.find(text)?.groupValues?.getOrNull(1)?.let { parseAmount(it) }?.let { return it }

        val lines = text.lines()
        val labelIndex = lines.indexOfFirst { line -> totalLabels.any { line.contains(it) } }
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

    private fun toMillis(match: MatchResult, nowMillis: Long, zone: ZoneId): Long? {
        val groups = match.groupValues
        val year = groups.getOrNull(1)?.toIntOrNull()
        val month = groups.getOrNull(2)?.toIntOrNull() ?: return null
        val day = groups.getOrNull(3)?.toIntOrNull() ?: return null
        val hour = groups.getOrNull(4)?.toIntOrNull() ?: 0
        val minute = groups.getOrNull(5)?.toIntOrNull() ?: 0
        val resolvedYear = year ?: runCatching {
            // 只有「10-14 21:06」时补当前年；跨年截图按 PRD 仍属于最近这笔。
            Instant.ofEpochMilli(nowMillis).atZone(zone).year
        }.getOrDefault(1970)
        return runCatching {
            LocalDateTime.of(resolvedYear, month, day, hour, minute)
                .atZone(zone).toInstant().toEpochMilli()
        }.getOrNull()
    }

    private val fullDateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
}
