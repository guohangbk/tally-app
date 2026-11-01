package io.github.guohangbk.tally.ui.budget

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.core.tween
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import io.github.guohangbk.tally.domain.Period
import io.github.guohangbk.tally.domain.formatAmount
import io.github.guohangbk.tally.domain.model.BudgetItem
import io.github.guohangbk.tally.domain.model.BudgetPeriod
import io.github.guohangbk.tally.domain.model.BudgetSummary
import io.github.guohangbk.tally.domain.model.BudgetUsage
import io.github.guohangbk.tally.ui.common.CategoryPickerSheet
import io.github.guohangbk.tally.ui.common.LedgerPickerSheet
import io.github.guohangbk.tally.ui.common.tallyViewModel
import io.github.guohangbk.tally.ui.design.BudgetCategoryRow
import io.github.guohangbk.tally.ui.design.BudgetMoreButton
import io.github.guohangbk.tally.ui.design.BudgetProgressBar
import io.github.guohangbk.tally.ui.design.IconTile
import io.github.guohangbk.tally.ui.design.InlineIcon
import io.github.guohangbk.tally.ui.design.KeypadRole
import io.github.guohangbk.tally.ui.design.TallyConfirmDialog
import io.github.guohangbk.tally.ui.design.TallyKeypad
import io.github.guohangbk.tally.ui.design.TallySegmented
import io.github.guohangbk.tally.ui.design.TallySwitch
import io.github.guohangbk.tally.ui.design.TallyTag
import io.github.guohangbk.tally.ui.design.TagTone
import io.github.guohangbk.tally.ui.design.TallyToastEffects
import io.github.guohangbk.tally.ui.design.TallyType
import io.github.guohangbk.tally.ui.design.budgetAmountText
import io.github.guohangbk.tally.ui.design.tallyColors
import io.github.guohangbk.tally.ui.shell.TallyTopBar
import kotlin.math.roundToInt

/**
 * 预算总览（BU21-03）与页内编辑（BU21-04）。编辑是页内状态，不单独占路由：
 * 返回就是放弃修改，不做自动保存。顶栏按板上那一排：返回 · 标题 · 账本标签 ▾ · ＋，
 * 早先挂在正文里的那一行（账本标签 + 第二个＋）已经撤掉——一页只能有一个新建入口。
 */
