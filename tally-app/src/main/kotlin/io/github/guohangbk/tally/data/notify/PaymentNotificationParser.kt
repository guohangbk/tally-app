package io.github.guohangbk.tally.data.notify

import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.math.RoundingMode
import java.util.regex.Pattern

/** 一条通知的原始快照：只看这几个字段，不碰系统通知对象的其他内容。 */
data class PaymentSnapshot(
    val packageName: String,
    val title: String,
    val text: String,
    val extras: Map<String, String>,
    val atMillis: Long,
    /** 应用的中文名（「抖音省省」这种）：AI 认分类时它比包名有用，取不到就留空。 */
    val appName: String = "",
)

/** 从通知里解析出来的支付事实；金额是硬条件，解析不出就不返回。 */
data class ParsedPayment(
    val amountMinor: Long,
    val merchant: String?,
    val occurredAt: Long,
    val orderNo: String?,
    /** 交给识别管线的文本：原文拼好，规则与（开着时的）AI 都从它抽字段。 */
    val text: String,
    /** 只留正文（标题已单列），给 AI 拼「应用 / 标题 / 内容 / 时间」那一份用。 */
    val body: String = "",
)

/**
 * 通知抓取的解析器：**只认支付类通知，解析不出金额直接丢弃**。
 *
 * 纯函数、不依赖 Android，方便单测把「有金额 / 无金额 / 非支付」几条边界钉死。
 * 商户、订单号、时间都是尽力解析：解析不到就留空，交给识别管线与幂等台账按已有字段处理，
 * 不在这里猜、也不静默填默认值。
 */
object PaymentNotificationParser {

    private val amountPatterns = listOf(
        // ¥ 38.50 / ￥1,234.56
        Pattern.compile("[¥￥]\\s*([0-9][0-9,]*(?:\\.[0-9]{1,2})?)"),
        // 38.50元 / 1234 元
        Pattern.compile("([0-9][0-9,]*(?:\\.[0-9]{1,2})?)\\s*元"),
    )
    private val merchantPattern = Pattern.compile("(?:商户|收款方|付款方|向)[：:]\\s*([^\\s，,。;；]{2,20})")
    private val orderNoPattern = Pattern.compile("(?:订单号|订单编号|交易号)[：:]?\\s*([A-Za-z0-9_-]{6,40})")
    private val orderExtraKeys = listOf("out_trade_no", "orderNo", "order_id", "trade_no", "transaction_id")
    private val merchantExtraKeys = listOf("merchant", "merchant_name", "payer", "payee")

    /** 通知尾巴上的营销句（「点击领取 2 个积分」「立即查看详情」）与这笔账无关，先剪掉。 */
    private val marketingTail = Pattern.compile("[，,；;].*(?:点击|领取|立即|详情|前往|打开|下载|积分).*$")

    /** 明确「钱已经付了」的句式：有它就不用再看别的门槛。 */
    private val strongPatterns = listOf(
        "支付成功", "付款成功", "支付完成", "付款完成", "成功支付",
        "已支付", "已付款", "扣款成功", "交易成功", "收款成功",
    )

    /** 弱特征：只写了「消费 / 支出 / 交易」的通知，还得过下面的汇总拦截才算一笔。 */
    private val paymentHints = listOf("支付", "付款", "已付", "扣款", "消费", "支出", "收款", "转账", "交易")

    /**
     * 状态词：出现在**任何位置**都说明这条不是一笔已完成的付款，整条丢弃。
     * （「支付失败 19.9 元」「退款成功 19.9 元」这种金额再像也不能记。）
     */
    private val blockedHints = listOf(
        "待支付", "待付款", "未支付", "支付失败", "付款失败", "交易失败",
        "验证码", "退款", "退订", "还款", "应还", "待还", "额度",
    )

    /**
     * 汇总口径：没有强句式、又出现这些词的，是「本周累计消费」「本月账单」这类日结/月结通知，
     * 记进去就是一整段时间的重复金额。
     */
    private val summaryHints = listOf(
        "累计", "合计", "共计", "总计", "汇总", "总额",
        "本周", "本月", "上月", "本年", "年度", "月度", "账单", "预算",
    )

    /** 紧挨在金额前面、说明这不是实付的价签词（「立省 10.81 元」「原价 199 元」）。 */
    private val discountPrefixes = listOf(
        "立省", "省下", "已省", "省", "优惠", "立减", "减", "抵扣", "折扣", "券",
        "返现", "返", "红包", "满", "低至", "原价", "市场价", "参考价",
    )

    /** 紧挨在金额前面、说明这就是实付的词（「实付 4.99 元」「支付成功 2.9 元」）。 */
    private val paidPrefixes = listOf(
        "实付", "支付", "付款", "已付", "扣款", "交易", "收款", "到手", "应付",
    )

    /** 同一句里出现这些词，这一句的金额都不当实付（除非金额自己挂着「实付 / 到手」）。 */
    private val discountNoise = listOf(
        "立省", "省下", "已省", "优惠", "立减", "满减", "折扣", "返现", "红包",
        "低至", "原价", "市场价", "参考价", "券",
    )

    /** 断句：价格词只在同一句里生效，隔着句号或逗号的「立省」不影响另一句的实付。 */
    private val clauseBreaks = charArrayOf('，', ',', '；', ';', '。', '！', '？', '\n')

