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

/**
 * 「Ink & Jasmine」：日常记账是纸墨（骨白纸面 / 松烟正文 / 墨绿主操作 + 衬线数字），
 * 金晕只出现在小莉相关的建议、光晕与状态点上。不开小莉，界面里没有一丝金色。
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

private val LightColors = TallyColors(
    isDark = false,
    paper = Color(0xFFF6F3EB),
    card = Color(0xFFFFFDF8),
    ink = Color(0xFF16211D),
    ink2 = Color(0xFF4C5A53),
    ink3 = Color(0xFF8D978F),
    line = Color(0xFF16211D).tintedOn(Color(0xFFF6F3EB), 0.10f),
    brand = Color(0xFF1F4A38),
    onBrand = Color(0xFFF6F3EB),
    brandSurface = Color(0xFF1F4A38).tintedOn(Color(0xFFFFFDF8), 0.08f),
    expense = Color(0xFFBF4E3B),
    expenseSurface = Color(0xFFF6E4DF),
    income = Color(0xFF1F4A38),
    slate = Color(0xFF5E7A8A),
    gold = Color(0xFFB98A34),
    goldHi = Color(0xFFE8C57A),
    glow = Color(0xFFF5E3BE),
    onGlow = Color(0xFF7A5A18),
    summaryBar = Color(0xFF16211D),
    onSummaryBar = Color(0xFFF6F3EB),
)

private val DarkColors = TallyColors(
    isDark = true,
    paper = Color(0xFF131A17),
    card = Color(0xFF1A2320),
    ink = Color(0xFFE9E5DA),
    ink2 = Color(0xFFB9C2BB),
    ink3 = Color(0xFF93A09A),
    line = Color(0xFFE9E5DA).tintedOn(Color(0xFF131A17), 0.12f),
    brand = Color(0xFF7ECBA9),
    onBrand = Color(0xFF131A17),
    brandSurface = Color(0xFF7ECBA9).tintedOn(Color(0xFF1A2320), 0.10f),
    expense = Color(0xFFE8917B),
    expenseSurface = Color(0xFFE8917B).tintedOn(Color(0xFF1A2320), 0.14f),
    income = Color(0xFF7ECBA9),
    slate = Color(0xFF9FB6C2),
    gold = Color(0xFFE8C57A),
    goldHi = Color(0xFFF5E3BE),
    glow = Color(0xFFB98A34).tintedOn(Color(0xFF1A2320), 0.26f),
    onGlow = Color(0xFFE8C57A),
    summaryBar = Color(0xFFE9E5DA),
    onSummaryBar = Color(0xFF131A17),
)

val LocalTallyColors = staticCompositionLocalOf { LightColors }

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
fun TallyTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val colors = if (darkTheme) DarkColors else LightColors
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
            onError = Color(0xFF131A17),
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
            onError = Color.White,
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
