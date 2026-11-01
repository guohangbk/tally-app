package io.github.guohangbk.tally.domain.model

import io.github.guohangbk.tally.data.db.entity.BillEntity
import io.github.guohangbk.tally.data.db.entity.CategoryEntity
import io.github.guohangbk.tally.domain.Period
import io.github.guohangbk.tally.domain.PeriodRange
import kotlinx.coroutines.flow.Flow

/** 记账页提交的草稿。只有调用 BillRepository.save 才会落库。 */
data class BillDraft(
    val id: String? = null,
    val ledgerId: String? = null,
    val categoryL1Id: String? = null,
    val categoryL2Id: String? = null,
    val type: BillType = BillType.EXPENSE,
    val amountMinor: Long = 0L,
    val occurredAt: Long = System.currentTimeMillis(),
    val timePrecision: TimePrecision = TimePrecision.DATE,
    val note: String? = null,
    val reimbursed: ReimburseStatus = ReimburseStatus.NONE,
    val isRefund: Boolean = false,
    /** 被退款的原单；非空即这笔是退款，金额累计不得超原单。 */
    val refundOfBillId: String? = null,
    /** 不计入收支：账单级开关，记账页 / 详情 / 批量三处同源（F6）。 */
    val excludedFromStats: Boolean = false,
    /** 速记与截图确认入账时带上来源，账单行才能显示「速记 · 语音」这类副标题。 */
    val source: BillSource = BillSource.MANUAL,
    /** 截图里的订单号；同一订单号重复导入要提示而不是静默跳过。 */
    val externalId: String? = null,
    /** 图片指纹，用于「同一张截图重复分享」的去重。 */
    val fingerprint: String? = null,
)

data class BillListItem(
    val bill: BillEntity,
    val ledgerName: String,
    val categoryL1Name: String?,
    val categoryL2Name: String?,
    val categoryL1Icon: String? = null,
    val categoryL2Icon: String? = null,
    val installmentPeriodCount: Int? = null,
    val attachmentCount: Int = 0,
    /** 作为原单被退掉的累计金额；只有原单行有值，退款行自己不算。 */
    val refundedMinor: Long = 0L,
) {
    val categoryPath: String
        get() = listOfNotNull(categoryL1Name, categoryL2Name).joinToString(" · ")

    /** 行上图标用二级自己的图标，二级没有时才回落一级。 */
    val iconKey: String?
        get() = categoryL2Icon ?: categoryL1Icon
}

data class BillSummary(
    val billCount: Int = 0,
    val expenseMinor: Long = 0L,
    val incomeMinor: Long = 0L,
    val excludedMinor: Long = 0L,
    val excludedCount: Int = 0,
    val pendingReimburseMinor: Long = 0L,
    val pendingReimburseCount: Int = 0,
    /** 已报销那几笔：钱会回来，不进支出合计，单列出来好对账。 */
    val reimbursedMinor: Long = 0L,
    val reimbursedCount: Int = 0,
) {
    val balanceMinor: Long get() = incomeMinor - expenseMinor
}

data class BillDayGroup(
    val dayStart: Long,
    val bills: List<BillListItem>,
    val expenseMinor: Long,
    val incomeMinor: Long,
)

data class BillMonthSnapshot(
    val monthStart: Long,
    val summary: BillSummary,
    val days: List<BillDayGroup>,
)

/** 原单详情卡里的「已退」明细；点一行能跳去那笔退款。 */
data class RefundEntry(
    val id: String,
    val amountMinor: Long,
    val occurredAt: Long,
    val note: String?,
)

data class BillSearchResult(
    val summary: BillSummary,
    val bills: List<BillListItem>,
    /** 列表按最近一批截断了；命中数与合计仍是全量口径，页面要如实说明。 */
    val truncated: Boolean = false,
)

/**
 * 分类报表下钻弹层里的记录。[netMinor] 与排行上那一行同口径：退款按原单分类冲抵、
 * 可选剔除未报销。弹层上若出现「合计与上面那行对不上」，比没有明细更伤信任。
 */
data class CategoryBills(
    val bills: List<BillListItem>,
    val netMinor: Long,
)

/**
 * 搜索的排序档。四档都在 SQL 里排，不在界面上排——列表只取回一批（[BillSearchResult.bills]），
 * 本地排序等于只在这批里排，「金额最高的那笔」会排错。
 */
enum class BillSort {
    TIME_DESC,
    TIME_ASC,
    AMOUNT_DESC,
    AMOUNT_ASC;

    /** 只有按时间排的两种才按天分组；按金额排是排行榜，分组会把序打乱。 */
    val groupsByDay: Boolean get() = this == TIME_DESC || this == TIME_ASC
}

/** null 字段表示不限；endAtExclusive 使用左闭右开区间。 */
data class BillFilter(
    val keyword: String = "",
    val startAt: Long? = null,
    val endAtExclusive: Long? = null,
    val type: BillType? = null,
    val ledgerId: String? = null,
    val categoryL1Id: String? = null,
    val categoryL2Id: String? = null,
    val source: BillSource? = null,
    /** 金额区间（分）。真实库里 80% 的账单没有备注，关键词根本覆盖不到——区间是另一条筛路。 */
    val amountMinMinor: Long? = null,
    val amountMaxMinor: Long? = null,
    val sort: BillSort = BillSort.TIME_DESC,
)

/**
 * 统计粒度：月维度按日落桶，年维度按月落桶；区间维度按跨度自动切日/月，
 * 也可以由请求显式指定按周。
 */
enum class StatsGranularity {
    MONTH,
    YEAR,
    RANGE,
}