    fun parse(snapshot: PaymentSnapshot): ParsedPayment? {
        val title = snapshot.title.trim()
        val body = marketingTail.matcher(snapshot.text.trim()).replaceFirst("").trim()
        val raw = buildString {
            append(title)
            if (body.isNotEmpty()) append(' ').append(body)
        }.trim()
        if (raw.isBlank()) return null

        if (blockedHints.any { raw.contains(it) }) return null
        val strong = strongPatterns.any { raw.contains(it) }
        // 汇总类只在没有强句式时拦：「支付成功 580.87 元（本周累计）」仍是这一笔。
        if (!strong && summaryHints.any { raw.contains(it) }) return null
        // 一定要有支付特征：extras 里有东西不再单独放行，否则任何带 extras 的推广通知都能过。
        if (!strong && paymentHints.none { raw.contains(it) }) return null
        val amount = amountMinor(raw) ?: return null

        return ParsedPayment(
            amountMinor = amount,
            merchant = merchant(snapshot, raw),
            occurredAt = occurredAt(snapshot, raw),
            orderNo = orderNo(snapshot, raw),
            text = raw,
            body = body,
        )
    }

    /**
     * 金额不是「全篇第一个数字」：一条通知里常同时有原价、立省、实付，取错了就把优惠当成消费。
     *
     * 先剔掉挂着价签词的（立省 10.81、满 100 减 20），再优先取挂着「实付 / 支付成功 / 到手」的；
     * 都没有就退回第一个干净金额。全被剔掉时返回 null——这条不当支付。
     */
    private fun amountMinor(raw: String): Long? {
        val candidates = amountCandidates(raw).filterNot { isDiscountAmount(raw, it.at) }
        return candidates.firstOrNull { hasPaidPrefix(raw, it.at) }?.minor ?: candidates.firstOrNull()?.minor
    }

    private data class AmountCandidate(val minor: Long, val at: Int)

    private fun amountCandidates(raw: String): List<AmountCandidate> = buildList {
        amountPatterns.forEach { pattern ->
            val matcher = pattern.matcher(raw)
            while (matcher.find()) {
                val digits = matcher.group(1)?.replace(",", "") ?: continue
                // 金额按分落账，用 BigDecimal 换算：用 Double 乘 100 会把 19.99 算成 1998 分。
                val minor = digits.toBigDecimalOrNull()
                    ?.movePointRight(2)?.setScale(0, RoundingMode.HALF_UP)?.toLong() ?: continue
                if (minor > 0L) add(AmountCandidate(minor, matcher.start(1)))
            }
        }
    }.distinctBy { it.at }.sortedBy { it.at }

    /** 金额前面的那截字：紧挨着的价签词（「立省」）与实付词（「实付」）都只看这几个字。 */
    private fun prefixOf(raw: String, at: Int): String = raw.substring(maxOf(0, at - 6), at).trimEnd()

    private fun hasPaidPrefix(raw: String, at: Int): Boolean =
        paidPrefixes.any { prefixOf(raw, at).endsWith(it) }

    private fun isDiscountAmount(raw: String, at: Int): Boolean {
        val prefix = prefixOf(raw, at)
        if (discountPrefixes.any { prefix.endsWith(it) }) return true
        // 「到手 4.99」这种自己写清了是到手价的，同一句里出现「立省」也不算优惠金额。
        if (paidPrefixes.any { prefix.endsWith(it) }) return false
        return discountNoise.any { clauseOf(raw, at).contains(it) }
    }

    private fun clauseOf(raw: String, at: Int): String {
        var start = at
        while (start > 0 && raw[start - 1] !in clauseBreaks) start--
        var end = at
        while (end < raw.length && raw[end] !in clauseBreaks) end++
        return raw.substring(start, end)
    }

    private fun merchant(snapshot: PaymentSnapshot, raw: String): String? {
        snapshot.extras.entries.firstOrNull { (k, _) -> k in merchantExtraKeys }?.let { return it.value.trim() }
        merchantPattern.matcher(raw).let { matcher ->
            if (matcher.find()) return matcher.group(1).trim()
        }
        return null
    }

    private fun orderNo(snapshot: PaymentSnapshot, raw: String): String? {
        snapshot.extras.entries.firstOrNull { (k, _) -> k in orderExtraKeys }?.let { return it.value.trim() }
        orderNoPattern.matcher(raw).let { matcher ->
            if (matcher.find()) return matcher.group(1).trim()
        }
        return null
    }

    private val dateTimePatterns = listOf(
        Pattern.compile("(\\d{4}-\\d{1,2}-\\d{1,2}[ T]\\d{1,2}:\\d{2}(?::\\d{2})?)"),
        Pattern.compile("(\\d{1,2}-\\d{1,2}[ T]\\d{1,2}:\\d{2})"),
    )

    /** 通知正文里带的时刻优先；解析不到就用通知到达的时刻（通知本身的时间戳最接近支付时刻）。 */
    private fun occurredAt(snapshot: PaymentSnapshot, raw: String): Long {
        dateTimePatterns.forEach { pattern ->
            val matcher = pattern.matcher(raw)
            if (matcher.find()) {
                val parsed = runCatching {
                    val zone = ZoneId.systemDefault()
                    val text = matcher.group(1).replace('T', ' ')
                    val formatter = if (text.contains(':') && text.split(':').size == 3)
                        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                    else
                        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
                    val local = java.time.LocalDateTime.parse(text, formatter)
                    local.atZone(zone).toInstant().toEpochMilli()
                }.getOrNull()
                if (parsed != null) return parsed
            }
        }
        return snapshot.atMillis
    }
}
