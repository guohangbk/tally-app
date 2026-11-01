package io.github.guohangbk.tally.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.github.guohangbk.tally.data.db.entity.AiConfigEntity
import kotlinx.coroutines.flow.Flow

/**
 * 小莉配置可以有多条，但一次只有一条生效（`active` 为真）。
 * 下面两条查询都按「生效的优先、其次最近改的」取一条，所以单条配置时的行为与以前完全一致，
 * 多条时天然落在生效的那条上。状态列就是状态机的落库形态，页面读它而不是各自判断
 * 「有没有 Key」「上次是不是失败」。
 */
@Dao
interface AiConfigDao {
    @Query("SELECT * FROM ai_config ORDER BY active DESC, updated_at DESC LIMIT 1")
    fun observe(): Flow<AiConfigEntity?>

    @Query("SELECT * FROM ai_config ORDER BY active DESC, updated_at DESC LIMIT 1")
    suspend fun find(): AiConfigEntity?

    /** 全部配置，配置页的列表就靠它：生效的在前，其次最近改的。 */
    @Query("SELECT * FROM ai_config ORDER BY active DESC, updated_at DESC")
    fun observeAll(): Flow<List<AiConfigEntity>>

    @Query("SELECT * FROM ai_config WHERE id = :id")
    suspend fun findById(id: String): AiConfigEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(config: AiConfigEntity)

    /** 让某一条生效前先把其他条摘掉：一次只有一条为真。 */
    @Query("UPDATE ai_config SET active = 0 WHERE id != :id")
    suspend fun clearOthersActive(id: String)

    @Query("DELETE FROM ai_config WHERE id = :id")
    suspend fun delete(id: String)
}
