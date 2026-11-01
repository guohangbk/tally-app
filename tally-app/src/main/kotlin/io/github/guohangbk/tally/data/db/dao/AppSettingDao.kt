package io.github.guohangbk.tally.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.github.guohangbk.tally.data.db.entity.AppSettingEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AppSettingDao {
    @Query("SELECT * FROM app_setting WHERE setting_key = :key")
    fun observe(key: String): Flow<AppSettingEntity?>

    @Query("SELECT * FROM app_setting WHERE setting_key = :key")
    suspend fun find(key: String): AppSettingEntity?

    @Query("SELECT * FROM app_setting")
    suspend fun findAll(): List<AppSettingEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(setting: AppSettingEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putAll(settings: List<AppSettingEntity>)

    @Query("DELETE FROM app_setting WHERE setting_key = :key")
    suspend fun remove(key: String)
}
