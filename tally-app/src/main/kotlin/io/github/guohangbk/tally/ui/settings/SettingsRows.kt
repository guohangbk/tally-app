package io.github.guohangbk.tally.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import io.github.guohangbk.tally.domain.model.TallySkin
import io.github.guohangbk.tally.ui.design.ButtonVariant
import io.github.guohangbk.tally.ui.design.Hairline
import io.github.guohangbk.tally.ui.design.TallyButton
import io.github.guohangbk.tally.ui.design.TallyCard
import io.github.guohangbk.tally.ui.design.TallyLabeledSwitch
import io.github.guohangbk.tally.ui.design.TallyListRow
import io.github.guohangbk.tally.ui.design.TallyRowChevron
import io.github.guohangbk.tally.ui.design.TallySectionLabel
import io.github.guohangbk.tally.ui.design.TallyType
import io.github.guohangbk.tally.ui.design.tallyColors

/**
 * 设置页的行语言：分组眉标 + Level 1 卡片 + 发丝线分隔，对应设计板 P20 的 `.grpt` / `.grp` / `.set`。
 * 颜色一律来自 token，卡片描边只有小莉分组允许金色。
 */
@Composable
internal fun SettingsSection(
    title: String,
    modifier: Modifier = Modifier,
    gold: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colors = tallyColors
    Column(modifier = modifier.fillMaxWidth()) {
        TallySectionLabel(title)
        TallyCard(
            corner = 16,
            contentPadding = 9.dp,
            borderColor = if (gold) colors.gold.copy(alpha = 0.32f) else colors.line,
        ) {
            Column(modifier = Modifier.fillMaxWidth()) { content() }
        }
    }
}

@Composable
internal fun SettingsDivider() {
    Hairline(modifier = Modifier.padding(vertical = 1.dp))
}

@Composable
internal fun SettingsSwitchRow(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    label: String,
    description: String?,
    modifier: Modifier = Modifier,
    gold: Boolean = false,
    descriptionAction: (@Composable () -> Unit)? = null,
    /** 依赖别的开关时才置灰：关着的行仍然看得见，说明行写清为什么点不动。 */
    enabled: Boolean = true,
) {
    TallyLabeledSwitch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        label = label,
        modifier = modifier,
        description = description,
        descriptionAction = descriptionAction,
        gold = gold,
        enabled = enabled,
    )
}

/**
 * 子级开关行：挂在某一项主开关下面，靠一段缩进和左侧竖线表达从属，不另起分组、也不压分隔线。
 * 主开关关着时整行不渲染，所以这里不管可见性。
 */
@Composable
internal fun SettingsNestedSwitchRow(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    label: String,
    description: String?,
) {
    val colors = tallyColors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp)
            .height(IntrinsicSize.Min),
    ) {
        Box(
            modifier = Modifier
                .width(2.dp)
                .fillMaxHeight()
                .padding(vertical = 8.dp)
                .background(colors.line, RoundedCornerShape(1.dp)),
        )
        SettingsSwitchRow(
            checked = checked,
            onCheckedChange = onCheckedChange,
            label = label,
            description = description,
            modifier = Modifier
                .weight(1f)
                .padding(start = 12.dp),
        )
    }
}

/** 说明行末尾的文字链：品牌色、不加底色，别做成第二个按钮把一行说明挤成两行。 */
@Composable
internal fun SettingsInlineLink(text: String, onClick: () -> Unit) {
    val colors = tallyColors
    Text(
        text = text,
        style = TallyType.helper.copy(fontWeight = FontWeight.Medium),
        color = colors.brand,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 3.dp, vertical = 2.dp),
    )
}

/** 导航行右端只放一个箭头：文字「进入」既占位又重复了「这一行能点」这件事。 */
@Composable
internal fun SettingsNavRow(
    title: String,
    subtitle: String,
    iconKey: String,
    onClick: () -> Unit,
) {
    TallyListRow(
        title = title,
        subtitle = subtitle,
        iconKey = iconKey,
        iconSize = 30,
        onClick = onClick,
        trailing = { TallyRowChevron() },
    )
}

