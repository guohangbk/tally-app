package io.github.guohangbk.tally.ui.installment

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.guohangbk.tally.di.rememberAppGraph
import io.github.guohangbk.tally.domain.formatAmount
import io.github.guohangbk.tally.domain.formatRateBp
import io.github.guohangbk.tally.domain.model.FeeMode
import io.github.guohangbk.tally.domain.model.InstallmentStatus
import io.github.guohangbk.tally.domain.model.RepaymentMethod
import io.github.guohangbk.tally.domain.schedule.InstallmentSchedule
import io.github.guohangbk.tally.ui.common.CategoryPickerSheet
import io.github.guohangbk.tally.ui.common.LedgerPickerSheet
import io.github.guohangbk.tally.ui.common.TallyDatePickerDialog
import io.github.guohangbk.tally.ui.common.TallyFormInput
import io.github.guohangbk.tally.ui.common.formatDate
import io.github.guohangbk.tally.ui.common.tallyViewModel
import io.github.guohangbk.tally.ui.design.ButtonVariant
import io.github.guohangbk.tally.ui.design.InlineIcon
import io.github.guohangbk.tally.ui.design.TagTone
import io.github.guohangbk.tally.ui.design.TallyButton
import io.github.guohangbk.tally.ui.design.TallyCard
import io.github.guohangbk.tally.ui.design.TallyEmptyState
import io.github.guohangbk.tally.ui.design.TallyListRow
import io.github.guohangbk.tally.ui.design.TallyRowChevron
import io.github.guohangbk.tally.ui.design.TallySegmented
import io.github.guohangbk.tally.ui.design.TallyTag
import io.github.guohangbk.tally.ui.design.TallyToastEffects
import io.github.guohangbk.tally.ui.design.TallyType
import io.github.guohangbk.tally.ui.design.tallyColors
import io.github.guohangbk.tally.ui.shell.TallyTopBar

/**
 * P13 新建 / 编辑分期共用一个页面。编辑时金额、期数、首期入账日变成带锁的只读条，
 * 房贷的改利率并入利率区；类型不可改——已生成的期按原类型排期。
 */
