package io.github.guohangbk.tally.data.db.dao

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.github.guohangbk.tally.data.db.entity.BillTagEntity
import io.github.guohangbk.tally.data.db.entity.TagEntity
import kotlinx.coroutines.flow.Flow

/** 管理墙 / 选择器共用的一行：名称 + 笔数（只数未删账单）+ 最近一次用到的时间。 */
data class TagUsageRow(
    @ColumnInfo("id") val id: String,
    @ColumnInfo("name") val name: String,
    @ColumnInfo("sort_order") val sortOrder: Double,
    @ColumnInfo("archived_at") val archivedAt: Long?,
    @ColumnInfo("bill_count") val billCount: Int,
    @ColumnInfo("last_used_at") val lastUsedAt: Long?,
)

/** 列表行上的标签投影：一笔可挂多枚，按标签自己的排序。 */
data class BillTagNameRow(
    @ColumnInfo("bill_id") val billId: String,
    @ColumnInfo("id") val id: String,
    @ColumnInfo("name") val name: String,
    @ColumnInfo("sort_order") val sortOrder: Double,
)

/** 标签详情的合计一块：笔数 / 账本数 / 最近一笔 / 支出 / 收入 / 已排除笔数。 */
data class TagTotalsRow(
    @ColumnInfo("bill_count") val billCount: Int,
    @ColumnInfo("ledger_count") val ledgerCount: Int,
    @ColumnInfo("last_occurred_at") val lastOccurredAt: Long?,
    @ColumnInfo("expense_minor") val expenseMinor: Long,
    @ColumnInfo("income_minor") val incomeMinor: Long,
    @ColumnInfo("excluded_count") val excludedCount: Int,
)

data class TagMonthRow(
    @ColumnInfo("month") val month: Int,
    @ColumnInfo("amount_minor") val amountMinor: Long,
)

data class TagCategoryRow(
    @ColumnInfo("category_id") val categoryId: String?,
    @ColumnInfo("name") val name: String,
    @ColumnInfo("icon_key") val iconKey: String?,
    @ColumnInfo("amount_minor") val amountMinor: Long,
)

data class TagLedgerRow(
    @ColumnInfo("ledger_id") val ledgerId: String,
    @ColumnInfo("ledger_name") val ledgerName: String,
    @ColumnInfo("bill_count") val billCount: Int,
    @ColumnInfo("amount_minor") val amountMinor: Long,
)

/**
 * 标签领域的读写。标签只切分「看哪批账」，所以这里的金额聚合与统计页用同一个 CASE：
 * 不计入收支、已报销完成、退款行都按原规则处理，标签不引入第二套口径。
 */
@Dao
interface TagDao {
    // ---- 标签本身 ------------------------------------------------------

    /** 使用中（未归档、未删）的标签，带笔数与最近使用时间；管理墙、选择器、筛选共用。 */
    @Query(
        """
        SELECT t.id AS id, t.name AS name, t.sort_order AS sort_order, t.archived_at AS archived_at,
               (SELECT COUNT(*) FROM bill_tag bt JOIN bill b ON b.id = bt.bill_id
                 WHERE bt.tag_id = t.id AND b.deleted_at IS NULL) AS bill_count,
               (SELECT MAX(b.updated_at) FROM bill_tag bt JOIN bill b ON b.id = bt.bill_id
                 WHERE bt.tag_id = t.id AND b.deleted_at IS NULL) AS last_used_at
        FROM tag t
        WHERE t.deleted_at IS NULL AND t.archived_at IS NULL
        ORDER BY t.sort_order ASC, t.created_at ASC
        """,
    )
    fun observeActive(): Flow<List<TagUsageRow>>

    @Query(
        """
        SELECT t.id AS id, t.name AS name, t.sort_order AS sort_order, t.archived_at AS archived_at,
               (SELECT COUNT(*) FROM bill_tag bt JOIN bill b ON b.id = bt.bill_id
                 WHERE bt.tag_id = t.id AND b.deleted_at IS NULL) AS bill_count,
               (SELECT MAX(b.updated_at) FROM bill_tag bt JOIN bill b ON b.id = bt.bill_id
                 WHERE bt.tag_id = t.id AND b.deleted_at IS NULL) AS last_used_at
        FROM tag t
        WHERE t.deleted_at IS NULL AND t.archived_at IS NOT NULL
        ORDER BY t.archived_at DESC
        """,
    )
    fun observeArchived(): Flow<List<TagUsageRow>>

    @Query("SELECT * FROM tag WHERE deleted_at IS NULL ORDER BY sort_order ASC")
    fun observeAllOnce(): Flow<List<TagEntity>>

    @Query("SELECT * FROM tag WHERE deleted_at IS NULL ORDER BY sort_order ASC")
    suspend fun allOnce(): List<TagEntity>

