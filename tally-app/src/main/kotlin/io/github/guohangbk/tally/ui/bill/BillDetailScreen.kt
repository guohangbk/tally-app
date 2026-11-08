package io.github.guohangbk.tally.ui.bill

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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.guohangbk.tally.di.rememberAppGraph
import io.github.guohangbk.tally.domain.formatAmount
import io.github.guohangbk.tally.domain.model.BillListItem
import io.github.guohangbk.tally.domain.model.BillType
import io.github.guohangbk.tally.domain.model.ReimburseStatus
import io.github.guohangbk.tally.domain.model.TimePrecision
import io.github.guohangbk.tally.ui.common.BillBatchViewModel
import io.github.guohangbk.tally.ui.common.formatDate
import io.github.guohangbk.tally.ui.common.formatDateTime
import io.github.guohangbk.tally.ui.common.tallyViewModel
import io.github.guohangbk.tally.ui.design.BillBadges
import io.github.guohangbk.tally.ui.design.ButtonVariant
import io.github.guohangbk.tally.ui.design.CategoryGlyph
import io.github.guohangbk.tally.ui.design.FullScreenImageViewer
import io.github.guohangbk.tally.ui.design.ImageThumbnail
import io.github.guohangbk.tally.ui.design.InlineIcon
import io.github.guohangbk.tally.ui.design.TallyBottomSheet
import io.github.guohangbk.tally.ui.design.TallyButton
import io.github.guohangbk.tally.ui.design.TallyConfirmDialog
import io.github.guohangbk.tally.ui.design.TallyTag
import io.github.guohangbk.tally.ui.design.TallyToast
import io.github.guohangbk.tally.ui.design.TallyType
import io.github.guohangbk.tally.ui.design.TagTone
import io.github.guohangbk.tally.ui.design.ToastTone
import io.github.guohangbk.tally.ui.design.rememberTallyHaptics
import io.github.guohangbk.tally.ui.design.sheetBody
import io.github.guohangbk.tally.ui.design.tallyColors

