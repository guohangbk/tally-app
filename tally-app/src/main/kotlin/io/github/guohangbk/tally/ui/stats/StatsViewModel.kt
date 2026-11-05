package io.github.guohangbk.tally.ui.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.guohangbk.tally.data.preferences.TallySettings
import io.github.guohangbk.tally.di.AppGraph
import io.github.guohangbk.tally.domain.Period
import io.github.guohangbk.tally.domain.PeriodRange
import io.github.guohangbk.tally.domain.model.BillType
import io.github.guohangbk.tally.domain.model.CategoryBills
import io.github.guohangbk.tally.domain.model.CategoryTotal
import io.github.guohangbk.tally.domain.model.StatisticsSnapshot
import io.github.guohangbk.tally.domain.model.StatsBucket
import io.github.guohangbk.tally.domain.model.StatsGranularity
import io.github.guohangbk.tally.domain.model.StatsRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 区间维度的快捷预设：都从今天往回数，算完就落到具体日期，翻页即离开预设。 */
enum class RangePreset(val label: String) {
    MONTH_TO_DATE("本月至今"),
    LAST_7_DAYS("近 7 天"),
    LAST_30_DAYS("近 30 天"),
    YEAR_TO_DATE("今年至今"),
}

/**
 * 分类报表下钻弹层的一层。一级弹层（[children] 非空）画这一级的拆分行：各个二级在前，
 * 直接挂在一级上（没选二级）的钱排在最后，沿用一级分类自己的名称与图标；
 * 二级弹层与没有下级的独立一级直接出账单列表。
 *
 * [l1Id] / [l2Id] 是账单列表按哪一级筛：[l2Id] 为 null 时只要**直接挂在 [l1Id] 上**的
 * 账单（没有二级的那些）；非空时筛这个二级。
 */
data class CategoryDrill(
    val l1Id: String?,
    val l2Id: String?,
    val name: String,
    val iconKey: String?,
    val row: CategoryTotal?,
    val children: List<CategoryTotal> = emptyList(),
    val type: BillType,
) {
    val isLevelOne: Boolean get() = children.isNotEmpty()
    /** 直接挂在这一级上的钱：一级合计 − 各二级合计。 */
    val directMinor: Long get() = (row?.amountMinor ?: 0L) - children.sumOf { it.amountMinor }
}

/** 日报表/月报表点开一天或一月时的账单明细。 */
data class BucketBills(val bucketStart: Long, val bills: List<io.github.guohangbk.tally.domain.model.BillListItem>)

/** 五路 series 状态捆成一包：combine 最多接五路，外层还要再合账本与设置。 */
private data class SeriesState(
    val daily: BillType,
    val ranking: BillType,
    val weekday: BillType,
    val weekdayPick: Int?,
    val pick: Long?,
)

data class StatsUiState(
    val granularity: StatsGranularity = StatsGranularity.MONTH,
    val anchorStart: Long = Period.monthRange(System.currentTimeMillis()).startAt,
    /** 每月起始日：只影响月维度与首页一致的那条边界。 */
    val startDay: Int = Period.DEFAULT_START_DAY,
    /** 区间维度的右边界（不含）；月/年维度为 null。 */
    val rangeEndExclusive: Long? = null,
    /** 区间维度实际用的分桶（自动或用户指定）。 */
    val bucket: StatsBucket? = null,
    val ledgerId: String? = null,
    val ledgerName: String = "全部账本",
    val snapshot: StatisticsSnapshot? = null,
    val dailySeries: BillType = BillType.EXPENSE,
    val rankingSeries: BillType = BillType.EXPENSE,
    val weekdaySeries: BillType = BillType.EXPENSE,
    /** 星期分布里被点开的柱子（0–6，周一…周日）；null 时用最高的那天。 */
    val weekdayPicked: Int? = null,
    /** 趋势里被点开的那个桶（日/周/月的起点）。 */
    val selectedBucketStart: Long? = null,
    val excludePendingReimburse: Boolean = false,
    val settings: TallySettings = TallySettings.DEFAULT,
) {
    val range: PeriodRange
        get() = when (granularity) {
            StatsGranularity.MONTH -> Period.monthRange(anchorStart, startDay)
            StatsGranularity.YEAR -> Period.yearRange(anchorStart)
            StatsGranularity.RANGE -> Period.dayRange(anchorStart, rangeEndExclusive ?: Period.shiftDays(anchorStart, 1))
        }

    /** 顶栏那一行：月维度沿用首页的周期标签，区间写「9/12–10/11」。 */
    val periodLabel: String
        get() = when (granularity) {
            StatsGranularity.MONTH -> Period.label(Period.monthRange(anchorStart, startDay), startDay)
            StatsGranularity.YEAR -> "${Period.toDate(anchorStart).year} 年"
            StatsGranularity.RANGE -> Period.rangeLabel(anchorStart, rangeEndExclusive ?: Period.shiftDays(anchorStart, 1))
        }

    /** 小莉结算是同一份请求：口径与屏上四段必须完全一致。 */
    fun analysisRequest(): StatsRequest = StatsRequest(
        ledgerId = ledgerId,
        periodStart = anchorStart,
        granularity = granularity,
        excludePendingReimburse = excludePendingReimburse,
        startDay = startDay,
        rangeEndExclusive = rangeEndExclusive.takeIf { granularity == StatsGranularity.RANGE },
        bucket = bucket.takeIf { granularity == StatsGranularity.RANGE },
    )
}

