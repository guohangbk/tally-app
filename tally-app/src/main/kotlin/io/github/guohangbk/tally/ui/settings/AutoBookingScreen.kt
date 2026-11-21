package io.github.guohangbk.tally.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import io.github.guohangbk.tally.data.notify.NotificationAccess
import io.github.guohangbk.tally.data.preferences.TallySettings
import io.github.guohangbk.tally.domain.model.AiStatus
import io.github.guohangbk.tally.di.AppGraph
import io.github.guohangbk.tally.di.rememberAppGraph
import io.github.guohangbk.tally.ui.common.tallyViewModel
import io.github.guohangbk.tally.ui.design.TallyToast
import io.github.guohangbk.tally.ui.design.TallyType
import io.github.guohangbk.tally.ui.design.ToastTone
import io.github.guohangbk.tally.ui.design.tallyColors
import io.github.guohangbk.tally.ui.shell.TallyTopBar
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 自动记账：机器记账这一块的总开关页。
 *
 * 上半是「自动入口」——通知抓取与待确认角标，开关点了先走系统授权，
 * 授权回来自动点亮；下半是「AI 提取」的五个入口开关，没配服务商一律显示成关。
 *
 * 口径只有一句：**规则永远先跑**，缺金额或缺分类才交给 AI 提取；关掉的那一路
 * 就纯粹在本机跑完（最快，也不往外发）。落库一律要人点，页面不开任何「自动写账」的口子。
 */
