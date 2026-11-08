package io.github.guohangbk.tally.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import io.github.guohangbk.tally.data.db.entity.BillEntity
import io.github.guohangbk.tally.data.db.query.BillListItemRow
import io.github.guohangbk.tally.data.db.query.LedgerSpan
import io.github.guohangbk.tally.data.db.query.LedgerStat
import io.github.guohangbk.tally.data.db.query.PlanPeriodRow
import io.github.guohangbk.tally.data.db.query.RecycleSummary
import io.github.guohangbk.tally.data.db.query.RecurringPeriodRowTuple
import io.github.guohangbk.tally.data.db.query.StatsBillRow
import io.github.guohangbk.tally.domain.model.BillSummary
import io.github.guohangbk.tally.domain.model.RefundEntry
import kotlinx.coroutines.flow.Flow

/**
 * 搜索的筛选条件。列表与汇总两条查询共用这一份，免得出现"列表按一套条件截断、
 * 汇总按另一套条件统计"这种对不上的账。
 */
private const val SEARCH_WHERE = """
        WHERE b.deleted_at IS NULL
          AND (:startAt IS NULL OR b.occurred_at >= :startAt)
          AND (:endAtExclusive IS NULL OR b.occurred_at < :endAtExclusive)
          AND (:type IS NULL OR b.type = :type)
          AND (:ledgerId IS NULL OR b.ledger_id = :ledgerId)
          AND (:categoryL1Id IS NULL OR b.category_l1_id = :categoryL1Id)
          AND (:categoryL2Id IS NULL OR b.category_l2_id = :categoryL2Id)
          AND (:source IS NULL OR b.source = :source)
          AND (:amountMin IS NULL OR b.amount_minor >= :amountMin)
          AND (:amountMax IS NULL OR b.amount_minor <= :amountMax)
          AND (:keyword = '' OR b.note LIKE '%' || :keyword || '%'
               OR c1.name LIKE '%' || :keyword || '%'
               OR c2.name LIKE '%' || :keyword || '%')
        """

/**
 * 搜索列表的公共前缀：列、连接、条件都只有一份，四条查询只差 ORDER BY。
 * 排序必须在 SQL 里做——列表只取回最近一批，回到界面上排等于只在这批里排，
 * 「金额最高的那笔」会排错。
 */
private const val SEARCH_ROWS = """
        SELECT b.*, l.name AS ledger_name, c1.name AS category_l1_name, c2.name AS category_l2_name,
               c1.icon_key AS category_l1_icon, c2.icon_key AS category_l2_icon,
               ip.period_count AS installment_period_count,
               (SELECT COUNT(*) FROM attachment a WHERE a.bill_id = b.id AND a.deleted_at IS NULL) AS attachment_count,
               (SELECT IFNULL(SUM(r.amount_minor), 0) FROM bill r
                 WHERE r.refund_of_bill_id = b.id AND r.deleted_at IS NULL) AS refunded_minor
        FROM bill b
        INNER JOIN ledger l ON l.id = b.ledger_id
        LEFT JOIN category c1 ON c1.id = b.category_l1_id
        LEFT JOIN category c2 ON c2.id = b.category_l2_id
        LEFT JOIN installment_plan ip ON ip.id = b.installment_plan_id
        $SEARCH_WHERE
        """

/** 概览页要的整行账单：列与连接跟搜索、区间查询一致，行组件才能直接复用。 */
private const val LEDGER_BILL_ROWS = """
        SELECT b.*, l.name AS ledger_name, c1.name AS category_l1_name, c2.name AS category_l2_name,
               c1.icon_key AS category_l1_icon, c2.icon_key AS category_l2_icon,
               ip.period_count AS installment_period_count,
               (SELECT COUNT(*) FROM attachment a WHERE a.bill_id = b.id AND a.deleted_at IS NULL) AS attachment_count,
               (SELECT IFNULL(SUM(r.amount_minor), 0) FROM bill r
                 WHERE r.refund_of_bill_id = b.id AND r.deleted_at IS NULL) AS refunded_minor
        FROM bill b
        INNER JOIN ledger l ON l.id = b.ledger_id
        LEFT JOIN category c1 ON c1.id = b.category_l1_id
        LEFT JOIN category c2 ON c2.id = b.category_l2_id
        LEFT JOIN installment_plan ip ON ip.id = b.installment_plan_id
        """

