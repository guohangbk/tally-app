package io.github.guohangbk.tally.ui.stats

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import io.github.guohangbk.tally.domain.model.StatsGranularity
import io.github.guohangbk.tally.ui.common.localDateToMillis
import io.github.guohangbk.tally.ui.common.millisToLocalDate
import io.github.guohangbk.tally.ui.design.ButtonVariant
import io.github.guohangbk.tally.ui.design.InlineIcon
import io.github.guohangbk.tally.ui.design.TallyButton
import io.github.guohangbk.tally.ui.design.TallyType
import io.github.guohangbk.tally.ui.design.tallyColors
import java.time.LocalDate
import java.time.ZoneId

/**
 * 月/年选择器：范围行那个「2026年10月 ▾」点开它。
 * 安卓原生日历的配色和这套纸墨语言对不上，所以自绘。
 */
@Composable
fun StatsPeriodPicker(
    granularity: StatsGranularity,
    anchor: Long,
    startDay: Int = 1,
    onDismiss: () -> Unit,
    onConfirm: (Long) -> Unit,
) {
    val colors = tallyColors
    val today = LocalDate.now(ZoneId.systemDefault())
    val initial = millisToLocalDate(anchor)
    val isYear = granularity == StatsGranularity.YEAR
    val day = startDay.coerceIn(1, 28)
    var selected by remember {
        mutableStateOf(if (isYear) initial.withDayOfMonth(1).withMonth(1) else initial)
    }
    var windowStart by remember { mutableStateOf(if (isYear) selected.year - 4 else selected.year) }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .width(300.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(colors.paper)
                .border(1.dp, colors.line, RoundedCornerShape(20.dp))
                .padding(horizontal = 14.dp, vertical = 13.dp),
        ) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                WindowStep(direction = -1) { windowStart -= if (isYear) 12 else 1 }
                Spacer(Modifier.width(6.dp))
                Text(
                    text = if (isYear) "$windowStart – ${windowStart + 11}" else "$windowStart 年",
                    style = TallyType.listTitle.copy(fontSize = 13.sp, lineHeight = 16.3.sp, fontWeight = FontWeight.SemiBold),
                    color = colors.ink,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.width(6.dp))
                WindowStep(direction = 1) { windowStart += if (isYear) 12 else 1 }
            }

            Spacer(Modifier.height(11.dp))
            val cells = if (isYear) {
                (0 until 12).map { LocalDate.of(windowStart + it, 1, 1) }
            } else {
                (1..12).map { LocalDate.of(windowStart, it, day) }
            }
            cells.chunked(4).forEach { row ->
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                    row.forEach { date ->
                        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                            PeriodCell(
                                label = if (isYear) date.year.toString() else "${date.monthValue} 月",
                                isSelected = date == selected,
                                isNow = if (isYear) date.year == today.year else date.monthYearEquals(today),
                                onClick = { selected = date },
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(9.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (isYear) "${selected.year} 年"
                    else if (day == 1) "${selected.year} 年 ${selected.monthValue} 月"
                    else "${selected.year} 年 ${selected.monthValue} 月 ${day} 号起",
                    style = TallyType.listTitle,
                    color = colors.ink,
                    modifier = Modifier.weight(1f),
                )
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

private fun LocalDate.monthYearEquals(other: LocalDate): Boolean = year == other.year && monthValue == other.monthValue

@Composable
private fun PeriodCell(label: String, isSelected: Boolean, isNow: Boolean, onClick: () -> Unit) {
    val colors = tallyColors
    val shape = RoundedCornerShape(9.dp)
    Box(
        modifier = Modifier
            .padding(horizontal = 2.dp)
            .fillMaxWidth()
            .height(31.dp)
            .clip(shape)
            .background(if (isSelected) colors.ink else colors.card)
            .border(1.dp, if (isSelected) colors.ink else if (isNow) colors.gold else colors.line, shape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = TallyType.listTitle.copy(fontSize = 11.5.sp, lineHeight = 14.4.sp, fontWeight = if (isSelected || isNow) FontWeight.Bold else FontWeight.Normal),
            color = when {
                isSelected -> colors.paper
                isNow -> colors.gold
                else -> colors.ink2
            },
            maxLines = 1,
        )
    }
}

@Composable
private fun WindowStep(direction: Int, onClick: () -> Unit) {
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
            contentDescription = if (direction < 0) "更早" else "更晚",
            size = 12.dp,
            tint = colors.ink2,
        )
    }
}
