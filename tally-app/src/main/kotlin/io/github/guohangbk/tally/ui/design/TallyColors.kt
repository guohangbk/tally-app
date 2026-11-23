package io.github.guohangbk.tally.ui.design

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.unit.dp
import io.github.guohangbk.tally.domain.model.TallySemantic
import io.github.guohangbk.tally.domain.model.TallySkin
import io.github.guohangbk.tally.domain.model.TallySkins
import androidx.compose.runtime.remember

/**
 * 「Ink & Jasmine」：日常记账是纸墨（骨白纸面 / 松烟正文 / 墨绿主操作 + 衬线数字），
 * 金晕只出现在小莉相关的建议、光晕与状态点上。不开小莉，界面里没有一丝金色。
 *
 * 纸面、正文、主操作这 9 个槽现在按皮肤取值（[TallySkins]），语义色不跟着换：
 * 支出红、收入绿、小莉金、中性强调钉死在 [TallySemantic]，换肤只换纸和按钮。
 */
@Immutable
data class TallyColors(
    val isDark: Boolean,
    val paper: Color,
    val card: Color,
    val ink: Color,
    val ink2: Color,
    val ink3: Color,
    val line: Color,
    val brand: Color,
    val onBrand: Color,
    val brandSurface: Color,
    val expense: Color,
    val expenseSurface: Color,
    val income: Color,
    val slate: Color,
    val gold: Color,
    val goldHi: Color,
    val glow: Color,
    val onGlow: Color,
    val summaryBar: Color,
    val onSummaryBar: Color,
    val disabledAlpha: Float = 0.4f,
) {
    /**
     * 墨底汇总卡上要「变陶土」的那支颜色：`expense` 在浅色档是给纸面用的（#BF4E3B），
     * 压在墨底上对比不够；深色档的汇总卡反而是米白底，直接用原色才对。
     * 和 `goldHi` 一样——它本来就是「金在墨底上的那一档」，这里是它在超支语义上的对应物。
     */
    val expenseOnSummary: Color
        get() = if (isDark) expense else expenseSurface
}

private fun Color.tintedOn(base: Color, alpha: Float): Color = copy(alpha = alpha).compositeOver(base)

/**
 * 由皮肤 + 明暗档拼出整套 token。
 *
 * 三个槽不在色表里、这里现算：`line` 是正文压在纸面上的半透明，
 * `brandSurface` 是主操作压在卡片上的浅底，`expenseSurface` 是支出色压在卡片上的浅底。
 * 收进派生而不是写死，十套皮肤就不必各抄一遍——加一套只填 9 个实色。
 */
internal fun tallyColorsFor(skin: TallySkin, dark: Boolean): TallyColors {
    val tone = TallySkins.tone(skin, dark)
    val paper = Color(tone.paper)
    val card = Color(tone.card)
    val ink = Color(tone.ink)
    val brand = Color(tone.brand)
    val expense = Color(TallySemantic.expense(dark))
    return TallyColors(
        isDark = dark,
        paper = paper,
        card = card,
        ink = ink,
        ink2 = Color(tone.ink2),
        ink3 = Color(tone.ink3),
        line = ink.tintedOn(paper, if (dark) 0.12f else 0.10f),
        brand = brand,
        onBrand = Color(tone.onBrand),
        brandSurface = brand.tintedOn(card, if (dark) 0.10f else 0.08f),
        expense = expense,
        expenseSurface = expense.tintedOn(card, 0.14f),
        income = Color(TallySemantic.income(dark)),
        slate = Color(TallySemantic.slate(dark)),
        gold = Color(TallySemantic.gold(dark)),
        goldHi = Color(TallySemantic.goldHi(dark)),
        glow = if (dark) Color(TallySemantic.goldLight).tintedOn(card, 0.26f) else Color(TallySemantic.glowLight),
        onGlow = Color(TallySemantic.onGlow(dark)),
        summaryBar = Color(tone.summaryBar),
        onSummaryBar = Color(tone.onSummaryBar),
    )
}

val LocalTallyColors = staticCompositionLocalOf { tallyColorsFor(TallySkin.INK, dark = false) }

val TallyShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

val TallyPillShape = RoundedCornerShape(50)
val TallySheetShape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp, bottomStart = 0.dp, bottomEnd = 0.dp)
val TallyIconTileShape = RoundedCornerShape(9.dp)

/** Level 1 卡片柔影、Level 2 弹层阴影，纸面本身无阴影。 */
data class TallyShadow(val offsetY: Int, val blur: Int, val alpha: Float)

object TallyElevation {
    val LEVEL_0 = TallyShadow(0, 0, 0f)
    val LEVEL_1 = TallyShadow(3, 8, 0.16f)
    val LEVEL_2 = TallyShadow(16, 34, 0.40f)
}

@Composable
fun TallyTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    skin: TallySkin = TallySkin.INK,
    content: @Composable () -> Unit,
) {
    // 皮肤与明暗档是极少变的两个输入，按它们记忆：切账本、敲数字都不该重新拼一遍色表。
    val colors = remember(darkTheme, skin) { tallyColorsFor(skin, darkTheme) }
    val scheme = if (darkTheme) {
        darkColorScheme(
            primary = colors.brand,
            onPrimary = colors.onBrand,
            secondary = colors.gold,
            onSecondary = colors.onGlow,
            background = colors.paper,
            onBackground = colors.ink,
            surface = colors.paper,
            onSurface = colors.ink,
            surfaceVariant = colors.card,
            onSurfaceVariant = colors.ink2,
            outline = colors.ink3,
            outlineVariant = colors.line,
            error = colors.expense,
            onError = colors.paper,
        )
    } else {
        lightColorScheme(
            primary = colors.brand,
            onPrimary = colors.onBrand,
            secondary = colors.gold,
            onSecondary = colors.onGlow,
            background = colors.paper,
            onBackground = colors.ink,
            surface = colors.paper,
            onSurface = colors.ink,
            surfaceVariant = colors.card,
            onSurfaceVariant = colors.ink2,
            outline = colors.ink3,
            outlineVariant = colors.line,
            error = colors.expense,
            onError = colors.card,
        )
    }
    androidx.compose.material3.MaterialTheme(
        colorScheme = scheme,
        typography = TallyMaterialTypography,
        shapes = TallyShapes,
        content = {
            CompositionLocalProvider(LocalTallyColors provides colors, content = content)
        },
    )
}

val tallyColors: TallyColors
    @Composable get() = LocalTallyColors.current