@Dao
interface BillDao {
    /** 统计口径集中在查询层：软删除与「不计入收支」在这里一次性排除。 */
    @Query(
        """
        SELECT ledger_id AS ledgerId,
               COUNT(*) AS billCount,
               MAX(occurred_at) AS lastOccurredAt,
               IFNULL(SUM(CASE
                   WHEN type = 'EXPENSE' AND excluded_from_stats = 0 AND is_refund = 0 AND reimbursed != 'DONE' THEN amount_minor
                   WHEN type = 'EXPENSE' AND excluded_from_stats = 0 AND refund_of_bill_id IS NOT NULL AND reimbursed != 'DONE' THEN -amount_minor
                   ELSE 0 END), 0) AS expenseMinor,
               IFNULL(SUM(CASE WHEN type = 'INCOME' AND excluded_from_stats = 0 THEN amount_minor ELSE 0 END), 0) AS incomeMinor,
               IFNULL(SUM(CASE WHEN excluded_from_stats = 1 THEN amount_minor ELSE 0 END), 0) AS excludedMinor
        FROM bill
        WHERE deleted_at IS NULL
        GROUP BY ledger_id
        """,
    )
    fun observeLedgerStats(): Flow<List<LedgerStat>>

    @Query("SELECT COUNT(*) FROM bill WHERE deleted_at IS NULL")
    fun observeTotalCount(): Flow<Int>

    /**
     * 抽屉标题的笔数。不拆成「单账本 / 全部账本」两条：实测这条语句在两种入参下都走
     * deleted_at 索引扫未删行，单账本占多数行时比走 (ledger_id, occurred_at) 回表查 deleted_at 快
     * （7122 行：0.7ms 对 2.5ms）。
     */
    @Query("SELECT COUNT(*) FROM bill WHERE deleted_at IS NULL AND (:ledgerId IS NULL OR ledger_id = :ledgerId)")
    fun observeBillCount(ledgerId: String?): Flow<Int>

    /** 账本概览的时间跨度：取首末两笔账目时间，空账本两端都是 null。 */
    @Query(
        "SELECT MIN(occurred_at) AS start_at, MAX(occurred_at) AS end_at FROM bill " +
            "WHERE deleted_at IS NULL AND ledger_id = :ledgerId",
    )
    fun observeLedgerSpan(ledgerId: String): Flow<LedgerSpan>

    /** 概览页「最近几笔」：只取列表要显示的那几行，不把整本账搬进内存。 */
    @Query("$LEDGER_BILL_ROWS WHERE b.deleted_at IS NULL AND b.ledger_id = :ledgerId ORDER BY b.occurred_at DESC, b.created_at DESC LIMIT :limit")
    fun observeRecentRows(ledgerId: String, limit: Int): Flow<List<BillListItemRow>>

    /**
     * 快速问答的「最大一笔」。口径与统计合计一致：不计入收支、已报销、退款行都不参与，
     * 所以这个数一定落在那本账的总支出里。
     */
    @Query(
        "$LEDGER_BILL_ROWS WHERE b.deleted_at IS NULL AND b.ledger_id = :ledgerId " +
            "AND b.type = 'EXPENSE' AND b.excluded_from_stats = 0 " +
            "AND b.is_refund = 0 AND b.refund_of_bill_id IS NULL AND b.reimbursed != 'DONE' " +
            "ORDER BY b.amount_minor DESC, b.occurred_at DESC LIMIT 1",
    )
    fun observeLargestExpenseRow(ledgerId: String): Flow<BillListItemRow?>

    @Query("SELECT COUNT(*) FROM bill WHERE deleted_at IS NULL AND (category_l1_id = :categoryId OR category_l2_id = :categoryId)")
    suspend fun countBillsUsing(categoryId: String): Int

