package io.github.guohangbk.tally.ui.bill

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
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.unit.dp
import io.github.guohangbk.tally.domain.formatAmount
import io.github.guohangbk.tally.domain.model.BillListItem
import io.github.guohangbk.tally.domain.model.TimePrecision
import io.github.guohangbk.tally.ui.common.TallyDateTimePickerDialog
import io.github.guohangbk.tally.ui.common.TallyFormInput
import io.github.guohangbk.tally.ui.common.formatDateTime
import io.github.guohangbk.tally.ui.design.ButtonVariant
import io.github.guohangbk.tally.ui.design.CategoryGlyph
import io.github.guohangbk.tally.ui.design.InlineIcon
import io.github.guohangbk.tally.ui.design.TallyBottomSheet
import io.github.guohangbk.tally.ui.design.TallyButton
import io.github.guohangbk.tally.ui.design.TallyType
import io.github.guohangbk.tally.ui.design.tallyColors

/**
 * 退款弹层：入口只出现在原单详情上，所以要退哪一笔已经定了，这里只填金额与退款时间。
 * 分类跟着原单走，不超过剩余可退的校验本地拦一道、仓库事务再拦一道。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BillRefundInputSheet(
    origin: BillListItem,
    remainingMinor: Long,
    amountText: String,
    refundAt: Long,
    error: String?,
    onAmountChange: (String) -> Unit,
    onTimeChange: (Long) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val colors = tallyColors
    var showTimePicker by remember { mutableStateOf(false) }
    val cardShape = RoundedCornerShape(14.dp)
    TallyBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = colors.paper,
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            Spacer(Modifier.height(12.dp))
            Text("退款", style = TallyType.sectionTitle, color = colors.ink)
            Spacer(Modifier.height(14.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(cardShape)
                    .background(colors.card)
                    .border(1.dp, colors.line, cardShape)
                    .padding(horizontal = 12.dp, vertical = 11.dp),
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
                        iconKey = origin.iconKey ?: "note",
                        label = origin.categoryPath.ifBlank { "账" },
                        size = 18.dp,
                        tint = colors.brand,
                    )
                }
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = origin.categoryPath.ifBlank { "未分类" },
                        style = TallyType.listTitle,
                        color = colors.ink,
                        maxLines = 1,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = formatDateTime(origin.bill.occurredAt),
                        style = TallyType.helper,
                        color = colors.ink3,
                    )
                }
                Text(
                    text = "−¥" + formatAmount(origin.bill.amountMinor),
                    style = TallyType.moneyList,
                    color = colors.expense,
                )
            }
            Spacer(Modifier.height(14.dp))
            TallyFormInput(
                label = "退款金额",
                prefix = "¥",
                numeric = true,
                value = amountText,
                placeholder = "0.00",
                onValueChange = onAmountChange,
            )
            Spacer(Modifier.height(4.dp))
            Text("剩余可退 ¥${formatAmount(remainingMinor)}", style = TallyType.helper, color = colors.ink3)
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(cardShape)
                    .background(colors.card)
                    .border(1.dp, colors.line, cardShape)
                    .clickable { showTimePicker = true }
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("退款时间", style = TallyType.helper, color = colors.ink3, modifier = Modifier.weight(1f))
                Text(formatDateTime(refundAt), style = TallyType.listTitle, color = colors.ink)
                Spacer(Modifier.width(6.dp))
                InlineIcon(iconKey = "calendar", contentDescription = "选择退款时间", size = 14.dp, tint = colors.ink3)
            }
            error?.let { failure ->
                Spacer(Modifier.height(10.dp))
                Text(failure, style = TallyType.helper, color = colors.expense)
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TallyButton(
                    label = "取消",
                    onClick = onDismiss,
                    variant = ButtonVariant.GHOST,
                    modifier = Modifier.weight(1f),
                )
                TallyButton(
                    label = "确认退款",
                    onClick = onConfirm,
                    variant = ButtonVariant.BRAND,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
    if (showTimePicker) {
        TallyDateTimePickerDialog(
            initialMillis = refundAt,
            precision = TimePrecision.DATETIME,
            onDismiss = { showTimePicker = false },
            onConfirm = { millis ->
                showTimePicker = false
                onTimeChange(millis)
            },
        )
    }
}
