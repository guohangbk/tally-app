package io.github.guohangbk.tally.ui.settings

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import io.github.guohangbk.tally.ui.design.ButtonVariant
import io.github.guohangbk.tally.ui.design.Hairline
import io.github.guohangbk.tally.ui.design.IconTile
import io.github.guohangbk.tally.ui.design.IconTone
import io.github.guohangbk.tally.ui.design.TagTone
import io.github.guohangbk.tally.ui.design.TallyButton
import io.github.guohangbk.tally.ui.design.TallyCard
import io.github.guohangbk.tally.ui.design.TallyLabeledSwitch
import io.github.guohangbk.tally.ui.design.TallyListRow
import io.github.guohangbk.tally.ui.design.TallyRowChevron
import io.github.guohangbk.tally.ui.design.TallySectionLabel
import io.github.guohangbk.tally.ui.design.TallySegmented
import io.github.guohangbk.tally.ui.design.TallyTag
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
    gold: Boolean = false,
) {
    TallyLabeledSwitch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        label = label,
        description = description,
        gold = gold,
    )
}

@Composable
internal fun <T> SettingsChoiceRow(
    label: String,
    description: String?,
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
) {
    val colors = tallyColors
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 9.dp)) {
        Text(label, style = TallyType.listTitle, color = colors.ink)
        if (description != null) {
            Text(description, style = TallyType.helper, color = colors.ink3)
        }
        Spacer(Modifier.height(8.dp))
        TallySegmented(options = options, selected = selected, onSelect = onSelect)
    }
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

/** 软提示：不是失败，只是「开关没有按你期望生效」时的就地说明。 */
@Composable
internal fun SettingsNotice(
    text: String,
    onDismiss: () -> Unit,
) {
    val colors = tallyColors
    TallyCard(borderColor = colors.brand.copy(alpha = 0.28f), contentPadding = 11.dp) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text, style = TallyType.helper, color = colors.ink2, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(9.dp))
            TallyButton(
                label = "知道了",
                onClick = onDismiss,
                variant = ButtonVariant.GHOST,
                height = 28,
                modifier = Modifier.width(66.dp),
            )
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
            Text("1–28 号 · 首页与统计按这一天切分月份", style = TallyType.helper, color = colors.ink3)
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
