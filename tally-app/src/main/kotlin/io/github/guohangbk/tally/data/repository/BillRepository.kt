package io.github.guohangbk.tally.data.repository

import io.github.guohangbk.tally.data.db.dao.AttachmentDao
import io.github.guohangbk.tally.data.db.dao.BillDao
import io.github.guohangbk.tally.data.db.dao.CategoryDao
import io.github.guohangbk.tally.data.db.dao.LedgerDao
import io.github.guohangbk.tally.data.db.dao.TagDao
import io.github.guohangbk.tally.data.db.entity.BillEntity
import io.github.guohangbk.tally.data.db.entity.BillTagEntity
import io.github.guohangbk.tally.data.db.entity.CategoryEntity
import io.github.guohangbk.tally.data.db.query.BillListItemRow
import io.github.guohangbk.tally.data.db.query.StatsBillRow
import io.github.guohangbk.tally.data.db.TallyDatabase
import io.github.guohangbk.tally.data.preferences.TallyPreferences
import io.github.guohangbk.tally.domain.Money
import io.github.guohangbk.tally.domain.Period
import io.github.guohangbk.tally.domain.PeriodRange
import io.github.guohangbk.tally.domain.policy.BillEditContext
import io.github.guohangbk.tally.domain.policy.BillEditPolicy
import io.github.guohangbk.tally.domain.model.BillDayGroup
import io.github.guohangbk.tally.domain.model.BillDraft
import io.github.guohangbk.tally.domain.model.BillFilter
import io.github.guohangbk.tally.domain.model.BillSort
import io.github.guohangbk.tally.domain.model.BillListItem
import io.github.guohangbk.tally.domain.model.BillMonthSnapshot
import io.github.guohangbk.tally.domain.model.BillRepositoryContract
import io.github.guohangbk.tally.domain.model.BillSearchResult
import io.github.guohangbk.tally.domain.model.BillSummary
import io.github.guohangbk.tally.domain.model.BillType
import io.github.guohangbk.tally.domain.model.CategoryBills
import io.github.guohangbk.tally.domain.model.CategoryTotal
import io.github.guohangbk.tally.domain.model.CategoryType
import io.github.guohangbk.tally.domain.model.DailyTotal
import io.github.guohangbk.tally.domain.model.DefaultCategoryTree
import io.github.guohangbk.tally.domain.model.GeneratedBillSpec
import io.github.guohangbk.tally.domain.model.ReimburseStatus
import io.github.guohangbk.tally.domain.model.RefundEntry
import io.github.guohangbk.tally.domain.model.RecurringPeriodRow
import io.github.guohangbk.tally.domain.model.StatsBucket
import io.github.guohangbk.tally.domain.model.StatsGranularity
import io.github.guohangbk.tally.domain.model.StatsRequest
import io.github.guohangbk.tally.domain.model.StatisticsSnapshot
import io.github.guohangbk.tally.domain.model.TallyError
import io.github.guohangbk.tally.domain.model.TimePrecision
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * 搜索列表一次最多映射多少笔：命中更多时页面写「还有 N 条未列出」，
 * 收窄靠日期、分类与金额区间，而不是把整本账搬进内存。
 */
private const val SEARCH_ROW_LIMIT = 1000

/** `IN (:ids)` 一次最多绑多少：API 28 的 SQLite 上限是 999，留出余量。 */
private const val TAG_QUERY_CHUNK = 500

/** 概览页的统计：合计与一级分类排行。口径与统计页是同一套聚合函数，只是不再产出整份快照。 */
data class OverviewStats(
    val expenseMinor: Long,
    val incomeMinor: Long,
    val balanceMinor: Long,
    val categories: List<CategoryTotal>,
)

/**
 * M2 账单读写与统计口径。页面不自行计算金额，所有汇总都从同一查询结果派生。
 */