@Composable
fun BudgetScreen(onBack: () -> Unit, onOpenHistory: () -> Unit) {
    val graph = rememberAppGraph()
    val viewModel = tallyViewModel { BudgetViewModel(graph) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val editState by viewModel.editState.collectAsStateWithLifecycle()
    val toast by viewModel.toastState.collectAsStateWithLifecycle()
    val clash by viewModel.clashState.collectAsStateWithLifecycle()
    var ledgerPicker by remember { mutableStateOf(false) }
    var categoryPicker by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<BudgetItem?>(null) }
    val categories by viewModel.expenseCategoryEntitiesFlow.collectAsStateWithLifecycle()
    val editing = editState
    // 退场那 240ms 里 `editing` 已经清空，滑出去的编辑屏要用最后一份草稿接着画，否则它会当场空掉。
    var shownEdit by remember { mutableStateOf(editing) }
    LaunchedEffect(editing) { if (editing != null) shownEdit = editing }

    // 编辑是页内状态：系统返回 / 侧滑先收起编辑回总览，而不是整页退出到首页（2026-10-28 走查）。
    if (editing != null) BackHandler(onBack = viewModel::dismissEdit)

    Column(modifier = Modifier.fillMaxSize().background(tallyColors.paper)) {
        TallyTopBar(
            title = if (editing == null) "预算管理" else if (editing.id == null) "新建预算" else "编辑预算",
            onBack = if (editing == null) onBack else viewModel::dismissEdit,
            trailing = {
                if (editing == null) {
                    LedgerTag(name = state.ledgerName, onClick = { ledgerPicker = true })
                    PlusButton { viewModel.beginCreate(isTotal = true) }
                } else {
                    // 编辑页只留账本标签：删除不在这里，收在列表每一行的 ⋯ 里（2026-10-28 走查）。
                    LedgerTag(name = editing.ledgerName, onClick = null)
                }
            },
        )
        // 编辑是页内状态，借不到导航转场，所以这里自己给一段横向推入：进编辑往左推、回总览反向推回。
        // targetState 只能是「在不在编辑」这一个布尔：草稿每敲一个字都是新的 data class 实例，
        // 拿它当 targetState 会让每次点击都重播一遍推入动画，看着就是整页闪一下（2026-10-28 走查）。
        AnimatedContent(
            targetState = editing == null,
            transitionSpec = {
                val direction = if (targetState) -1 else 1
                (slideInHorizontally(tween(240)) { width -> width / 5 * direction } + fadeIn(tween(200)))
                    .togetherWith(slideOutHorizontally(tween(200)) { width -> -width / 5 * direction } + fadeOut(tween(140)))
            },
            label = "budgetEditor",
        ) { isOverview ->
            if (isOverview) {
                BudgetOverview(
                    state = state,
                    onSelectPeriod = viewModel::selectPeriod,
                    onEdit = viewModel::beginEdit,
                    onDelete = { deleteTarget = it },
                    onOpenHistory = onOpenHistory,
                )
            } else {
                // 退场那 240ms 里 `editing` 已经清空，滑出去的这一屏要用最后一份草稿接着画。
                val draft = shownEdit
                if (draft != null) {
                    BudgetEdit(
                        state = draft,
                        categories = categories,
                        amountMinor = viewModel.saveAmountTextMinor(),
                        onPressKey = viewModel::pressKey,
                        onDelete = viewModel::deleteDigit,
                        onClear = viewModel::clearAmount,
                        onSave = viewModel::save,
                        onSetPeriod = viewModel::setPeriod,
                        onSetScope = viewModel::setScope,
                        onPickCategory = {
                            viewModel.loadExpenseCategories()
                            categoryPicker = true
                        },
                        onSetAlertRatio = viewModel::setAlertRatio,
                        onSetAutoRenew = viewModel::setAutoRenew,
                        onSetActive = viewModel::setActive,
                        clash = clash,
                        onGoClash = viewModel::openClash,
                    )
                }
            }
        }
    }

    if (ledgerPicker) {
        LedgerPickerSheet(
            ledgers = state.ledgers,
            selectedId = state.ledgerId,
            onSelect = { viewModel.selectLedger(it) },
            onDismiss = { ledgerPicker = false },
        )
    }
    if (categoryPicker) {
        CategoryPickerSheet(
            categories = categories,
            selectedL1 = editing?.categoryId,
            selectedL2 = null,
            onDismiss = { categoryPicker = false },
            onConfirm = { l1Id, _ ->
                val category = categories.firstOrNull { it.id == l1Id }
                if (category != null) viewModel.pickCategory(category.id, category.name)
                categoryPicker = false
            },
        )
    }
    deleteTarget?.let { target ->
        TallyConfirmDialog(
            title = "删除这条预算？",
            message = "${if (target.isTotal) state.ledgerName else target.categoryName ?: "分类"} · " +
                "${target.period.label} ${formatAmount(target.amountMinor, grouping = true)}。" +
                "账单不受影响，只是不再按这条预算提醒。",
            confirmLabel = "删除",
            destructive = true,
            onConfirm = {
                deleteTarget = null
                viewModel.delete(target)
            },
            onDismiss = { deleteTarget = null },
        )
    }

    TallyToastEffects(notice = toast, error = null, onNoticeShown = viewModel::clearToast)
}

/** 顶栏上的账本标签（.tag）：总览可点开设弹层，编辑页只读（新建时账本跟着当前选中走）。 */
@Composable
private fun LedgerTag(name: String, onClick: (() -> Unit)?) {
    Box(modifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier) {
        TallyTag(text = "$name ▾")
    }
}

