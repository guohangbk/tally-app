package io.github.guohangbk.tally.ui.tag

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import io.github.guohangbk.tally.data.repository.TagDetail
import io.github.guohangbk.tally.di.AppGraph
import io.github.guohangbk.tally.di.rememberAppGraph
import io.github.guohangbk.tally.domain.Period
import io.github.guohangbk.tally.domain.PeriodRange
import io.github.guohangbk.tally.domain.model.CategoryTotal
import io.github.guohangbk.tally.ui.common.TallyDatePickerDialog
import io.github.guohangbk.tally.ui.common.millisToLocalDate
import io.github.guohangbk.tally.ui.common.startOfDay
import io.github.guohangbk.tally.ui.common.tallyViewModel
import io.github.guohangbk.tally.ui.design.ButtonVariant
import io.github.guohangbk.tally.ui.design.InlineIcon
import io.github.guohangbk.tally.ui.design.MoneySign
import io.github.guohangbk.tally.ui.design.MoneyText
import io.github.guohangbk.tally.ui.design.TallyBottomSheet
import io.github.guohangbk.tally.ui.design.TallyButton
import io.github.guohangbk.tally.ui.design.TallyCard
import io.github.guohangbk.tally.ui.design.TallyEmptyState
import io.github.guohangbk.tally.ui.design.TallySegmented
import io.github.guohangbk.tally.ui.design.TallyToastEffects
import io.github.guohangbk.tally.ui.design.TallyType
import io.github.guohangbk.tally.ui.design.tallyColors
import io.github.guohangbk.tally.ui.stats.Donut
import io.github.guohangbk.tally.ui.stats.statsPalette
import io.github.guohangbk.tally.ui.shell.TallyTopBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToInt

/** 快捷范围；「全部」用 0 起点，标签页不另存一份账单起点。 */
enum class TagRangePreset(val label: String) {
    THIS_MONTH("本月"),
    LAST_MONTH("上月"),
    THIS_YEAR("今年"),
    LAST_YEAR("去年"),
    ALL("全部"),
}

data class TagDetailState(
    val name: String = "",
    val loading: Boolean = true,
    /** 走势固定看一整年：选月份只收窄合计 / 构成 / 账本三段。 */
    val year: Int = LocalDate.now().year,
    val month: Int? = null,
    val customStart: Long? = null,
    val customEnd: Long? = null,
    val customLabel: String? = null,
    val expenseTab: Boolean = true,
    val detail: TagDetail? = null,
    val startDay:Int = Period.DEFAULT_START_DAY,
    val rangeSheet: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
)