class BillRepository(
    private val database: TallyDatabase,
    private val billDao: BillDao,
    private val ledgerDao: LedgerDao,
    private val categoryDao: CategoryDao,
    private val attachmentDao: AttachmentDao,
    private val tagDao: TagDao,
    private val preferences: TallyPreferences,
    private val now: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val zoneId: () -> ZoneId = ZoneId::systemDefault,
) : BillRepositoryContract {

    /** 单账本与全账本各用一条等值/无条件的 SQL：可选条件写成 `:x IS NULL OR ...` 会丢掉索引。 */
    private fun rangeRows(
        ledgerId: String?,
        startAt: Long,
        endAtExclusive: Long,
    ): Flow<List<BillListItemRow>> =
        if (ledgerId == null) {
            billDao.observeRangeRowsAllLedgers(startAt, endAtExclusive)
        } else {
            billDao.observeRangeRows(ledgerId, startAt, endAtExclusive)
        }

    override fun observeMonth(ledgerId: String?, range: PeriodRange): Flow<BillMonthSnapshot> =
        // 行上的标签是二次查询补的：加上关联表的变更信号，打标 / 改名后列表才会重取一次。
        combine(
            rangeRows(ledgerId, range.startAt, range.endAtExclusive),
            tagDao.observeLinkTick(),
        ) { rows, _ ->
            val items = withTags(rows.map(::toItem))
            BillMonthSnapshot(
                monthStart = range.startAt,
                summary = summary(items),
                days = groupByDay(items),
            )
        }.flowOn(Dispatchers.Default)

    override fun observeBillCount(ledgerId: String?): Flow<Int> = billDao.observeBillCount(ledgerId)

    override fun observeSearch(filter: BillFilter): Flow<BillSearchResult> {
        // 条件只取一次，四条排序查询与汇总查询共用同一份，不会出现「列表一套、汇总另一套」。
        val keyword = filter.keyword.trim()
        val startAt = filter.startAt
        val endAtExclusive = filter.endAtExclusive
        val type = filter.type?.name
        val ledgerId = filter.ledgerId
        val categoryL1Id = filter.categoryL1Id
        val categoryL2Id = filter.categoryL2Id
        val source = filter.source?.name
        val amountMin = filter.amountMinMinor
        val amountMax = filter.amountMaxMinor
        // 标签多选是并集；Room 不认空列表的 IN，短路开关 tagFilter=0 时补一个永不命中的占位值。
        val tagFilter = if (filter.tagIds.isEmpty()) 0 else 1
        val tagIds = filter.tagIds.ifEmpty { listOf("") }
        val limit = SEARCH_ROW_LIMIT

        // 排序必须在 SQL 里选哪一条查询：列表只取回这一批，回到界面上排等于只在这批里排，
        // 「金额最高的那笔」会排错。
        val rows = when (filter.sort) {
            BillSort.TIME_DESC ->
                billDao.observeSearchRowsNewest(keyword, startAt, endAtExclusive, type, ledgerId, categoryL1Id, categoryL2Id, source, amountMin, amountMax, tagFilter, tagIds, limit)

            BillSort.TIME_ASC ->
                billDao.observeSearchRowsOldest(keyword, startAt, endAtExclusive, type, ledgerId, categoryL1Id, categoryL2Id, source, amountMin, amountMax, tagFilter, tagIds, limit)

            BillSort.AMOUNT_DESC ->
                billDao.observeSearchRowsLargest(keyword, startAt, endAtExclusive, type, ledgerId, categoryL1Id, categoryL2Id, source, amountMin, amountMax, tagFilter, tagIds, limit)

            BillSort.AMOUNT_ASC ->
                billDao.observeSearchRowsSmallest(keyword, startAt, endAtExclusive, type, ledgerId, categoryL1Id, categoryL2Id, source, amountMin, amountMax, tagFilter, tagIds, limit)
        }
        val summary = billDao.observeSearchSummary(
            keyword, startAt, endAtExclusive, type, ledgerId, categoryL1Id, categoryL2Id, source, amountMin, amountMax,
            tagFilter, tagIds,
        )
        return combine(rows, summary, tagDao.observeLinkTick()) { list, aggregate, _ ->
            // 列表只取最近一批，汇总走 SQL：几千笔账不该在打开搜索时整表映射成对象再分组。
            BillSearchResult(
                summary = aggregate,
                bills = withTags(list.map(::toItem)),
                truncated = list.size >= limit,
            )
        }.flowOn(Dispatchers.Default)
    }

    override fun observeStatistics(request: StatsRequest): Flow<StatisticsSnapshot> {
        val bounds = statsBounds(request)
        val bucket = when (request.granularity) {
            StatsGranularity.MONTH -> StatsBucket.DAY
            StatsGranularity.YEAR -> StatsBucket.MONTH
            StatsGranularity.RANGE -> request.bucket ?: StatsRequest.autoBucket(bounds.startAt, bounds.endAtExclusive)
        }
        // 「同期」按今天截断：本周期只过了 14 天，上期也只取前 14 天，环比才有意义。
        val today = now()
        val cut = minOf(endOfDay(today), bounds.endAtExclusive).coerceAtLeast(bounds.startAt)
        val elapsedDays = Period.daysBetween(bounds.startAt, cut)
        val fullDays = Period.daysBetween(bounds.startAt, bounds.endAtExclusive).toLong()
        val previousStart = when (request.granularity) {
            StatsGranularity.MONTH -> Period.shift(bounds.startAt, -1)
            StatsGranularity.YEAR -> Period.shift(bounds.startAt, -12)
            StatsGranularity.RANGE -> Period.shiftDays(bounds.startAt, -fullDays)
        }
        // 完整周期直接取整段上期：自定义起始日下两个月的天数可能差一天，
        // 用「本月已经过几天」去截上期会把 9/14 之后那一天漏掉。
        val previousEnd = if (cut >= bounds.endAtExclusive) {
            bounds.startAt
        } else {
            Period.shiftDays(previousStart, elapsedDays.toLong())
        }
        // 去年同期：本期窗口整体往前推一年，再按同样的方式截断。月/年维度的「整段」恰好就是 bounds.startAt，
        // 区间维度必须显式加回 fullDays——直接拿 bounds.startAt 当右界会取成一整年。
        val yearAgoStart = Period.shift(bounds.startAt, -12)
        val yearAgoEnd = Period.shiftDays(
            yearAgoStart,
            if (cut >= bounds.endAtExclusive) fullDays else elapsedDays.toLong(),
        )
        return combine(
            rangeRows(request.ledgerId, bounds.startAt, bounds.endAtExclusive),
            rangeRows(request.ledgerId, previousStart, maxOf(previousStart, previousEnd)),
            rangeRows(request.ledgerId, yearAgoStart, maxOf(yearAgoStart, yearAgoEnd)),
        ) { currentRows, previousRows, yearAgoRows ->
            val current = currentRows.map(::toItem)
            val previous = previousRows.map(::toItem)
            val yearAgo = yearAgoRows.map(::toItem)
            buildStatistics(
                request = request,
                bounds = bounds,
                bucket = bucket,
                cut = cut,
                current = current,
                previous = previous,
                yearAgo = yearAgo,
                origins = refundOrigins(current),
                previousOrigins = refundOrigins(previous),
            )
        }.flowOn(Dispatchers.Default)
    }

    /**
     * 概览页要的只有合计与一级分类排行：只扫当期一段的瘦行，不跑上期/去年同期、
     * 不建趋势桶、不算星期分布；聚合仍复用统计页那两份函数，口径不会分叉。
     */
    fun observeOverviewStats(ledgerId: String, range: PeriodRange): Flow<OverviewStats> =
        billDao.observeStatsRows(ledgerId, range.startAt, range.endAtExclusive).map { rows ->
            val entries = rows.map { it.toStatsEntry() }
            val origins = refundOriginsOf(entries.mapNotNull { it.refundOfBillId })
            val base = summaryOf(entries)
            val categories = rankTotals(
                aggregateEntries(entries, origins, BillType.EXPENSE, excludePendingReimburse = false),
                emptyMap(),
            ).first
            OverviewStats(
                expenseMinor = base.expenseMinor,
                incomeMinor = base.incomeMinor,
                balanceMinor = base.balanceMinor,
                categories = categories,
            )
        }.flowOn(Dispatchers.Default)

    /**
     * 分类报表下钻弹层的记录。筛选与合计都跟着排行走同一条规则，弹层上的「合计」才不会和
     * 上面那行对不上：退款按**原单**的分类归属（原单可能在区间外，所以要补一次原单查询）、
     * 未关联原单的退款是中性行、开「剔除未报销」时未报销的支出整行不进弹层。
     * 行序沿用区间查询的倒序（occurred_at DESC），与搜索页一致。
     *
     * [l2Id] 为 null 表示只要**直接挂在一级上**的账单（没有二级的那些）：账单没有二级时
     * category_l2_id 就是空，二级筛选拿空串对空串永远为真，必须显式排除带二级的行。
     */
    override fun observeCategoryBills(
        ledgerId: String?,
        range: PeriodRange,
        type: BillType,
        l1Id: String?,
        l2Id: String?,
        excludePendingReimburse: Boolean,
    ): Flow<CategoryBills> = flow {
        val items = rangeRows(ledgerId, range.startAt, range.endAtExclusive).first().map(::toItem)
        val origins = refundOrigins(items)
        val matched = items.filter { item ->
            val bill = item.bill
            if (bill.type != type.name || bill.excludedFromStats) return@filter false
            val refund = isRefundRow(bill)
            if (refund && bill.refundOfBillId == null) return@filter false
            val origin = if (refund) origins[bill.refundOfBillId] else null
            if (refund && origin == null) return@filter false
            val effL1 = if (refund) origin?.l1Id.orEmpty() else bill.categoryL1Id.orEmpty()
            val effL2 = if (refund) origin?.l2Id.orEmpty() else bill.categoryL2Id.orEmpty()
            if (l1Id != null && effL1 != l1Id) return@filter false
            if (l2Id != null) {
                if (effL2 != l2Id) return@filter false
            } else if (effL2.isNotEmpty()) {
                return@filter false
            }
            if (!refund && excludePendingReimburse && type == BillType.EXPENSE &&
                bill.reimbursed == ReimburseStatus.PENDING.name
            ) return@filter false
            true
        }
        var net = 0L
        for (item in matched) {
            val bill = item.bill
            net += if (isRefundRow(bill)) -bill.amountMinor else bill.amountMinor
        }
        emit(CategoryBills(matched, net))
    }.flowOn(Dispatchers.Default)

    /** 日报表/月报表点开一天或一月时的账单明细。 */
    override fun observeBucketBills(ledgerId: String?, range: PeriodRange): Flow<List<BillListItem>> =
        rangeRows(ledgerId, range.startAt, range.endAtExclusive)
            .map { rows -> rows.map(::toItem) }
            .flowOn(Dispatchers.Default)

    /**
     * 预算的已用：与统计页的分类排行同一条聚合（`aggregateByCategory`），
     * 只是不再丢掉 0 与负值的桶、也不排序——预算要的是「每一类净支出是多少」。
     */
    override fun observeNetExpenseByCategory(ledgerId: String?, range: PeriodRange): Flow<Map<String, Long>> =
        rangeRows(ledgerId, range.startAt, range.endAtExclusive).map { rows ->
            val items = rows.map(::toItem)
            netExpenseByCategory(items)
        }.flowOn(Dispatchers.Default)

    private suspend fun netExpenseByCategory(items: List<BillListItem>): Map<String, Long> {
        val origins = refundOrigins(items)
        return aggregateByCategory(items, origins, BillType.EXPENSE, excludePendingReimburse = false)
            .mapValues { it.value.amountMinor }
    }

    private fun statsBounds(request: StatsRequest): PeriodRange = when (request.granularity) {
        StatsGranularity.MONTH -> Period.monthRange(request.periodStart, request.startDay)
        StatsGranularity.YEAR -> Period.yearRange(request.periodStart)
        StatsGranularity.RANGE -> {
            val start = Period.dayRange(request.periodStart, request.periodStart).startAt
            val end = request.rangeEndExclusive?.let { Period.dayRange(it, it).startAt }
                ?: Period.shiftDays(start, 1)
            PeriodRange(start, maxOf(end, Period.shiftDays(start, 1)))
        }
    }

    /**
     * 退款行要归到原单的分类里，而原单可能落在区间外（9 月买的、10 月退的），
     * 列表查询拿不到它的分类名，所以只在真有退款行时补一次原单查询。
     */
    private suspend fun refundOrigins(items: List<BillListItem>): Map<String, RefundOrigin> =
        refundOriginsOf(items.mapNotNull { it.bill.refundOfBillId })

    private suspend fun refundOriginsOf(refundOriginIds: List<String>): Map<String, RefundOrigin> {
        val ids = refundOriginIds.distinct()
        if (ids.isEmpty()) return emptyMap()
        val entities = billDao.findEntitiesByIds(ids).associateBy { it.id }
        val categoryIds = entities.values.flatMap { listOfNotNull(it.categoryL1Id, it.categoryL2Id) }.distinct()
        val categories = categoryIds.mapNotNull { id -> categoryDao.findById(id)?.let { id to it } }.toMap()
        return entities.mapValues { (_, origin) ->
            val l1 = origin.categoryL1Id?.let { categories[it] }
            val l2 = origin.categoryL2Id?.let { categories[it] }
            RefundOrigin(
                l1Id = origin.categoryL1Id,
                name = l1?.name ?: "未分类",
                iconKey = l1?.iconKey,
                l2Id = origin.categoryL2Id,
                l2Name = l2?.name,
                l2IconKey = l2?.iconKey,
            )
        }
    }

    override suspend fun save(draft: BillDraft): String = database.withTransaction { writeBill(draft) }

    /**
     * 退款校验与写入必须在同一个事务里：「已退累计 + 本次」如果分两次读，
     * 快速连点两下就可能各读到旧值，两笔都放行后超过原单。
     */
    private suspend fun writeBill(draft: BillDraft): String {
        if (draft.amountMinor <= 0L || draft.amountMinor > Money.MAX_MINOR) {
            throw TallyError.AmountOutOfRange
        }
        val ledgerId = draft.ledgerId ?: ledgerDao.findDefault()?.id ?: throw TallyError.NoDefaultLedger
        val ledger = ledgerDao.findById(ledgerId) ?: throw TallyError.LedgerNotFound
        if (ledger.isArchived) throw TallyError.LedgerNotFound

        val categoryL1Id = draft.categoryL1Id ?: throw TallyError.CategoryNotFound
        val categoryL1 = categoryDao.findById(categoryL1Id) ?: throw TallyError.CategoryNotFound
        validateCategory(categoryL1, draft.type, parentId = null)
        val categoryL2 = draft.categoryL2Id?.let { id ->
            val category = categoryDao.findById(id) ?: throw TallyError.CategoryNotFound
            validateCategory(category, draft.type, parentId = categoryL1Id)
            category
        }

        val timestamp = now()
        val existing = draft.id?.let { billDao.findEntityById(it) ?: throw TallyError.BillNotFound }
        draft.refundOfBillId?.let { originId ->
            val origin = billDao.findEntityById(originId) ?: throw TallyError.RefundOriginInvalid
            if (origin.type != BillType.EXPENSE.name || origin.isRefund || origin.refundOfBillId != null) {
                throw TallyError.RefundOriginInvalid
            }
            if (draft.type != BillType.EXPENSE) throw TallyError.RefundNotAllowed
            val remaining = origin.amountMinor - billDao.refundedTotal(originId, excludeId = existing?.id.orEmpty())
            if (draft.amountMinor > remaining) throw TallyError.RefundExceedsRemaining(remaining)
        }
        // 反过来也要守：给一笔已经被退过的账单改金额，不能改到比已退合计还低。
        if (existing != null && draft.refundOfBillId == null) {
            val refunded = billDao.refundedTotal(existing.id, excludeId = "")
            if (refunded > 0L && draft.amountMinor < refunded) throw TallyError.AmountBelowRefunded(refunded)
        }
        val isRefund = draft.isRefund || draft.refundOfBillId != null
        // 落库的 occurred_at 与声明的精度必须是一把尺：日粒度统一当天零点。速记没提到时刻时
        // 给的是墙上时间，同一天里它（12:30）会排在记账页那条（零点）上面，看着像后记的反而沉底。
        val occurredAt = if (draft.timePrecision == TimePrecision.DATE) startOfDay(draft.occurredAt) else draft.occurredAt
        val entity = if (existing != null) {
            // 编辑分支的字段归属交给纯策略裁决（见 BillEditPolicy）：
            // source 等「既成事实」一律沿用原值，编辑页构造的草稿不带这些字段。
            BillEditPolicy.merge(
                existing = existing,
                draft = draft,
                context = BillEditContext(
                    ledgerId = ledgerId,
                    categoryL1Id = categoryL1.id,
                    categoryL2Id = categoryL2?.id,
                    occurredAt = occurredAt,
                    timestamp = timestamp,
                    isRefund = isRefund,
                ),
            )
        } else {
            BillEntity(
                id = newId(),
                ledgerId = ledgerId,
                categoryL1Id = categoryL1.id,
                categoryL2Id = categoryL2?.id,
                type = draft.type.name,
                amountMinor = draft.amountMinor,
                currency = "CNY",
                occurredAt = occurredAt,
                timePrecision = draft.timePrecision.name,
                note = draft.note?.trim()?.ifEmpty { null },
                source = draft.source.name,
                externalId = draft.externalId,
                fingerprint = draft.fingerprint,
                reimbursed = draft.reimbursed.name,
                isRefund = isRefund,
                refundOfBillId = draft.refundOfBillId,
                excludedFromStats = draft.excludedFromStats,
                createdAt = timestamp,
                updatedAt = timestamp,
                deviceId = preferences.deviceId(),
            )
        }
        billDao.upsert(entity)
        // 只有明确带着 tagIds 的草稿才改关联：退款、AI、导入这些入口不传，
        // 保存时不能顺手把用户挂好的标签清掉。空列表是「明确清空」。
        draft.tagIds?.let { ids ->
            tagDao.clearForBill(entity.id)
            val distinct = ids.distinct()
            if (distinct.isNotEmpty()) {
                tagDao.attach(distinct.map { BillTagEntity(billId = entity.id, tagId = it) })
            }
        }
        return entity.id
    }

    /** 原单详情里的「已退」明细。 */
    suspend fun refundsOf(originId: String): List<RefundEntry> = billDao.refundsOf(originId)

    override suspend fun find(id: String): BillListItem? =
        billDao.findRowById(id)?.let { row -> withTags(listOf(toItem(row))).firstOrNull() }

    /** 截图记账的去重提示：同一订单号已存在时要说出来，不能静默跳过。 */
    suspend fun findByExternalId(externalId: String): BillListItem? =
        billDao.findByExternalId(externalId)?.id?.let { find(it) }

    /**
     * 周期/分期生成的账单入口。幂等键是 (rule|plan, period_index)：
     * 已存在同期的**任何**账单时直接返回 null，补执行重复触发也不会多记一笔。
     *
     * 判重用的是「含回收站」的查询（`findXxxPeriodAny`），不是只查有效行的那个：
     * 这一期在回收站里还留着一行时，只看有效行会以为没生成过，于是插一条新行——
     * 而 (rule|plan, period_index) 上是全量唯一索引（不带 `deleted_at`），
     * `@Insert(REPLACE)` 会先把回收站那一行**物理删掉**再插新的，那笔就永久没了。
     * 用户删掉某一期就是「这一期不要了」，补执行不该把它顶掉、也不该再补一遍。
     */
    suspend fun insertGenerated(spec: GeneratedBillSpec): String? {
        val duplicate = when {
            spec.recurrenceRuleId != null -> billDao.findRecurringPeriodAny(spec.recurrenceRuleId, spec.periodIndex)
            spec.installmentPlanId != null -> billDao.findInstallmentPeriodAny(spec.installmentPlanId, spec.periodIndex)
            else -> null
        }
        if (duplicate != null) return null

        val timestamp = now()
        val entity = BillEntity(
            id = newId(),
            ledgerId = spec.ledgerId,
            categoryL1Id = spec.categoryL1Id,
            categoryL2Id = spec.categoryL2Id,
            type = spec.type.name,
            amountMinor = spec.amountMinor,
            currency = "CNY",
            occurredAt = spec.occurredAt,
            timePrecision = TimePrecision.DATE.name,
            note = spec.note?.trim()?.ifEmpty { null },
            source = spec.source.name,
            recurrenceRuleId = spec.recurrenceRuleId,
            installmentPlanId = spec.installmentPlanId,
            periodIndex = spec.periodIndex,
            createdAt = timestamp,
            updatedAt = timestamp,
            deviceId = preferences.deviceId(),
        )
        billDao.upsert(entity)
        return entity.id
    }

    /**
     * 某个周期任务生成过的每一期：明细弹层用，含回收站里的行。
     *
     * 这里只做形状转换；缺号补灰行由界面按期号对齐，
     * 因为要不要补、补到第几期取决于规则的生成水位，不在账单表里。
     */
    suspend fun recurringPeriods(ruleId: String): List<RecurringPeriodRow> =
        billDao.findByRecurringRule(ruleId).map { row ->
            RecurringPeriodRow(
                billId = row.billId,
                index = row.periodIndex ?: 0,
                occurredAt = row.occurredAt,
                amountMinor = row.amountMinor,
                isIncome = row.type == BillType.INCOME.name,
                note = row.note?.takeIf { it.isNotBlank() },
                deleted = row.deletedAt != null,
            )
        }

    override suspend fun delete(id: String) {
        billDao.findEntityById(id) ?: throw TallyError.BillNotFound
        val timestamp = now()
        billDao.softDelete(id, timestamp)
        // 原单走了，退款不能还指着一个看不见的原单：退回未关联，不再冲抵分类。
        billDao.detachRefunds(listOf(id), timestamp)
        // 附件行跟着账单一起软删，回收任务才看得到它们。
        attachmentDao.softDeleteByBills(listOf(id), timestamp)
    }

    suspend fun softDeleteIds(ids: List<String>): Int {
        if (ids.isEmpty()) return 0
        val timestamp = now()
        attachmentDao.softDeleteByBills(ids, timestamp)
        billDao.detachRefunds(ids, timestamp)
        return billDao.softDeleteIds(ids, timestamp)
    }

    suspend fun setCategoryIds(ids: List<String>, l1Id: String?, l2Id: String?): Int =
        billDao.setCategoryByIds(ids, l1Id, l2Id, now())

    suspend fun setReimbursedIds(ids: List<String>, status: ReimburseStatus): Int =
        billDao.setReimbursedByIds(ids, status.name, now())

    suspend fun setExcludedIds(ids: List<String>, excluded: Boolean): Int =
        billDao.setExcludedByIds(ids, excluded, now())

    /** 批量改备注：空白即清空，trim 口径与记账页一致。 */
    suspend fun setNoteIds(ids: List<String>, note: String?): Int =
        billDao.setNoteByIds(ids, note?.trim()?.ifEmpty { null }, now())

    /** 批量跨账本迁移。分类全机共用一套字典，不随账本走，所以这里只换 ledger_id。 */
    suspend fun moveToLedgerIds(ids: List<String>, targetId: String): Int =
        billDao.setLedgerByIds(ids, targetId, now())

    /**
     * 批量改日期：只换「哪一天」，每一笔自己的几点几分跟着走。
     * 整批写成同一时刻会把带时刻的账抹成零点，详情弹层上的时间就假了。
     */
    suspend fun setDateIds(ids: List<String>, dayStartAt: Long): Int {
        if (ids.isEmpty()) return 0
        val zone = ZoneId.systemDefault()
        val day = Instant.ofEpochMilli(dayStartAt).atZone(zone).toLocalDate()
        val timestamp = now()
        return billDao.updateAll(
            billDao.findEntitiesByIds(ids).map { bill ->
                val time = Instant.ofEpochMilli(bill.occurredAt).atZone(zone).toLocalTime()
                bill.copy(
                    occurredAt = time.atDate(day).atZone(zone).toInstant().toEpochMilli(),
                    updatedAt = timestamp,
                    version = bill.version + 1,
                )
            },
        )
    }

    /** 默认分类：支出固定落在三餐，三餐不可用时才回落到记忆值/全机笔数最多的一级（F1.3）。 */
    suspend fun preferredCategory(type: BillType): Pair<String?, String?> {
        val categoryType = if (type == BillType.INCOME) CategoryType.INCOME else CategoryType.EXPENSE
        val active = categoryDao.findActive(categoryType.name)
        if (type == BillType.EXPENSE) {
            val food = DefaultCategoryTree.seedId(CategoryType.EXPENSE, "food")
            if (active.any { it.id == food }) return food to null
        }
        val remembered = preferences.lastCategory()
        val l1 = remembered.first?.takeIf { id -> active.any { it.id == id && it.parentId == null } }
            ?: billDao.topCategoryL1(type.name)?.takeIf { id -> active.any { it.id == id } }
        val l2 = l1?.let { parent -> remembered.second?.takeIf { id -> active.any { it.id == id && it.parentId == parent } } }
        return l1 to l2
    }

    suspend fun rememberCategory(l1Id: String?, l2Id: String?) =
        preferences.rememberLastCategory(l1Id, l2Id)

    override suspend fun activeCategories(type: CategoryType): List<CategoryEntity> =
        categoryDao.findActive(type.name)

    override suspend fun defaultLedgerId(): String? = ledgerDao.findDefault()?.id

    private suspend fun validateCategory(
        category: CategoryEntity,
        type: BillType,
        parentId: String?,
    ) {
        if (category.type != type.name) throw TallyError.CategoryTypeMismatch
        if (category.parentId != parentId) throw TallyError.CategoryTargetInvalid
        if (category.isArchived) throw TallyError.CategoryNotFound
        // 自身没归档但父级归档了，这笔会落进记账页选不到的地方（继承语义）。
        val parent = category.parentId?.let { categoryDao.findById(it) }
        if (parent?.isArchived == true) throw TallyError.CategoryNotFound
    }

    private fun toItem(row: BillListItemRow): BillListItem = row.toListItem()

    /**
     * 给一批列表行补标签名。标签名不并进主查询：`GROUP_CONCAT` 在旧版 SQLite 上
     * 不支持 `ORDER BY`，而且 Room 不会因为 `bill_tag` 变化就重新发主查询的 Flow，
     * 所以由 `observeLinkTick()` 触发这第二条查询。
     *
     * 按 500 一批切：搜索一次最多取 1000 行，API 28 上 SQLite 的绑定上限是 999。
     */
    private suspend fun withTags(items: List<BillListItem>): List<BillListItem> {
        if (items.isEmpty()) return items
        val names = LinkedHashMap<String, MutableList<String>>()
        items.map { it.bill.id }.chunked(TAG_QUERY_CHUNK).forEach { chunk ->
            tagDao.namesForBills(chunk).forEach { row ->
                names.getOrPut(row.billId) { mutableListOf() } += row.name
            }
        }
        if (names.isEmpty()) return items
        return items.map { item -> names[item.bill.id]?.let { item.copy(tags = it) } ?: item }
    }

    /** 退款行判定：新数据靠原单指针，旧导入数据只写过 is_refund。 */
    private fun isRefundRow(bill: BillEntity): Boolean = bill.isRefund || bill.refundOfBillId != null

    private fun summary(items: List<BillListItem>): BillSummary = summaryOf(items.map { it.toStatsEntry() })

    private fun summaryOf(entries: List<StatsEntry>): BillSummary {
        var expense = 0L
        var income = 0L
        var excluded = 0L
        var excludedCount = 0
        var pendingReimburse = 0L
        var pendingReimburseCount = 0
        var reimbursed = 0L
        var reimbursedCount = 0
        for (entry in entries) {
            // 退款既不进收入，也不占「不计入」：它只在自己的周期里冲抵原单分类的支出。
            if (entry.refundRow) {
                if (!entry.excludedFromStats && entry.refundOfBillId != null) expense -= entry.amountMinor
                continue
            }
            if (entry.excludedFromStats) {
                excluded += entry.amountMinor
                excludedCount++
            } else when (entry.type) {
                BillType.EXPENSE.name -> {
                    // 已报销的钱会回来，不算这一期的支出；待报销照常计入。
                    if (entry.reimbursed == ReimburseStatus.DONE.name) {
                        reimbursed += entry.amountMinor
                        reimbursedCount++
                    } else {
                        expense += entry.amountMinor
                        if (entry.reimbursed == ReimburseStatus.PENDING.name) {
                            pendingReimburse += entry.amountMinor
                            pendingReimburseCount++
                        }
                    }
                }
                BillType.INCOME.name -> income += entry.amountMinor
            }
        }
        return BillSummary(
            entries.size, expense, income, excluded, excludedCount,
            pendingReimburse, pendingReimburseCount, reimbursed, reimbursedCount,
        )
    }

    private fun groupByDay(items: List<BillListItem>): List<BillDayGroup> =
        items.groupBy { startOfDay(it.bill.occurredAt) }
            .toSortedMap(compareByDescending { it })
            .map { (day, dayItems) ->
                var expense = 0L
                var income = 0L
                for (item in dayItems) {
                    val bill = item.bill
                    if (bill.excludedFromStats) continue
                    if (isRefundRow(bill)) {
                        if (bill.refundOfBillId != null) expense -= bill.amountMinor
                        continue
                    }
                    when (bill.type) {
                        BillType.EXPENSE.name -> expense += bill.amountMinor
                        BillType.INCOME.name -> income += bill.amountMinor
                    }
                }
                BillDayGroup(day, dayItems, expense, income)
            }

    private fun buildStatistics(
        request: StatsRequest,
        bounds: PeriodRange,
        bucket: StatsBucket,
        cut: Long,
        current: List<BillListItem>,
        previous: List<BillListItem>,
        yearAgo: List<BillListItem>,
        origins: Map<String, RefundOrigin>,
        previousOrigins: Map<String, RefundOrigin>,
    ): StatisticsSnapshot {
        val granularity = request.granularity
        val excludePendingReimburse = request.excludePendingReimburse
        val today = now()
        val currentBucket = bucketStartOf(today, bounds, bucket)
        val byBucket = current.groupBy { bucketStartOf(it.bill.occurredAt, bounds, bucket) }
        val buckets = bucketSeries(bounds, bucket).map { start ->
            val items = byBucket[start].orEmpty()
            val totals = billTotals(items, excludePendingReimburse)
            DailyTotal(
                dayStart = start,
                expenseMinor = totals.first,
                incomeMinor = totals.second,
                excludedMinor = totals.third,
                billCount = items.size,
                isFuture = start > currentBucket,
            )
        }
        val base = summary(current)
        val currentSummary = base.copy(
            expenseMinor = buckets.sumOf { it.expenseMinor },
            incomeMinor = buckets.sumOf { it.incomeMinor },
        )
        val (previousExpense, previousIncome, _) = billTotals(previous, excludePendingReimburse)
        val (yearAgoExpense, yearAgoIncome, _) = billTotals(yearAgo, excludePendingReimburse)
        val expenseRanking = ranking(
            current, origins, BillType.EXPENSE,
            previous, previousOrigins, excludePendingReimburse,
        )
        // 上期自己的排行：环比那条结论要说清「哪个分类带动的」，光有本期排行说不出来。
        // 把上期当 current、空列表当 previous，changePercent 自然为 null，不参与任何比较。
        val previousExpenseRanking = ranking(
            previous, previousOrigins, BillType.EXPENSE,
            emptyList(), emptyMap(), excludePendingReimburse,
        ).first
        val incomeRanking = ranking(
            current, emptyMap(), BillType.INCOME,
            previous, emptyMap(), excludePendingReimburse,
        )

        val elapsed = buckets.filterNot { it.isFuture }
        val elapsedCount = maxOf(1, elapsed.size)
        val highest = elapsed.maxByOrNull { it.expenseMinor }?.takeIf { it.expenseMinor > 0L }
        val zeroDays = elapsed.filter { it.expenseMinor == 0L }
        val weekday = weekdayDistribution(current, bounds, cut)
        return StatisticsSnapshot(
            monthStart = bounds.startAt,
            summary = currentSummary,
            categoryRanking = expenseRanking.first,
            dailyTrend = buckets,
            previousExpenseMinor = previousExpense,
            expenseChangePercent = changePercent(currentSummary.expenseMinor, previousExpense),
            yearAgoExpenseMinor = yearAgoExpense,
            yearAgoIncomeMinor = yearAgoIncome,
            yearAgoChangePercent = changePercent(currentSummary.expenseMinor, yearAgoExpense),
            previousCategoryRanking = previousExpenseRanking,
            granularity = granularity,
            incomeRanking = incomeRanking.first,
            previousIncomeMinor = previousIncome,
            incomeChangePercent = changePercent(currentSummary.incomeMinor, previousIncome),
            expenseCategoryCount = expenseRanking.second,
            incomeCategoryCount = incomeRanking.second,
            averageExpenseMinor = currentSummary.expenseMinor / elapsedCount,
            highestExpenseDayStart = highest?.dayStart,
            zeroExpenseDayCount = zeroDays.size,
            zeroExpenseFirstDayStart = zeroDays.firstOrNull()?.dayStart,
            rangeEndExclusive = if (granularity == StatsGranularity.RANGE) bounds.endAtExclusive else null,
            bucket = if (granularity == StatsGranularity.RANGE) bucket else null,
            weekdayExpenseMinor = weekday.expenseTotals,
            weekdayAverageMinor = weekday.expenseAverage,
            weekdayPeak = weekday.expensePeak,
            weekdayIncomeMinor = weekday.incomeTotals,
            weekdayAverageIncomeMinor = weekday.incomeAverage,
            weekdayIncomePeak = weekday.incomePeak,
        )
    }

    /**
     * 星期分布按「自然日」聚合，不跟着趋势的桶走：年维度按月落桶时，
     * 桶上没有星期几，只有账单本身有。日均的分母是周期内该星期几出现过几天
     * （当前周期只算到今天），否则周末两天会被工作日的五天摊薄。支出与收入各出一份。
     */
    private fun weekdayDistribution(
        items: List<BillListItem>,
        bounds: PeriodRange,
        cut: Long,
    ): WeekdayDistribution {
        val zone = zoneId()
        val expensePerDay = HashMap<java.time.LocalDate, Long>()
        val incomePerDay = HashMap<java.time.LocalDate, Long>()
        for (item in items) {
            val bill = item.bill
            val date = Instant.ofEpochMilli(bill.occurredAt).atZone(zone).toLocalDate()
            if (isRefundRow(bill)) {
                // 退款只冲支出：收入口径下它什么都不是。
                if (!bill.excludedFromStats && bill.refundOfBillId != null) {
                    expensePerDay[date] = (expensePerDay[date] ?: 0L) - bill.amountMinor
                }
                continue
            }
            if (bill.excludedFromStats) continue
            if (bill.type == BillType.EXPENSE.name && bill.reimbursed == ReimburseStatus.DONE.name) continue
            when (bill.type) {
                BillType.EXPENSE.name -> expensePerDay[date] = (expensePerDay[date] ?: 0L) + bill.amountMinor
                BillType.INCOME.name -> incomePerDay[date] = (incomePerDay[date] ?: 0L) + bill.amountMinor
            }
        }
        val expenseTotals = LongArray(7)
        for ((date, amount) in expensePerDay) expenseTotals[date.dayOfWeek.value - 1] += amount
        val incomeTotals = LongArray(7)
        for ((date, amount) in incomePerDay) incomeTotals[date.dayOfWeek.value - 1] += amount

        val dayCounts = IntArray(7)
        var cursor = Period.toDate(bounds.startAt, zone)
        val last = Period.toDate(minOf(bounds.endAtExclusive, cut), zone)
        while (cursor < last) {
            dayCounts[cursor.dayOfWeek.value - 1]++
            cursor = cursor.plusDays(1)
        }
        fun average(totals: LongArray): List<Long> = (0..6).map { index ->
            if (dayCounts[index] == 0) 0L else maxOf(0L, totals[index]) / dayCounts[index]
        }
        fun peak(average: List<Long>, totals: LongArray): Int =
            average.indices.maxByOrNull { average[it] }?.takeIf { totals[it] > 0L } ?: -1
        val expenseAverage = average(expenseTotals)
        val incomeAverage = average(incomeTotals)
        return WeekdayDistribution(
            expenseTotals = expenseTotals.toList(),
            expenseAverage = expenseAverage,
            expensePeak = peak(expenseAverage, expenseTotals),
            incomeTotals = incomeTotals.toList(),
            incomeAverage = incomeAverage,
            incomePeak = peak(incomeAverage, incomeTotals),
        )
    }

    /** 环比/同比：上期为 0 时没有基数，返回 null 让界面写「上期无支出」。 */
    private fun changePercent(currentMinor: Long, previousMinor: Long): Float? =
        if (previousMinor <= 0L) null
        else ((currentMinor - previousMinor).toFloat() / previousMinor) * 100f

    /** 口径表：不计入收支只进剔除列；「剔除未报销」开关只影响支出视图。退款冲抵支出。 */
    private fun billTotals(items: List<BillListItem>, excludePendingReimburse: Boolean): Triple<Long, Long, Long> {
        var expense = 0L
        var income = 0L
        var excluded = 0L
        for (item in items) {
            val bill = item.bill
            if (isRefundRow(bill)) {
                if (!bill.excludedFromStats && bill.refundOfBillId != null) expense -= bill.amountMinor
                continue
            }
            when {
                bill.excludedFromStats -> excluded += bill.amountMinor
                bill.type == BillType.EXPENSE.name -> if (bill.reimbursed != ReimburseStatus.DONE.name &&
                    (!excludePendingReimburse || bill.reimbursed != ReimburseStatus.PENDING.name)
                ) {
                    expense += bill.amountMinor
                }
                else -> income += bill.amountMinor
            }
        }
        return Triple(expense, income, excluded)
    }

    /**
     * 一级分类排行；退款按原单分类冲抵，冲成非正数的分类不进排行。
     * 占比分母只用正数净值之和，没有退款时与旧口径完全一致。一级全部平铺，不并「其他」。
     */
    private fun ranking(
        current: List<BillListItem>,
        origins: Map<String, RefundOrigin>,
        type: BillType,
        previous: List<BillListItem>,
        previousOrigins: Map<String, RefundOrigin>,
        excludePendingReimburse: Boolean,
    ): Pair<List<CategoryTotal>, Int> = rankTotals(
        aggregateEntries(current.map { it.toStatsEntry() }, origins, type, excludePendingReimburse),
        aggregateEntries(previous.map { it.toStatsEntry() }, previousOrigins, type, excludePendingReimburse),
    )

    /** 排行只差「本期一组、上期一组」：概览没有上期，传空表即可，环比自然为 null。 */
    private fun rankTotals(
        currentGroups: Map<String, CategoryAggregate>,
        previousGroups: Map<String, CategoryAggregate>,
    ): Pair<List<CategoryTotal>, Int> {
        val positiveTotal = currentGroups.values.sumOf { maxOf(0L, it.amountMinor) }

        val totals = currentGroups
            .map { (key, group) ->
                CategoryTotal(
                    categoryId = key.ifEmpty { null },
                    name = group.name,
                    amountMinor = group.amountMinor,
                    share = if (positiveTotal <= 0L) 0f else group.amountMinor.toFloat() / positiveTotal,
                    iconKey = group.iconKey,
                    billCount = group.billCount,
                    changePercent = changePercent(group.amountMinor, previousGroups[key]?.amountMinor ?: 0L),
                    children = childrenOf(group, previousGroups[key]),
                )
            }
            .filter { it.amountMinor > 0L }
            .sortedByDescending { it.amountMinor }
        return totals to totals.size
    }

    private fun aggregateByCategory(
        items: List<BillListItem>,
        origins: Map<String, RefundOrigin>,
        type: BillType,
        excludePendingReimburse: Boolean,
    ): Map<String, CategoryAggregate> =
        aggregateEntries(items.map { it.toStatsEntry() }, origins, type, excludePendingReimburse)

    /**
     * 分类聚合：普通账单落在自己的分类上，退款落在原单的分类上。
     * 原单不在本区间也能落到正确分类，靠调用方补来的 origins。
     */
    private fun aggregateEntries(
        entries: List<StatsEntry>,
        origins: Map<String, RefundOrigin>,
        type: BillType,
        excludePendingReimburse: Boolean,
    ): Map<String, CategoryAggregate> {
        val groups = LinkedHashMap<String, CategoryAggregate>()
        for (entry in entries) {
            if (entry.type != type.name || entry.excludedFromStats) continue
            val refund = entry.refundRow
            // 未关联原单的退款是中性行：不知道冲哪一类，就不进排行也不占分母。
            if (refund && entry.refundOfBillId == null) continue
            if (!refund && type == BillType.EXPENSE && entry.reimbursed == ReimburseStatus.DONE.name) continue
            if (!refund && excludePendingReimburse && type == BillType.EXPENSE &&
                entry.reimbursed == ReimburseStatus.PENDING.name
            ) continue
            val origin = if (refund) origins[entry.refundOfBillId] else null
            if (refund && origin == null) continue
            val key = if (refund) origin?.l1Id.orEmpty() else entry.categoryL1Id.orEmpty()
            val group = groups.getOrPut(key) {
                CategoryAggregate(
                    name = if (refund) origin?.name ?: "未分类" else entry.categoryL1Name ?: "未分类",
                    iconKey = if (refund) origin?.iconKey else entry.categoryL1Icon,
                )
            }
            val amount = if (refund) -entry.amountMinor else entry.amountMinor
            group.amountMinor += amount
            group.billCount++
            val childKey = if (refund) origin?.l2Id.orEmpty() else entry.categoryL2Id.orEmpty()
            if (childKey.isNotEmpty()) {
                val child = group.children.getOrPut(childKey) {
                    CategoryAggregate(
                        name = (if (refund) origin?.l2Name else entry.categoryL2Name) ?: "未分类",
                        iconKey = if (refund) origin?.l2IconKey else entry.categoryL2Icon,
                    )
                }
                child.amountMinor += amount
                child.billCount++
            }
        }
        return groups
    }

    /** 二级小计：占比按本一级算，条形图才不会一根比一根短。 */
    private fun childrenOf(
        parent: CategoryAggregate,
        previousParent: CategoryAggregate?,
    ): List<CategoryTotal> {
        if (parent.children.isEmpty()) return emptyList()
        val parentTotal = parent.amountMinor
        return parent.children
            .map { (key, child) ->
                CategoryTotal(
                    categoryId = key.ifEmpty { null },
                    name = child.name,
                    amountMinor = child.amountMinor,
                    share = if (parentTotal <= 0L) 0f
                    else (child.amountMinor.toFloat() / parentTotal).coerceIn(0f, 1f),
                    iconKey = child.iconKey,
                    billCount = child.billCount,
                    changePercent = changePercent(
                        child.amountMinor,
                        previousParent?.children?.get(key)?.amountMinor ?: 0L,
                    ),
                )
            }
            .filter { it.amountMinor > 0L }
            .sortedByDescending { it.amountMinor }
    }

    /** 补齐空档：没有账单的日子/周/月也要占位，缺勤是数据不是留白。 */
    private fun bucketSeries(bounds: PeriodRange, bucket: StatsBucket): List<Long> {
        val zone = zoneId()
        val start = Period.toDate(bounds.startAt, zone)
        val end = Period.toDate(bounds.endAtExclusive, zone)
        return when (bucket) {
            StatsBucket.DAY -> {
                val count = ChronoUnit.DAYS.between(start, end).toInt()
                (0 until count).map { Period.shiftDays(bounds.startAt, it.toLong()) }
            }
            StatsBucket.WEEK -> {
                val count = (ChronoUnit.DAYS.between(start, end).toInt() + 6) / 7
                (0 until count).map { Period.shiftDays(bounds.startAt, it * 7L) }
            }
            StatsBucket.MONTH -> buildList {
                var cursor = start.withDayOfMonth(1)
                while (cursor < end) {
                    add(cursor.atStartOfDay(zone).toInstant().toEpochMilli())
                    cursor = cursor.plusMonths(1)
                }
            }
        }
    }

    /** 区间里的「周」从区间首日起算 7 天一段：发薪周期本来就不一定对齐周一。 */
    private fun bucketStartOf(
        epochMillis: Long,
        bounds: PeriodRange,
        bucket: StatsBucket,
    ): Long = when (bucket) {
        StatsBucket.DAY -> startOfDay(epochMillis)
        StatsBucket.WEEK -> {
            val offset = Period.daysBetween(bounds.startAt, epochMillis).coerceAtLeast(0)
            Period.shiftDays(bounds.startAt, (offset / 7) * 7L)
        }
        StatsBucket.MONTH -> {
            val zone = zoneId()
            Period.toDate(epochMillis, zone).withDayOfMonth(1)
                .atStartOfDay(zone).toInstant().toEpochMilli()
        }
    }

    /** 次日零点，用作左闭右开的日终边界。 */
    private fun endOfDay(epochMillis: Long): Long = Period.shiftDays(epochMillis, 1)

    private fun startOfDay(epochMillis: Long): Long {
        val zone = zoneId()
        return Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate()
            .atStartOfDay(zone).toInstant().toEpochMilli()
    }
}

