package io.github.guohangbk.tally.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import io.github.guohangbk.tally.data.db.entity.LedgerEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface LedgerDao {
    @Query("SELECT * FROM ledger WHERE deleted_at IS NULL ORDER BY is_default DESC, sort_order ASC")
    fun observeActive(): Flow<List<LedgerEntity>>

    @Query("SELECT * FROM ledger WHERE deleted_at IS NULL AND is_archived = 0 ORDER BY is_default DESC, sort_order ASC")
    fun observeActiveNotArchived(): Flow<List<LedgerEntity>>

    @Query("SELECT * FROM ledger WHERE id = :id AND deleted_at IS NULL")
    suspend fun findById(id: String): LedgerEntity?

    /** 概览页只认这一本账：不跟着列表一起刷，也不为一行标题去跑整表聚合。 */
    @Query("SELECT * FROM ledger WHERE id = :id AND deleted_at IS NULL LIMIT 1")
    fun observeById(id: String): Flow<LedgerEntity?>

    @Query("SELECT * FROM ledger WHERE deleted_at IS NULL AND is_default = 1 LIMIT 1")
    fun observeDefault(): Flow<LedgerEntity?>

    @Query("SELECT * FROM ledger WHERE deleted_at IS NULL AND is_default = 1 LIMIT 1")
    suspend fun findDefault(): LedgerEntity?

    /** 无默认排序账本时要从这份列表里挑接管者，所以必须给出确定的顺序。 */
    @Query("SELECT * FROM ledger WHERE deleted_at IS NULL ORDER BY sort_order ASC, id ASC")
    suspend fun findAllOnce(): List<LedgerEntity>

    @Query("SELECT COUNT(*) FROM ledger WHERE deleted_at IS NULL")
    suspend fun countActive(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(ledger: LedgerEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(ledgers: List<LedgerEntity>)

    @Update
    suspend fun update(ledger: LedgerEntity)

    @Query("UPDATE ledger SET is_default = 0, updated_at = :now WHERE deleted_at IS NULL")
    suspend fun clearDefault(now: Long)

    /** 默认账本全局唯一：清空与置位必须在同一事务内完成。 */
    @Transaction
    suspend fun setDefault(id: String, now: Long) {
        clearDefault(now)
        setDefaultFlag(id, now)
    }

    @Query("UPDATE ledger SET is_default = 1, updated_at = :now WHERE id = :id AND deleted_at IS NULL")
    suspend fun setDefaultFlag(id: String, now: Long)

    @Query(
        "UPDATE ledger SET is_archived = :archived, updated_at = :now, deleted_at = :deletedAt " +
            "WHERE id = :id AND deleted_at IS NULL",
    )
    suspend fun setArchived(id: String, archived: Boolean, now: Long, deletedAt: Long?)

    @Query(
        "SELECT EXISTS(SELECT 1 FROM ledger WHERE deleted_at IS NULL AND is_default = 1 LIMIT 1)",
    )
    fun hasDefault(): Flow<Boolean>
    @Query("SELECT * FROM ledger WHERE deleted_at IS NULL AND name = :name ORDER BY is_default DESC, sort_order ASC LIMIT 1")
    suspend fun findByName(name: String): LedgerEntity?
}