    @Query(
        "UPDATE bill SET ledger_id = :targetId, updated_at = :now, version = version + 1 " +
            "WHERE ledger_id = :sourceId AND deleted_at IS NULL",
    )
    suspend fun migrateToLedger(sourceId: String, targetId: String, now: Long): Int

    @Query("SELECT COUNT(*) FROM bill WHERE deleted_at IS NULL AND ledger_id = :ledgerId")
    suspend fun countByLedger(ledgerId: String): Int

    /**
     * 未记账提醒的一条查询同时回答两个问题：今天有没有账（返回值是否落在今天），
     * 以及上一次是哪天（算「已经 N 天没记」）。口径是有效账单，软删进回收站的不算记过。
     */
    @Query("SELECT MAX(occurred_at) FROM bill WHERE deleted_at IS NULL AND occurred_at < :endAtExclusive")
    suspend fun lastOccurredBefore(endAtExclusive: Long): Long?

    @Query("UPDATE bill SET deleted_at = :now, updated_at = :now WHERE ledger_id = :ledgerId AND deleted_at IS NULL")
    suspend fun softDeleteByLedger(ledgerId: String, now: Long): Int

    // ---- 回收站 ------
    //
    // 不建新表：每次删除写下的 deleted_at 就是回收站的全部数据来源。

    /** 回收站列表：按删除时间倒序，最近删的在最上面。 */
    @Query(
        """
        SELECT b.*, l.name AS ledger_name, c1.name AS category_l1_name, c2.name AS category_l2_name,
               c1.icon_key AS category_l1_icon, c2.icon_key AS category_l2_icon,
               ip.period_count AS installment_period_count,
               (SELECT COUNT(*) FROM attachment a WHERE a.bill_id = b.id AND a.deleted_at IS NULL) AS attachment_count,
               (SELECT IFNULL(SUM(r.amount_minor), 0) FROM bill r
                 WHERE r.refund_of_bill_id = b.id AND r.deleted_at IS NULL) AS refunded_minor
        FROM bill b
        INNER JOIN ledger l ON l.id = b.ledger_id
        LEFT JOIN category c1 ON c1.id = b.category_l1_id
        LEFT JOIN category c2 ON c2.id = b.category_l2_id
        LEFT JOIN installment_plan ip ON ip.id = b.installment_plan_id
        WHERE b.deleted_at IS NOT NULL
        ORDER BY b.deleted_at DESC, b.occurred_at DESC
        LIMIT :limit
        """,
    )
    suspend fun findRecycledRows(limit: Int): List<BillListItemRow>

    /** 顶部汇总：笔数与合计一次算完，不在 Kotlin 里再扫一遍。 */
    @Query(
        """
        SELECT COUNT(*) AS bills, IFNULL(SUM(amount_minor), 0) AS minor
        FROM bill WHERE deleted_at IS NOT NULL
        """,
    )
    suspend fun recycledSummary(): RecycleSummary

    @Query("SELECT id FROM bill WHERE deleted_at IS NOT NULL AND deleted_at < :cutoff ORDER BY deleted_at ASC LIMIT :limit")
    suspend fun findExpiredRecycledIds(cutoff: Long, limit: Int): List<String>

    /** 确认框里那句「¥412.00」：按选中的这几笔求和，不把整页拉回来再算。 */
    @Query("SELECT IFNULL(SUM(amount_minor), 0) FROM bill WHERE deleted_at IS NOT NULL AND id IN (:ids)")
    suspend fun recycledAmountOf(ids: List<String>): Long

    @Query("UPDATE bill SET deleted_at = NULL, updated_at = :now, version = version + 1 WHERE deleted_at IS NOT NULL AND id IN (:ids)")
    suspend fun restoreRecycled(ids: List<String>, now: Long): Int

