package io.github.guohangbk.tally.ui.stats

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.guohangbk.tally.di.rememberAppGraph
import io.github.guohangbk.tally.domain.ai.AnalysisSummaryParser
import io.github.guohangbk.tally.domain.ai.analysisLabels
import io.github.guohangbk.tally.domain.Period
import io.github.guohangbk.tally.ui.assistant.AssistantAnalysisUiState
import io.github.guohangbk.tally.ui.assistant.AssistantAnalysisViewModel
import io.github.guohangbk.tally.ui.bill.BillDetailSheet
import io.github.guohangbk.tally.domain.formatAmount
import io.github.guohangbk.tally.domain.model.BillType
import io.github.guohangbk.tally.domain.model.CategoryTotal
import io.github.guohangbk.tally.domain.model.DailyTotal
import io.github.guohangbk.tally.domain.model.StatisticsSnapshot
import io.github.guohangbk.tally.domain.model.StatsBucket
import io.github.guohangbk.tally.domain.model.StatsGranularity
import io.github.guohangbk.tally.ui.common.formatDay
import io.github.guohangbk.tally.ui.common.formatMonth
import io.github.guohangbk.tally.ui.common.millisToLocalDate
import io.github.guohangbk.tally.ui.common.tallyViewModel
import io.github.guohangbk.tally.ui.design.BillBadge
import io.github.guohangbk.tally.ui.design.BudgetUsageRow
import io.github.guohangbk.tally.ui.design.ButtonVariant
import io.github.guohangbk.tally.ui.design.CategoryGlyph
import io.github.guohangbk.tally.ui.design.InlineIcon
import io.github.guohangbk.tally.ui.design.Marker
import io.github.guohangbk.tally.ui.design.MarkerSwitch
import io.github.guohangbk.tally.ui.design.MarkerTone
import io.github.guohangbk.tally.ui.design.MoneySign
import io.github.guohangbk.tally.ui.design.MoneyText
import io.github.guohangbk.tally.ui.design.TagTone
import io.github.guohangbk.tally.ui.design.TallyCard
import io.github.guohangbk.tally.ui.design.TallyButton
import io.github.guohangbk.tally.ui.design.TallyEmptyState
import io.github.guohangbk.tally.ui.design.TallyPillShape
import io.github.guohangbk.tally.ui.design.TallyRowChevron
import io.github.guohangbk.tally.ui.design.TallyTag
import io.github.guohangbk.tally.ui.design.TallyToastEffects
import io.github.guohangbk.tally.ui.design.TallyType
import io.github.guohangbk.tally.ui.design.budgetRatioText
import io.github.guohangbk.tally.ui.navigation.Routes
import io.github.guohangbk.tally.ui.design.tallyColors
import io.github.guohangbk.tally.ui.shell.TallyIconAction
import io.github.guohangbk.tally.ui.shell.TallyTopBar
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.math.PI

private val statsZone: ZoneId get() = ZoneId.systemDefault()
private val weekdayLabels = listOf("日", "一", "二", "三", "四", "五", "六")
/** 柱子从周一起排，与 [weekdayLabels] 的周日开头相反，独立一份。 */
private val weekdayBarLabels = listOf("一", "二", "三", "四", "五", "六", "日")
private val brown = Color(0xFFB08968)

/** 排行条、环形、图标底共用同一套位次配色：金 / 墨绿 / 褐 / 陶土 / 松烟。 */
@Composable
internal fun statsPalette(): List<Color> {
    val colors = tallyColors
    return listOf(colors.gold, colors.brand, brown, colors.expense, colors.ink.copy(alpha = 0.22f))
}

@Composable
private fun statsTileColors(index: Int): Pair<Color, Color> {
    val colors = tallyColors
    return when (index % 5) {
        0 -> colors.glow to colors.onGlow
        1 -> colors.brand.copy(alpha = 0.09f) to colors.brand
        2 -> brown.copy(alpha = 0.18f) to if (colors.isDark) brown else Color(0xFF8A6A4C)
        3 -> colors.expense.copy(alpha = 0.09f) to colors.expense
        else -> colors.ink.copy(alpha = 0.06f) to colors.ink3
    }
}

/**
 * P06 统计：一页四段，顶部两行定范围，换范围只重算数字不换页。
 * 段序按设计板 §12——收支总览 → 每日统计 → 分类报表 → 日报表 / 月报表。
 * 右下角那颗「分析本月」是唯一的 AI 入口：点一次算一次，没点这一屏里没有任何 AI 痕迹。
 */
