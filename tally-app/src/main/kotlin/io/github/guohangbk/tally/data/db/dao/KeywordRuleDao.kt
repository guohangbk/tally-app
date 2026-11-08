package io.github.guohangbk.tally.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.github.guohangbk.tally.data.db.entity.KeywordRuleEntity
import kotlinx.coroutines.flow.Flow

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

    /** 撤销删除：只把刚被软删的这几行原样救回来，别的字段一概不动。 */
    @Query("UPDATE keyword_rule SET deleted_at = NULL, updated_at = :now WHERE id IN (:ids)")
    suspend fun restore(ids: List<String>, now: Long): Int

    @Query("UPDATE keyword_rule SET category_id = :categoryId, updated_at = :now WHERE id = :id AND deleted_at IS NULL")
    suspend fun updateCategory(id: String, categoryId: String, now: Long): Int

    @Query("UPDATE keyword_rule SET deleted_at = :now WHERE category_id IN (:categoryIds) AND deleted_at IS NULL")
    suspend fun softDeleteByCategories(categoryIds: List<String>, now: Long): Int
}
