package io.github.guohangbk.tally.ui.bill

import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.guohangbk.tally.di.rememberAppGraph
import io.github.guohangbk.tally.domain.formatAmount
import io.github.guohangbk.tally.domain.model.BillListItem
import io.github.guohangbk.tally.domain.model.BillType
import io.github.guohangbk.tally.domain.model.TimePrecision
import io.github.guohangbk.tally.ui.common.BillAmountTone
import io.github.guohangbk.tally.ui.common.TagPill
import io.github.guohangbk.tally.ui.common.billAmountView
import io.github.guohangbk.tally.ui.common.formatDate
import io.github.guohangbk.tally.ui.common.formatDateTime
import io.github.guohangbk.tally.ui.common.tallyViewModel
import io.github.guohangbk.tally.ui.design.BillBadge
import io.github.guohangbk.tally.ui.design.CategoryGlyph
import io.github.guohangbk.tally.ui.design.FullScreenImageViewer
import io.github.guohangbk.tally.ui.design.ImageThumbnail
import io.github.guohangbk.tally.ui.design.InlineIcon
import io.github.guohangbk.tally.ui.design.Marker
import io.github.guohangbk.tally.ui.design.TallyBottomSheet
import io.github.guohangbk.tally.ui.design.TallyConfirmDialog
import io.github.guohangbk.tally.ui.design.TallyToast
import io.github.guohangbk.tally.ui.design.TallyType
import io.github.guohangbk.tally.ui.design.ToastTone
import io.github.guohangbk.tally.ui.design.billBadges
import io.github.guohangbk.tally.ui.design.rememberTallyHaptics
import io.github.guohangbk.tally.ui.design.tallyColors

/**
 * 点账单行不再推进入页：信息从底部浮上来，看完随手关掉。
 * 要改内容才进记账页——弹层里不长出第二套编辑器，分类组件也不再重复一遍。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun BillDetailSheet(
    billId: String,
    onDismiss: () -> Unit,
    onEdit: (String) -> Unit,
    onRepeat: (l1Id: String?, l2Id: String?, type: BillType?) -> Unit = { _, _, _ -> },
    /** 退款与原单互跳：换一个弹层继续看，不退出到列表。 */
    onOpenBill: (String) -> Unit = {},
    /** 点标签先收起弹层，再去标签统计页。 */
    onOpenTag: ((String) -> Unit)? = null,
) {
    val graph = rememberAppGraph()
    val viewModel = tallyViewModel(key = "bill-sheet-$billId") { BillDetailViewModel(graph, billId) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val colors = tallyColors
    val context = LocalContext.current
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
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 520.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(top = 2.dp, bottom = 20.dp),
        ) {
            val item = state.item
            when {
                item == null && state.loaded -> Text("这笔账单已经不在了。", style = TallyType.helper, color = colors.ink3)
                item == null -> Text("正在读取…", style = TallyType.helper, color = colors.ink3)
                else -> {
                    val bill = item.bill
                    val isIncome = bill.type == BillType.INCOME.name
                    val canRefund = !isIncome && !bill.isRefund && bill.refundOfBillId == null &&
                        state.refundableMinor > 0L

                    DetailHeader(
                        canRefund = canRefund,
                        onRepeat = {
                            onRepeat(
                                bill.categoryL1Id,
                                bill.categoryL2Id,
                                if (isIncome) BillType.INCOME else BillType.EXPENSE,
                            )
                        },
                        onRefund = viewModel::openRefund,
                        onEdit = { onEdit(billId) },
                        onDelete = { confirmDelete = true },
                    )

                    Spacer(Modifier.height(6.dp))
                    DetailGroup {
                        DetailField(label = "金额") { AmountCell(item) }
                        DetailField(label = "分类") { CategoryCell(item) }
                        DetailField(label = "时间") { TimeCell(item) }

                        val badges = billBadges(item)
                        if (badges.isNotEmpty()) {
                            DetailField(label = "标记") {
                                FlowRow(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(5.dp, Alignment.End),
                                    verticalArrangement = Arrangement.spacedBy(5.dp),
                                ) {
                                    badges.forEach { badge: BillBadge -> Marker(badge) }
                                }
                            }
                        }

                        if (item.tags.isNotEmpty()) {
                            DetailField(label = "标签") {
                                FlowRow(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(5.dp, Alignment.End),
                                    verticalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    item.tags.forEach { tag ->
                                        TagPill(
                                            name = tag.name,
                                            fontSize = 10.sp,
                                            verticalPadding = 2.dp,
                                            onClick = onOpenTag?.let { open ->
                                                {
                                                    onDismiss()
                                                    open(tag.id)
                                                }
                                            },
                                            trailing = {
                                                InlineIcon(
                                                    iconKey = "chevron",
                                                    contentDescription = null,
                                                    size = 9.dp,
                                                    tint = colors.ink3,
                                                )
                                            },
                                        )
                                    }
                                }
                            }
                        }

                        val note = bill.note?.takeIf { it.isNotBlank() }
                        if (note != null) {
                            DetailField(
                                label = "备注",
                                onClick = {
                                    context.getSystemService(ClipboardManager::class.java)
                                        ?.setPrimaryClip(ClipData.newPlainText("账单备注", note))
                                    Toast.makeText(context, "复制成功", Toast.LENGTH_SHORT).show()
                                },
                            ) {
                                Text(
                                    text = note,
                                    modifier = Modifier.fillMaxWidth(),
                                    fontSize = 12.5.sp,
                                    lineHeight = 20.5.sp,
                                    color = colors.ink2,
                                    textAlign = TextAlign.End,
                                )
                            }
                        }

                        if (state.imagePaths.isNotEmpty()) {
                            DetailField(label = "凭证") {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    state.imagePaths.take(3).forEachIndexed { index, path ->
                                        ImageThumbnail(
                                            relativePath = path,
                                            size = 42,
                                            onClick = { viewerStart = index },
                                        )
                                    }
                                    if (state.imagePaths.size > 3) {
                                        Text(
                                            text = "+${state.imagePaths.size - 3}",
                                            style = TallyType.helper,
                                            color = colors.ink3,
                                        )
                                    }
                                }
                            }
                        }
                    }

                    RefundBlock(state = state, onOpenBill = onOpenBill)
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
                onTimeChange = viewModel::setRefundTime,
                onDismiss = viewModel::closeRefund,
                onConfirm = viewModel::confirmRefund,
            )
        }
    }
}