@Composable
fun TallyStatsScreen(
    initialLedgerId: String? = null,
    onBack: () -> Unit,
    onOpenReimbursement: () -> Unit,
    onAskAssistant: (String) -> Unit = {},
    onOpenBillEdit: (String) -> Unit = {},
    onRepeatBill: (l1Id: String?, l2Id: String?, type: BillType?) -> Unit = { _, _, _ -> },
    onNavigateRoute: (String) -> Unit = {},
) {
    val graph = rememberAppGraph()
    val viewModel = tallyViewModel { StatsViewModel(graph, initialLedgerId) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    // 预算段在概览之后：只取总额与分类预算前两条，与首页、组件同一份聚合。
    val budget by viewModel.budgetSummary.collectAsStateWithLifecycle()
    val colors = tallyColors
    val snapshot = state.snapshot
    var periodPicker by remember { mutableStateOf(false) }
    var rangePicker by remember { mutableStateOf(false) }
    var analysisSheet by remember { mutableStateOf(false) }
    /** 海报入口被按下但这段没账时的那句提示（SH-01 的置灰态）。 */
    var posterHint by remember { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()
    // 悬浮球只关心"滚出去没有"：直接读列表状态会让整页跟着每一帧滚动重组，
    // derivedStateOf 把它收敛成"跨过阈值的那一帧"。
    val pillCollapsed by remember {
        derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 24 }
    }
    val grouping = state.settings.showThousandsSeparator
    val yearMode = state.granularity == StatsGranularity.YEAR
    val rangeMode = state.granularity == StatsGranularity.RANGE
    // 月维度沿用首页的周期标签，区间写「9/12–10/11」：四段标题和 AI 弹层标题跟着它走。
    val periodLabel = state.periodLabel
    val analysis = tallyViewModel(key = "stats-analysis") { AssistantAnalysisViewModel(graph) }
    val analysisState by analysis.uiState.collectAsStateWithLifecycle()
    // 没开小莉或模型未配置时，这颗胶囊直接不出现——而不是点开后让用户读一段「你还没配置」。
    val aiEntryVisible by viewModel.aiEntryVisible.collectAsStateWithLifecycle()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.paper)
            // 左右滑翻周期：和顶栏的 ‹ › 是同一个动作，滑过屏宽 22% 才算，避免误触。
            .pointerInput(state.granularity, state.anchorStart) {
                var dragged = 0f
                detectHorizontalDragGestures(
                    onDragStart = { dragged = 0f },
                    onDragEnd = {
                        val threshold = size.width * 0.22f
                        when {
                            dragged > threshold -> viewModel.previous()
                            dragged < -threshold -> viewModel.next()
                        }
                    },
                    onHorizontalDrag = { _, amount -> dragged += amount },
                )
            },
    ) {
    Column(modifier = Modifier.fillMaxSize()) {
        TallyTopBar(
            title = "统计",
            onBack = onBack,
            trailing = {
                // 账本在这儿只读：要换就回首页换，统计跟着走——右上角放两枚可点的胶囊太挤，
                // 而且「这一段看到的是哪个范围」必须一眼确定，不给就地改的机会。
                TallyTag(text = state.ledgerName, tone = TagTone.OUTLINE)
                // 海报的入口就是这一颗（SH-01）：跟随账本 + 维度 + 区间，进去不用再选一次。
                // 这一段一笔都没有时画不出东西，置灰并说原因，不给一张空纸。
                val posterReady = (snapshot?.summary?.billCount ?: 0) > 0
                TallyIconAction(
                    iconKey = "share",
                    contentDescription = "生成长图",
                    modifier = if (posterReady) Modifier else Modifier.alpha(0.4f),
                    onClick = {
                        if (snapshot != null && posterReady) {
                            onNavigateRoute(Routes.poster(state.analysisRequest()))
                        } else {
                            posterHint = "这段时间还没有账单"
                        }
                    },
                )
            },
        )
        RangeRow(
            state = state,
            onPrevious = viewModel::previous,
            onNext = viewModel::next,
            onPickPeriod = { if (rangeMode) rangePicker = true else periodPicker = true },
            onGranularity = viewModel::setGranularity,
        )

        // 四个区块各占一个 item：以前整页是 verticalScroll 的一根 Column，进统计页那一下要把
        // 31 根柱子、排名条和整张日报表一次性组合完，掉帧就掉在那一帧。
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(start = 15.dp, end = 15.dp, bottom = 22.dp),
        ) {
            when {
                snapshot == null -> item(key = "loading") {
                    Text("正在计算统计…", style = TallyType.helper, color = colors.ink3)
                }

                snapshot.summary.billCount == 0 -> item(key = "empty") {
                    TallyEmptyState(
                        title = "这个周期还没有账单",
                        description = "",
                        iconKey = "chart",
                    )
                }

                else -> {
                    item(key = "overview") {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            BlockHeader("收支总览", right = overviewCaption(state, snapshot))
                            OverviewSection(snapshot, grouping)
                        }
                    }

                    // 预算段在概览之后、日报表之前：它回答的是「这一段还剩多少能花」，是概览的下一层。
                    // 年维度读的是「每年预算」那一组，那一段没设就把整段撤掉，不留空标题。
                    budget?.takeIf { it.hasBudget }?.let { summary ->
                        item(key = "budget") {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Spacer(Modifier.height(16.dp))
                                BlockHeader(
                                    title = if (summary.period == io.github.guohangbk.tally.domain.model.BudgetPeriod.YEARLY) {
                                        "年度预算"
                                    } else {
                                        "本月预算"
                                    },
                                    right = summary.total?.let { "已用 ${budgetRatioText(it)}" },
                                )
                                StatsBudgetSection(summary = summary, grouping = grouping, onOpenBudget = { onNavigateRoute(Routes.BUDGET) })
                            }
                        }
                    }

                    item(key = "daily") {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Spacer(Modifier.height(16.dp))
                            BlockHeader(
                                title = when {
                                    rangeMode -> when (state.bucket) {
                                        StatsBucket.WEEK -> "每周趋势"
                                        StatsBucket.MONTH -> "每月趋势"
                                        else -> "每日趋势"
                                    }
                                    yearMode -> "每月统计"
                                    else -> "每日统计"
                                },
                                trailing = {
                                    MiniSwitch(
                                        options = listOf(BillType.EXPENSE to "支出", BillType.INCOME to "收入"),
                                        selected = state.dailySeries,
                                        onSelect = viewModel::selectDailySeries,
                                        small = true,
                                    )
                                },
                            )
                            if (rangeMode) {
                                Row(modifier = Modifier.fillMaxWidth().padding(bottom = 7.dp)) {
                                    MiniSwitch(
                                        options = listOf(
                                            StatsBucket.DAY to "日",
                                            StatsBucket.WEEK to "周",
                                            StatsBucket.MONTH to "月",
                                        ),
                                        selected = state.bucket ?: StatsBucket.DAY,
                                        onSelect = viewModel::setBucket,
                                        small = true,
                                    )
                                }
                            }
                            DailySection(state, snapshot, grouping, viewModel::selectBucket)
                        }
                    }

                    item(key = "weekday") {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Spacer(Modifier.height(16.dp))
                            val weekdayExpense = state.weekdaySeries == BillType.EXPENSE
                            val weekdayAverages = if (weekdayExpense) snapshot.weekdayAverageMinor else snapshot.weekdayAverageIncomeMinor
                            val weekdayPicked = state.weekdayPicked
                                ?: if (weekdayExpense) snapshot.weekdayPeak else snapshot.weekdayIncomePeak
                            BlockHeader(
                                title = "星期分布",
                                right = if (weekdayPicked >= 0) {
                                    "周${weekdayBarLabels[weekdayPicked]} 日均 ${formatAmount(weekdayAverages.getOrNull(weekdayPicked) ?: 0L, grouping)}"
                                } else {
                                    null
                                },
                                trailing = {
                                    MiniSwitch(
                                        options = listOf(BillType.EXPENSE to "支出", BillType.INCOME to "收入"),
                                        selected = state.weekdaySeries,
                                        onSelect = viewModel::selectWeekdaySeries,
                                        small = true,
                                    )
                                },
                            )
                            WeekdayBars(
                                snapshot = snapshot,
                                expense = weekdayExpense,
                                picked = weekdayPicked,
                                onSelectDay = viewModel::selectWeekday,
                            )
                        }
                    }

                    item(key = "ranking") {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Spacer(Modifier.height(16.dp))
                            BlockHeader(
                                title = "分类报表",
                                trailing = {
                                    MiniSwitch(
                                        options = listOf(BillType.EXPENSE to "支出", BillType.INCOME to "收入"),
                                        selected = state.rankingSeries,
                                        onSelect = viewModel::selectRankingSeries,
                                        small = true,
                                    )
                                },
                            )
                            RankingSection(
                                state,
                                snapshot,
                                grouping,
                                viewModel::setExcludePendingReimburse,
                                onOpenReimbursement,
                                onOpenCategory = { viewModel.openCategory(it, state.rankingSeries) },
                            )
                        }
                    }

                    item(key = "table") {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Spacer(Modifier.height(16.dp))
                            val bucket = effectiveBucket(snapshot)
                            val tableTitle = when {
                                yearMode -> "月报表"
                                rangeMode -> when (bucket) {
                                    StatsBucket.WEEK -> "周报表"
                                    StatsBucket.MONTH -> "月报表"
                                    else -> "日报表"
                                }
                                else -> "日报表"
                            }
                            val tableNote = when {
                                rangeMode -> "区间汇总"
                                yearMode -> "按月汇总"
                                else -> "按日汇总"
                            }
                            BlockHeader(tableTitle, right = tableNote)
                            DailyTableSection(snapshot, grouping, viewModel::openBucketBills)
                        }
                    }

                    item(key = "tail") {
                        Text(
                            text = "到底了",
                            fontSize = 10.sp, lineHeight = 12.5.sp,
                            letterSpacing = 0.14.em,
                            color = colors.ink3,
                            modifier = Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 1.dp),
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }
    }

    // .aifab：入口常驻，内容点了才开始算。周期与口径都跟着这一屏走，报告才可能对得上格子。
    if (aiEntryVisible) {
        AnalysisPill(
            label = when {
                yearMode -> "分析本年"
                rangeMode -> "分析区间"
                else -> "分析本月"
            },
            busy = analysisState.working,
            collapsed = pillCollapsed,
            onClick = {
                analysisSheet = true
                analysis.open(state.analysisRequest())
            },
        )
    }
    }

    if (analysisSheet) {
        StatsAnalysisSheet(
            state = analysisState,
            periodLabel = periodLabel,
            billCount = snapshot?.summary?.billCount ?: 0,
            categoryCount = snapshot?.expenseCategoryCount ?: 0,
            onGenerate = { analysis.analyze(force = true) },
            onAsk = { report -> onAskAssistant(report) },
            // 看完这段结论想转出去，冲动点就在这里（SH-01 的第二处入口）。
            onPoster = {
                analysisSheet = false
                if ((snapshot?.summary?.billCount ?: 0) > 0) {
                    onNavigateRoute(Routes.poster(state.analysisRequest()))
                } else {
                    posterHint = "这段时间还没有账单"
                }
            },
            onDismiss = {
                if (analysisState.working) analysis.cancel()
                analysisSheet = false
            },
        )
    }

    TallyToastEffects(
        notice = posterHint,
        error = null,
        onNoticeShown = { posterHint = null },
    )

    if (periodPicker) {
        StatsPeriodPicker(
            granularity = state.granularity,
            anchor = state.anchorStart,
            startDay = state.startDay,
            onDismiss = { periodPicker = false },
            onConfirm = { millis ->
                viewModel.pickPeriod(millis)
                periodPicker = false
            },
        )
    }
    if (rangePicker) {
        StatsRangePicker(
            rangeStart = state.anchorStart,
            rangeEndExclusive = state.rangeEndExclusive,
            onPreset = { preset -> viewModel.applyPreset(preset) },
            onDismiss = { rangePicker = false },
            onConfirm = { start, endExclusive ->
                viewModel.setRange(start, endExclusive)
                rangePicker = false
            },
        )
    }

    // 分类报表下钻：一级弹层给这一级的拆分行，点拆分行进账单列表；点账单进账单详情弹层。
    val drill by viewModel.drill.collectAsStateWithLifecycle()
    val drillBills by viewModel.drillBillsState.collectAsStateWithLifecycle()
    val bucketBills by viewModel.bucketBills.collectAsStateWithLifecycle()
    var detailBillId by remember { mutableStateOf<String?>(null) }
    drill?.let { target ->
        CategoryDrillSheet(
            drill = target,
            bills = drillBills,
            grouping = grouping,
            onDismiss = viewModel::closeCategory,
            onDrillInto = viewModel::drillInto,
            onOpenBill = { detailBillId = it },
        )
    }
    bucketBills?.let { (start, list) ->
        val snap = snapshot ?: return@let
        val (label, amountMinor, positive) = bucketSheetSummary(start, snap)
        BillListSheet(
            label = label,
            count = list.size,
            amountMinor = amountMinor,
            positive = positive,
            bills = list,
            grouping = grouping,
            onDismiss = viewModel::closeBucketBills,
            onOpenBill = { detailBillId = it },
        )
    }
    detailBillId?.let { id ->
        BillDetailSheet(
            billId = id,
            onDismiss = { detailBillId = null },
            onEdit = { editId ->
                detailBillId = null
                onOpenBillEdit(editId)
            },
            onRepeat = { l1, l2, type ->
                detailBillId = null
                onRepeatBill(l1, l2, type)
            },
            onOpenBill = { detailBillId = it },
        )
    }
}