    @Query("SELECT * FROM tag WHERE id = :id AND deleted_at IS NULL LIMIT 1")
    suspend fun find(id: String): TagEntity?

    @Query("SELECT * FROM tag WHERE name = :name AND deleted_at IS NULL LIMIT 1")
    suspend fun findByName(name: String): TagEntity?

    @Query("SELECT MAX(sort_order) FROM tag WHERE deleted_at IS NULL")
    suspend fun maxSortOrder(): Double?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(tag: TagEntity)

    @Query("UPDATE tag SET name = :name, updated_at = :now, version = version + 1 WHERE id = :id")
    suspend fun rename(id: String, name: String, now: Long)

    @Query("UPDATE tag SET archived_at = :archivedAt, updated_at = :now, version = version + 1 WHERE id = :id")
    suspend fun setArchivedAt(id: String, archivedAt: Long?, now: Long)

    @Query("UPDATE tag SET deleted_at = :now, updated_at = :now, version = version + 1 WHERE id = :id")
    suspend fun softDelete(id: String, now: Long)

    @Query("UPDATE tag SET sort_order = :sortOrder, updated_at = :now WHERE id = :id")
    suspend fun setSortOrder(id: String, sortOrder: Double, now: Long)

    // ---- 账单关联 ------------------------------------------------------

    @Query("SELECT tag_id FROM bill_tag WHERE bill_id = :billId")
    suspend fun tagIdsForBill(billId: String): List<String>

    @Query("SELECT bill_id, tag_id FROM bill_tag WHERE bill_id IN (:billIds)")
    suspend fun tagIdsForBills(billIds: List<String>): List<BillTagLink>

    @Query(
        """
        SELECT bt.bill_id AS bill_id, t.id AS id, t.name AS name, t.sort_order AS sort_order
        FROM bill_tag bt
        INNER JOIN tag t ON t.id = bt.tag_id
        WHERE bt.bill_id IN (:billIds) AND t.deleted_at IS NULL
        ORDER BY t.sort_order ASC, t.created_at ASC
        """,
    )
    suspend fun namesForBills(billIds: List<String>): List<BillTagNameRow>

    @Query("SELECT t.* FROM tag t INNER JOIN bill_tag bt ON bt.tag_id = t.id WHERE bt.bill_id = :billId AND t.deleted_at IS NULL ORDER BY t.sort_order ASC")
    suspend fun tagsForBill(billId: String): List<TagEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun attach(rows: List<BillTagEntity>)

    @Query("DELETE FROM bill_tag WHERE bill_id = :billId AND tag_id IN (:tagIds)")
    suspend fun detach(billId: String, tagIds: List<String>)

    @Query("DELETE FROM bill_tag WHERE bill_id = :billId")
    suspend fun clearForBill(billId: String)

    @Query("DELETE FROM bill_tag WHERE bill_id IN (:billIds) AND tag_id IN (:tagIds)")
    suspend fun detachMany(billIds: List<String>, tagIds: List<String>)

    @Query("DELETE FROM bill_tag WHERE bill_id IN (:billIds)")
    suspend fun clearForBills(billIds: List<String>)

    @Query("DELETE FROM bill_tag WHERE tag_id = :tagId")
    suspend fun clearForTag(tagId: String)

    @Query("SELECT COUNT(*) FROM bill b INNER JOIN bill_tag bt ON bt.bill_id = b.id WHERE bt.tag_id = :tagId AND b.deleted_at IS NULL")
    suspend fun countBills(tagId: String): Int

    /**
     * 关联表的变更信号：行数（增删）与标签 `updated_at`（改名 / 归档）任一变化都会推一次。
     * 列表行的标签是二次查询补上的，没有这条流，改标签后列表不会重新取一次。
     */
    @Query("SELECT (SELECT COUNT(*) FROM bill_tag) + (SELECT IFNULL(SUM(updated_at), 0) FROM tag) AS tick")
    fun observeLinkTick(): Flow<Long>

    // ---- 标签详情 ------------------------------------------------------

