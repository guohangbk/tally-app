package io.github.guohangbk.tally.ui.common

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import io.github.guohangbk.tally.domain.model.TimePrecision
import io.github.guohangbk.tally.ui.design.ButtonVariant
import io.github.guohangbk.tally.ui.design.TallyButton
import io.github.guohangbk.tally.ui.design.TallyType
import io.github.guohangbk.tally.ui.design.tallyColors
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime

private val ROW_HEIGHT = 44.dp

/**
 * 日期＋时刻选择：月历复用 [TallyDatePickerDialog] 的那套网格，下方按精度决定要不要出「时 / 分」两列滚轮。
 * 精度是录入粒度——关掉时刻就只选日期，保存时把时间归零到本地 00:00。
 */
@Composable
fun TallyDateTimePickerDialog(
    initialMillis: Long,
    precision: TimePrecision,
    onDismiss: () -> Unit,
    onConfirm: (Long) -> Unit,
) {
    val colors = tallyColors
    val initial = remember(initialMillis) { Instant.ofEpochMilli(initialMillis).atZone(pickerZone) }
    val today = LocalDate.now(pickerZone)
    val withTime = precision == TimePrecision.DATETIME
    var selected by remember { mutableStateOf(initial.toLocalDate()) }
    var cursor by remember { mutableStateOf(selected.withDayOfMonth(1)) }
    var hour by remember { mutableIntStateOf(initial.hour) }
    var minute by remember { mutableIntStateOf(initial.minute) }
    val hourState = rememberLazyListState(initial.hour)
    val minuteState = rememberLazyListState(initial.minute)
    val scope = rememberCoroutineScope()

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
                if (withTime) {
                    // 「现在」把日期和时刻一起拉回来，省得先点今天再滚两列。
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(9.dp))
                            .background(colors.card)
                            .border(1.dp, colors.line, RoundedCornerShape(9.dp))
                            .clickable {
                                val now = ZonedDateTime.now(pickerZone)
                                selected = now.toLocalDate()
                                cursor = selected.withDayOfMonth(1)
                                hour = now.hour
                                minute = now.minute
                                scope.launch {
                                    hourState.scrollToItem(now.hour)
                                    minuteState.scrollToItem(now.minute)
                                }
                            }
                            .padding(horizontal = 11.dp, vertical = 5.dp),
                    ) {
                        Text("现在", fontSize = 11.5.sp, lineHeight = 14.4.sp, fontWeight = FontWeight.SemiBold, color = colors.ink2)
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

            if (withTime) {
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    TimeWheel(
                        label = "时",
                        count = 24,
                        selected = hour,
                        listState = hourState,
                        onChange = { hour = it },
                        modifier = Modifier.weight(1f),
                    )
                    TimeWheel(
                        label = "分",
                        count = 60,
                        selected = minute,
                        listState = minuteState,
                        onChange = { minute = it },
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = selected.format(isoFormatter) + if (withTime) " ${clockText(hour, minute)}" else "",
                    style = TallyType.listTitle,
                    color = colors.ink,
                    modifier = Modifier.weight(1f),
                )
                TallyButton(label = "取消", onClick = onDismiss, variant = ButtonVariant.GHOST, height = 34, modifier = Modifier.width(68.dp))
                Spacer(Modifier.width(7.dp))
                TallyButton(
                    label = "确定",
                    onClick = {
                        val millis = selected.atTime(LocalTime.of(hour, minute)).atZone(pickerZone).toInstant().toEpochMilli()
                        onConfirm(if (withTime) millis else localDateToMillis(selected))
                    },
                    variant = ButtonVariant.BRAND,
                    height = 34,
                    modifier = Modifier.width(68.dp),
                )
            }
        }
    }
}

/**
 * 两列滚轮之一：44dp 一行、视口三行，停在哪一格就以哪一格为准。
 * 上下各留一行内边距，滚到 0 时第一项正好落在中间行。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TimeWheel(
    label: String,
    count: Int,
    selected: Int,
    listState: LazyListState,
    onChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = tallyColors
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress to listState.firstVisibleItemIndex }
            .collect { (scrolling, index) ->
                // 只在停稳后取值，滚动过程中来回跳会让上面的「时:分」预览乱闪。
                if (!scrolling) onChange(index)
            }
    }
    Column(modifier = modifier) {
        Text(label, fontSize = 8.sp, lineHeight = 10.sp, color = colors.ink3, letterSpacing = 0.1.sp, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
        Spacer(Modifier.height(3.dp))
        Box(
            modifier = Modifier
                .height(ROW_HEIGHT * 3)
                .fillMaxWidth()
                .clip(RoundedCornerShape(11.dp))
                .background(colors.card)
                .border(1.dp, colors.line, RoundedCornerShape(11.dp)),
        ) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .fillMaxWidth()
                    .height(ROW_HEIGHT)
                    .background(colors.brandSurface.copy(alpha = 0.55f)),
            )
            LazyColumn(
                state = listState,
                flingBehavior = rememberSnapFlingBehavior(lazyListState = listState),
                contentPadding = PaddingValues(vertical = ROW_HEIGHT),
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxSize(),
            ) {
                items(count) { value ->
                    Box(modifier = Modifier.height(ROW_HEIGHT).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text(
                            text = value.toString().padStart(2, '0'),
                            style = TallyType.moneyBody.copy(fontSize = if (value == selected) 19.sp else 13.sp),
                            color = if (value == selected) colors.ink else colors.ink3,
                        )
                    }
                }
            }
        }
    }
}

/**
 * 整点选择：每日提醒时刻只需要「几点」，不需要日期和分钟，
 * 所以复用同一只滚轮，不给它再画一套弹层外壳。
 */
@Composable
fun TallyHourPickerDialog(
    initialHour: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    val colors = tallyColors
    var hour by remember { mutableIntStateOf(initialHour.coerceIn(0, 23)) }
    val hourState = rememberLazyListState(hour)
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .width(228.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(colors.paper)
                .border(1.dp, colors.line, RoundedCornerShape(20.dp))
                .padding(horizontal = 14.dp, vertical = 13.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("每日提醒时刻", style = TallyType.listTitle, color = colors.ink)
            Spacer(Modifier.height(10.dp))
            TimeWheel(
                label = "时",
                count = 24,
                selected = hour,
                listState = hourState,
                onChange = { hour = it },
            )
            Spacer(Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "${hour.toString().padStart(2, '0')}:00",
                    style = TallyType.moneyBody,
                    color = colors.ink,
                    modifier = Modifier.weight(1f),
                )
                TallyButton(label = "取消", onClick = onDismiss, variant = ButtonVariant.GHOST, height = 34, modifier = Modifier.width(68.dp))
                Spacer(Modifier.width(7.dp))
                TallyButton(label = "确定", onClick = { onConfirm(hour) }, variant = ButtonVariant.BRAND, height = 34, modifier = Modifier.width(68.dp))
            }
        }
    }
}

/** 滚轮停稳后的行号就是数值本身。 */
private fun clockText(hour: Int, minute: Int): String =
    "${hour.toString().padStart(2, '0')}:${minute.toString().padStart(2, '0')}"
