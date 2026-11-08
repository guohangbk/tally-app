package io.github.guohangbk.tally.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import io.github.guohangbk.tally.data.db.entity.AiReportEntity
import kotlinx.coroutines.flow.Flow

/**
 * 周期报告按 (ledger_id, month_key) 取最新，`month_key` 是 `2026-10-01_2026-10-20` 这种两端含当天的区间；
 * 按 input_sha256 唯一判缓存命中：本地汇总没变就不该再花一次额度。
 */
@Dao
interface AiReportDao {
    @Query("SELECT * FROM ai_report WHERE deleted_at IS NULL ORDER BY created_at DESC LIMIT 12")
    fun observeRecent(): Flow<List<AiReportEntity>>

    @Query(
        """
        SELECT * FROM ai_report
        WHERE deleted_at IS NULL AND ledger_id = :ledgerId AND month_key = :monthKey
        ORDER BY created_at DESC LIMIT 1
        """,
    )
    suspend fun findLatest(ledgerId: String, monthKey: String): AiReportEntity?

    @Query("SELECT * FROM ai_report WHERE id = :id AND deleted_at IS NULL LIMIT 1")
    suspend fun findById(id: String): AiReportEntity?

    @Query("SELECT * FROM ai_report WHERE input_sha256 = :digest AND deleted_at IS NULL LIMIT 1")
    suspend fun findByDigest(digest: String): AiReportEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(report: AiReportEntity)

    @Update
    suspend fun update(report: AiReportEntity)

    @Query("UPDATE ai_report SET deleted_at = :now, updated_at = :now WHERE id = :id AND deleted_at IS NULL")
    suspend fun softDelete(id: String, now: Long): Int
}