    /**
     * 恢复前先把指向已消失分类的引用清掉：分类可以硬删（那时它名下 0 笔未删账单，
     * 但回收站里的软删账单还挂着它），不清就会恢复出一笔分类不存在、
     * 记账页又选不回去的账。清成未分类，页面提示重新归类。
     */
    @Query(
        """
        UPDATE bill SET category_l1_id = NULL, category_l2_id = NULL, updated_at = :now, version = version + 1
        WHERE id IN (:ids)
          AND ((category_l1_id IS NOT NULL AND category_l1_id NOT IN (SELECT id FROM category WHERE deleted_at IS NULL))
            OR (category_l2_id IS NOT NULL AND category_l2_id NOT IN (SELECT id FROM category WHERE deleted_at IS NULL)))
        """,
    )
    suspend fun detachGoneCategories(ids: List<String>, now: Long): Int

    @Query("DELETE FROM bill WHERE id IN (:ids)")
    suspend fun hardDeleteByIds(ids: List<String>): Int

    @Query(
        "UPDATE bill SET category_l1_id = :targetL1, category_l2_id = :targetL2, updated_at = :now, " +
            "version = version + 1 WHERE deleted_at IS NULL AND (category_l1_id = :sourceL1 OR category_l2_id = :sourceL1)",
    )
    suspend fun reassignCategory(
        sourceL1: String,
        targetL1: String?,
        targetL2: String?,
        now: Long,
    ): Int

    @Query(
        "UPDATE bill SET category_l2_id = :targetL2, updated_at = :now, version = version + 1 " +
            "WHERE deleted_at IS NULL AND category_l2_id IN (:sourceL2Ids)",
    )
    suspend fun reassignCategoryL2(sourceL2Ids: List<String>, targetL2: String?, now: Long): Int

    @Query(
        "UPDATE bill SET category_l1_id = NULL, category_l2_id = NULL, updated_at = :now, version = version + 1 " +
            "WHERE deleted_at IS NULL AND category_l1_id IN (:sourceL1Ids)",
    )
    suspend fun detachCategoryL1(sourceL1Ids: List<String>, now: Long): Int

    /**
     * 单账本区间明细：账本条件写成等值，查询计划才会走 bill(ledger_id, occurred_at)。
     * 写成 `:ledgerId IS NULL OR ledger_id = :ledgerId` 时 SQLite 用不上这个复合索引，
     * 只能退回 deleted_at 全扫——区间查询是首页、统计、概览的共用底座，代价被放大很多次。
     */
    @Query(
        "$LEDGER_BILL_ROWS WHERE b.deleted_at IS NULL AND b.ledger_id = :ledgerId " +
            "AND b.occurred_at >= :startAt AND b.occurred_at < :endAtExclusive " +
            "ORDER BY b.occurred_at DESC, b.created_at DESC",
    )
    fun observeRangeRows(
        ledgerId: String,
        startAt: Long,
        endAtExclusive: Long,
    ): Flow<List<BillListItemRow>>

    /** 全部账本的区间明细：没有可用的账本等值条件，只能按 occurred_at 过滤。 */
    @Query(
        "$LEDGER_BILL_ROWS WHERE b.deleted_at IS NULL " +
            "AND b.occurred_at >= :startAt AND b.occurred_at < :endAtExclusive " +
            "ORDER BY b.occurred_at DESC, b.created_at DESC",
    )
    fun observeRangeRowsAllLedgers(
        startAt: Long,
        endAtExclusive: Long,
    ): Flow<List<BillListItemRow>>

