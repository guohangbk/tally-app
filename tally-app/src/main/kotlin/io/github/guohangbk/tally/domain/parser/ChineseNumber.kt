package io.github.guohangbk.tally.domain.parser

/**
 * 中文数字与口语金额的可读片段识别。
 *
 * 只做「识别 + 求值」，不做业务判断：识别到什么数值由调用方决定，
 * 金额一律换算到「分」，与 Money 的定点口径一致。
 */
object ChineseNumber {

    private val digitValues: Map<Char, Int> = mapOf(
        '零' to 0, '〇' to 0, '一' to 1, '壹' to 1, '两' to 2, '二' to 2, '贰' to 2,
        '三' to 3, '叁' to 3, '四' to 4, '肆' to 4, '五' to 5, '伍' to 5,
        '六' to 6, '陆' to 6, '七' to 7, '柒' to 7, '八' to 8, '捌' to 8,
        '九' to 9, '玖' to 9,
    )

    /** 十百千直接乘，万亿做分段。 */
    private val placeValues: Map<Char, Long> = mapOf(
        '十' to 10L, '拾' to 10L, '百' to 100L, '佰' to 100L, '千' to 1000L, '仟' to 1000L,
    )

    private val sectionValues: Map<Char, Long> = mapOf('万' to 10_000L, '亿' to 100_000_000L)

    /** 口语金额里这些字都属于数字串的一部分。 */
    private val amountConnectors = setOf('块', '元', '圆', '角', '毛', '分', '点', '半')

    fun isNumberChar(ch: Char): Boolean =
        ch in '0'..'9' || ch in '０'..'９' || ch in digitValues || ch in placeValues || ch in sectionValues || ch == '点'

    /**
     * 单个裸数字词（一、两、伍…）：它更可能是「一共120元」「第三次」里的那个字，
     * 而不是一笔 1 元的账。位权字（十百千万）不算，「十」单独说出来确实是十元。
     */
    fun isBareDigitWord(ch: Char): Boolean = ch in digitValues

    /** 纯整数写法（含中文数字与全角）；解析不出返回 null。 */
    fun parseInt(segment: String): Long? {
        val text = normalize(segment)
        if (text.isEmpty()) return null
        if (text.all { it in '0'..'9' }) return text.toLongOrNull()
        return parseInteger(text)
    }

    /**
     * 解析一个口语金额片段为「分」。覆盖：
     * 三十五 → 3500、一百二 → 12000、三十五块五 → 3550、两块五 → 250、五块半 → 550、
     * 35块5 → 3550、12.5 → 1250、21块8毛 → 2180、29块8毛5 → 2985、二十九块八毛五 → 2985、
     * 3.5块 → 350。
     */
    fun parseMinor(segment: String): Long? {
        val text = normalize(segment)
            .removePrefix("¥").removePrefix("￥").removePrefix("CNY")
            .replace("钱", "")
        if (text.isEmpty()) return null
        val decimal = splitDecimal(text)
        val minor = if (decimal != null) {
            val integer = parseIntegerOrArabic(decimal.first) ?: return null
            integer * 100 + decimal.second
        } else {
            parseIntegerOrArabic(text)?.times(100) ?: return null
        }
        return minor.takeIf { it > 0L && it <= MAX_NOTE_MINOR }
    }

    /** 速记一条最多九十九万九千九百九十九元九角九分；超出即认为这段文字不是金额。 */
    private const val MAX_NOTE_MINOR = 99_999_999L

    private fun parseIntegerOrArabic(text: String): Long? {
        val clean = normalize(text)
        if (clean.isEmpty()) return 0L
        if (clean.all { it in '0'..'9' }) return clean.toLongOrNull()
        return parseInteger(clean)
    }