/**
 * 点账单行不再推进入页：信息从底部浮上来，看完随手关掉。
 * 要改内容才进记账页——弹层里不长出第二套编辑器，分类组件也不再重复一遍。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BillDetailSheet(
    billId: String,
    onDismiss: () -> Unit,
    onEdit: (String) -> Unit,
    onRepeat: (l1Id: String?, l2Id: String?, type: BillType?) -> Unit = { _, _, _ -> },
    /** 退款与原单互跳：换一个弹层继续看，不退出到列表。 */
    onOpenBill: (String) -> Unit = {},
) {
    val graph = rememberAppGraph()
    val viewModel = tallyViewModel(key = "bill-sheet-$billId") { BillDetailViewModel(graph, billId) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val colors = tallyColors
    var confirmDelete by remember { mutableStateOf(false) }
    var viewerStart by remember { mutableStateOf<Int?>(null) }

    // ViewModel 按 billId 缓存，改完账单再点开必须重新取一次，否则弹窗里还是改之前那份。
    // 删完就收弹层：白底压着列表好几行，比那笔账单本身更碍事。不再给「撤销」——
    // 找回走回收站，那里能单条恢复也能整组恢复，不必靠一条长气泡兜命。
    // 「已删除」走事件而不是状态：状态会留在缓存的 ViewModel 上，回收站恢复后再点开，
    // 弹层还没读到新数据就先被上一次的删除结果关掉了。
    LaunchedEffect(billId) {
        viewModel.load()
        viewModel.deletedEvents.collect {
            TallyToast.show(text = "已删除，可在回收站找回", tone = ToastTone.INFO)
            onDismiss()
        }
    }
    LaunchedEffect(state.error) {
        state.error?.let { failure ->
            TallyToast.show(failure, ToastTone.ERROR)
            viewModel.clearError()
        }
    }
    LaunchedEffect(state.refundNotice) {
        state.refundNotice?.let { notice ->
            TallyToast.show(notice, ToastTone.INFO)
            viewModel.clearRefundNotice()
        }
    }

    TallyBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = colors.paper,
    ) {
        val item = state.item
        Column(modifier = Modifier.fillMaxWidth().sheetBody().padding(horizontal = 15.dp).padding(bottom = 18.dp)) {
            when {
                item == null && state.loaded -> Text("这笔账单已经不在了。", style = TallyType.helper, color = colors.ink3)
                item == null -> Text("正在读取…", style = TallyType.helper, color = colors.ink3)
                else -> {
                    SheetHead(
                        item = item,
                        imagePaths = state.imagePaths,
                        onOpenImage = { viewerStart = it },
                    )
                    SheetNote(item.bill.note)
                    RefundBlock(state = state, onOpenBill = onOpenBill)
                    Spacer(Modifier.height(14.dp))
                    val isIncome = item.bill.type == BillType.INCOME.name
                    // 退款入口只给未退过的支出原单：已退满的不再出现，省掉一次注定失败的提交。
                    val canRefund = !isIncome && !item.bill.isRefund && item.bill.refundOfBillId == null &&
                        state.refundableMinor > 0L
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedAction(icon = "repeat", label = "再记一笔") {
                            onRepeat(
                                item.bill.categoryL1Id,
                                item.bill.categoryL2Id,
                                if (isIncome) BillType.INCOME else BillType.EXPENSE,
                            )
                        }
                        if (canRefund) {
                            OutlinedAction(icon = "refund", label = "退款") { viewModel.openRefund() }
                        }
                        OutlinedAction(icon = "trash", label = "删除", danger = true) { confirmDelete = true }
                        Spacer(Modifier.weight(1f))
                        TallyButton(
                            label = "修改",
                            onClick = { onEdit(billId) },
                            variant = ButtonVariant.BRAND,
                            leadingIcon = "pencil",
                            modifier = Modifier.widthIn(min = 104.dp, max = 128.dp),
                        )
                    }
                }
            }

            state.item?.let { bill ->
                if (confirmDelete) {
                    DeleteConfirm(
                        item = bill,
                        refundCount = state.refunds.size,
                        onDismiss = { confirmDelete = false },
                        onConfirm = {
                            confirmDelete = false
                            viewModel.delete()
                        },
                    )
                }
            }
        }
    }

    // 大图层挂在弹层之外：ModalBottomSheet 的 content 里再嵌全屏 Dialog 会被压回半屏。
    viewerStart?.let { start ->
        FullScreenImageViewer(
            relativePaths = state.imagePaths,
            startIndex = start,
            onDismiss = { viewerStart = null },
        )
    }

    // 退款弹层同样挂在外面：嵌进 content 会被外层 sheet 的拖拽手势抢走。
    if (state.refundSheet) {
        state.item?.let { origin ->
            BillRefundInputSheet(
                origin = origin,
                remainingMinor = state.refundableMinor,
                amountText = state.refundAmountText,
                refundAt = state.refundAt,
                error = state.refundError,
                onAmountChange = viewModel::setRefundAmount,
                onDateChange = viewModel::setRefundDate,
                onDismiss = viewModel::closeRefund,
                onConfirm = viewModel::confirmRefund,
            )
        }
    }
}

