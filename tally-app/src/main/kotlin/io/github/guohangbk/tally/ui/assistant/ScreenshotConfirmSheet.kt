package io.github.guohangbk.tally.ui.assistant

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.guohangbk.tally.domain.formatAmount
import io.github.guohangbk.tally.ui.design.ButtonVariant
import io.github.guohangbk.tally.ui.design.InlineIcon
import io.github.guohangbk.tally.ui.design.TallyButton
import io.github.guohangbk.tally.ui.design.TallyTag
import io.github.guohangbk.tally.ui.design.TallyType
import io.github.guohangbk.tally.ui.design.TagTone
import io.github.guohangbk.tally.ui.design.tallyColors

/**
 * V-06 待确认列表。一张列表截图常常一次抽出好几笔，所以先给清单而不是直接跳记账页；
 * 没认出分类的那行标红并留空，「存入」会挡住它——绝不塞进「其他」后静默落库。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScreenshotConfirmSheet(
    state: ScreenshotBatchUiState,
    onToggle: (Int) -> Unit,
    onEdit: (Int) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = tallyColors
    val imageCount = (state.bills.map { it.imageIndex } + state.failures.map { it.imageIndex }).distinct().size
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = colors.paper,
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                Text(
                    text = "识别出 ${state.bills.size} 笔",
                    style = TallyType.sectionTitle,
                    color = colors.ink,
                    modifier = Modifier.weight(1f),
                )
                TallyTag(text = "来自 $imageCount 张截图", tone = TagTone.OUTLINE)
            }
            Spacer(Modifier.height(4.dp))
            state.bills.forEach { bill ->
                ScanRow(
                    bill = bill,
                    onToggle = { onToggle(bill.key) },
                    onEdit = { onEdit(bill.key) },
                )
            }
            if (state.failures.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                state.failures.forEach { failure ->
                    Text(
                        text = "第 ${failure.imageIndex + 1} 张：${failure.reason}，这张没有记上账单",
                        fontSize = 10.5.sp,
                        lineHeight = 15.sp,
                        color = colors.expense,
                    )
                }
            }
            Spacer(Modifier.height(11.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                TallyButton(
                    label = "逐条修改",
                    onClick = { state.bills.firstOrNull()?.let { onEdit(it.key) } },
                    variant = ButtonVariant.GHOST,
                    modifier = Modifier.weight(1f),
                )
                TallyButton(
                    label = "存入 ${state.checkedBills.size} 笔",
                    onClick = onSave,
                    variant = ButtonVariant.BRAND,
                    enabled = !state.busy && state.checkedBills.isNotEmpty() && !state.blockedByMissingCategory,
                    modifier = Modifier.weight(1f),
                )
            }
            if (state.blockedByMissingCategory) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "有 1 笔没认出分类，先补上或取消勾选。",
                    fontSize = 10.5.sp, lineHeight = 13.1.sp,
                    color = colors.expense,
                )
            }
        }
    }
}

@Composable
private fun ScanRow(bill: ScannedBill, onToggle: () -> Unit, onEdit: () -> Unit) {
    val colors = tallyColors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onEdit)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(15.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(if (bill.checked) colors.brand else colors.card)
                .border(1.6.dp, if (bill.checked) colors.brand else colors.ink3, RoundedCornerShape(5.dp))
                .clickable(onClick = onToggle),
            contentAlignment = Alignment.Center,
        ) {
            if (bill.checked) {
                InlineIcon(iconKey = "check", contentDescription = "已选", size = 9.dp, tint = colors.onBrand)
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = (bill.categoryLabel ?: "未分类") + " " + formatAmount(bill.amountMinor),
                fontSize = 12.5.sp, lineHeight = 15.6.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (bill.needsCategory) colors.expense else colors.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                ConfidenceBar(value = bill.confidence, low = bill.needsCategory)
                if (bill.needsCategory) {
                    Text("分类未认出 · 请补", fontSize = 10.sp, lineHeight = 12.5.sp, fontWeight = FontWeight.Bold, color = colors.onGlow)
                } else {
                    Text(
                        text = "高置信 · " + scannedStamp(bill.occurredAt),
                        fontSize = 10.sp, lineHeight = 12.5.sp,
                        color = colors.ink3,
                    )
                }
            }
        }
    }
}

@Composable
private fun ConfidenceBar(value: Float, low: Boolean) {
    val colors = tallyColors
    Box(
        modifier = Modifier
            .size(width = 26.dp, height = 3.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(colors.ink.copy(alpha = 0.10f)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(value.coerceIn(0.08f, 1f))
                .height(3.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(if (low) colors.expense else colors.gold),
        )
    }
}

/** 行上的时间戳：没有识别到日期就写「今天」，与截图页的口径一致。 */
private fun scannedStamp(epochMillis: Long?): String =
    if (epochMillis == null) "今天" else scanStampFormat.format(java.time.Instant.ofEpochMilli(epochMillis))

private val scanStampFormat = java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm")
    .withZone(java.time.ZoneId.systemDefault())