    /**
     * 概览用的聚合瘦行：只取合计与分类排行会读到的列，不带 `b.*`、不带附件/已退子查询、
     * 不 JOIN 账本与分期、不排序。退回界面的行数一样，但每行宽度与每行开销都小一个量级。
     */
    @Query(
        """
        SELECT b.type AS type, b.amount_minor AS amount_minor,
               b.category_l1_id AS category_l1_id, b.category_l2_id AS category_l2_id,
               b.is_refund AS is_refund, b.refund_of_bill_id AS refund_of_bill_id,
               b.reimbursed AS reimbursed, b.excluded_from_stats AS excluded_from_stats,
               c1.name AS category_l1_name, c1.icon_key AS category_l1_icon,
               c2.name AS category_l2_name, c2.icon_key AS category_l2_icon
        FROM bill b
        LEFT JOIN category c1 ON c1.id = b.category_l1_id
        LEFT JOIN category c2 ON c2.id = b.category_l2_id
        WHERE b.deleted_at IS NULL AND b.ledger_id = :ledgerId
          AND b.occurred_at >= :startAt AND b.occurred_at < :endAtExclusive
        """,
    )
    fun observeStatsRows(
        ledgerId: String,
        startAt: Long,
        endAtExclusive: Long,
    ): Flow<List<StatsBillRow>>

    /**
     * 搜索页的列表：只取最近 [:limit] 条。几千笔全量映射成对象再分组，
     * 就是"点进去先转一下才出数据"的真实开销；命中数交给 [observeSearchSummary]，它扫一遍就够。
     *
     * 四条查询同一份列与条件，只换 ORDER BY，分别对应 `BillSort` 的四档。
     */
    @Query("$SEARCH_ROWS ORDER BY b.occurred_at DESC, b.created_at DESC LIMIT :limit")
    fun observeSearchRowsNewest(
        keyword: String,
        startAt: Long?,
        endAtExclusive: Long?,
        type: String?,
        ledgerId: String?,
        categoryL1Id: String?,
        categoryL2Id: String?,
        source: String?,
        amountMin: Long?,
        amountMax: Long?,
        limit: Int,
    ): Flow<List<BillListItemRow>>

    @Query("$SEARCH_ROWS ORDER BY b.occurred_at ASC, b.created_at ASC LIMIT :limit")
    fun observeSearchRowsOldest(
        keyword: String,
        startAt: Long?,
        endAtExclusive: Long?,
        type: String?,
        ledgerId: String?,
        categoryL1Id: String?,
        categoryL2Id: String?,
        source: String?,
        amountMin: Long?,
        amountMax: Long?,
        limit: Int,
    ): Flow<List<BillListItemRow>>

    /** 金额相同的按时间倒序兜底，否则这一批的顺序每次刷新会抖。 */
    @Query("$SEARCH_ROWS ORDER BY b.amount_minor DESC, b.occurred_at DESC LIMIT :limit")
    fun observeSearchRowsLargest(
        keyword: String,
        startAt: Long?,
        endAtExclusive: Long?,
        type: String?,
        ledgerId: String?,
        categoryL1Id: String?,
        categoryL2Id: String?,
        source: String?,
        amountMin: Long?,
        amountMax: Long?,
        limit: Int,
    ): Flow<List<BillListItemRow>>

    @Query("$SEARCH_ROWS ORDER BY b.amount_minor ASC, b.occurred_at ASC LIMIT :limit")
    fun observeSearchRowsSmallest(
        keyword: String,
        startAt: Long?,
        endAtExclusive: Long?,
        type: String?,
        ledgerId: String?,
        categoryL1Id: String?,
        categoryL2Id: String?,
        source: String?,
        amountMin: Long?,
        amountMax: Long?,
        limit: Int,
    ): Flow<List<BillListItemRow>>

