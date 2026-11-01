package io.github.guohangbk.tally.ui.assistant

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.guohangbk.tally.data.ai.AiSaveOutcome
import io.github.guohangbk.tally.di.AppGraph
import io.github.guohangbk.tally.domain.ai.AiConfigDraft
import io.github.guohangbk.tally.domain.ai.AiConfiguration
import io.github.guohangbk.tally.domain.ai.AiProviderPresets
import io.github.guohangbk.tally.domain.ai.AiResult
import io.github.guohangbk.tally.domain.model.AiStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** [editingId] 取这个值代表「正在填一条新的」，不对应库里任何一行。 */
const val NEW_CONFIG_ID = "__new__"

/** P17 的即时状态。Key 只在内存里，写入前必须先过连接测试。 */
data class AssistantConfigUiState(
    val loaded: Boolean = false,
    val draft: AiConfigDraft = AiConfigDraft(),
    /** 全部配置，列表就靠它；[configuration] 只是其中生效的那一条。 */
    val configs: List<AiConfiguration> = emptyList(),
    val configuration: AiConfiguration? = null,
    val busy: Boolean = false,
    val hasSavedKey: Boolean = false,
    val revealedKey: Boolean = false,
    /** 配置表单是否开着：新增与修改共用一张弹层，页面本体只留列表。 */
    val editing: Boolean = false,
    /** 表单正在改哪一条；[NEW_CONFIG_ID] 是新建，null 是没在改。 */
    val editingId: String? = null,
    val notice: String? = null,
    val error: String? = null,
    val lastLatencyMillis: Long? = null,
    val lastTokens: Int? = null,
) {
    val status: AiStatus get() = configuration?.status ?: AiStatus.UNCONFIGURED
    val canSave: Boolean get() = !busy && draft.baseUrl.isNotBlank() && draft.model.isNotBlank() &&
        (draft.apiKey.isNotBlank() || hasSavedKey)
}

class AssistantConfigViewModel(private val graph: AppGraph) : ViewModel() {
    private val configs = graph.aiConfigRepository
    private val state = MutableStateFlow(AssistantConfigUiState())

    val uiState: StateFlow<AssistantConfigUiState> = state.asStateFlow()

    init {
        viewModelScope.launch {
            // 只看配置本身：启用与否是 app 设置里那个开关的字段，这一页不重复管一份。
            configs.observeAll().collect { list ->
                state.update { previous ->
                    val active = list.firstOrNull { it.active } ?: list.firstOrNull()
                    // 表单正在改的那一条优先；正在新建就看生效的那条，好把 Key 状态说准。
                    val target = when (previous.editingId) {
                        NEW_CONFIG_ID -> null
                        null -> active
                        else -> list.firstOrNull { it.id == previous.editingId } ?: active
                    }
                    // 旧配置只存过 base URL，按地址反推出服务商，升级后表单能直接落在正确的下拉项上。
                    val provider = AiProviderPresets.byBaseUrl(target?.baseUrl)
                    val savedModel = target?.model.orEmpty()
                    val hasKey = target != null && !target.keyReference.isNullOrBlank() &&
                        graph.aiKeyVault.read(target.id) != null
                    previous.copy(
                        loaded = true,
                        configs = list,
                        configuration = active,
                        hasSavedKey = hasKey,
                        // 表单只在点「新增」或「修改」时打开：进来就摊开输入框，这一页的列表便没有存在的意义。
                        editing = previous.editing,
                        draft = previous.draft.copy(
                            provider = if (!previous.loaded) provider.key else previous.draft.provider,
                            baseUrl = if (!previous.loaded || previous.editingId == NEW_CONFIG_ID) {
                                if (previous.editingId == NEW_CONFIG_ID) "" else target?.baseUrl.orEmpty()
                            } else previous.draft.baseUrl,
                            model = if (!previous.loaded || previous.editingId == NEW_CONFIG_ID) {
                                if (previous.editingId == NEW_CONFIG_ID) "" else savedModel
                            } else previous.draft.model,
                            visionCapable = if (!previous.loaded) {
                                target?.visionCapable ?: (AiProviderPresets.visionOf(provider.key, savedModel) ?: false)
                            } else {
                                previous.draft.visionCapable
                            },
                        ),
                    )
                }
            }
        }
    }

    /** 列表里点一条：把它的值放回表单，Key 留空代表沿用已存的那把。 */
    fun selectForEdit(configId: String) {
        val config = state.value.configs.firstOrNull { it.id == configId } ?: return
        state.update {
            it.copy(
                editing = true,
                editingId = configId,
                draft = it.draft.copy(
                    provider = AiProviderPresets.byBaseUrl(config.baseUrl).key,
                    baseUrl = config.baseUrl,
                    model = config.model,
                    visionCapable = config.visionCapable,
                    apiKey = "",
                ),
                error = null,
                notice = null,
            )
        }
    }

    /** 新建一条：表单清空，不继承任何已存值。 */
    fun addNew() = state.update {
        it.copy(
            editing = true,
            editingId = NEW_CONFIG_ID,
            draft = AiConfigDraft(),
            error = null,
            notice = null,
        )
    }

