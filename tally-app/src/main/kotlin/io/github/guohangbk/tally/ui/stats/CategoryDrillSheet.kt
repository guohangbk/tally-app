package io.github.guohangbk.tally.ui.stats

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.guohangbk.tally.domain.formatAmount
import io.github.guohangbk.tally.domain.model.BillListItem
import io.github.guohangbk.tally.domain.model.BillType
import io.github.guohangbk.tally.domain.model.CategoryBills
import io.github.guohangbk.tally.domain.model.CategoryTotal
import io.github.guohangbk.tally.ui.common.BillRow
import io.github.guohangbk.tally.ui.design.CategoryGlyph
import io.github.guohangbk.tally.ui.design.InlineIcon
import io.github.guohangbk.tally.ui.design.MoneySign
import io.github.guohangbk.tally.ui.design.MoneyText
import io.github.guohangbk.tally.ui.design.TallyBottomSheet
import io.github.guohangbk.tally.ui.design.TallyType
import io.github.guohangbk.tally.ui.design.tallyColors

/**
 * 分类报表下钻弹层。两种形态：
 *
 * 一级弹层（有下级时）：分类名 + 可点饼图 + 拆分行——各个二级在前，
 * 直接挂在一级上（没选二级）的钱排在最后、沿用一级分类名；每行是「在本级内的占比 + 金额」。
 *
 * 末级（二级弹层，或没有下级的独立一级）：一行汇总 + 首页账单行，没有标题也没有返回箭头——
 * 用户点进来时已经知道自己点了什么。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoryDrillSheet(
    drill: CategoryDrill,
    bills: CategoryBills?,
    grouping: Boolean,
    onDismiss: () -> Unit,
    onDrillInto: (CategoryTotal?) -> Unit,
    onOpenBill: (String) -> Unit,
) {
    val colors = tallyColors
    if (drill.isLevelOne) {
        TallyBottomSheet(
            onDismissRequest = onDismiss,
            containerColor = colors.paper,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 15.dp)
                    .padding(bottom = 18.dp),
            ) {
                LevelOneView(drill = drill, grouping = grouping, onDrillInto = onDrillInto)
            }
        }
    } else {
        BillListSheet(
            label = drill.name,
            count = bills?.bills?.size ?: (drill.row?.billCount ?: 0),
            amountMinor = bills?.netMinor ?: (drill.row?.amountMinor ?: 0L),
            positive = drill.type == BillType.INCOME,
            bills = bills?.bills,
            grouping = grouping,
            onDismiss = onDismiss,
            onOpenBill = onOpenBill,
        )
    }
}

/** 一级弹层：分类名 + 可点饼图 + 这一级的拆分行。 */
@Composable
private fun LevelOneView(
    drill: CategoryDrill,
    grouping: Boolean,
    onDrillInto: (CategoryTotal?) -> Unit,
) {
    val colors = tallyColors
    val palette = statsPalette()
    val total = drill.row?.amountMinor ?: 0L
    // 拆分行与饼图共用同一份切片：二级在前，直接挂一级上的那部分在最后。
    val slices = buildList {
        addAll(drill.children.map { DrillSlice(total = it, share = it.share) })
        if (drill.directMinor > 0L) {
            add(
                DrillSlice(
                    total = CategoryTotal(
                        categoryId = null,
                        name = drill.name,
                        amountMinor = drill.directMinor,
                        share = 0f,
                        iconKey = drill.iconKey,
                    ),
                    share = if (total <= 0L) 0f else drill.directMinor.toFloat() / total.toFloat(),
                ),
            )
        }
    }
    // 点饼图只换圆心的金额与分类名，不跳去账单列表——跳的是下面的拆分行。
    var picked by remember { mutableStateOf<Int?>(null) }

    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        CategoryTile(iconKey = drill.iconKey, label = drill.name)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = drill.name,
                fontSize = 14.5.sp,
                lineHeight = 17.sp,
                fontWeight = FontWeight.SemiBold,
                color = colors.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(1.dp))
            Text(
                text = "${drill.row?.billCount ?: 0} 笔",
                fontSize = 9.5.sp,
                lineHeight = 12.sp,
                color = colors.ink3,
            )
        }
        Donut(
            ranking = slices.map { it.total },
            palette = palette,
            totalLabel = drill.name,
            totalMinor = total,
            grouping = grouping,
            selected = picked,
            onSelectSlice = { picked = it },
            diameter = 96.dp,
        )
    }

    if (slices.isEmpty()) {
        Text("这一级没有可拆分的下级。", style = TallyType.helper, color = colors.ink3)
        return
    }
    Spacer(Modifier.height(4.dp))
    slices.forEachIndexed { index, slice ->
        if (index > 0) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(colors.ink.copy(alpha = 0.06f)))
        }
        SplitRow(
            slice = slice.total,
            share = slice.share,
            color = palette[index % palette.size],
            grouping = grouping,
            onClick = { onDrillInto(slice.total) },
        )
    }
}

