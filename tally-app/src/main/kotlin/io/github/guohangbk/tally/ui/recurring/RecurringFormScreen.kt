package io.github.guohangbk.tally.ui.recurring

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.guohangbk.tally.di.rememberAppGraph
import io.github.guohangbk.tally.domain.formatAmount
import io.github.guohangbk.tally.domain.model.BillType
import io.github.guohangbk.tally.domain.model.RecurrenceSpec
import io.github.guohangbk.tally.domain.model.RecurrenceUnit
import io.github.guohangbk.tally.ui.common.CategoryPickerSheet
import io.github.guohangbk.tally.ui.common.LedgerPickerSheet
import io.github.guohangbk.tally.ui.common.TallyDatePickerDialog
import io.github.guohangbk.tally.ui.common.TallyFormInput
import io.github.guohangbk.tally.ui.common.formatDate
import io.github.guohangbk.tally.ui.common.millisToLocalDate
import io.github.guohangbk.tally.ui.common.tallyViewModel
import io.github.guohangbk.tally.ui.design.ButtonVariant
import io.github.guohangbk.tally.ui.design.InlineIcon
import io.github.guohangbk.tally.ui.design.TagTone
import io.github.guohangbk.tally.ui.design.TallyButton
import io.github.guohangbk.tally.ui.design.TallyCard
import io.github.guohangbk.tally.ui.design.TallyListRow
import io.github.guohangbk.tally.ui.design.TallySegmented
import io.github.guohangbk.tally.ui.design.TallyRowChevron
import io.github.guohangbk.tally.ui.design.TallyTag
import io.github.guohangbk.tally.ui.design.TallyType
import io.github.guohangbk.tally.ui.design.TallyToastEffects
import io.github.guohangbk.tally.ui.design.tallyColors
import io.github.guohangbk.tally.ui.shell.TallyTopBar
import java.time.format.DateTimeFormatter
import java.util.Locale

private val previewDayFormatter = DateTimeFormatter.ofPattern("MM-dd EEE", Locale.CHINA)

/**
 * P12b 新建周期规则：整页而不是弹层，保存前先把「未来 3 期」摊出来。
 * 到期才落库、不提前不追溯这句话必须写在启用按钮之上。
 */