@Composable
private fun DetailHeader(
    canRefund: Boolean,
    onRepeat: () -> Unit,
    onRefund: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val colors = tallyColors
    Row(
        modifier = Modifier.fillMaxWidth().height(30.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("详情", style = TallyType.screenTitle, color = colors.ink)
        Spacer(Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
            DetailAction(label = "再记", onClick = onRepeat)
            if (canRefund) DetailAction(label = "退款", onClick = onRefund)
            DetailAction(label = "修改", primary = true, onClick = onEdit)
            DetailAction(label = "删除", danger = true, onClick = onDelete)
        }
    }
}

@Composable
private fun DetailAction(
    label: String,
    onClick: () -> Unit,
    primary: Boolean = false,
    danger: Boolean = false,
) {
    val colors = tallyColors
    val haptics = rememberTallyHaptics()
    val shape = RoundedCornerShape(14.dp)
    val background = when {
        primary -> colors.brand
        else -> colors.card
    }
    val foreground = when {
        primary -> colors.onBrand
        danger -> colors.expense
        else -> colors.ink
    }
    val borderColor = when {
        primary -> colors.brand
        danger -> colors.expense.copy(alpha = 0.34f)
        else -> colors.line.copy(alpha = 0.72f)
    }
    Row(
        modifier = Modifier
            .height(28.dp)
            .clip(shape)
            .background(background)
            .border(1.dp, borderColor, shape)
            .clickable {
                haptics.tap()
                onClick()
            }
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            fontSize = 12.5.sp,
            lineHeight = 15.sp,
            fontWeight = FontWeight.SemiBold,
            color = foreground,
            maxLines = 1,
        )
    }
}

/** 详情主信息与退款明细各自成组：只用浅色卡片和留白分区，不再画满宽硬线。 */
@Composable
private fun DetailGroup(content: @Composable () -> Unit) {
    val colors = tallyColors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(colors.card)
            .padding(horizontal = 14.dp),
    ) {
        content()
    }
}

@Composable
private fun DetailSectionHeader(title: String) {
    val colors = tallyColors
    Text(
        text = title,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 18.dp)
            .padding(bottom = 2.dp),
        style = TallyType.screenTitle,
        color = colors.ink,
    )
    Spacer(Modifier.height(6.dp))
}

@Composable
private fun DetailField(
    label: String,
    horizontal: Alignment.Horizontal = Alignment.End,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val colors = tallyColors
    val haptics = rememberTallyHaptics()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
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
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            modifier = Modifier.width(58.dp),
            fontSize = 13.sp,
            lineHeight = 16.sp,
            fontWeight = FontWeight.Medium,
            color = colors.ink2,
        )
        Column(modifier = Modifier.weight(1f), horizontalAlignment = horizontal) { content() }
    }
}

@Composable
private fun AmountCell(item: BillListItem) {
    val colors = tallyColors
    val bill = item.bill
    val amount = billAmountView(item)
    val color = when (amount.tone) {
        BillAmountTone.EXPENSE -> colors.expense
        BillAmountTone.INCOME -> colors.income
        BillAmountTone.NEUTRAL -> colors.slate
    }
    val isRefund = bill.isRefund || bill.refundOfBillId != null
    Column(horizontalAlignment = Alignment.End) {
        Text(
            text = amount.sign + "¥" + formatAmount(amount.amountMinor),
            style = TallyType.moneyDisplay.copy(fontSize = 25.sp, lineHeight = 29.sp),
            color = color,
            fontWeight = FontWeight.Medium,
        )
        if (!isRefund && item.refundedMinor > 0L) {
            val remaining = (bill.amountMinor - item.refundedMinor).coerceAtLeast(0L)
            Text(
                text = if (remaining > 0L) {
                    "已退 ¥${formatAmount(item.refundedMinor)} · 实际支出 ¥${formatAmount(remaining)}"
                } else {
                    "已全额退款"
                },
                style = TallyType.helper,
                color = colors.ink3,
                textAlign = TextAlign.End,
            )
        }
    }
}