/** 四段共用一次查询：换账本、换维度、换区间、换收支只重算这个数字，不换页。 */
class StatsViewModel(
    private val graph: AppGraph,
    initialLedgerId: String? = null,
    initialAnchor: Long? = null,
) : ViewModel() {
    private val granularity = MutableStateFlow(StatsGranularity.MONTH)
    // 首页翻到哪个月，统计就从那个月起步；没给才按「今天所在月」。
    private val anchorStart = MutableStateFlow(initialAnchor ?: Period.monthRange(System.currentTimeMillis()).startAt)
    private val rangeEnd = MutableStateFlow<Long?>(null)
    private val bucket = MutableStateFlow<StatsBucket?>(null)
    private val startDay = MutableStateFlow(Period.DEFAULT_START_DAY)
    private val ledgerId = MutableStateFlow(initialLedgerId)
    private val dailySeries = MutableStateFlow(BillType.EXPENSE)
    private val rankingSeries = MutableStateFlow(BillType.EXPENSE)
    private val weekdaySeries = MutableStateFlow(BillType.EXPENSE)
    private val weekdayPicked = MutableStateFlow<Int?>(null)
    private val selectedBucket = MutableStateFlow<Long?>(null)
    private val excludePendingReimburse = MutableStateFlow(false)
    private val settings = graph.preferences.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, TallySettings.DEFAULT)

    /**
     * 统计页那颗「分析本月」能不能出现。没开小莉、或开了但模型还没配好，
     * 入口本身就该隐掉，而不是让用户点进去再看一段「你还没配置」的说明。
     */
    private val aiEntry = MutableStateFlow(false)
    val aiEntryVisible: StateFlow<Boolean> = aiEntry.asStateFlow()

    private data class PeriodChoice(
        val granularity: StatsGranularity,
        val anchor: Long,
        val rangeEnd: Long?,
        val bucket: StatsBucket?,
        val startDay: Int,
    )

    private val request = combine(
        combine(granularity, anchorStart, rangeEnd, bucket, startDay) { period, anchor, end, bucket, day ->
            PeriodChoice(period, anchor, end, bucket, day)
        },
        combine(ledgerId, excludePendingReimburse) { ledger, pending -> ledger to pending },
    ) { period, (ledger, pending) ->
        StatsRequest(
            ledgerId = ledger,
            periodStart = period.anchor,
            granularity = period.granularity,
            excludePendingReimburse = pending,
            startDay = period.startDay,
            rangeEndExclusive = period.rangeEnd.takeIf { period.granularity == StatsGranularity.RANGE },
            bucket = period.bucket.takeIf { period.granularity == StatsGranularity.RANGE },
        )
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private val snapshot = request.flatMapLatest { graph.billRepository.observeStatistics(it) }

    /**
     * 统计页的预算段：只显示总额进度与分类预算前两条。
     * 与首页、组件是同一份聚合——预算层不自己再算一遍口径。
     * 周期跟着页面上的节奏走：年维度读「每年预算」那一组、月与区间读「每月预算」，锚点用当前选中的那一段（2026-10-28 走查）。
     */
    val budgetSummary: StateFlow<io.github.guohangbk.tally.domain.model.BudgetSummary?> =
        request
            .mapNotNull { r -> r.ledgerId?.let { r to it } }
            .flatMapLatest { (r, ledger) ->
                graph.budgetRepository.observeSummary(
                    ledgerId = ledger,
                    period = if (r.granularity == StatsGranularity.YEAR) {
                        io.github.guohangbk.tally.domain.model.BudgetPeriod.YEARLY
                    } else {
                        io.github.guohangbk.tally.domain.model.BudgetPeriod.MONTHLY
                    },
                    startDay = r.startDay,
                    rangeAnchor = r.periodStart,
                )
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * 下钻只有一个当前层：一级弹层、末级账单列表，两者互斥。
     * 分类树只有两级，末级不再往下钻，也就不需要路径栈。
     */
    private val drillTarget = MutableStateFlow<CategoryDrill?>(null)
    val drill: StateFlow<CategoryDrill?> = drillTarget.asStateFlow()

    /**
     * 账单列表层的数据。只有列表层才查账：一级弹层要的拆分行已经在排行里算好了，
     * 再为它跑一遍查询就是同一个数算两遍。
     */
    private val drillBills: StateFlow<CategoryBills?> = drillTarget.flatMapLatest { target ->
        if (target == null || target.isLevelOne) {
            flowOf(null)
        } else {
            graph.billRepository.observeCategoryBills(
                ledgerId = ledgerId.value,
                range = currentRange(),
                type = target.type,
                l1Id = target.l1Id,
                l2Id = target.l2Id,
                excludePendingReimburse = excludePendingReimburse.value,
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val drillBillsState: StateFlow<CategoryBills?> = drillBills

    /** 与页面四段同一口径的边界：换维度、翻页、改区间都走这一条。 */
    private fun currentRange(): PeriodRange = when (granularity.value) {
        StatsGranularity.MONTH -> Period.monthRange(anchorStart.value, startDay.value)
        StatsGranularity.YEAR -> Period.yearRange(anchorStart.value)
        StatsGranularity.RANGE -> Period.dayRange(
            anchorStart.value,
            rangeEnd.value ?: Period.shiftDays(anchorStart.value, 1),
        )
    }

    /** 点一级分类行：有下级停在一级弹层（拆分行），没有下级一次就出账单列表。 */
    fun openCategory(item: CategoryTotal, type: BillType) {
        drillTarget.value = CategoryDrill(
            l1Id = item.categoryId,
            l2Id = null,
            name = item.name,
            iconKey = item.iconKey,
            row = item,
            children = item.children,
            type = type,
        )
    }

    /** 一级弹层里点一行：没有 id 的那行是直接挂在一级上的钱，沿用一级名称；其余是各个二级。 */
    fun drillInto(child: CategoryTotal?) {
        val parent = drillTarget.value ?: return
        if (!parent.isLevelOne) return
        drillTarget.value = if (child == null || child.categoryId == null) {
            CategoryDrill(
                l1Id = parent.l1Id,
                l2Id = null,
                name = parent.name,
                iconKey = parent.iconKey,
                row = null,
                type = parent.type,
            )
        } else {
            CategoryDrill(
                l1Id = parent.l1Id,
                l2Id = child.categoryId,
                name = "${parent.name} · ${child.name}",
                iconKey = child.iconKey,
                row = child,
                type = parent.type,
            )
        }
    }

    fun closeCategory() { drillTarget.value = null }

    /** 日报表/月报表点开一天或一月。 */
    private val bucketBillsOpen = MutableStateFlow<Long?>(null)
    val bucketBills: StateFlow<BucketBills?> = bucketBillsOpen.flatMapLatest { start ->
        if (start == null) {
            flowOf(null)
        } else {
            graph.billRepository.observeBucketBills(
                ledgerId = ledgerId.value,
                PeriodRange(start, bucketEnd(start)),
            ).map { BucketBills(start, it) }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun openBucketBills(start: Long) { bucketBillsOpen.value = start }
    fun closeBucketBills() { bucketBillsOpen.value = null }

    /** 桶的右边界（不含）：日 +1 天、周 +7 天、月 +1 月。 */
    private fun bucketEnd(start: Long): Long = when {
        granularity.value == StatsGranularity.YEAR -> Period.shift(start, 1)
        granularity.value == StatsGranularity.RANGE -> when (bucket.value ?: StatsBucket.DAY) {
            StatsBucket.DAY -> Period.shiftDays(start, 1)
            StatsBucket.WEEK -> Period.shiftDays(start, 7)
            StatsBucket.MONTH -> Period.shift(start, 1)
        }
        else -> Period.shiftDays(start, 1)
    }

    val state: StateFlow<StatsUiState> = combine(
        snapshot,
        request,
        combine(dailySeries, rankingSeries, weekdaySeries, weekdayPicked, selectedBucket) {
            daily, ranking, weekday, pickWeekday, pick -> SeriesState(daily, ranking, weekday, pickWeekday, pick)
        },
        combine(graph.ledgerRepository.items, settings) { ledgers, preferences -> ledgers to preferences },
    ) { statistics, query, series, (ledgers, preferences) ->
        StatsUiState(
            granularity = query.granularity,
            anchorStart = query.periodStart,
            startDay = query.startDay,
            rangeEndExclusive = query.rangeEndExclusive,
            bucket = if (query.granularity == StatsGranularity.RANGE) statistics.bucket ?: query.bucket else null,
            ledgerId = query.ledgerId,
            ledgerName = ledgers.firstOrNull { it.ledger.id == query.ledgerId }?.ledger?.name ?: "全部账本",
            snapshot = statistics,
            dailySeries = series.daily,
            rankingSeries = series.ranking,
            weekdaySeries = series.weekday,
            weekdayPicked = series.weekdayPick,
            selectedBucketStart = series.pick,
            excludePendingReimburse = query.excludePendingReimburse,
            settings = preferences,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StatsUiState())

    init {
        // 账本只由入口决定：首页选「全部账本」传进来的就是 null，统计跟着算全部。
        // 这里不能再回落到默认账本——那正是「首页选了全部，进统计却只剩日常账本」的根因。
        viewModelScope.launch { graph.ledgerRepository.bootstrap() }
        // 开关一改就重新判定：入口显隐不能等到用户点开才发现不对。
        viewModelScope.launch(Dispatchers.IO) {
            graph.preferences.settings.collect { aiEntry.value = graph.aiGateway.availability().showsEntry }
        }
        // 起始日一改，当前看的那段按月重新切一刀。这里必须跟着 anchorStart 走而不是「今天」：
        // 这个流首次回读也会发一次，写死今天就会把首页带进来的那个月当场冲掉。
        viewModelScope.launch(Dispatchers.IO) {
            graph.settingRepository.monthStartDay.collect { day ->
                startDay.value = day
                anchorStart.value = Period.monthRange(anchorStart.value, day).startAt
                if (granularity.value == StatsGranularity.RANGE && rangeEnd.value == null) {
                    rangeEnd.value = defaultRange(day).endAtExclusive
                }
            }
        }
    }

    /**
     * 首页带着某个月进来时重新落锚点。统计的 ViewModel 按目的地缓存且不带 key，
     * 只在构造参数里读一次的话，第二次从首页翻到别的月份进来仍是上一段——所以每次进页调一次。
     * 区间维度不跟着改：它有自己的预设，不该被首页那一段月份覆盖。
     */
    fun anchorAt(anchor: Long) {
        anchorStart.value = when (granularity.value) {
            StatsGranularity.MONTH -> Period.monthRange(anchor, startDay.value).startAt
            StatsGranularity.YEAR -> Period.yearRange(anchor).startAt
            StatsGranularity.RANGE -> return
        }
    }

    fun setGranularity(value: StatsGranularity) {
        if (value == granularity.value) return
        when (value) {
            StatsGranularity.MONTH -> anchorStart.value = Period.monthRange(anchorStart.value, startDay.value).startAt
            StatsGranularity.YEAR -> anchorStart.value = Period.yearRange(anchorStart.value).startAt
            StatsGranularity.RANGE -> {
                val range = defaultRange(startDay.value)
                anchorStart.value = range.startAt
                rangeEnd.value = range.endAtExclusive
            }
        }
        if (value != StatsGranularity.RANGE) {
            rangeEnd.value = null
            bucket.value = null
        }
        granularity.value = value
        selectedBucket.value = null
        // 弹层里的数字跟着范围走，翻页后再留着上一个周期的分类明细是自找的混乱。
        closeCategory()
    }

    fun previous() { shift(-1) }
    fun next() { shift(1) }

    private fun shift(delta: Int) {
        when (granularity.value) {
            StatsGranularity.MONTH -> anchorStart.value = Period.shift(anchorStart.value, delta.toLong())
            StatsGranularity.YEAR -> anchorStart.value = Period.shift(anchorStart.value, delta * 12L)
            StatsGranularity.RANGE -> {
                val end = rangeEnd.value ?: defaultRange(startDay.value).endAtExclusive
                val length = Period.daysBetween(anchorStart.value, end)
                anchorStart.value = Period.shiftDays(anchorStart.value, delta.toLong() * length)
                rangeEnd.value = Period.shiftDays(end, delta.toLong() * length)
            }
        }
        selectedBucket.value = null
        closeCategory()
    }

    /** 月份/年份选择器给出该周期内任意一天。 */
    fun pickPeriod(epochMillis: Long) {
        when (granularity.value) {
            StatsGranularity.MONTH -> {
                val date = Period.toDate(epochMillis)
                anchorStart.value = Period.monthStart(date.year, date.monthValue, startDay.value)
            }
            StatsGranularity.YEAR -> anchorStart.value = Period.yearRange(epochMillis).startAt
            StatsGranularity.RANGE -> setRange(epochMillis, Period.shiftDays(epochMillis, 1))
        }
        selectedBucket.value = null
        closeCategory()
    }

    /** 自定义区间：左闭右开，起止都按本地日期归零。换了跨度，之前手选的分桶就作废。 */
    fun setRange(startAt: Long, endExclusive: Long) {
        val start = Period.dayRange(startAt, startAt).startAt
        val end = Period.dayRange(endExclusive, endExclusive).startAt
        anchorStart.value = start
        rangeEnd.value = maxOf(end, Period.shiftDays(start, 1))
        granularity.value = StatsGranularity.RANGE
        bucket.value = null
        selectedBucket.value = null
        closeCategory()
    }

    fun applyPreset(preset: RangePreset) {
        val now = System.currentTimeMillis()
        val today = Period.dayRange(now, now).startAt
        val end = Period.shiftDays(today, 1)
        val start = when (preset) {
            RangePreset.MONTH_TO_DATE -> Period.monthRange(now, startDay.value).startAt
            RangePreset.LAST_7_DAYS -> Period.shiftDays(today, -6)
            RangePreset.LAST_30_DAYS -> Period.shiftDays(today, -29)
            RangePreset.YEAR_TO_DATE -> Period.yearRange(now).startAt
        }
        anchorStart.value = start
        rangeEnd.value = end
        granularity.value = StatsGranularity.RANGE
        bucket.value = null
        selectedBucket.value = null
        closeCategory()
    }

    fun setBucket(value: StatsBucket?) {
        bucket.value = value
        selectedBucket.value = null
    }

    fun selectDailySeries(type: BillType) { dailySeries.value = type }
    fun selectRankingSeries(type: BillType) { rankingSeries.value = type }
    fun selectWeekdaySeries(type: BillType) {
        weekdaySeries.value = type
        weekdayPicked.value = null
    }
    fun selectWeekday(index: Int) { weekdayPicked.value = index }
    fun selectBucket(start: Long) { selectedBucket.value = start }
    fun setExcludePendingReimburse(exclude: Boolean) { excludePendingReimburse.value = exclude }

    /** 默认区间就是「本月至今」：跟月视图一开始看到的是同一段数字。 */
    private fun defaultRange(day: Int): PeriodRange {
        val now = System.currentTimeMillis()
        val today = Period.dayRange(now, now).startAt
        return PeriodRange(
            startAt = Period.monthRange(now, day).startAt,
            endAtExclusive = Period.shiftDays(today, 1),
        )
    }
}
