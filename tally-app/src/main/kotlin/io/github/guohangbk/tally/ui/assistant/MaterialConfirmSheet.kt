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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.guohangbk.tally.domain.formatAmount
import io.github.guohangbk.tally.domain.ingest.IngestSource
import io.github.guohangbk.tally.domain.model.BillType
import io.github.guohangbk.tally.ui.design.ButtonVariant
import io.github.guohangbk.tally.ui.design.InlineIcon
import io.github.guohangbk.tally.ui.design.TallyBottomSheet
import io.github.guohangbk.tally.ui.design.TallyButton
import io.github.guohangbk.tally.ui.design.TallyType
import io.github.guohangbk.tally.ui.design.tallyColors

/**
 * V-06 待确认列表：截图、分享、语音三条入口共用这一张卡。
 * 一张列表截图常常一次抽出好几笔，所以先给清单而不是直接跳记账页；
 * 没认出分类的那行标红并留空，「存入」会挡住它——绝不塞进「其他」后静默落库。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MaterialConfirmSheet(
    state: MaterialBatchUiState,
    onToggle: (Int) -> Unit,
    /** 点某一行：只改这一笔。 */
    onEdit: (Int) -> Unit,
    /** 从第一笔开始，存一笔自动接下一笔。 */
    onQueue: () -> Unit,
    onSave: () -> Unit,
    /** 这一批不要了：连落盘的清单一起丢掉。 */
    onDiscard: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = tallyColors
    val imageCount = (state.bills.map { it.imageIndex } + state.failures.map { it.imageIndex }).distinct().size
    TallyBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = colors.paper,
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                // 张数并进标题里（「识别出 1 笔（1 张图）」），卡头只占一行：右边那一格留给「先不处理」，
                // 它以前是一枚灰色小字，跟旁边的标签长得一样，看不出能点。
                Text(
                    text = if (state.bills.isEmpty()) {
                        "没识别到可记的一笔"
                    } else {
                        "识别出 ${state.bills.size} 笔（${materialLabel(state, imageCount)}）"
                    },
                    style = TallyType.sectionTitle,
                    color = colors.ink,
                    modifier = Modifier.weight(1f),
                )
                SheetDismissAction(onClick = onDiscard)
            }
            // 没识别到的张数写在卡头：卡一浮起来就把气泡盖住了，只弹气泡等于没说。
            if (state.failures.isNotEmpty()) {
                Spacer(Modifier.height(3.dp))
                Text(
                    text = "$imageCount 张里 ${state.failures.size} 张没认出来",
                    fontSize = 10.5.sp, lineHeight = 13.1.sp,
                    color = colors.expense,
                )
            }
            Spacer(Modifier.height(4.dp))
            // 一次最多 [MaterialBatchViewModel.MAX_IMAGES] 张，每张还可能认出好几笔：
            // 行数跟着数据走，给个上限免得顶满整屏。
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 300.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                if (state.failures.isNotEmpty()) {
                    state.failures.sortedBy { it.imageIndex }.forEach { failure ->
                        Text(
                            text = "第 ${failure.imageIndex + 1} 张：${failure.reason}",
                            fontSize = 10.5.sp,
                            lineHeight = 15.sp,
                            color = colors.expense,
                            modifier = Modifier.padding(bottom = 3.dp),
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                }
                val ordered = state.bills.sortedWith(
                    compareBy<ScannedBill> { bill -> rowOrder(bill) }.thenBy { it.key },
                )
                ordered.forEach { bill ->
                    ScanRow(
                        bill = bill,
                        onToggle = { onToggle(bill.key) },
                        onEdit = { onEdit(bill.key) },
                    )
                }
            }
            Spacer(Modifier.height(11.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                TallyButton(
                    label = "逐条确认",
                    onClick = onQueue,
                    variant = ButtonVariant.GHOST,
                    enabled = !state.busy && state.bills.isNotEmpty(),
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
        }
    }
}

@Composable
private fun ScanRow(
    bill: ScannedBill,
    onToggle: () -> Unit,
    onEdit: () -> Unit,
) {
    val colors = tallyColors
    val marked = bill.checked
    val positive = bill.type == BillType.INCOME
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // 整行都是「改这一笔」的入口：以前只有右侧一小块配「修改」两个字可点，
            // 手指落在分类上反而没反应。
            .then(if (bill.editing) Modifier else Modifier.clickable(onClick = onEdit))
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(15.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(if (marked) colors.brand else colors.card)
                .border(1.6.dp, if (marked) colors.brand else colors.ink3, RoundedCornerShape(5.dp))
                .then(if (bill.editing) Modifier else Modifier.clickable(onClick = onToggle)),
            contentAlignment = Alignment.Center,
        ) {
            if (marked) {
                InlineIcon(iconKey = "check", contentDescription = "已选", size = 9.dp, tint = colors.onBrand)
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = bill.categoryLabel ?: "还没选分类",
                fontSize = 13.5.sp,
                lineHeight = 17.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (bill.needsCategory) colors.expense else colors.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            if (bill.editing) {
                Text("正在修改这一笔", fontSize = 10.sp, lineHeight = 12.5.sp, color = colors.ink3)
            } else {
                ScanMetaLine(bill)
            }
        }
        // 金额换成和首页账单同一套写法与配色（正负号 + 同色），右边一列对齐。
        Text(
            text = (if (positive) "+" else "−") + formatAmount(bill.amountMinor, grouping = false),
            style = TallyType.moneyList.copy(fontSize = 15.sp, lineHeight = 18.5.sp),
            fontWeight = FontWeight.Medium,
            color = if (positive) colors.income else colors.expense,
            maxLines = 1,
        )
    }
}

/**
 * 分类下面那一行：**日期在前、备注在后**，用中点分开；没有备注就只留日期。
 *
 * 「日期是不是真的」不另写一句解释——日期自己标红就是那个意思（默认按今天落的账、
 * 或原文提了日期却没解析出来）。文案解释会跟右侧金额抢位置，也说不出更多信息。
 */
@Composable
private fun ScanMetaLine(bill: ScannedBill) {
    val colors = tallyColors
    val guessed = bill.dateDefaulted || bill.dateUnresolved
    val note = bill.note?.takeIf { it.isNotBlank() } ?: bill.merchant?.takeIf { it.isNotBlank() }
    Text(
        text = buildAnnotatedString {
            withStyle(
                SpanStyle(
                    color = if (guessed) colors.expense else colors.ink3,
                    fontWeight = if (guessed) FontWeight.Bold else FontWeight.Normal,
                ),
            ) { append(scanDayStamp(bill.occurredAt)) }
            if (note != null) {
                append(" · ")
                append(note)
            }
        },
        fontSize = 10.sp,
        lineHeight = 12.5.sp,
        color = colors.ink3,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

private fun rowOrder(bill: ScannedBill): Int = when {
    bill.needsCategory || bill.dateUnresolved || bill.missing.isNotEmpty() -> 0
    else -> 1
}

/**
 * 卡头的「先不处理」：描边小按钮，不是一个灰字。
 * 它以前跟旁边的来源标签长得一模一样，一眼看不出能点——而它是这张卡唯一的退出口。
 */
@Composable
private fun SheetDismissAction(onClick: () -> Unit) {
    val colors = tallyColors
    val shape = RoundedCornerShape(11.dp)
    Row(
        modifier = Modifier
            .clip(shape)
            .background(colors.card)
            .border(1.dp, colors.line, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        InlineIcon(iconKey = "close", contentDescription = null, size = 11.dp, tint = colors.ink2)
        Text(
            text = "先不处理",
            fontSize = 11.5.sp,
            lineHeight = 14.sp,
            fontWeight = FontWeight.Medium,
            color = colors.ink2,
            maxLines = 1,
        )
    }
}

/** 卡头那句「这批素材从哪来」：语音是一句话，其余都是一张张图。 */
private fun materialLabel(state: MaterialBatchUiState, imageCount: Int): String =
    if (state.source == IngestSource.VOICE) "1 句话" else "$imageCount 张图"

/** 行上的落账日期：今天 / 昨天 直接说，再往前报月日。 */
private fun scanDayStamp(epochMillis: Long?): String {
    if (epochMillis == null) return "今天"
    val zone = java.time.ZoneId.systemDefault()
    val day = java.time.Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate()
    val today = java.time.LocalDate.now(zone)
    return when (day) {
        today -> "今天"
        today.minusDays(1) -> "昨天"
        else -> "${day.monthValue}月${day.dayOfMonth}日"
    }
}

