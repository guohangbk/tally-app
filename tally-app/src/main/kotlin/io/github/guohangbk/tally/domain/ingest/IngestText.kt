package io.github.guohangbk.tally.domain.ingest

import java.security.MessageDigest
import io.github.guohangbk.tally.domain.parser.ChineseNumber

/**
 * 识别原文里能被本机复核的两件事：订单号、金额。
 *
 * 判级不该只信模型——它可能照着上下文补一个数、也可能把订单号读错一位。这里做的复核不引入
 * 任何猜测：找得到就是找得到，找不到就走确认卡。
 */

/** 订单号 / 交易单号 / 商户订单号 / 订单编号：支付截图里的稳定标识。 */
private val ORDER_NO = Regex("""(?:商户订单号|订单号|交易单号|订单编号)\s*[:：]?\s*([A-Za-z0-9_\-]{6,32})""")

/** 原文里的订单号。没有就返回 null——没有就不写，绝不编一个。 */
fun findOrderNo(text: String): String? =
    ORDER_NO.find(text)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }

/**
 * 金额是不是真的出现在原文里。
 *
 * 判级要它，是因为「自动写入」这个动作前面不能只站着模型自己的一句话。元为单位的常见写法都认：
 * `35.5`、`35.50`、`¥35.5`、`1,280.00`；命中处两侧不能再挨着数字，避免 `35` 命中 `1350`。
 */
fun amountAppearsInText(text: String, amountMinor: Long): Boolean {
    if (amountMinor <= 0 || text.isBlank()) return false
    val yuan = amountMinor / 100
    val cents = (amountMinor % 100).toInt()
    val candidates = buildList {
        add("$yuan.${cents.toString().padStart(2, '0')}")
        if (cents == 0) {
            // 整数金额在原文里通常写作 35 / 35.0 / 35.00 三选一。
            add("$yuan")
            add("$yuan.0")
        } else if (cents % 10 == 0) {
            // 35.50 也常被写成 35.5。
            add("$yuan.${cents / 10}")
        }
    }
    // 千分位：原文写 1,280.00、金额是 128000 分，直接搜「1280.00」搜不到，去逗号再搜一遍。
    val haystacks = listOf(text, text.replace(",", ""))
    if (candidates.any { candidate -> haystacks.any { haystack -> hits(haystack, candidate) } }) return true
    return AMOUNT_FRAGMENT.findAll(text).any { match ->
        runCatching { ChineseNumber.parseMinor(match.value) }.getOrNull() == amountMinor
    }
}

/** 口语金额片段：数字或中文数字，后头可以跟元角分、小数或「点半」。 */
private val AMOUNT_FRAGMENT = Regex(
    """(?:[¥￥]?\d+(?:[.,]\d{1,2})?|[零〇一壹两二贰三叁四肆五伍六陆七柒八捌九玖十拾百佰千仟万亿点半]{1,10})""" +
        """(?:块钱|块|元|圆|角|毛|分|半)?(?:[零〇一壹两二贰三叁四肆五伍六陆七柒八捌九玖半\d]{1,4})?""" +
        """(?:角|毛|分)?""",
)

/** 数字边界命中：`35` 不该命中 `1350` 或 `35.5` 里的整数部分。 */
private fun hits(text: String, needle: String): Boolean {
    var from = 0
    while (true) {
        val at = text.indexOf(needle, from)
        if (at < 0) return false
        val before = text.getOrNull(at - 1)
        val after = text.getOrNull(at + needle.length)
        val leftOk = before == null || !before.isDigit()
        val rightOk = after == null || (!after.isDigit() && !(after == '.' && text.getOrNull(at + needle.length + 1)?.isDigit() == true))
        if (leftOk && rightOk) return true
        from = at + 1
    }
}

/**
 * 图片指纹：同一张截图重复分享时，用它认出「这张图已经记过了」。
 *
 * 用源字节算，不依赖压缩后的结果——压出来那份是给模型看的，指纹只需要对同一份输入稳定。
 * 与订单号是两条互补的路：订单号管「同一笔换张图再发」，指纹管「同一张图再发一次」。
 */
fun imageFingerprint(bytes: ByteArray): String =
    "img:" + MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { byte -> "%02x".format(byte) }
        .take(32)