/**
 * 聚合只认这些字段：行可以来自列表查询，也可以来自概览的瘦查询，
 * 进到这里之后合计与排行的算法只有一份，不会出现两套口径。
 */
private data class StatsEntry(
    val type: String,
    val amountMinor: Long,
    val categoryL1Id: String?,
    val categoryL2Id: String?,
    val categoryL1Name: String?,
    val categoryL2Name: String?,
    val categoryL1Icon: String?,
    val categoryL2Icon: String?,
    val isRefund: Boolean,
    val refundOfBillId: String?,
    val reimbursed: String,
    val excludedFromStats: Boolean,
) {
    val refundRow: Boolean get() = isRefund || refundOfBillId != null
}

private fun BillListItem.toStatsEntry(): StatsEntry = StatsEntry(
    type = bill.type,
    amountMinor = bill.amountMinor,
    categoryL1Id = bill.categoryL1Id,
    categoryL2Id = bill.categoryL2Id,
    categoryL1Name = categoryL1Name,
    categoryL2Name = categoryL2Name,
    categoryL1Icon = categoryL1Icon,
    categoryL2Icon = categoryL2Icon,
    isRefund = bill.isRefund,
    refundOfBillId = bill.refundOfBillId,
    reimbursed = bill.reimbursed,
    excludedFromStats = bill.excludedFromStats,
)