@Composable
fun InstallmentFormScreen(planId: String? = null, onBack: () -> Unit) {
    val graph = rememberAppGraph()
    val viewModel = tallyViewModel(key = "installment-form-${planId ?: "new"}") {
        InstallmentFormViewModel(graph, planId)
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val colors = tallyColors
    var showCategory by remember { mutableStateOf(false) }
    var showLedger by remember { mutableStateOf(false) }
    var showDate by remember { mutableStateOf(false) }

    LaunchedEffect(state.saved) {
        if (state.saved) onBack()
    }

    // 计划已经不在了：给一张空态，不把「读不到」报成「保存没有完成」。
    if (state.missing) {
        Column(modifier = Modifier.fillMaxSize().background(colors.paper)) {
            TallyTopBar(title = "编辑分期", onBack = onBack)
            TallyEmptyState(
                title = "这个分期计划不在了",
                description = "它可能已经被删除，回列表看看别的计划。",
                iconKey = "slice",
                action = {
                    TallyButton(
                        label = "返回列表",
                        onClick = onBack,
                        variant = ButtonVariant.BRAND,
                        modifier = Modifier.width(170.dp),
                    )
                },
            )
        }
        return
    }

    Column(modifier = Modifier.fillMaxSize().background(colors.paper)) {
        TallyTopBar(
            title = if (state.isEditing) "编辑分期" else "新建分期",
            onBack = onBack,
            trailing = {
                TallyTag(
                    text = state.ledgers.firstOrNull { it.ledger.id == state.ledgerId }?.ledger?.name ?: "默认账本",
                    tone = TagTone.OUTLINE,
                    modifier = Modifier.clickable(onClick = { showLedger = true }),
                )
            },
        )

        TallyToastEffects(
            notice = null,
            error = state.error,
            errorTitle = if (state.isEditing) "保存没有完成" else "还不能创建",
            onErrorShown = viewModel::clearError,
        )

        Column(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 15.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            TallySegmented(
                options = listOf(
                    InstallmentScenario.PLAIN to "普通分期",
                    InstallmentScenario.MORTGAGE to "房贷",
                ),
                selected = state.scenario,
                // 类型进编辑页就锁住：已生成的期数按原类型排期，切换没有意义。
                onSelect = { if (!state.isEditing) viewModel.setScenario(it) },
            )

            if (state.isEditing && state.originalPlan != null) {
                LockedField(
                    label = if (state.isMortgage) "贷款金额" else "总金额",
                    value = "¥${formatAmount(state.originalPlan?.principalMinor ?: 0L)}",
                )
                LockedField(
                    label = if (state.isMortgage) "期数（月）" else "期数",
                    value = "${state.originalPlan?.periodCount ?: 0} 期",
                )
                LockedField(label = "首期入账日", value = formatDate(state.firstOccurredAt))
                if (!state.isMortgage && (state.originalPlan?.feeMinor ?: 0L) > 0L) {
                    LockedField(
                        label = "手续费",
                        value = "¥${formatAmount(state.originalPlan?.feeMinor ?: 0L)}",
                    )
                }
            } else {
                Text(
                    text = if (state.isMortgage) {
                        "创建后可改名称、账本、分类、备注与利率；已入账的期不变。"
                    } else {
                        "创建后可改名称、账本、分类与备注；已入账的期不变。"
                    },
                    style = TallyType.helper,
                    color = colors.ink3,
                )
            }

            TallyFormInput(
                label = "计划名称",
                value = state.name,
                placeholder = if (state.isMortgage) "例如：首套房贷" else "例如：iPhone 17 Pro",
                onValueChange = viewModel::setName,
            )

            if (!state.isEditing) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TallyFormInput(
                        label = if (state.isMortgage) "贷款金额" else "总金额",
                        value = state.principalText,
                        placeholder = "0.00",
                        onValueChange = viewModel::setPrincipal,
                        prefix = "¥",
                        numeric = true,
                        modifier = Modifier.weight(1f),
                    )
                    TallyFormInput(
                        label = if (state.isMortgage) "期数（月）" else "期数",
                        value = state.periodText,
                        placeholder = if (state.isMortgage) "360" else "12",
                        onValueChange = viewModel::setPeriods,
                        numeric = true,
                        modifier = Modifier.width(96.dp),
                    )
                }

                if (state.isMortgage) {
                    TallySegmented(
                        options = listOf(
                            RepaymentMethod.EQUAL_PAYMENT to "等额本息",
                            RepaymentMethod.EQUAL_PRINCIPAL to "等额本金",
                        ),
                        selected = state.repaymentMethod,
                        onSelect = viewModel::setRepaymentMethod,
                    )
                } else {
                    TallyFormInput(
                        label = "手续费（可选）",
                        value = state.feeText,
                        placeholder = "0.00",
                        onValueChange = viewModel::setFee,
                        prefix = "¥",
                        numeric = true,
                    )
                    TallySegmented(
                        options = listOf(FeeMode.INCLUDED to "手续费并入每期", FeeMode.SEPARATE to "手续费单独成期"),
                        selected = state.feeMode,
                        onSelect = viewModel::setFeeMode,
                    )
                    TallySegmented(
                        options = listOf(
                            1 to "每月",
                            2 to "每 2 月",
                            3 to "每季",
                            6 to "每半年",
                            12 to "每年",
                        ),
                        selected = state.intervalMonths,
                        onSelect = viewModel::setInterval,
                    )
                }
            }

            // 利率区：新建时只给房贷用；编辑时房贷并进这里（原「改利率」页已删），结清后只读。
            if (state.isMortgage && state.loaded && (!state.isEditing || state.status == InstallmentStatus.ACTIVE)) {
                SectionLabel("利率")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TallyFormInput(
                        label = "LPR（%）",
                        value = state.lprText,
                        placeholder = "3.45",
                        onValueChange = viewModel::setLpr,
                        numeric = true,
                        modifier = Modifier.weight(1f),
                    )
                    TallyFormInput(
                        label = "加点（BP）",
                        value = state.bpText,
                        placeholder = "0",
                        onValueChange = viewModel::setBp,
                        modifier = Modifier.weight(1f),
                    )
                }
                if (state.isEditing) {
                    RatePreviewCard(state)
                }
            } else if (state.isMortgage && state.loaded) {
                LockedField(label = "年利率", value = formatRateBp(state.currentRateBp))
            }

            TallyListRow(
                title = state.categories.firstOrNull { it.id == state.categoryL1Id }?.name ?: "选择分类",
                subtitle = "分类",
                iconKey = "tag",
                onClick = { showCategory = true },
                trailing = { TallyRowChevron() },
            )
            if (!state.isEditing) {
                TallyListRow(
                    title = formatDate(state.firstOccurredAt),
                    subtitle = "首期入账日",
                    iconKey = "calendar",
                    onClick = { showDate = true },
                    trailing = { TallyRowChevron() },
                )
            }

            state.preview?.let { PreviewCard(it) }

            TallyFormInput(label = "备注（可选）", value = state.note, placeholder = "写点什么都行", onValueChange = viewModel::setNote)

            Spacer(Modifier.height(4.dp))
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 15.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TallyButton(label = "取消", onClick = onBack, variant = ButtonVariant.GHOST, height = 40, modifier = Modifier.weight(1f))
            TallyButton(
                label = when {
                    state.saving -> "正在保存…"
                    state.isEditing -> "保存"
                    else -> "创建分期"
                },
                onClick = viewModel::save,
                variant = ButtonVariant.BRAND,
                enabled = state.loaded && state.canSave && !state.saving,
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
    if (showDate) {
        TallyDatePickerDialog(initialDate = state.firstOccurredAt, onDismiss = { showDate = false }, onConfirm = {
            viewModel.setFirstOccurredAt(it)
            showDate = false
        })
    }
}

/** 虚线只读条：金额、期数、首期入账日这些「改了就和已生成账单对不上」的字段。 */
@Composable
private fun LockedField(label: String, value: String) {
    val colors = tallyColors
    val shape = RoundedCornerShape(11.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.paper)
            .drawBehind {
                drawRoundRect(
                    color = colors.line,
                    cornerRadius = CornerRadius(11.dp.toPx()),
                    style = Stroke(
                        width = 1.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())),
                    ),
                )
            }
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        InlineIcon(iconKey = "lock", contentDescription = null, size = 11.dp, tint = colors.ink3)
        Text(label, fontSize = 12.sp, lineHeight = 15.sp, color = colors.ink2, modifier = Modifier.weight(1f))
        Text(
            value,
            fontSize = 13.sp, lineHeight = 16.3.sp,
            fontWeight = FontWeight.SemiBold,
            color = colors.ink,
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        fontSize = 10.sp, lineHeight = 12.5.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.16.em,
        color = tallyColors.ink3,
        modifier = Modifier.padding(top = 2.dp),
    )
}