@Composable
fun AutoBookingSettingsScreen(
    onBack: () -> Unit,
    onOpenProviderConfig: () -> Unit = {},
    onOpenHealth: () -> Unit = {},
) {
    val graph = rememberAppGraph()
    val viewModel = tallyViewModel(key = "auto-booking") { AutoBookingViewModel(graph) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val colors = tallyColors
    // 没配服务商时开关一律显示成关：留着「开着也不生效」的样子比关掉更让人误会。
    val aiReady = state.providerReady

    // 没配好服务商就点开关：把话说明白，并把人送去该去的那一页，不留一个「开了也不生效」的开关。
    LaunchedEffect(state.needsProvider) {
        if (!state.needsProvider) return@LaunchedEffect
        TallyToast.show("先配好 AI 服务商，AI 提取才会生效", ToastTone.INFO)
        viewModel.clearNotice()
        onOpenProviderConfig()
    }

    // 从系统授权页回来要重新核一遍：授权成功就自动落偏好，服务被系统停掉也要如实显示。
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.onResume() }

    Column(modifier = Modifier.fillMaxSize().background(colors.paper)) {
        TallyTopBar(title = "自动记账", onBack = onBack)
        if (!state.loaded) return@Column
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 15.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            AutoBookingNotice(assistantOn = state.settings.assistantEnabled, providerReady = state.providerReady)

            SettingsSection(title = "自动入口") {
                SettingsSwitchRow(
                    checked = state.settings.notificationCaptureEnabled && state.notificationOn,
                    onCheckedChange = viewModel::setNotificationCapture,
                    label = "通知抓取",
                    description = "所有应用的通知都按内容过滤，支付成功才进待确认",
                )
                SettingsDivider()
                SettingsSwitchRow(
                    checked = state.settings.pendingBadgeEnabled,
                    onCheckedChange = viewModel::setPendingBadge,
                    label = "待确认提醒",
                    description = when {
                        state.overlayAllowed -> "抓到就浮一个角标，点它进 App 确认"
                        else -> "系统显示未授权，角标不出现时点这里开启"
                    },
                )
                SettingsDivider()
                SettingsNavRow(
                    title = "权限与检查",
                    subtitle = "通知使用权、悬浮窗与电池策略",
                    iconKey = "check",
                    onClick = onOpenHealth,
                )
            }

            SettingsSection(title = "AI 提取") {
                SettingsSwitchRow(
                    checked = state.settings.aiVoiceEnabled && aiReady,
                    onCheckedChange = viewModel::setVoiceEnhanced,
                    label = "语音记账",
                    description = "规则认不出的那句话，交给 AI 补金额与分类",
                )
                SettingsDivider()
                SettingsSwitchRow(
                    checked = state.settings.aiImageEnabled && aiReady,
                    onCheckedChange = viewModel::setImageEnhanced,
                    label = "识图记账",
                    description = "本机 OCR 认不清的账单图，交给 AI 再读一遍",
                )
                SettingsDivider()
                SettingsSwitchRow(
                    checked = state.settings.aiQuickNoteEnabled && aiReady,
                    onCheckedChange = viewModel::setQuickNoteEnhanced,
                    label = "塔莉速记",
                    description = "图库分享进来的图，缺金额或分类时交给 AI",
                )
                SettingsDivider()
                SettingsSwitchRow(
                    checked = state.settings.aiPaymentComplete && aiReady,
                    onCheckedChange = viewModel::setPaymentComplete,
                    label = "支付页补全",
                    description = "卡上缺分类时，交给 AI 补",
                )
                SettingsDivider()
                SettingsSwitchRow(
                    checked = state.settings.aiNotificationComplete && aiReady,
                    onCheckedChange = viewModel::setNotificationComplete,
                    label = "通知补全",
                    description = "通知草稿缺字段时，交给 AI 补",
                )
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

/**
 * 五个开关各自的说明已经写清了「这一步会走 AI」，所以顶部只在**开了也不生效**时补一句：
 * 总开关关着、或还没配服务商。其余情况这人话是多余的。
 */
@Composable
private fun AutoBookingNotice(assistantOn: Boolean, providerReady: Boolean) {
    val text = when {
        !assistantOn -> "设置里的 AI 开关还关着，这五个开关打开也不会生效"
        !providerReady -> "还没配服务商，这五个开关打开也不会生效，先去「服务商配置」加一家"
        else -> return
    }
    Text(text = text, style = TallyType.helper, color = tallyColors.ink3)
}

/** 需要走系统授权才能打开的自动入口。 */
enum class AuthTarget { NOTIFICATION, OVERLAY }

/** 自动入口的实时状态合成一份：避免 combine 超过五路时落进同类型的 List 重载。 */
private data class EntryState(
    val notificationOn: Boolean,
    val overlayAllowed: Boolean,
    val pendingAuth: AuthTarget?,
)

data class AutoBookingUiState(
    val loaded: Boolean = false,
    val settings: TallySettings = TallySettings.DEFAULT,
    /** 服务商配好了没有：没配好时五个开关一律显示成关，也不该被打开。 */
    val providerReady: Boolean = false,
    /** 没配服务商就想开 AI 提取：页面接住这一下并把人送去配置页。 */
    val needsProvider: Boolean = false,
    /** 通知使用权的真实状态。 */
    val notificationOn: Boolean = false,
    /** 悬浮窗权限的真实状态。 */
    val overlayAllowed: Boolean = false,
    /** 正在等系统授权回来的那一项。 */
    val pendingAuth: AuthTarget? = null,
)

/** 这一页读写自动入口与 AI 提取的开关，落点全是设备本地偏好（DataStore）。 */
class AutoBookingViewModel(private val graph: AppGraph) : ViewModel() {
    private val notice = MutableStateFlow(false)
    private val pendingAuth = MutableStateFlow<AuthTarget?>(null)
    private val notificationOn = MutableStateFlow(false)
    private val overlayAllowed = MutableStateFlow(false)

    private val entryState = combine(pendingAuth, notificationOn, overlayAllowed) {
        auth, notifOn, overlay ->
        EntryState(notifOn, overlay, auth)
    }

    val state: StateFlow<AutoBookingUiState> = combine(
        graph.preferences.settings,
        notice,
        entryState,
        graph.aiConfigRepository.observeAvailability(),
    ) { settings, needsProvider, entry, availability ->
        AutoBookingUiState(
            loaded = true,
            settings = settings,
            providerReady = availability.status != AiStatus.UNCONFIGURED,
            needsProvider = needsProvider,
            notificationOn = entry.notificationOn,
            overlayAllowed = entry.overlayAllowed,
            pendingAuth = entry.pendingAuth,
        )
    }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AutoBookingUiState())

    init {
        onResume()
    }

    /** 每次回到页面都重新核一遍系统状态：授权回来要能自动点亮，服务被停掉也要如实显示。 */
    fun onResume() {
        val context = graph.context
        notificationOn.value = NotificationAccess.enabled(context)
        overlayAllowed.value = Settings.canDrawOverlays(context)
        settleAuth()
    }

    /** 授权回来：权限真的拿到了才落偏好，不让人回来再点一次。 */
    private fun settleAuth() {
        val target = pendingAuth.value ?: return
        viewModelScope.launch {
            when (target) {
                AuthTarget.NOTIFICATION -> if (NotificationAccess.enabled(graph.context)) {
                    graph.preferences.setNotificationCaptureEnabled(true)
                    pendingAuth.value = null
                }
                AuthTarget.OVERLAY -> if (Settings.canDrawOverlays(graph.context)) {
                    graph.preferences.setPendingBadgeEnabled(true)
                    overlayAllowed.value = true
                    pendingAuth.value = null
                }
            }
        }
    }

    fun setNotificationCapture(enabled: Boolean) {
        viewModelScope.launch {
            if (!enabled) {
                graph.preferences.setNotificationCaptureEnabled(false)
                return@launch
            }
            if (NotificationAccess.enabled(graph.context)) {
                graph.preferences.setNotificationCaptureEnabled(true)
            } else {
                pendingAuth.value = AuthTarget.NOTIFICATION
                openSystemSettings(graph.context, Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            }
        }
    }

    /**
     * 悬浮窗权限只能由人在系统页给。
     *
     * 但开关状态只认「人的意图」：小米上 `canDrawOverlays()` 会骗人（返回 false，浮窗却加得上），
     * 拿它当开关条件就会出现「角标明明在浮，设置里却显示关着」。能不能真浮出来由 addView 说话，
     * 系统说没权限时顺带把人送到授权页即可。
     */
    fun setPendingBadge(enabled: Boolean) {
        viewModelScope.launch {
            graph.preferences.setPendingBadgeEnabled(enabled)
            if (!enabled) {
                return@launch
            }
            if (Settings.canDrawOverlays(graph.context)) {
                overlayAllowed.value = true
            } else {
                pendingAuth.value = AuthTarget.OVERLAY
                openSystemSettings(
                    graph.context,
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    "package:${graph.context.packageName}",
                )
            }
        }
    }

    private fun openSystemSettings(context: Context, action: String, data: String? = null) {
        runCatching {
            context.startActivity(
                Intent(action).apply {
                    data?.let { this.data = Uri.parse(it) }
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                },
            )
        }
    }

    fun clearNotice() {
        notice.value = false
    }

    fun setVoiceEnhanced(enabled: Boolean) = toggleAi(enabled) { graph.preferences.setAiVoiceEnabled(it) }

    fun setImageEnhanced(enabled: Boolean) = toggleAi(enabled) { graph.preferences.setAiImageEnabled(it) }

    fun setQuickNoteEnhanced(enabled: Boolean) = toggleAi(enabled) { graph.preferences.setAiQuickNoteEnabled(it) }

    fun setPaymentComplete(enabled: Boolean) = toggleAi(enabled) { graph.preferences.setAiPaymentComplete(it) }

    fun setNotificationComplete(enabled: Boolean) = toggleAi(enabled) { graph.preferences.setAiNotificationComplete(it) }

    /** 打开之前先问一句「配好了吗」：没配好就不写偏好，页面接手把人送去配置页。 */
    private fun toggleAi(enabled: Boolean, write: suspend (Boolean) -> Unit) {
        viewModelScope.launch {
            if (enabled && graph.aiConfigRepository.availability().status == AiStatus.UNCONFIGURED) {
                notice.value = true
            } else {
                write(enabled)
            }
        }
    }
}
