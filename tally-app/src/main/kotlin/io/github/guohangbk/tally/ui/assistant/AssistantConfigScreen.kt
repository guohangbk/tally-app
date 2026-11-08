package io.github.guohangbk.tally.ui.assistant

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.guohangbk.tally.di.rememberAppGraph
import io.github.guohangbk.tally.domain.ai.AiConfiguration
import io.github.guohangbk.tally.domain.ai.AiModelPreset
import io.github.guohangbk.tally.domain.ai.AiProviderPreset
import io.github.guohangbk.tally.domain.ai.AiProviderPresets
import io.github.guohangbk.tally.ui.common.tallyViewModel
import io.github.guohangbk.tally.ui.design.ButtonVariant
import io.github.guohangbk.tally.ui.design.InlineIcon
import io.github.guohangbk.tally.ui.design.TallyBottomSheet
import io.github.guohangbk.tally.ui.design.TallyConfirmDialog
import io.github.guohangbk.tally.ui.design.TallyTag
import io.github.guohangbk.tally.ui.design.TallyButton
import io.github.guohangbk.tally.ui.design.TallyCard
import io.github.guohangbk.tally.ui.design.TallyListRow
import io.github.guohangbk.tally.ui.design.TallySectionLabel
import io.github.guohangbk.tally.ui.design.TallyType
import io.github.guohangbk.tally.ui.design.TallyToastEffects
import io.github.guohangbk.tally.ui.design.TagTone
import io.github.guohangbk.tally.ui.design.rememberTallyHaptics
import io.github.guohangbk.tally.ui.design.tallyColors
import io.github.guohangbk.tally.ui.shell.TallyIconAction
import io.github.guohangbk.tally.ui.shell.TallyTopBar
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 小莉配置页（从 app 设置里的「小莉配置」进来）。
 *
 * 只维护「小莉当前用哪一家服务商」：服务商从预置列表里选，地址与模型一起带出来；
 * 自定义服务商才手填。是否支持图片由保存前的勾选决定：不勾选时对话里不发图，
 * 只走文字与语音；勾选后截图直接交给模型。
 *
 * 页面本体只有配置列表（没有配置时是一条空态），新增与修改共用右上角 ＋ 打开的那张弹层。
 * 启用与否不在这里管——那是 app 设置里那个开关的事，一整面墙只允许有一个开关。
 * 保存按钮文案就是「测试并保存」：没过测试就什么都不写。
 */
