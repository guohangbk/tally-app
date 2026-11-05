package io.github.guohangbk.tally.ui.installment

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.guohangbk.tally.di.rememberAppGraph
import io.github.guohangbk.tally.domain.formatAmount
import io.github.guohangbk.tally.domain.formatRateBp
import io.github.guohangbk.tally.domain.model.FeeMode
import io.github.guohangbk.tally.domain.model.InstallmentPeriod
import io.github.guohangbk.tally.domain.model.InstallmentPlanView
import io.github.guohangbk.tally.domain.model.InstallmentStatus
import io.github.guohangbk.tally.ui.common.tallyViewModel
import io.github.guohangbk.tally.ui.design.ButtonVariant
import io.github.guohangbk.tally.ui.design.IconTile
import io.github.guohangbk.tally.ui.design.InlineIcon
import io.github.guohangbk.tally.ui.design.TallyButton
import io.github.guohangbk.tally.ui.design.TallyCard
import io.github.guohangbk.tally.ui.design.TallyCardAction
import io.github.guohangbk.tally.ui.design.TallyConfirmDialog
import io.github.guohangbk.tally.ui.design.TallyEmptyState
import io.github.guohangbk.tally.ui.design.TallyMenuEntry
import io.github.guohangbk.tally.ui.design.TallyMoreMenu
import io.github.guohangbk.tally.ui.design.TallyTag
import io.github.guohangbk.tally.ui.design.TallyType
import io.github.guohangbk.tally.ui.design.TallyToastEffects
import io.github.guohangbk.tally.ui.design.TagTone
import io.github.guohangbk.tally.ui.design.tallyColors
import io.github.guohangbk.tally.ui.shell.TallyIconAction
import io.github.guohangbk.tally.ui.shell.TallyTopBar
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * P13 分期管理：卡片只回答「这是什么、进行到哪、能做什么」。
 * 本期应还、剩余本金、下期本金移进「明细」弹层顶部；补记到期先弹窗说清补几期、补多少。
 */
