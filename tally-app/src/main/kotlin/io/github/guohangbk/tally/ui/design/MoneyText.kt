package io.github.guohangbk.tally.ui.design

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.em
import io.github.guohangbk.tally.domain.formatAmount

enum class MoneySign {
    NONE,
    EXPENSE,
    INCOME,
}

/** 千分位开关由根节点按设置下发；非 MoneyText 的金额文案直接读它，避免各页各写一份。 */
val LocalMoneyGrouping = staticCompositionLocalOf { true }

/**
 * 金额显示：整数最大字号、小数降一级透明度；支出用陶土、收入用墨绿，
 * 同时用 － / ＋ 前缀冗余编码，不依赖颜色区分。金额始终以分存储，这里只做显示格式化。
 */
@Composable
fun MoneyText(
    amountMinor: Long,
    sign: MoneySign,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    grouping: Boolean = LocalMoneyGrouping.current,
    showCurrency: Boolean = true,
    currencySymbol: String = "¥",
    textAlign: TextAlign = TextAlign.Unspecified,
) {
    BasicText(
        text = rememberMoneyAnnotated(amountMinor, sign, style, color, grouping, showCurrency, currencySymbol),
        style = style.copy(color = color, textAlign = textAlign),
        modifier = modifier,
    )
}

@Composable
private fun rememberMoneyAnnotated(
    amountMinor: Long,
    sign: MoneySign,
    style: TextStyle,
    color: Color,
    grouping: Boolean,
    showCurrency: Boolean,
    currencySymbol: String,
): AnnotatedString {
    val absolute = kotlin.math.abs(amountMinor)
    val grouped = formatAmount(absolute, grouping)
    val (integer, fraction) = grouped.split(".")
    val prefix = when (sign) {
        MoneySign.NONE -> ""
        MoneySign.EXPENSE -> "－"
        MoneySign.INCOME -> "＋"
    }
    val muted = color.copy(alpha = 0.62f)
    return buildAnnotatedString {
        if (showCurrency && prefix.isEmpty()) {
            withStyle(SpanStyle(fontSize = style.fontSize * 0.55f, color = muted, fontWeight = FontWeight.Normal)) {
                append(currencySymbol)
            }
        }
        if (prefix.isNotEmpty()) {
            withStyle(SpanStyle(color = muted, fontSize = style.fontSize * 0.8f)) { append(prefix) }
        }
        if (showCurrency && prefix.isNotEmpty()) {
            withStyle(SpanStyle(fontSize = style.fontSize * 0.5f, color = muted)) { append(" ") }
        }
        withStyle(SpanStyle(color = color)) { append(integer) }
        withStyle(SpanStyle(fontSize = style.fontSize * 0.62f, color = muted, letterSpacing = 0.02.em)) {
            append("." + fraction)
        }
    }
}
