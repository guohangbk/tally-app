package io.github.guohangbk.tally.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.guohangbk.tally.domain.model.TimePrecision
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID

enum class ThemeMode {
    LIGHT,
    SYSTEM,
    DARK,
}

/**
 * 设备本地偏好走 DataStore；需要随备份迁移的业务开关走 `app_setting` 表。
 */
class TallyPreferences(private val dataStore: DataStore<Preferences>) {
    object Keys {
        val DEVICE_ID = stringPreferencesKey("device_id")
        val BILL_IMAGE_ENABLED = booleanPreferencesKey("bill_image_enabled")
        val REIMBURSE_ENABLED = booleanPreferencesKey("reimburse_enabled")
        val ASSISTANT_ENABLED = booleanPreferencesKey("assistant_enabled")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val SHOW_CURRENCY_SYMBOL = booleanPreferencesKey("show_currency_symbol")
        val SHOW_THOUSANDS_SEPARATOR = booleanPreferencesKey("show_thousands_separator")
        val TIME_PRECISION = stringPreferencesKey("time_precision")
        val ONBOARDING_COMPLETED = booleanPreferencesKey("onboarding_completed")
        val LAST_CATEGORY_L1 = stringPreferencesKey("last_category_l1")
        val LAST_CATEGORY_L2 = stringPreferencesKey("last_category_l2")
        val WEBDAV_URL = stringPreferencesKey("webdav_url")
        val WEBDAV_USER = stringPreferencesKey("webdav_user")
        val WEBDAV_DIRECTORY = stringPreferencesKey("webdav_directory")
        val LAST_BACKUP_AT = stringPreferencesKey("last_backup_at")
        val CHILD_ICON_MIGRATED_V2 = booleanPreferencesKey("child_icon_migrated_v2")
        val DRAG_GUIDE_SEEN = booleanPreferencesKey("drag_guide_seen")
        val DEBUG_MODE = booleanPreferencesKey("debug_mode")
        val NOTIFICATIONS_ENABLED = booleanPreferencesKey("notifications_enabled")
        val NOTIFY_UNLOGGED = booleanPreferencesKey("notify_unlogged")
        val NOTIFY_RECURRING = booleanPreferencesKey("notify_recurring")
        val NOTIFY_INSTALLMENT = booleanPreferencesKey("notify_installment")
        val NOTIFY_FAILURE = booleanPreferencesKey("notify_failure")
        val DAILY_REMINDER_HOUR = intPreferencesKey("daily_reminder_hour")
        val SHOW_AMOUNT_ON_LOCKSCREEN = booleanPreferencesKey("show_amount_on_lockscreen")
        val POSTER_MASKED = booleanPreferencesKey("poster_masked")
        /** 未记账提醒的发信日（ISO 本地日期）。游标存本机，换机与恢复备份都不该把「今天已经提醒过」带过去。 */
        val LAST_UNLOGGED_REMINDER_AT = stringPreferencesKey("last_unlogged_reminder_at")
        /**
         * 预算提醒游标：每项形如 `ledgerId|PERIOD|tier|segment`，每个（账本·周期·档位）只留当前段那一条。
         * 没有这根游标，预算通知会在每次回前台重新弹（用户划掉又冒出来）；有了它，一段一档只发一次。
         */
        val BUDGET_ALERTS_SENT = stringSetPreferencesKey("budget_alerts_sent")
        /** 预算自动续期的上次执行段（当前月周期起始日的 ISO）。只在真的跑了一次后写，避免同月重复复制。 */
        val BUDGET_LAST_ROLLOVER_AT = stringPreferencesKey("budget_last_rollover_at")
    }

