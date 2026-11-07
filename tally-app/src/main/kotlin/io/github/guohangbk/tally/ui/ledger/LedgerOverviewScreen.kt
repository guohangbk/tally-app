package io.github.guohangbk.tally.ui.ledger

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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.guohangbk.tally.domain.Period
import io.github.guohangbk.tally.domain.formatAmount
import io.github.guohangbk.tally.domain.model.BillListItem
import io.github.guohangbk.tally.domain.model.CategoryTotal
import io.github.guohangbk.tally.ui.bill.BillDetailSheet
import io.github.guohangbk.tally.ui.common.BillRow
import io.github.guohangbk.tally.ui.design.BottomSheetGrabber
import io.github.guohangbk.tally.ui.design.ButtonVariant
import io.github.guohangbk.tally.ui.design.IconTone
import io.github.guohangbk.tally.ui.design.IconTile
import io.github.guohangbk.tally.ui.design.MoneySign
import io.github.guohangbk.tally.ui.design.MoneyText
import io.github.guohangbk.tally.ui.design.SkeletonBar
import io.github.guohangbk.tally.ui.design.TagTone
import io.github.guohangbk.tally.ui.design.TallyButton
import io.github.guohangbk.tally.ui.design.TallyCard
import io.github.guohangbk.tally.ui.design.TallyColors
import io.github.guohangbk.tally.ui.design.TallyConfirmDialog
import io.github.guohangbk.tally.ui.design.TallyEmptyState
import io.github.guohangbk.tally.ui.design.TallyListRow
import io.github.guohangbk.tally.ui.design.TallySectionLabel
import io.github.guohangbk.tally.ui.design.TallyTag
import io.github.guohangbk.tally.ui.design.TallyToastEffects
import io.github.guohangbk.tally.ui.design.TallyType
import io.github.guohangbk.tally.ui.design.rememberSkeletonShown
import io.github.guohangbk.tally.ui.design.tallyColors
import io.github.guohangbk.tally.ui.shell.TallyIconAction
import io.github.guohangbk.tally.ui.shell.TallyTopBar
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 账本概览：一本账的白底卡 + 快速问答 + 分类占比 + 最近几笔。
 *
 * 与统计页分工：统计是任意区间的查询工具，这里只回答「这本账花了多少、花在哪」。
 * 预算是周期维度，留在首页与统计页——概览卡只放账本维度的总支出、跨度、笔数、日均。
 */
