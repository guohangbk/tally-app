package io.github.guohangbk.tally.ui.recycle

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.guohangbk.tally.data.repository.RecycleBinRepository
import io.github.guohangbk.tally.di.rememberAppGraph
import io.github.guohangbk.tally.domain.formatAmount
import io.github.guohangbk.tally.domain.model.PurgePreview
import io.github.guohangbk.tally.domain.model.RecycleGroup
import io.github.guohangbk.tally.domain.model.RecycleItem
import io.github.guohangbk.tally.domain.model.RecycleKeepDays
import io.github.guohangbk.tally.domain.model.RecycleSnapshot
import io.github.guohangbk.tally.ui.common.BarAction
import io.github.guohangbk.tally.ui.common.CheckBox
import io.github.guohangbk.tally.ui.common.SelectionBarShell
import io.github.guohangbk.tally.ui.common.tallyViewModel
import io.github.guohangbk.tally.ui.design.BottomSheetGrabber
import io.github.guohangbk.tally.ui.design.CategoryGlyph
import io.github.guohangbk.tally.ui.design.Hairline
import io.github.guohangbk.tally.ui.design.InlineIcon
import io.github.guohangbk.tally.ui.design.TallyCard
import io.github.guohangbk.tally.ui.design.TallyConfirmDialog
import io.github.guohangbk.tally.ui.design.TallyEmptyState
import io.github.guohangbk.tally.ui.design.TallyListRow
import io.github.guohangbk.tally.ui.design.TallySectionLabel
import io.github.guohangbk.tally.ui.design.TallyTag
import io.github.guohangbk.tally.ui.design.TallyToastEffects
import io.github.guohangbk.tally.ui.design.TallyType
import io.github.guohangbk.tally.ui.design.TagTone
import io.github.guohangbk.tally.ui.design.tallyColors
import io.github.guohangbk.tally.ui.shell.TallyIconAction
import io.github.guohangbk.tally.ui.shell.TallyTopBar
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * R-01 / R-02 / R-03：回收站。
 *
 * 数据是一直在写的 `deleted_at`，这一页做的只是把它列出来、能点回去。三个决定写在外面，
 * 免得日后被人当"顺手改一下"：
 *
 * 1. **选择态要长按才进**。一屏几十笔默认画满勾选框，会让人以为点「恢复」也得先勾一下。
 * 2. **彻底删除前把代价说清**：连带回收几张图、哪几张因为被别的账单共用而留下。
 *    这句话是那个确认框存在的理由，没有它就不必拦人。
 * 3. **保留期与附件回收共用一个设置**（见 [RecycleKeepDays]），分开设就会恢复出一笔没图的账。
 */
@Composable
fun RecycleBinScreen(onBack: () -> Unit) {
    val graph = rememberAppGraph()
    val viewModel = tallyViewModel { RecycleBinViewModel(graph) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val colors = tallyColors

    Column(modifier = Modifier.fillMaxSize().background(colors.paper)) {
        TallyTopBar(
            title = "回收站",
            onBack = onBack,
            trailing = {
                TallyIconAction(iconKey = "settings", contentDescription = "保留期设置", onClick = viewModel::showKeepDays)
            },
        )
        TallyToastEffects(
            notice = state.notice,
            error = state.error,
            errorTitle = "这一步没有完成",
            onNoticeShown = viewModel::dismissMessages,
            onErrorShown = viewModel::dismissMessages,
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 15.dp),
        ) {
            Spacer(Modifier.height(6.dp))
            when {
                state.loading -> Text("正在读回收站…", style = TallyType.helper, color = colors.ink3)

                state.snapshot.groups.isEmpty() -> TallyEmptyState(
                    title = "回收站是空的",
                    description = "删掉的账单在这里留 ${state.snapshot.keepDays.label}，到期才彻底消失",
                    iconKey = "recycle",
                )

                else -> {
                    RecycleSummaryCard(state.snapshot)
                    state.snapshot.groups.forEach { group ->
                        RecycleGroupBlock(
                            group = group,
                            expanded = group.day in state.expanded,
                            onToggle = { viewModel.toggleExpanded(group.day) },
                            selecting = state.selecting,
                            selected = state.selected,
                            onSelect = viewModel::toggleSelected,
                            onRestoreOne = viewModel::restoreOne,
                            onRestoreGroup = viewModel::restoreGroup,
                            onSelectGroup = viewModel::selectGroup,
                        )
                    }
                    Spacer(Modifier.height(24.dp))
                }
            }
        }

        if (state.selecting) {
            RecycleSelectionBar(
                count = state.selectedCount,
                onRestore = viewModel::restoreSelected,
                onPurge = viewModel::requestPurge,
                onClear = viewModel::clearSelection,
            )
        }
    }

    state.purgePreview?.let { preview ->
        PurgeConfirm(
            preview = preview,
            onConfirm = viewModel::confirmPurge,
            onDismiss = viewModel::dismissPurge,
        )
    }

    if (state.showKeepDays) {
        KeepDaysSheet(
            current = state.snapshot.keepDays,
            onSelect = viewModel::setKeepDays,
            onDismiss = viewModel::dismissKeepDays,
        )
    }
}

