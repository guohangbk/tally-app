package io.github.guohangbk.tally.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.github.guohangbk.tally.data.db.entity.AttachmentEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AttachmentDao {
    @Query("SELECT IFNULL(SUM(size_bytes), 0) FROM attachment WHERE deleted_at IS NULL")
    fun observeTotalBytes(): Flow<Long>

    @Query("SELECT COUNT(*) FROM attachment WHERE deleted_at IS NULL")
    fun observeActiveCount(): Flow<Int>

    @Query("SELECT * FROM attachment WHERE bill_id = :billId AND deleted_at IS NULL ORDER BY created_at ASC")
    suspend fun findByBill(billId: String): List<AttachmentEntity>

    /** 图片指纹去重：同一张截图被重复分享时不再复制第二份文件。 */
    @Query("SELECT * FROM attachment WHERE sha256 = :sha256 AND deleted_at IS NULL LIMIT 1")
    suspend fun findBySha(sha256: String): AttachmentEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(attachment: AttachmentEntity)

    @Query("UPDATE attachment SET deleted_at = :now WHERE bill_id = :billId AND deleted_at IS NULL")
    suspend fun softDeleteByBill(billId: String, now: Long): Int

    /** 账单批量删除 / 撤销：附件跟着账单走，不然回收任务永远看不到它们。 */
    @Query("UPDATE attachment SET deleted_at = :now WHERE bill_id IN (:billIds) AND deleted_at IS NULL")
    suspend fun softDeleteByBills(billIds: List<String>, now: Long): Int

    @Query("UPDATE attachment SET deleted_at = NULL WHERE bill_id IN (:billIds) AND deleted_at IS NOT NULL")
    suspend fun restoreByBills(billIds: List<String>): Int

    /** 清理任务：软删已超过保留期的行，按最早删的先处理。 */
    @Query(
        "SELECT * FROM attachment WHERE deleted_at IS NOT NULL AND deleted_at <= :cutoff " +
            "ORDER BY deleted_at ASC LIMIT :limit",
    )
    suspend fun findExpired(cutoff: Long, limit: Int): List<AttachmentEntity>

    /** 同一张图可以被多笔账单共享（sha256 去重），只要还有一笔在用就不能删文件。 */
    @Query("SELECT COUNT(*) FROM attachment WHERE relative_path = :path AND deleted_at IS NULL")
    suspend fun liveCountForPath(path: String): Int

    // ---- 彻底删除 ------

    /**
     * 这几笔账单挂着的附件行。**不过滤 deleted_at**——回收站里的账单，它的附件行
     * 是软删状态，按「未删」筛就会漏，文件于是永远留在磁盘上。
     */
    @Query("SELECT * FROM attachment WHERE bill_id IN (:billIds)")
    suspend fun findAllForBills(billIds: List<String>): List<AttachmentEntity>

    /**
     * 这张图还被**别人**引用吗。集合外的行无论软删与否都算引用：
     * 另一笔可能也躺在回收站里，将来恢复还要它。
     */
    @Query("SELECT COUNT(*) FROM attachment WHERE relative_path = :path AND bill_id NOT IN (:billIds)")
    suspend fun referencesOutside(path: String, billIds: List<String>): Int

    @Query("DELETE FROM attachment WHERE bill_id IN (:billIds)")
    suspend fun hardDeleteByBills(billIds: List<String>): Int

    @Query("DELETE FROM attachment WHERE id IN (:ids)")
    suspend fun hardDelete(ids: List<String>)

    @Query("SELECT * FROM attachment WHERE bill_id IN (:billIds) AND deleted_at IS NULL ORDER BY created_at ASC")
    suspend fun findByBillIds(billIds: List<String>): List<AttachmentEntity>
}

