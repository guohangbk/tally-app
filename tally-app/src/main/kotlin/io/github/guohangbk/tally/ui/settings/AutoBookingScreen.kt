package io.github.guohangbk.tally.ui.settings

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
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
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
 * 现在装的是三条 AI 提取入口与「账单图片」；无障碍记账、通知记账这类开关以后也归这一页，
 * 所以分组按「AI 提取 / 通用设置」切，不按某一条入口切。
 *
 * 口径只有一句：**规则永远先跑**，缺金额或缺分类才交给 AI 提取；关掉的那一路
 * 就纯粹在本机跑完（最快，也不往外发）。落库一律要人点，页面不开任何「自动写账」的口子。
 */
@Composable
fun AutoBookingSettingsScreen(
    onBack: () -> Unit,
    onOpenProviderConfig: () -> Unit = {},
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
            }

            SettingsSection(title = "通用设置") {
                SettingsSwitchRow(
                    checked = state.settings.autoSaveBillImage,
                    onCheckedChange = viewModel::setSaveBillImage,
                    label = "账单图片",
                    description = "识图记账时把那张图一并存进这一笔",
                )
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

/**
 * 三个开关各自的说明已经写清了「这一步会走 AI」，所以顶部只在**开了也不生效**时补一句：
 * 总开关关着、或还没配服务商。其余情况这人话是多余的。
 */
@Composable
private fun AutoBookingNotice(assistantOn: Boolean, providerReady: Boolean) {
    val text = when {
        !assistantOn -> "设置里的 AI 开关还关着，这三个开关打开也不会生效"
        !providerReady -> "还没配服务商，这三个开关打开也不会生效，先去「服务商配置」加一家"
        else -> return
    }
    Text(text = text, style = TallyType.helper, color = tallyColors.ink3)
}

data class AutoBookingUiState(
    val loaded: Boolean = false,
    val settings: TallySettings = TallySettings.DEFAULT,
    /** 服务商配好了没有：没配好时三个开关一律显示成关，也不该被打开。 */
    val providerReady: Boolean = false,
    /** 没配服务商就想开 AI 提取：页面接住这一下并把人送去配置页。 */
    val needsProvider: Boolean = false,
)

/** 这一页只读写四个开关，落点全是设备本地偏好（DataStore）。 */
class AutoBookingViewModel(private val graph: AppGraph) : ViewModel() {
    private val notice = MutableStateFlow(false)

    val state: StateFlow<AutoBookingUiState> = combine(
        graph.preferences.settings,
        notice,
        graph.aiConfigRepository.observeAvailability(),
    ) { settings, needsProvider, availability ->
        AutoBookingUiState(
            loaded = true,
            settings = settings,
            providerReady = availability.status != AiStatus.UNCONFIGURED,
            needsProvider = needsProvider,
        )
    }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AutoBookingUiState())

    fun setVoiceEnhanced(enabled: Boolean) = toggleAi(enabled) { graph.preferences.setAiVoiceEnabled(it) }

    fun setImageEnhanced(enabled: Boolean) = toggleAi(enabled) { graph.preferences.setAiImageEnabled(it) }

    fun setQuickNoteEnhanced(enabled: Boolean) = toggleAi(enabled) { graph.preferences.setAiQuickNoteEnabled(it) }

    fun setSaveBillImage(enabled: Boolean) = put { graph.preferences.setAutoSaveBillImage(enabled) }

    fun clearNotice() {
        notice.value = false
    }

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

    private fun put(operation: suspend () -> Unit) {
        viewModelScope.launch { operation() }
    }
}