/** 顶部汇总。笔数与合计按全库算，所以列表被截断时这一行仍然不撒谎。 */
@Composable
private fun RecycleSummaryCard(snapshot: RecycleSnapshot) {
    val colors = tallyColors
    TallyCard(contentPadding = 11.dp) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(colors.slate),
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "${snapshot.totalBills} 笔 · 合计 ¥${formatAmount(snapshot.totalMinor)}",
                        style = TallyType.listTitle,
                        color = colors.ink,
                    )
                    Text(
                        text = if (snapshot.keepDays.days == null) {
                            "${snapshot.keepDays.label} · 不会自动清理"
                        } else {
                            "保留 ${snapshot.keepDays.label} · 超期自动彻底删除"
                        },
                        style = TallyType.helper,
                        color = colors.ink3,
                    )
                }
                snapshot.lastCleanup?.let { TallyTag(text = cleanupChip(it.at), tone = TagTone.SLATE) }
            }
            if (snapshot.truncated) {
                Spacer(Modifier.height(7.dp))
                Text(
                    text = "只列出最近 ${RecycleBinRepository.LIST_LIMIT} 条，上面那行合计算的是全部",
                    style = TallyType.helper,
                    color = colors.ink3,
                )
            }
        }
    }
    Spacer(Modifier.height(12.dp))
}

@Composable
private fun RecycleGroupBlock(
    group: RecycleGroup,
    expanded: Boolean,
    onToggle: () -> Unit,
    selecting: Boolean,
    selected: Set<String>,
    onSelect: (String) -> Unit,
    onRestoreOne: (String) -> Unit,
    onRestoreGroup: (RecycleGroup) -> Unit,
    onSelectGroup: (RecycleGroup) -> Unit,
) {
    val colors = tallyColors
    val selectedHere = group.items.count { it.billId in selected }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(11.dp))
            // 点组头开合；长按把这一天整组选中，折叠着也能批量恢复。
            .combinedClickable(onClick = onToggle, onLongClick = { onSelectGroup(group) })
            .padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        InlineIcon(
            iconKey = if (expanded) "chevron_down" else "chevron",
            contentDescription = if (expanded) "收起" else "展开",
            size = 13.dp,
            tint = colors.ink3,
        )
        Spacer(Modifier.width(7.dp))
        Text(group.day.label(), style = TallyType.listTitle, color = colors.ink)
        Spacer(Modifier.width(7.dp))
        Text(
            text = "${group.items.size} 笔 · ¥${formatAmount(group.totalMinor)}",
            style = TallyType.helper,
            color = colors.ink3,
        )
        Spacer(Modifier.weight(1f))
        if (selectedHere > 0) {
            Text("已选 $selectedHere", fontSize = 10.5.sp, lineHeight = 13.1.sp, fontWeight = FontWeight.Bold, color = colors.brand)
        } else {
            Text(
                text = "全部恢复",
                fontSize = 11.sp, lineHeight = 13.8.sp,
                fontWeight = FontWeight.Bold,
                color = colors.brand,
                modifier = Modifier
                    .clip(RoundedCornerShape(7.dp))
                    .clickable { onRestoreGroup(group) }
                    .padding(horizontal = 6.dp, vertical = 3.dp),
            )
        }
    }
    AnimatedVisibility(
        visible = expanded,
        enter = expandVertically(tween(190, easing = FastOutSlowInEasing)) + fadeIn(tween(160)),
        exit = shrinkVertically(tween(160, easing = FastOutSlowInEasing)) + fadeOut(tween(120)),
    ) {
        TallyCard(contentPadding = 0.dp) {
            Column {
                group.items.forEachIndexed { index, item ->
                    RecycleRow(
                        item = item,
                        selecting = selecting,
                        checked = item.billId in selected,
                        onCheck = { onSelect(item.billId) },
                        onRestore = { onRestoreOne(item.billId) },
                    )
                    if (index != group.items.lastIndex) Hairline()
                }
            }
        }
    }
    Spacer(Modifier.height(6.dp))
}