/** 拆分行上的一格：饼图切片（[total]）与它在本级内的占比（[share]）。 */
private data class DrillSlice(val total: CategoryTotal, val share: Float)

/** 拆分行：在本级内的占比 + 金额。点整行进这一格的账单列表。 */
@Composable
private fun SplitRow(
    slice: CategoryTotal,
    share: Float,
    color: Color,
    grouping: Boolean,
    onClick: () -> Unit,
) {
    val colors = tallyColors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(9.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CategoryTile(iconKey = slice.iconKey, label = slice.name, nested = true)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = slice.name,
                fontSize = 12.5.sp,
                lineHeight = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = colors.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(1.dp))
            Text(
                text = percent(share),
                fontSize = 9.5.sp,
                lineHeight = 12.sp,
                color = colors.ink3,
            )
        }
        MoneyText(
            amountMinor = slice.amountMinor,
            sign = MoneySign.NONE,
            style = TallyType.moneyList.copy(fontSize = 13.5.sp),
            color = colors.ink2,
            grouping = grouping,
            showCurrency = false,
            textAlign = TextAlign.End,
            modifier = Modifier.width(64.dp),
        )
        InlineIcon(iconKey = "chevron", contentDescription = "查看 ${slice.name} 的账单", size = 13.dp, tint = colors.ink3)
    }
}

/**
 * 末级账单弹层：分类末级与日报表点开一天/一月共用这一枚。
 * 顶部只有一行汇总（名称、笔数、合计、关闭），下面就是首页那套账单行。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BillListSheet(
    label: String,
    count: Int,
    amountMinor: Long,
    positive: Boolean,
    bills: List<BillListItem>?,
    grouping: Boolean,
    onDismiss: () -> Unit,
    onOpenBill: (String) -> Unit,
) {
    val colors = tallyColors
    TallyBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = colors.paper,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 460.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 15.dp)
                .padding(bottom = 18.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = label,
                    fontSize = 12.5.sp, lineHeight = 15.6.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "$count 笔",
                    fontSize = 11.sp, lineHeight = 13.8.sp,
                    fontWeight = FontWeight.Bold,
                    color = colors.ink2,
                    maxLines = 1,
                )
                Text("·", fontSize = 11.sp, lineHeight = 13.8.sp, color = colors.ink3)
                Text(
                    text = (if (positive) "+" else "−") + formatAmount(amountMinor, grouping),
                    fontSize = 11.sp, lineHeight = 13.8.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (positive) colors.income else colors.expense,
                    maxLines = 1,
                )
                InlineIcon(
                    iconKey = "close",
                    contentDescription = "关闭",
                    size = 13.dp,
                    tint = colors.ink3,
                    modifier = Modifier.clickable(onClick = onDismiss),
                )
            }
            when {
                bills == null -> Text("正在读取…", style = TallyType.helper, color = colors.ink3)
                bills.isEmpty() -> Text("这个周期里没有账单。", style = TallyType.helper, color = colors.ink3)
                else -> bills.forEach { row ->
                    BillRow(
                        item = row,
                        grouping = grouping,
                        onClick = { onOpenBill(row.bill.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun CategoryTile(iconKey: String?, label: String, nested: Boolean = false) {
    val colors = tallyColors
    val shape = RoundedCornerShape(if (nested) 8.dp else 11.dp)
    Box(
        modifier = Modifier
            .size(if (nested) 26.dp else 30.dp)
            .clip(shape)
            .background(colors.card)
            .border(1.dp, colors.line, shape),
        contentAlignment = Alignment.Center,
    ) {
        CategoryGlyph(
            iconKey = iconKey ?: "more",
            label = label,
            size = if (nested) 13.dp else 15.dp,
            tint = colors.ink,
        )
    }
}

private fun percent(share: Float): String = "%.1f%%".format(share.coerceIn(0f, 1f) * 100f)