@Composable
fun LedgerOverviewScreen(
    viewModel: LedgerOverviewViewModel,
    onBack: () -> Unit,
    onOpenStats: () -> Unit,
    onOpenCategory: (String) -> Unit,
    onOpenBillEdit: (String) -> Unit,
    onOpenSettlePoster: () -> Unit,
    /** 从账本列表 ⋯ 的「结算」进来：页面就绪后直接弹确认框，不再让人找第二次按钮。 */
    startSettle: Boolean = false,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val colors = tallyColors
    var detailBillId by remember { mutableStateOf<String?>(null) }
    // 骨架只留给「真的慢」：账本信息、统计都在 150ms 内到齐的话，一帧灰条都不画；出现与消失都硬切换。
    val pageSkeletonAlpha = if (rememberSkeletonShown(!state.loaded)) 1f else 0f
    val statsSkeletonAlpha = if (rememberSkeletonShown(state.loaded && !state.statsLoaded)) 1f else 0f

    LaunchedEffect(state.loaded, startSettle) {
        if (state.loaded && startSettle && state.canSettle) viewModel.requestSettle()
    }

    Column(modifier = Modifier.fillMaxSize().background(colors.paper)) {
        TallyTopBar(
            title = state.ledgerName.ifBlank { "账本" },
            onBack = onBack,
            trailing = {
                if (!state.missing) {
                    TallyIconAction(iconKey = "chart", contentDescription = "看统计", onClick = onOpenStats)
                }
            },
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(start = 15.dp, end = 15.dp, top = 6.dp, bottom = 18.dp),
        ) {
            when {
                state.missing -> TallyEmptyState(
                    title = "这本账已经不在了",
                    description = "它可能已被删除。返回账本列表看看其它账本。",
                    iconKey = "ledger",
                )

                !state.loaded -> Box(Modifier.alpha(pageSkeletonAlpha)) { OverviewSkeleton() }

                else -> {
                    // 统计到齐就直接显示，不做淡入过渡。
                    val statsAlpha = if (state.statsLoaded) 1f else 0f
                    OverviewCard(state, statsAlpha, statsSkeletonAlpha)
                    QuickQuestionBlock(
                        state = state,
                        statsAlpha = statsAlpha,
                        skeletonAlpha = statsSkeletonAlpha,
                        onClickChip = { question ->
                            // 没有分类可选时直接落到空态，不给一个空的选择器。
                            if (question == OverviewQuestion.CATEGORY && state.categories.isNotEmpty()) {
                                viewModel.openCategoryPicker()
                            } else {
                                viewModel.ask(question)
                            }
                        },
                        onOpenBill = { detailBillId = it },
                        onOpenCategory = onOpenCategory,
                    )
                    ShareSection(state, statsAlpha, statsSkeletonAlpha)
                    RecentSection(state, onOpenBill = { detailBillId = it })
                    BottomAction(state, onSettle = viewModel::requestSettle)
                }
            }
        }
    }

    // 弹层与对话框都挂在页面外层：嵌进滚动列会被裁掉。
    if (state.settleDialogVisible) {
        TallyConfirmDialog(
            title = "结算「${state.ledgerName}」账本？",
            message = "结算后这本账会被锁定，之后不能往里记新账。如需继续记账，请新建账本。",
            confirmLabel = "确认结算",
            onConfirm = viewModel::confirmSettle,
            onDismiss = viewModel::dismissSettleDialog,
        )
    }

    if (state.categoryPickerOpen) {
        TallyConfirmDialog(
            title = "看哪个分类？",
            message = "只列这本账里出现过的支出分类。",
            confirmLabel = null,
            dismissLabel = "关闭",
            onDismiss = viewModel::closeCategoryPicker,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 320.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                state.categories.forEach { category ->
                    TallyListRow(
                        title = category.name,
                        subtitle = "${category.billCount} 笔",
                        trailing = {
                            Text(
                                text = "¥" + formatAmount(category.amountMinor, state.grouping),
                                style = TallyType.moneyList,
                                color = colors.ink2,
                            )
                        },
                        onClick = { viewModel.pickCategory(category.categoryId) },
                    )
                }
            }
        }
    }

    if (state.settleSheetVisible) {
        SettlementSheet(
            state = state,
            onDismiss = viewModel::dismissSettleSheet,
            onGeneratePoster = {
                viewModel.dismissSettleSheet()
                onOpenSettlePoster()
            },
        )
    }

    detailBillId?.let { id ->
        BillDetailSheet(
            billId = id,
            onDismiss = { detailBillId = null },
            onEdit = { billId ->
                detailBillId = null
                onOpenBillEdit(billId)
            },
            onOpenBill = { detailBillId = it },
        )
    }

    TallyToastEffects(
        notice = null,
        error = state.error,
        errorTitle = "这一步没有完成",
        onErrorShown = viewModel::dismissError,
    )
}