class TagDetailViewModel(
    private val graph: AppGraph,
    private val tagId: String,
) : ViewModel() {
    private val mutable = MutableStateFlow(TagDetailState())
    val state: StateFlow<TagDetailState> = mutable
    private var loadToken = 0

    init {
        viewModelScope.launch {
            val entity = graph.tagRepository.find(tagId)
            mutable.value = if (entity == null) {
                mutable.value.copy(loading = false, error = "标签不存在或已删除")
            } else {
                mutable.value.copy(name = entity.name)
            }
            reload()
        }
        viewModelScope.launch {
            graph.settingRepository.monthStartDay.collect { day ->
                if (mutable.value.startDay != day) {
                    mutable.value = mutable.value.copy(startDay = day)
                    reload()
                }
            }
        }
        // 记账、批量打标、删标签都会改这里要算的数：关联表一动就重算一次。
        viewModelScope.launch {
            graph.tagRepository.observeLinkTick().collect { reload() }
        }
    }

    fun openRangeSheet() {
        mutable.value = mutable.value.copy(rangeSheet = true)
    }

    fun closeRangeSheet() {
        mutable.value = mutable.value.copy(rangeSheet = false)
    }

    /** 顶部 chip：月 = 只收窄下面三段，走势跟着这个月所在的年。 */
    fun selectMonthOf(year: Int, month: Int) =
        applyScope(year = year, month = month, customStart = null, customEnd = null, customLabel = null)

    /** 最左侧那枚「今年」：看全年，和月份 chip 互斥。 */
    fun selectThisYear() {
        val today = LocalDate.now()
        applyScope(year = today.year, month = null, customStart = null, customEnd = null, customLabel = null)
    }

    fun applyPreset(preset: TagRangePreset) {
        val today = LocalDate.now()
        when (preset) {
            TagRangePreset.THIS_MONTH -> applyScope(today.year, today.monthValue, null, null, null)
            TagRangePreset.LAST_MONTH -> {
                val previous = today.minusMonths(1)
                applyScope(previous.year, previous.monthValue, null, null, null)
            }
            TagRangePreset.THIS_YEAR -> applyScope(today.year, null, null, null, null)
            TagRangePreset.LAST_YEAR -> applyScope(today.year - 1, null, null, null, null)
            TagRangePreset.ALL -> {
                val end = today.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
                applyScope(today.year, null, 0L, end, "全部")
            }
        }
    }

    /** 自定义起止：两端都含当天，右端转成开区间。 */
    fun applyCustom(startMillis: Long, endInclusiveMillis: Long) {
        val start = startOfDay(minOf(startMillis, endInclusiveMillis))
        val endDay = millisToLocalDate(maxOf(startMillis, endInclusiveMillis)).plusDays(1)
        val end = endDay.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val label = Period.rangeLabel(start, end)
        applyScope(endDay.year, null, start, end, label)
    }

    fun showIncome(income: Boolean) {
        mutable.value = mutable.value.copy(expenseTab = !income)
    }

    fun dismissNotice() {
        mutable.value = mutable.value.copy(notice = null)
    }

    fun dismissError() {
        mutable.value = mutable.value.copy(error = null)
    }

    private fun applyScope(
        year: Int,
        month: Int?,
        customStart: Long?,
        customEnd: Long?,
        customLabel: String?,
    ) {
        val current = mutable.value
        if (current.customLabel == customLabel) {
            // 「全部」的右端跟着今天走，但用户没换范围时不重算。
            if (customLabel != null) {
                mutable.value = current.copy(rangeSheet = false)
                return
            }
        }
        mutable.value = current.copy(
            year = year,
            month = month,
            customStart = customStart,
            customEnd = customEnd,
            customLabel = customLabel,
            rangeSheet = false,
            loading = true,
        )
        reload()
    }

    private fun reload() {
        val snapshot = mutable.value
        if (snapshot.name.isEmpty()) return
        val token = ++loadToken
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val totals = totalsRange(snapshot)
                val year = Period.yearRange(yearAnchor(snapshot.year))
                val detail = graph.tagRepository.detail(
                    tagId = tagId,
                    startAt = totals.startAt,
                    endAt = totals.endAtExclusive,
                    yearStart = year.startAt,
                    yearEnd = year.endAtExclusive,
                )
                if (token == loadToken) mutable.value = mutable.value.copy(detail = detail, loading = false)
            } catch (error: Exception) {
                if (token == loadToken) {
                    mutable.value = mutable.value.copy(loading = false, error = error.message ?: "标签详情没有算出来")
                }
            }
        }
    }

    private fun totalsRange(state: TagDetailState): PeriodRange {
        val customStart = state.customStart
        val customEnd = state.customEnd
        if (customStart != null && customEnd != null) return PeriodRange(customStart, customEnd)
        val month = state.month
        if (month != null) {
            return Period.monthRange(Period.monthStart(state.year, month, state.startDay), state.startDay)
        }
        return Period.yearRange(yearAnchor(state.year))
    }

    private fun yearAnchor(year: Int): Long =
        LocalDate.of(year, 1, 1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
}

/**
 * 标签详情（TG-10 / TG-11）：一页看完合计、整年走势、分类构成与涉及账本。
 * 顶部 chip 最左是「今年」（整年），后面铺最近 12 个月：点一个月只收窄合计以下三段，
 * 走势看这个月所在的年。
 */