@Composable
fun AssistantConfigScreen(onBack: () -> Unit) {
    val graph = rememberAppGraph()
    val viewModel = tallyViewModel(key = "assistant-config") { AssistantConfigViewModel(graph) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val colors = tallyColors
    var picker by remember { mutableStateOf<ConfigPicker?>(null) }

    Column(modifier = Modifier.fillMaxSize().background(colors.paper)) {
        TallyTopBar(
            title = "小莉配置",
            onBack = onBack,
            trailing = {
                TallyIconAction(iconKey = "plus", contentDescription = "新增配置", onClick = viewModel::addNew)
            },
        )

        // 提示走贴底气泡，但弹层开着时不归它管：气泡贴在屏幕底部，正好被那张表单弹层压在下面，
        // 「测试没通过」就变成看不见的一句。这一段错误改由弹层自己就地画（见 ConfigSheet）。
        TallyToastEffects(
            notice = state.notice,
            error = if (state.editing) null else state.error,
            errorTitle = "这一步没有完成",
            onNoticeShown = viewModel::dismissMessages,
            onErrorShown = viewModel::dismissMessages,
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 15.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (state.loaded) {
                TallySectionLabel("配置列表")
                ConfigList(
                    configs = state.configs,
                    activeId = state.configuration?.id,
                    busy = state.busy,
                    onSelect = viewModel::selectForEdit,
                    onActivate = viewModel::activate,
                    onRemove = viewModel::remove,
                    onRetest = viewModel::retest,
                )
            }

            Spacer(Modifier.height(24.dp))
        }
    }

    // 表单是弹层，服务商与模型的选择也是弹层：同一时刻只放一张，草稿留在 ViewModel 里不会丢。
    if (state.editing && picker == null) {
        ConfigSheet(
            state = state,
            viewModel = viewModel,
            onDismiss = viewModel::cancelEdit,
            onPickProvider = { picker = ConfigPicker.PROVIDER },
            onPickModel = { picker = ConfigPicker.MODEL },
        )
    }

    when (picker) {
        ConfigPicker.PROVIDER -> ProviderPickerSheet(
            selectedKey = state.draft.provider,
            onSelect = {
                viewModel.onProvider(it)
                picker = null
            },
            onDismiss = { picker = null },
        )

        ConfigPicker.MODEL -> ModelPickerSheet(
            provider = AiProviderPresets.byKey(state.draft.provider),
            selectedModel = state.draft.model,
            onSelect = {
                viewModel.selectModel(it)
                picker = null
            },
            onDismiss = { picker = null },
        )

        null -> Unit
    }
}

private enum class ConfigPicker { PROVIDER, MODEL }

/**
 * 配置列表：每条一张卡，**服务地址单独一行放在模型前面**——同一个自定义网关配多个模型时，
 * 只有地址能把这几条区分开，模型名很可能重复。生效的那条顶上有标记，行尾是「切换 / 删除」。
 * 一条都没有时不放输入框，只留一条空态把人指到右上角的 ＋。
 */
@Composable
private fun ConfigList(
    configs: List<AiConfiguration>,
    activeId: String?,
    busy: Boolean,
    onSelect: (String) -> Unit,
    onActivate: (String) -> Unit,
    onRemove: (String) -> Unit,
    onRetest: () -> Unit,
) {
    val colors = tallyColors
    if (configs.isEmpty()) {
        TallyCard {
            TallyListRow(
                title = "当前没有配置 AI 服务商",
                subtitle = "点右上角 ＋ 添加一家",
                iconKey = "ai",
            )
        }
        return
    }
    configs.forEach { config ->
        val isActive = config.id == activeId
        var confirmRemove by remember { mutableStateOf(false) }
        AiCard {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text(
                        AiProviderPresets.byKey(config.provider).name,
                        style = TallyType.sectionTitle.copy(fontSize = 15.5.sp, lineHeight = 20.sp),
                        color = colors.ink,
                        maxLines = 1,
                    )
                    if (isActive) TallyTag(text = "生效中", tone = TagTone.GOLD)
                    Spacer(Modifier.weight(1f))
                    TallyTag(
                        text = if (config.visionCapable) "图片" else "文本",
                        tone = if (config.visionCapable) TagTone.SLATE else TagTone.OUTLINE,
                    )
                }
                Text(
                    text = config.baseUrl.ifBlank { "（未填地址）" },
                    fontSize = 11.5.sp, lineHeight = 16.sp,
                    color = colors.ink2,
                    maxLines = 1,
                )
                AiMetaLine("模型", config.model)
                AiMetaLine("更新于", formatClock(config.updatedAt))
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
                    TallyButton(
                        label = "修改",
                        onClick = { onSelect(config.id) },
                        height = 32,
                        modifier = Modifier.weight(1f),
                    )
                    if (isActive) {
                        TallyButton(
                            label = "重新测试",
                            onClick = onRetest,
                            variant = ButtonVariant.GHOST,
                            height = 32,
                            enabled = !busy,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    if (!isActive) {
                        TallyButton(
                            label = "切换",
                            onClick = { onActivate(config.id) },
                            variant = ButtonVariant.GHOST,
                            height = 32,
                            enabled = !busy,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    val shape = RoundedCornerShape(10.dp)
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(shape)
                            .background(colors.expense.copy(alpha = 0.07f))
                            .border(1.dp, colors.expense.copy(alpha = 0.28f), shape)
                            .clickable { confirmRemove = true },
                        contentAlignment = Alignment.Center,
                    ) {
                        InlineIcon(iconKey = "trash", contentDescription = "删除这条配置", size = 14.dp, tint = colors.expense)
                    }
                }
            }
        }
        if (confirmRemove) {
            TallyConfirmDialog(
                title = "删除这条配置？",
                message = "这家服务商的地址、模型与 Key 都会清掉。其他配置不受影响。",
                confirmLabel = "删除",
                destructive = true,
                onConfirm = {
                    confirmRemove = false
                    onRemove(config.id)
                },
                onDismiss = { confirmRemove = false },
            )
        }
    }
}

/**
 * 新增与修改共用一张弹层。表单只在这里出现：页面本体是列表，进来不该被输入框盖住。
 * 服务商与模型的选择是同层级的另一张弹层，由调用方保证同一时刻只放一张。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConfigSheet(
    state: AssistantConfigUiState,
    viewModel: AssistantConfigViewModel,
    onDismiss: () -> Unit,
    onPickProvider: () -> Unit,
    onPickModel: () -> Unit,
) {
    val colors = tallyColors
    val isNew = state.editingId == NEW_CONFIG_ID
    TallyBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = colors.paper,
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            Spacer(Modifier.height(12.dp))
            Text(if (isNew) "新增配置" else "修改配置", style = TallyType.sectionTitle, color = colors.ink)
            Spacer(Modifier.height(4.dp))
            Text(
                if (isNew) "填一家服务商，测试通过后小莉就能用了" else "改地址或模型；Key 留空就沿用已存的那把",
                style = TallyType.helper,
                color = colors.ink3,
            )
            Spacer(Modifier.height(12.dp))
            Column(modifier = Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState())) {
                AiCard {
                    Column(verticalArrangement = Arrangement.spacedBy(11.dp)) {
                        val preset = AiProviderPresets.byKey(state.draft.provider)

                        AiSelectRow(
                            label = "服务商",
                            value = preset.name,
                            onClick = onPickProvider,
                        )
                        AiField(
                            label = "服务地址 base URL",
                            value = state.draft.baseUrl,
                            onValueChange = viewModel::onBaseUrl,
                            placeholder = "https://api.example.com/v1",
                            keyboardType = KeyboardType.Uri,
                            trailing = { InlineIcon("link", contentDescription = null, size = 15.dp, tint = colors.gold) },
                        )
                        if (preset.isCustom) {
                            AiField(
                                label = "模型名",
                                value = state.draft.model,
                                onValueChange = viewModel::onModel,
                                placeholder = "gpt-x-vision",
                            )
                        } else {
                            AiSelectRow(
                                label = "模型",
                                value = state.draft.model,
                                onClick = onPickModel,
                            )
                        }
                        AiField(
                            label = "API Key",
                            value = state.draft.apiKey,
                            onValueChange = viewModel::onApiKey,
                            placeholder = if (state.hasSavedKey) "已保存 · 留空则不改" else "sk-...",
                            secret = true,
                            revealed = state.revealedKey,
                            trailing = {
                                Box(
                                    modifier = Modifier
                                        .size(24.dp)
                                        .clickable(onClick = viewModel::toggleRevealKey),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    InlineIcon(
                                        iconKey = if (state.revealedKey) "eye" else "lock",
                                        contentDescription = if (state.revealedKey) "隐藏 Key" else "显示 Key",
                                        size = 15.dp,
                                        tint = colors.ink3,
                                    )
                                }
                            },
                        )
                    }
                }

                Spacer(Modifier.height(10.dp))
                AiCheckRow(
                    checked = state.draft.visionCapable,
                    onCheckedChange = viewModel::onVision,
                    text = "支持图片输入（模型可直接看截图；不勾选则对话里不能发图）",
                )

                Spacer(Modifier.height(10.dp))
                AiCheckRow(
                    checked = state.draft.privacyAcknowledged,
                    onCheckedChange = viewModel::onPrivacy,
                    text = "我已了解：截图与账单文本将发送到我配置的这家服务商。Key 只存 Android Keystore，不进备份包、不进日志。",
                )

                Spacer(Modifier.height(16.dp))
                // 测试没过：说在按钮上面，不甩给页面气泡（那张弹层会把气泡整个盖住）。
                // 错误留在状态里到下一次点按，不自动收——人还在这一屏改，收掉就没了。
                state.error?.let { reason ->
                    Text(reason, style = TallyType.helper, color = colors.expense)
                    Spacer(Modifier.height(8.dp))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    TallyButton(
                        label = if (state.busy) "正在测试…" else "测试并保存",
                        onClick = viewModel::testAndSave,
                        variant = ButtonVariant.GOLD,
                        enabled = state.canSave,
                        height = 44,
                        leadingIcon = "refresh",
                        modifier = Modifier.weight(1f),
                    )
                    TallyButton(
                        label = "取消",
                        onClick = onDismiss,
                        variant = ButtonVariant.GHOST,
                        height = 44,
                        modifier = Modifier.weight(0.32f),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProviderPickerSheet(
    selectedKey: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = tallyColors
    TallyBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = colors.paper,
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            Spacer(Modifier.height(12.dp))
            Text("选择服务商", style = TallyType.sectionTitle, color = colors.ink)
            Spacer(Modifier.height(4.dp))
            Text(
                "选中后会自动填好服务地址与常用模型，仍可改成中转地址。",
                style = TallyType.helper,
                color = colors.ink3,
            )
            Spacer(Modifier.height(8.dp))
            Column(modifier = Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState())) {
                AiProviderPresets.all.forEach { preset ->
                    PickerRow(
                        title = preset.name,
                        subtitle = if (preset.isCustom) "OpenAI 兼容 · 自己填地址与模型" else preset.baseUrl,
                        selected = preset.key == selectedKey,
                        onClick = { onSelect(preset.key) },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelPickerSheet(
    provider: AiProviderPreset,
    selectedModel: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = tallyColors
    TallyBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = colors.paper,
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            Spacer(Modifier.height(12.dp))
            Text("选择${provider.name}模型", style = TallyType.sectionTitle, color = colors.ink)
            Spacer(Modifier.height(4.dp))
            Text(
                "图片模型可直接看截图；文本模型会先在本机 OCR，两种都能记账。",
                style = TallyType.helper,
                color = colors.ink3,
            )
            Spacer(Modifier.height(8.dp))
            Column(modifier = Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState())) {
                provider.models.forEach { model ->
                    ModelPickerRow(
                        model = model,
                        selected = model.id == selectedModel,
                        onClick = { onSelect(model.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun PickerRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = tallyColors
    val haptics = rememberTallyHaptics()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(11.dp))
            .background(if (selected) colors.brandSurface else Color.Transparent)
            .clickable {
                haptics.tap()
                onClick()
            }
            .padding(horizontal = 11.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp, lineHeight = 17.5.sp, color = colors.ink, maxLines = 1)
            Text(subtitle, style = TallyType.helper, color = colors.ink3, maxLines = 1)
        }
        if (selected) {
            Spacer(Modifier.size(8.dp))
            InlineIcon(iconKey = "check", contentDescription = "已选择", size = 15.dp, tint = colors.brand)
        }
    }
}

@Composable
private fun ModelPickerRow(
    model: AiModelPreset,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = tallyColors
    val haptics = rememberTallyHaptics()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(11.dp))
            .background(if (selected) colors.brandSurface else Color.Transparent)
            .clickable {
                haptics.tap()
                onClick()
            }
            .padding(horizontal = 11.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = model.id,
            fontSize = 14.sp, lineHeight = 17.5.sp,
            color = colors.ink,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        TallyTag(
            text = if (model.vision) "图片" else "文本",
            tone = if (model.vision) TagTone.GOLD else TagTone.SLATE,
        )
        if (selected) {
            Spacer(Modifier.size(8.dp))
            InlineIcon(iconKey = "check", contentDescription = "已选择", size = 15.dp, tint = colors.brand)
        }
    }
}

private fun formatClock(millis: Long): String =
    SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(millis))