    /** 取消修改：丢掉表单里的临时值，退回已保存的那一份，避免下次点「修改」看到一堆没生效的改动。 */
    fun cancelEdit() = state.update { previous ->
        val config = previous.configuration ?: return@update previous.copy(editing = false, editingId = null)
        previous.copy(
            editing = false,
            editingId = null,
            draft = previous.draft.copy(
                provider = AiProviderPresets.byBaseUrl(config.baseUrl).key,
                baseUrl = config.baseUrl,
                model = config.model,
                visionCapable = config.visionCapable,
                apiKey = "",
            ),
            error = null,
            notice = null,
        )
    }

    /**
     * 换服务商就把地址与模型一起换掉：留着上一家的模型名只会让测试报一个看不懂的模型不存在。
     * 自定义服务商清空这两项，让用户从空白开始填，而不是背着一个别人的地址。
     */
    fun onProvider(key: String) {
        val preset = AiProviderPresets.byKey(key)
        state.update { previous ->
            if (previous.draft.provider == preset.key) {
                previous
            } else {
                previous.copy(
                    draft = previous.draft.copy(
                        provider = preset.key,
                        baseUrl = preset.baseUrl,
                        model = preset.models.firstOrNull()?.id.orEmpty(),
                        visionCapable = preset.models.firstOrNull()?.vision ?: false,
                    ),
                    error = null,
                )
            }
        }
    }

    fun onBaseUrl(value: String) = state.update { it.copy(draft = it.draft.copy(baseUrl = value), error = null) }

    fun onModel(value: String) = state.update { it.copy(draft = it.draft.copy(model = value), error = null) }

    /** 从预置列表选模型：顺手把图片勾选对齐到预设，用户仍可再手动改。 */
    fun selectModel(modelId: String) = state.update {
        val vision = AiProviderPresets.visionOf(it.draft.provider, modelId)
        it.copy(
            draft = it.draft.copy(
                model = modelId,
                visionCapable = vision ?: it.draft.visionCapable,
            ),
            error = null,
        )
    }

    fun onVision(capable: Boolean) =
        state.update { it.copy(draft = it.draft.copy(visionCapable = capable)) }

    fun onApiKey(value: String) = state.update { it.copy(draft = it.draft.copy(apiKey = value), error = null) }

    fun onPrivacy(acknowledged: Boolean) =
        state.update { it.copy(draft = it.draft.copy(privacyAcknowledged = acknowledged)) }

    fun toggleRevealKey() = state.update { it.copy(revealedKey = !it.revealedKey) }

    fun dismissMessages() = state.update { it.copy(notice = null, error = null) }

    /** 「测试并保存」：一个动词做完两件事，测试不过就什么都不写。 */
    fun testAndSave() {
        val current = state.value
        if (current.busy) return
        val draft = current.draft.copy(apiKey = current.draft.apiKey.ifBlank {
            graph.aiKeyVault.read(current.editingId ?: current.configuration?.id ?: "").orEmpty()
        })
        val targetId = if (current.editingId == NEW_CONFIG_ID) null else current.editingId
        state.update { it.copy(busy = true, error = null, notice = null) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                when (val outcome = configs.testAndSave(draft, targetId)) {
                    is AiSaveOutcome.InvalidForm -> state.update { it.copy(busy = false, error = outcome.reason) }

                    is AiSaveOutcome.TestFailed -> state.update {
                        it.copy(busy = false, error = outcome.failure.detail ?: outcome.failure.kind.userMessage)
                    }

                    is AiSaveOutcome.Saved -> state.update {
                        it.copy(
                            busy = false,
                            editing = false,
                            editingId = null,
                            draft = it.draft.copy(apiKey = ""),
                            notice = "已保存并启用 · ${if (outcome.visionCapable) "支持图片" else "仅文本"}",
                            lastLatencyMillis = outcome.report.latencyMillis,
                            lastTokens = outcome.report.usage.totalTokens,
                        )
                    }
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                state.update { it.copy(busy = false, error = failure.message ?: "保存失败，配置没有改动") }
            }
        }
    }

    /** 让某一条配置生效；Key 不动，所以切回来还是原来那把。 */
    fun activate(configId: String) = runOp { configs.setActive(configId) }

    fun remove(configId: String) = runOp { configs.delete(configId) }

    /** 重新测试是唯一的自动恢复时机：进入已断开后不做后台轮询。 */
    fun retest() {
        if (state.value.busy) return
        state.update { it.copy(busy = true, error = null, notice = null) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                when (val result = configs.retest()) {
                    is AiResult.Success -> state.update {
                        it.copy(
                            busy = false,
                            notice = "连接正常 · ${result.value.latencyMillis}ms",
                            lastLatencyMillis = result.value.latencyMillis,
                            lastTokens = result.value.usage.totalTokens,
                        )
                    }

                    is AiResult.Failure -> state.update {
                        it.copy(busy = false, error = result.failure.detail ?: result.failure.kind.userMessage)
                    }
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                state.update { it.copy(busy = false, error = failure.message ?: "小莉暂时联系不上") }
            }
        }
    }

    private fun runOp(label: String? = null, block: suspend () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                block()
                state.update { it.copy(notice = label ?: it.notice) }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                state.update { it.copy(error = failure.message ?: "这一步没有完成") }
            }
        }
    }
}