    /** 小数部分：点、阿拉伯小数点，或 块/元/圆 后跟 角/毛/分/半。 */
    private fun splitDecimal(text: String): Pair<String, Long>? {
        val dot = text.indexOf('点').takeIf { it >= 0 } ?: text.indexOf('.').takeIf { it >= 0 }
        if (dot != null) {
            // 「3.5块」的小数段尾巴上挂着单位，不剥掉就什么都算不出来。
            val minor = fractionMinorOf(text.substring(dot + 1).trimEnd('块', '元', '圆')) ?: return null
            return text.substring(0, dot) to minor
        }
        val unit = text.indexOfFirst { it == '块' || it == '元' || it == '圆' }
        if (unit >= 0) {
            val tail = text.substring(unit + 1).trim()
            if (tail.isEmpty()) return text.substring(0, unit) to 0L
            val minor = fractionMinorOf(tail) ?: return null
            return text.substring(0, unit) to minor
        }
        val jiao = text.indexOfFirst { it == '角' || it == '毛' }
        if (jiao >= 0) {
            // 「五毛」「三毛五」这类只有角没有元的写法。
            val head = text.substring(0, jiao).trim()
            val tail = text.substring(jiao + 1).trim()
            val jiaoValue = singleDigitValue(head) ?: return null
            val fenValue = if (tail.isEmpty()) 0L else (singleDigitValue(tail.removeSuffix("分"))?.toLong() ?: return null)
            return "" to (jiaoValue * 10 + fenValue)
        }
        val fen = text.indexOf('分')
        if (fen >= 0) {
            // 「8分」「八分」：既没有元也没有角毛，整段就按分计。
            return "" to (parseIntegerOrArabic(text.substring(0, fen).trim()) ?: return null)
        }
        return null
    }

    /** 「五」=5 角、「05」=5 分、「三毛五」=3 角 5 分、「八分」=8 分、「半」=5 角。 */
    private fun fractionMinorOf(rawFraction: String): Long? {
        val fraction = normalize(rawFraction).replace('毛', '角').trim()
        if (fraction.isEmpty()) return 0L
        if (fraction.all { it in '0'..'9' }) {
            return when (fraction.length) {
                1 -> fraction.toInt() * 10L
                2 -> fraction.toLong()
                else -> null
            }
        }
        val jiao = fraction.indexOf('角')
        if (jiao >= 0) {
            val jiaoValue = singleDigitValue(fraction.substring(0, jiao)) ?: return null
            val fenPart = fraction.substring(jiao + 1).trim()
            if (fenPart.isEmpty()) return jiaoValue * 10L
            val fenValue = singleDigitValue(fenPart.removeSuffix("分")) ?: return null
            return jiaoValue * 10L + fenValue
        }
        if (fraction.endsWith("分")) return singleDigitValue(fraction.removeSuffix("分"))?.toLong()
        return singleDigitValue(fraction)?.let { it * 10L }
    }

    private fun singleDigitValue(text: String): Int? {
        val trimmed = text.trim()
        if (trimmed == "半") return 5
        if (trimmed.length != 1) return null
        return digitValues[trimmed.single()] ?: trimmed.single().digitToIntOrNull()
    }

    /** 整数部分。处理省略位权（一百二 = 120）与万/亿分段。 */
    private fun parseInteger(text: String): Long? {
        if (text.isEmpty()) return 0L
        var total = 0L
        var section = 0L
        var current: Long? = null
        var lastPlace = 1L
        var sawNumberChar = false
        for (ch in text) {
            when {
                ch in digitValues -> {
                    current = digitValues[ch]!!.toLong()
                    sawNumberChar = true
                }

                ch in '0'..'9' -> {
                    current = ch.digitToInt().toLong()
                    sawNumberChar = true
                }

                ch in placeValues -> {
                    val place = placeValues[ch]!!
                    section += (current ?: 1L) * place
                    current = null
                    lastPlace = place
                    sawNumberChar = true
                }

                ch in sectionValues -> {
                    val unit = sectionValues[ch]!!
                    section += current ?: 0L
                    current = null
                    total = (total + section) * unit
                    section = 0L
                    lastPlace = unit
                    sawNumberChar = true
                }

                ch in amountConnectors -> Unit

                else -> return null
            }
        }
        if (!sawNumberChar) return null
        val tail = current ?: return total + section
        // 省略位权：一百二 = 一百二十，一千二 = 一千两百。
        return if (tail < 10 && lastPlace >= 100) total + section + tail * (lastPlace / 10) else total + section + tail
    }

    private fun normalize(text: String): String = buildString(text.length) {
        for (ch in text) {
            append(if (ch in '０'..'９') Char(ch.code - '０'.code + '0'.code) else ch)
        }
    }.trim()
}