/** 顶栏下面那一行：月/年/区间维度 + 周期步进。 */
@Composable
private fun RangeRow(
    state: StatsUiState,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onPickPeriod: () -> Unit,
    onGranularity: (StatsGranularity) -> Unit,
) {
    val colors = tallyColors
    val label = state.periodLabel
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 15.dp, end = 15.dp, top = 3.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        MiniSwitch(
            options = listOf(
                StatsGranularity.MONTH to "月",
                StatsGranularity.YEAR to "年",
                StatsGranularity.RANGE to "区间",
            ),
            selected = state.granularity,
            onSelect = onGranularity,
        )
        Spacer(Modifier.weight(1f))
        StepKey(direction = -1, onClick = onPrevious)
        Text(
            text = "$label ▾",
            style = TallyType.listTitle.copy(fontSize = 14.5.sp, lineHeight = 18.1.sp, fontWeight = FontWeight.SemiBold),
            color = colors.ink,
            modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onPickPeriod),
            maxLines = 1,
        )
        StepKey(direction = 1, onClick = onNext)
    }
}

@Composable
private fun StepKey(direction: Int, onClick: () -> Unit) {
    val colors = tallyColors
    Box(
        modifier = Modifier
            .size(width = 20.dp, height = 26.dp)
            .clip(RoundedCornerShape(7.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        InlineIcon(
            iconKey = "chevron",
            contentDescription = if (direction < 0) "上一档" else "下一档",
            size = 12.dp,
            tint = colors.ink3,
            modifier = if (direction < 0) Modifier.rotate(180f) else Modifier,
        )
    }
}

/** 设计板 .msw：紧凑维度/系列切换，small 对应 .msw.sm。 */
@Composable
private fun <T> MiniSwitch(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    small: Boolean = false,
) {
    val colors = tallyColors
    val outerCorner = if (small) 8.dp else 11.dp
    val innerCorner = if (small) 7.dp else 8.dp
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(outerCorner))
            .background(colors.ink.copy(alpha = if (colors.isDark) 0.08f else 0.06f))
            .padding(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        options.forEach { (value, label) ->
            val active = value == selected
            Text(
                text = label,
                fontSize = if (small) 10.sp else 11.5.sp,
                lineHeight = if (small) 13.sp else 16.sp,
                fontWeight = FontWeight.Bold,
                color = if (active) colors.ink else colors.ink3,
                modifier = Modifier
                    .clip(RoundedCornerShape(innerCorner))
                    .background(if (active) colors.card else Color.Transparent)
                    .then(if (active) Modifier.border(1.dp, colors.line, RoundedCornerShape(innerCorner)) else Modifier)
                    .clickable { onSelect(value) }
                    .padding(horizontal = if (small) 10.dp else 13.dp, vertical = if (small) 1.5.dp else 3.dp),
            )
        }
    }
}

/** 设计板 .blk：段标题 + 发丝线 + 右侧口径注。 */
@Composable
private fun BlockHeader(title: String, right: String? = null, trailing: (@Composable () -> Unit)? = null) {
    val colors = tallyColors
    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Text(
            text = title,
            style = TallyType.eyebrow.copy(fontSize = 11.sp, lineHeight = 13.8.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.14.em),
            color = colors.ink3,
        )
        Box(Modifier.weight(1f).height(1.dp).background(colors.line))
        if (right != null) {
            Text(text = right, fontSize = 10.sp, lineHeight = 12.5.sp, fontWeight = FontWeight.SemiBold, color = colors.ink3, maxLines = 1)
        }
        trailing?.invoke()
    }
}

private fun overviewCaption(state: StatsUiState, snapshot: StatisticsSnapshot): String = when (state.granularity) {
    StatsGranularity.MONTH -> "${snapshot.summary.billCount} 笔"
    StatsGranularity.YEAR -> "至 ${elapsedMonthOrdinal(snapshot)} 月"
    StatsGranularity.RANGE -> {
        val end = snapshot.rangeEndExclusive ?: Period.shiftDays(state.anchorStart, 1)
        "${Period.daysBetween(state.anchorStart, end)} 天 · ${snapshot.summary.billCount} 笔"
    }
}

/** 年视图的「至 X 月」：本年度取到今天所在的月，历史年份取满 12 月。 */
private fun elapsedMonthOrdinal(snapshot: StatisticsSnapshot): Int {
    val anchor = millisToLocalDate(snapshot.monthStart)
    val today = LocalDate.now(statsZone)
    return if (anchor.year == today.year) today.monthValue else 12
}

/** 设计板 .ov：2×2 汇总格，1px 分隔线，数字衬线。 */
@Composable
private fun OverviewSection(snapshot: StatisticsSnapshot, grouping: Boolean) {
    val summary = snapshot.summary
    val colors = tallyColors
    val yearMode = snapshot.granularity == StatsGranularity.YEAR
    TallyCard(corner = 15, contentPadding = 0.dp) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(modifier = Modifier.fillMaxWidth()) {
                OvCell("支出", summary.expenseMinor, colors.expense, yearMode, grouping)
                OvDivider(vertical = true)
                OvCell("收入", summary.incomeMinor, colors.income, yearMode, grouping)
            }
            OvDivider(vertical = false)
            Row(modifier = Modifier.fillMaxWidth()) {
                OvCell("结余", summary.balanceMinor, colors.ink, yearMode, grouping, signed = true)
                OvDivider(vertical = true)
                OvCell(averageLabel(snapshot), snapshot.averageExpenseMinor, colors.ink3, yearMode, grouping)
            }
        }
    }
}

/** 统计页的预算段：总额一行 + 分类预算前两条，与首页速览同一行紧凑写法（样式太大、重复表达都撤了）。 */
@Composable
private fun StatsBudgetSection(
    summary: io.github.guohangbk.tally.domain.model.BudgetSummary,
    grouping: Boolean,
    onOpenBudget: () -> Unit,
) {
    val colors = tallyColors
    val categories = summary.categories
    Column(modifier = Modifier.fillMaxWidth()) {
        summary.total?.let {
            BudgetUsageRow(usage = it, title = "总额", iconKey = "coin", grouping = grouping, onClick = onOpenBudget)
        }
        // 分类预算不进分类报表，避免两处打架；这里只给前两条，其余走预算页。
        categories.take(2).forEach { usage ->
            BudgetUsageRow(
                usage = usage,
                title = usage.item.categoryName ?: "分类",
                iconKey = usage.item.categoryIconKey ?: "tag",
                grouping = grouping,
                onClick = onOpenBudget,
            )
        }
        if (categories.size > 2) {
            Text(
                text = "还有 ${categories.size - 2} 条 · 看预算页",
                style = TallyType.helper,
                color = colors.ink3,
                modifier = Modifier.clickable(onClick = onOpenBudget).padding(top = 2.dp),
            )
        }
    }
}

