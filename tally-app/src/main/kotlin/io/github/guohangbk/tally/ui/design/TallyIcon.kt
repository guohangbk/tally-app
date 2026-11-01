package io.github.guohangbk.tally.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ceil

enum class IconTone {
    /** 默认：纸面底 + 松烟描边图标。 */
    PAPER,

    /** 高频分类：墨绿底加权。 */
    BRAND,

    /** 小莉命中：金晕底。 */
    GLOW,

    /** 选中：松烟底反白。 */
    INK,

    /** 支出 / 异常。 */
    EXPENSE,

    /** 不计入收支、报销。 */
    SLATE,
}

@Composable
fun toneColors(tone: IconTone): Pair<Color, Color> {
    val colors = tallyColors
    return when (tone) {
        IconTone.PAPER -> colors.card to colors.ink
        IconTone.BRAND -> colors.brand to colors.onBrand
        IconTone.GLOW -> colors.glow to colors.onGlow
        IconTone.INK -> colors.ink to colors.paper
        IconTone.EXPENSE -> colors.expenseSurface to colors.expense
        IconTone.SLATE -> colors.slate.copy(alpha = 0.14f) to colors.slate
    }
}

/**
 * 全进程共用的图标 painter：同一枚图标在同一尺寸上只建一棵组件树、只光栅化一张位图。
 *
 * `painterResource` 自带的缓存只到 XML 解析（`ImageVector`），`rememberVectorPainter` 仍然
 * 每个调用点各建一个 painter，而 painter 里的 `Vector` 会把整棵矢量树画进一张位图、按绘制
 * 尺寸缓存。于是同一个图标出现多少行就建多少棵树、光栅化多少次——关键词页 38 行全排进首帧
 * 要 300～495ms，去掉图标只剩 101ms，成本就在这里。
 *
 * 尺寸必须进键：那张位图是按绘制尺寸光栅化的，同一 painter 被两种尺寸交替绘制会每帧重画。
 * 颜色不必进键：tint 是画位图时的着色，不参与光栅化。
 */
private val sharedIconPainters = ConcurrentHashMap<Long, Painter>()

@Composable
private fun iconPainter(drawable: Int, size: Dp): Painter {
    val px = with(LocalDensity.current) { size.roundToPx() }
    val key = (drawable.toLong() shl 32) or px.toLong()
    sharedIconPainters[key]?.let { return it }
    return painterResource(drawable).also { sharedIconPainters[key] = it }
}

/**
 * 预热覆盖的两档字形尺寸：列表行（34dp 底里的 17dp）与记账页分类磁贴（18dp）。
 * 尺寸在缓存键里，所以一档一枚 painter；这两档是实测首帧绘制最重的两处——
 * 进记账页那一帧的遍历+绘制 49ms，基本就是 30 多枚 18dp 图标在现场光栅化。
 * 其余尺寸（13/14/15dp 行内小图标）位图小、数量少，留给首见时按需建。
 */
private val warmSizes = listOf(17.dp, 18.dp)

/**
 * 每帧预热一枚图标：建 painter，并且**替它画一次**。
 *
 * 只建 painter 是不够的——`Vector` 的缓存位图要到首次绘制那一下才光栅化，所以「预热完再点」
 * 和「没预热」在首帧上没有区别，只有点过两三次、位图逐次建起来之后才不卡。这里画进一张废画布，
 * 把光栅化也挪进预热。
 *
 * 尺寸必须和 `PainterModifier` 算出来的那个完全一致（intrinsic × Fit 的缩放系数，`Vector` 按
 * 绘制尺寸判缓存），差一个浮点真页首帧就会重画一次，预热等于白做。
 *
 * 一次一枚是为了不把首页自己的首帧拖慢；`index` 每帧自增会持续请求下一帧，
 * 所以这一串在冷启动后约 3 秒跑完，之后不再产生任何帧。
 */
@Composable
internal fun WarmIconPainters() {
    val ids = IconLibrary.allDrawables
    val density = LocalDensity.current
    var index by remember { mutableIntStateOf(0) }
    if (index < ids.size) {
        warmSizes.forEach { dp ->
            prerasterize(iconPainter(ids[index], dp), with(density) { dp.roundToPx() }, density)
        }
        LaunchedEffect(index) {
            withFrameNanos { }
            index += 1
        }
    }
}

