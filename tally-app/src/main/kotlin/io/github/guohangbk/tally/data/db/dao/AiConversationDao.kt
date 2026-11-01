package io.github.guohangbk.tally.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import io.github.guohangbk.tally.data.db.entity.AiConversationEntity
import io.github.guohangbk.tally.data.db.entity.AiMessageEntity
import kotlinx.coroutines.flow.Flow

/**
 * 会话与消息。对话删除只做软删除，账单与账单附件不受影响；
 * 排序靠 ai_message.sequence，不用 created_at 排，避免同一毫秒内的两条消息乱序。
 */
@Dao
interface AiConversationDao {
    @Query("SELECT * FROM ai_conversation WHERE deleted_at IS NULL ORDER BY updated_at DESC")
    fun observeAll(): Flow<List<AiConversationEntity>>

    /**
     * 最近一段对话：小莉进来就该接上它。归档这个概念已经撤了（没人用），
     * 所以旧的 archived_at 不再参与筛选——那些被归档过的对话本来也一直显示在列表里。
     */
    @Query("SELECT * FROM ai_conversation WHERE deleted_at IS NULL ORDER BY updated_at DESC LIMIT 1")
    suspend fun findLatest(): AiConversationEntity?

    @Query("SELECT * FROM ai_conversation WHERE id = :id AND deleted_at IS NULL LIMIT 1")
    suspend fun findById(id: String): AiConversationEntity?

    @Query("SELECT COUNT(*) FROM ai_conversation WHERE deleted_at IS NULL")
    fun observeActiveCount(): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(conversation: AiConversationEntity)

    @Update
    suspend fun update(conversation: AiConversationEntity)

    @Query("UPDATE ai_conversation SET title = :title, updated_at = :now, version = version + 1 WHERE id = :id")
    suspend fun rename(id: String, title: String, now: Long)

    @Query("UPDATE ai_conversation SET context_summary = :summary, updated_at = :now WHERE id = :id")
    suspend fun updateSummary(id: String, summary: String?, now: Long)

    @Query("UPDATE ai_conversation SET deleted_at = :now, updated_at = :now WHERE id = :id AND deleted_at IS NULL")
    suspend fun softDelete(id: String, now: Long): Int
}

@Dao
interface AiMessageDao {
    @Query("SELECT * FROM ai_message WHERE conversation_id = :conversationId AND deleted_at IS NULL ORDER BY sequence ASC")
    fun observeForConversation(conversationId: String): Flow<List<AiMessageEntity>>

    @Query(
        """
        SELECT * FROM ai_message
        WHERE conversation_id = :conversationId AND deleted_at IS NULL
        ORDER BY sequence DESC LIMIT :limit
        """,
    )
    suspend fun latestForConversation(conversationId: String, limit: Int): List<AiMessageEntity>

    @Query(
        """
        SELECT IFNULL(MAX(sequence), 0) FROM ai_message
        WHERE conversation_id = :conversationId AND deleted_at IS NULL
        """,
    )
    suspend fun maxSequence(conversationId: String): Int

    @Query("SELECT COUNT(*) FROM ai_message WHERE conversation_id = :conversationId AND deleted_at IS NULL")
    suspend fun countForConversation(conversationId: String): Int

    @Query("SELECT * FROM ai_message WHERE id = :id AND deleted_at IS NULL LIMIT 1")
    suspend fun findById(id: String): AiMessageEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(message: AiMessageEntity)

    @Update
    suspend fun update(message: AiMessageEntity)

    @Query(
        """
        UPDATE ai_message
        SET content = :content, status = :status, error_code = :errorCode, message_type = :messageType,
            updated_at = :now, version = version + 1
        WHERE id = :id
        """,
    )
    suspend fun finish(id: String, content: String, status: String, errorCode: String?, messageType: String, now: Long)

    @Query("UPDATE ai_message SET deleted_at = :now, updated_at = :now WHERE id = :id AND deleted_at IS NULL")
    suspend fun softDelete(id: String, now: Long): Int

    @Query("UPDATE ai_message SET deleted_at = :now, updated_at = :now WHERE conversation_id = :conversationId AND deleted_at IS NULL")
    suspend fun softDeleteByConversation(conversationId: String, now: Long): Int
}