@Composable
private fun SheetHead(
    item: BillListItem,
    imagePaths: List<String>,
    onOpenImage: (Int) -> Unit,
) {
    val colors = tallyColors
    val bill = item.bill
    val isIncome = bill.type == BillType.INCOME.name
    // 退款是钱回来，按正数方向显示；它不进收入合计，由退款角标说明。
    val isRefund = bill.isRefund || bill.refundOfBillId != null
    val positive = isIncome || isRefund
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(11.dp))
                    .background(colors.brandSurface),
                contentAlignment = Alignment.Center,
            ) {
                CategoryGlyph(iconKey = item.iconKey ?: "note", label = item.categoryPath, size = 17.dp, tint = colors.brand)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(item.categoryPath.ifBlank { "未分类" }, style = TallyType.listTitle, color = colors.ink)
                Text(
                    text = "${if (bill.timePrecision == TimePrecision.DATETIME.name) formatDateTime(bill.occurredAt) else formatDate(bill.occurredAt)} · ${item.ledgerName}",
                    style = TallyType.helper,
                    color = colors.ink3,
                )
            }
            Text(
                text = (if (positive) "+" else "−") + "¥" + formatAmount(bill.amountMinor),
                style = TallyType.moneyDisplay.copy(fontSize = 24.sp, lineHeight = 29.sp),
                color = if (positive) colors.income else colors.expense,
                fontWeight = FontWeight.Medium,
            )
        }
        Spacer(Modifier.height(9.dp))
        // 图片缩略图与「支出」标签同处一行，靠右落在金额正下方；点开进全屏大图。
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                BillBadges(item = item)
                TallyTag(text = if (isIncome) "收入" else "支出", tone = if (isIncome) TagTone.BRAND else TagTone.NEUTRAL)
                if (bill.reimbursed == ReimburseStatus.DONE.name) {
                    TallyTag(text = "已报销", tone = TagTone.GOLD)
                }
            }
            if (imagePaths.isNotEmpty()) {
                Spacer(Modifier.weight(1f))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    imagePaths.take(3).forEachIndexed { index, path ->
                        ImageThumbnail(relativePath = path, size = 40, onClick = { onOpenImage(index) })
                    }
                    if (imagePaths.size > 3) {
                        Text("+${imagePaths.size - 3}", style = TallyType.helper, color = colors.ink3)
                    }
                }
            }
        }
        // 账目时间在上头跟着分类走，记账时间单独一行：补记昨天的账时这两个不是一回事。
        Spacer(Modifier.height(8.dp))
        Text("记录于 ${formatDateTime(bill.createdAt)}", style = TallyType.helper, color = colors.ink3)
    }
}

/**
 * 退款信息块：退款行写「退自哪一笔」，原单写「已退多少」并列出明细。
 * 两边都能跳到对账单，退款与原始消费在同一个弹层链路里看全。
 */
@Composable
private fun RefundBlock(
    state: BillDetailUiState,
    onOpenBill: (String) -> Unit,
) {
    val colors = tallyColors
    val item = state.item ?: return
    val bill = item.bill
    val isRefund = bill.isRefund || bill.refundOfBillId != null
    if (isRefund) {
        val origin = state.origin
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(colors.brandSurface)
                .border(1.dp, colors.brand.copy(alpha = 0.22f), RoundedCornerShape(12.dp))
                .then(
                    if (origin != null) Modifier.clickable { onOpenBill(origin.bill.id) } else Modifier,
                )
                .padding(horizontal = 11.dp, vertical = 9.dp),
        ) {
            Text("退自", style = TallyType.helper, color = colors.brand)
            Spacer(Modifier.height(3.dp))
            if (origin != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = origin.categoryPath.ifBlank { "未分类" } + " · " +
                            formatDate(origin.bill.occurredAt) + " · ¥" + formatAmount(origin.bill.amountMinor),
                        style = TallyType.listTitle,
                        color = colors.ink,
                        modifier = Modifier.weight(1f),
                    )
                    InlineIcon(iconKey = "chevron", contentDescription = "查看原单", size = 12.dp, tint = colors.ink3)
                }
            } else {
                Text(
                    text = "未关联原单：这笔退款不冲抵任何分类的支出",
                    style = TallyType.helper,
                    color = colors.ink3,
                )
            }
        }
        return
    }
    if (state.refunds.isEmpty()) return
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(colors.card)
            .border(1.dp, colors.line, RoundedCornerShape(12.dp))
            .padding(horizontal = 11.dp, vertical = 9.dp),
    ) {
        Text(
            text = "已退 ¥${formatAmount(state.refundedMinor)} / ¥${formatAmount(bill.amountMinor)}",
            style = TallyType.helper,
            color = colors.ink3,
        )
        Spacer(Modifier.height(4.dp))
        state.refunds.forEachIndexed { index, refund ->
            if (index > 0) {
                Spacer(Modifier.height(4.dp))
                Box(Modifier.fillMaxWidth().height(1.dp).background(colors.line))
                Spacer(Modifier.height(4.dp))
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onOpenBill(refund.id) }
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = formatDate(refund.occurredAt) + (refund.note?.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()),
                    style = TallyType.helper,
                    color = colors.ink2,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                )
                Text(
                    text = "+¥" + formatAmount(refund.amountMinor),
                    style = TallyType.moneyList.copy(fontSize = 13.sp, lineHeight = 16.3.sp),
                    color = colors.income,
                )
                InlineIcon(iconKey = "chevron", contentDescription = "查看退款", size = 12.dp, tint = colors.ink3)
            }
        }
    }
}