    val settings: Flow<TallySettings> = dataStore.data.map { prefs ->
        TallySettings(
            deviceId = prefs[Keys.DEVICE_ID] ?: "",
            billImageEnabled = prefs[Keys.BILL_IMAGE_ENABLED] ?: false,
            reimburseEnabled = prefs[Keys.REIMBURSE_ENABLED] ?: false,
            assistantEnabled = prefs[Keys.ASSISTANT_ENABLED] ?: false,
            themeMode = prefs[Keys.THEME_MODE]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() }
                ?: ThemeMode.SYSTEM,
            showCurrencySymbol = prefs[Keys.SHOW_CURRENCY_SYMBOL] ?: true,
            showThousandsSeparator = prefs[Keys.SHOW_THOUSANDS_SEPARATOR] ?: true,
            timePrecision = prefs[Keys.TIME_PRECISION]?.let { runCatching { TimePrecision.valueOf(it) }.getOrNull() }
                ?: TimePrecision.DATE,
            onboardingCompleted = prefs[Keys.ONBOARDING_COMPLETED] ?: false,
            dragGuideSeen = prefs[Keys.DRAG_GUIDE_SEEN] ?: false,
            lastCategoryL1Id = prefs[Keys.LAST_CATEGORY_L1]?.ifEmpty { null },
            lastCategoryL2Id = prefs[Keys.LAST_CATEGORY_L2]?.ifEmpty { null },
            webDavUrl = prefs[Keys.WEBDAV_URL] ?: "",
            webDavUser = prefs[Keys.WEBDAV_USER] ?: "",
            webDavDirectory = prefs[Keys.WEBDAV_DIRECTORY] ?: "tally",
            lastBackupAt = prefs[Keys.LAST_BACKUP_AT]?.toLongOrNull(),
            debugMode = prefs[Keys.DEBUG_MODE] ?: false,
            notificationsEnabled = prefs[Keys.NOTIFICATIONS_ENABLED] ?: true,
            notifyUnlogged = prefs[Keys.NOTIFY_UNLOGGED] ?: true,
            notifyRecurring = prefs[Keys.NOTIFY_RECURRING] ?: true,
            notifyInstallment = prefs[Keys.NOTIFY_INSTALLMENT] ?: true,
            notifyFailure = prefs[Keys.NOTIFY_FAILURE] ?: true,
            dailyReminderHour = prefs[Keys.DAILY_REMINDER_HOUR] ?: TallySettings.DEFAULT_REMINDER_HOUR,
            showAmountOnLockscreen = prefs[Keys.SHOW_AMOUNT_ON_LOCKSCREEN] ?: false,
        )
    }

    /** 设备标识用于同步预留字段，首次访问时生成并持久化。 */
    suspend fun deviceId(): String {
        val current = dataStore.data.map { it[Keys.DEVICE_ID] }.first()
        if (!current.isNullOrBlank()) return current
        val generated = UUID.randomUUID().toString()
        dataStore.edit { it[Keys.DEVICE_ID] = generated }
        return generated
    }

    suspend fun setBillImageEnabled(enabled: Boolean) = put(Keys.BILL_IMAGE_ENABLED, enabled)

    suspend fun setReimburseEnabled(enabled: Boolean) = put(Keys.REIMBURSE_ENABLED, enabled)

    suspend fun setAssistantEnabled(enabled: Boolean) = put(Keys.ASSISTANT_ENABLED, enabled)

    suspend fun setThemeMode(mode: ThemeMode) {
        dataStore.edit { it[Keys.THEME_MODE] = mode.name }
    }

    suspend fun setShowCurrencySymbol(show: Boolean) = put(Keys.SHOW_CURRENCY_SYMBOL, show)

    suspend fun setShowThousandsSeparator(show: Boolean) = put(Keys.SHOW_THOUSANDS_SEPARATOR, show)

    suspend fun setTimePrecision(precision: TimePrecision) {
        dataStore.edit { it[Keys.TIME_PRECISION] = precision.name }
    }

    suspend fun completeOnboarding() = put(Keys.ONBOARDING_COMPLETED, true)

    /** 隐藏手势的一次性引导：看过不再出现，设置里可重开（AC-MC-2）。 */
    suspend fun setDragGuideSeen(seen: Boolean) = put(Keys.DRAG_GUIDE_SEEN, seen)

    /**
     * 海报的脱敏开关属于这台设备：反复发图给同一个人时不该每次重开，
     * 也不该跟着备份跑到另一台机器上。
     */
    val posterMasked: Flow<Boolean> = dataStore.data.map { it[Keys.POSTER_MASKED] ?: false }

    suspend fun setPosterMasked(masked: Boolean) = put(Keys.POSTER_MASKED, masked)

    suspend fun setDebugMode(enabled: Boolean) = put(Keys.DEBUG_MODE, enabled)

    // ---- 通知 ------
    // 开关属于这台设备：换机、恢复备份都不该把「我在这台机器上不想被打扰」带过去，
    // 所以整组走 DataStore，不进 app_setting、不进 .tally。

    suspend fun setNotificationsEnabled(enabled: Boolean) = put(Keys.NOTIFICATIONS_ENABLED, enabled)

    suspend fun setNotifyUnlogged(enabled: Boolean) = put(Keys.NOTIFY_UNLOGGED, enabled)

    suspend fun setNotifyRecurring(enabled: Boolean) = put(Keys.NOTIFY_RECURRING, enabled)

    suspend fun setNotifyInstallment(enabled: Boolean) = put(Keys.NOTIFY_INSTALLMENT, enabled)

    suspend fun setNotifyFailure(enabled: Boolean) = put(Keys.NOTIFY_FAILURE, enabled)

    suspend fun setDailyReminderHour(hour: Int) {
        dataStore.edit { it[Keys.DAILY_REMINDER_HOUR] = hour.coerceIn(0, 23) }
    }

    suspend fun setShowAmountOnLockscreen(show: Boolean) = put(Keys.SHOW_AMOUNT_ON_LOCKSCREEN, show)

    /** 未记账提醒的每日游标：同一天只发一条，错过时刻由回前台补发。 */
    suspend fun lastUnloggedReminderDate(): String? =
        dataStore.data.map { it[Keys.LAST_UNLOGGED_REMINDER_AT] }.first()

    suspend fun markUnloggedReminder(date: String) {
        dataStore.edit { it[Keys.LAST_UNLOGGED_REMINDER_AT] = date }
    }

    /** 预算提醒：某（账本·周期·档位）本段是否已经发过。 */
    suspend fun budgetAlertsSent(): Set<String> =
        dataStore.data.map { it[Keys.BUDGET_ALERTS_SENT] ?: emptySet() }.first()

    /**
     * 记一笔预算提醒游标。写之前先把同一 `ledgerId|PERIOD|tier|` 前缀下的旧段清掉，
     * 让每个（账本·周期·档位）只保留当前段一条——既实现「一段一档只发一次」，又不会让集合无界增长。
     */
    suspend fun markBudgetAlertSent(key: String) {
        val prefix = key.substringBeforeLast('|') + '|'
        dataStore.edit { prefs ->
            val kept = (prefs[Keys.BUDGET_ALERTS_SENT] ?: emptySet()).filterNot { it.startsWith(prefix) }.toMutableSet()
            kept += key
            prefs[Keys.BUDGET_ALERTS_SENT] = kept
        }
    }

    /** 预算自动续期：上次为哪个周期段跑过。 */
    suspend fun lastBudgetRolloverAt(): String? =
        dataStore.data.map { it[Keys.BUDGET_LAST_ROLLOVER_AT] }.first()

    suspend fun markBudgetRolloverAt(segment: String) {
        dataStore.edit { it[Keys.BUDGET_LAST_ROLLOVER_AT] = segment }
    }

    suspend fun setWebDav(baseUrl: String, userName: String, directory: String) {
        dataStore.edit {
            it[Keys.WEBDAV_URL] = baseUrl.trim()
            it[Keys.WEBDAV_USER] = userName.trim()
            it[Keys.WEBDAV_DIRECTORY] = directory.trim().ifEmpty { "tally" }
        }
    }

    /** 最近一次成功导出的时间，用于「每周提醒导出备份」的判断。 */
    /** 二级独立图标的第二轮存量映射是否已跑过（11 个默认键）。 */
    suspend fun childIconsMigratedV2(): Boolean = dataStore.data.map { it[Keys.CHILD_ICON_MIGRATED_V2] ?: false }.first()

    suspend fun markChildIconsMigratedV2() = put(Keys.CHILD_ICON_MIGRATED_V2, true)

    suspend fun setLastBackupAt(epochMillis: Long) {
        dataStore.edit { it[Keys.LAST_BACKUP_AT] = epochMillis.toString() }
    }

    suspend fun rememberLastCategory(l1Id: String?, l2Id: String?) {
        if (l1Id == null) return
        dataStore.edit {
            it[Keys.LAST_CATEGORY_L1] = l1Id
            it[Keys.LAST_CATEGORY_L2] = l2Id ?: ""
        }
    }

    /** 分类全机共用，记忆也就只有一对全局键，不再按账本分叉。 */
    suspend fun lastCategory(): Pair<String?, String?> {
        val prefs = dataStore.data.first()
        val l1 = prefs[Keys.LAST_CATEGORY_L1]?.ifEmpty { null }
        val l2 = prefs[Keys.LAST_CATEGORY_L2]?.ifEmpty { null }
        return l1 to l2
    }

    private suspend fun put(key: Preferences.Key<Boolean>, value: Boolean) {
        dataStore.edit { it[key] = value }
    }

    companion object {
        private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "tally_settings")

        fun from(context: Context): TallyPreferences = TallyPreferences(context.applicationContext.dataStore)
    }
}