@Composable
private fun RowScope.OvCell(
    label: String,
    amountMinor: Long,
    valueColor: Color,
    yearMode: Boolean,
    grouping: Boolean,
    signed: Boolean = false,
) {
    val colors = tallyColors
    val sign = when {
        !signed -> ""
        amountMinor > 0 -> "+"
        amountMinor < 0 -> "−"
        else -> ""
    }
    Column(modifier = Modifier.weight(1f).padding(horizontal = 11.dp, vertical = 8.dp)) {
        Text(label, fontSize = 10.sp, lineHeight = 12.5.sp, letterSpacing = 0.06.em, color = colors.ink3, maxLines = 1)
        Spacer(Modifier.height(2.dp))
        Text(
            text = sign + formatAmount(abs(amountMinor), grouping),
            style = TallyType.moneyBody.copy(
                fontSize = if (yearMode) 17.sp else 20.sp,
                fontWeight = FontWeight.SemiBold,
                lineHeight = if (yearMode) 21.sp else 24.sp,
            ),
            color = valueColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun OvDivider(vertical: Boolean) {
    val colors = tallyColors
    if (vertical) {
        Box(Modifier.width(1.dp).fillMaxHeight().background(colors.line))
    } else {
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.line))
    }
}

@Composable
private fun DailySection(
    state: StatsUiState,
    snapshot: StatisticsSnapshot,
    grouping: Boolean,
    onPickBucket: (Long) -> Unit,
) {
    val colors = tallyColors
    val expense = state.dailySeries == BillType.EXPENSE
    val valueOf: (DailyTotal) -> Long = if (expense) DailyTotal::expenseMinor else DailyTotal::incomeMinor
    val buckets = snapshot.dailyTrend
    val maxValue = max(1L, buckets.maxOfOrNull(valueOf) ?: 1L)
    val selected = buckets.firstOrNull { it.dayStart == state.selectedBucketStart }
        ?: buckets.filterNot { it.isFuture }.maxByOrNull(valueOf)
        ?: buckets.firstOrNull()
    val samePeriod = if (expense) snapshot.previousExpenseMinor else snapshot.previousIncomeMinor
    val change = if (expense) snapshot.expenseChangePercent else snapshot.incomeChangePercent
    val periodWord = when (snapshot.granularity) {
        StatsGranularity.MONTH -> "上月同期"
        StatsGranularity.YEAR -> "去年同期"
        StatsGranularity.RANGE -> "上期"
    }

    TallyCard(corner = 15, contentPadding = 0.dp) {
        Column(modifier = Modifier.fillMaxWidth().padding(start = 11.dp, end = 11.dp, top = 10.dp, bottom = 8.dp)) {
            val rangeMode = snapshot.granularity == StatsGranularity.RANGE
            // 只有一个桶时画折线等于画一个点：图表省掉，下面的选中行和上期对比还在。
            if (rangeMode && buckets.size >= 2) {
                TrendLineChart(
                    snapshot = snapshot,
                    buckets = buckets,
                    maxValue = maxValue,
                    valueOf = valueOf,
                    selected = selected,
                    expense = expense,
                    onSelect = onPickBucket,
                )
            } else if (!rangeMode) {
                BarsChart(
                    buckets = buckets,
                    maxValue = maxValue,
                    valueOf = valueOf,
                    selected = selected,
                    expense = expense,
                    yearMode = snapshot.granularity == StatsGranularity.YEAR,
                    onSelect = onPickBucket,
                )
            }
            if (selected != null) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    Text(
                        text = bucketLabel(selected.dayStart, snapshot),
                        style = TallyType.listTitle.copy(fontSize = 12.5.sp, lineHeight = 15.6.sp, fontWeight = FontWeight.Bold),
                        color = colors.ink,
                    )
                    Box(Modifier.width(1.dp).height(11.dp).background(colors.line))
                    Text(
                        text = bucketNote(selected, snapshot, valueOf(selected) == maxValue && maxValue > 1L),
                        fontSize = 10.5.sp, lineHeight = 13.1.sp,
                        color = colors.ink3,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.weight(1f))
                    MoneyText(
                        amountMinor = valueOf(selected),
                        sign = MoneySign.NONE,
                        style = TallyType.moneyList.copy(fontSize = 17.sp),
                        color = if (expense) colors.expense else colors.income,
                        grouping = grouping,
                        showCurrency = false,
                    )
                }
                Spacer(Modifier.height(7.dp))
                Box(Modifier.fillMaxWidth().height(1.dp).background(colors.line))
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                Text(
                    text = "$periodWord ${formatAmount(samePeriod, grouping)}",
                    fontSize = 10.5.sp, lineHeight = 13.1.sp,
                    color = colors.ink2,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = when {
                        change == null -> "上期无${if (expense) "支出" else "收入"}"
                        change >= 0 -> "+${"%.1f".format(change)}%"
                        else -> "−${"%.1f".format(abs(change))}%"
                    },
                    style = TallyType.moneyList.copy(fontSize = 12.sp, lineHeight = 15.sp, fontWeight = FontWeight.Bold),
                    color = when {
                        change == null -> colors.ink3
                        change >= 0 -> colors.expense
                        else -> colors.brand
                    },
                )
            }
        }
    }
}

/** 桶的标题：按日写「10-26」，按周写起止，按月写「10 月」（跨年区间带上年份）。 */
private fun bucketLabel(millis: Long, snapshot: StatisticsSnapshot): String {
    val date = millisToLocalDate(millis)
    return when (effectiveBucket(snapshot)) {
        StatsBucket.DAY -> if (snapshot.granularity == StatsGranularity.MONTH) {
            "%02d-%02d".format(date.monthValue, date.dayOfMonth)
        } else {
            "${date.monthValue}/${date.dayOfMonth}"
        }
        StatsBucket.WEEK -> {
            val end = date.plusDays(6)
            if (date.year == end.year) "${date.monthValue}/${date.dayOfMonth}–${end.monthValue}/${end.dayOfMonth}"
            else "${date.monthValue}/${date.dayOfMonth}–${end.year}/${end.monthValue}/${end.dayOfMonth}"
        }
        StatsBucket.MONTH -> if (spansYears(snapshot)) "${date.year % 100}/${date.monthValue}" else "${date.monthValue} 月"
    }
}

private fun effectiveBucket(snapshot: StatisticsSnapshot): StatsBucket = when (snapshot.granularity) {
    StatsGranularity.MONTH -> StatsBucket.DAY
    StatsGranularity.YEAR -> StatsBucket.MONTH
    StatsGranularity.RANGE -> snapshot.bucket ?: StatsBucket.DAY
}

private fun spansYears(snapshot: StatisticsSnapshot): Boolean {
    val start = millisToLocalDate(snapshot.monthStart)
    val end = snapshot.rangeEndExclusive?.let { millisToLocalDate(it) } ?: return false
    return start.year != end.year
}

/**
 * 日/月报表弹层顶部那一行：日期或周期在左，笔数与合计在右。
 * 合计取表格里同一个桶的数字，弹层与表格不会是两套口径。
 */
private fun bucketSheetSummary(start: Long, snapshot: StatisticsSnapshot): Triple<String, Long, Boolean> {
    val label = when (effectiveBucket(snapshot)) {
        StatsBucket.MONTH -> formatMonth(start)
        StatsBucket.WEEK -> bucketLabel(start, snapshot)
        else -> formatDay(start)
    }
    val total = snapshot.dailyTrend.firstOrNull { it.dayStart == start }
    val expense = total?.expenseMinor ?: 0L
    val income = total?.incomeMinor ?: 0L
    val positive = expense <= 0L && income > 0L
    return Triple(label, if (positive) income else expense, positive)
}

/** 桶的量词：日均、周均、月均和「最高的一天/一周/一月」都靠它。 */
private fun bucketUnit(snapshot: StatisticsSnapshot): String = when (effectiveBucket(snapshot)) {
    StatsBucket.DAY -> "天"
    StatsBucket.WEEK -> "周"
    StatsBucket.MONTH -> "月"
}

private fun averageLabel(snapshot: StatisticsSnapshot): String = when (effectiveBucket(snapshot)) {
    StatsBucket.DAY -> "日均支出"
    StatsBucket.WEEK -> "周均支出"
    StatsBucket.MONTH -> "月均支出"
}

private fun weekdayOf(millis: Long): String = "周" + weekdayLabels[millisToLocalDate(millis).dayOfWeek.value % 7]

private fun bucketNote(bucket: DailyTotal, snapshot: StatisticsSnapshot, isPeak: Boolean): String {
    val parts = mutableListOf<String>()
    if (effectiveBucket(snapshot) == StatsBucket.DAY) parts += weekdayOf(bucket.dayStart)
    parts += "${bucket.billCount} 笔"
    if (isPeak) parts += "全${bucketUnit(snapshot)}最高"
    if (bucket.isFuture) parts += "还没到"
    return parts.joinToString(" · ")
}