@Composable
private fun BudgetOverview(
    state: BudgetUiState,
    onSelectPeriod: (BudgetPeriod) -> Unit,
    onEdit: (BudgetItem) -> Unit,
    onDelete: (BudgetItem) -> Unit,
    onOpenHistory: () -> Unit,
) {
    val colors = tallyColors
    val summary = state.summary
    val categories = summary?.categories ?: emptyList()

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 15.dp)) {
        Spacer(Modifier.height(4.dp))
        TallySegmented(
            options = listOf(BudgetPeriod.MONTHLY to "每月", BudgetPeriod.YEARLY to "每年"),
            selected = state.period,
            onSelect = onSelectPeriod,
        )
        Spacer(Modifier.height(12.dp))

        if (summary == null || !summary.hasBudget) {
            // 空态分两样（§3.1 规则 2）：另一排设过就别铺一张「什么都没设」的假空态。
            Text(
                text = if (state.totalItems > 0) {
                    "${state.period.label}还没有预算 · 换回${if (state.period == BudgetPeriod.MONTHLY) "每年" else "每月"}看已设的"
                } else {
                    "还没设预算"
                },
                style = TallyType.secondary,
                color = colors.ink2,
                modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(14.dp))
            BudgetHistoryLink(onOpen = onOpenHistory)
            return@Column
        }

        summary.total?.let { total ->
            BudgetTotalCard(
                usage = total,
                startDay = state.startDay,
                onClick = { onEdit(total.item) },
                onDelete = { onDelete(total.item) },
            )
        }

        if (categories.isNotEmpty()) {
            Spacer(Modifier.height(18.dp))
            Text(
                text = "分类预算(${categories.size}条)",
                style = TallyType.sectionTitle,
                color = colors.ink,
            )
            Spacer(Modifier.height(10.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(categories, key = { it.item.id }) { usage ->
                    BudgetCategoryRow(
                        usage = usage,
                        onClick = { onEdit(usage.item) },
                        onDelete = { onDelete(usage.item) },
                    )
                }
            }
        }

        Spacer(Modifier.height(14.dp))
        BudgetHistoryLink(onOpen = onOpenHistory)
    }
}

/** 总额卡：墨底，周期标签与 ⋯ 在右上，日均与剩余天数在最底一行。关了提醒也照常显示进度，只补一枚标签。 */
@Composable
private fun BudgetTotalCard(
    usage: BudgetUsage,
    startDay: Int,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    val colors = tallyColors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(colors.ink)
            .clickable(onClick = onClick)
            .padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "${budgetPeriodLabel(usage)}总额预算",
                style = TallyType.eyebrow,
                color = colors.paper.copy(alpha = 0.6f),
            )
            // 开关只管提醒，进度照算：墨底上不置灰，只打一枚亮一点的标签标明（2026-10-28 走查）。
            if (!usage.item.isActive) {
                Spacer(Modifier.width(6.dp))
                TallyTag(text = "提醒已关闭", tone = TagTone.SLATE)
            }
            // 续期只在这条开着时才标：总额没开、分类开了，下月就只见分类那条回来——标出来才看得懂。
            if (usage.item.autoRenew && usage.item.period == BudgetPeriod.MONTHLY) {
                Spacer(Modifier.width(6.dp))
                TallyTag(text = "每月续期")
            }
            Spacer(Modifier.weight(1f))
            Text(
                text = budgetPeriodTag(usage, startDay),
                fontSize = 10.sp, lineHeight = 12.5.sp,
                color = colors.paper.copy(alpha = 0.6f),
            )
            Box(modifier = Modifier.padding(start = 2.dp)) {
                BudgetMoreButton(onClick = onDelete, tint = colors.paper.copy(alpha = 0.7f))
            }
        }
        Spacer(Modifier.height(9.dp))
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("¥", fontSize = 11.sp, lineHeight = 13.8.sp, color = colors.onSummaryBar.copy(alpha = 0.55f))
            Text(
                text = formatAmount(usage.usedMinor),
                style = TallyType.moneyBody,
                color = if (usage.isOver) colors.expense else colors.onSummaryBar,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = "/ ${formatAmount(usage.item.amountMinor)} · 已用 ${(usage.ratio * 100).toInt()}%",
                fontSize = 11.sp, lineHeight = 13.8.sp,
                color = colors.onSummaryBar.copy(alpha = 0.6f),
            )
        }
        Spacer(Modifier.height(9.dp))
        // 墨底上要用亮色轨道：默认的墨色轨压在墨底上等于隐形，看不出灰底是 100%。
        BudgetProgressBar(usage = usage, trackOverride = colors.onSummaryBar.copy(alpha = 0.22f))
        Spacer(Modifier.height(9.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "日均可用 ¥${formatAmount(usage.dailyAverageMinor)}",
                style = TallyType.helper,
                color = colors.onSummaryBar.copy(alpha = 0.65f),
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = "还剩 ${usage.remainingDays} 天",
                style = TallyType.helper,
                color = colors.onSummaryBar.copy(alpha = 0.65f),
            )
        }
    }
}