@Composable
private fun CategoryCell(item: BillListItem) {
    val colors = tallyColors
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(colors.brandSurface),
            contentAlignment = Alignment.Center,
        ) {
            CategoryGlyph(
                iconKey = item.iconKey ?: "note",
                label = item.categoryPath.ifBlank { "账" },
                size = 18.dp,
                tint = colors.brand,
            )
        }
        Spacer(Modifier.width(8.dp))
        Column(
            modifier = Modifier.widthIn(max = 220.dp),
            horizontalAlignment = Alignment.Start,
        ) {
            Text(
                text = item.categoryPath.ifBlank { "未分类" },
                style = TallyType.listTitle,
                color = colors.ink,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = item.ledgerName,
                fontSize = 11.sp,
                lineHeight = 14.sp,
                color = colors.ink3,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun TimeCell(item: BillListItem) {
    val colors = tallyColors
    val bill = item.bill
    val occurred = if (bill.timePrecision == TimePrecision.DATETIME.name) {
        formatDateTime(bill.occurredAt)
    } else {
        formatDate(bill.occurredAt)
    }
    Column(horizontalAlignment = Alignment.End) {
        Text(
            text = occurred,
            modifier = Modifier.fillMaxWidth(),
            style = TallyType.secondary,
            color = colors.ink,
            textAlign = TextAlign.End,
        )
        Text(
            text = "记录于 ${formatDateTime(bill.createdAt)}",
            modifier = Modifier.fillMaxWidth(),
            fontSize = 10.5.sp,
            lineHeight = 14.sp,
            color = colors.ink3.copy(alpha = 0.82f),
            textAlign = TextAlign.End,
        )
    }
}

/**
 * 退款关系块：退款行向上看原账单，原单向下看退款明细。
 * 两边都能跳到对账单，退款与原始消费在同一个弹层链路里看全。
 */
@Composable
private fun RefundBlock(
    state: BillDetailUiState,
    onOpenBill: (String) -> Unit,
) {
    val item = state.item ?: return
    val bill = item.bill
    val isRefund = bill.isRefund || bill.refundOfBillId != null
    if (isRefund) {
        val origin = state.origin
        DetailSectionHeader("原账单")
        DetailGroup {
            if (origin != null) {
                OriginalBillRow(origin = origin, onClick = { onOpenBill(origin.bill.id) })
            } else {
                Text(
                    text = "未关联原单：这笔退款不冲抵任何分类的支出",
                    modifier = Modifier.padding(vertical = 10.dp),
                    style = TallyType.helper,
                    color = tallyColors.ink3,
                )
            }
        }
        return
    }
    if (state.refunds.isEmpty()) return

    DetailSectionHeader("退款记录")
    DetailGroup {
        state.refunds.forEach { refund ->
            RefundRecordRow(
                category = item.categoryPath.substringAfterLast(" · ").ifBlank { "未分类" },
                occurredAt = refund.occurredAt,
                amountMinor = refund.amountMinor,
                onClick = { onOpenBill(refund.id) },
            )
        }
    }
}

@Composable
private fun OriginalBillRow(
    origin: BillListItem,
    onClick: () -> Unit,
) {
    val colors = tallyColors
    val bill = origin.bill
    val category = origin.categoryPath.substringAfterLast(" · ").ifBlank { "未分类" }
    val note = bill.note?.takeIf { it.isNotBlank() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = category,
                style = TallyType.listTitle,
                color = colors.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = formatDate(bill.occurredAt) + note?.let { " · $it" }.orEmpty(),
                style = TallyType.helper,
                color = colors.ink3,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = "−¥" + formatAmount(bill.amountMinor),
                style = TallyType.moneyList,
                color = colors.expense,
            )
            if (origin.refundedMinor > 0L) {
                Text(
                    text = "(退款¥${formatAmount(origin.refundedMinor)})",
                    fontSize = 10.5.sp,
                    lineHeight = 13.sp,
                    color = colors.ink3,
                )
            }
        }
        InlineIcon(iconKey = "chevron", contentDescription = "查看原账单", size = 12.dp, tint = colors.ink3)
    }
}

@Composable
private fun RefundRecordRow(
    category: String,
    occurredAt: Long,
    amountMinor: Long,
    onClick: () -> Unit,
) {
    val colors = tallyColors
    val textColor = colors.slate
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "退款 · $category",
                style = TallyType.helper,
                color = textColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = formatDate(occurredAt),
                fontSize = 10.5.sp,
                lineHeight = 13.sp,
                color = textColor.copy(alpha = 0.8f),
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = "¥" + formatAmount(amountMinor),
            style = TallyType.moneyList,
            color = textColor,
        )
        InlineIcon(iconKey = "chevron", contentDescription = "查看退款", size = 12.dp, tint = textColor.copy(alpha = 0.8f))
    }
}

@Composable
private fun DeleteConfirm(
    item: BillListItem,
    refundCount: Int,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
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