private val warmScope = CanvasDrawScope()
private var warmScratch: ImageBitmap? = null

private fun prerasterize(painter: Painter, layoutPx: Int, density: Density) {
    val intrinsic = painter.intrinsicSize
    val layout = Size(layoutPx.toFloat(), layoutPx.toFloat())
    val target = if (intrinsic.width > 0f && intrinsic.height > 0f) {
        val factor = ContentScale.Fit.computeScaleFactor(intrinsic, layout)
        Size(intrinsic.width * factor.scaleX, intrinsic.height * factor.scaleY)
    } else {
        layout
    }
    val px = ceil(target.width).toInt().coerceAtLeast(1)
    val scratch = warmScratch?.takeIf { it.width >= px && it.height >= px }
        ?: ImageBitmap(px, px).also { warmScratch = it }
    warmScope.draw(density, LayoutDirection.Ltr, Canvas(scratch), target) {
        with(painter) { draw(size = target) }
    }
}

/**
 * 分类图标位：内置线性图标优先，未收录的键按 emoji 或名称首字兜底。
 */
@Composable
fun CategoryGlyph(
    iconKey: String,
    label: String,
    size: Dp,
    modifier: Modifier = Modifier,
    tint: Color = tallyColors.ink,
) {
    val drawable = IconLibrary.drawable(iconKey)
    when {
        drawable != null -> Icon(
            painter = iconPainter(drawable, size),
            contentDescription = label,
            tint = tint,
            modifier = modifier.size(size),
        )

        IconLibrary.isEmoji(iconKey) -> Text(
            text = iconKey,
            fontSize = (size.value * 0.72f).sp,
            modifier = modifier,
        )

        else -> Text(
            text = label.firstOrNull()?.toString() ?: "·",
            style = TallyType.listTitle.copy(fontWeight = FontWeight.Medium),
            color = tint,
            fontSize = (size.value * 0.58f).sp,
            modifier = modifier,
        )
    }
}

/** 图标底：24dp 网格、圆头线性图标 + 8% 墨绿底。 */
@Composable
fun IconTile(
    iconKey: String,
    label: String,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    corner: Dp = 12.dp,
    tone: IconTone = IconTone.PAPER,
    outlined: Boolean = true,
) {
    val (background, tint) = toneColors(tone)
    val colors = tallyColors
    Box(
        modifier = modifier
            .size(size)
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(corner))
            .background(background)
            .then(
                if (outlined && tone != IconTone.INK) {
                    Modifier.border(1.dp, colors.line, androidx.compose.foundation.shape.RoundedCornerShape(corner))
                } else {
                    Modifier
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        CategoryGlyph(iconKey = iconKey, label = label, size = (size.value * 0.5f).dp, tint = tint)
    }
}

/** 行内小图标，用于设置行、按钮与状态点旁。 */
@Composable
fun InlineIcon(
    iconKey: String,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    size: Dp = 14.dp,
    tint: Color = tallyColors.ink,
) {
    val drawable = IconLibrary.drawable(iconKey)
    if (drawable != null) {
        Icon(painter = iconPainter(drawable, size), contentDescription = contentDescription, tint = tint, modifier = modifier.size(size))
    } else {
        Box(modifier = modifier.size(size).defaultMinSize(minWidth = size, minHeight = size), contentAlignment = Alignment.Center) {
            Text(text = contentDescription?.firstOrNull()?.toString() ?: "?", fontSize = (size.value * 0.7f).sp, color = tint)
        }
    }
}

/** AI 状态点：金晕描边的小圆点。 */
@Composable
fun StatusDot(
    modifier: Modifier = Modifier,
    color: Color = tallyColors.gold,
    halo: Color = tallyColors.glow,
    size: Dp = 5.dp,
) {
    Box(
        modifier = modifier
            .size(size + 6.dp)
            .clip(CircleShape)
            .background(halo),
        contentAlignment = Alignment.Center,
    ) {
        Box(modifier = Modifier.size(size).clip(CircleShape).background(color))
    }
}

@Composable
fun Hairline(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxWidth().height(1.dp).background(tallyColors.line))
}
