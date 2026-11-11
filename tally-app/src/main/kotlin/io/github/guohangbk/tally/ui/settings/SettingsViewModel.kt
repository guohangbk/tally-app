package io.github.guohangbk.tally.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.guohangbk.tally.data.preferences.ThemeMode
import io.github.guohangbk.tally.data.preferences.TallySettings
import io.github.guohangbk.tally.data.update.UpdateCheck
import io.github.guohangbk.tally.di.AppGraph
import io.github.guohangbk.tally.domain.model.TallyError
import io.github.guohangbk.tally.domain.model.TimePrecision
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 需要二次确认的动作；目前只有「恢复默认偏好」。 */
enum class SettingsDialogKind {
    RESET_PREFERENCES,
}

/** 整点一律写成 08:00 而不是 8:00，两位对齐后设置页与通知里读起来是同一个时刻。 */
internal fun hourLabel(hour: Int): String = "${hour.coerceIn(0, 23).toString().padStart(2, '0')}:00"

data class SettingsUiState(
    val loaded: Boolean = false,
    val settings: TallySettings = TallySettings.DEFAULT,
    val defaultCurrency: String = "CNY",
    /** 月周期起始日：1–28，默认 1 号。改动立刻影响首页与统计的月份切分。 */
    val monthStartDay: Int = 1,
    val error: String? = null,
    val notice: String? = null,
    val dialog: SettingsDialogKind? = null,
    /** 「检查更新」进行中：按钮变成「检查中…」并暂时点不动。 */
    val checkingUpdate: Boolean = false,
    /** 查到比安装包新的版本才非空，弹窗由它驱动。 */
    val updateAvailable: UpdateCheck.Available? = null,
)

/**
 * 设置页唯一的写入通道：设备本地偏好走 [TallyPreferences]（DataStore），
 * 需要随备份迁移的业务开关走 [SettingRepository]（app_setting 表）。
 */
class SettingsViewModel(private val graph: AppGraph) : ViewModel() {
    private val errorState = MutableStateFlow<String?>(null)
    private val noticeState = MutableStateFlow<String?>(null)
    private val dialogState = MutableStateFlow<SettingsDialogKind?>(null)
    private val checkingUpdate = MutableStateFlow(false)
    private val updateAvailable = MutableStateFlow<UpdateCheck.Available?>(null)

    private data class Signals(
        val error: String?,
        val notice: String?,
        val dialog: SettingsDialogKind?,
        val checkingUpdate: Boolean,
        val updateAvailable: UpdateCheck.Available?,
    )

    private val business = combine(
        graph.settingRepository.defaultCurrency,
        graph.settingRepository.monthStartDay,
    ) { currency, startDay -> currency to startDay }

    private val signals = combine(
        errorState,
        noticeState,
        dialogState,
        checkingUpdate,
        updateAvailable,
    ) { error, notice, dialog, checking, update ->
        Signals(error, notice, dialog, checking, update)
    }

