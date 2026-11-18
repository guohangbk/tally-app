package io.github.guohangbk.tally.domain.ingest

/**
 * 脱敏作用于**机器产出的文本**：图片的 OCR 结果与语音转写的那句话。
 * 人亲手打的字不在这里改写——那是他自己要发给自己配的服务商的。
 * 日志只允许记遮了几处，不记原文。
 */
data class Redaction(val text: String, val maskedCount: Int)

private data class MaskRule(
    val regex: Regex,
    val keepLast: Int = 0,
    val label: String = "已隐藏",
)

private val phone = Regex("""(?<!\d)1[3-9]\d[\s-]?\d{4}[\s-]?\d{4}(?!\d)""")
private val identity = Regex("""(?<!\d)\d{17}[\dXx](?!\d)""")
private val bankCard = Regex("""(?<!\d)\d{12,19}(?!\d)""")
private val labeledOrder = Regex(
    """((?:商户订单号|订单号|交易单号|订单编号|快递单号|运单号)\s*[:：]?\s*)([A-Za-z0-9_\-]{6,32})""",
)
private val labeledAddress = Regex("""((?:详细地址|收货地址|地址)\s*[:：]\s*)([^\n]{4,80})""")
private val bareTracking = Regex("""(?<![A-Za-z0-9])(?:SF|YT|ZTO|STO|YD|JD|EMS)?\d{10,18}(?![A-Za-z0-9])""")

/** 保留订单号后四位，其余号码一律不保留可还原的数字串。 */
object IngestRedactor {
    fun redact(text: String): Redaction {
        if (text.isBlank()) return Redaction(text, 0)
        var count = 0
        var output = labeledAddress.replace(text) { match ->
            count++
            match.groupValues[1] + "［地址已隐藏］"
        }
        output = labeledOrder.replace(output) { match ->
            count++
            val tail = match.groupValues[2].takeLast(4)
            match.groupValues[1] + "****$tail"
        }
        output = listOf(
            MaskRule(phone),
            MaskRule(identity),
            MaskRule(bankCard),
            MaskRule(bareTracking),
        ).fold(output) { current, rule ->
            rule.regex.replace(current) {
                count++
                "［${rule.label}］"
            }
        }
        return Redaction(output, count)
    }
}
