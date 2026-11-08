package io.github.guohangbk.tally.ui.design

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog

/** Level 0 纸面 / Level 1 卡片柔影 / Level 2 弹层。 */
enum class CardLevel {
    FLAT,
    CARD,
    SHEET,
}

@Composable
fun TallyCard(
    modifier: Modifier = Modifier,
    level: CardLevel = CardLevel.CARD,
    borderColor: Color? = tallyColors.line,
    corner: Int = 15,
    contentPadding: androidx.compose.ui.unit.Dp = 12.dp,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val colors = tallyColors
    val haptics = rememberTallyHaptics()
    val shape = RoundedCornerShape(corner.dp)
    val base = modifier
        .fillMaxWidth()
        // 裁剪交给 shadow 自己做（clip = true），不要再叠一层 .clip(shape)：
        // 两者各起一个 RenderNode，合并之后同样的圆角、同样的阴影，每卡少一层。
        .shadow(
            elevation = when (level) {
                CardLevel.FLAT -> 0.dp
                CardLevel.CARD -> 3.dp
                CardLevel.SHEET -> 16.dp
            },
            shape = shape,
            clip = true,
            ambientColor = colors.ink,
            spotColor = colors.ink,
        )
        .background(colors.card)
        .then(
            if (borderColor != null) Modifier.border(BorderStroke(1.dp, borderColor), shape) else Modifier,
        )
    Box(
        modifier = base
            .then(
                if (onClick != null) {
                    Modifier.clickable {
                        haptics.tap()
                        onClick()
                    }
                } else {
                    Modifier
                },
            )
            .padding(contentPadding),
    ) {
        content()
    }
}

enum class TagTone {
    NEUTRAL,
    BRAND,
    GOLD,
    DANGER,
    INK,
    SLATE,
    OUTLINE,
}

/** 小标签 / 徽标：默认账本、待报销、不计入收支、来源等。 */
@Composable
fun TallyTag(
    text: String,
    modifier: Modifier = Modifier,
    tone: TagTone = TagTone.NEUTRAL,
) {
    val colors = tallyColors
    val (background, foreground, border) = when (tone) {
        TagTone.NEUTRAL -> Triple(colors.paper, colors.ink2, colors.line)
        TagTone.BRAND -> Triple(colors.brand, colors.onBrand, colors.brand)
        TagTone.GOLD -> Triple(colors.glow, colors.onGlow, colors.gold.copy(alpha = 0.28f))
        TagTone.DANGER -> Triple(colors.expense, Color.White, colors.expense)
        TagTone.INK -> Triple(colors.ink, colors.paper, colors.ink)
        TagTone.SLATE -> Triple(colors.paper, colors.slate, colors.slate.copy(alpha = 0.4f))
        TagTone.OUTLINE -> Triple(colors.paper, colors.ink2, colors.line)
    }
    val shape = RoundedCornerShape(17.dp)
    Box(
        modifier = modifier
            .clip(shape)
            .background(background)
            .border(1.dp, border, shape)
            .padding(horizontal = 7.dp, vertical = 2.5.dp),
    ) {
        Text(
            text = text,
            fontSize = 10.5.sp,
            lineHeight = 14.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.06.em,
            color = foreground,
            maxLines = 1,
        )
    }
}

/**
 * 卡片动作条上的一格（账本 / 周期 / 分期 / 预算四页共用）。
 * 高度 32dp、圆角 9dp、纸底 1dp 描边；图标 12.5 + 文字 11.5，四格等宽由调用方给 `weight(1f)`。
 * 不可用降透明度而不是消失——位置跳动的列表没法单手点。
 */
enum class CardActionTone { NEUTRAL, BRAND, GOLD, DANGER }

@Composable
fun TallyCardAction(
    label: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    iconKey: String? = null,
    enabled: Boolean = true,
    tone: CardActionTone = CardActionTone.NEUTRAL,
    contentDescription: String? = label,
    leading: (@Composable () -> Unit)? = null,
) {
    val colors = tallyColors
    // 有底色的格子（默认 / 补记待办）表达的是状态，不是「按不动」，所以不跟着降透明度。
    val alpha = if (enabled || tone != CardActionTone.NEUTRAL) 1f else colors.disabledAlpha
    val (background, borderColor, contentColor) = when (tone) {
        CardActionTone.NEUTRAL -> Triple(colors.paper, colors.line, colors.ink)
        CardActionTone.BRAND -> Triple(colors.brandSurface, colors.brand.copy(alpha = 0.2f), colors.brand)
        CardActionTone.GOLD -> Triple(colors.glow, colors.gold.copy(alpha = 0.3f), colors.onGlow)
        CardActionTone.DANGER -> Triple(colors.paper, colors.expense.copy(alpha = 0.35f), colors.expense)
    }
    val haptics = rememberTallyHaptics()
    val shape = RoundedCornerShape(9.dp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(32.dp)
            .clip(shape)
            .background(background)
            .border(1.dp, borderColor, shape)
            .clickable(enabled = enabled) {
                haptics.tap()
                onClick()
            },
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading?.invoke()
        if (leading != null && label != null) Spacer(Modifier.width(5.dp))
        if (iconKey != null) {
            InlineIcon(iconKey, contentDescription = contentDescription, size = 12.5.dp, tint = contentColor.copy(alpha = alpha))
        }
        if (label != null) {
            Spacer(Modifier.width(4.dp))
            Text(
                label,
                fontSize = 11.5.sp,
                lineHeight = 15.sp,
                fontWeight = if (tone == CardActionTone.NEUTRAL) FontWeight.Medium else FontWeight.SemiBold,
                color = contentColor.copy(alpha = alpha),
                maxLines = 1,
            )
        }
    }
}

