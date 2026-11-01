package io.github.guohangbk.tally.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import io.github.guohangbk.tally.data.db.entity.AiMessageAttachmentEntity
import io.github.guohangbk.tally.data.db.entity.AiReportEntity
import io.github.guohangbk.tally.data.db.entity.KeywordRuleEntity
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

/** 只有用户明确要求长期保留的对话图片才有这一行；临时截图不入库。 */
@Dao
interface AiMessageAttachmentDao {
    @Query("SELECT * FROM ai_message_attachment WHERE message_id = :messageId AND deleted_at IS NULL")
    suspend fun findByMessage(messageId: String): List<AiMessageAttachmentEntity>

    /** 消息流要一次性把缩略图路径带上，所以按会话整批取，不在映射里逐条查库。rowid 保插入顺序：一条消息的几张图按选图顺序排。 */
    @Query(
        """
        SELECT message_id AS messageId, relative_path AS relativePath FROM ai_message_attachment
        WHERE deleted_at IS NULL
          AND message_id IN (SELECT id FROM ai_message WHERE conversation_id = :conversationId AND deleted_at IS NULL)
        ORDER BY rowid
        """,
    )
    fun observeThumbsForConversation(conversationId: String): Flow<List<MessageThumb>>

    @Query(
        """
        SELECT * FROM ai_message_attachment
        WHERE deleted_at IS NULL AND sha256 = :sha256 LIMIT 1
        """,
    )
    suspend fun findBySha(sha256: String): AiMessageAttachmentEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(attachment: AiMessageAttachmentEntity)

    @Query(
        """
        UPDATE ai_message_attachment SET deleted_at = :now, updated_at = :now
        WHERE message_id IN (SELECT id FROM ai_message WHERE conversation_id = :conversationId)
          AND deleted_at IS NULL
        """,
    )
    suspend fun softDeleteByConversation(conversationId: String, now: Long): Int
}

/**
 * 端侧文本分类关键词（用户可编辑，内置词表随 App 附带）。
 * 内置词表不落库，只在解析时与这里的用户词表合并，用户词表优先级更高。
 * 全机一套，不按账本隔离——分类本身也是全机的，按本隔离只会让同一句话在不同本里给出不同分类。
 */
@Dao
interface KeywordRuleDao {
    @Query("SELECT * FROM keyword_rule WHERE deleted_at IS NULL ORDER BY priority DESC, keyword ASC")
    fun observeAll(): Flow<List<KeywordRuleEntity>>

    @Query("SELECT * FROM keyword_rule WHERE deleted_at IS NULL")
    suspend fun findAll(): List<KeywordRuleEntity>

    /** 同名即同一条：全机一套之后没有「各本一份」可言。 */
    @Query("SELECT * FROM keyword_rule WHERE deleted_at IS NULL AND keyword = :keyword LIMIT 1")
    suspend fun findKeyword(keyword: String): KeywordRuleEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(rule: KeywordRuleEntity)

    @Query("UPDATE keyword_rule SET deleted_at = :now, updated_at = :now WHERE id = :id AND deleted_at IS NULL")
    suspend fun softDelete(id: String, now: Long): Int

    @Query("UPDATE keyword_rule SET category_id = :categoryId, updated_at = :now WHERE id = :id AND deleted_at IS NULL")
    suspend fun updateCategory(id: String, categoryId: String, now: Long): Int

    @Query("UPDATE keyword_rule SET deleted_at = :now WHERE category_id IN (:categoryIds) AND deleted_at IS NULL")
    suspend fun softDeleteByCategories(categoryIds: List<String>, now: Long): Int
}

/** 一条消息的缩略图位置；气泡回显只查这两列，不把整行附件拉进消息流。 */
data class MessageThumb(val messageId: String, val relativePath: String)
