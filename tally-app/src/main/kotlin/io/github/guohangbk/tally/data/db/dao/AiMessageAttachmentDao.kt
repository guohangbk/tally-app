package io.github.guohangbk.tally.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.github.guohangbk.tally.data.db.entity.AiMessageAttachmentEntity
import kotlinx.coroutines.flow.Flow

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

/** 一条消息的缩略图位置；气泡回显只查这两列，不把整行附件拉进消息流。 */
data class MessageThumb(val messageId: String, val relativePath: String)
