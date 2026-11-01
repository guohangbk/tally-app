package io.github.guohangbk.tally.domain

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * 金额一律以「分」为单位的 Long 存储，禁止 Float / Double 参与业务计算。
 */
@JvmInline
value class Money(val minor: Long) {
    init {
        require(minor >= 0) { "金额不能为负：$minor" }
    }

    val integerPart: Long get() = minor / 100
    val fractionPart: Long get() = minor % 100

    companion object {
        val ZERO = Money(0)

        /**
         * 显示上限 99999999.99，见 
         */
        const val MAX_MINOR: Long = 9_999_999_999L

        fun fromDecimal(text: String): Money? = parseAmount(text)?.let { Money(it) }
    }
}

/**
 * 十进制定点解析：先清洗千分位（全角 `，` 与半角 `,`）与货币符号，再四舍五入到分。
 * 全角逗号是小号中文逗号，绝不能当成小数点。
 *
 * 带符号的输入一律拒收：金额必须大于 0，退款与报销用 `is_refund` / 报销状态标记，
 * 不用负数金额。导入器若遇到负数行，应转成「支出 + 退款标记」而不是在这里放宽。
 */
fun parseAmount(raw: String): Long? {
    val cleaned = raw
        .trim()
        .replace("，", "")
        .replace("、", "")
        .replace(",", "")
        .replace("￥", "")
        .replace("¥", "")
        .replace("CNY", "")
        .replace(" ", "")
        .replace("−", "-")
    if (cleaned.isEmpty()) return null
    val negative = cleaned.startsWith("-")
    val digits = cleaned.removePrefix("-").removePrefix("+")
    if (!plainDecimal.matches(digits)) return null
    if (negative) return null
    val rounded = digits.toBigDecimal().setScale(2, RoundingMode.HALF_UP)
    if (rounded > MAX_MINOR_DECIMAL) return null
    return rounded.movePointRight(2).longValueExact()
}

/** 只接受十进制定点写法：`1e5`、`0x1f`、`Infinity` 之类一律拒收，避免科学计数被当成合法金额。 */
private val plainDecimal = Regex("""\d+(\.\d+)?|\.\d+""")

/**
 * 带符号输入的解析：返回 `(分, 是否为负)`，符号本身不落成负金额。
 *
 * 「怎么认符号」全仓只有这一处；符号的语义由调用方决定——导入器把负数行转成
 * 「支出 + 退款标记」，而 [parseAmount] 继续拒收带符号输入，因为账单方向由 `BillType` 表达。
 */
fun parseSignedAmount(raw: String): Pair<Long?, Boolean> {
    val text = raw.trim().replace("−", "-")
    val negative = text.startsWith("-")
    return parseAmount(text.removePrefix("-").removePrefix("+")) to negative
}

/**
 * 记账键盘的加减连算（钱迹习惯）：`15+20-3.5` 求和成分。
 * 半截式子——结尾是运算符、运算符前后没有数字——返回 null，界面据此保持「还不能保存」，
 * 而不是把输入到一半的式子当成一个金额。
 */
fun parseAmountExpression(raw: String): Long? {
    val text = raw.trim().replace(" ", "")
    if (text.isEmpty()) return null
    var total = 0L
    var sign = 1L
    val term = StringBuilder()
    fun closeTerm(): Boolean {
        val value = parseAmount(term.toString()) ?: return false
        total += sign * value
        term.setLength(0)
        return true
    }
    for (ch in text) {
        if (ch == '+' || ch == '−' || ch == '-') {
            if (!closeTerm()) return null
            sign = if (ch == '+') 1L else -1L
        } else {
            term.append(ch)
        }
    }
    if (!closeTerm()) return null
    return total.takeIf { it in -Money.MAX_MINOR..Money.MAX_MINOR }
}

/** 上限先在 BigDecimal 里比对，超出范围的输入返回 null 而不是让 `longValueExact` 抛异常。 */
private val MAX_MINOR_DECIMAL: BigDecimal = BigDecimal(Money.MAX_MINOR).movePointLeft(2)

fun Long.toMoney(): Money = Money(this)

/** 千分位整数部分 + 两位小数，例如 2,780.45。 */
fun formatAmount(minor: Long, grouping: Boolean = true): String {
    val negative = minor < 0
    // 逐段取绝对值：`-minor` 在 Long.MIN_VALUE 上会溢出，而先除后取负始终在范围内。
    val integer = (if (negative) -(minor / 100) else minor / 100).toString()
    val fraction = (if (negative) -(minor % 100) else minor % 100).toString().padStart(2, '0')
    val grouped = if (grouping) groupThousands(integer) else integer
    return (if (negative) "-" else "") + grouped + "." + fraction
}

private fun groupThousands(digits: String): String =
    digits.reversed().chunked(3).joinToString(",").reversed()

/** 年利率以 BP（万分之一）存：345 → 3.45%，350 → 3.5%。 */
fun formatRateBp(bp: Int): String {
    val whole = bp / 100
    val fraction = kotlin.math.abs(bp % 100).toString().padStart(2, '0').trimEnd('0')
    return if (fraction.isEmpty()) "$whole%" else "$whole.$fraction%"
}

/** 把「3.45」「3.45%」解析成 BP；有效年利率需在 0%–30%，格式不对返回 null。 */
fun parseRateBp(text: String): Int? {
    val cleaned = text.trim().removeSuffix("%").trim()
    if (cleaned.isEmpty()) return null
    val value = cleaned.toBigDecimalOrNull() ?: return null
    if (value.signum() < 0 || value > BigDecimal(30)) return null
    return value.movePointRight(2).setScale(0, RoundingMode.HALF_UP).toInt()
}

/** 用于对账与统计的定点求和，避免浮点累加误差。 */
fun List<Long>.sumMinor(): Long {
    var total = BigDecimal.ZERO
    for (value in this) total = total.add(BigDecimal(value))
    return total.longValueExact()
}
