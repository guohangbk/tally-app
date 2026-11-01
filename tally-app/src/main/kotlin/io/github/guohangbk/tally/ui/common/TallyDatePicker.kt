package io.github.guohangbk.tally.ui.common

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import io.github.guohangbk.tally.ui.design.ButtonVariant
import io.github.guohangbk.tally.ui.design.InlineIcon
import io.github.guohangbk.tally.ui.design.TallyButton
import io.github.guohangbk.tally.ui.design.TallyType
import io.github.guohangbk.tally.ui.design.tallyColors
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal val pickerZone: ZoneId get() = ZoneId.systemDefault()
internal val WEEK_LABELS = listOf("日", "一", "二", "三", "四", "五", "六")
internal val monthTitleFormatter = DateTimeFormatter.ofPattern("yyyy年M月", java.util.Locale.CHINA)
internal val isoFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd", java.util.Locale.CHINA)

/**
 * 自绘日期选择：今天/昨天/前天快捷片 + 紧凑月历。
 * 原生 DatePickerDialog 的配色、圆角和这套纸墨语言对不上，所以不用它。
 */
@Composable
fun TallyDatePickerDialog(
    initialDate: Long,
    onDismiss: () -> Unit,
    onConfirm: (Long) -> Unit,
) {
    val colors = tallyColors
    val today = LocalDate.now(pickerZone)
    var selected by remember { mutableStateOf(millisToLocalDate(initialDate)) }
    var cursor by remember { mutableStateOf(selected.withDayOfMonth(1)) }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .width(300.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(colors.paper)
                .border(1.dp, colors.line, RoundedCornerShape(20.dp))
                .padding(horizontal = 14.dp, vertical = 13.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                listOf("今天" to 0L, "昨天" to -1L, "前天" to -2L).forEach { (label, offset) ->
                    val date = today.plusDays(offset)
                    val active = selected == date
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(9.dp))
                            .background(if (active) colors.ink else colors.card)
                            .border(1.dp, if (active) colors.ink else colors.line, RoundedCornerShape(9.dp))
                            .clickable {
                                selected = date
                                cursor = date.withDayOfMonth(1)
                            }
                            .padding(horizontal = 11.dp, vertical = 5.dp),
                    ) {
                        Text(
                            text = label,
                            fontSize = 11.5.sp, lineHeight = 14.4.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (active) colors.paper else colors.ink2,
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                MonthStep(direction = -1, onClick = { cursor = cursor.minusMonths(1) })
                Spacer(Modifier.width(6.dp))
                Text(cursor.format(monthTitleFormatter), style = TallyType.listTitle, color = colors.ink)
                Spacer(Modifier.weight(1f))
                MonthStep(direction = 1, onClick = { cursor = cursor.plusMonths(1) })
            }

            Spacer(Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                WEEK_LABELS.forEach { label ->
                    Text(
                        text = label,
                        fontSize = 10.5.sp, lineHeight = 13.1.sp,
                        color = colors.ink3,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            Spacer(Modifier.height(2.dp))
            MonthGrid(firstOfMonth = cursor, selected = selected, today = today, onSelect = { selected = it })

            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(selected.format(isoFormatter), style = TallyType.listTitle, color = colors.ink, modifier = Modifier.weight(1f))
                TallyButton(label = "取消", onClick = onDismiss, variant = ButtonVariant.GHOST, height = 34, modifier = Modifier.width(72.dp))
                Spacer(Modifier.width(7.dp))
                TallyButton(
                    label = "确定",
                    onClick = { onConfirm(localDateToMillis(selected)) },
                    variant = ButtonVariant.BRAND,
                    height = 34,
                    modifier = Modifier.width(72.dp),
                )
            }
        }
    }
}

@Composable
internal fun MonthStep(direction: Int, onClick: () -> Unit) {
    val colors = tallyColors
    Box(
        modifier = Modifier
            .size(26.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(colors.ink.copy(alpha = 0.05f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        InlineIcon(
            iconKey = if (direction < 0) "back" else "chevron",
            contentDescription = if (direction < 0) "上一月" else "下一月",
            size = 12.dp,
            tint = colors.ink2,
        )
    }
}

@Composable
internal fun MonthGrid(
    firstOfMonth: LocalDate,
    selected: LocalDate,
    today: LocalDate,
    onSelect: (LocalDate) -> Unit,
) {
    val colors = tallyColors
    val daysInMonth = firstOfMonth.lengthOfMonth()
    // 周日起点：LocalDate.dayOfWeek 周一=1…周日=7，换算成周日=0
    val leading = firstOfMonth.dayOfWeek.value % 7
    val rows = (leading + daysInMonth + 6) / 7

    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        repeat(rows) { week ->
            Row(modifier = Modifier.fillMaxWidth()) {
                repeat(7) { weekday ->
                    val slot = week * 7 + weekday
                    val day = if (slot < leading || slot >= leading + daysInMonth) null else slot - leading + 1
                    Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        if (day != null) {
                            val date = firstOfMonth.withDayOfMonth(day)
                            val isSelected = date == selected
                            val isToday = date == today
                            Box(
                                modifier = Modifier
                                    .size(30.dp)
                                    .clip(CircleShape)
                                    .background(if (isSelected) colors.ink else colors.paper)
                                    .then(
                                        if (isToday && !isSelected) Modifier.border(1.dp, colors.gold, CircleShape) else Modifier,
                                    )
                                    .clickable { onSelect(date) },
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = day.toString(),
                                    fontSize = 11.5.sp, lineHeight = 14.4.sp,
                                    fontWeight = if (isSelected || isToday) FontWeight.Bold else FontWeight.Normal,
                                    color = when {
                                        isSelected -> colors.paper
                                        isToday -> colors.gold
                                        else -> colors.ink2
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 年月选择：只靠左右箭头翻月，跳去半年前那笔要按十几下。
 * 这里给「年份左右翻 + 整年 12 格一览」，点哪个月就跳到哪个月；选中即收起，不需要确定键。
 */
@Composable
fun TallyMonthPickerDialog(
    currentMonthStart: Long,
    onDismiss: () -> Unit,
    onPick: (Long) -> Unit,
) {
    val colors = tallyColors
    val selected = remember(currentMonthStart) { millisToLocalDate(currentMonthStart).withDayOfMonth(1) }
    var year by remember { mutableStateOf(selected.year) }
    val thisMonth = LocalDate.now().withDayOfMonth(1)

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .width(300.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(colors.paper)
                .border(1.dp, colors.line, RoundedCornerShape(20.dp))
                .padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                YearStep(-1, "上一年") { year -= 1 }
                Text(
                    text = "${year}年",
                    style = TallyType.listTitle.copy(lineHeight = 18.8.sp, fontWeight = FontWeight.SemiBold),
                    color = colors.ink,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                )
                YearStep(1, "下一年") { year += 1 }
            }
            Spacer(Modifier.height(8.dp))
            (1..12).chunked(3).forEach { row ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    row.forEach { month ->
                        val target = LocalDate.of(year, month, 1)
                        val active = target == selected
                        val isNow = target == thisMonth
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(38.dp)
                                .clip(RoundedCornerShape(11.dp))
                                .background(if (active) colors.ink else colors.card)
                                .border(
                                    width = 1.dp,
                                    color = when {
                                        active -> colors.ink
                                        isNow -> colors.gold.copy(alpha = 0.55f)
                                        else -> colors.line
                                    },
                                    shape = RoundedCornerShape(11.dp),
                                )
                                .clickable { onPick(localDateToMillis(target)) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = "${month}月",
                                fontSize = 12.5.sp, lineHeight = 15.6.sp,
                                fontWeight = if (active || isNow) FontWeight.SemiBold else FontWeight.Normal,
                                color = when {
                                    active -> colors.paper
                                    isNow -> colors.gold
                                    else -> colors.ink2
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun YearStep(direction: Int, label: String, onClick: () -> Unit) {
    val colors = tallyColors
    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(colors.card)
            .border(1.dp, colors.line, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        InlineIcon(
            iconKey = "chevron",
            contentDescription = label,
            size = 14.dp,
            tint = colors.ink2,
            modifier = if (direction < 0) Modifier.rotate(180f) else Modifier,
        )
    }
}
