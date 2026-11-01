package io.github.guohangbk.tally.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.guohangbk.tally.domain.model.BillListItem
import io.github.guohangbk.tally.domain.model.BillSource
import io.github.guohangbk.tally.domain.model.ReimburseStatus

/**
 * 标记体系（设计板 §03）：同一标记在记账页、详情、批量三处的图形、文案、口径必须完全一致。
 * 金色只留给「小莉 / 周期 / 分期」这三类自动产生的账，人工标记一律墨绿或青灰。
 */
enum class MarkerTone { BRAND, OUTLINE, SLATE, GOLD, EXPENSE }

data class BillBadge(val iconKey: String, val label: String, val tone: MarkerTone)

/** 由账单本身推导角标集合，页面不再各自拼文案。 */
fun billBadges(item: BillListItem): List<BillBadge> {
    val bill = item.bill
    val badges = mutableListOf<BillBadge>()
    if (bill.type != "INCOME") {
        when (bill.reimbursed) {
            ReimburseStatus.PENDING.name -> badges += BillBadge("receipt", "未报销", MarkerTone.OUTLINE)
            ReimburseStatus.DONE.name -> badges += BillBadge("receipt", "已报销", MarkerTone.BRAND)
            else -> Unit
        }
    }
    if (bill.isRefund || bill.refundOfBillId != null) {
        // 旧导入数据只有 is_refund、没有原单可指：角标要如实写出来，不能假装关联过。
        val label = if (bill.refundOfBillId != null) "退款" else "退款（未关联原单）"
        badges += BillBadge("refund", label, MarkerTone.BRAND)
    }
    if (bill.excludedFromStats) badges += BillBadge("eyeoff", "不计入收支", MarkerTone.SLATE)
    if (bill.recurrenceRuleId != null) badges += BillBadge("repeat", "周期", MarkerTone.GOLD)
    if (bill.installmentPlanId != null) {
        val index = bill.periodIndex?.plus(1)
        val total = item.installmentPeriodCount
        val label = if (index != null && total != null && total > 0) "分期 $index/$total" else "分期"
        badges += BillBadge("slice", label, MarkerTone.GOLD)
    }
    when (bill.source) {
        BillSource.IMPORT.name -> badges += BillBadge("import", "导入", MarkerTone.EXPENSE)
        BillSource.VOICE.name, BillSource.SCREENSHOT.name ->
            badges += BillBadge("ai", "速记", MarkerTone.GOLD)
        else -> Unit
    }
    return badges
}

@Composable
fun markerColors(tone: MarkerTone): Triple<Color, Color, Color> {
    val colors = tallyColors
    return when (tone) {
        MarkerTone.BRAND -> Triple(colors.brand.copy(alpha = 0.08f), colors.brand, colors.brand.copy(alpha = 0.26f))
        MarkerTone.OUTLINE -> Triple(colors.ink.copy(alpha = 0.05f), colors.ink3, colors.line)
        MarkerTone.SLATE -> Triple(colors.slate.copy(alpha = 0.12f), colors.slate, colors.slate.copy(alpha = 0.32f))
        MarkerTone.GOLD -> Triple(colors.glow, colors.onGlow, colors.gold.copy(alpha = 0.32f))
        MarkerTone.EXPENSE -> Triple(colors.expense.copy(alpha = 0.08f), colors.expense, colors.expense.copy(alpha = 0.28f))
    }
}

/** 行上角标，对应设计板 .mk。 */
@Composable
fun Marker(
    badge: BillBadge,
    modifier: Modifier = Modifier,
) {
    val (background, foreground, border) = markerColors(badge.tone)
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(5.dp))
            .background(background)
            .border(1.dp, border, RoundedCornerShape(5.dp))
            .padding(horizontal = 5.dp, vertical = 1.5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        InlineIcon(iconKey = badge.iconKey, contentDescription = null, size = 9.dp, tint = foreground)
        Text(badge.label, fontSize = 9.5.sp, lineHeight = 12.sp, fontWeight = FontWeight.Bold, color = foreground, maxLines = 1)
    }
}

@Composable
fun BillBadges(
    item: BillListItem,
    modifier: Modifier = Modifier,
) {
    val badges = billBadges(item)
    if (badges.isEmpty()) return
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        badges.forEach { Marker(it) }
    }
}

/**
 * 开关行，对应设计板 .mkr：记账页、详情、批量三处共用同一组件与同一文案。
 * `enabled = false` 时不可点击，`detail` 用来交代为什么点不动。
 */
@Composable
fun MarkerSwitch(
    label: String,
    iconKey: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    detail: String? = null,
    enabled: Boolean = true,
    compact: Boolean = false,
    selectedTone: MarkerTone = MarkerTone.BRAND,
) {
    val colors = tallyColors
    val active = checked && enabled
    val tone = if (active) selectedTone else MarkerTone.OUTLINE
    val (background, foreground, border) = markerColors(tone)
    val shape = RoundedCornerShape(10.dp)
    Row(
        modifier = modifier
            .height(if (compact) 28.dp else 29.dp)
            .clip(shape)
            .background(if (active) background else colors.card)
            .border(1.dp, if (active) border else colors.line, shape)
            .clickable(enabled = enabled) { onCheckedChange(!checked) }
            .padding(horizontal = if (compact) 7.dp else 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        InlineIcon(
            iconKey = iconKey,
            contentDescription = label,
            size = if (compact) 13.dp else 14.dp,
            tint = if (enabled) foreground else colors.ink3,
        )
        Spacer(Modifier.width(5.dp))
        Text(
            text = label,
            fontSize = if (compact) 11.sp else 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (enabled) foreground else colors.ink3,
            maxLines = 1,
        )
        if (detail != null) {
            Spacer(Modifier.width(6.dp))
            Text(detail, fontSize = 9.5.sp, lineHeight = 11.9.sp, color = colors.ink3, maxLines = 1, modifier = Modifier.weight(1f))
        } else {
            Spacer(Modifier.weight(1f))
        }
        // 青灰开关（不计入收支）与墨绿开关（报销）用同一形状，只换轨道色。
        SwitchTrack(checked = checked, enabled = enabled, onColor = if (active) markerColors(selectedTone).second else colors.ink3)
    }
}

/** 小开关轨道，对应 .mkr .dot2。 */
@Composable
private fun SwitchTrack(checked: Boolean, enabled: Boolean, onColor: Color) {
    val colors = tallyColors
    val shape = RoundedCornerShape(20.dp)
    Box(
        modifier = Modifier
            .width(26.dp)
            .height(15.dp)
            .clip(shape)
            .background(
                when {
                    !enabled -> colors.ink3.copy(alpha = 0.25f)
                    checked -> onColor
                    else -> colors.ink3.copy(alpha = 0.35f)
                },
            ),
    ) {
        Box(
            modifier = Modifier
                .size(11.dp)
                .align(if (checked) Alignment.CenterEnd else Alignment.CenterStart)
                .padding(horizontal = 2.5.dp)
                .clip(CircleShape)
                .background(Color.White),
        )
    }
}