/** 改利率预览：改后每期与改前一比，说明只落在未生成的期上。 */
@Composable
private fun RatePreviewCard(state: InstallmentFormState) {
    val colors = tallyColors
    val after = state.previewNextMinor ?: state.originalNextMinor ?: 0L
    val before = state.originalNextMinor ?: after
    val diff = after - before
    val rateValid = state.annualRateBp != null
    val remaining = (state.originalPeriods.size - state.generatedPeriods).coerceAtLeast(0)
    TallyCard(corner = 15, contentPadding = 11.dp, borderColor = colors.brand.copy(alpha = 0.22f)) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("改后每期 ${formatAmount(after)}", style = TallyType.listTitle, color = colors.ink)
                Spacer(Modifier.weight(1f))
                TallyTag(
                    text = when {
                        !rateValid -> "利率不完整"
                        diff < 0 -> "少 ${formatAmount(-diff)}"
                        diff > 0 -> "多 ${formatAmount(diff)}"
                        else -> "未改动"
                    },
                    tone = when {
                        !rateValid -> TagTone.DANGER
                        diff == 0L -> TagTone.OUTLINE
                        else -> TagTone.BRAND
                    },
                )
            }
            Spacer(Modifier.height(3.dp))
            Text(
                "改前 ${formatAmount(before)} · 剩余利息 ${formatAmount(state.previewInterestMinor)}" +
                    "（当前 ${formatAmount(state.currentInterestMinor)}）",
                style = TallyType.helper,
                color = colors.ink3,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "只影响未生成的 $remaining 期，已入账的 ${state.generatedPeriods} 期不改。",
                fontSize = 10.5.sp,
                lineHeight = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = colors.ink2,
            )
        }
    }
}