/** 一笔回收站里的账单：左中右三段，右侧同时给金额与还剩几天。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RecycleRow(
    item: RecycleItem,
    selecting: Boolean,
    checked: Boolean,
    onCheck: () -> Unit,
    onRestore: () -> Unit,
) {
    val colors = tallyColors
    val bill = item.item.bill
    val isIncome = bill.type == "INCOME"
    val moneyColor = if (isIncome) colors.income else colors.expense
    val daysLeft = item.daysLeft
    val urgent = daysLeft != null && daysLeft <= URGENT_DAYS

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (checked) colors.brand.copy(alpha = 0.06f) else colors.paper)
            // 没进选择态时点行就是恢复这一笔；进了选择态点行改成勾选，长按始终切换勾选，
            // 所以「只想恢复一笔」不必先进模式再退出来。
            .combinedClickable(onClick = if (selecting) onCheck else onRestore, onLongClick = onCheck)
            .padding(start = 11.dp, end = 11.dp, top = 9.dp, bottom = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (checked) {
            CheckBox(checked = true)
            Spacer(Modifier.width(9.dp))
        } else {
            CategoryGlyph(
                iconKey = item.item.iconKey ?: "note",
                label = item.item.categoryPath.ifBlank { "账" },
                size = 15.dp,
                tint = colors.ink3,
            )
            Spacer(Modifier.width(9.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.item.categoryPath.ifBlank { "未分类" },
                style = TallyType.listTitle,
                color = colors.ink2,
                maxLines = 1,
            )
            Text(
                text = item.meta(),
                style = TallyType.helper,
                color = colors.ink3,
                maxLines = 1,
            )
        }
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = (if (isIncome) "+" else "−") + formatAmount(bill.amountMinor),
                style = TallyType.moneyList.copy(fontSize = 14.sp, lineHeight = 17.5.sp),
                color = moneyColor,
                fontWeight = FontWeight.Medium,
            )
            if (daysLeft != null) {
                Text(
                    text = if (daysLeft <= 0L) "今天到期" else "剩 $daysLeft 天",
                    fontSize = 10.sp, lineHeight = 12.5.sp,
                    color = if (urgent) colors.expense else colors.ink3,
                    maxLines = 1,
                )
            }
        }
        // 选中状态下不再画「恢复」：底部那条操作栏就是此刻唯一的批量出口。
        if (!selecting) {
            Spacer(Modifier.width(9.dp))
            Text(
                text = "恢复",
                fontSize = 11.5.sp, lineHeight = 14.4.sp,
                fontWeight = FontWeight.Bold,
                color = colors.brand,
            )
        }
    }
}

/** 复用首页批量底栏那一套壳，只换动作区——不再自己画一条深色列。 */
@Composable
private fun RecycleSelectionBar(
    count: Int,
    onRestore: () -> Unit,
    onPurge: () -> Unit,
    onClear: () -> Unit,
) {
    SelectionBarShell(count = count, amountMinor = null, hint = null) {
        BarAction("undo", "恢复", highlight = true, onClick = onRestore)
        BarAction("trash", "彻底删除", danger = true, onClick = onPurge)
        BarAction("close", "取消", onClick = onClear)
    }
}