/** 周期标签：月预算随每月起始日（1 号起写「2026年10月」，否则写区间），年预算只写年份。 */
private fun budgetPeriodTag(usage: BudgetUsage, startDay: Int): String =
    if (usage.item.period == BudgetPeriod.YEARLY) {
        Period.toDate(usage.range.startAt).year.toString() + " 年"
    } else {
        Period.label(usage.range, startDay)
    }

private fun budgetPeriodLabel(usage: BudgetUsage): String =
    if (usage.item.period == BudgetPeriod.YEARLY) "年度" else "本月"

/** 新建 / 编辑预算（BU21-04）：周期 → 范围 → 分类 / 阈值 / 启用 → 金额，键盘复用记账页那一套。 */
@Composable
private fun BudgetEdit(
    state: BudgetEditState,
    categories: List<io.github.guohangbk.tally.data.db.entity.CategoryEntity>,
    amountMinor: Long,
    onPressKey: (String) -> Unit,
    onDelete: () -> Unit,
    onClear: () -> Unit,
    onSave: () -> Unit,
    onSetPeriod: (BudgetPeriod) -> Unit,
    onSetScope: (Boolean) -> Unit,
    onPickCategory: () -> Unit,
    onSetAlertRatio: (Int) -> Unit,
    onSetAutoRenew: (Boolean) -> Unit,
    onSetActive: (Boolean) -> Unit,
    clash: BudgetItem?,
    onGoClash: () -> Unit,
) {
    val colors = tallyColors
    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 15.dp)) {
        Spacer(Modifier.height(6.dp))
        // 周期行（BU-02 早就画了，代码一直缺这一排）：改完保存就切到那一段。
        TallySegmented(
            options = listOf(BudgetPeriod.MONTHLY to "每月", BudgetPeriod.YEARLY to "每年"),
            selected = state.period,
            onSelect = onSetPeriod,
        )
        Spacer(Modifier.height(7.dp))
        TallySegmented(
            options = listOf(true to "总额预算", false to "分类预算"),
            selected = state.isTotal,
            onSelect = onSetScope,
        )
        Spacer(Modifier.height(10.dp))

        if (!state.isTotal) {
            Row(
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                    .background(colors.card)
                    .border(1.dp, colors.line, RoundedCornerShape(12.dp))
                    .clickable(onClick = onPickCategory)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconTile(
                    iconKey = categories.firstOrNull { it.id == state.categoryId }?.iconKey ?: "tag",
                    label = state.categoryName ?: "分类",
                    size = 28.dp,
                    corner = 8.dp,
                )
                Column(modifier = Modifier.weight(1f).padding(start = 10.dp)) {
                    Text(
                        text = state.categoryName ?: "选一个一级分类",
                        style = TallyType.listTitle,
                        color = colors.ink,
                        maxLines = 1,
                    )
                    Text(
                        text = if (state.categoryId == null) "一级分类" else "一级分类 · 含 ${state.categoryBillCount ?: 0} 笔本月账单",
                        style = TallyType.helper,
                        color = colors.ink3,
                        maxLines = 1,
                    )
                }
                InlineIcon(iconKey = "chevron", contentDescription = "选择", tint = colors.ink3)
            }
        }

        // BU21-08 重复拦截：总额撞总额、分类撞同分类都在同一个位置给一行陶土提示，附「去看那一条」。
        if (clash != null) {
            Spacer(Modifier.height(8.dp))
            BlockTip(text = clashText(state, clash), onGo = onGoClash)
        }

        // 预算提醒与提醒阈值收进同一个模块：开关关了就不给滑条，打开才拖（2026-10-28 走查）。
        Spacer(Modifier.height(10.dp))
        Column(
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                .background(colors.card)
                .border(1.dp, colors.line, RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("预算提醒", style = TallyType.listTitle, color = colors.ink, modifier = Modifier.weight(1f))
                TallySwitch(checked = state.isActive, onCheckedChange = onSetActive)
            }
            if (state.isActive) {
                var sliderValue by remember(state.id) { mutableFloatStateOf(state.alertRatio.toFloat()) }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("阈值", style = TallyType.helper, color = colors.ink3)
                    Slider(
                        value = sliderValue,
                        onValueChange = {
                            sliderValue = it
                            onSetAlertRatio(it.roundToInt())
                        },
                        modifier = Modifier.weight(1f),
                        valueRange = 10f..100f,
                        colors = SliderDefaults.colors(
                            thumbColor = colors.brand,
                            activeTrackColor = colors.brand,
                            inactiveTrackColor = colors.ink.copy(alpha = 0.14f),
                        ),
                    )
                    Text(
                        text = "${state.alertRatio}%",
                        style = TallyType.listTitle,
                        color = colors.brand,
                        modifier = Modifier.width(42.dp),
                        textAlign = TextAlign.End,
                    )
                }
            }
        }

        // 每月自动续期（BU21-12）：只对每月预算有意义，勾上就每月自动复制一条同样的。
        if (state.period == BudgetPeriod.MONTHLY) {
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                    .background(colors.card)
                    .border(1.dp, colors.line, RoundedCornerShape(12.dp))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("每月自动续期", style = TallyType.listTitle, color = colors.ink, modifier = Modifier.weight(1f))
                TallySwitch(checked = state.autoRenew, onCheckedChange = onSetAutoRenew)
            }
        }
        Spacer(Modifier.height(8.dp))

        // 金额直接回显键盘正在输入的那串字：拼成另一个数会让人怀疑刚才按错了。
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text("¥", fontSize = 13.sp, lineHeight = 16.3.sp, color = colors.ink3)
            Text(
                text = if (state.amountText.isEmpty()) "0.00" else state.amountText,
                style = TallyType.moneyField,
                color = colors.ink,
                maxLines = 1,
            )
        }
        Spacer(Modifier.height(4.dp))

        TallyKeypad(
            role = KeypadRole.BUDGET,
            onKey = onPressKey,
            onClear = onClear,
            onSave = onSave,
            saveEnabled = amountMinor > 0L,
        )
    }
}