@Composable
fun TagDetailScreen(
    tagId: String,
    onBack: () -> Unit,
    onOpenBills: (ledgerId: String?, tagIds: List<String>) -> Unit,
) {
    val graph = rememberAppGraph()
    val viewModel = tallyViewModel { TagDetailViewModel(graph, tagId) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val colors = tallyColors
    val detail = state.detail

    Column(modifier = Modifier.fillMaxSize().background(colors.paper)) {
        TallyTopBar(
            title = state.name.ifEmpty { "标签" },
            onBack = onBack,
            trailing = {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(9.dp))
                        .clickable(onClick = viewModel::openRangeSheet)
                        .padding(7.dp),
                ) {
                    InlineIcon(iconKey = "filter", contentDescription = "选择日期范围", size = 16.dp, tint = colors.ink2)
                }
            },
        )
        TallyToastEffects(
            notice = state.notice,
            error = state.error,
            errorTitle = "这段标签没有算出来",
            onNoticeShown = viewModel::dismissNotice,
            onErrorShown = viewModel::dismissError,
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 15.dp),
        ) {
            Spacer(Modifier.height(6.dp))
            ScopeChips(
                state = state,
                onYear = viewModel::selectThisYear,
                onMonth = viewModel::selectMonthOf,
            )
            Spacer(Modifier.height(10.dp))

            if (detail == null) {
                if (!state.loading) {
                    TallyEmptyState(
                        title = "没有读到这个标签",
                        description = "它可能已经被删除，返回列表看看。",
                        iconKey = "tag",
                    )
                }
            } else if (detail.totals.billCount == 0) {
                TallyEmptyState(
                    title = "这段时间没有账单",
                    description = "换个范围，或者在记账页给账单挂上这枚标签。",
                    iconKey = "tag",
                )
            } else {
                TotalsCard(state = state, detail = detail, onSelect = viewModel::showIncome)
                Spacer(Modifier.height(16.dp))
                SectionHeader(title = "月度走势", trailing = "${state.year} 年 · 支出")
                Spacer(Modifier.height(7.dp))
                MonthlyCard(year = state.year, monthly = detail.monthlyExpense)
                Spacer(Modifier.height(16.dp))
                SectionHeader(title = "分类构成", trailing = "${detail.categories.size} 类")
                Spacer(Modifier.height(7.dp))
                CompositionCard(categories = detail.categories)
                Spacer(Modifier.height(16.dp))
                SectionHeader(title = "涉及账本", trailing = "${detail.ledgers.size} 个")
                Spacer(Modifier.height(7.dp))
                LedgerCard(
                    ledgers = detail.ledgers,
                    totalBills = detail.totals.billCount,
                    onOpenAll = { onOpenBills(null, listOf(tagId)) },
                )
            }
            Spacer(Modifier.height(26.dp))
        }
    }

    if (state.rangeSheet && detail != null) {
        val month = state.month
        val customStart = state.customStart
        val customEnd = state.customEnd
        val year = state.year
        val startDay = state.startDay
        val range = remember(month, year, customStart, customEnd, startDay) {
            if (month != null) {
                Period.monthRange(Period.monthStart(year, month, startDay), startDay)
            } else if (customStart != null && customEnd != null) {
                PeriodRange(customStart, customEnd)
            } else {
                Period.yearRange(LocalDate.of(year, 1, 1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli())
            }
        }
        TagRangeSheet(
            rangeStart = range.startAt,
            rangeEndInclusive = millisToLocalDate(range.endAtExclusive.minus(1)).let { date ->
                date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
            },
            onPreset = viewModel::applyPreset,
            onCustom = viewModel::applyCustom,
            onDismiss = viewModel::closeRangeSheet,
        )
    }
}

@Composable
private fun ScopeChips(
    state: TagDetailState,
    onYear: () -> Unit,
    onMonth: (year: Int, month: Int) -> Unit,
) {
    val today = remember { LocalDate.now() }
    // 滚动窗口：本月往前数 12 个自然月；跨年的那几枚带上年份，省得 12 月看着像今年。
    val months = remember(today) { (0 until 12).map { today.minusMonths(it.toLong()) } }
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // 整年口径放在最左：不选月份时合计三段看的就是这一年。
        ScopeChip(
            label = "今年",
            selected = state.customStart == null && state.month == null && state.year == today.year,
            onClick = onYear,
        )
        state.customLabel?.let { label ->
            ScopeChip(label = label, selected = true, onClick = {})
        }
        months.forEach { date ->
            ScopeChip(
                label = if (date.year == today.year) "${date.monthValue}月" else "${date.year}/${date.monthValue}",
                selected = state.customStart == null && state.year == date.year && state.month == date.monthValue,
                onClick = { onMonth(date.year, date.monthValue) },
            )
        }
    }
}