    @Query(
        """
        SELECT COUNT(*) AS bill_count,
               (SELECT COUNT(DISTINCT b.ledger_id) FROM bill b INNER JOIN bill_tag bt ON bt.bill_id = b.id
                 WHERE bt.tag_id = :tagId AND b.deleted_at IS NULL
                   AND b.occurred_at >= :startAt AND b.occurred_at < :endAt) AS ledger_count,
               (SELECT MAX(b.occurred_at) FROM bill b INNER JOIN bill_tag bt ON bt.bill_id = b.id
                 WHERE bt.tag_id = :tagId AND b.deleted_at IS NULL
                   AND b.occurred_at >= :startAt AND b.occurred_at < :endAt) AS last_occurred_at,
               IFNULL(SUM(CASE
                   WHEN b.type = 'EXPENSE' AND b.excluded_from_stats = 0 AND b.is_refund = 0 AND b.reimbursed != 'DONE' THEN b.amount_minor
                   WHEN b.type = 'EXPENSE' AND b.excluded_from_stats = 0 AND b.refund_of_bill_id IS NOT NULL AND b.reimbursed != 'DONE' THEN -b.amount_minor
                   ELSE 0 END), 0) AS expense_minor,
               IFNULL(SUM(CASE WHEN b.type = 'INCOME' AND b.excluded_from_stats = 0 THEN b.amount_minor ELSE 0 END), 0) AS income_minor,
               IFNULL(SUM(CASE WHEN b.excluded_from_stats = 1 THEN 1 ELSE 0 END), 0) AS excluded_count
        FROM bill b INNER JOIN bill_tag bt ON bt.bill_id = b.id
        WHERE bt.tag_id = :tagId AND b.deleted_at IS NULL
          AND b.occurred_at >= :startAt AND b.occurred_at < :endAt
        """,
    )
    suspend fun totals(tagId: String, startAt: Long, endAt: Long): TagTotalsRow

    /** 月度走势固定看一整年：选月份只收窄下面几段，不跟着缩成一天的数据。 */
    @Query(
        """
        SELECT CAST(strftime('%m', b.occurred_at / 1000, 'unixepoch', 'localtime') AS INTEGER) AS month,
               IFNULL(SUM(CASE
                   WHEN b.excluded_from_stats = 0 AND b.type = 'EXPENSE' AND b.is_refund = 0 AND b.reimbursed != 'DONE' THEN b.amount_minor
                   WHEN b.excluded_from_stats = 0 AND b.type = 'EXPENSE' AND b.refund_of_bill_id IS NOT NULL AND b.reimbursed != 'DONE' THEN -b.amount_minor
                   ELSE 0 END), 0) AS amount_minor
        FROM bill b INNER JOIN bill_tag bt ON bt.bill_id = b.id
        WHERE bt.tag_id = :tagId AND b.deleted_at IS NULL
          AND b.occurred_at >= :startAt AND b.occurred_at < :endAt
        GROUP BY month
        """,
    )
    suspend fun monthlyExpense(tagId: String, startAt: Long, endAt: Long): List<TagMonthRow>

    @Query(
        """
        SELECT b.category_l1_id AS category_id, IFNULL(c.name, '未分类') AS name, c.icon_key AS icon_key,
               IFNULL(SUM(CASE
                   WHEN b.excluded_from_stats = 0 AND b.type = 'EXPENSE' AND b.is_refund = 0 AND b.reimbursed != 'DONE' THEN b.amount_minor
                   WHEN b.excluded_from_stats = 0 AND b.type = 'EXPENSE' AND b.refund_of_bill_id IS NOT NULL AND b.reimbursed != 'DONE' THEN -b.amount_minor
                   ELSE 0 END), 0) AS amount_minor
        FROM bill b INNER JOIN bill_tag bt ON bt.bill_id = b.id
        LEFT JOIN category c ON c.id = b.category_l1_id
        WHERE bt.tag_id = :tagId AND b.deleted_at IS NULL
          AND b.occurred_at >= :startAt AND b.occurred_at < :endAt
        GROUP BY b.category_l1_id
        HAVING amount_minor > 0
        ORDER BY amount_minor DESC
        """,
    )
    suspend fun categoryComposition(tagId: String, startAt: Long, endAt: Long): List<TagCategoryRow>

    @Query(
        """
        SELECT b.ledger_id AS ledger_id, l.name AS ledger_name, COUNT(*) AS bill_count,
               IFNULL(SUM(CASE
                   WHEN b.excluded_from_stats = 0 AND b.type = 'EXPENSE' AND b.is_refund = 0 AND b.reimbursed != 'DONE' THEN b.amount_minor
                   WHEN b.excluded_from_stats = 0 AND b.type = 'EXPENSE' AND b.refund_of_bill_id IS NOT NULL AND b.reimbursed != 'DONE' THEN -b.amount_minor
                   ELSE 0 END), 0) AS amount_minor
        FROM bill b INNER JOIN bill_tag bt ON bt.bill_id = b.id
        INNER JOIN ledger l ON l.id = b.ledger_id
        WHERE bt.tag_id = :tagId AND b.deleted_at IS NULL
          AND b.occurred_at >= :startAt AND b.occurred_at < :endAt
        GROUP BY b.ledger_id
        ORDER BY amount_minor DESC, l.name ASC
        """,
    )
    suspend fun ledgerBreakdown(tagId: String, startAt: Long, endAt: Long): List<TagLedgerRow>
}

/** 只看关联本身：批量打标要做「交集预选」时用它分组。 */
data class BillTagLink(
    @ColumnInfo("bill_id") val billId: String,
    @ColumnInfo("tag_id") val tagId: String,
)
