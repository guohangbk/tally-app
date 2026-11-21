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
import androidx.compose.foundation.layout.width
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
    /** 这一行不要了：通知抓进来的垃圾（退款、验证码之类）得有出口，不能挂着等 30 天过期。 */
    onRemove: (Int) -> Unit,
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
                Text(
                    text = if (state.bills.isEmpty()) {
                        "没有可确认的一笔"
                    } else {
                        "待确认 ${state.bills.size} 笔"
                    },
                    style = TallyType.sectionTitle,
                    color = colors.ink,
                    modifier = Modifier.weight(1f),
                )
                // 只留一个明确的出口：清空。单行不要了点行尾的「×」，整批不要了点这里。
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
                        onRemove = { onRemove(bill.key) },
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
    onRemove: () -> Unit,
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = bill.categoryLabel ?: "还没选分类",
                    fontSize = 13.5.sp,
                    lineHeight = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (bill.needsCategory) colors.expense else colors.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.width(5.dp))
                SourceChip(bill.source)
            }
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
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(RoundedCornerShape(12.dp))
                .then(if (bill.editing) Modifier else Modifier.clickable(onClick = onRemove)),
            contentAlignment = Alignment.Center,
        ) {
            InlineIcon(iconKey = "close", contentDescription = "移除这一笔", size = 11.dp, tint = colors.ink3)
        }
    }
}

/**
 * 分类下面那一行：日期在前、商户/备注在后；没有备注就只留日期。
 * 来源不进这一行，它是分类名右边的 [SourceChip]，一行一个，不跟文字挤。
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
 * 卡头右侧的「清空」：垃圾桶图标。
 *
 * 只留图标不写字：这一行右侧有标题占着，「清空」两个字会把「待确认 N 笔」挤短；
 * 垃圾桶本身就是「整批不要了」，比小字更一眼。
 */
@Composable
private fun SheetDismissAction(onClick: () -> Unit) {
    val colors = tallyColors
    val shape = RoundedCornerShape(11.dp)
    Box(
        modifier = Modifier
            .size(30.dp)
            .clip(shape)
            .background(colors.card)
            .border(1.dp, colors.line, shape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        InlineIcon(iconKey = "trash", contentDescription = "清空待确认", size = 13.dp, tint = colors.ink2)
    }
}

/**
 * 行内来源标签：只标这张卡上真会出现的四个入口。
 * 支付页与对话不走这份清单（前者入口已下线，后者是小莉自己的对话框），落 else 只作兜底。
 */
private fun sourceLabel(source: IngestSource): String = when (source) {
    IngestSource.NOTIFICATION -> "通知"
    IngestSource.SCREENSHOT -> "截图"
    IngestSource.SHARE -> "塔莉速记"
    IngestSource.VOICE -> "语音"
    else -> "其他"
}

@Composable
private fun SourceChip(source: IngestSource) {
    val colors = tallyColors
    val shape = RoundedCornerShape(5.dp)
    Text(
        text = sourceLabel(source),
        modifier = Modifier
            .clip(shape)
            .background(colors.ink.copy(alpha = 0.05f))
            .border(1.dp, colors.line, shape)
            .padding(horizontal = 5.dp, vertical = 1.5.dp),
        fontSize = 9.sp,
        lineHeight = 11.sp,
        color = colors.ink3,
        maxLines = 1,
    )
}

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

