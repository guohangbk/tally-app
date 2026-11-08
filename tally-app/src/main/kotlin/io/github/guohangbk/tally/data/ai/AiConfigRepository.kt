package io.github.guohangbk.tally.data.ai

import io.github.guohangbk.tally.data.db.dao.AiConfigDao
import io.github.guohangbk.tally.data.db.entity.AiConfigEntity
import io.github.guohangbk.tally.data.preferences.TallyPreferences
import io.github.guohangbk.tally.domain.ai.AiCallOutcome
import io.github.guohangbk.tally.domain.ai.AiClient
import io.github.guohangbk.tally.domain.ai.AiConfigDraft
import io.github.guohangbk.tally.domain.ai.AiConfiguration
import io.github.guohangbk.tally.domain.ai.AiConnectionReport
import io.github.guohangbk.tally.domain.ai.AiErrorKind
import io.github.guohangbk.tally.domain.ai.AiFailure
import io.github.guohangbk.tally.domain.ai.AiResult
import io.github.guohangbk.tally.domain.ai.AiState
import io.github.guohangbk.tally.domain.ai.AiStateMachine
import io.github.guohangbk.tally.domain.ai.AiTarget
import io.github.guohangbk.tally.domain.ai.toOutcome
import io.github.guohangbk.tally.domain.model.AiStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID

/**
 * 保存结果分三种，页面提示各不相同：表单没填对、连接测试没过、测试过了才落库。
 * 把「表单不合格」和「服务商拒了」混成一个错误类型，用户就分不清该改地址还是该查网络。
 */
sealed interface AiSaveOutcome {
    data class InvalidForm(val reason: String) : AiSaveOutcome

    data class TestFailed(val failure: AiFailure) : AiSaveOutcome

    data class Saved(val report: AiConnectionReport, val visionCapable: Boolean) : AiSaveOutcome
}

/**
 * 小莉配置的读写与状态落库。
 *
 * 严格模式：[testAndSave] 用**候选值**先打一次最小真实请求，通过才写库；
 * 失败时数据库与 Key 存储都保持原状。状态机本身是 domain 的纯函数，
 * 这里只把它的输出映射到 ai_config 这一行上。
 *
 * 配置可以有多条，一次只有一条生效（`active` 为真）；Key 也跟着配置走，
 * 所以切回上一家时那把 Key 还在，不会因为存过新的就把旧的覆盖掉。
 */