@Composable
private fun PreviewCard(preview: InstallmentSchedule.Computation) {
    val colors = tallyColors
    val shape = RoundedCornerShape(15.dp)
    val mortgage = preview.repaymentMethod != RepaymentMethod.PLAIN
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.card)
            .border(1.dp, colors.brand.copy(alpha = 0.25f), shape)
            .padding(horizontal = 12.dp, vertical = 11.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = if (mortgage) {
                    "首期 ${formatAmount(preview.periods.first().amountMinor)}"
                } else {
                    "每期 ${formatAmount(preview.perPeriodMinor)}"
                },
                style = TallyType.listTitle,
                color = colors.ink,
            )
            Spacer(Modifier.weight(1f))
            TallyTag(
                text = if (mortgage) "总利息 ${formatAmount(preview.interestMinor)}" else "合计 ${formatAmount(preview.totalMinor)}",
                tone = TagTone.BRAND,
            )
        }
        if (mortgage) {
            Spacer(Modifier.height(3.dp))
            Text(
                text = "${methodLabel(preview.repaymentMethod)} · 年利率 ${formatRateBp(preview.annualRateBp)} · " +
                    "末月 ${formatAmount(preview.periods.last().amountMinor)}",
                style = TallyType.helper,
                color = colors.ink3,
            )
        } else if (preview.tailMinor != preview.perPeriodMinor) {
            Spacer(Modifier.height(3.dp))
            Text(
                text = "末期 ${formatAmount(preview.tailMinor)} · 尾差 ${formatAmount(preview.tailMinor - preview.perPeriodMinor)}",
                style = TallyType.helper,
                color = colors.ink3,
            )
        }
        Spacer(Modifier.height(6.dp))
        preview.periods.take(4).forEach { period ->
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
                Text("第 ${period.index} 期", style = TallyType.helper, color = colors.ink3, modifier = Modifier.width(56.dp))
                Text(formatDate(period.dueAt), style = TallyType.helper, color = colors.ink2, modifier = Modifier.weight(1f))
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = formatAmount(period.amountMinor) + if (period.isFee) " 手续费" else "",
                        style = TallyType.helper,
                        color = colors.ink,
                    )
                    if (mortgage && period.interestMinor != null) {
                        Text(
                            text = "本金 ${formatAmount(period.principalMinor ?: 0)} · 利息 ${formatAmount(period.interestMinor)}",
                            fontSize = 9.5.sp, lineHeight = 11.9.sp,
                            color = colors.ink3,
                        )
                    }
                }
            }
        }
        if (preview.periods.size > 4) {
            Text("… 共 ${preview.periods.size} 期", style = TallyType.helper, color = colors.ink3)
        }
        Spacer(Modifier.height(5.dp))
        Text(
            text = "以上各期现在一笔都不写，首期在 ${formatDate(preview.periods.firstOrNull()?.dueAt ?: 0L)} 到期当天生成" +
                if (mortgage) "；之后改利率只重算未入账的期。" else "。",
            fontSize = 10.5.sp,
            lineHeight = 15.sp,
            fontWeight = FontWeight.SemiBold,
            color = colors.ink2,
        )
    }
}

internal fun methodLabel(method: RepaymentMethod): String = when (method) {
    RepaymentMethod.EQUAL_PAYMENT -> "等额本息"
    RepaymentMethod.EQUAL_PRINCIPAL -> "等额本金"
    RepaymentMethod.PLAIN -> "普通分期"
}