data class TallySettings(
    val deviceId: String,
    val billImageEnabled: Boolean,
    val reimburseEnabled: Boolean,
    val assistantEnabled: Boolean,
    val themeMode: ThemeMode,
    val showCurrencySymbol: Boolean,
    val showThousandsSeparator: Boolean,
    val timePrecision: TimePrecision,
    val onboardingCompleted: Boolean,
    val dragGuideSeen: Boolean,
    val lastCategoryL1Id: String?,
    val lastCategoryL2Id: String?,
    val webDavUrl: String,
    val webDavUser: String,
    val webDavDirectory: String,
    val lastBackupAt: Long?,
    /** 调试模式：设置里连点包名 5 次开启，开启后每个页面都能调出控制台。 */
    val debugMode: Boolean = false,
    /** 通知总开关：关掉之后四类一条都不发，App 内也不留红点。 */
    val notificationsEnabled: Boolean = true,
    val notifyUnlogged: Boolean = true,
    val notifyRecurring: Boolean = true,
    val notifyInstallment: Boolean = true,
    val notifyFailure: Boolean = true,
    /** 每日未记账提醒的时刻（本地整点），错过则回前台补发。 */
    val dailyReminderHour: Int = DEFAULT_REMINDER_HOUR,
    /** 锁屏与预览态要不要露出金额；默认关，关着就只显示「今天还没记账」这类句子。 */
    val showAmountOnLockscreen: Boolean = false,
) {
    companion object {
        /** 每日未记账提醒的默认时刻：晚上八点，设计板 T-01 定的那个。 */
        const val DEFAULT_REMINDER_HOUR = 20

        val DEFAULT = TallySettings(
            deviceId = "",
            billImageEnabled = false,
            reimburseEnabled = false,
            assistantEnabled = false,
            themeMode = ThemeMode.SYSTEM,
            showCurrencySymbol = true,
            showThousandsSeparator = true,
            timePrecision = TimePrecision.DATE,
            onboardingCompleted = false,
            dragGuideSeen = false,
            lastCategoryL1Id = null,
            lastCategoryL2Id = null,
            webDavUrl = "",
            webDavUser = "",
            webDavDirectory = "tally",
            lastBackupAt = null,
            debugMode = false,
        )
    }
}