@Composable
private fun BarsChart(
    buckets: List<DailyTotal>,
    maxValue: Long,
    valueOf: (DailyTotal) -> Long,
    selected: DailyTotal?,
    expense: Boolean,
    yearMode: Boolean,
    onSelect: (Long) -> Unit,
) {
    val colors = tallyColors
    val chartHeight = if (yearMode) 82.dp else 152.dp
    val barColor = if (expense) colors.expense else colors.brand
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Column(
            modifier = Modifier.width(25.dp).height(chartHeight),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.End,
        ) {
            listOf(maxValue, maxValue / 2, 0L).forEach { tick ->
                Text(
                    text = if (tick <= 0L) "0" else (tick / 100).toString(),
                    style = TallyType.moneyList.copy(fontSize = 8.5.sp, lineHeight = 10.6.sp, fontWeight = FontWeight.Normal),
                    color = colors.ink3,
                    maxLines = 1,
                )
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Row(modifier = Modifier.fillMaxWidth().height(chartHeight)) {
                buckets.forEach { bucket ->
                    val amount = valueOf(bucket)
                    val fraction = (amount.toFloat() / maxValue.toFloat()).coerceIn(0f, 1f)
                    val isActive = bucket.dayStart == selected?.dayStart
                    Box(
                        modifier = Modifier.weight(1f).fillMaxHeight().clickable { onSelect(bucket.dayStart) },
                        contentAlignment = Alignment.BottomCenter,
                    ) {
                        when {
                            bucket.isFuture -> HatchBar(Modifier.fillMaxWidth().height(6.dp))
                            amount <= 0L -> Box(
                                Modifier
                                    .fillMaxWidth()
                                    .height(2.5.dp)
                                    .background(colors.ink.copy(alpha = 0.1f), RoundedCornerShape(1.dp, 1.dp, 0.dp, 0.dp)),
                            )
                            else -> Box(
                                Modifier
                                    .fillMaxWidth()
                                    .height(chartHeight * fraction)
                                    .clip(RoundedCornerShape(2.dp, 2.dp, 0.dp, 0.dp))
                                    .background(if (isActive) barColor else barColor.copy(alpha = 0.36f)),
                            )
                        }
                    }
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(colors.line))
            Spacer(Modifier.height(4.dp))
            // 标签与柱子同一套等分：每根柱子上方一个格子，标签中心正好压在对应柱子的中心上。
            // 日期桶印 1 / 10 / 20 号与末日——按实际存在的日子印，不按固定第几根。
            Row(modifier = Modifier.fillMaxWidth()) {
                buckets.forEachIndexed { index, _ ->
                    val label = barAxisLabel(index, buckets, yearMode)
                    Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        label?.let {
                            Text(
                                text = it,
                                style = TallyType.helper.copy(fontSize = 8.5.sp, lineHeight = 10.6.sp, fontWeight = FontWeight.Normal),
                                color = colors.ink3,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 柱状图轴标签：日期桶印 1 / 10 / 20 号与末日（按桶里真实的日子，没有就跳过）；年视图按月号印 1 / 4 / 7 / 10 / 12。 */
private fun barAxisLabel(index: Int, buckets: List<DailyTotal>, yearMode: Boolean): String? = when {
    yearMode -> when (index) {
        0 -> "1"
        3 -> "4"
        6 -> "7"
        9 -> "10"
        buckets.lastIndex -> "12"
        else -> null
    }
    index == buckets.lastIndex -> millisToLocalDate(buckets[index].dayStart).dayOfMonth.toString()
    else -> {
        val day = millisToLocalDate(buckets[index].dayStart).dayOfMonth
        if (day == 1 || day == 10 || day == 20) day.toString() else null
    }
}

/** 未来周期画斜纹空柱：位置留着，数字不编。 */
@Composable
private fun HatchBar(modifier: Modifier) {
    val hatch = tallyColors.ink.copy(alpha = 0.16f)
    Canvas(modifier = modifier) {
        val step = 4.dp.toPx()
        var x = 0f
        while (x < size.width) {
            val run = min(size.height, size.width - x)
            drawLine(hatch, Offset(x, size.height), Offset(x + run, size.height - run), strokeWidth = 1.6.dp.toPx())
            x += step
        }
    }
}

/**
 * 区间趋势折线：桶一多柱子就挤成栅栏，折线才看得出走势。
 * 点按选最近的那个桶，选中值和下方标签与柱状图共用同一套状态。
 */
@Composable
private fun TrendLineChart(
    snapshot: StatisticsSnapshot,
    buckets: List<DailyTotal>,
    maxValue: Long,
    valueOf: (DailyTotal) -> Long,
    selected: DailyTotal?,
    expense: Boolean,
    onSelect: (Long) -> Unit,
) {
    val colors = tallyColors
    val chartHeight = 152.dp
    val lineColor = if (expense) colors.expense else colors.brand
    val hitBuckets = buckets
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Column(
            modifier = Modifier.width(25.dp).height(chartHeight),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.End,
        ) {
            listOf(maxValue, maxValue / 2, 0L).forEach { tick ->
                Text(
                    text = if (tick <= 0L) "0" else (tick / 100).toString(),
                    style = TallyType.moneyList.copy(fontSize = 8.5.sp, lineHeight = 10.6.sp, fontWeight = FontWeight.Normal),
                    color = colors.ink3,
                    maxLines = 1,
                )
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(chartHeight)
                    .pointerInput(hitBuckets, valueOf) {
                        detectTapGestures { offset ->
                            if (hitBuckets.isEmpty()) return@detectTapGestures
                            val step = size.width.toFloat() / hitBuckets.size
                            val index = (offset.x / step).toInt().coerceIn(0, hitBuckets.lastIndex)
                            onSelect(hitBuckets[index].dayStart)
                        }
                    },
            ) {
                val count = buckets.size
                if (count == 0) return@Canvas
                val step = size.width / count
                val bottom = size.height - 1.dp.toPx()
                fun point(index: Int): Offset {
                    val value = valueOf(buckets[index]).coerceAtLeast(0L)
                    val fraction = (value.toFloat() / maxValue.toFloat()).coerceIn(0f, 1f)
                    return Offset(step * (index + 0.5f), bottom - fraction * (bottom - 3.dp.toPx()))
                }
                drawLine(colors.line, Offset(0f, bottom), Offset(size.width, bottom), strokeWidth = 1.dp.toPx())
                val path = Path()
                buckets.indices.forEach { index ->
                    val p = point(index)
                    if (index == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
                }
                drawPath(path, lineColor, style = Stroke(width = 1.8.dp.toPx(), cap = StrokeCap.Round))
                selected?.let { pick ->
                    val index = buckets.indexOfFirst { it.dayStart == pick.dayStart }
                    if (index >= 0) {
                        val p = point(index)
                        drawCircle(lineColor, radius = 3.5.dp.toPx(), center = p)
                        drawCircle(colors.card, radius = 1.5.dp.toPx(), center = p)
                    }
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(colors.line))
            Spacer(Modifier.height(4.dp))
            // 刻度与数据点同一套坐标：数据点落在 (i + 0.5) / n，刻度也按格子居中，
            // 首 / 中 / 末三格各印一个，不会挤在 0 / 0.5 / 1 上指错桶。
            val tickIndices = setOf(0, buckets.size / 2, buckets.lastIndex)
            Row(modifier = Modifier.fillMaxWidth()) {
                buckets.forEachIndexed { index, bucket ->
                    Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        if (index in tickIndices) {
                            Text(
                                text = bucketLabel(bucket.dayStart, snapshot),
                                style = TallyType.helper.copy(fontSize = 8.5.sp, lineHeight = 10.6.sp, fontWeight = FontWeight.Normal),
                                color = colors.ink3,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 星期分布：周一到周日七根柱，按自然日累计净额（退款冲抵支出）。
 * 柱子画的是「该星期几平均每天多少」——分母是它在周期里出现过的天数，
 * 否则多出一个周一就会被记成花得更多，而不是同一天花得更多。
 * 柱子可点：右上角跟着点开的那根走。
 */
@Composable
private fun WeekdayBars(
    snapshot: StatisticsSnapshot,
    expense: Boolean,
    picked: Int,
    onSelectDay: (Int) -> Unit,
) {
    val colors = tallyColors
    val labels = listOf("一", "二", "三", "四", "五", "六", "日")
    val averages = if (expense) snapshot.weekdayAverageMinor else snapshot.weekdayAverageIncomeMinor
    if (averages.size != 7 || averages.all { it <= 0L }) return
    val maxValue = max(1L, averages.maxOrNull() ?: 0L)
    val barColor = if (expense) colors.expense else colors.brand
    TallyCard(corner = 15, contentPadding = 0.dp) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().height(70.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                averages.forEachIndexed { index, average ->
                    val fraction = (maxOf(0L, average).toFloat() / maxValue.toFloat()).coerceIn(0f, 1f)
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height((60.dp * fraction).coerceAtLeast(2.dp))
                            .clip(RoundedCornerShape(3.dp, 3.dp, 1.dp, 1.dp))
                            .background(if (index == picked) barColor else barColor.copy(alpha = 0.32f))
                            .clickable { onSelectDay(index) },
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                labels.forEachIndexed { index, label ->
                    Text(
                        text = label,
                        fontSize = 9.5.sp, lineHeight = 11.9.sp,
                        fontWeight = if (index == picked) FontWeight.Bold else FontWeight.Normal,
                        color = if (index == picked) barColor else colors.ink3,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun RankingSection(
    state: StatsUiState,
    snapshot: StatisticsSnapshot,
    grouping: Boolean,
    onToggleExcludePending: (Boolean) -> Unit,
    onReimburse: () -> Unit,
    onOpenCategory: (CategoryTotal) -> Unit,
) {
    val colors = tallyColors
    val expense = state.rankingSeries == BillType.EXPENSE
    val ranking = if (expense) snapshot.categoryRanking else snapshot.incomeRanking
    val total = if (expense) snapshot.summary.expenseMinor else snapshot.summary.incomeMinor
    val categoryCount = if (expense) snapshot.expenseCategoryCount else snapshot.incomeCategoryCount
    val palette = statsPalette()
    // 点了饼图哪一块，右边对应那一行就亮起来，并弹出它的分类统计。
    var picked by remember(expense, snapshot) { mutableStateOf<Int?>(null) }

    TallyCard(corner = 15, contentPadding = 0.dp) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 13.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(13.dp),
        ) {
            Donut(
                ranking = ranking,
                palette = palette,
                totalLabel = if (expense) "支出" else "收入",
                totalMinor = total,
                grouping = grouping,
                selected = picked,
                onSelectSlice = { index ->
                    picked = index
                    onOpenCategory(ranking[index])
                },
            )
            Column(modifier = Modifier.weight(1f)) {
                MoneyText(
                    amountMinor = total,
                    sign = MoneySign.NONE,
                    style = TallyType.moneyBody.copy(fontSize = 20.sp, fontWeight = FontWeight.SemiBold),
                    color = if (expense) colors.expense else colors.income,
                    grouping = grouping,
                    showCurrency = false,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = "$categoryCount 类 · ${snapshot.summary.billCount} 笔",
                    fontSize = 10.sp, lineHeight = 12.5.sp,
                    letterSpacing = 0.06.em,
                    color = colors.ink3,
                )
                Spacer(Modifier.height(5.dp))
                Box(Modifier.fillMaxWidth().height(1.dp).background(colors.line))
                if (snapshot.summary.excludedCount > 0) {
                    Spacer(Modifier.height(6.dp))
                    LegendLine(
                        badge = BillBadge("eyeoff", "已剔除", MarkerTone.SLATE),
                        text = "${snapshot.summary.excludedCount} 笔 ¥${formatAmount(snapshot.summary.excludedMinor, grouping)}",
                    )
                }
                if (expense && snapshot.summary.pendingReimburseCount > 0) {
                    Spacer(Modifier.height(6.dp))
                    LegendLine(
                        badge = BillBadge("receipt", "未报销", MarkerTone.OUTLINE),
                        text = "${snapshot.summary.pendingReimburseCount} 笔 ¥${formatAmount(snapshot.summary.pendingReimburseMinor, grouping)}",
                        trailing = true,
                        onClick = onReimburse,
                    )
                }
            }
        }
    }

    if (expense) {
        Spacer(Modifier.height(12.dp))
        MarkerSwitch(
            label = "支出视图剔除未报销",
            iconKey = "receipt",
            checked = state.excludePendingReimburse,
            onCheckedChange = onToggleExcludePending,
            selectedTone = MarkerTone.SLATE,
            modifier = Modifier.fillMaxWidth(),
        )
    }

    Spacer(Modifier.height(12.dp))
    if (ranking.isEmpty()) {
        TallyCard(corner = 15) {
            Text("这个周期没有可统计的${if (expense) "支出" else "收入"}分类。", style = TallyType.helper, color = colors.ink3)
        }
    } else {
        TallyCard(corner = 16, contentPadding = 0.dp) {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp)) {
                ranking.forEachIndexed { index, item ->
                    if (index > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(colors.ink.copy(alpha = 0.05f)))
                    // 整行可点：有二级先弹二级统计，没有二级直接弹记录。
                    RankRow(
                        item = item,
                        color = palette[index % palette.size],
                        tile = statsTileColors(index),
                        highlighted = picked == index,
                        onOpen = { onOpenCategory(item) },
                    )
                }
            }
        }
    }
}

@Composable
private fun LegendLine(
    badge: BillBadge,
    text: String,
    trailing: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val colors = tallyColors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick) else Modifier)
            .padding(vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Marker(badge)
        Text(text, fontSize = 10.5.sp, lineHeight = 13.1.sp, color = colors.ink2, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (trailing) TallyRowChevron()
    }
}

@Composable
private fun RankRow(
    item: CategoryTotal,
    color: Color,
    tile: Pair<Color, Color>,
    highlighted: Boolean = false,
    /** 点整行弹出这个分类的下钻弹层；没有二级的一级直接出记录。 */
    onOpen: (() -> Unit)? = null,
) {
    val colors = tallyColors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(9.dp))
            .background(
                when {
                    highlighted -> color.copy(alpha = 0.12f)
                    else -> Color.Transparent
                },
            )
            .then(if (onOpen != null) Modifier.clickable(onClick = onOpen) else Modifier)
            .padding(top = 6.dp, bottom = 6.dp, end = if (onOpen != null) 2.dp else 0.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(26.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(tile.first),
            contentAlignment = Alignment.Center,
        ) {
            CategoryGlyph(
                iconKey = item.iconKey ?: "more",
                label = item.name,
                size = 13.dp,
                tint = tile.second,
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = item.name,
                    fontSize = 12.5.sp,
                    lineHeight = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.ink,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.width(7.dp))
                ChangeLabel(item.changePercent)
                Spacer(Modifier.width(7.dp))
                MoneyText(
                    amountMinor = item.amountMinor,
                    sign = MoneySign.NONE,
                    style = TallyType.moneyList.copy(fontSize = 13.5.sp),
                    color = colors.ink2,
                    modifier = Modifier.width(64.dp),
                    grouping = true,
                    showCurrency = false,
                    textAlign = TextAlign.End,
                )
            }
            Spacer(Modifier.height(3.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(colors.ink.copy(alpha = if (colors.isDark) 0.1f else 0.07f)),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(item.share.coerceIn(0f, 1f))
                        .height(4.dp)
                        .background(color),
                )
            }
            Spacer(Modifier.height(2.dp))
            Text(
                text = "%.1f%%".format(item.share * 100f),
                style = TallyType.moneyList.copy(fontSize = 9.5.sp, lineHeight = 12.sp, fontWeight = FontWeight.Normal),
                color = colors.ink3,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // 每一行都带一个「进入」箭头：明细不该被藏在长按或右滑里。
        if (onOpen != null) {
            InlineIcon(
                iconKey = "chevron",
                contentDescription = "查看 ${item.name} 的分类统计",
                size = 13.dp,
                tint = colors.ink3,
            )
        }
    }
}

@Composable
private fun ChangeLabel(changePercent: Float?) {
    val colors = tallyColors
    val change = changePercent
    val flat = change == null || abs(change) <= 0.05f
    Text(
        text = when {
            change == null -> "—"
            abs(change) <= 0.05f -> "持平"
            change > 0 -> "+${"%.1f".format(change)}%"
            else -> "−${"%.1f".format(abs(change))}%"
        },
        style = TallyType.moneyList.copy(fontSize = 10.sp, lineHeight = 12.5.sp, fontWeight = if (flat) FontWeight.SemiBold else FontWeight.Bold),
        color = when {
            flat -> colors.ink3
            change > 0 -> colors.expense
            else -> colors.brand
        },
        textAlign = TextAlign.End,
        maxLines = 1,
        modifier = Modifier.width(58.dp),
    )
}

@Composable
internal fun Donut(
    ranking: List<CategoryTotal>,
    palette: List<Color>,
    totalLabel: String,
    totalMinor: Long,
    grouping: Boolean,
    selected: Int?,
    onSelectSlice: (Int) -> Unit,
    diameter: Dp = 124.dp,
) {
    val colors = tallyColors
    val total = max(1L, ranking.sumOf { it.amountMinor })
    val picked = selected?.let { ranking.getOrNull(it) }
    Box(modifier = Modifier.size(diameter), contentAlignment = Alignment.Center) {
        Canvas(
            modifier = Modifier
                .size(diameter)
                .pointerInput(ranking, total) {
                    detectTapGestures { offset ->
                        val ring = 20.dp.toPx()
                        val centre = size.width / 2f
                        val arcRadius = (size.width - ring) / 2f
                        val dx = offset.x - centre
                        val dy = offset.y - centre
                        val distance = sqrt(dx * dx + dy * dy)
                        // 只有落在圆环带上才算点中扇区：圆心和四角都不参与。
                        if (distance < arcRadius - ring / 2f || distance > arcRadius + ring / 2f) return@detectTapGestures
                        // 绘制从 12 点方向顺时针铺开，把触点换算回同一套角度。
                        var angle = (atan2(dy, dx) * 180.0 / PI).toFloat() + 90f
                        if (angle < 0f) angle += 360f
                        var cumulative = 0f
                        var hit = -1
                        for (index in ranking.indices) {
                            val sweep = ranking[index].amountMinor.toFloat() / total.toFloat() * 360f
                            if (angle < cumulative + sweep) {
                                hit = index
                                break
                            }
                            cumulative += sweep
                        }
                        if (hit >= 0) onSelectSlice(hit)
                    }
                },
        ) {
            val ring = 20.dp.toPx()
            val inset = ring / 2f
            val arcSize = Size(size.width - ring, size.height - ring)
            var start = -90f
            ranking.forEachIndexed { index, item ->
                val sweep = item.amountMinor.toFloat() / total.toFloat() * 360f
                val isSelected = index == selected
                drawArc(
                    // 选中那块加粗、其余压暗：饼图终于能回答"这一条是哪块"。
                    color = when {
                        selected == null -> palette[index % palette.size]
                        isSelected -> palette[index % palette.size]
                        else -> palette[index % palette.size].copy(alpha = 0.28f)
                    },
                    startAngle = start,
                    sweepAngle = sweep,
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = arcSize,
                    style = Stroke(width = if (isSelected) ring * 1.28f else ring, cap = StrokeCap.Butt),
                )
                start += sweep
            }
        }
        Box(
            modifier = Modifier
                .size(diameter - 40.dp)
                .clip(CircleShape)
                .background(colors.paper),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(horizontal = 5.dp),
            ) {
                Text(
                    text = formatAmount(picked?.amountMinor ?: (totalMinor - totalMinor % 100L), grouping),
                    style = TallyType.moneyBody.copy(fontSize = if (picked == null) 14.sp else 13.sp, fontWeight = FontWeight.SemiBold),
                    color = colors.ink,
                    maxLines = 1,
                )
                Text(
                    text = picked?.let { "${it.name} · ${"%.1f".format(it.share * 100)}%" } ?: totalLabel,
                    fontSize = 9.5.sp, lineHeight = 11.9.sp,
                    color = colors.ink3,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun DailyTableSection(snapshot: StatisticsSnapshot, grouping: Boolean, onOpenBucket: (Long) -> Unit) {
    val colors = tallyColors
    val bucket = effectiveBucket(snapshot)
    val unit = bucketUnit(snapshot)
    val shown = snapshot.dailyTrend.filterNot { it.isFuture }.reversed()
    val highest = snapshot.highestExpenseDayStart?.let { start -> snapshot.dailyTrend.firstOrNull { it.dayStart == start } }
    val zeroFirst = snapshot.zeroExpenseFirstDayStart?.let { start -> snapshot.dailyTrend.firstOrNull { it.dayStart == start } }

    TallyCard(corner = 15, contentPadding = 0.dp) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.weight(1f).padding(horizontal = 11.dp, vertical = 8.dp)) {
                OvCaption("支出最高的一$unit")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = formatAmount(highest?.expenseMinor ?: 0L, grouping),
                        style = TallyType.moneyBody.copy(fontSize = 17.sp, lineHeight = 21.3.sp, fontWeight = FontWeight.SemiBold),
                        color = colors.expense,
                        maxLines = 1,
                    )
                    Spacer(Modifier.width(5.dp))
                    Text(
                        text = highest?.let { bucketLabel(it.dayStart, snapshot) } ?: "—",
                        fontSize = 10.sp, lineHeight = 12.5.sp,
                        color = colors.ink3,
                    )
                }
            }
            OvDivider(vertical = true)
            Column(modifier = Modifier.weight(1f).padding(horizontal = 11.dp, vertical = 8.dp)) {
                OvCaption("无支出${unit}数")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = snapshot.zeroExpenseDayCount.toString(),
                        style = TallyType.moneyBody.copy(fontSize = 17.sp, lineHeight = 21.3.sp, fontWeight = FontWeight.SemiBold),
                        color = colors.ink3,
                    )
                    Spacer(Modifier.width(5.dp))
                    Text(
                        text = zeroFirst?.let { bucketLabel(it.dayStart, snapshot) } ?: "—",
                        fontSize = 10.sp, lineHeight = 12.5.sp,
                        color = colors.ink3,
                    )
                }
            }
        }
    }

    Spacer(Modifier.height(12.dp))
    TallyCard(corner = 16, contentPadding = 0.dp) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp)) {
            Row(modifier = Modifier.fillMaxWidth()) {
                TableHead(
                    when (bucket) {
                        StatsBucket.MONTH -> "月份"
                        StatsBucket.WEEK -> "期间"
                        else -> "日期"
                    },
                    0.31f,
                    TextAlign.Start,
                )
                TableHead("支出", 0.25f, TextAlign.End)
                TableHead("收入", 0.27f, TextAlign.End)
                TableHead("笔数", 0.17f, TextAlign.End)
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(colors.line))
            shown.forEach { row -> TableBucketRow(row, snapshot, grouping, onOpenBucket) }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(7.dp))
                    .background(if (colors.isDark) colors.brand.copy(alpha = 0.1f) else Color(0xFF3E9B77).copy(alpha = 0.07f)),
            ) {
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "至 ${shown.firstOrNull()?.let { bucketLabel(it.dayStart, snapshot) } ?: "—"} 合计",
                        style = tableTextStyle(FontWeight.Bold),
                        color = colors.ink,
                        modifier = Modifier.weight(0.31f).padding(horizontal = 6.dp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    TableMoney(formatAmount(snapshot.summary.expenseMinor, grouping), colors.expense, 0.25f, bold = true)
                    TableMoney(formatAmount(snapshot.summary.incomeMinor, grouping), colors.brand, 0.27f, bold = true)
                    TableMoney(snapshot.summary.billCount.toString(), colors.ink2, 0.17f, bold = true)
                }
            }
        }
    }
}

@Composable
private fun OvCaption(text: String) {
    Text(
        text = text,
        fontSize = 10.sp, lineHeight = 12.5.sp,
        letterSpacing = 0.06.em,
        color = tallyColors.ink3,
        maxLines = 1,
        modifier = Modifier.padding(bottom = 2.dp),
    )
}

@Composable
private fun RowScope.TableHead(text: String, weight: Float, align: TextAlign) {
    Text(
        text = text,
        style = tableTextStyle(FontWeight.Bold).copy(fontSize = 10.sp, lineHeight = 12.5.sp, letterSpacing = 0.08.em),
        color = tallyColors.ink3,
        textAlign = align,
        modifier = Modifier.weight(weight).padding(horizontal = 6.dp, vertical = 4.dp),
        maxLines = 1,
    )
}

/**
 * 报表格子的字面：日期列混着中文与数字，金额列只有数字，两套字面各站各的 line box，
 * 看上去就是「支出那栏没跟日期在同一横线上」。字号、行高、字体留白全部锁成同一份，
 * 行再按垂直居中摆，四栏才真的齐。字族只差一处：日期与表头是文字用无衬线，金额才是衬线。
 */
private fun tableTextStyle(weight: FontWeight) = TextStyle(
    fontWeight = weight,
    fontSize = 10.5.sp,
    lineHeight = 14.sp,
    platformStyle = PlatformTextStyle(includeFontPadding = false),
    fontFeatureSettings = "tnum",
)

private fun tableMoneyStyle(weight: FontWeight) = tableTextStyle(weight).copy(fontFamily = FontFamily.Serif)

@Composable
private fun TableBucketRow(bucket: DailyTotal, snapshot: StatisticsSnapshot, grouping: Boolean, onOpenBucket: (Long) -> Unit) {
    val colors = tallyColors
    val muted = bucket.billCount == 0
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .clickable { onOpenBucket(bucket.dayStart) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = bucketLabel(bucket.dayStart, snapshot) +
                if (effectiveBucket(snapshot) == StatsBucket.DAY) " " + weekdayOf(bucket.dayStart) else "",
            style = tableTextStyle(FontWeight.Normal),
            color = if (muted) colors.ink3 else colors.ink,
            modifier = Modifier.weight(0.31f).padding(horizontal = 6.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        TableMoney(
            text = if (bucket.expenseMinor > 0L) formatAmount(bucket.expenseMinor, grouping) else "0.00",
            color = if (muted) colors.ink3 else colors.expense,
            weight = 0.25f,
        )
        TableMoney(
            text = if (bucket.incomeMinor > 0L) formatAmount(bucket.incomeMinor, grouping) else "—",
            color = if (bucket.incomeMinor > 0L && !muted) colors.brand else colors.ink3,
            weight = 0.27f,
        )
        TableMoney(
            text = bucket.billCount.toString(),
            color = if (muted) colors.ink3 else colors.ink2,
            weight = 0.17f,
        )
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(colors.ink.copy(alpha = 0.05f)))
}

@Composable
private fun RowScope.TableMoney(text: String, color: Color, weight: Float, bold: Boolean = false) {
    Text(
        text = text,
        style = tableMoneyStyle(if (bold) FontWeight.Bold else FontWeight.Normal),
        color = color,
        textAlign = TextAlign.End,
        modifier = Modifier.weight(weight).padding(horizontal = 6.dp),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/** .aifab：金色胶囊，宽度跟着字走不留死空间；向下滚动时收成一个小圆点，不压住报表的金额列。 */
@Composable
private fun BoxScope.AnalysisPill(label: String, busy: Boolean, collapsed: Boolean, onClick: () -> Unit) {
    val colors = tallyColors
    val shape = RoundedCornerShape(20.dp)
    Row(
        modifier = Modifier
            .align(Alignment.BottomEnd)
            .padding(end = 13.dp, bottom = 14.dp)
            .height(34.dp)
            .clip(shape)
            .background(
                if (busy) SolidColor(colors.card) else Brush.linearGradient(listOf(colors.goldHi, colors.gold)),
                shape,
            )
            .border(1.dp, if (busy) colors.line else colors.gold.copy(alpha = 0.5f), shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        InlineIcon(iconKey = "spark", contentDescription = label, size = 14.dp, tint = if (busy) colors.gold else colors.ink)
        if (!collapsed) {
            Text(
                text = if (busy) "正在算…" else label,
                fontSize = 12.5.sp, lineHeight = 15.6.sp,
                fontWeight = FontWeight.Bold,
                color = if (busy) colors.ink3 else colors.ink,
                maxLines = 1,
            )
        }
    }
}

/**
 * S-02 / S-03：生成中给骨架屏并写明在算哪、读了多少笔；
 * 完成后每条结论都能回指统计页的某一格。关掉即取消这次计算。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StatsAnalysisSheet(
    state: AssistantAnalysisUiState,
    periodLabel: String,
    billCount: Int,
    categoryCount: Int,
    onGenerate: () -> Unit,
    onAsk: (String) -> Unit,
    onPoster: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = tallyColors
    val cloud = state.availability?.usableQuietly == true
    val report = state.report
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = colors.paper,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // 长报告不设上限会一路撑到屏底，把「重新生成」挤到看不见也点不到；
                // 限高 + 内部滚动，弹层只占半屏左右。
                .heightIn(max = (LocalConfiguration.current.screenHeightDp * 0.6f).dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 15.dp)
                .padding(bottom = 20.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Brush.linearGradient(listOf(colors.goldHi, colors.gold))),
                    contentAlignment = Alignment.Center,
                ) {
                    InlineIcon(iconKey = "jasmine", contentDescription = null, size = 13.dp, tint = colors.onGlow)
                }
                Text(
                    text = if (state.working) "正在分析 $periodLabel" else "${periodLabel}小结",
                    fontSize = 15.5.sp,
                    lineHeight = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = TallyType.screenTitle.fontFamily,
                    color = colors.ink,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                )
                if (cloud) TallyTag(text = "云端 · 已脱敏", tone = TagTone.OUTLINE)
                TallyIconAction(iconKey = "close", contentDescription = "关闭", onClick = onDismiss)
            }
            Spacer(Modifier.height(8.dp))

            when {
                state.working -> {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(0.dp, 12.dp, 36.dp).forEach { tail ->
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(9.dp)
                                    .padding(end = tail)
                                    .clip(RoundedCornerShape(5.dp))
                                    .background(colors.glow),
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "已读入 $billCount 笔 · $categoryCount 个分类",
                        fontSize = 10.5.sp, lineHeight = 13.1.sp,
                        color = colors.ink3,
                    )
                }

                state.error != null -> {
                    Text(state.error.orEmpty(), fontSize = 12.sp, lineHeight = 19.sp, color = colors.expense)
                    Spacer(Modifier.height(10.dp))
                    TallyButton(label = "再算一次", onClick = onGenerate, variant = ButtonVariant.GOLD, height = 32)
                }

                state.notice != null -> Text(
                    text = state.notice.orEmpty(),
                    fontSize = 12.sp,
                    lineHeight = 19.sp,
                    color = colors.ink2,
                )

                report != null -> {
                    // 模型被要求只输出一个 JSON 对象，但不听话时会包上 markdown 围栏、或在前面多写一段
                    // 解释。解析出来就按字段排；两层都解析不出来就退回逐行显示——宁可多显示，也不把话吞掉。
                    val fields = AnalysisSummaryParser.parse(report.content)
                    if (fields.isNotEmpty()) {
                        analysisLabels.forEach { (field, label) -> fields[field]?.let { AnalysisLine(label, it) } }
                    } else {
                        report.content.lineSequence()
                            .map(String::trim)
                            .filter { it.isNotEmpty() }
                            .forEach { line ->
                                val (label, body) = splitLabel(line)
                                AnalysisLine(label, body)
                            }
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        TallyButton(
                            label = "重新生成",
                            onClick = onGenerate,
                            variant = ButtonVariant.GHOST,
                            height = 32,
                            leadingIcon = "undo",
                            modifier = Modifier.weight(1f),
                        )
                        TallyButton(
                            label = "问小莉",
                            onClick = { onAsk("${periodLabel}小结：${report.content}") },
                            variant = ButtonVariant.BRAND,
                            height = 32,
                            leadingIcon = "jasmine",
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Spacer(Modifier.height(7.dp))
                    Text(
                        text = "生成于 " + formatStamp(report.createdAt),
                        fontSize = 10.sp, lineHeight = 12.5.sp,
                        color = colors.ink3,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center,
                    )
                    // 读完这段小结想转出去的人，就地给一张图，不用回顶栏再找（SH-01 第二处入口）。
                    Spacer(Modifier.height(9.dp))
                    Text(
                        text = "生成海报 ›",
                        fontSize = 12.sp, lineHeight = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.brand,
                        modifier = Modifier.fillMaxWidth().clip(TallyPillShape).clickable(onClick = onPoster)
                            .padding(vertical = 7.dp),
                        textAlign = TextAlign.Center,
                    )
                }

                else -> Text("还没有生成过。", fontSize = 12.sp, lineHeight = 15.sp, color = colors.ink3)
            }
        }
    }
}

/** 一条结论：有标签就当标签用（44dp 列），没有标签就整句进正文。 */
@Composable
private fun AnalysisLine(label: String?, body: String) {
    val colors = tallyColors
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        label?.let {
            Text(
                text = it,
                // 行高与正文取同一个值：两个行盒等高，标签的字才会和正文第一行落在同一水平线上。
                fontSize = 10.5.sp, lineHeight = 19.sp,
                letterSpacing = 0.06.em,
                fontWeight = FontWeight.Bold,
                color = colors.onGlow,
                modifier = Modifier.width(44.dp),
            )
        }
        Text(body, fontSize = 11.5.sp, lineHeight = 19.sp, color = colors.ink2, modifier = Modifier.weight(1f))
    }
}

/**
 * 兜底路径：从一行里认出标签。新格式的五个标签，加上上一版存下来的四个旧标签都认，
 * 认不出就整句当正文——旧缓存里的东西也还能看，不至于变成一堆没标签的灰字。
 */
private fun splitLabel(line: String): Pair<String?, String> {
    val labels = analysisLabels.values + listOf("变化", "节奏", "待办", "口径")
    // 旧缓存是 markdown：`## 变化：…`、`**节奏**：…`、`- 待办: …` 都见过。
    // 先剥掉行首的 markdown 记号再认标签；认不出就整句当正文。
    val stripped = line.trim().trimStart('#', '>', '*', '-', '·', '`', ' ', '\t')
    val label = labels.firstOrNull { stripped.startsWith(it) } ?: return null to line
    return label to stripped.removePrefix(label).removePrefix("：").removePrefix(":").trim()
}

private fun formatStamp(epochMillis: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date(epochMillis))
