package io.github.guohangbk.tally.ui.reimbursement

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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.guohangbk.tally.di.rememberAppGraph
import io.github.guohangbk.tally.domain.model.BillType
import io.github.guohangbk.tally.ui.bill.BillDetailSheet
import io.github.guohangbk.tally.domain.formatAmount
import io.github.guohangbk.tally.domain.model.BillListItem
import io.github.guohangbk.tally.ui.common.CheckBox
import io.github.guohangbk.tally.ui.common.LedgerPickerSheet
import io.github.guohangbk.tally.ui.common.formatDay
import io.github.guohangbk.tally.ui.common.startOfDay
import io.github.guohangbk.tally.ui.common.tallyViewModel
import io.github.guohangbk.tally.ui.design.BillBadges
import io.github.guohangbk.tally.ui.design.ButtonVariant
import io.github.guohangbk.tally.ui.design.CategoryGlyph
import io.github.guohangbk.tally.ui.design.InlineIcon
import io.github.guohangbk.tally.ui.design.TallyBottomSheet
import io.github.guohangbk.tally.ui.design.TallyButton
import io.github.guohangbk.tally.ui.design.TallyCard
import io.github.guohangbk.tally.ui.design.TallyEmptyState
import io.github.guohangbk.tally.ui.design.TallySegmented
import io.github.guohangbk.tally.ui.design.TallyTag
import io.github.guohangbk.tally.ui.design.TallyType
import io.github.guohangbk.tally.ui.design.TallyToastEffects
import io.github.guohangbk.tally.ui.design.TagTone
import io.github.guohangbk.tally.ui.design.tallyColors
import io.github.guohangbk.tally.ui.design.rememberTallyHaptics
import io.github.guohangbk.tally.ui.shell.TallyTopBar