/** ⋯ 里的一条动作；`danger` 是那一项转陶土。低频动作走这里，常用的删除在动作条上是 [CardActionTone.DANGER] 单独一格。 */
data class TallyMenuEntry(val label: String, val danger: Boolean = false, val onClick: () -> Unit)

/**
 * 卡片动作条最后一格的 ⋯：与 [TallyCardAction] 同尺寸的纸底格，点开一枚只放低频与不可逆动作的菜单。
 * [tint] 给墨底卡用（预算总额卡那格的图标要亮一档）。
 */
@Composable
fun TallyMoreMenu(
    entries: List<TallyMenuEntry>,
    modifier: Modifier = Modifier,
    tint: Color? = null,
) {
    val colors = tallyColors
    val haptics = rememberTallyHaptics()
    var expanded by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(9.dp)
    Box(modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(32.dp)
                .clip(shape)
                .background(colors.paper)
                .border(1.dp, colors.line, shape)
                .clickable {
                    haptics.tap()
                    expanded = true
                },
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            InlineIcon("more", contentDescription = "更多操作", size = 12.5.dp, tint = tint ?: colors.ink)
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            containerColor = colors.card,
        ) {
            entries.forEach { entry ->
                DropdownMenuItem(
                    text = {
                        Text(
                            entry.label,
                            fontSize = 13.5.sp, lineHeight = 16.9.sp,
                            color = if (entry.danger) colors.expense else colors.ink,
                        )
                    },
                    onClick = {
                        expanded = false
                        entry.onClick()
                    },
                )
            }
        }
    }
}

enum class ButtonVariant {
    /** 松烟底：主要 / 保存。 */
    PRIMARY,
    /** 墨绿底：品牌操作。 */
    BRAND,

    /** 描边：次要。 */
    GHOST,

    /** 陶土底：危险。 */
    DANGER,

    /** 金晕：仅小莉相关。 */
    GOLD,
}

@Composable
fun TallyButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: ButtonVariant = ButtonVariant.PRIMARY,
    enabled: Boolean = true,
    height: Int = 38,
    leadingIcon: String? = null,
) {
    val colors = tallyColors
    val haptics = rememberTallyHaptics()
    val (background, foreground, border) = when (variant) {
        ButtonVariant.PRIMARY -> Triple(colors.ink, colors.paper, colors.ink)
        ButtonVariant.BRAND -> Triple(colors.brand, colors.onBrand, colors.brand)
        ButtonVariant.GHOST -> Triple(colors.card, colors.ink, colors.line)
        ButtonVariant.DANGER -> Triple(colors.expense, Color.White, colors.expense)
        ButtonVariant.GOLD -> Triple(colors.glow, colors.onGlow, colors.gold.copy(alpha = 0.4f))
    }
    val shape = RoundedCornerShape(13.dp)
    val contentAlpha = if (enabled) 1f else colors.disabledAlpha
    Box(
        modifier = modifier
            .height(height.dp)
            .clip(shape)
            .background(background.copy(alpha = background.alpha * contentAlpha))
            .border(1.dp, border.copy(alpha = border.alpha * contentAlpha), shape)
            .clickable(enabled = enabled) {
                haptics.tap()
                onClick()
            },
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            if (leadingIcon != null) {
                InlineIcon(
                    iconKey = leadingIcon,
                    contentDescription = null,
                    size = 15.dp,
                    tint = foreground.copy(alpha = contentAlpha),
                )
                Spacer(Modifier.width(6.dp))
            }
            Text(
                label,
                style = TallyType.listTitle.copy(lineHeight = 18.8.sp, fontWeight = FontWeight.SemiBold),
                color = foreground.copy(alpha = contentAlpha),
                textAlign = TextAlign.Center,
                maxLines = 1,
                softWrap = false,
                overflow = androidx.compose.ui.text.style.TextOverflow.Clip,
            )
        }
    }
}

