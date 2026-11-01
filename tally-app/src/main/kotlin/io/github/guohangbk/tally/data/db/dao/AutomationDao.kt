package io.github.guohangbk.tally.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import io.github.guohangbk.tally.data.db.entity.InstallmentPlanEntity
import io.github.guohangbk.tally.data.db.entity.RecurringRuleEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface RecurringRuleDao {
    @Query("SELECT * FROM recurring_rule WHERE deleted_at IS NULL ORDER BY status ASC, next_due_at ASC")
    fun observeAll(): Flow<List<RecurringRuleEntity>>

    @Query("SELECT * FROM recurring_rule WHERE id = :id AND deleted_at IS NULL")
    suspend fun findById(id: String): RecurringRuleEntity?

    @Query("SELECT * FROM recurring_rule WHERE deleted_at IS NULL AND status = 'ACTIVE' ORDER BY next_due_at ASC")
    suspend fun findActive(): List<RecurringRuleEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(rule: RecurringRuleEntity)

    @Update
    suspend fun update(rule: RecurringRuleEntity)

    @Query("UPDATE recurring_rule SET deleted_at = :now, updated_at = :now, version = version + 1 WHERE id = :id")
    suspend fun softDelete(id: String, now: Long)

    /** 补执行成功后推进游标；期序与游标一起写，避免下次重复生成。 */
    @Query(
        "UPDATE recurring_rule SET next_due_at = :nextDueAt, last_executed_at = :executedAt, " +
            "executed_periods = :executedPeriods, updated_at = :now, version = version + 1 WHERE id = :id",
    )
    suspend fun advance(id: String, nextDueAt: Long, executedAt: Long, executedPeriods: Int, now: Long)

    @Query("UPDATE recurring_rule SET status = :status, updated_at = :now, version = version + 1 WHERE id = :id")
    suspend fun setStatus(id: String, status: String, now: Long)

    /**
     * 记下"这条规则今天已经提醒过"。单独一条 UPDATE 而不是走 [update]：
     * 提醒发生在补执行之后，那时内存里的实体可能已经是旧的一份，整行写回会覆盖掉刚推进的游标。
     */
    @Query("UPDATE recurring_rule SET last_reminded_at = :at, updated_at = :at, version = version + 1 WHERE id = :id")
    suspend fun markReminded(id: String, at: Long)
}

@Dao
interface InstallmentPlanDao {
    @Query("SELECT * FROM installment_plan WHERE deleted_at IS NULL ORDER BY status ASC, first_occurred_at DESC")
    fun observeAll(): Flow<List<InstallmentPlanEntity>>

    @Query("SELECT * FROM installment_plan WHERE id = :id AND deleted_at IS NULL")
    suspend fun findById(id: String): InstallmentPlanEntity?

    @Query("SELECT * FROM installment_plan WHERE deleted_at IS NULL AND status = 'ACTIVE' ORDER BY first_occurred_at ASC")
    suspend fun findActive(): List<InstallmentPlanEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(plan: InstallmentPlanEntity)

    @Update
    suspend fun update(plan: InstallmentPlanEntity)

    @Query("UPDATE installment_plan SET deleted_at = :now, updated_at = :now, version = version + 1 WHERE id = :id")
    suspend fun softDelete(id: String, now: Long)

    @Query(
        "UPDATE installment_plan SET generated_periods = :generatedPeriods, " +
            "status = :status, updated_at = :now, version = version + 1 WHERE id = :id",
    )
    suspend fun advance(id: String, generatedPeriods: Int, status: String, now: Long)

    /** 与周期任务同义：一条计划每天最多提醒一次，游标单独写以免覆盖刚推进的期数。 */
    @Query("UPDATE installment_plan SET last_reminded_at = :at, updated_at = :at, version = version + 1 WHERE id = :id")
    suspend fun markReminded(id: String, at: Long)
}

/** 报销管理页需要的三组账单，口径在 SQL 里一次分开，避免页面各自过滤。 */
@Dao
interface ReimbursementDao {
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
        WHERE b.deleted_at IS NULL AND b.reimbursed = :status
          AND b.excluded_from_stats = 0
          AND b.is_reimbursement = 0 AND b.type = 'EXPENSE'
          AND (:ledgerId IS NULL OR b.ledger_id = :ledgerId)
        ORDER BY b.occurred_at DESC, b.created_at DESC
        """,
    )
    fun observeByReimburseStatus(status: String, ledgerId: String?): Flow<List<io.github.guohangbk.tally.data.db.query.BillListItemRow>>

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
        WHERE b.deleted_at IS NULL AND b.is_reimbursement = 1
          AND (:ledgerId IS NULL OR b.ledger_id = :ledgerId)
        ORDER BY b.occurred_at DESC, b.created_at DESC
        """,
    )
    fun observeReimbursementRecords(ledgerId: String?): Flow<List<io.github.guohangbk.tally.data.db.query.BillListItemRow>>

    /**
     * 报销只改状态：DONE 才写报销日期，撤销回 PENDING 时保留原日期供追溯（T7.3）。
     * 口径与统计层一致，不动 amount_minor，也不动 excluded_from_stats。
     */
    @Query(
        "UPDATE bill SET reimbursed = :status, updated_at = :now, version = version + 1, " +
            "reimbursed_at = CASE WHEN :status = 'DONE' THEN :now ELSE reimbursed_at END " +
            "WHERE id = :id AND deleted_at IS NULL AND type = 'EXPENSE'",
    )
    suspend fun setReimbursed(id: String, status: String, now: Long): Int

    @Query(
        "UPDATE bill SET reimbursed = :status, updated_at = :now, version = version + 1, " +
            "reimbursed_at = CASE WHEN :status = 'DONE' THEN :now ELSE reimbursed_at END " +
            "WHERE id IN (:ids) AND deleted_at IS NULL AND type = 'EXPENSE' AND excluded_from_stats = 0",
    )
    suspend fun setReimbursedByIds(ids: List<String>, status: String, now: Long): Int
}