class AiConfigRepository(
    private val dao: AiConfigDao,
    private val keys: AiKeyVault,
    private val preferences: TallyPreferences,
    private val client: AiClient,
    private val now: () -> Long = System::currentTimeMillis,
) {
    fun observeAll(): Flow<List<AiConfiguration>> = dao.observeAll().map { rows -> rows.map { it.toDomain() } }

    fun observe(): Flow<AiConfiguration?> = dao.observe().map { it?.toDomain() }

    suspend fun current(): AiConfiguration? = dao.find()?.toDomain()

    suspend fun isEnabled(): Boolean = preferences.settings.first().assistantEnabled

    /** 一次性读取「能不能用、为什么不能用」，给调用方做同步判断。 */
    suspend fun availability(): AiAvailability = availabilityOf(dao.find()?.toDomain(), isEnabled())

    /** 订阅式可用性：右臂三态与首页引导都读这一条，开关或配置一变就跟着走。 */
    fun observeAvailability(): Flow<AiAvailability> =
        combine(dao.observe(), preferences.settings) { config, settings ->
            availabilityOf(config?.toDomain(), settings.assistantEnabled)
        }

    suspend fun target(): AiTarget? {
        val config = dao.find() ?: return null
        return keys.targetOf(config.id, config.baseUrl, config.model)
    }

    /**
     * [configId] 为空就是新增一条；给 id 就是改那一条。两种情况都直接生效——
     * 用户在这一页改配置，就是想让它马上用。
     */
    suspend fun testAndSave(draft: AiConfigDraft, configId: String? = null): AiSaveOutcome {
        draft.missingField?.let { return AiSaveOutcome.InvalidForm(it) }
        if (!draft.privacyAcknowledged) return AiSaveOutcome.InvalidForm("请先勾选隐私告知，再测试并保存")

        val url = draft.baseUrl.trim()
        val model = draft.model.trim()
        val key = draft.apiKey.trim()
        if (OpenAiCompatibleClient.chatEndpoint(url) == null) {
            return AiSaveOutcome.InvalidForm("服务地址形如 https://api.example.com/v1")
        }

        val candidate = AiTarget(baseUrl = url, model = model, apiKey = key)
        val report = when (val result = client.testConnection(candidate)) {
            is AiResult.Success -> result.value
            is AiResult.Failure -> return AiSaveOutcome.TestFailed(result.failure)
        }
        // 能力由用户在表单里勾选：探测只能判断模型此刻是否配合，勾选才代表他打算怎么用。
        val vision = draft.visionCapable

        val id = configId ?: UUID.randomUUID().toString()
        keys.save(id, key)
        dao.clearOthersActive(id)
        dao.upsert(
            AiConfigEntity(
                id = id,
                provider = draft.provider,
                baseUrl = url,
                model = model,
                keyReference = keys.referenceFor(id),
                active = true,
                status = AiStatus.READY.name,
                visionCapable = vision,
                lastError = null,
                consecutiveFailures = 0,
                updatedAt = now(),
            ),
        )
        preferences.setAssistantEnabled(true)
        // 第一次真正配通：回首页说一次「小莉已经就绪」，之后不再提。
        preferences.markAssistantReadyHint()
        return AiSaveOutcome.Saved(report, vision)
    }

    /** 把某一条设成生效；本来就生效的也重写一次 updated_at，列表顺序跟着走。 */
    suspend fun setActive(configId: String) {
        val config = dao.findById(configId) ?: return
        dao.clearOthersActive(configId)
        dao.upsert(config.copy(active = true, updatedAt = now()))
        preferences.setAssistantEnabled(true)
    }

    /** 删一条配置与它自己的 Key；删掉生效的那一条就把最近改的另一条顶上。 */
    suspend fun delete(configId: String) {
        val config = dao.findById(configId) ?: return
        dao.delete(configId)
        keys.clear(configId)
        if (config.active) {
            val next = dao.find()
            if (next != null) {
                dao.clearOthersActive(next.id)
                dao.upsert(next.copy(active = true, updatedAt = now()))
                preferences.setAssistantEnabled(true)
            } else {
                preferences.setAssistantEnabled(false)
            }
        }
    }

    /** 「重新测试」用已存配置再打一次，成功即回到 READY。 */
    suspend fun retest(): AiResult<AiConnectionReport> {
        val target = target() ?: return AiResult.failed(AiErrorKind.NETWORK, "还没有可用配置")
        val result = client.testConnection(target)
        applyOutcome(result.toOutcome())
        return result
    }

    /** 开关重新打开时，配置齐全但从未验证过的按「疑似异常」处理，让下一次调用去确认。 */
    suspend fun setEnabled(enabled: Boolean) {
        preferences.setAssistantEnabled(enabled)
        if (!enabled) return
        val config = dao.find() ?: return
        if (config.status == AiStatus.UNCONFIGURED.name && keys.targetOf(config.id, config.baseUrl, config.model) != null) {
            dao.upsert(config.copy(status = AiStatus.SUSPECTED.name, updatedAt = now()))
        }
    }

    /** 每次 AI 调用结束后回写状态机结果；返回新状态，调用方据此决定是否降级。 */
    suspend fun applyOutcome(outcome: AiCallOutcome): AiState {
        val config = dao.find() ?: return AiState.UNCONFIGURED
        val change = AiStateMachine.reduce(config.toState(), outcome)
        dao.upsert(
            config.copy(
                status = change.after.status.name,
                consecutiveFailures = change.after.consecutiveFailures,
                lastError = (outcome as? AiCallOutcome.Failed)?.kind?.name,
                updatedAt = now(),
            ),
        )
        return change.after
    }

}

private fun AiConfigEntity.toState(): AiState = AiState(
    status = status.toAiStatus(),
    consecutiveFailures = consecutiveFailures,
)

/** 开关与配置行的唯一合成口径：页面不自己拼「有没有 Key」「开没开」。 */
private fun availabilityOf(config: AiConfiguration?, enabled: Boolean): AiAvailability =
    AiAvailability(
        status = config?.status ?: AiStatus.UNCONFIGURED,
        enabled = enabled,
        consecutiveFailures = config?.consecutiveFailures ?: 0,
        visionCapable = config?.visionCapable ?: false,
    )

private fun AiConfigEntity.toDomain(): AiConfiguration = AiConfiguration(
    id = id,
    provider = provider,
    baseUrl = baseUrl.orEmpty(),
    model = model.orEmpty(),
    keyReference = keyReference,
    active = active,
    // 备份包不含 API Key：恢复后状态列可能还是 READY，但没有 Key 就不可用，照实显示未配置。
    status = if (keyReference.isNullOrBlank()) AiStatus.UNCONFIGURED else status.toAiStatus(),
    visionCapable = visionCapable,
    lastError = lastError,
    consecutiveFailures = consecutiveFailures,
    updatedAt = updatedAt,
)

/** 状态列被外部写入过非法值时按未配置处理，绝不让一个脏字符串把设置页搞崩。 */
private fun String?.toAiStatus(): AiStatus =
    runCatching { AiStatus.valueOf(this ?: "") }.getOrDefault(AiStatus.UNCONFIGURED)
