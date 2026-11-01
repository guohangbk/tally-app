package io.github.guohangbk.tally.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.guohangbk.tally.domain.formatAmount
import io.github.guohangbk.tally.domain.model.BudgetUsage

/**
 * 预算的进度条：灰底是整段的 100%，填充按档位变色。
 *
 * 三档填充色是板上定的——0–80% 墨绿、80–100% 金晕、超 100% 陶土；金晕这一支的依据是
 * 「预算阈值属于机器建议你去点什么的场合」（板 §12 的金晕边界）。
 * 阈值不再画刻度：板上的 1.5dp 竖线搬进 6dp 细条就是那个像断裂的断点（2026-10-28 走查），
 * 阈值本来就在编辑页的滑条上能看能改，这里不重复表达。[trackOverride] 给墨底汇总卡用——
 * 默认的墨色轨道压在墨底上等于隐形，「没有灰底代表 100%」说的就是这个。
 */
@Composable
fun BudgetProgressBar(
    usage: BudgetUsage,
    modifier: Modifier = Modifier,
    height: Int = 6,
    trackOverride: Color? = null,
) {
    val colors = tallyColors
    val shape = RoundedCornerShape((height / 2).dp)
    val ratio = usage.ratio.coerceIn(0f, 1f)
    val fill: Brush = when {
        usage.isOver -> Brush.linearGradient(listOf(colors.expense, colors.expense))
        usage.ratio >= 0.8f -> Brush.linearGradient(listOf(colors.goldHi, colors.gold))
        else -> Brush.linearGradient(listOf(colors.brand, colors.brand))
    }
    val track = trackOverride ?: colors.ink.copy(alpha = if (colors.isDark) 0.20f else 0.12f)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height.dp)
            .clip(shape)
            .background(track),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(ratio)
                .height(height.dp)
                .clip(shape)
                .background(fill),
        )
    }
}

/** 预算文案里的金额：整元不写角分，非整元才两位小数（走查口径「总预算：1000，已使用：500」）。 */
fun budgetAmountText(minor: Long, grouping: Boolean = true): String = formatAmount(minor, grouping).removeSuffix(".00")

/** 一条预算的用量一句话：首页速览 / 统计页 / 预算页三处同一串字，不各写一套。 */
fun budgetUsageCaption(usage: BudgetUsage, grouping: Boolean = true): String =
    "总预算：${budgetAmountText(usage.item.amountMinor, grouping)}，已使用：${budgetAmountText(usage.usedMinor, grouping)}"

/** 已用比例：超支也照实写（120%），不再用「超多少元」表达（2026-10-28 走查）。 */
fun budgetRatioText(usage: BudgetUsage): String = "${(usage.ratio * 100).toInt()}%"

/**
 * 预算用量的一行紧凑写法，首页速览与统计页共用（2026-10-28 走查第 6、10 条）：
 * 名称把「总预算 / 已使用」带在括号里，进度条后面只跟一个比例；不打「提醒已关闭」，也不写超了多少。
 * 比 [BudgetCategoryRow] 少一圈卡片与边框，塞得进统计页那种密集列表。
 */
@Composable
fun BudgetUsageRow(
    usage: BudgetUsage,
    title: String,
    iconKey: String,
    grouping: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = tallyColors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(9.dp))
            .clickable(onClick = onClick)
            .padding(top = 6.dp, bottom = 6.dp, end = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(26.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(colors.ink.copy(alpha = 0.05f))
                .border(1.dp, colors.line, RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center,
        ) {
            InlineIcon(iconKey = iconKey, contentDescription = null, size = 14.dp, tint = colors.ink2)
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "$title（${budgetUsageCaption(usage, grouping)}）",
                fontSize = 12.5.sp,
                lineHeight = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = colors.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(5.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                BudgetProgressBar(
                    usage = usage,
                    height = 4,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = budgetRatioText(usage),
                    style = TallyType.moneyList.copy(fontSize = 10.sp, lineHeight = 12.sp),
                    color = if (usage.isOver) colors.expense else colors.ink3,
                    maxLines = 1,
                )
            }
        }
        InlineIcon(iconKey = "chevron", contentDescription = "看预算页", size = 13.dp, tint = colors.ink3)
    }
}

/**
 * 预算行右端的 ⋯：删除收在这里，编辑页顶栏那个垃圾桶撤了（2026-10-28 走查）。
 * 与分期管理同一套——行内点整条是编辑，破坏性动作进 ⋯。
 */
@Composable
fun BudgetMoreButton(onClick: () -> Unit, tint: Color) {
    val colors = tallyColors
    var expanded by remember { mutableStateOf(false) }
    Box {
        Box(
            modifier = Modifier
                .size(26.dp)
                .clip(CircleShape)
                .clickable { expanded = true },
            contentAlignment = Alignment.Center,
        ) {
            InlineIcon(iconKey = "more", contentDescription = "更多操作", size = 15.dp, tint = tint)
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            containerColor = colors.card,
        ) {
            DropdownMenuItem(
                text = { Text("删除这条预算", fontSize = 13.5.sp, lineHeight = 16.9.sp, color = colors.expense) },
                onClick = {
                    expanded = false
                    onClick()
                },
            )
        }
    }
}

/** 分类预算行：图标瓦 + 名称 + 总预算/已使用 + 进度条 + 右端比例，删除在 ⋯ 里。 */
@Composable
fun BudgetCategoryRow(
    usage: BudgetUsage,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = tallyColors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(colors.card)
            .border(1.dp, colors.line, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(start = 12.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconTile(
            iconKey = usage.item.categoryIconKey ?: "tag",
            label = usage.item.categoryName ?: "分类",
            size = 28.dp,
            corner = 8.dp,
        )
        Column(modifier = Modifier.weight(1f).padding(start = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = usage.item.categoryName ?: "分类",
                    style = TallyType.listTitle,
                    color = colors.ink,
                    maxLines = 1,
                )
                // 开关只管提醒，进度照算：这里只标明状态，不降对比（2026-10-28 走查）。
                if (!usage.item.isActive) {
                    TallyTag(text = "提醒已关闭", tone = TagTone.SLATE)
                }
            }
            Text(
                text = budgetUsageCaption(usage),
                style = TallyType.helper,
                color = colors.ink3,
                maxLines = 1,
            )
            Spacer(Modifier.height(5.dp))
            BudgetProgressBar(usage = usage, modifier = Modifier.fillMaxWidth())
        }
        Text(
            text = budgetRatioText(usage),
            style = TallyType.moneyList,
            color = if (usage.isOver) colors.expense else colors.ink2,
            modifier = Modifier.width(44.dp),
            textAlign = TextAlign.End,
            maxLines = 1,
        )
        BudgetMoreButton(onClick = onDelete, tint = colors.ink3)
    }
}
