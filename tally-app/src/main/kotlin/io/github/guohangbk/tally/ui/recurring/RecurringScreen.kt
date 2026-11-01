package io.github.guohangbk.tally.ui.recurring

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.guohangbk.tally.di.rememberAppGraph
import io.github.guohangbk.tally.domain.formatAmount
import io.github.guohangbk.tally.domain.model.RecurringPeriodRow
import io.github.guohangbk.tally.domain.model.RecurringRuleView
import io.github.guohangbk.tally.domain.model.RecurringStatus
import io.github.guohangbk.tally.ui.common.tallyViewModel
import io.github.guohangbk.tally.ui.design.ButtonVariant
import io.github.guohangbk.tally.ui.design.CardActionTone
import io.github.guohangbk.tally.ui.design.IconTile
import io.github.guohangbk.tally.ui.design.IconTone
import io.github.guohangbk.tally.ui.design.InlineIcon
import io.github.guohangbk.tally.ui.design.MoneySign
import io.github.guohangbk.tally.ui.design.MoneyText
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * P12 周期记账：任务以「下次执行日期」为锚点，待补账以金晕卡浮出，
 * 超过阈值的补账必须由用户确认，不静默改账。
 */
@Composable
fun RecurringScreen(onBack: () -> Unit, onCreate: () -> Unit, onEdit: (String) -> Unit = {}) {
    val graph = rememberAppGraph()
    val viewModel = tallyViewModel { RecurringViewModel(graph) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val detail by viewModel.detail.collectAsStateWithLifecycle()
    val periods by viewModel.periods.collectAsStateWithLifecycle()
    val colors = tallyColors
    var confirmAction by remember { mutableStateOf<ConfirmAction?>(null) }

    // 状态写在每张卡上，所以列表不再按状态分段：进行中排前面，已结束沉底。
    val items = state.items.sortedBy {
        when (it.status) {
            RecurringStatus.ACTIVE -> 0
            RecurringStatus.PAUSED -> 1
            else -> 2
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(colors.paper)) {
        TallyTopBar(
            title = "周期记账",
            onBack = onBack,
            trailing = {
                TallyIconAction(
                    iconKey = "plus",
                    contentDescription = "新建周期任务",
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
            if (state.pendingTotal > 0) {
                PendingCatchUpCard(
                    count = state.pendingTotal,
                    amountMinor = state.pendingAmountMinor,
                    onCatchUp = viewModel::catchUpAll,
                    busy = state.busy,
                )
            }

            if (state.items.isEmpty()) {
                TallyEmptyState(
                    title = "还没有周期任务",
                    description = "房租、话费、会员订阅这类固定支出，设一次就会按月自动记账。",
                    iconKey = "repeat",
                    action = {
                        TallyButton(
                            label = "新建周期任务",
                            onClick = onCreate,
                            variant = ButtonVariant.BRAND,
                            leadingIcon = "plus",
                            modifier = Modifier.width(180.dp),
                        )
                    },
                )
            }

            items.forEach { view ->
                RecurringCard(
                    view = view,
                    onDetail = { viewModel.openDetail(view) },
                    onEdit = { onEdit(view.rule.id) },
                    onCatchUp = if (view.pendingCount > 0) ({ viewModel.catchUp(view.rule.id) }) else null,
                    onPause = if (view.status == RecurringStatus.ACTIVE) {
                        ({ viewModel.setStatus(view.rule.id, RecurringStatus.PAUSED) })
                    } else {
                        null
                    },
                    onResume = if (view.status == RecurringStatus.PAUSED) {
                        ({ viewModel.setStatus(view.rule.id, RecurringStatus.ACTIVE) })
                    } else {
                        null
                    },
                    onFinish = if (view.status != RecurringStatus.FINISHED) {
                        ({ confirmAction = ConfirmAction.Finish(view) })
                    } else {
                        null
                    },
                    onDelete = { confirmAction = ConfirmAction.Delete(view) },
                )
            }
            Spacer(Modifier.height(20.dp))
        }
    }

    confirmAction?.let { action ->
        when (action) {
            is ConfirmAction.Finish -> TallyConfirmDialog(
                title = "结束「${action.view.rule.name}」",
                message = "结束后不再生成新账单，已生成的账单会保留。",
                confirmLabel = "结束任务",
                onConfirm = {
                    viewModel.setStatus(action.view.rule.id, RecurringStatus.FINISHED)
                    confirmAction = null
                },
                onDismiss = { confirmAction = null },
            )
            is ConfirmAction.Delete -> TallyConfirmDialog(
                title = "删除「${action.view.rule.name}」",
                message = "任务会从列表移除，已生成的账单保留，不影响历史账目。",
                confirmLabel = "删除任务",
                destructive = true,
                onConfirm = {
                    viewModel.delete(action.view.rule.id)
                    confirmAction = null
                },
                onDismiss = { confirmAction = null },
            )
        }
    }

    detail?.let { view ->
        RecurringDetailSheet(
            view = view,
            periods = periods,
            onDismiss = viewModel::closeDetail,
            onCatchUp = if (view.pendingCount > 0) ({ viewModel.catchUp(view.rule.id) }) else null,
        )
    }
}

private sealed interface ConfirmAction {
    data class Finish(val view: RecurringRuleView) : ConfirmAction
    data class Delete(val view: RecurringRuleView) : ConfirmAction
}

@Composable
private fun PendingCatchUpCard(
    count: Int,
    amountMinor: Long,
    onCatchUp: () -> Unit,
    busy: Boolean,
) {
    val colors = tallyColors
    TallyCard(corner = 16, borderColor = colors.gold.copy(alpha = 0.4f), contentPadding = 12.dp) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconTile(iconKey = "clock", label = "待补账", size = 32.dp, corner = 11.dp, tone = IconTone.GLOW)
                Spacer(Modifier.width(9.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("检测到 $count 笔待补账单", style = TallyType.listTitle, color = colors.ink)
                    Text("错过的执行日，账单日期仍是应执行日", style = TallyType.helper, color = colors.ink3)
                }
                TallyTag(text = "¥${formatAmount(amountMinor)}", tone = TagTone.GOLD)
            }
            Spacer(Modifier.height(9.dp))
            TallyButton(
                label = if (busy) "正在补记…" else "全部补记",
                onClick = onCatchUp,
                variant = ButtonVariant.GOLD,
                enabled = !busy,
                height = 36,
                leadingIcon = "refresh",
            )
        }
    }
}

/**
 * 周期任务卡：槽位与分期管理一一对应——瓦 + 名称（分类在标题下）+ 右侧一个衬线数；
 * 标签行给账本 / 状态 / 频率（有代补时再加一枚）；期限与「下次」并排一行；
 * 动作固定四格「明细 · 补记 · 编辑 · ⋯」，暂停 / 恢复 / 结束 / 删除都收进 ⋯。
 * 瓦不加品牌底（状态已经有标签表达），「已生成 N 期」从卡面挪进明细弹层（2026-10-28 走查）。
 */
@Composable
private fun RecurringCard(
    view: RecurringRuleView,
    onDetail: () -> Unit,
    onEdit: () -> Unit,
    onCatchUp: (() -> Unit)? = null,
    onPause: (() -> Unit)? = null,
    onResume: (() -> Unit)? = null,
    onFinish: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
) {
    val colors = tallyColors
    val rule = view.rule
    val finished = view.status == RecurringStatus.FINISHED
    val catchUpEnabled = view.pendingCount > 0 && onCatchUp != null
    TallyCard(
        corner = 16,
        contentPadding = 12.dp,
        borderColor = when {
            view.pendingCount > 0 -> colors.gold.copy(alpha = 0.38f)
            view.status == RecurringStatus.ACTIVE -> colors.brand.copy(alpha = 0.22f)
            else -> colors.line
        },
    ) {
        Column {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                IconTile(
                    iconKey = view.categoryIcon ?: "repeat",
                    label = rule.name,
                    size = 34.dp,
                    corner = 10.dp,
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = rule.name,
                        style = TallyType.listTitle,
                        color = if (finished) colors.ink3 else colors.ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = view.categoryPath,
                        fontSize = 11.sp,
                        lineHeight = 15.sp,
                        color = colors.ink3,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                // 右上角两格：状态标签在上、每期金额在下——状态是这行的身份，金额要够大才看得清。
                Column(horizontalAlignment = Alignment.End) {
                    TallyTag(
                        text = when (view.status) {
                            RecurringStatus.ACTIVE -> "进行中"
                            RecurringStatus.PAUSED -> "已暂停"
                            else -> "已结束"
                        },
                        tone = when (view.status) {
                            RecurringStatus.ACTIVE -> TagTone.BRAND
                            RecurringStatus.PAUSED -> TagTone.SLATE
                            else -> TagTone.SLATE
                        },
                    )
                    Spacer(Modifier.height(5.dp))
                    MoneyText(
                        amountMinor = rule.amountMinor,
                        sign = MoneySign.NONE,
                        style = TallyType.moneyBody.copy(fontSize = 22.sp, lineHeight = 24.sp),
                        color = if (rule.type == "INCOME") colors.income else colors.expense,
                        showCurrency = false,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                TallyTag(
                    text = view.ledgerName,
                    tone = TagTone.OUTLINE,
                    modifier = Modifier.widthIn(max = 118.dp),
                )
                TallyTag(text = view.spec.summary)
                if (view.pendingCount > 0) TallyTag(text = "待补 ${view.pendingCount} 笔", tone = TagTone.GOLD)
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = view.endDescription,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    color = colors.ink3,
                    maxLines = 1,
                )
                Spacer(Modifier.weight(1f))
                if (!finished) {
                    Text(
                        text = "下次 ${rule.nextDueText()}",
                        fontSize = 11.sp,
                        lineHeight = 15.sp,
                        color = if (view.status == RecurringStatus.ACTIVE) colors.brand else colors.ink3,
                    )
                }
            }
            Spacer(Modifier.height(9.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(colors.line.copy(alpha = 0.6f)))
            Spacer(Modifier.height(9.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                TallyCardAction(label = "明细", iconKey = "document", onClick = onDetail, modifier = Modifier.weight(1f))
                TallyCardAction(
                    label = "补记",
                    iconKey = "refresh",
                    onClick = { onCatchUp?.invoke() },
                    enabled = catchUpEnabled,
                    tone = if (catchUpEnabled) CardActionTone.GOLD else CardActionTone.NEUTRAL,
                    contentDescription = if (catchUpEnabled) "补记 ${view.pendingCount} 期" else "没有待补的期",
                    modifier = Modifier.weight(1f),
                )
                TallyCardAction(label = "编辑", iconKey = "pencil", onClick = onEdit, modifier = Modifier.weight(1f))
                TallyMoreMenu(
                    entries = buildList {
                        if (onPause != null) add(TallyMenuEntry("暂停任务") { onPause() })
                        if (onResume != null) add(TallyMenuEntry("恢复任务") { onResume() })
                        if (onFinish != null) add(TallyMenuEntry("结束任务") { onFinish() })
                        if (onDelete != null) add(TallyMenuEntry("删除任务", danger = true) { onDelete() })
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/**
 * 明细弹层：这一条任务已经生成的每一期——期次、日期、分类与备注、金额，末尾一行合计。
 * 「已生成 N 期」与期限都在这张弹层里说清楚，卡面不再堆这些数。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RecurringDetailSheet(
    view: RecurringRuleView,
    periods: List<RecurringPeriodRow>,
    onDismiss: () -> Unit,
    onCatchUp: (() -> Unit)?,
) {
    val colors = tallyColors
    val rule = view.rule
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = colors.card,
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconTile(iconKey = view.categoryIcon ?: "repeat", label = rule.name, size = 34.dp, corner = 10.dp)
                Spacer(Modifier.width(9.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(rule.name, style = TallyType.listTitle, color = colors.ink, maxLines = 1)
                    Text(
                        text = "${view.categoryPath} · ${view.spec.summary}",
                        fontSize = 11.sp,
                        lineHeight = 15.sp,
                        color = colors.ink3,
                        maxLines = 1,
                    )
                }
                Text(
                    text = "已生成 ${periods.size} 期",
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
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TallyTag(text = view.endDescription, tone = TagTone.OUTLINE)
                TallyTag(text = "每期 ${formatAmount(rule.amountMinor)}", tone = TagTone.OUTLINE)
                Spacer(Modifier.weight(1f))
                if (view.pendingCount > 0) TallyTag(text = "待补 ${view.pendingCount} 笔", tone = TagTone.GOLD)
            }
            Spacer(Modifier.height(4.dp))
            Column(modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                if (periods.isEmpty()) {
                    Text(
                        text = "还没有生成过账单",
                        style = TallyType.helper,
                        color = colors.ink3,
                        modifier = Modifier.padding(vertical = 14.dp),
                    )
                }
                periods.forEachIndexed { position, row ->
                    if (position > 0) {
                        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.line.copy(alpha = 0.5f)))
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "第 ${if (row.index > 0) row.index else position + 1} 期",
                            fontSize = 11.5.sp, lineHeight = 14.4.sp,
                            color = colors.ink3,
                            modifier = Modifier.width(54.dp),
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(formatPeriodDate(row.occurredAt), fontSize = 13.sp, lineHeight = 16.3.sp, color = colors.ink, maxLines = 1)
                            Text(
                                text = row.categoryName + (row.note?.let { " · $it" } ?: ""),
                                fontSize = 11.sp,
                                lineHeight = 15.sp,
                                color = colors.ink3,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        MoneyText(
                            amountMinor = row.amountMinor,
                            sign = MoneySign.NONE,
                            style = TallyType.moneyList,
                            color = if (row.isIncome) colors.income else colors.expense,
                            showCurrency = false,
                        )
                    }
                }
                if (periods.isNotEmpty()) {
                    Box(Modifier.fillMaxWidth().height(1.dp).background(colors.line.copy(alpha = 0.5f)))
                    Spacer(Modifier.height(9.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("合计", fontSize = 11.5.sp, lineHeight = 14.4.sp, color = colors.ink3)
                        Spacer(Modifier.weight(1f))
                        Text(
                            text = formatAmount(periods.sumOf { it.amountMinor }),
                            style = TallyType.moneyList,
                            color = colors.ink,
                        )
                    }
                }
            }
            if (onCatchUp != null) {
                Spacer(Modifier.height(12.dp))
                TallyButton(
                    label = "补记 ${view.pendingCount} 期",
                    onClick = onCatchUp,
                    variant = ButtonVariant.GOLD,
                    leadingIcon = "refresh",
                    height = 34,
                )
            }
        }
    }
}

/** 明细里一期的日期：跨年的任务要带年份，否则「10-01」会被读成本年。 */
private fun formatPeriodDate(timestamp: Long): String =
    SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(timestamp))