/** 设计板 .sw：32×18 的窄轨开关，Material 的 52×32 太占地方。 */
@Composable
fun TallySwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onColor: Color = tallyColors.brand,
) {
    val colors = tallyColors
    val haptics = rememberTallyHaptics()
    Box(
        modifier = modifier
            .size(width = 38.dp, height = 21.dp)
            .clip(CircleShape)
            .background(if (checked) onColor else colors.slate.copy(alpha = 0.4f))
            .clickable(enabled = enabled) {
                haptics.tap()
                onCheckedChange(!checked)
            }
            .padding(horizontal = 3.dp, vertical = 3.dp),
        contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(
            modifier = Modifier
                .size(15.dp)
                .shadow(1.dp, CircleShape, clip = true)
                .background(if (colors.isDark) colors.ink else Color.White),
        )
    }
}

@Composable
fun TallyLabeledSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    description: String? = null,
    gold: Boolean = false,
    enabled: Boolean = true,
) {
    val colors = tallyColors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, fontSize = 13.5.sp, lineHeight = 16.9.sp, color = colors.ink)
            if (description != null) {
                Text(description, fontSize = 11.sp, lineHeight = 13.8.sp, color = colors.ink3)
            }
        }
        TallySwitch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            onColor = if (gold) colors.gold else colors.brand,
        )
    }
}

@Composable
fun TallySectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = TallyType.eyebrow,
        color = tallyColors.gold,
        modifier = modifier.padding(top = 18.dp, bottom = 8.dp),
    )
}

@Composable
fun <T> TallySegmented(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    activeColor: ((T) -> Color)? = null,
) {
    val colors = tallyColors
    val haptics = rememberTallyHaptics()
    val outer = RoundedCornerShape(11.dp)
    val inner = RoundedCornerShape(8.dp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(outer)
            .background(colors.ink.copy(alpha = if (colors.isDark) 0.08f else 0.06f))
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        options.forEach { (value, label) ->
            val active = value == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(inner)
                    .then(if (active) Modifier.shadow(1.5.dp, inner, clip = false) else Modifier)
                    .background(if (active) colors.card else Color.Transparent)
                    .clickable {
                        haptics.tap()
                        onSelect(value)
                    }
                    .padding(vertical = 5.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    fontSize = 12.5.sp,
                    lineHeight = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = when {
                        !active -> colors.ink3
                        activeColor != null -> activeColor(value)
                        else -> colors.ink
                    },
                    maxLines = 1,
                )
            }
        }
    }
}

/** 空状态：纸面留白 + 图标 + 一句引导。 */
@Composable
fun TallyEmptyState(
    title: String,
    description: String,
    modifier: Modifier = Modifier,
    iconKey: String = "note",
    action: (@Composable () -> Unit)? = null,
) {
    val colors = tallyColors
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        IconTile(iconKey = iconKey, label = title, size = 44.dp, corner = 14.dp, tone = IconTone.PAPER)
        Spacer(Modifier.height(12.dp))
        Text(title, style = TallyType.sectionTitle, color = colors.ink)
        Spacer(Modifier.height(4.dp))
        Text(
            text = description,
            style = TallyType.helper,
            color = colors.ink3,
            textAlign = TextAlign.Center,
        )
        if (action != null) {
            Spacer(Modifier.height(14.dp))
            action()
        }
    }
}

/** 错误态：陶土描边卡片，就地说明 + 修复入口。 */
@Composable
fun TallyErrorBanner(
    title: String,
    detail: String? = null,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val colors = tallyColors
    TallyCard(
        modifier = modifier,
        borderColor = colors.expense.copy(alpha = 0.35f),
        contentPadding = 12.dp,
    ) {
        Column {
            Row(verticalAlignment = Alignment.Top) {
                InlineIcon(iconKey = "warn", contentDescription = "警告", size = 16.dp, tint = colors.expense)
                Spacer(Modifier.width(9.dp))
                Column {
                    Text(title, style = TallyType.listTitle.copy(fontWeight = FontWeight.SemiBold), color = colors.expense)
                    if (detail != null) {
                        Spacer(Modifier.height(4.dp))
                        Text(detail, style = TallyType.helper, color = colors.ink2)
                    }
                }
            }
            if (actionLabel != null && onAction != null) {
                Spacer(Modifier.height(9.dp))
                TallyButton(label = actionLabel, onClick = onAction, variant = ButtonVariant.GHOST, height = 30, modifier = Modifier.width(180.dp))
            }
        }
    }
}