@Composable
private fun OverviewCard(state: LedgerOverviewUiState, statsAlpha: Float, skeletonAlpha: Float) {
    val colors = tallyColors
    TallyCard(corner = 16, contentPadding = 14.dp) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                IconTile(
                    iconKey = state.ledgerIconKey,
                    label = state.ledgerName,
                    size = 28.dp,
                    corner = 9.dp,
                    tone = IconTone.PAPER,
                )
                Text(
                    text = state.ledgerName.ifBlank { "账本" },
                    fontSize = 15.sp,
                    lineHeight = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                when {
                    state.isDefault -> TallyTag(text = "默认", tone = TagTone.BRAND)
                    state.isArchived -> TallyTag(text = "已结算", tone = TagTone.SLATE)
                    state.billCount == 0 -> TallyTag(text = "空账本", tone = TagTone.GOLD)
                    else -> TallyTag(text = "${state.billCount} 笔")
                }
            }
            Spacer(Modifier.height(10.dp))
            // 金额始终按最终版式排版，只做透明度：骨架和数字的行高不同，换着画会跳一下。
            Box(Modifier.fillMaxWidth()) {
                MoneyText(
                    amountMinor = state.totalExpenseMinor,
                    sign = MoneySign.NONE,
                    style = TallyType.moneyDisplay.copy(fontSize = 30.sp, lineHeight = 33.sp),
                    color = colors.expense,
                    grouping = state.grouping,
                    modifier = Modifier.alpha(statsAlpha),
                )
                if (skeletonAlpha > 0f) {
                    SkeletonBar(
                        width = 190.dp,
                        height = 29.dp,
                        modifier = Modifier.align(Alignment.CenterStart).alpha(skeletonAlpha),
                    )
                }
            }
            Spacer(Modifier.height(3.dp))
            Text(
                text = overviewNote(state),
                fontSize = 11.5.sp,
                lineHeight = 15.5.sp,
                color = colors.ink3,
            )
        }
    }
}

@Composable
private fun QuickQuestionBlock(
    state: LedgerOverviewUiState,
    statsAlpha: Float,
    skeletonAlpha: Float,
    onClickChip: (OverviewQuestion) -> Unit,
    onOpenBill: (String) -> Unit,
    onOpenCategory: (String) -> Unit,
) {
    val colors = tallyColors
    TallySectionLabel("快速问答")
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        OverviewQuestion.entries.forEach { question ->
            QuestionChip(
                text = question.chip,
                selected = state.question == question,
                onClick = { onClickChip(question) },
            )
        }
    }
    Spacer(Modifier.height(8.dp))
    TallyCard(corner = 15, contentPadding = 13.dp) {
        Box(Modifier.fillMaxWidth()) {
            if (state.statsLoaded) {
                Box(Modifier.fillMaxWidth().alpha(statsAlpha)) {
                    QuestionResult(
                        state = state,
                        onOpenBill = onOpenBill,
                        onOpenCategory = onOpenCategory,
                    )
                }
            }
            // 骨架与结果叠在一起淡入淡出，中间不会空出一块白；没到 150ms 时它只是占位。
            if (!state.statsLoaded || skeletonAlpha > 0f) {
                Box(Modifier.fillMaxWidth().alpha(skeletonAlpha)) { QSkeleton() }
            }
        }
    }
}

