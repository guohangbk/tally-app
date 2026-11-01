package io.github.guohangbk.tally.ui.bill

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.guohangbk.tally.domain.formatAmount
import io.github.guohangbk.tally.domain.model.BillListItem
import io.github.guohangbk.tally.domain.model.BillType
import io.github.guohangbk.tally.domain.model.TimePrecision
import io.github.guohangbk.tally.ui.common.TallyDateTimePickerDialog
import io.github.guohangbk.tally.ui.common.TallyFormInput
import io.github.guohangbk.tally.ui.common.formatDate
import io.github.guohangbk.tally.ui.design.BottomSheetGrabber
import io.github.guohangbk.tally.ui.design.ButtonVariant
import io.github.guohangbk.tally.ui.design.InlineIcon
import io.github.guohangbk.tally.ui.design.TallyButton
import io.github.guohangbk.tally.ui.design.TallyType
import io.github.guohangbk.tally.ui.design.tallyColors

/**
 * 退款弹层：入口只出现在原单详情上，所以要退哪一笔已经定了，这里只填金额与退款日期。
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
    onDateChange: (Long) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val colors = tallyColors
    var showDatePicker by remember { mutableStateOf(false) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = colors.paper,
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            BottomSheetGrabber(modifier = Modifier.align(Alignment.CenterHorizontally))
            Spacer(Modifier.height(12.dp))
            Text("退款", style = TallyType.sectionTitle, color = colors.ink)
            Spacer(Modifier.height(4.dp))
            Text("退款不算收入，从这笔支出的分类里冲抵。", style = TallyType.helper, color = colors.ink3)
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = origin.categoryPath.ifBlank { "未分类" } + " · " +
                        formatDate(origin.bill.occurredAt) + " · ¥" + formatAmount(origin.bill.amountMinor),
                    style = TallyType.listTitle,
                    color = colors.ink,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                )
                InlineIcon(iconKey = "refund", contentDescription = "退款", size = 14.dp, tint = colors.brand)
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
            Text("还能退 ¥${formatAmount(remainingMinor)}", style = TallyType.helper, color = colors.ink3)
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(38.dp)
                    .padding(horizontal = 11.dp)
                    .padding(vertical = 8.dp)
                    .clickable { showDatePicker = true },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("退款日期", style = TallyType.helper, color = colors.ink3, modifier = Modifier.weight(1f))
                Text(formatDate(refundAt), style = TallyType.listTitle, color = colors.ink)
                InlineIcon(iconKey = "calendar", contentDescription = "选择退款日期", size = 14.dp, tint = colors.ink3)
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
    if (showDatePicker) {
        TallyDateTimePickerDialog(
            initialMillis = refundAt,
            precision = TimePrecision.DATE,
            onDismiss = { showDatePicker = false },
            onConfirm = { millis ->
                showDatePicker = false
                onDateChange(millis)
            },
        )
    }
}
