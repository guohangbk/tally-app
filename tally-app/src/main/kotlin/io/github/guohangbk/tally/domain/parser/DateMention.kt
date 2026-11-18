package io.github.guohangbk.tally.domain.parser

import io.github.guohangbk.tally.domain.ingest.findOrderNo

private val WORD_DATES = Regex(
    "(今天|今日|昨天|昨日|前天|前日|明天|明日|后天|大前天|大后天|本周|这周|上周|下周|" +
        "本月|这个月|上月|上个月|下月|下个月|去年|今年|明年|周[一二三四五六日天末]|" +
        "星期[一二三四五六日天]|礼拜[一二三四五六日天]|上旬|中旬|下旬|月初|月底|年末|年初|" +
        "[上这下前](?:周|星期|礼拜|个月|月|年)[一二三四五六日天]?)",
)
private val NUMBER_DATES = Regex(
    """(?<![\d.])(?:\d{4}[年/-]\d{1,2}[月/-]\d{1,2}日?|\d{1,2}月\d{1,2}[日号]|\d{1,2}[/-]\d{1,2})(?![\d.])""",
)
private val AMOUNT_FRAGMENT = Regex(
    """(?:\d+(?:\.\d+)?|[零〇一壹两二贰三叁四肆五伍六陆七柒八捌九玖十拾百佰千仟万亿]{1,8})""" +
        """(?:块钱|块|元|圆|角|毛|分|半|点\d+|.\d+)?""",
)

/**
 * 原文有没有提到日期。它只用来挡住「说了日期但没解析出来」的自动入账，
 * 不负责解析日期，也不把金额、时刻或订单号里的数字当成日期。
 */
object DateMention {
    fun mentions(text: String): Boolean {
        val clean = withoutOrders(text)
        if (WORD_DATES.containsMatchIn(clean)) return true
        return NUMBER_DATES.findAll(clean).any { match -> !insideAmount(clean, match.range) }
    }

    private fun withoutOrders(text: String): String {
        var clean = text
        while (true) {
            val order = findOrderNo(clean) ?: return clean
            clean = clean.replace(order, " ")
        }
    }

    private fun insideAmount(text: String, range: IntRange): Boolean =
        AMOUNT_FRAGMENT.findAll(text).any { match ->
            match.range.first <= range.first && match.range.last >= range.last
        }
}