@Composable
private fun QuestionChip(text: String, selected: Boolean, onClick: () -> Unit) {
    val colors = tallyColors
    val shape = RoundedCornerShape(9.dp)
    Box(
        modifier = Modifier
            .clip(shape)
            .background(if (selected) colors.brand else colors.card)
            .border(1.dp, if (selected) colors.brand else colors.line, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(
            text = text,
            fontSize = 12.5.sp,
            lineHeight = 16.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (selected) colors.onBrand else colors.ink2,
            maxLines = 1,
        )
    }
}

@Composable
private fun QuestionResult(
    state: LedgerOverviewUiState,
    onOpenBill: (String) -> Unit,
    onOpenCategory: (String) -> Unit,
) {
    val colors = tallyColors
    when (state.question) {
        OverviewQuestion.NET -> QValue(
            question = OverviewQuestion.NET.chip,
            amountMinor = state.incomeMinor,
            suffix = "· 结余 " + signedMoney(state.balanceMinor, state.grouping),
            grouping = state.grouping,
            metas = listOf(
                "支出" to money(state.totalExpenseMinor, state.grouping),
                "笔数" to "${state.billCount} 笔",
            ),
        )

        OverviewQuestion.LARGEST -> {
            val largest = state.largestExpense
            if (largest == null) {
                QEmpty(OverviewQuestion.LARGEST.chip, "这本账还没有可统计的支出。")
            } else {
                QValue(
                    question = OverviewQuestion.LARGEST.chip,
                    amountMinor = largest.bill.amountMinor,
                    suffix = "· " + (largest.categoryL2Name ?: largest.categoryL1Name ?: "未分类"),
                    grouping = state.grouping,
                    metas = listOf(
                        "日期" to shortDate(largest.bill.occurredAt),
                        "分类" to (largest.categoryL1Name ?: "未分类"),
                    ),
                    footnote = buildAnnotatedString {
                        append("这笔占这本账的 ")
                        withStyle(SpanStyle(color = colors.expense, fontWeight = FontWeight.SemiBold)) {
                            append(percentText(state.largestShare))
                        }
                        append(" · 点这笔进账单详情")
                    },
                    onClick = { onOpenBill(largest.bill.id) },
                )
            }
        }

        OverviewQuestion.MONTHLY -> QValue(
            question = OverviewQuestion.MONTHLY.chip,
            amountMinor = state.monthlyAverageMinor,
            suffix = "· ${state.months} 个月",
            grouping = state.grouping,
            metas = listOf(
                "总支出" to money(state.totalExpenseMinor, state.grouping),
                "时间跨度" to spanShort(state),
            ),
        )

        OverviewQuestion.CATEGORY -> {
            val category = state.questionCategory
            if (category == null) {
                QEmpty(OverviewQuestion.CATEGORY.chip, "这本账还没有可归类的支出。")
            } else {
                QValue(
                    question = category.name + "花了多少",
                    amountMinor = category.amountMinor,
                    suffix = "· ${category.billCount} 笔",
                    grouping = state.grouping,
                    metas = listOf(
                        "占这本账" to "${(category.share * 100).roundToInt()}%",
                        "月均" to money(
                            if (state.months > 0) category.amountMinor / state.months else 0L,
                            state.grouping,
                        ),
                    ),
                    footnote = buildAnnotatedString { append("点这个分类进统计页看这笔分类的明细") },
                    onClick = category.categoryId?.let { id -> { onOpenCategory(id) } },
                )
            }
        }
    }
}

@Composable
private fun QValue(
    question: String,
    amountMinor: Long,
    suffix: String,
    grouping: Boolean,
    metas: List<Pair<String, String>>,
    footnote: AnnotatedString? = null,
    onClick: (() -> Unit)? = null,
) {
    val colors = tallyColors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(onClick?.let { action -> Modifier.clickable(onClick = action) } ?: Modifier),
    ) {
        Text(
            text = question,
            fontSize = 11.sp,
            lineHeight = 14.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.08.em,
            color = colors.ink3,
        )
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            MoneyText(
                amountMinor = amountMinor,
                sign = MoneySign.NONE,
                style = TallyType.moneyBody.copy(fontSize = 24.sp, lineHeight = 27.sp, fontWeight = FontWeight.SemiBold),
                color = colors.ink,
                grouping = grouping,
                modifier = Modifier.alignByBaseline(),
            )
            Spacer(Modifier.width(4.dp))
            Text(
                text = suffix,
                fontSize = 12.5.sp,
                lineHeight = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = colors.ink3,
                modifier = Modifier.alignByBaseline(),
            )
        }
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            metas.forEach { (label, value) ->
                Column {
                    Text(label, fontSize = 10.5.sp, lineHeight = 13.sp, color = colors.ink3)
                    Text(
                        value,
                        fontSize = 12.sp,
                        lineHeight = 15.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.ink2,
                    )
                }
            }
        }
        if (footnote != null) {
            Spacer(Modifier.height(7.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(colors.line))
            Spacer(Modifier.height(7.dp))
            Text(
                text = footnote,
                fontSize = 11.sp,
                lineHeight = 16.sp,
                color = colors.ink3,
            )
        }
    }
}