@Composable
private fun ScopeChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = tallyColors
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(9.dp))
            .background(if (selected) colors.brandSurface else colors.card)
            .border(1.dp, if (selected) colors.brand.copy(alpha = 0.35f) else colors.line, RoundedCornerShape(9.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 6.dp),
    ) {
        Text(
            text = label,
            fontSize = 11.5.sp,
            lineHeight = 14.4.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) colors.brand else colors.ink2,
            maxLines = 1,
        )
    }
}

@Composable
private fun SectionHeader(title: String, trailing: String) {
    val colors = tallyColors
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = TallyType.listTitle, color = colors.ink, modifier = Modifier.weight(1f))
        Text(trailing, style = TallyType.helper, color = colors.ink3, maxLines = 1)
    }
}

@Composable
private fun TotalsCard(
    state: TagDetailState,
    detail: TagDetail,
    onSelect: (Boolean) -> Unit,
) {
    val colors = tallyColors
    val expense = state.expenseTab
    val last = detail.totals.lastOccurredAt?.let { millis ->
        val date = millisToLocalDate(millis)
        "%02d-%02d".format(date.monthValue, date.dayOfMonth)
    }
    TallyCard(corner = 15, contentPadding = 0.dp) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 13.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (expense) "支出合计" else "收入合计",
                        style = TallyType.helper,
                        color = colors.ink3,
                    )
                    Spacer(Modifier.height(4.dp))
                    MoneyText(
                        amountMinor = if (expense) detail.totals.expenseMinor else detail.totals.incomeMinor,
                        sign = MoneySign.NONE,
                        style = TallyType.moneyDisplay.copy(fontSize = 26.sp, lineHeight = 30.sp),
                        color = if (expense) colors.expense else colors.income,
                    )
                }
                TallySegmented(
                    options = listOf(true to "支出", false to "收入"),
                    selected = expense,
                    onSelect = onSelect,
                    modifier = Modifier.width(96.dp),
                )
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "${detail.totals.billCount} 笔 · ${detail.totals.ledgerCount} 个账本",
                    style = TallyType.helper,
                    color = colors.ink2,
                    modifier = Modifier.weight(1f),
                )
                if (last != null) {
                    Text("最近 $last", style = TallyType.helper, color = colors.ink3)
                }
            }
        }
    }
}