    /** 与四条列表查询共用同一份筛选条件（[SEARCH_WHERE]），所以截断列表也不影响汇总。 */
    @Query(
        """
        SELECT COUNT(*) AS billCount,
               IFNULL(SUM(CASE
                   WHEN b.excluded_from_stats = 0 AND b.type = 'EXPENSE' AND b.is_refund = 0 AND b.reimbursed != 'DONE' THEN b.amount_minor
                   WHEN b.excluded_from_stats = 0 AND b.type = 'EXPENSE' AND b.refund_of_bill_id IS NOT NULL AND b.reimbursed != 'DONE' THEN -b.amount_minor
                   ELSE 0 END), 0) AS expenseMinor,
               IFNULL(SUM(CASE WHEN b.excluded_from_stats = 0 AND b.type = 'INCOME' THEN b.amount_minor ELSE 0 END), 0) AS incomeMinor,
               IFNULL(SUM(CASE WHEN b.excluded_from_stats = 1 THEN b.amount_minor ELSE 0 END), 0) AS excludedMinor,
               IFNULL(SUM(CASE WHEN b.excluded_from_stats = 1 THEN 1 ELSE 0 END), 0) AS excludedCount,
               IFNULL(SUM(CASE WHEN b.excluded_from_stats = 0 AND b.type = 'EXPENSE' AND b.reimbursed = 'PENDING' THEN b.amount_minor ELSE 0 END), 0) AS pendingReimburseMinor,
               IFNULL(SUM(CASE WHEN b.excluded_from_stats = 0 AND b.type = 'EXPENSE' AND b.reimbursed = 'PENDING' THEN 1 ELSE 0 END), 0) AS pendingReimburseCount,
               IFNULL(SUM(CASE WHEN b.excluded_from_stats = 0 AND b.type = 'EXPENSE' AND b.reimbursed = 'DONE' THEN b.amount_minor ELSE 0 END), 0) AS reimbursedMinor,
               IFNULL(SUM(CASE WHEN b.excluded_from_stats = 0 AND b.type = 'EXPENSE' AND b.reimbursed = 'DONE' THEN 1 ELSE 0 END), 0) AS reimbursedCount
        FROM bill b
        INNER JOIN ledger l ON l.id = b.ledger_id
        LEFT JOIN category c1 ON c1.id = b.category_l1_id
        LEFT JOIN category c2 ON c2.id = b.category_l2_id
        $SEARCH_WHERE
        """,
    )
    fun observeSearchSummary(
        keyword: String,
        startAt: Long?,
        endAtExclusive: Long?,
        type: String?,
        ledgerId: String?,
        categoryL1Id: String?,
        categoryL2Id: String?,
        source: String?,
        amountMin: Long?,
        amountMax: Long?,
    ): Flow<BillSummary>

    @Query(
        """
        SELECT b.*, l.name AS ledger_name, c1.name AS category_l1_name, c2.name AS category_l2_name,
               c1.icon_key AS category_l1_icon, c2.icon_key AS category_l2_icon,
               ip.period_count AS installment_period_count,
               (SELECT COUNT(*) FROM attachment a WHERE a.bill_id = b.id AND a.deleted_at IS NULL) AS attachment_count,
               (SELECT IFNULL(SUM(r.amount_minor), 0) FROM bill r
                 WHERE r.refund_of_bill_id = b.id AND r.deleted_at IS NULL) AS refunded_minor
        FROM bill b
        INNER JOIN ledger l ON l.id = b.ledger_id
        LEFT JOIN category c1 ON c1.id = b.category_l1_id
        LEFT JOIN category c2 ON c2.id = b.category_l2_id
        LEFT JOIN installment_plan ip ON ip.id = b.installment_plan_id
        WHERE b.id = :id AND b.deleted_at IS NULL
        LIMIT 1
        """,
    )
    suspend fun findRowById(id: String): BillListItemRow?

    @Query("SELECT * FROM bill WHERE id = :id AND deleted_at IS NULL LIMIT 1")
    suspend fun findEntityById(id: String): BillEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(bill: BillEntity)

    @Update
    suspend fun update(bill: BillEntity)

    @Query("UPDATE bill SET deleted_at = :now, updated_at = :now, version = version + 1 WHERE id = :id AND deleted_at IS NULL")
    suspend fun softDelete(id: String, now: Long): Int

    /** 批量动作：一次操作 = 一个可撤销单元，所以按 ID 列表整批写。 */
    @Query("SELECT * FROM bill WHERE id IN (:ids)")
    suspend fun findEntitiesByIds(ids: List<String>): List<BillEntity>

    @Query("UPDATE bill SET deleted_at = :now, updated_at = :now, version = version + 1 WHERE id IN (:ids) AND deleted_at IS NULL")
    suspend fun softDeleteIds(ids: List<String>, now: Long): Int