/** 区间分桶：短区间按日、长区间按月，用户可显式改周。月/年维度沿用固定桶。 */
enum class StatsBucket {
    DAY,
    WEEK,
    MONTH,
}

data class StatsRequest(
    val ledgerId: String?,
    val periodStart: Long,
    val granularity: StatsGranularity,
    val excludePendingReimburse: Boolean = false,
    /** 月起止日：只影响 MONTH 维度的边界。 */
    val startDay: Int = 1,
    /** 只有 RANGE 维度有值；区间为 [periodStart, rangeEndExclusive)。 */
    val rangeEndExclusive: Long? = null,
    /** 区间维度的分桶；null 表示按跨度自动选。 */
    val bucket: StatsBucket? = null,
) {
    companion object {
        /** 区间跨度 ≤ 62 天按日，其余按月。 */
        const val MAX_DAILY_RANGE_DAYS = 62

        fun autoBucket(startAt: Long, endAtExclusive: Long): StatsBucket =
            if (Period.daysBetween(startAt, endAtExclusive) <= MAX_DAILY_RANGE_DAYS) StatsBucket.DAY
            else StatsBucket.MONTH
    }
}

data class CategoryTotal(
    val categoryId: String?,
    val name: String,
    val amountMinor: Long,
    val share: Float,
    val iconKey: String? = null,
    val billCount: Int = 0,
    val changePercent: Float? = null,
    /** 一级行下的二级小计；[share] 是相对本一级的占比，不是全表占比。 */
    val children: List<CategoryTotal> = emptyList(),
)

/** 一段统计桶：月维度是一天，年维度是一月，字段含义相同。 */
data class DailyTotal(
    val dayStart: Long,
    val expenseMinor: Long,
    val incomeMinor: Long,
    val excludedMinor: Long,
    val billCount: Int = 0,
    val isFuture: Boolean = false,
)

data class StatisticsSnapshot(
    val monthStart: Long,
    val summary: BillSummary,
    val categoryRanking: List<CategoryTotal>,
    val dailyTrend: List<DailyTotal>,
    val previousExpenseMinor: Long,
    val expenseChangePercent: Float?,
    /** 去年同期同一段的支出与收入：按已过天数截断，与本期等长。 */
    val yearAgoExpenseMinor: Long = 0L,
    val yearAgoIncomeMinor: Long = 0L,
    val yearAgoChangePercent: Float? = null,
    /** 上一期的一级分类排行：环比那条结论要靠它说出「哪个分类带动的」。 */
    val previousCategoryRanking: List<CategoryTotal> = emptyList(),
    val granularity: StatsGranularity = StatsGranularity.MONTH,
    val incomeRanking: List<CategoryTotal> = emptyList(),
    val previousIncomeMinor: Long = 0L,
    val incomeChangePercent: Float? = null,
    val expenseCategoryCount: Int = 0,
    val incomeCategoryCount: Int = 0,

    /** 月维度是日均支出，年维度是月均支出，区间维度是按桶的均值；都按已过段数算。 */
    val averageExpenseMinor: Long = 0L,
    val highestExpenseDayStart: Long? = null,
    val zeroExpenseDayCount: Int = 0,
    val zeroExpenseFirstDayStart: Long? = null,

    /** 区间维度的右边界；月/年维度为 null。 */
    val rangeEndExclusive: Long? = null,
    /** 区间维度的分桶方式；月/年维度为 null。 */
    val bucket: StatsBucket? = null,
    /** 星期分布（周一…周日）：该星期几的支出净额、日均与最高的一天。 */
    val weekdayExpenseMinor: List<Long> = emptyList(),
    val weekdayAverageMinor: List<Long> = emptyList(),
    val weekdayPeak: Int = -1,
    /** 同一组分布的收入口径：柱子与「日均」跟着收支切换走。 */
    val weekdayIncomeMinor: List<Long> = emptyList(),
    val weekdayAverageIncomeMinor: List<Long> = emptyList(),
    val weekdayIncomePeak: Int = -1,
)

/** UI 只依赖这个稳定接口；实现放在 data/repository。 */
interface BillRepositoryContract {
    fun observeMonth(ledgerId: String?, range: PeriodRange): Flow<BillMonthSnapshot>

    /** 抽屉标题的「这本账 N 笔」：单账本/全部账本的轻量 COUNT，不碰整表 GROUP BY。 */
    fun observeBillCount(ledgerId: String?): Flow<Int>
    fun observeSearch(filter: BillFilter): Flow<BillSearchResult>
    fun observeStatistics(request: StatsRequest): Flow<StatisticsSnapshot>
    /** 分类报表下钻：弹层里列出这条分类下的记录，合计与排行同口径。 */
    fun observeCategoryBills(
        ledgerId: String?,
        range: PeriodRange,
        type: BillType,
        l1Id: String?,
        l2Id: String?,
        excludePendingReimburse: Boolean,
    ): Flow<CategoryBills>

    /** 日报表/月报表点开一天或一月时的账单明细：不按收支筛，两类都在。 */
    fun observeBucketBills(ledgerId: String?, range: PeriodRange): Flow<List<BillListItem>>

    /**
     * 预算的已用金额：净支出按**一级分类**归堆（退款冲原单分类、不计入收支排除、报销待处理占位）。
     * 预算层不自己再算一遍口径，只能消费这一份；键是一级分类 id（未分类是空串），值是净支出（分）。
     */
    fun observeNetExpenseByCategory(ledgerId: String?, range: PeriodRange): Flow<Map<String, Long>>

    suspend fun save(draft: BillDraft): String
    suspend fun find(id: String): BillListItem?
    suspend fun delete(id: String)
    suspend fun activeCategories(type: CategoryType): List<CategoryEntity>
    suspend fun defaultLedgerId(): String?
}