@Composable
private fun MonthlyCard(year: Int, monthly: List<Long>) {
    val colors = tallyColors
    val today = LocalDate.now()
    val total = monthly.sum()
    val maxAmount = monthly.maxOrNull() ?: 0L
    val peak = if (maxAmount > 0) monthly.indexOf(maxAmount) else -1
    val divisor = if (year == today.year) today.monthValue else 12
    val average = total / maxOf(1, divisor)
    val peakShare = if (total > 0) maxAmount.toFloat() / total else 0f

    TallyCard(corner = 15, contentPadding = 0.dp) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 13.dp, vertical = 11.dp)) {
            if (total <= 0L) {
                Text("这一年还没有支出", style = TallyType.helper, color = colors.ink3)
                return@Column
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("月均 ", style = TallyType.helper, color = colors.ink3)
                MoneyText(
                    amountMinor = average,
                    sign = MoneySign.NONE,
                    style = TallyType.moneyList.copy(fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.Normal),
                    color = colors.ink3,
                    grouping = true,
                    showCurrency = true,
                )
                Spacer(Modifier.weight(1f))
                Text("按月", style = TallyType.helper, color = colors.ink3)
            }
            Spacer(Modifier.height(7.dp))
            Row(
                modifier = Modifier.fillMaxWidth().height(58.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                monthly.forEachIndexed { index, amount ->
                    val fraction = (amount.toFloat() / maxAmount.toFloat()).coerceIn(0f, 1f)
                    Box(
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        contentAlignment = Alignment.BottomCenter,
                    ) {
                        if (amount > 0L) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(58.dp * fraction)
                                    .clip(RoundedCornerShape(2.dp, 2.dp, 0.dp, 0.dp))
                                    .background(if (index == peak) colors.gold else colors.brand.copy(alpha = 0.32f)),
                            )
                        } else {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(2.dp)
                                    .background(colors.ink.copy(alpha = 0.1f)),
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(3.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                monthly.forEachIndexed { index, _ ->
                    Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        Text(
                            text = "%02d".format(index + 1),
                            fontSize = 7.5.sp,
                            lineHeight = 9.sp,
                            color = if (index == peak) colors.gold else colors.ink3,
                            maxLines = 1,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("最高 ${peak + 1} 月 · ", style = TallyType.helper, color = colors.ink3)
                MoneyText(
                    amountMinor = maxAmount,
                    sign = MoneySign.NONE,
                    style = TallyType.moneyList.copy(fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.Normal),
                    color = colors.ink3,
                    grouping = true,
                    showCurrency = true,
                )
                Spacer(Modifier.weight(1f))
                Text("占 ${(peakShare * 100).roundToInt()}%", style = TallyType.helper, color = colors.ink3)
            }
        }
    }
}

@Composable
private fun CompositionCard(categories: List<io.github.guohangbk.tally.data.db.dao.TagCategoryRow>) {
    val colors = tallyColors
    val palette = statsPalette()
    val total = categories.sumOf { it.amountMinor }
    if (categories.isEmpty() || total <= 0L) {
        TallyCard(corner = 15, contentPadding = 0.dp) {
            Text(
                text = "这段里没有支出",
                style = TallyType.helper,
                color = colors.ink3,
                modifier = Modifier.padding(horizontal = 13.dp, vertical = 12.dp),
            )
        }
        return
    }
    val ranking = categories.map { row ->
        CategoryTotal(
            categoryId = row.categoryId,
            name = row.name,
            amountMinor = row.amountMinor,
            share = row.amountMinor.toFloat() / total,
            iconKey = row.iconKey,
        )
    }
    TallyCard(corner = 15, contentPadding = 0.dp) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 13.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Donut(
                ranking = ranking,
                palette = palette,
                totalLabel = "支出",
                totalMinor = total,
                grouping = true,
                selected = null,
                onSelectSlice = {},
                diameter = 96.dp,
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                categories.forEachIndexed { index, row ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(palette[index % palette.size]),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = row.name,
                            fontSize = 11.5.sp,
                            lineHeight = 14.4.sp,
                            color = colors.ink2,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        MoneyText(
                            amountMinor = row.amountMinor,
                            sign = MoneySign.NONE,
                            style = TallyType.moneyList.copy(fontSize = 10.5.sp, lineHeight = 13.sp, fontWeight = FontWeight.Normal),
                            color = colors.ink2,
                            grouping = true,
                            showCurrency = false,
                        )
                        Text(
                            text = " · ${(row.amountMinor.toFloat() / total * 100).roundToInt()}%",
                            fontSize = 10.5.sp,
                            lineHeight = 13.sp,
                            color = colors.ink3,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LedgerCard(
    ledgers: List<io.github.guohangbk.tally.data.db.dao.TagLedgerRow>,
    totalBills: Int,
    onOpenAll: () -> Unit,
) {
    val colors = tallyColors
    TallyCard(corner = 15, contentPadding = 0.dp) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 13.dp, vertical = 4.dp)) {
            ledgers.forEach { row ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = row.ledgerName,
                            fontSize = 12.5.sp,
                            lineHeight = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = colors.ink,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = "${row.billCount} 笔",
                            style = TallyType.helper.copy(fontSize = 10.5.sp, lineHeight = 13.sp),
                            color = colors.ink3,
                        )
                    }
                    MoneyText(
                        amountMinor = row.amountMinor,
                        sign = MoneySign.NONE,
                        style = TallyType.moneyList.copy(fontSize = 13.sp, lineHeight = 16.sp),
                        color = colors.ink2,
                        grouping = true,
                        showCurrency = false,
                    )
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(colors.line))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenAll)
                    .padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "查看全部 $totalBills 笔",
                    style = TallyType.listTitle,
                    color = colors.brand,
                    modifier = Modifier.weight(1f),
                )
                Text("进搜索", style = TallyType.helper, color = colors.ink3)
                Spacer(Modifier.width(3.dp))
                InlineIcon(iconKey = "chevron", contentDescription = null, size = 12.dp, tint = colors.ink3)
            }
        }
    }
}