    @Query("UPDATE bill SET category_l1_id = :l1Id, category_l2_id = :l2Id, updated_at = :now, version = version + 1 WHERE id IN (:ids)")
    suspend fun setCategoryByIds(ids: List<String>, l1Id: String?, l2Id: String?, now: Long): Int

    @Query("UPDATE bill SET reimbursed = :status, updated_at = :now, version = version + 1 WHERE id IN (:ids)")
    suspend fun setReimbursedByIds(ids: List<String>, status: String, now: Long): Int

    @Query("UPDATE bill SET excluded_from_stats = :excluded, updated_at = :now, version = version + 1 WHERE id IN (:ids)")
    suspend fun setExcludedByIds(ids: List<String>, excluded: Boolean, now: Long): Int

    @Query("UPDATE bill SET note = :note, updated_at = :now, version = version + 1 WHERE id IN (:ids) AND deleted_at IS NULL")
    suspend fun setNoteByIds(ids: List<String>, note: String?, now: Long): Int

    @Query("UPDATE bill SET ledger_id = :targetId, updated_at = :now, version = version + 1 WHERE id IN (:ids) AND deleted_at IS NULL")
    suspend fun setLedgerByIds(ids: List<String>, targetId: String, now: Long): Int

    @Update
    suspend fun updateAll(bills: List<BillEntity>): Int

    /** 撤销窗口内按快照整批还原，含 deleted_at 与字段原值。 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun restore(bills: List<BillEntity>)

    /** 记忆的分类已归档或删除时，回落到全机笔数最多的一级（F1.3）。 */
    @Query(
        "SELECT category_l1_id FROM bill WHERE deleted_at IS NULL AND type = :type " +
            "AND category_l1_id IS NOT NULL GROUP BY category_l1_id ORDER BY COUNT(*) DESC LIMIT 1",
    )
    suspend fun topCategoryL1(type: String): String?

    @Query(
        """
        SELECT b.*, l.name AS ledger_name, c1.name AS category_l1_name, c2.name AS category_l2_name,
               c1.icon_key AS category_l1_icon, c2.icon_key AS category_l2_icon,
               ip.period_count AS installment_period_count,
               (SELECT COUNT(*) FROM attachment a WHERE a.bill_id = b.id AND a.deleted_at IS NULL) AS attachment_count,
               (SELECT IFNULL(SUM(r.amount_minor), 0) FROM bill r
                 WHERE r.refund_of_bill_id = b.id AND r.deleted_at IS NULL) AS refunded_minor
        FROM bill b
        INNER JOIN ledger l ON l.id = b.ledger_id
        LEFT JOIN category c1 ON c1.id = b.category_l1_id
        LEFT JOIN category c2 ON c2.id = b.category_l2_id
        LEFT JOIN installment_plan ip ON ip.id = b.installment_plan_id
        WHERE b.deleted_at IS NULL
          AND b.occurred_at >= :startAt AND b.occurred_at < :endAtExclusive
          AND (:ledgerId IS NULL OR b.ledger_id = :ledgerId)
          AND (:type IS NULL OR b.type = :type)
          AND (:categoryL1Id IS NULL OR b.category_l1_id = :categoryL1Id)
          AND (:source IS NULL OR b.source = :source)
        ORDER BY b.occurred_at ASC, b.created_at ASC
        """,
    )
    suspend fun exportRows(
        ledgerId: String?,
        startAt: Long,
        endAtExclusive: Long,
        type: String?,
        categoryL1Id: String?,
        source: String?,
    ): List<BillListItemRow>

    @Query("SELECT * FROM bill WHERE external_id = :externalId AND deleted_at IS NULL LIMIT 1")
    suspend fun findByExternalId(externalId: String): BillEntity?

    /** 周期/分期幂等：同一任务（或计划）的同一期只允许一笔有效账单。 */
    @Query(
        "SELECT * FROM bill WHERE recurrence_rule_id = :ruleId AND period_index = :periodIndex " +
            "AND deleted_at IS NULL LIMIT 1",
    )
    suspend fun findByRecurringPeriod(ruleId: String, periodIndex: Int): BillEntity?

