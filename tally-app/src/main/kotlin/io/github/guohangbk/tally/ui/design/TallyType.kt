package io.github.guohangbk.tally.ui.design

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

private val Serif = FontFamily.Serif
private val Sans = FontFamily.Default

/**
 * 字族规则：**衬线只留给钱**——金额与大数字用衬线等宽（tabular），文字一律无衬线。
 *
 * 中文衬线在标题尺寸上没有「标题感」，只有「跟周围不一样」；真正需要衬线的是
 * 让等宽数字对得齐。所以 `pageTitle` / `screenTitle` / `eyebrow` 都是无衬线，
 * 别把它们改回 Serif。
 *
 * 每个 token 的 `lineHeight` 都是配死写死的：行高跟着 style 走，裸写 `fontSize =`
 * 会继承默认行高（22sp），10.5sp 的字在 22sp 的行盒里顶着上边，跟旁边对不齐。
 * 要改字号就必须同时改这里的行高。
 */
object TallyType {
    val moneyDisplay = TextStyle(
        fontFamily = Serif,
        fontWeight = FontWeight.Normal,
        fontSize = 42.sp,
        letterSpacing = (-0.02).em,
        lineHeight = 46.sp,
    )

    val moneyField = TextStyle(
        fontFamily = Serif,
        fontWeight = FontWeight.Medium,
        fontSize = 31.sp,
        letterSpacing = (-0.02).em,
        lineHeight = 32.sp,
    )

    val moneyBody = TextStyle(
        fontFamily = Serif,
        fontWeight = FontWeight.Medium,
        fontSize = 18.5.sp,
        letterSpacing = (-0.01).em,
        lineHeight = 20.sp,
    )

    val moneyList = TextStyle(
        fontFamily = Serif,
        fontWeight = FontWeight.Medium,
        fontSize = 15.5.sp,
        lineHeight = 18.sp,
    )

    val pageTitle = TextStyle(
        fontFamily = Sans,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        letterSpacing = 0.01.em,
        lineHeight = 28.sp,
    )

    val screenTitle = TextStyle(
        fontFamily = Sans,
        fontWeight = FontWeight.SemiBold,
        fontSize = 18.5.sp,
        letterSpacing = 0.01.em,
        lineHeight = 22.sp,
    )

    val sectionTitle = TextStyle(
        fontFamily = Sans,
        fontWeight = FontWeight.Medium,
        fontSize = 16.5.sp,
        lineHeight = 22.sp,
    )

    val body = TextStyle(
        fontFamily = Sans,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 22.sp,
    )

    val secondary = TextStyle(
        fontFamily = Sans,
        fontWeight = FontWeight.Normal,
        fontSize = 13.5.sp,
        lineHeight = 18.sp,
    )

    val helper = TextStyle(
        fontFamily = Sans,
        fontWeight = FontWeight.Normal,
        fontSize = 12.5.sp,
        lineHeight = 16.sp,
    )

    val eyebrow = TextStyle(
        fontFamily = Sans,
        fontWeight = FontWeight.Bold,
        fontSize = 11.5.sp,
        letterSpacing = 0.18.em,
        lineHeight = 14.sp,
    )

    val listTitle = TextStyle(
        fontFamily = Sans,
        fontWeight = FontWeight.Medium,
        fontSize = 15.sp,
        lineHeight = 18.sp,
    )
}

/** 把设计板字号映射进 Material 3 角色，保证内置组件同调。 */
val TallyMaterialTypography = Typography(
    displayLarge = TallyType.moneyDisplay,
    displayMedium = TallyType.moneyField,
    displaySmall = TallyType.pageTitle,
    headlineMedium = TallyType.pageTitle,
    headlineSmall = TallyType.screenTitle,
    titleLarge = TallyType.sectionTitle,
    titleMedium = TallyType.listTitle,
    titleSmall = TallyType.secondary,
    bodyLarge = TallyType.body,
    bodyMedium = TallyType.secondary,
    bodySmall = TallyType.helper,
    labelLarge = TallyType.listTitle,
    labelMedium = TallyType.helper,
    labelSmall = TallyType.eyebrow,
)