@Composable
fun InstallmentScreen(
    onBack: () -> Unit,
    onCreate: () -> Unit,
    onEdit: (String) -> Unit,
) {
    val graph = rememberAppGraph()
    val viewModel = tallyViewModel { InstallmentViewModel(graph) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val colors = tallyColors
    var confirm by remember { mutableStateOf<InstallmentConfirm?>(null) }
    var dueTargetId by remember { mutableStateOf<String?>(null) }
    var detailId by remember { mutableStateOf<String?>(null) }
    val today = LocalDate.now()
    val dueView = dueTargetId?.let { id -> state.items.firstOrNull { it.plan.id == id } }
    val detailView = detailId?.let { id -> state.items.firstOrNull { it.plan.id == id } }

    Column(modifier = Modifier.fillMaxSize().background(colors.paper)) {
        TallyTopBar(
            title = "分期管理",
            onBack = onBack,
            trailing = {
                TallyIconAction(
                    iconKey = "plus",
                    contentDescription = "新建分期",
                    onClick = onCreate,
                )
            },
        )

        // 提示改成贴底气泡，保留「操作没有完成」这个定性说法；两条通道都由 clearNotice 清。
        TallyToastEffects(
            notice = state.notice,
            error = state.error,
            errorTitle = "操作没有完成",
            onNoticeShown = viewModel::clearNotice,
            onErrorShown = viewModel::clearNotice,
        )

        Column(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 15.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            if (state.items.isEmpty()) {
                TallyEmptyState(
                    title = "还没有分期计划",
                    description = "把一笔大额支出拆成多期，每月自动生成一笔账单，末期自动校正分币尾差。",
                    iconKey = "slice",
                    action = {
                        TallyButton(
                            label = "新建分期",
                            onClick = onCreate,
                            variant = ButtonVariant.BRAND,
                            leadingIcon = "plus",
                            modifier = Modifier.width(170.dp),
                        )
                    },
                )
            }

            state.items.forEach { view ->
                InstallmentCard(
                    view = view,
                    busy = state.busy,
                    onDetail = { detailId = view.plan.id },
                    onDue = { dueTargetId = view.plan.id },
                    onEdit = { onEdit(view.plan.id) },
                    onSettle = { confirm = InstallmentConfirm.Settle(view) },
                    onDelete = { confirm = InstallmentConfirm.Delete(view) },
                )
            }
            Spacer(Modifier.height(20.dp))
        }
    }

    confirm?.let { action ->
        when (action) {
            is InstallmentConfirm.Settle -> TallyConfirmDialog(
                title = "提前结清「${action.view.plan.name}」",
                message = "剩余 ${action.view.remainingPeriods} 期不再生成，已生成的账单保留。",
                confirmLabel = "结清",
                onConfirm = {
                    viewModel.settleEarly(action.view.plan.id)
                    confirm = null
                },
                onDismiss = { confirm = null },
            )
            is InstallmentConfirm.Delete -> TallyConfirmDialog(
                title = "删除「${action.view.plan.name}」",
                message = "未生成的未来期不再生成，已生成的账单保留。",
                confirmLabel = "删除计划",
                destructive = true,
                onConfirm = {
                    viewModel.delete(action.view.plan.id)
                    confirm = null
                },
                onDismiss = { confirm = null },
            )
        }
    }

    dueView?.let { view ->
        InstallmentDueDialog(
            view = view,
            busy = state.busy,
            today = today,
            onConfirm = {
                viewModel.generateDue(view.plan.id)
                dueTargetId = null
            },
            onDismiss = { dueTargetId = null },
        )
    }

    detailView?.let { view ->
        InstallmentDetailSheet(
            view = view,
            today = today,
            onDismiss = { detailId = null },
            onDue = {
                // 先收起明细再弹补记：两层浮层叠着时焦点会打架。
                detailId = null
                dueTargetId = view.plan.id
            },
        )
    }
}

private sealed interface InstallmentConfirm {
    data class Settle(val view: InstallmentPlanView) : InstallmentConfirm
    data class Delete(val view: InstallmentPlanView) : InstallmentConfirm
}

private val monthDayFormatter = DateTimeFormatter.ofPattern("M月d日", Locale.CHINA)
private val shortDateFormatter = DateTimeFormatter.ofPattern("MM-dd", Locale.CHINA)
private val zone: ZoneId get() = ZoneId.systemDefault()

private fun monthDay(epochMillis: Long): String =
    Instant.ofEpochMilli(epochMillis).atZone(zone).format(monthDayFormatter)

private fun shortDate(epochMillis: Long): String =
    Instant.ofEpochMilli(epochMillis).atZone(zone).format(shortDateFormatter)

/** 与仓储同一条判定：已过应还日（含今天）且尚未入账。 */
private fun InstallmentPlanView.duePeriods(today: LocalDate): List<InstallmentPeriod> {
    val endOfToday = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    return periods.filter { it.index > plan.generatedPeriods && it.dueAt < endOfToday }
}

private fun overdueLabel(epochMillis: Long, today: LocalDate): String {
    val days = ChronoUnit.DAYS.between(
        Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate(),
        today,
    )
    return if (days <= 0L) "今天到期" else "已过期 $days 天"
}

@Composable
private fun InstallmentCard(
    view: InstallmentPlanView,
    busy: Boolean,
    onDetail: () -> Unit,
    onDue: () -> Unit,
    onEdit: () -> Unit,
    onSettle: () -> Unit,
    onDelete: () -> Unit,
) {
    val colors = tallyColors
    val plan = view.plan
    val settled = view.status != InstallmentStatus.ACTIVE
    // 卡片大数字与进度条都按「账上真实入账的期数」算，不按生成水位——
    // 否则删掉某一期之后，卡片仍报"已还 N 期"，而账本里并没有那笔钱
    val deposited = view.depositedCount
    val total = view.periods.size

    TallyCard(
        corner = 16,
        contentPadding = 12.dp,
        borderColor = if (settled) colors.line else colors.brand.copy(alpha = 0.22f),
    ) {
        Column {
            Row(verticalAlignment = Alignment.Top) {
                IconTile(iconKey = view.categoryIconKey ?: "slice", label = plan.name, size = 34.dp, corner = 10.dp)
                Spacer(Modifier.width(9.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        plan.name,
                        style = TallyType.listTitle,
                        color = if (settled) colors.ink3 else colors.ink,
                        maxLines = 1,
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        view.categoryPath,
                        fontSize = 11.sp,
                        lineHeight = 15.sp,
                        color = colors.ink3,
                        maxLines = 1,
                    )
                }
                Spacer(Modifier.width(6.dp))
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        "$deposited/$total",
                        style = TallyType.moneyBody.copy(fontSize = 22.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold),
                        color = if (settled) colors.ink3 else colors.ink,
                    )
                    Text("期", fontSize = 10.5.sp, lineHeight = 13.1.sp, color = colors.ink3, modifier = Modifier.padding(start = 2.dp, bottom = 1.dp))
                }
            }

            Spacer(Modifier.height(7.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                TallyTag(
                    text = view.ledgerName,
                    tone = TagTone.OUTLINE,
                    modifier = Modifier.widthIn(max = 118.dp),
                )
                view.cardTags().forEach { (text, tone) ->
                    TallyTag(text = text, tone = tone)
                }
                Spacer(Modifier.weight(1f))
                // 「已入账」按账上真实存在的账单数说，不按生成水位——被删掉的期不能算进来
                Text("已入账 ${view.depositedCount} 期", fontSize = 10.5.sp, lineHeight = 13.1.sp, color = colors.ink3)
            }

            Spacer(Modifier.height(7.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(5.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(colors.ink.copy(alpha = 0.08f)),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(view.progress.coerceIn(0f, 1f))
                        .height(5.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(if (settled) colors.slate else colors.brand),
                )
            }

            Spacer(Modifier.height(9.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(colors.line.copy(alpha = 0.6f)))
            Spacer(Modifier.height(10.dp))

            // 四格动作固定：明细 · 补记 · 编辑 · ⋯（提前结清与删除收进 ⋯）。
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                TallyCardAction(label = "明细", iconKey = "document", onClick = onDetail, modifier = Modifier.weight(1f))
                TallyCardAction(
                    label = "补记",
                    iconKey = "refresh",
                    onClick = onDue,
                    enabled = !settled && !busy,
                    modifier = Modifier.weight(1f),
                )
                TallyCardAction(label = "编辑", iconKey = "pencil", onClick = onEdit, modifier = Modifier.weight(1f))
                TallyMoreMenu(
                    entries = buildList {
                        if (!settled) add(TallyMenuEntry("提前结清") { onSettle() })
                        add(TallyMenuEntry("删除计划", danger = true) { onDelete() })
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** 卡第二行标签：账本之外最多两枚，状态/利率/手续费/尾差各取其一。 */
private fun InstallmentPlanView.cardTags(): List<Pair<String, TagTone>> {
    val tags = mutableListOf<Pair<String, TagTone>>()
    when (status) {
        InstallmentStatus.SETTLED_EARLY -> tags += "已提前结清" to TagTone.SLATE
        InstallmentStatus.FINISHED -> tags += "已完成" to TagTone.SLATE
        else -> {
            if (isMortgage) {
                tags += "${methodLabel(method)} ${formatRateBp(currentRateBp)}" to TagTone.NEUTRAL
            } else if (plan.feeMinor > 0L) {
                tags += (if (plan.feeMode == FeeMode.SEPARATE.name) "手续费单独成期" else "手续费并入每期") to TagTone.NEUTRAL
            }
            val balanced = periods.sumOf { it.amountMinor } == plan.totalMinor
            if (!balanced) tags += "尾差异常" to TagTone.DANGER
        }
    }
    return tags.take(2)
}

/**
 * 明细弹层：卡片让出来的三格指标落在顶部，下面才是每期表格。
 * 360 期的房贷也只在半屏里滚动，不把列表撑变形。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InstallmentDetailSheet(
    view: InstallmentPlanView,
    today: LocalDate,
    onDismiss: () -> Unit,
    onDue: () -> Unit,
) {
    val colors = tallyColors
    val plan = view.plan
    val settled = view.status != InstallmentStatus.ACTIVE
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = colors.paper,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 560.dp)
                .padding(horizontal = 15.dp),
        ) {
            Row(verticalAlignment = Alignment.Top) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(plan.name, style = TallyType.listTitle, color = colors.ink, maxLines = 1)
                    Spacer(Modifier.height(2.dp))
                    Text(view.sheetSubline(), fontSize = 11.sp, lineHeight = 13.8.sp, color = colors.ink3, maxLines = 1)
                }
                Text(
                    "${view.depositedCount} / ${view.periods.size} 期",
                    fontSize = 11.sp, lineHeight = 13.8.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.ink2,
                )
                Spacer(Modifier.width(8.dp))
                InlineIcon(
                    iconKey = "close",
                    contentDescription = "关闭",
                    size = 13.dp,
                    tint = colors.ink3,
                    modifier = Modifier.clickable(onClick = onDismiss),
                )
            }

            Spacer(Modifier.height(10.dp))
            Column(modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                MetricCells(view.metricCells(today))
                Spacer(Modifier.height(11.dp))
                PeriodTable(view)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    val balanced = view.periods.sumOf { it.amountMinor } == plan.totalMinor
                    TallyTag(
                        text = if (balanced) "Σ 各期 = ${formatAmount(plan.totalMinor)} ✓" else "尾差异常",
                        tone = if (balanced) TagTone.OUTLINE else TagTone.DANGER,
                    )
                    Spacer(Modifier.weight(1f))
                    if (!view.isMortgage && plan.feeMinor > 0L) {
                        TallyTag(
                            text = if (plan.feeMode == FeeMode.SEPARATE.name) "手续费单独成期" else "手续费并入每期",
                            tone = TagTone.OUTLINE,
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                TallyButton(
                    label = "关闭",
                    onClick = onDismiss,
                    variant = ButtonVariant.GHOST,
                    height = 34,
                    modifier = Modifier.weight(1f),
                )
                TallyButton(
                    label = "补记到期",
                    onClick = onDue,
                    variant = ButtonVariant.BRAND,
                    leadingIcon = "refresh",
                    enabled = !settled,
                    height = 34,
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(10.dp))
        }
    }
}

/** 弹层头部的副标题：房贷给还款方式与利率，普通分期给每期与末期。 */
private fun InstallmentPlanView.sheetSubline(): String = if (isMortgage) {
    "${methodLabel(method)} · 年利率 ${formatRateBp(currentRateBp)}"
} else {
    "每期 ${formatAmount(plan.perPeriodMinor)}" +
        if (plan.tailMinor != plan.perPeriodMinor) " · 末期 ${formatAmount(plan.tailMinor)}" else ""
}

private enum class MetricTone { PLAIN, BRAND, EXPENSE }

private data class Metric(
    val label: String,
    val value: String,
    val note: String,
    val tone: MetricTone = MetricTone.PLAIN,
)

/** 三格：房贷讲本金，普通分期讲已还与剩余期数。 */
private fun InstallmentPlanView.metricCells(today: LocalDate): List<Metric> {
    val next = nextPeriod
    val dueNote = next?.let {
        val status = if (it.dueAt <= today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()) "已到期" else "未到期"
        "${monthDay(it.dueAt)} · $status"
    } ?: if (depositedCount >= periods.size) "全部已入账" else "已入账 $depositedCount / ${periods.size} 期"
    val first = Metric(
        label = "本期应还",
        value = formatAmount(next?.amountMinor ?: 0L),
        note = dueNote,
        tone = MetricTone.EXPENSE,
    )
    if (!isMortgage) {
        return listOf(
            first,
            Metric(
                label = "已还金额",
                value = formatAmount(paidMinor),
                note = "已入账 ${depositedCount} 期",
                tone = MetricTone.BRAND,
            ),
            Metric(
                label = "剩余期数",
                value = "${remainingPeriods} 期",
                note = "末期 ${formatAmount(periods.lastOrNull()?.amountMinor ?: plan.tailMinor)}",
            ),
        )
    }
    val principal = next?.principalMinor ?: 0L
    val interest = next?.interestMinor ?: 0L
    return listOf(
        first,
        Metric(
            label = "剩余本金",
            value = formatAmount(remainingPrincipalMinor),
            note = "已还 ${formatAmount(paidMinor)}",
        ),
        Metric(
            label = "下期本金",
            value = formatAmount(principal),
            note = "利息 ${formatAmount(interest)}",
            tone = MetricTone.BRAND,
        ),
    )
}

@Composable
private fun MetricCells(cells: List<Metric>) {
    val colors = tallyColors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(colors.line)
            .padding(1.dp),
        horizontalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        cells.forEach { cell ->
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(11.dp))
                    .background(colors.card)
                    .padding(horizontal = 9.dp, vertical = 8.dp),
            ) {
                Text(
                    cell.label,
                    fontSize = 9.5.sp, lineHeight = 11.9.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.08.em,
                    color = colors.ink3,
                    maxLines = 1,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    cell.value,
                    style = TallyType.moneyBody.copy(fontSize = 15.5.sp, lineHeight = 19.4.sp),
                    color = when (cell.tone) {
                        MetricTone.PLAIN -> colors.ink
                        MetricTone.BRAND -> colors.brand
                        MetricTone.EXPENSE -> colors.expense
                    },
                    maxLines = 1,
                )
                Spacer(Modifier.height(2.dp))
                Text(cell.note, fontSize = 9.5.sp, lineHeight = 13.sp, color = colors.ink3, maxLines = 1)
            }
        }
    }
}

@Composable
private fun PeriodTable(view: InstallmentPlanView) {
    val colors = tallyColors
    val mortgage = view.isMortgage
    val lastAmount = view.periods.lastOrNull()?.amountMinor
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
            TableHead("期", Modifier.width(24.dp))
            TableHead("到期日", Modifier.weight(1f))
            if (mortgage) {
                TableHead("本金", Modifier.width(62.dp))
                TableHead("利息", Modifier.width(54.dp))
            }
            TableHead("金额", Modifier.width(74.dp))
            TableHead("状态", Modifier.width(56.dp))
        }
        view.periods.forEach { period ->
            val deposited = period.index in view.depositedPeriods
            // 生成过、但账上那笔已经不在了（用户删了它）：不能继续报「已入账」，
            // 否则明细说有钱、账本里没这笔，汇总就悄悄少算。它也不会被自动补记——
            // 用户删掉某一期就是「这一期不要了」，补执行不该把它顶回来。
            val removed = !deposited && period.index <= view.plan.generatedPeriods
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                TableCell(period.index.toString(), Modifier.width(24.dp), colors.ink3)
                TableCell(shortDate(period.dueAt), Modifier.weight(1f), colors.ink2)
                if (mortgage) {
                    TableCell(formatAmount(period.principalMinor ?: 0L), Modifier.width(62.dp), colors.ink2)
                    TableCell(formatAmount(period.interestMinor ?: 0L), Modifier.width(54.dp), colors.ink2)
                }
                TableCell(formatAmount(period.amountMinor), Modifier.width(74.dp), colors.ink)
                val label = when {
                    deposited -> "已入账"
                    removed -> "已删除"
                    period.amountMinor == lastAmount -> "末期"
                    period.isFee -> "手续费"
                    else -> "待生成"
                }
                TableCell(label, Modifier.width(56.dp), if (deposited) colors.brand else colors.ink2)
            }
        }
    }
}

@Composable
private fun TableHead(text: String, modifier: Modifier) {
    Text(text, fontSize = 10.sp, lineHeight = 12.5.sp, color = tallyColors.ink3, modifier = modifier, maxLines = 1)
}

@Composable
private fun TableCell(text: String, modifier: Modifier, color: Color) {
    Text(text, fontSize = 10.5.sp, lineHeight = 13.1.sp, color = color, modifier = modifier, maxLines = 1)
}

/** 补记到期：先说清补几期、补多少钱、最早一期过期多久，再让用户按。 */
@Composable
private fun InstallmentDueDialog(
    view: InstallmentPlanView,
    busy: Boolean,
    today: LocalDate,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = tallyColors
    val due = view.duePeriods(today)
    val totalMinor = due.sumOf { it.amountMinor }
    val interestMinor = due.sumOf { it.interestMinor ?: 0L }
    val first = due.firstOrNull()
    TallyConfirmDialog(
        title = "补记到期",
        message = "把已过应还日但还没入账的期一次补上，账单日期仍是应还日，不挪到今天。",
        confirmLabel = if (due.isEmpty()) "没有可补的期" else "补记 ${due.size} 期",
        confirmEnabled = due.isNotEmpty() && !busy,
        onConfirm = onConfirm,
        onDismiss = onDismiss,
        content = {
            Column {
                MetricCells(
                    listOf(
                        Metric(
                            label = "可补期数",
                            value = "${due.size} 期",
                            note = if (first == null) "没有到期未入账" else "第 ${first.index}–${due.last().index} 期",
                            tone = MetricTone.BRAND,
                        ),
                        Metric(
                            label = "合计",
                            value = formatAmount(totalMinor),
                            note = if (view.isMortgage) "含利息 ${formatAmount(interestMinor)}" else "不含利息",
                        ),
                        Metric(
                            label = "最早一期",
                            value = first?.let { shortDate(it.dueAt) } ?: "—",
                            note = first?.let { overdueLabel(it.dueAt, today) } ?: "—",
                        ),
                    ),
                )
                Spacer(Modifier.height(9.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(colors.glow)
                        .border(1.dp, colors.gold.copy(alpha = 0.24f), RoundedCornerShape(10.dp))
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                ) {
                    Text(
                        "补记后仍可在明细里逐期改或删，同一期不会被重复入账。",
                        fontSize = 10.5.sp,
                        lineHeight = 15.sp,
                        color = colors.onGlow,
                    )
                }
            }
        },
    )
}