    /**
     * 某个周期任务生成过的全部期：明细弹层用，**含回收站里的行**。
     *
     * 这一期被删掉后仍占着排期期号，明细要把缺号补成灰行，只看有效行会让期号凭空跳。
     * 分类名不再随行取回——弹层顶部已经写了一行分类路径。
     */
    @Query(
        """
        SELECT b.id AS bill_id, b.period_index, b.occurred_at, b.amount_minor, b.type, b.deleted_at, b.note
        FROM bill b
        WHERE b.recurrence_rule_id = :ruleId
        ORDER BY b.period_index DESC, b.occurred_at DESC
        """
    )
    suspend fun findByRecurringRule(ruleId: String): List<RecurringPeriodRowTuple>

    @Query(
        "SELECT * FROM bill WHERE installment_plan_id = :planId AND period_index = :periodIndex " +
            "AND deleted_at IS NULL LIMIT 1",
    )
    suspend fun findByInstallmentPeriod(planId: String, periodIndex: Int): BillEntity?

    // ---- 幂等判定用的「含回收站」版本 --------------------------------
    //
    // 上面两个查询滤掉了 deleted_at，只适合「这一期有没有活账」这种展示用途。
    // **幂等判定不能只用它们**：同一期在回收站里还留着一行时，只看有效行会判定成
    // 「还没生成过」，于是插一条新行 —— 而 (rule|plan, period_index) 上是全量唯一索引，
    // @Insert(REPLACE) 会先把回收站那一行**物理删掉**再插新的，用户就再也找不回那笔了。
    // 所以判重一律走下面这两个：只要这一期存在过（哪怕已软删），就不再写第二次。

    @Query(
        "SELECT * FROM bill WHERE recurrence_rule_id = :ruleId AND period_index = :periodIndex LIMIT 1",
    )
    suspend fun findRecurringPeriodAny(ruleId: String, periodIndex: Int): BillEntity?

    @Query(
        "SELECT * FROM bill WHERE installment_plan_id = :planId AND period_index = :periodIndex LIMIT 1",
    )
    suspend fun findInstallmentPeriodAny(planId: String, periodIndex: Int): BillEntity?

    /**
     * 所有分期计划下**有效**账单的期号：明细弹层用它核对「这一期到底入没入账」。
     * 一次查全部计划，不做成 per-plan 查询——列表页要同时给多张卡算口径。
     */
    @Query(
        "SELECT installment_plan_id AS planId, period_index AS periodIndex FROM bill " +
            "WHERE installment_plan_id IS NOT NULL AND deleted_at IS NULL",
    )
    fun observeDepositedPlanPeriods(): Flow<List<PlanPeriodRow>>

    // ---- 退款与原单关联 ------

    /**
     * 原单已退累计。[excludeId] 传正在编辑的那笔退款自身，免得把它算进「已退」；
     * 新建时传空串——SQL 里 `id != NULL` 永远不成立，必须给一个真实值。
     */
    @Query(
        "SELECT IFNULL(SUM(amount_minor), 0) FROM bill " +
            "WHERE deleted_at IS NULL AND refund_of_bill_id = :originId AND id != :excludeId",
    )
    suspend fun refundedTotal(originId: String, excludeId: String): Long

    @Query(
        """
        SELECT id AS id, amount_minor AS amountMinor, occurred_at AS occurredAt, note AS note
        FROM bill WHERE deleted_at IS NULL AND refund_of_bill_id = :originId
        ORDER BY occurred_at ASC, created_at ASC
        """,
    )
    suspend fun refundsOf(originId: String): List<RefundEntry>

    /** 原单被彻底删除时解开指向：没有原单可指，退款行退回「未关联原单」。 */
    @Query("UPDATE bill SET refund_of_bill_id = NULL, updated_at = :now, version = version + 1 WHERE refund_of_bill_id IN (:originIds)")
    suspend fun detachRefunds(originIds: List<String>, now: Long): Int
}