@Composable
private fun QEmpty(question: String, message: String) {
    val colors = tallyColors
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = question,
            fontSize = 11.sp,
            lineHeight = 14.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.08.em,
            color = colors.ink3,
        )
        Spacer(Modifier.height(6.dp))
        Text(message, style = TallyType.helper, color = colors.ink3)
    }
}

/** 首帧骨架：账本信息还没回来时，先把一屏的版式占住，避免一闪而过的加载文字。 */
@Composable
private fun OverviewSkeleton() {
    TallyCard(corner = 16, contentPadding = 14.dp) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                SkeletonBar(width = 28.dp, height = 28.dp, corner = 9.dp)
                SkeletonBar(width = 120.dp, height = 15.dp)
                Spacer(Modifier.weight(1f))
                SkeletonBar(width = 44.dp, height = 18.dp, corner = 9.dp)
            }
            Spacer(Modifier.height(14.dp))
            SkeletonBar(width = 190.dp, height = 29.dp)
            Spacer(Modifier.height(9.dp))
            SkeletonBar(width = 250.dp, height = 12.dp)
        }
    }
    Spacer(Modifier.height(4.dp))
    TallySectionLabel("快速问答")
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        SkeletonBar(width = 84.dp, height = 28.dp, corner = 9.dp)
        SkeletonBar(width = 68.dp, height = 28.dp, corner = 9.dp)
        SkeletonBar(width = 80.dp, height = 28.dp, corner = 9.dp)
        SkeletonBar(width = 96.dp, height = 28.dp, corner = 9.dp)
    }
    Spacer(Modifier.height(8.dp))
    TallyCard(corner = 15, contentPadding = 13.dp) { QSkeleton() }
    ShareSkeleton()
}

@Composable
private fun QSkeleton() {
    Column {
        SkeletonBar(width = 96.dp, height = 11.dp)
        Spacer(Modifier.height(9.dp))
        SkeletonBar(width = 168.dp, height = 24.dp)
        Spacer(Modifier.height(9.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            SkeletonBar(width = 64.dp, height = 12.dp)
            SkeletonBar(width = 64.dp, height = 12.dp)
        }
    }
}

@Composable
private fun ShareSkeleton() {
    TallySectionLabel("分类占比")
    TallyCard(corner = 15, contentPadding = 13.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(11.dp)) {
            repeat(3) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    SkeletonBar(width = 56.dp, height = 12.dp)
                    SkeletonBar(height = 6.dp, modifier = Modifier.weight(1f))
                    SkeletonBar(width = 64.dp, height = 12.dp)
                }
            }
        }
    }
}

@Composable
private fun ShareSection(state: LedgerOverviewUiState, statsAlpha: Float, skeletonAlpha: Float) {
    val colors = tallyColors
    if (!state.statsLoaded) {
        // 分类是统计出来的：没到之前只留骨架，同样要等到真的慢才画出来。
        if (skeletonAlpha > 0f) {
            Box(Modifier.alpha(skeletonAlpha)) { ShareSkeleton() }
        }
        return
    }
    val slices = shareSlices(state.categories, sharePalette(colors))
    if (slices.isEmpty()) return
    TallySectionLabel("分类占比")
    TallyCard(corner = 15, contentPadding = 13.dp) {
        Column(
            modifier = Modifier.alpha(statsAlpha),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            slices.forEach { slice ->
                ShareRow(slice = slice, grouping = state.grouping)
            }
        }
    }
}

@Composable
private fun ShareRow(slice: ShareSlice, grouping: Boolean) {
    val colors = tallyColors
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        Text(
            text = slice.name,
            fontSize = 12.sp,
            lineHeight = 15.sp,
            color = colors.ink2,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(min = 44.dp, max = 78.dp),
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(colors.line),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(slice.ratio.coerceIn(0.02f, 1f))
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(slice.color),
            )
        }
        Text(
            text = "¥" + formatAmount(slice.amountMinor, grouping),
            fontSize = 11.sp,
            lineHeight = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = colors.ink2,
            textAlign = TextAlign.End,
            maxLines = 1,
            modifier = Modifier.widthIn(min = 58.dp),
        )
    }
}

