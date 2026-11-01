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
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import io.github.guohangbk.tally.ui.common.TallyDatePickerDialog
import io.github.guohangbk.tally.ui.common.localDateToMillis
import io.github.guohangbk.tally.ui.common.millisToLocalDate
import io.github.guohangbk.tally.ui.design.ButtonVariant
import io.github.guohangbk.tally.ui.design.InlineIcon
import io.github.guohangbk.tally.ui.design.TallyButton
import io.github.guohangbk.tally.ui.design.TallyType
import io.github.guohangbk.tally.ui.design.tallyColors
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * 区间选择：预设一段、或自己点起止两个日期。
 * 起止都包含在内；确定时把结束日 +1 天换成左闭右开的右边界。
 */
@Composable
fun StatsRangePicker(
    rangeStart: Long,
    rangeEndExclusive: Long?,
    onPreset: (RangePreset) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: (Long, Long) -> Unit,
) {
    val colors = tallyColors
    var start by remember { mutableStateOf(millisToLocalDate(rangeStart)) }
    var end by remember {
        mutableStateOf(
            millisToLocalDate(rangeEndExclusive ?: rangeStart)
                .minusDays(1)
                .takeIf { !it.isBefore(millisToLocalDate(rangeStart)) }
                ?: millisToLocalDate(rangeStart),
        )
    }
    var picking by remember { mutableStateOf<Picking?>(null) }
    val days = ChronoUnit.DAYS.between(start, end).toInt() + 1

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .width(320.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(colors.paper)
                .border(1.dp, colors.line, RoundedCornerShape(20.dp))
                .padding(horizontal = 14.dp, vertical = 13.dp),
        ) {
            Text("统计区间", style = TallyType.listTitle, color = colors.ink)
            Spacer(Modifier.height(3.dp))
            Text(
                text = "起止两天都算在内，趋势与分类报表按这段日期重算",
                fontSize = 10.5.sp, lineHeight = 13.1.sp,
                color = colors.ink3,
            )
            Spacer(Modifier.height(11.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                RangePreset.entries.forEach { preset ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(9.dp))
                            .background(colors.card)
                            .border(1.dp, colors.line, RoundedCornerShape(9.dp))
                            .clickable {
                                // 预设就是确定：点完直接按这段区间重算，不让人再点一次「确定」。
                                onPreset(preset)
                                onDismiss()
                            }
                            .padding(vertical = 6.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = preset.label,
                            fontSize = 10.sp, lineHeight = 12.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = colors.ink2,
                            maxLines = 1,
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            DateRow(label = "开始", date = start, onClick = { picking = Picking.START })
            Spacer(Modifier.height(7.dp))
            DateRow(label = "结束", date = end, onClick = { picking = Picking.END })
            Spacer(Modifier.height(9.dp))
            Text(
                text = "共 $days 天",
                fontSize = 11.sp, lineHeight = 13.8.sp,
                color = colors.ink2,
            )

            Spacer(Modifier.height(11.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.weight(1f))
                TallyButton(label = "取消", onClick = onDismiss, variant = ButtonVariant.GHOST, height = 34, modifier = Modifier.width(72.dp))
                Spacer(Modifier.width(7.dp))
                TallyButton(
                    label = "确定",
                    onClick = { onConfirm(localDateToMillis(start), localDateToMillis(end.plusDays(1))) },
                    variant = ButtonVariant.BRAND,
                    height = 34,
                    modifier = Modifier.width(72.dp),
                )
            }
        }
    }

    when (picking) {
        Picking.START -> TallyDatePickerDialog(
            initialDate = localDateToMillis(start),
            onDismiss = { picking = null },
            onConfirm = { millis ->
                val picked = millisToLocalDate(millis)
                start = picked
                if (end.isBefore(picked)) end = picked
                picking = null
            },
        )

        Picking.END -> TallyDatePickerDialog(
            initialDate = localDateToMillis(end),
            onDismiss = { picking = null },
            onConfirm = { millis ->
                val picked = millisToLocalDate(millis)
                end = picked
                if (picked.isBefore(start)) start = picked
                picking = null
            },
        )

        null -> Unit
    }
}

private enum class Picking { START, END }

@Composable
private fun DateRow(label: String, date: LocalDate, onClick: () -> Unit) {
    val colors = tallyColors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(colors.card)
            .border(1.dp, colors.line, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(label, fontSize = 11.5.sp, lineHeight = 14.4.sp, color = colors.ink3)
        Spacer(Modifier.weight(1f))
        Text(
            text = date.toString(),
            style = TallyType.listTitle.copy(fontSize = 13.sp, lineHeight = 16.3.sp, fontWeight = FontWeight.SemiBold),
            color = colors.ink,
        )
        InlineIcon(iconKey = "chevron_down", contentDescription = "选日期", size = 11.dp, tint = colors.ink3)
    }
}