/** 保存被拦下时那一行的措辞：总额一段只允许一条，分类按「同分类已有」来说。整元不写角分。 */
private fun clashText(draft: BudgetEditState, clash: BudgetItem): String =
    if (draft.isTotal) {
        "已存在${draft.period.label}总额预算 · ¥${budgetAmountText(clash.amountMinor)}"
    } else {
        "该分类已有${draft.period.label}预算 · ¥${budgetAmountText(clash.amountMinor)}"
    }

@Composable
private fun PlusButton(onClick: () -> Unit) {
    val colors = tallyColors
    Box(
        modifier = Modifier
            .size(30.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(colors.brand)
            .border(1.dp, colors.brand, RoundedCornerShape(9.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        InlineIcon(iconKey = "plus", contentDescription = "新建预算", size = 16.dp, tint = colors.onBrand)
    }
}

/** BU21-08 拦截提示：键盘上方一行陶土底，右侧「去看那一条」直接进那条已有预算的编辑。 */
@Composable
private fun BlockTip(text: String, onGo: () -> Unit) {
    val colors = tallyColors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(9.dp))
            .background(colors.expense.copy(alpha = 0.10f))
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = TallyType.helper.copy(fontSize = 10.5.sp, lineHeight = 13.1.sp),
            color = colors.expense,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = "去看那一条 ›",
            style = TallyType.helper.copy(fontSize = 10.5.sp, lineHeight = 13.1.sp, fontWeight = FontWeight.SemiBold),
            color = colors.expense,
            modifier = Modifier.clickable(onClick = onGo).padding(start = 8.dp),
            maxLines = 1,
        )
    }
}

/** 历史入口（BU21-14）：总览底部一行，点开进历史页；空态与当期两屏都给，翻月后回看往段不丢入口。 */
@Composable
private fun BudgetHistoryLink(onOpen: () -> Unit) {
    val colors = tallyColors
    Row(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .background(colors.card)
            .border(1.dp, colors.line, RoundedCornerShape(12.dp))
            .clickable(onClick = onOpen)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        InlineIcon(iconKey = "clock", contentDescription = "历史", size = 15.dp, tint = colors.brand)
        Spacer(Modifier.width(9.dp))
        Text("预算历史", style = TallyType.listTitle, color = colors.ink)
        Spacer(Modifier.weight(1f))
        // 历史本来就跟着顶部的「每月 / 每年」各存各的，行尾再写「回看往月」在年维度下是错的。
        InlineIcon(iconKey = "chevron", contentDescription = "查看预算历史", size = 13.dp, tint = colors.ink3)
    }
}

