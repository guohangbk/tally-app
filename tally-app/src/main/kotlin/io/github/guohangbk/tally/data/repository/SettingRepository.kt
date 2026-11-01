package io.github.guohangbk.tally.data.repository

import io.github.guohangbk.tally.data.db.dao.AppSettingDao
import io.github.guohangbk.tally.data.db.entity.AppSettingEntity
import io.github.guohangbk.tally.domain.Period
import io.github.guohangbk.tally.domain.model.RecycleCleanupReport
import io.github.guohangbk.tally.domain.model.RecycleKeepDays
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 需要随备份迁移的业务开关存 `app_setting` 表；设备本地显示偏好存 DataStore。
 */
class SettingRepository(
    private val dao: AppSettingDao,
    private val now: () -> Long = System::currentTimeMillis,
) {
    object Keys {
        const val DEFAULT_CURRENCY = "default_currency"
        const val RECYCLE_KEEP_DAYS = "recycle_keep_days"
        const val RECYCLE_LAST_CLEANUP = "recycle_last_cleanup"
        const val MONTH_START_DAY = "month_start_day"
    }

    val defaultCurrency: Flow<String> = dao.observe(Keys.DEFAULT_CURRENCY).map { it?.value ?: "CNY" }

    /** 每月起始日：随备份走，换机后同一个月的合计不该变。没写过就是 1 号。 */
    val monthStartDay: Flow<Int> = dao.observe(Keys.MONTH_START_DAY).map { entity ->
        entity?.value?.toIntOrNull()?.let(Period::normalizeStartDay) ?: Period.DEFAULT_START_DAY
    }

    suspend fun setDefaultCurrency(currency: String) = put(Keys.DEFAULT_CURRENCY, currency)

    suspend fun monthStartDay(): Int =
        dao.find(Keys.MONTH_START_DAY)?.value?.toIntOrNull()?.let(Period::normalizeStartDay)
            ?: Period.DEFAULT_START_DAY

    suspend fun setMonthStartDay(day: Int) =
        put(Keys.MONTH_START_DAY, Period.normalizeStartDay(day).toString())

    /** 没写过这行 = 用户还没选过 = 默认 30 天；「永不清理」是显式写进去的 `never`。 */
    suspend fun recycleKeepDays(): RecycleKeepDays =
        RecycleKeepDays.fromKey(dao.find(Keys.RECYCLE_KEEP_DAYS)?.value)

    suspend fun setRecycleKeepDays(days: RecycleKeepDays) = put(Keys.RECYCLE_KEEP_DAYS, days.key)

    /** 上次清理的产出；读不出结构就当从没清理过，不在界面上编一个数字。 */
    suspend fun lastRecycleCleanup(): RecycleCleanupReport? =
        RecycleCleanupReport.parse(dao.find(Keys.RECYCLE_LAST_CLEANUP)?.value)

    suspend fun recordRecycleCleanup(report: RecycleCleanupReport) =
        put(Keys.RECYCLE_LAST_CLEANUP, report.store())

    private suspend fun put(key: String, value: String) {
        dao.put(AppSettingEntity(key = key, value = value, updatedAt = now()))
    }
}