/** R-02：把代价说清的确认框。图片按内容哈希计数，共用的那张不跟着删——这句话是这屏的理由。 */
@Composable
private fun PurgeConfirm(
    preview: PurgePreview,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = tallyColors
    TallyConfirmDialog(
        title = "彻底删除这 ${preview.bills} 笔？",
        message = "¥${formatAmount(preview.totalMinor)} · 删除后无法找回，" +
            "这些账单本来就不计入收支，所以本月合计不会变化。",
        confirmLabel = "彻底删除",
        destructive = true,
        onConfirm = onConfirm,
        onDismiss = onDismiss,
        content = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(11.dp))
                    .background(colors.expense.copy(alpha = 0.06f))
                    .border(1.dp, colors.expense.copy(alpha = 0.22f), RoundedCornerShape(11.dp))
                    .padding(horizontal = 11.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                InlineIcon(iconKey = "warn", contentDescription = null, size = 13.dp, tint = colors.expense)
                Text(
                    text = preview.imageLine(),
                    fontSize = 11.5.sp, lineHeight = 14.4.sp,
                    fontWeight = FontWeight.Bold,
                    color = colors.expense,
                )
            }
        },
    )
}

/** R-03：保留期。四个值都是显式选择，「永不清理」不是没设值。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun KeepDaysSheet(
    current: RecycleKeepDays,
    onSelect: (RecycleKeepDays) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = tallyColors
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = colors.paper,
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            BottomSheetGrabber(modifier = Modifier.align(Alignment.CenterHorizontally))
            Spacer(Modifier.height(12.dp))
            TallySectionLabel(text = "保留多久")
            // 这里不能再传 contentPadding = 0.dp：TallyListRow 自身不带水平内边距，
            // 卡片给 0 就等于每一行都贴到边框上。
            TallyCard {
                Column {
                    RecycleKeepDays.entries.forEachIndexed { index, days ->
                        TallyListRow(
                            title = days.label,
                            subtitle = days.explain(),
                            iconKey = if (days == current) "check" else null,
                            onClick = { onSelect(days) },
                        )
                        if (index != RecycleKeepDays.entries.lastIndex) Hairline()
                    }
                }
            }
        }
    }
}

private const val URGENT_DAYS = 3L

private val groupFormatter = DateTimeFormatter.ofPattern("M 月 d 日", Locale.CHINA)
private val shortFormatter = DateTimeFormatter.ofPattern("MM-dd", Locale.CHINA)

/** 「今天 10-14」/「昨天 10-13」/「10 月 11 日」——跨年时补上年份，否则两组会同名。 */
private fun LocalDate.label(): String {
    val today = LocalDate.now()
    val short = format(shortFormatter)
    return when (this) {
        today -> "今天 $short"
        today.minusDays(1) -> "昨天 $short"
        else -> if (year == today.year) format(groupFormatter) else "${year} 年 $short"
    }
}

private fun RecycleItem.meta(): String {
    val note = item.bill.note?.takeIf { it.isNotBlank() }
    val tail = if (item.attachmentCount > 0) "有 ${item.attachmentCount} 张图片" else item.ledgerName
    return listOfNotNull(note, deletedAt.toLocalDate().format(shortFormatter), tail).joinToString(" · ")
}

private fun Long.toLocalDate(): LocalDate =
    Instant.ofEpochMilli(this).atZone(ZoneId.systemDefault()).toLocalDate()

private fun cleanupChip(at: Long): String {
    val day = at.toLocalDate()
    val prefix = when (day) {
        LocalDate.now() -> "今天"
        LocalDate.now().minusDays(1) -> "昨天"
        else -> day.format(shortFormatter)
    }
    return "$prefix 清理过"
}

private fun PurgePreview.imageLine(): String = when {
    images == 0 -> "这几笔没有挂图片"
    sharedImages == 0 -> "连带回收 $images 张图片"
    else -> "连带回收 $images 张图片（其中 $sharedImages 张与别的账单共用，保留）"
}

private fun RecycleKeepDays.explain(): String = when (this) {
    RecycleKeepDays.WEEK -> "一周后彻底删除"
    RecycleKeepDays.MONTH -> "默认值 · 一个月后彻底删除"
    RecycleKeepDays.QUARTER -> "一个季度后彻底删除"
    RecycleKeepDays.NEVER -> "一直留着，图片文件也不会被回收"
}