/**
 * 预算历史（BU21-15）：本账本、当前节奏最近若干段的额度与完成，只读回看——
 * 顶部跟着总览的「每月 / 每年」切换，每一段一张卡，当期那一段挨上「当月 / 当年」标。
 */
@Composable
fun BudgetHistoryScreen(onBack: () -> Unit) {
    val graph = rememberAppGraph()
    val viewModel = tallyViewModel { BudgetViewModel(graph) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val history by viewModel.history.collectAsStateWithLifecycle()
    val currentStart = graph.budgetRepository.periodRange(state.period, state.startDay).startAt

    Column(modifier = Modifier.fillMaxSize().background(tallyColors.paper)) {
        TallyTopBar(
            title = "预算历史",
            onBack = onBack,
            trailing = { LedgerTag(name = state.ledgerName, onClick = null) },
        )
        if (!state.loaded) return@Column
        if (history.isEmpty()) {
            Text(
                text = "还没有${state.period.label}预算的历史",
                style = TallyType.secondary,
                color = tallyColors.ink2,
                modifier = Modifier.fillMaxWidth().padding(top = 40.dp),
                textAlign = TextAlign.Center,
            )
            return@Column
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = 15.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp),
        ) {
            items(history, key = { it.range.startAt }) { summary ->
                BudgetHistoryCard(
                    summary = summary,
                    startDay = state.startDay,
                    isCurrent = summary.range.startAt == currentStart,
                )
            }
        }
    }
}

/** 一段历史：标题（周期段名 + 当期标），总额行打头（若有），下面逐条分类。全部只读，不点不改进。 */
@Composable
private fun BudgetHistoryCard(summary: BudgetSummary, startDay: Int, isCurrent: Boolean) {
    val colors = tallyColors
    Column(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .background(colors.card)
            .border(1.dp, colors.line, RoundedCornerShape(14.dp))
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(historyTitle(summary, startDay), style = TallyType.listTitle, color = colors.ink)
            if (isCurrent) {
                Spacer(Modifier.width(6.dp))
                TallyTag(text = if (summary.period == BudgetPeriod.YEARLY) "当年" else "当月")
            }
        }
        summary.total?.let { total ->
            Spacer(Modifier.height(10.dp))
            HistoryUsageLine(usage = total, title = "总额预算", iconKey = "coin")
        }
        summary.categories.forEachIndexed { index, cat ->
            Spacer(Modifier.height(if (index == 0 && summary.total == null) 4.dp else 10.dp))
            HistoryUsageLine(
                usage = cat,
                title = cat.item.categoryName ?: "分类",
                iconKey = cat.item.categoryIconKey,
            )
        }
    }
}

/** 一行用量：图标 + 名称 + 已用/额度 + 完成率，底下一条与总览同款的进度条。 */
@Composable
private fun HistoryUsageLine(usage: BudgetUsage, title: String, iconKey: String?) {
    val colors = tallyColors
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            InlineIcon(iconKey = iconKey ?: "tag", contentDescription = title, size = 13.dp, tint = colors.ink3)
            Spacer(Modifier.width(6.dp))
            Text(title, style = TallyType.helper.copy(fontSize = 11.5.sp, lineHeight = 14.4.sp), color = colors.ink, maxLines = 1)
            Spacer(Modifier.weight(1f))
            Text(
                text = "${formatAmount(usage.usedMinor)} / ${formatAmount(usage.item.amountMinor)}",
                style = TallyType.moneyBody.copy(fontSize = 11.sp, lineHeight = 13.8.sp),
                color = colors.ink2,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = "${(usage.ratio * 100).toInt()}%",
                style = TallyType.helper.copy(fontSize = 10.sp, lineHeight = 12.5.sp, fontWeight = FontWeight.SemiBold),
                color = if (usage.isOver) colors.expense else colors.brand,
            )
        }
        Spacer(Modifier.height(5.dp))
        BudgetProgressBar(usage = usage)
    }
}

/** 历史段标题：年预算写年份，月预算按每月起始日走 Period.label（1 号起即「2026年10月」）。 */
private fun historyTitle(summary: BudgetSummary, startDay: Int): String =
    if (summary.period == BudgetPeriod.YEARLY) {
        Period.toDate(summary.range.startAt).year.toString() + " 年"
    } else {
        Period.label(summary.range, startDay)
    }
