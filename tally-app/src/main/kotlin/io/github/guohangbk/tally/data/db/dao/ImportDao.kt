package io.github.guohangbk.tally.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.github.guohangbk.tally.data.db.entity.BillEntity
import io.github.guohangbk.tally.data.db.entity.ImportBatchEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ImportBatchDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(batch: ImportBatchEntity)

    @Query("SELECT * FROM import_batch WHERE id = :id AND deleted_at IS NULL LIMIT 1")
    suspend fun findById(id: String): ImportBatchEntity?

    @Query("SELECT * FROM import_batch WHERE status = 'RUNNING' AND deleted_at IS NULL ORDER BY started_at DESC")
    suspend fun findRunning(): List<ImportBatchEntity>

    @Query("SELECT * FROM import_batch WHERE deleted_at IS NULL ORDER BY started_at DESC LIMIT 20")
    fun observeRecent(): Flow<List<ImportBatchEntity>>
}

@Dao
interface ImportBillDao {
    @Query("SELECT * FROM bill WHERE external_id = :externalId AND deleted_at IS NULL LIMIT 1")
    suspend fun findByExternalId(externalId: String): BillEntity?

    @Query("SELECT * FROM bill WHERE fingerprint = :fingerprint AND deleted_at IS NULL LIMIT 1")
    suspend fun findByFingerprint(fingerprint: String): BillEntity?

    @Query("SELECT COUNT(*) FROM bill WHERE deleted_at IS NULL")
    suspend fun countActive(): Int

    /** 导入预览一次取齐判重键，不逐行查库。 */
    @Query("SELECT external_id FROM bill WHERE deleted_at IS NULL AND external_id IS NOT NULL")
    suspend fun allExternalIds(): List<String>

    @Query("SELECT fingerprint FROM bill WHERE deleted_at IS NULL AND fingerprint IS NOT NULL")
    suspend fun allFingerprints(): List<String>

    @Query("UPDATE bill SET deleted_at = :now, updated_at = :now WHERE id IN (:ids) AND deleted_at IS NULL")
    suspend fun softDeleteIds(ids: List<String>, now: Long): Int
}