    val state: StateFlow<SettingsUiState> = combine(
        graph.preferences.settings,
        business,
        signals,
    ) { settings, (currency, startDay), ui ->
        SettingsUiState(
            loaded = true,
            settings = settings,
            defaultCurrency = currency,
            monthStartDay = startDay,
            error = ui.error,
            notice = ui.notice,
            dialog = ui.dialog,
            checkingUpdate = ui.checkingUpdate,
            updateAvailable = ui.updateAvailable,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    fun setMonthStartDay(day: Int) = persist(
        notice = "每月从 ${day.coerceIn(1, 28)} 号起算 · 首页与统计已按新周期分段",
    ) {
        graph.settingRepository.setMonthStartDay(day)
    }

    fun setBillImageEnabled(enabled: Boolean) = persist { graph.preferences.setBillImageEnabled(enabled) }

    fun setQuickOpenEnabled(enabled: Boolean) = persist(
        notice = if (enabled) "秒开模式已开启 · 下次打开应用直接进记账页" else "秒开模式已关闭 · 打开应用先进首页",
    ) {
        graph.preferences.setQuickOpenEnabled(enabled)
    }

    fun setReimburseEnabled(enabled: Boolean) = persist { graph.preferences.setReimburseEnabled(enabled) }

    fun setExcludeEnabled(enabled: Boolean) = persist { graph.preferences.setExcludeEnabled(enabled) }

    fun setTagEnabled(enabled: Boolean) = persist { graph.preferences.setTagEnabled(enabled) }

    fun setThemeMode(mode: ThemeMode) = persist { graph.preferences.setThemeMode(mode) }

    fun setTimePrecision(precision: TimePrecision) = persist { graph.preferences.setTimePrecision(precision) }

    /** 调试模式：设置 → 关于 → 版本，1.2 秒内连点 5 次开关，开启后每个页面右下角会出现控制台。 */
    fun setDebugMode(enabled: Boolean) = persist(
        notice = if (enabled) "调试模式已开启 · 每个页面右下角可以调出控制台" else "调试模式已关闭",
    ) {
        graph.preferences.setDebugMode(enabled)
    }

    fun setShowCurrencySymbol(show: Boolean) = persist { graph.preferences.setShowCurrencySymbol(show) }

    fun setShowThousandsSeparator(show: Boolean) = persist { graph.preferences.setShowThousandsSeparator(show) }

    // ---- 提醒与通知 ------
    // 整组都是设备本地偏好：换机或恢复备份不该把「我在这台机器上不想被打扰」带过去。

    /** 关掉总开关时把通知栏里还挂着的一条都收掉：只留开关状态、留着旧通知等于说话不算数。 */
    fun setNotificationsEnabled(enabled: Boolean) = persist {
        graph.preferences.setNotificationsEnabled(enabled)
        if (!enabled) graph.tallyNotifier.cancelAll()
    }

    fun setNotifyUnlogged(enabled: Boolean) = persist { graph.preferences.setNotifyUnlogged(enabled) }

    fun setNotifyRecurring(enabled: Boolean) = persist { graph.preferences.setNotifyRecurring(enabled) }

    fun setNotifyInstallment(enabled: Boolean) = persist { graph.preferences.setNotifyInstallment(enabled) }

    fun setNotifyFailure(enabled: Boolean) = persist { graph.preferences.setNotifyFailure(enabled) }

    fun setDailyReminderHour(hour: Int) = persist(notice = "每日提醒改到 ${hourLabel(hour)}") {
        graph.preferences.setDailyReminderHour(hour)
    }

    fun setShowAmountOnLockscreen(show: Boolean) = persist { graph.preferences.setShowAmountOnLockscreen(show) }

    /**
     * 小莉的唯一开关，开与关都直接落库。
     *
     * 早先这里是「没通过连接测试就拒绝开启」，理由是当时还没有配置页；现在配置页在了，
     * 这一拒绝反而变成界面上一个点不动的开关。开关本来也不该承担校验职责：
     * 开与没开只决定 ＋ 右臂把人送到哪一页（`AssistantEntry`），一次请求都不会发出去。
     */
    fun setAssistantEnabled(enabled: Boolean) {
        persist(
            notice = if (enabled) {
                "小莉已开启 · 长按 ＋ 右滑去配置，配好就能聊"
            } else {
                "小莉已关闭 · 右滑仍会带你到这里的开关"
            },
        ) {
            // 沿用配置仓的口径：重新打开时，配置齐全但从未验证过的记为「疑似异常」，让下一次调用去确认。
            graph.aiConfigRepository.setEnabled(enabled)
        }
    }

    // ---- 检查更新 ------
    // 只在用户点按钮时打一次公开仓的 latest release：不轮询、不自动下载、不静默安装。

    fun checkForUpdate(installedCode: Long, installedLabel: String) {
        if (checkingUpdate.value) return
        checkingUpdate.value = true
        viewModelScope.launch {
            try {
                when (val result = graph.updateChecker.check(installedCode)) {
                    is UpdateCheck.Available -> updateAvailable.value = result
                    is UpdateCheck.UpToDate ->
                        noticeState.value = "已经是最新版本 $installedLabel，安心用。"
                    UpdateCheck.Failed ->
                        noticeState.value = "网络好像不太顺，过一会儿再试试。"
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } finally {
                checkingUpdate.value = false
            }
        }
    }

    fun dismissUpdate() {
        updateAvailable.value = null
    }

    fun requestResetPreferences() {
        errorState.value = null
        dialogState.value = SettingsDialogKind.RESET_PREFERENCES
    }

    fun confirmResetPreferences() {
        persist(
            notice = "偏好已恢复默认 · 账单、账本、分类和图片都没有改动。",
            onSaved = ::dismissDialog,
        ) {
            graph.preferences.setBillImageEnabled(TallySettings.DEFAULT.billImageEnabled)
            graph.preferences.setQuickOpenEnabled(TallySettings.DEFAULT.quickOpenEnabled)
            graph.preferences.setReimburseEnabled(TallySettings.DEFAULT.reimburseEnabled)
            graph.preferences.setExcludeEnabled(TallySettings.DEFAULT.excludeEnabled)
            graph.preferences.setTagEnabled(TallySettings.DEFAULT.tagEnabled)
            graph.preferences.setThemeMode(TallySettings.DEFAULT.themeMode)
            graph.preferences.setShowCurrencySymbol(TallySettings.DEFAULT.showCurrencySymbol)
            graph.preferences.setShowThousandsSeparator(TallySettings.DEFAULT.showThousandsSeparator)
            graph.preferences.setTimePrecision(TallySettings.DEFAULT.timePrecision)
            graph.preferences.setAssistantEnabled(TallySettings.DEFAULT.assistantEnabled)
        }
    }

    fun dismissDialog() {
        dialogState.value = null
    }

    fun dismissError() {
        errorState.value = null
    }

    fun dismissNotice() {
        noticeState.value = null
    }

    private fun persist(
        notice: String? = null,
        onSaved: () -> Unit = {},
        operation: suspend () -> Unit,
    ) {
        viewModelScope.launch {
            try {
                operation()
                notice?.let { noticeState.value = it }
                onSaved()
            } catch (tallyError: TallyError) {
                errorState.value = tallyError.message
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                errorState.value = failure.message ?: "这项设置没有保存成功，请重试。"
            }
        }
    }
}