/** 端侧下载等场景的确定进度条：细轨 + 圆头，配色走墨绿 brand。 */
@Composable
fun TallyProgressBar(
    progress: Float,
    modifier: Modifier = Modifier,
    height: Int = 6,
    color: Color = tallyColors.brand,
) {
    val colors = tallyColors
    val track = RoundedCornerShape((height / 2).dp)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height.dp)
            .clip(track)
            .background(colors.ink.copy(alpha = if (colors.isDark) 0.16f else 0.08f)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(progress.coerceIn(0f, 1f))
                .height(height.dp)
                .clip(track)
                .background(color),
        )
    }
}

@Composable
fun TallyConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String? = null,
    onConfirm: () -> Unit = {},
    modifier: Modifier = Modifier,
    dismissLabel: String = "取消",
    onDismiss: () -> Unit = {},
    destructive: Boolean = false,
    confirmEnabled: Boolean = true,
    content: (@Composable () -> Unit)? = null,
) {
    val colors = tallyColors
    val shape = RoundedCornerShape(20.dp)
    // 设计板 .modal：纸面卡片、左右各留 20dp、标题衬线、按钮等宽 34dp。
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .clip(shape)
                .background(colors.card)
                .border(1.dp, colors.line, shape)
                .padding(start = 18.dp, end = 18.dp, top = 19.dp, bottom = 16.dp),
        ) {
            Text(title, style = TallyType.screenTitle.copy(fontSize = 17.sp, lineHeight = 21.3.sp), color = colors.ink)
            if (message.isNotEmpty()) {
                Spacer(Modifier.height(7.dp))
                Text(message, fontSize = 12.5.sp, lineHeight = 21.sp, color = colors.ink2)
            }
            if (content != null) {
                Spacer(Modifier.height(12.dp))
                content()
            }
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TallyButton(
                    label = dismissLabel,
                    onClick = onDismiss,
                    variant = ButtonVariant.GHOST,
                    height = 34,
                    modifier = Modifier.weight(1f),
                )
                // confirmLabel 传 null 就是只读弹窗：只剩一个关闭按钮，不摆一个没有动作的主按钮。
                if (confirmLabel != null) {
                    TallyButton(
                        label = confirmLabel,
                        onClick = onConfirm,
                        variant = if (destructive) ButtonVariant.DANGER else ButtonVariant.BRAND,
                        enabled = confirmEnabled,
                        height = 34,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

/** 列表行：图标位 + 主副标题 + 右侧值/操作。 */
@Composable
fun TallyListRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    iconKey: String? = null,
    iconTone: IconTone = IconTone.PAPER,
    iconSize: Int = 34,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    val colors = tallyColors
    val haptics = rememberTallyHaptics()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (onClick != null) {
                    Modifier.clickable {
                        haptics.tap()
                        onClick()
                    }
                } else {
                    Modifier
                },
            )
            .padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (iconKey != null) {
            IconTile(iconKey = iconKey, label = title, size = iconSize.dp, corner = 10.dp, tone = iconTone)
            Spacer(Modifier.width(11.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = TallyType.listTitle, color = colors.ink)
            if (subtitle != null) {
                Text(subtitle, style = TallyType.helper, color = colors.ink3)
            }
        }
        if (trailing != null) trailing()
    }
}

/** 行尾只放一个箭头：能点的行不需要再写「进入 / 查看 / 更多」这种字。 */
@Composable
fun TallyRowChevron(modifier: Modifier = Modifier) {
    InlineIcon(iconKey = "chevron", contentDescription = null, modifier = modifier, size = 13.dp, tint = tallyColors.ink3)
}

@Composable
fun BottomSheetGrabber(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .width(38.dp)
            .height(4.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(tallyColors.ink3.copy(alpha = 0.4f)),
    )
}

/**
 * 全站唯一的底部弹层。
 *
 * 抓手只留 M3 默认那一条——以前各处既用默认 dragHandle、又在内容里画一根，
 * 顶上就是两条横杠。内容自己负责限高和内部滚动（长列表不要顶满整屏）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TallyBottomSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    containerColor: Color = tallyColors.paper,
    skipPartiallyExpanded: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = tallyColors
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = skipPartiallyExpanded),
        containerColor = containerColor,
        shape = TallySheetShape,
        scrimColor = colors.ink.copy(alpha = if (colors.isDark) 0.62f else 0.42f),
        dragHandle = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) { BottomSheetGrabber() }
        },
        content = content,
    )
}

/**
 * 弹层正文的限高：内容长度跟着数据走时用它，360dp 封顶并内部滚动。
 * 数据一多就顶满整屏是以前的常见毛病，别在调用点再手写一遍。
 */
@Composable
fun Modifier.sheetBody(): Modifier =
    heightIn(max = 360.dp)
        .verticalScroll(rememberScrollState())

@Composable
fun ColumnGap(height: Int) {
    Spacer(Modifier.height(height.dp))
}

@Composable
fun RowGap(width: Int) {
    Spacer(Modifier.width(width.dp))
}