@Composable
fun RecurringFormScreen(onBack: () -> Unit, ruleId: String? = null) {
    val graph = rememberAppGraph()
    val viewModel = tallyViewModel(key = "recurring-form-${ruleId ?: "new"}") { RecurringFormViewModel(graph, ruleId) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val colors = tallyColors
    var showCategory by remember { mutableStateOf(false) }
    var showLedger by remember { mutableStateOf(false) }
    var showStart by remember { mutableStateOf(false) }
    var showEnd by remember { mutableStateOf(false) }

    LaunchedEffect(state.saved) {
        if (state.saved) onBack()
    }

    Column(modifier = Modifier.fillMaxSize().background(colors.paper)) {
        TallyTopBar(
            title = if (ruleId == null) "新建周期任务" else "修改周期任务",
            onBack = onBack,
            trailing = {
                TallyTag(
                    text = state.ledgers.firstOrNull { it.ledger.id == state.ledgerId }?.ledger?.name ?: "默认账本",
                    tone = TagTone.OUTLINE,
                    modifier = Modifier.clickable(onClick = { showLedger = true }),
                )
            },
        )

        // 提示改成贴底气泡，保留「还不能保存」这个定性说法。
        TallyToastEffects(
            notice = null,
            error = state.error,
            errorTitle = "还不能保存",
            onErrorShown = viewModel::clearError,
        )

        Column(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 15.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            TallySegmented(
                options = listOf(BillType.EXPENSE to "支出", BillType.INCOME to "收入"),
                selected = state.type,
                onSelect = viewModel::setType,
            )

            TallyFormInput(label = "任务名称", value = state.name, placeholder = "例如：房租、话费", onValueChange = viewModel::setName)
            TallyFormInput(
                label = "每期金额",
                value = state.amountText,
                placeholder = "0.00",
                onValueChange = viewModel::setAmount,
                prefix = "¥",
                numeric = true,
            )

            TallyListRow(
                title = state.categories.firstOrNull { it.id == state.categoryL1Id }?.name ?: "选择分类",
                subtitle = state.categories.firstOrNull { it.id == state.categoryL2Id }?.name
                    ?: "分类 · 每期都记在同一类下",
                iconKey = "tag",
                onClick = { showCategory = true },
                trailing = { TallyRowChevron() },
            )

            FieldLabel("重复")
            TallySegmented(
                options = RecurrenceUnit.entries.map { it to unitLabel(it) },
                selected = state.spec.unit,
                onSelect = viewModel::setUnit,
            )
            when (state.spec.unit) {
                RecurrenceUnit.MONTHLY -> DayPicker("每月几号", state.spec.dayOfMonth ?: 1, viewModel::setDayOfMonth, 31)
                RecurrenceUnit.WEEKLY -> WeekdayPicker(state.spec.dayOfWeek ?: 1, viewModel::setDayOfWeek)
                RecurrenceUnit.YEARLY -> {
                    DayPicker("几月", state.spec.monthOfYear ?: 1, viewModel::setMonthOfYear, 12)
                    DayPicker("几号", state.spec.dayOfMonth ?: 1, viewModel::setDayOfMonth, 31)
                }
                RecurrenceUnit.LAST_DAY_OF_MONTH -> FormNote("每月最后一天执行，2 月自动落在 28/29 日。")
                RecurrenceUnit.DAILY -> FormNote("每天执行一次。")
            }

            TallyListRow(
                title = formatDate(state.startAt),
                subtitle = "开始日期",
                iconKey = "calendar",
                onClick = { showStart = true },
                trailing = { TallyRowChevron() },
            )

            FieldLabel("结束方式")
            TallySegmented(
                options = listOf(EndMode.NEVER to "永不结束", EndMode.ON_DATE to "截止日期", EndMode.AFTER_COUNT to "执行次数"),
                selected = state.endMode,
                onSelect = viewModel::setEndMode,
            )
            when (state.endMode) {
                EndMode.ON_DATE -> TallyListRow(
                    title = state.endDate?.let(::formatDate) ?: "未设置",
                    subtitle = "最后一天应执行日",
                    iconKey = "calendar",
                    onClick = { showEnd = true },
                    trailing = { TallyRowChevron() },
                )
                EndMode.AFTER_COUNT -> TallyFormInput(
                    label = "共执行几期",
                    value = state.endCount,
                    placeholder = "12",
                    onValueChange = viewModel::setEndCount,
                    numeric = true,
                )
                EndMode.NEVER -> Unit
            }

            TallyFormInput(label = "备注（可选）", value = state.note, placeholder = "写点什么都行", onValueChange = viewModel::setNote)

            PreviewBlock(state)

            Spacer(Modifier.height(4.dp))
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 15.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TallyButton(label = "取消", onClick = onBack, variant = ButtonVariant.GHOST, height = 40, modifier = Modifier.weight(1f))
            TallyButton(
                label = if (state.saving) "正在保存…" else if (ruleId == null) "启用规则" else "保存修改",
                onClick = viewModel::save,
                variant = ButtonVariant.BRAND,
                enabled = state.canSave && !state.saving,
                height = 40,
                modifier = Modifier.weight(1.6f),
            )
        }
        Spacer(Modifier.height(6.dp))
    }

    if (showCategory) {
        CategoryPickerSheet(
            categories = state.categories,
            selectedL1 = state.categoryL1Id,
            selectedL2 = state.categoryL2Id,
            onConfirm = { l1, l2 ->
                viewModel.selectCategory(l1, l2)
                showCategory = false
            },
            onDismiss = { showCategory = false },
        )
    }
    if (showLedger) {
        LedgerPickerSheet(
            ledgers = state.ledgers,
            selectedId = state.ledgerId,
            onSelect = {
                viewModel.setLedger(it)
                showLedger = false
            },
            onDismiss = { showLedger = false },
        )
    }
    if (showStart) {
        TallyDatePickerDialog(initialDate = state.startAt, onDismiss = { showStart = false }, onConfirm = {
            viewModel.setStartAt(it)
            showStart = false
        })
    }
    if (showEnd) {
        TallyDatePickerDialog(initialDate = state.endDate ?: state.startAt, onDismiss = { showEnd = false }, onConfirm = {
            viewModel.setEndDate(it)
            showEnd = false
        })
    }
}

@Composable
private fun FieldLabel(text: String) {
    Text(
        text = text,
        style = TallyType.eyebrow.copy(fontSize = 11.sp, lineHeight = 13.8.sp),
        color = tallyColors.ink3,
        modifier = Modifier.padding(top = 4.dp),
    )
}

@Composable
private fun FormNote(text: String) {
    Text(text, style = TallyType.helper, color = tallyColors.ink3)
}

/** 生成前预览：只列 3 期，明确「现在一笔都不写」。 */
@Composable
private fun PreviewBlock(state: RecurringFormState) {
    val colors = tallyColors
    val amountMinor = state.amountMinor
    Column {
        Row(modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "未来 3 期预览",
                style = TallyType.eyebrow.copy(fontSize = 11.sp, lineHeight = 13.8.sp),
                color = colors.ink3,
            )
            Box(Modifier.weight(1f).height(1.dp).background(colors.line).padding(horizontal = 7.dp))
            Text("仅预览 · 不落库", fontSize = 10.sp, lineHeight = 12.5.sp, fontWeight = FontWeight.SemiBold, color = colors.ink3)
        }
        if (state.preview.isEmpty() || amountMinor == null) {
            TallyCard(corner = 15, contentPadding = 11.dp) {
                Text("填好金额与重复规则后，这里会列出接下来 3 期的应执行日。", style = TallyType.helper, color = colors.ink3)
            }
        } else {
            state.preview.forEachIndexed { index, dueAt ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("第${index + 1}期", fontSize = 10.sp, lineHeight = 12.5.sp, color = colors.ink3, modifier = Modifier.width(32.dp))
                    Text(
                        text = previewDayFormatter.format(millisToLocalDate(dueAt)),
                        style = TallyType.listTitle.copy(fontSize = 12.5.sp, lineHeight = 15.6.sp, fontWeight = FontWeight.SemiBold),
                        color = colors.ink,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = (if (state.type == BillType.INCOME) "+" else "−") + formatAmount(amountMinor),
                        style = TallyType.moneyList.copy(fontSize = 13.5.sp, lineHeight = 16.9.sp),
                        color = if (state.type == BillType.INCOME) colors.income else colors.expense,
                        textAlign = TextAlign.End,
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            val shape = RoundedCornerShape(15.dp)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(shape)
                    .background(colors.brand.copy(alpha = if (colors.isDark) 0.14f else 0.06f))
                    .border(1.dp, colors.brand.copy(alpha = 0.22f), shape)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                InlineIcon(iconKey = "info", contentDescription = null, size = 14.dp, tint = colors.brand)
                Text(
                    text = "这几期现在一笔都不写。账单只在到期日当天生成；不提前、不追溯，错过只补到应执行日。",
                    fontSize = 10.5.sp,
                    lineHeight = 16.sp,
                    color = colors.ink2,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun DayPicker(label: String, value: Int, onSelect: (Int) -> Unit, max: Int) {
    val colors = tallyColors
    Column {
        Text("$label · $value", style = TallyType.helper, color = colors.ink3)
        Spacer(Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            (1..max).forEach { day ->
                val selected = day == value
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (selected) colors.brand else colors.card)
                        .border(1.dp, if (selected) colors.brand else colors.line, RoundedCornerShape(8.dp))
                        .clickable { onSelect(day) }
                        .padding(horizontal = 9.dp, vertical = 5.dp),
                ) {
                    Text(
                        day.toString(),
                        style = TallyType.listTitle.copy(fontSize = 12.sp, lineHeight = 15.sp),
                        color = if (selected) colors.onBrand else colors.ink2,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    )
                }
            }
        }
    }
}

@Composable
private fun WeekdayPicker(value: Int, onSelect: (Int) -> Unit) {
    val colors = tallyColors
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        (1..7).forEach { day ->
            val selected = day == value
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (selected) colors.brand else colors.card)
                    .border(1.dp, if (selected) colors.brand else colors.line, RoundedCornerShape(8.dp))
                    .clickable { onSelect(day) }
                    .padding(vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    RecurrenceSpec.weekdayName(day),
                    style = TallyType.listTitle.copy(fontSize = 12.sp, lineHeight = 15.sp),
                    color = if (selected) colors.onBrand else colors.ink2,
                )
            }
        }
    }
}

private fun unitLabel(unit: RecurrenceUnit): String = when (unit) {
    RecurrenceUnit.DAILY -> "每日"
    RecurrenceUnit.WEEKLY -> "每周"
    RecurrenceUnit.MONTHLY -> "每月"
    RecurrenceUnit.LAST_DAY_OF_MONTH -> "月末"
    RecurrenceUnit.YEARLY -> "每年"
}
