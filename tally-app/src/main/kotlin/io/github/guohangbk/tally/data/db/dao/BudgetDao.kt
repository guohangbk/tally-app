package io.github.guohangbk.tally.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import io.github.guohangbk.tally.data.db.entity.BudgetEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface BudgetDao {
    @Query("SELECT * FROM budget WHERE deleted_at IS NULL AND ledger_id = :ledgerId ORDER BY is_active DESC, created_at ASC")
    fun observeByLedger(ledgerId: String): Flow<List<BudgetEntity>>

    @Query(
        "SELECT b.* FROM budget b JOIN ledger l ON l.id = b.ledger_id AND l.deleted_at IS NULL AND l.is_archived = 0 " +
            "WHERE b.deleted_at IS NULL AND b.is_active = 1 AND b.period = :period",
    )
    fun observeActiveAnyLedger(period: String): Flow<List<BudgetEntity>>

    @Query(
        "SELECT b.* FROM budget b JOIN ledger l ON l.id = b.ledger_id AND l.deleted_at IS NULL AND l.is_archived = 0 " +
            "WHERE b.deleted_at IS NULL AND b.is_active = 1",
    )
    suspend fun findAllActive(): List<BudgetEntity>

    @Query("SELECT * FROM budget WHERE id = :id AND deleted_at IS NULL")
    suspend fun findById(id: String): BudgetEntity?

    @Query("SELECT * FROM budget WHERE deleted_at IS NULL AND ledger_id = :ledgerId AND period = :period AND period_start = :periodStart AND IFNULL(category_id, '') = IFNULL(:categoryId, '')")
    suspend fun findSameScope(ledgerId: String, period: String, periodStart: Long, categoryId: String?): BudgetEntity?

    /** 自动续期取数：某段里开了 auto_renew、启用、未删的全部账本月预算行，作为下一段的复制源。 */
    @Query("SELECT * FROM budget WHERE deleted_at IS NULL AND period = :period AND period_start = :periodStart AND auto_renew = 1 AND is_active = 1")
    suspend fun findAutoRenewInPeriod(period: String, periodStart: Long): List<BudgetEntity>

    @Query("SELECT * FROM budget WHERE deleted_at IS NULL AND ledger_id = :ledgerId AND period = :period AND is_active = 1")
    suspend fun findActive(ledgerId: String, period: String): List<BudgetEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(budget: BudgetEntity)

    @Update
    suspend fun update(budget: BudgetEntity)

    @Query("UPDATE budget SET is_active = :active, updated_at = :now, version = version + 1 WHERE id = :id AND deleted_at IS NULL")
    suspend fun setActive(id: String, active: Boolean, now: Long): Int

    @Query("UPDATE budget SET deleted_at = :now, updated_at = :now, version = version + 1 WHERE id = :id AND deleted_at IS NULL")
    suspend fun softDelete(id: String, now: Long): Int

    @Query("DELETE FROM budget WHERE ledger_id = :ledgerId")
    suspend fun purgeByLedger(ledgerId: String): Int
}