private fun StatsBillRow.toStatsEntry(): StatsEntry = StatsEntry(
    type = type,
    amountMinor = amountMinor,
    categoryL1Id = categoryL1Id,
    categoryL2Id = categoryL2Id,
    categoryL1Name = categoryL1Name,
    categoryL2Name = categoryL2Name,
    categoryL1Icon = categoryL1Icon,
    categoryL2Icon = categoryL2Icon,
    isRefund = isRefund,
    refundOfBillId = refundOfBillId,
    reimbursed = reimbursed,
    excludedFromStats = excludedFromStats,
)

/** 退款原单的分类落点：原单可能不在当前区间，名字和图标要提前取好。 */
private data class RefundOrigin(
    val l1Id: String?,
    val name: String,
    val iconKey: String?,
    val l2Id: String?,
    val l2Name: String?,
    val l2IconKey: String?,
)

private class CategoryAggregate(
    val name: String,
    val iconKey: String?,
    var amountMinor: Long = 0L,
    var billCount: Int = 0,
    val children: MutableMap<String, CategoryAggregate> = LinkedHashMap(),
)

/** 星期一到星期日的支出净额、日均与最高的一天；索引 0 是周一。 */
private data class WeekdayDistribution(
    val expenseTotals: List<Long>,
    val expenseAverage: List<Long>,
    val expensePeak: Int,
    val incomeTotals: List<Long>,
    val incomeAverage: List<Long>,
    val incomePeak: Int,
)
