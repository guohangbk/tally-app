package io.github.guohangbk.tally.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.guohangbk.tally.domain.formatAmount
import io.github.guohangbk.tally.domain.model.BillListItem
import io.github.guohangbk.tally.ui.design.BillBadges
import io.github.guohangbk.tally.ui.design.CategoryGlyph
import io.github.guohangbk.tally.ui.design.rememberTallyHaptics
import io.github.guohangbk.tally.ui.design.tallyColors

/**
 * 账单行：首页、搜索页、批量态共用同一套行组件与标记体系。
 * 被排除的账单仍然出现在列表里，只是整行降透明度并挂青灰角标（AC-F6-2）。
 */
@Composable
fun BillRow(
    item: BillListItem,
    grouping: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    selected: Boolean = false,
    showCheckbox: Boolean = false,
) {
    val colors = tallyColors
    val haptics = rememberTallyHaptics()
    val bill = item.bill
    val isIncome = bill.type == "INCOME"
    // 退款是「钱回来」，列表中按正数方向显示；它不进收入合计，角标与统计口径负责说明。
    val isRefund = bill.isRefund || bill.refundOfBillId != null
    val positive = isIncome || isRefund
    val excluded = bill.excludedFromStats
    val contentAlpha = if (excluded && !selected) 0.55f else 1f

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(if (selected) colors.brand.copy(alpha = 0.06f) else Color.Transparent)
            .then(
                if (selected) {
                    Modifier.drawBehind {
                        drawLine(
                            color = colors.brand,
                            start = androidx.compose.ui.geometry.Offset.Zero,
                            end = androidx.compose.ui.geometry.Offset(0f, size.height),
                            strokeWidth = 3.dp.toPx(),
                        )
                    }
                } else {
                    Modifier
                },
            )
            .combinedClickable(
                onClick = {
                    haptics.tap()
                    onClick()
                },
                onLongClick = onLongClick?.let {
                    {
                        haptics.longPress()
                        it()
                    }
                },
            )
            .padding(start = if (selected) 6.dp else 0.dp, end = 0.dp, top = 7.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showCheckbox) {
            CheckBox(checked = selected)
            Spacer(Modifier.width(8.dp))
        }
        val tileShape = RoundedCornerShape(11.dp)
        Box(
            modifier = Modifier
                .size(30.dp)
                .clip(tileShape)
                .background(if (excluded) colors.slate.copy(alpha = 0.14f) else colors.card)
                .border(1.dp, if (excluded) colors.slate.copy(alpha = 0.3f) else colors.line, tileShape)
                .alpha(contentAlpha),
            contentAlignment = Alignment.Center,
        ) {
            CategoryGlyph(
                iconKey = item.iconKey ?: "note",
                label = item.categoryL1Name ?: "账",
                size = 15.dp,
                tint = if (excluded) colors.slate else colors.ink,
            )
        }
        Spacer(Modifier.width(9.dp))
        Column(modifier = Modifier.weight(1f).alpha(contentAlpha)) {
            Text(
                text = item.categoryPath.ifBlank { "未分类" },
                fontSize = 13.5.sp,
                lineHeight = 17.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (excluded) colors.ink2 else colors.ink,
                maxLines = 1,
            )
            Spacer(Modifier.height(1.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                BillBadges(item = item)
                // 行上最多两枚标签，第三枚起折成 ＋N（TG-06）：标签多了也不把这一行撑高。
                // 名称由用户自己起，可能很长——给一枚封顶宽度，超出走省略号，不把备注与金额挤出去。
                if (item.tags.isNotEmpty()) {
                    val shown = item.tags.take(2)
                    shown.forEach { name ->
                        TagPill(
                            name = name,
                            modifier = Modifier.widthIn(max = 80.dp),
                            fontSize = 9.sp,
                            verticalPadding = 1.dp,
                        )
                    }
                    if (item.tags.size > shown.size) {
                        TagPill(name = "＋${item.tags.size - shown.size}", fontSize = 9.sp, verticalPadding = 1.dp)
                    }
                }
                // 行上只留备注：时刻属于「这一笔是什么时候」的细节，点开弹层再看，列表不铺时间。
                val note = bill.note?.takeIf { it.isNotBlank() }
                if (note != null) {
                    Text(
                        note,
                        fontSize = 11.sp, lineHeight = 13.sp,
                        color = colors.ink3,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        val refunded = if (isRefund) 0L else item.refundedMinor
        val remaining = bill.amountMinor - refunded
        if (refunded > 0L) {
            Column(modifier = Modifier.alpha(contentAlpha), horizontalAlignment = Alignment.End) {
                Text(
                    text = "−" + formatAmount(remaining, grouping),
                    style = io.github.guohangbk.tally.ui.design.TallyType.moneyList.copy(fontSize = 17.5.sp, lineHeight = 21.9.sp),
                    color = colors.expense,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.height(1.dp))
                Text(
                    text = if (remaining > 0L) "(已退款¥${formatAmount(refunded, grouping)})" else "(已全额退款)",
                    fontSize = 9.5.sp, lineHeight = 11.9.sp,
                    color = colors.ink3,
                    fontWeight = FontWeight.Medium,
                )
            }
        } else {
            Text(
                text = (if (positive) "+" else "−") + formatAmount(bill.amountMinor, grouping),
                style = io.github.guohangbk.tally.ui.design.TallyType.moneyList.copy(fontSize = 17.5.sp, lineHeight = 21.9.sp),
                color = if (positive) colors.income else colors.expense,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.alpha(contentAlpha),
            )
        }
    }
}

/** 勾选态同时用左侧描边 + 复选框 + 行底色三种信号，不只靠颜色（F2、TalkBack）。 */
@Composable
fun CheckBox(checked: Boolean, modifier: Modifier = Modifier) {
    val colors = tallyColors
    val shape = RoundedCornerShape(5.dp)
    Box(
        modifier = modifier
            .size(17.dp)
            .clip(shape)
            .background(if (checked) colors.brand else colors.card)
            .border(1.4.dp, if (checked) colors.brand else colors.ink3, shape),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) {
            io.github.guohangbk.tally.ui.design.InlineIcon(
                iconKey = "check",
                contentDescription = "已选",
                size = 11.dp,
                tint = colors.onBrand,
            )
        }
    }
}
