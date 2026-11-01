package io.github.guohangbk.tally.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import io.github.guohangbk.tally.data.db.entity.BillEntity
import io.github.guohangbk.tally.data.db.query.BillListItemRow
import io.github.guohangbk.tally.data.db.query.LedgerStat
import io.github.guohangbk.tally.data.db.query.RecycleSummary
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
     * 抽屉标题那个笔数用的单账本计数：传 ledgerId 走 bill(ledger_id, occurred_at) 索引，
     * 传 null（全部账本）走 deleted_at 索引。比 observeLedgerStats 的整表 GROUP BY 轻得多。
     */
    @Query("SELECT COUNT(*) FROM bill WHERE deleted_at IS NULL AND (:ledgerId IS NULL OR ledger_id = :ledgerId)")
    fun observeBillCount(ledgerId: String?): Flow<Int>

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
          AND (:ledgerId IS NULL OR b.ledger_id = :ledgerId)
          AND b.occurred_at >= :startAt AND b.occurred_at < :endAtExclusive
        ORDER BY b.occurred_at DESC, b.created_at DESC
        """,
    )
    fun observeRangeRows(
        ledgerId: String?,
        startAt: Long,
        endAtExclusive: Long,
    ): Flow<List<BillListItemRow>>

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

    /** 某个周期任务已生成的全部账单：明细弹层按期次排着看。 */
    @Query(
        "SELECT * FROM bill WHERE recurrence_rule_id = :ruleId AND deleted_at IS NULL " +
            "ORDER BY period_index ASC, occurred_at ASC",
    )
    suspend fun findByRecurringRule(ruleId: String): List<BillEntity>

    @Query(
        "SELECT * FROM bill WHERE installment_plan_id = :planId AND period_index = :periodIndex " +
            "AND deleted_at IS NULL LIMIT 1",
    )
    suspend fun findByInstallmentPeriod(planId: String, periodIndex: Int): BillEntity?

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