@Composable
private fun RecentSection(state: LedgerOverviewUiState, onOpenBill: (String) -> Unit) {
    if (state.recent.isEmpty()) return
    TallySectionLabel("最近几笔")
    Column {
        state.recent.forEachIndexed { index, item ->
            if (index > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(tallyColors.line))
            BillRow(
                item = item,
                grouping = state.grouping,
                onClick = { onOpenBill(item.bill.id) },
            )
        }
    }
}

@Composable
private fun BottomAction(state: LedgerOverviewUiState, onSettle: () -> Unit) {
    val colors = tallyColors
    if (state.canSettle) {
        Spacer(Modifier.height(16.dp))
        TallyButton(
            label = "结算",
            onClick = onSettle,
            variant = ButtonVariant.BRAND,
            leadingIcon = "flag",
            height = 40,
            modifier = Modifier.fillMaxWidth(),
        )
    } else if (state.isArchived) {
        Spacer(Modifier.height(16.dp))
        Text(
            text = "账本已结算 · 不能往里记新账\n可在我的账本里恢复记账",
            fontSize = 11.sp,
            lineHeight = 16.5.sp,
            color = colors.ink3,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettlementSheet(
    state: LedgerOverviewUiState,
    onDismiss: () -> Unit,
    onGeneratePoster: () -> Unit,
) {
    val colors = tallyColors
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = colors.card,
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 15.dp).padding(bottom = 18.dp)) {
            BottomSheetGrabber(modifier = Modifier.align(Alignment.CenterHorizontally))
            Spacer(Modifier.height(8.dp))
            Text(
                text = "${state.ledgerName} · 结算",
                style = TallyType.screenTitle.copy(fontSize = 16.sp, lineHeight = 19.5.sp),
                color = colors.ink,
            )
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                MoneyText(
                    amountMinor = state.totalExpenseMinor,
                    sign = MoneySign.NONE,
                    style = TallyType.moneyDisplay.copy(fontSize = 27.sp, lineHeight = 29.sp),
                    color = colors.expense,
                    grouping = state.grouping,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = "总支出",
                    fontSize = 10.5.sp,
                    lineHeight = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.ink3,
                )
            }
            state.budgetLimitMinor?.let { limit ->
                Spacer(Modifier.height(10.dp))
                BudgetCompareRow(limit = limit, delta = state.budgetDeltaMinor ?: 0L, grouping = state.grouping)
            }
            val slices = shareSlices(state.categories, sharePalette(colors))
            if (slices.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "分类占比",
                    fontSize = 11.sp,
                    lineHeight = 14.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.08.em,
                    color = colors.ink3,
                )
                Spacer(Modifier.height(7.dp))
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    slices.forEach { slice -> ShareRow(slice = slice, grouping = state.grouping) }
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                text = "账本已结算 · 之后不能往里记新账",
                fontSize = 11.sp,
                lineHeight = 16.sp,
                color = colors.ink3,
            )
            Spacer(Modifier.height(12.dp))
            TallyButton(
                label = "生成结算长图",
                onClick = onGeneratePoster,
                variant = ButtonVariant.GOLD,
                leadingIcon = "share",
                height = 40,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun BudgetCompareRow(limit: Long, delta: Long, grouping: Boolean) {
    val colors = tallyColors
    val shape = RoundedCornerShape(10.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.paper)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "预算 ¥${formatAmount(limit, grouping)} · " + when {
                delta > 0L -> "已超支"
                delta < 0L -> "剩余"
                else -> "正好用完"
            },
            fontSize = 11.5.sp,
            lineHeight = 15.sp,
            color = colors.ink3,
            modifier = Modifier.weight(1f),
        )
        if (delta != 0L) {
            Text(
                text = (if (delta > 0L) "+¥" else "−¥") + formatAmount(kotlin.math.abs(delta), grouping),
                fontSize = 12.sp,
                lineHeight = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (delta > 0L) colors.expense else colors.income,
            )
        }
    }
}