/**
 * 主题入口行：右侧写当前皮肤名，**字的颜色就是这套皮肤的主操作色**。
 *
 * 页面本身已经按这套皮肤渲染，所以直接取 `colors.brand` 即可——名字与颜色天然对齐，
 * 不需要再维护一份「皮肤 → 展示色」的映射。顺带还是个自检：两套皮肤如果右侧看起来一样，
 * 说明它们的 brand 拉不开，得改色表。
 */
@Composable
internal fun SettingsThemeRow(
    skin: TallySkin,
    onClick: () -> Unit,
) {
    val colors = tallyColors
    TallyListRow(
        title = "主题",
        subtitle = "10 套配色，深浅可跟随系统",
        iconKey = "paint",
        iconSize = 30,
        onClick = onClick,
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = skin.label,
                    style = TallyType.listTitle.copy(fontSize = 13.sp, lineHeight = 18.sp),
                    color = colors.brand,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.width(4.dp))
                TallyRowChevron()
            }
        },
    )
}

@Composable
internal fun SettingsInfoRow(
    label: String,
    value: String,
) {
    val colors = tallyColors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = TallyType.listTitle, color = colors.ink2, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(10.dp))
        Text(
            text = value,
            style = TallyType.listTitle.copy(fontSize = 13.sp, lineHeight = 18.sp),
            color = colors.ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 状态行：左边标题 + 一行说明，右边是系统里的真实状态和箭头。
 *
 * 整行可点，不另起「去设置」链接——那会把一行撑成两行，页面高度翻倍。
 * [status] 为 null 时右端只留箭头（自启动这类读不到状态的项）。
 */
@Composable
internal fun SettingsStatusRow(
    title: String,
    description: String?,
    status: String?,
    modifier: Modifier = Modifier,
    statusTone: Color = tallyColors.ink3,
    onClick: (() -> Unit)? = null,
) {
    val colors = tallyColors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = TallyType.listTitle, color = colors.ink)
            if (description != null) {
                Text(description, style = TallyType.helper, color = colors.ink3)
            }
        }
        if (status != null) {
            Spacer(Modifier.width(10.dp))
            Text(
                text = status,
                style = TallyType.listTitle.copy(fontSize = 13.sp, lineHeight = 18.sp),
                color = statusTone,
                maxLines = 1,
            )
        }
        if (onClick != null) {
            Spacer(Modifier.width(4.dp))
            TallyRowChevron()
        }
    }
}

/** 每月起始日：28 格选一个，避开 29–31 在短月里要额外解释的「月末对齐」。 */
@Composable
internal fun MonthStartDayDialog(
    selected: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    val colors = tallyColors
    var draft by remember { mutableIntStateOf(selected) }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .width(300.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(colors.paper)
                .border(1.dp, colors.line, RoundedCornerShape(20.dp))
                .padding(horizontal = 14.dp, vertical = 13.dp),
        ) {
            Text("每月起始日", style = TallyType.listTitle, color = colors.ink)
            Spacer(Modifier.height(2.dp))
            Text("1 到 28 号，首页与统计按这一天切分月份", style = TallyType.helper, color = colors.ink3)
            Spacer(Modifier.height(10.dp))
            (1..28).chunked(7).forEach { row ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    row.forEach { day ->
                        val active = day == draft
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(33.dp)
                                .clip(RoundedCornerShape(9.dp))
                                .background(if (active) colors.ink else colors.card)
                                .border(
                                    1.dp,
                                    if (active) colors.ink else colors.line,
                                    RoundedCornerShape(9.dp),
                                )
                                .clickable { draft = day },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = day.toString(),
                                style = TallyType.listTitle.copy(
                                    fontSize = 12.sp, lineHeight = 15.sp,
                                    fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                                ),
                                color = if (active) colors.paper else colors.ink2,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "每月 $draft 号起算",
                    style = TallyType.listTitle,
                    color = colors.ink,
                    modifier = Modifier.weight(1f),
                )
                TallyButton(
                    label = "取消",
                    onClick = onDismiss,
                    variant = ButtonVariant.GHOST,
                    height = 34,
                    modifier = Modifier.width(72.dp),
                )
                Spacer(Modifier.width(7.dp))
                TallyButton(
                    label = "确定",
                    onClick = { onConfirm(draft) },
                    variant = ButtonVariant.BRAND,
                    height = 34,
                    modifier = Modifier.width(72.dp),
                )
            }
        }
    }
}