/**
 * P14 报销管理（纯状态流）：只看支出，收入与被排除的账单不出现在这一页。
 * 顶部三个数就是口径本身，不另加一句解释。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReimbursementScreen(
    onBack: () -> Unit,
    onOpenBillEdit: (String) -> Unit = {},
    onRepeatBill: (String?, String?, BillType?) -> Unit = { _, _, _ -> },
) {
    val graph = rememberAppGraph()
    val viewModel = tallyViewModel { ReimbursementViewModel(graph) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val colors = tallyColors
    val snapshot = state.snapshot
    val pending = snapshot.pending
    val done = snapshot.done
    var showLedgerSheet by remember { mutableStateOf(false) }
    var detailBillId by remember { mutableStateOf<String?>(null) }

    Column(modifier = Modifier.fillMaxSize().background(colors.paper)) {
        TallyTopBar(
            title = "报销管理",
            onBack = onBack,
            trailing = {
                TallyTag(
                    text = "${state.ledgers.firstOrNull { it.ledger.id == state.ledgerId }?.ledger?.name ?: "全部账本"} ▾",
                    tone = TagTone.OUTLINE,
                    modifier = Modifier.clip(RoundedCornerShape(17.dp)).clickable { showLedgerSheet = true },
                )
            },
        )

        // 提示改成贴底气泡，保留「操作没有完成」这个定性说法；两条通道都由 clearNotice 清。
        TallyToastEffects(
            notice = state.notice,
            error = state.error,
            errorTitle = "操作没有完成",
            onNoticeShown = viewModel::clearNotice,
            onErrorShown = viewModel::clearNotice,
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 15.dp),
        ) {
            Spacer(Modifier.height(6.dp))

            if (!state.enabled) {
                TallyCard(corner = 14, borderColor = colors.gold.copy(alpha = 0.32f), contentPadding = 12.dp) {
                    Text("设置 · 记账偏好里的报销开关未开启。", style = TallyType.helper, color = colors.ink2)
                }
                Spacer(Modifier.height(8.dp))
            }

            TallySegmented(
                options = listOf(
                    ReimburseTab.PENDING to "未报销 ${pending.size}",
                    ReimburseTab.DONE to "已报销 ${done.size}",
                ),
                selected = state.tab,
                onSelect = viewModel::selectTab,
            )
            Spacer(Modifier.height(10.dp))

            SummaryCard(
                pendingCount = pending.size,
                pendingMinor = state.pendingMinor,
                doneMinor = state.doneMinor,
                grouping = true,
            )

            Spacer(Modifier.height(14.dp))
            when (state.tab) {
                ReimburseTab.PENDING -> PendingList(
                    items = pending,
                    selected = state.selected,
                    grouping = true,
                    selectionMode = state.selectionMode,
                    onToggle = viewModel::toggle,
                    onSelectAll = viewModel::selectAllPending,
                    onEnterSelection = viewModel::enterSelection,
                    onConfirmOne = viewModel::confirmOne,
                    onOpenBill = { id -> detailBillId = id },
                )

                ReimburseTab.DONE -> DoneList(
                    items = done,
                    grouping = true,
                    onUndo = viewModel::undo,
                    onOpenBill = { id -> detailBillId = id },
                )
            }
            Spacer(Modifier.height(20.dp))
        }

        if (state.tab == ReimburseTab.PENDING && state.selectionMode) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.ink)
                    .padding(horizontal = 13.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "已选 ${state.selected.size} 笔 · ${formatAmount(state.selectedMinor)}",
                        fontSize = 12.5.sp,
                        lineHeight = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = colors.paper,
                    )
                    Text(
                        text = "确认后这 ${state.selected.size} 笔不再计入本月支出",
                        fontSize = 10.5.sp,
                        lineHeight = 14.sp,
                        color = colors.paper.copy(alpha = 0.6f),
                    )
                }
                Text(
                    text = "取消",
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    color = colors.paper.copy(alpha = 0.7f),
                    modifier = Modifier.clickable(onClick = viewModel::exitSelection),
                )
                TallyButton(
                    label = "确认报销",
                    onClick = viewModel::openConfirm,
                    variant = ButtonVariant.GOLD,
                    enabled = state.selected.isNotEmpty(),
                    height = 34,
                    modifier = Modifier.width(104.dp),
                )
            }
        }

        if (state.showConfirm) {
            TallyBottomSheet(
                onDismissRequest = viewModel::closeConfirm,
                containerColor = colors.paper,
            ) {
                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 20.dp)) {
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("确认报销 ${state.selected.size} 笔", style = TallyType.sectionTitle, color = colors.ink, modifier = Modifier.weight(1f))
                        TallyTag(text = "¥${formatAmount(state.selectedMinor)}", tone = TagTone.GOLD)
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(colors.card)
                            .border(1.dp, colors.line, RoundedCornerShape(10.dp))
                            .padding(horizontal = 9.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        InlineIcon(iconKey = "calendar", contentDescription = null, size = 13.dp, tint = colors.ink3)
                        Spacer(Modifier.width(6.dp))
                        Text("报销日期", fontSize = 12.sp, lineHeight = 15.sp, color = colors.ink2, modifier = Modifier.weight(1f))
                        Text(formatDay(System.currentTimeMillis()), fontSize = 12.sp, lineHeight = 15.sp, fontWeight = FontWeight.SemiBold, color = colors.ink)
                        Spacer(Modifier.width(6.dp))
                        TallyTag(text = "今天", tone = TagTone.OUTLINE)
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(11.dp))
                            .background(colors.slate.copy(alpha = 0.07f))
                            .border(1.dp, colors.slate.copy(alpha = 0.26f), RoundedCornerShape(11.dp))
                            .padding(horizontal = 11.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        InlineIcon(iconKey = "info", contentDescription = null, size = 13.dp, tint = colors.slate)
                        Text(
                            text = "确认后这 ${state.selected.size} 笔转为已报销并记录日期，本月支出会减去这 ${state.selected.size} 笔。",
                            fontSize = 11.5.sp,
                            lineHeight = 16.sp,
                            color = colors.ink2,
                        )
                    }
                    Spacer(Modifier.height(14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TallyButton(
                            label = "取消",
                            onClick = viewModel::closeConfirm,
                            variant = ButtonVariant.GHOST,
                            height = 38,
                            modifier = Modifier.weight(1f),
                        )
                        TallyButton(
                            label = "确认报销",
                            onClick = viewModel::confirmReimburse,
                            variant = ButtonVariant.BRAND,
                            height = 38,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }

    if (showLedgerSheet) {
        LedgerPickerSheet(
            ledgers = state.ledgers,
            selectedId = state.ledgerId,
            onSelect = { id ->
                viewModel.selectLedger(id.ifEmpty { null })
                showLedgerSheet = false
            },
            onDismiss = { showLedgerSheet = false },
            includeAll = true,
        )
    }

    detailBillId?.let { id ->
        BillDetailSheet(
            billId = id,
            onDismiss = { detailBillId = null },
            onEdit = { editId ->
                detailBillId = null
                onOpenBillEdit(editId)
            },
            onRepeat = { l1, l2, type ->
                detailBillId = null
                onRepeatBill(l1, l2, type)
            },
            onOpenBill = { detailBillId = it },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PendingList(
    items: List<BillListItem>,
    selected: List<BillListItem>,
    grouping: Boolean,
    selectionMode: Boolean,
    onToggle: (BillListItem) -> Unit,
    onSelectAll: () -> Unit,
    onEnterSelection: (BillListItem) -> Unit,
    onConfirmOne: (BillListItem) -> Unit,
    onOpenBill: (String) -> Unit,
) {
    val colors = tallyColors
    val haptics = rememberTallyHaptics()
    if (items.isEmpty()) {
        TallyEmptyState(
            title = "没有待报销的账单",
            description = "在记账页把支出标记为「报销」，它就会出现在这里。",
            iconKey = "receipt",
        )
        return
    }
    val selectedIds = selected.map { it.bill.id }.toSet()
    Row(modifier = Modifier.fillMaxWidth().padding(bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("${items.size} 笔待处理", fontSize = 11.5.sp, lineHeight = 15.sp, color = colors.ink3, modifier = Modifier.weight(1f))
        Text(
            text = "全选未报销",
            fontSize = 11.5.sp,
            lineHeight = 15.sp,
            color = colors.brand,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.clickable(onClick = onSelectAll),
        )
    }
    items.forEachIndexed { index, item ->
        if (index > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(colors.ink.copy(alpha = 0.045f)))
        val isSelected = selectedIds.contains(item.bill.id)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(if (isSelected) colors.brand.copy(alpha = 0.06f) else Color.Transparent)
                .combinedClickable(
                    onClick = {
                        haptics.tap()
                        // 选择态里点整行是勾选，不是点开账单——勾选框与行底是同一次点击，不会跑到上一页去。
                        if (selectionMode) onToggle(item) else onOpenBill(item.bill.id)
                    },
                    onLongClick = {
                        haptics.longPress()
                        onEnterSelection(item)
                    },
                )
                .padding(vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selectionMode) {
                CheckBox(checked = isSelected)
                Spacer(Modifier.width(9.dp))
            }
            Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                ReimbIcon(item)
                Spacer(Modifier.width(9.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.categoryPath.ifBlank { "未分类" },
                        fontSize = 13.5.sp,
                        lineHeight = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.ink,
                        maxLines = 1,
                    )
                    Spacer(Modifier.height(2.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                        BillBadges(item = item)
                        Text(
                            text = formatDay(item.bill.occurredAt),
                            fontSize = 11.sp,
                            lineHeight = 14.sp,
                            color = colors.ink3,
                        )
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = "−" + formatAmount(item.bill.amountMinor, grouping),
                style = TallyType.moneyList.copy(fontSize = 17.5.sp, lineHeight = 20.sp),
                color = colors.expense,
            )
            if (selectionMode) {
                Spacer(Modifier.width(8.dp))
                TallyTag(
                    text = "确认",
                    tone = TagTone.GOLD,
                    modifier = Modifier.clickable { onConfirmOne(item) },
                )
            }
        }
    }
}

/** 已报销按报销日期分组——报销是按批次发生的，这样才对得上报销单（T7.2）。 */
@Composable
private fun DoneList(
    items: List<BillListItem>,
    grouping: Boolean,
    onUndo: (BillListItem) -> Unit,
    onOpenBill: (String) -> Unit,
) {
    val colors = tallyColors
    if (items.isEmpty()) {
        TallyEmptyState(title = "还没有已报销记录", description = "在未报销页勾选后一次确认。", iconKey = "check")
        return
    }
    val grouped = items
        .groupBy { startOfDay(it.bill.reimbursedAt ?: it.bill.occurredAt) }
        .toSortedMap(compareByDescending { it })
    grouped.forEach { (day, bills) ->
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Text(
                text = "报销于 ${formatDay(day)}",
                fontSize = 12.sp,
                lineHeight = 16.sp,
                fontWeight = FontWeight.Bold,
                color = colors.ink2,
                maxLines = 1,
            )
            Box(Modifier.weight(1f).height(1.dp).background(colors.ink.copy(alpha = 0.055f)))
            Text(
                text = "${bills.size} 笔 · ${formatAmount(bills.sumOf { it.bill.amountMinor }, grouping)}",
                style = TallyType.helper.copy(fontSize = 11.5.sp, lineHeight = 14.sp),
                color = colors.ink3,
            )
        }
        bills.forEachIndexed { index, item ->
            if (index > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(colors.ink.copy(alpha = 0.045f)))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpenBill(item.bill.id) }
                    .padding(vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ReimbIcon(item)
                Spacer(Modifier.width(9.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.categoryPath.ifBlank { "未分类" },
                        fontSize = 13.5.sp,
                        lineHeight = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.ink,
                        maxLines = 1,
                    )
                    Spacer(Modifier.height(2.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                        BillBadges(item = item)
                        Text(
                            text = "${formatDay(item.bill.occurredAt)} 记",
                            fontSize = 11.sp,
                            lineHeight = 14.sp,
                            color = colors.ink3,
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    text = formatAmount(item.bill.amountMinor, grouping),
                    style = TallyType.moneyList.copy(fontSize = 17.5.sp, lineHeight = 20.sp),
                    color = colors.expense,
                )
                Spacer(Modifier.width(8.dp))
                TallyTag(
                    text = "撤销",
                    tone = TagTone.OUTLINE,
                    modifier = Modifier.clickable { onUndo(item) },
                )
            }
        }
    }
    Spacer(Modifier.height(10.dp))
    Text("撤销报销回到未报销，原报销日期仍留档。", fontSize = 11.sp, lineHeight = 14.sp, color = colors.ink3)
}

@Composable
private fun ReimbIcon(item: BillListItem) {
    val colors = tallyColors
    val shape = RoundedCornerShape(11.dp)
    Box(
        modifier = Modifier
            .size(30.dp)
            .clip(shape)
            .background(colors.card)
            .border(1.dp, colors.line, shape),
        contentAlignment = Alignment.Center,
    ) {
        CategoryGlyph(
            iconKey = item.iconKey ?: "receipt",
            label = item.categoryL1Name ?: "账",
            size = 15.dp,
            tint = colors.ink,
        )
    }
}

@Composable
private fun SummaryCard(
    pendingCount: Int,
    pendingMinor: Long,
    doneMinor: Long,
    grouping: Boolean,
) {
    val colors = tallyColors
    TallyCard(corner = 16, contentPadding = 10.dp) {
        Row(modifier = Modifier.fillMaxWidth()) {
            SummaryCell(
                amount = formatAmount(pendingMinor, grouping),
                label = "待报销合计",
                color = colors.expense,
                modifier = Modifier.weight(1f),
            )
            Box(modifier = Modifier.width(1.dp).height(28.dp).background(colors.line))
            SummaryCell(
                amount = "$pendingCount",
                label = "笔数",
                color = colors.ink,
                modifier = Modifier.weight(1f).padding(start = 10.dp),
            )
            Box(modifier = Modifier.width(1.dp).height(28.dp).background(colors.line))
            SummaryCell(
                amount = formatAmount(doneMinor, grouping),
                label = "已报销累计",
                color = colors.brand,
                modifier = Modifier.weight(1f).padding(start = 10.dp),
            )
        }
    }
}

/** 数字与小字各自把行高收住：只写 `fontSize` 会继承默认行高，同一份卡片上下会不齐。 */
@Composable
private fun SummaryCell(amount: String, label: String, color: Color, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(
            text = amount,
            style = TallyType.moneyBody.copy(fontSize = 18.sp, lineHeight = 20.sp),
            color = color,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(3.dp))
        Text(label, fontSize = 10.5.sp, lineHeight = 14.sp, color = tallyColors.ink3)
    }
}