private data class ShareSlice(
    val name: String,
    val amountMinor: Long,
    val ratio: Float,
    val color: Color,
)

private fun sharePalette(colors: TallyColors): List<Color> =
    listOf(colors.brand, colors.gold, colors.slate, colors.ink3)

/** 前三是分类自己的颜色，剩下的合并成「其他」——超过三根颜色就只剩装饰，读不出结构。 */
private fun shareSlices(categories: List<CategoryTotal>, palette: List<Color>): List<ShareSlice> {
    val positive = categories.filter { it.amountMinor > 0L }
    if (positive.isEmpty()) return emptyList()
    val result = positive.take(3).mapIndexed { index, item ->
        ShareSlice(
            name = item.name,
            amountMinor = item.amountMinor,
            // 直接用统计层算好的占比：条长之和才是 100%，不再各算一套分母。
            ratio = item.share,
            color = palette[index],
        )
    }.toMutableList()
    val rest = positive.drop(3)
    if (rest.isNotEmpty()) {
        result += ShareSlice(
            name = "其他",
            amountMinor = rest.sumOf { it.amountMinor },
            ratio = rest.sumOf { it.share.toDouble() }.toFloat(),
            color = palette[3],
        )
    }
    return result
}

private val fullDateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.CHINA)
private val shortDatePattern = DateTimeFormatter.ofPattern("MM-dd", Locale.CHINA)

private fun shortDate(timestamp: Long): String =
    Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()).toLocalDate().format(shortDatePattern)

private fun fullDate(timestamp: Long): String =
    Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()).toLocalDate().format(fullDateFormatter)

/** 问答里的跨度：两端都带年份，「06-01」单看分不清是哪一年。默认账本的右端永远写「至今」。 */
private fun spanShort(state: LedgerOverviewUiState): String {
    val start = state.spanStartAt ?: return "—"
    return if (state.isDefault) {
        fullDate(start) + " ～ 至今"
    } else {
        state.spanEndAt?.let { "${fullDate(start)} ～ ${fullDate(it)}" } ?: fullDate(start)
    }
}

/** 概览卡那行：非默认带天数，默认账本带笔数并写「至今」。日均要等统计到齐才写。 */
private fun overviewNote(state: LedgerOverviewUiState): String {
    val start = state.spanStartAt
    if (start == null) return "还没有账单，先记一笔"
    val daily = money(state.dailyAverageMinor, state.grouping)
    return if (state.isDefault) {
        "${fullDate(start)} ～ 至今 · ${state.billCount} 笔" +
            if (state.statsLoaded) " · 日均 $daily" else ""
    } else {
        val end = state.spanEndAt?.let(::fullDate) ?: fullDate(start)
        "${fullDate(start)} ～ $end · ${state.spanDays} 天" +
            if (state.statsLoaded) " · 日均 $daily" else ""
    }
}

private fun money(minor: Long, grouping: Boolean): String = "¥" + formatAmount(minor, grouping)

/** 结余可正可负：正数带 +，负数带 −，零就只写金额。 */
private fun signedMoney(minor: Long, grouping: Boolean): String = when {
    minor > 0L -> "+¥" + formatAmount(minor, grouping)
    minor < 0L -> "−¥" + formatAmount(-minor, grouping)
    else -> "¥" + formatAmount(0L, grouping)
}

/** 占比只留一位小数：18.3% 比 18.27% 好读，也与设计板一致。 */
private fun percentText(share: Float): String {
    val value = share * 100f
    return String.format(Locale.CHINA, "%.1f", value) + "%"
}