/** 日期（TG-11）：快捷与自定义二选一，点「完成」一起变。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TagRangeSheet(
    rangeStart: Long,
    rangeEndInclusive: Long,
    onPreset: (TagRangePreset) -> Unit,
    onCustom: (Long, Long) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = tallyColors
    var preset by remember { mutableStateOf<TagRangePreset?>(null) }
    var start by remember { mutableStateOf(startOfDay(rangeStart)) }
    var end by remember { mutableStateOf(startOfDay(rangeEndInclusive)) }
    var picking by remember { mutableStateOf<Int?>(null) }

    TallyBottomSheet(onDismissRequest = onDismiss, containerColor = colors.paper) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("账单日期", style = TallyType.sectionTitle, color = colors.ink, modifier = Modifier.weight(1f))
                InlineIcon(
                    iconKey = "close",
                    contentDescription = "关闭",
                    size = 14.dp,
                    tint = colors.ink3,
                    modifier = Modifier.clickable(onClick = onDismiss),
                )
            }
            Spacer(Modifier.height(12.dp))
            Text("快捷", style = TallyType.eyebrow, color = colors.ink3)
            Spacer(Modifier.height(7.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                TagRangePreset.entries.forEach { option ->
                    val active = preset == option
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(9.dp))
                            .background(if (active) colors.brandSurface else colors.card)
                            .border(1.dp, if (active) colors.brand.copy(alpha = 0.35f) else colors.line, RoundedCornerShape(9.dp))
                            .clickable {
                                preset = option
                            }
                            .padding(horizontal = 11.dp, vertical = 6.dp),
                    ) {
                        Text(
                            text = option.label,
                            fontSize = 11.5.sp,
                            lineHeight = 14.4.sp,
                            color = if (active) colors.brand else colors.ink2,
                            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Text("自定义", style = TallyType.eyebrow, color = colors.ink3)
            Spacer(Modifier.height(7.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                DateField(
                    label = "开始日期",
                    value = start,
                    modifier = Modifier.weight(1f),
                    onClick = { picking = 0 },
                )
                Text("–", style = TallyType.helper, color = colors.ink3)
                DateField(
                    label = "截止日期",
                    value = end,
                    modifier = Modifier.weight(1f),
                    onClick = { picking = 1 },
                )
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                TallyButton(
                    label = "重置",
                    onClick = {
                        preset = null
                        start = startOfDay(rangeStart)
                        end = startOfDay(rangeEndInclusive)
                    },
                    variant = ButtonVariant.GHOST,
                    height = 34,
                    modifier = Modifier.weight(1f),
                )
                TallyButton(
                    label = "完成",
                    onClick = {
                        val selected = preset
                        if (selected != null) onPreset(selected) else onCustom(start, end)
                    },
                    variant = ButtonVariant.BRAND,
                    height = 34,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }

    when (picking) {
        0 -> TallyDatePickerDialog(
            initialDate = start,
            onDismiss = { picking = null },
            onConfirm = {
                start = it
                preset = null
                picking = null
            },
        )
        1 -> TallyDatePickerDialog(
            initialDate = end,
            onDismiss = { picking = null },
            onConfirm = {
                end = it
                preset = null
                picking = null
            },
        )
    }
}

@Composable
private fun DateField(
    label: String,
    value: Long,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val colors = tallyColors
    val shape = RoundedCornerShape(10.dp)
    val date = millisToLocalDate(value)
    Column(
        modifier = modifier
            .clip(shape)
            .background(colors.card)
            .border(1.dp, colors.line, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 7.dp),
    ) {
        Text(label, fontSize = 9.5.sp, lineHeight = 12.sp, color = colors.ink3)
        Spacer(Modifier.height(2.dp))
        Text(
            text = "%04d-%02d-%02d".format(date.year, date.monthValue, date.dayOfMonth),
            fontSize = 12.sp,
            lineHeight = 15.sp,
            color = colors.ink,
        )
    }
}