/** 备注整段摊开显示：长内容不折叠、不加「展开」。 */
@Composable
private fun SheetNote(note: String?) {    val colors = tallyColors
    if (note.isNullOrBlank()) return
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(colors.card)
            .border(1.dp, colors.line, RoundedCornerShape(12.dp))
            .padding(horizontal = 11.dp, vertical = 9.dp),
    ) {
        Text("备注", style = TallyType.helper, color = colors.ink3)
        Spacer(Modifier.height(3.dp))
        Text(note, style = TallyType.listTitle, color = colors.ink)
    }
}

@Composable
private fun DeleteConfirm(
    item: BillListItem,
    refundCount: Int,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val colors = tallyColors
    val bill = item.bill
    val isRefund = bill.isRefund || bill.refundOfBillId != null
    val delta = when {
        isRefund -> if (bill.refundOfBillId != null) "删除后不再冲抵原单分类的支出。" else "它本来就不参与收支汇总。"
        bill.excludedFromStats -> "它不计入收支，汇总不变。"
        bill.type == BillType.INCOME.name -> "本月收入将减少 ${formatAmount(bill.amountMinor)}。"
        refundCount > 0 -> "本月支出将减少 ${formatAmount(bill.amountMinor)}；关联的 $refundCount 笔退款会失去原单，不再冲抵支出。"
        else -> "本月支出将减少 ${formatAmount(bill.amountMinor)}。"
    }
    TallyConfirmDialog(
        title = "删除这笔账单？",
        message = "¥${formatAmount(bill.amountMinor)} · ${item.categoryPath.ifBlank { "未分类" }} · ${formatDateTime(bill.occurredAt)}\n$delta",
        confirmLabel = "删除",
        destructive = true,
        onConfirm = onConfirm,
        onDismiss = onDismiss,
    )
}

/**
 * 动作行左边的三枚描边小胶囊：30dp 高、9dp 圆角、12.5dp 图标 + 11.5sp 中字重标签，
 * 只包一圈表示「点得动」，矮行尾那枚实心按钮一档，四块才不会读成平级。
 * 删除转陶土，与卡上其它破坏动作同色。
 */
@Composable
private fun OutlinedAction(
    icon: String,
    label: String,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    val colors = tallyColors
    val haptics = rememberTallyHaptics()
    val background = if (danger) colors.expense.copy(alpha = 0.07f) else colors.card
    val borderColor = if (danger) colors.expense.copy(alpha = 0.28f) else colors.line
    val contentColor = if (danger) colors.expense else colors.ink
    val shape = RoundedCornerShape(9.dp)
    Row(
        modifier = Modifier
            .height(30.dp)
            .clip(shape)
            .background(background)
            .border(1.dp, borderColor, shape)
            .clickable {
                haptics.tap()
                onClick()
            }
            .padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        InlineIcon(iconKey = icon, contentDescription = label, size = 12.5.dp, tint = contentColor)
        Spacer(Modifier.width(4.dp))
        Text(
            label,
            fontSize = 11.5.sp,
            lineHeight = 15.sp,
            fontWeight = FontWeight.Medium,
            color = contentColor,
            maxLines = 1,
        )
    }
}
