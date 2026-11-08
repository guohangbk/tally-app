package io.github.guohangbk.tally.data.ai

import io.github.guohangbk.tally.domain.ai.AiClient
import io.github.guohangbk.tally.domain.ai.AiCompletion
import io.github.guohangbk.tally.domain.ai.AiErrorKind
import io.github.guohangbk.tally.domain.ai.AiFailure
import io.github.guohangbk.tally.domain.ai.AiRequest
import io.github.guohangbk.tally.domain.ai.AiResult
import io.github.guohangbk.tally.domain.ai.toOutcome
import io.github.guohangbk.tally.domain.model.AiStatus

/** 小莉当前能不能用，以及为什么不能用。页面只认这一层，不自己拼开关与状态。 */
enum class AiUnavailable(val reason: String) {
    NotConfigured("还没有配置小莉"),
    SwitchedOff("小莉开关已关闭"),
    Disconnected("小莉暂时联系不上"),
}

data class AiAvailability(
    val status: AiStatus,
    val enabled: Boolean,
    val consecutiveFailures: Int,
    val visionCapable: Boolean,
) {
    /** 入口显示条件：开关打开且配置有效。管的是统计页与对话页里的 AI 触点；＋ 右臂另走 [entry]，常驻。 */
    val showsEntry: Boolean
        get() = enabled && status != AiStatus.UNCONFIGURED

    /** 已就绪与疑似异常都能用；已断开只做用户显式重试。 */
    val usableQuietly: Boolean
        get() = showsEntry && status != AiStatus.DISCONNECTED

    /**
     * ＋ 右臂的落点。右臂常驻：关闭时去设置里的开关区、开启但没配好去配置页，
     * 只有真的可用才进对话——入口随状态消失，用户就没机会知道「右滑有小莉」这件事。
     */
    val entry: AssistantEntry
        get() = when {
            !enabled -> AssistantEntry.Settings
            status == AiStatus.UNCONFIGURED -> AssistantEntry.Configure
            else -> AssistantEntry.Chat
        }
}

/** 右滑小莉的三种去向，导航与右臂文案共用。 */
enum class AssistantEntry { Settings, Configure, Chat }

sealed interface AiEnhancement {
    data class Applied(val completion: AiCompletion) : AiEnhancement

    data class Skipped(val failure: AiFailure?) : AiEnhancement

    val text: String? get() = (this as? Applied)?.completion?.text
}

/**
 * 所有 AI 调用的唯一入口（「AI 是增强，不是依赖」）。
 *
 * [enhance] 用在记账链路上：任何不可用都静默返回 Skipped，调用方回退端侧规则，
 * 记账流程绝不因为小莉而中断；[call] 用在小莉自己的页面（分析、问答、重新测试）：
 * 已断开也允许打一次，因为那是用户主动点的，同时也是唯一的自动恢复时机。
 */
class AiGateway(
    private val client: AiClient,
    private val configs: AiConfigRepository,
) {
    suspend fun availability(): AiAvailability = configs.availability()

    suspend fun enhance(request: AiRequest): AiEnhancement {
        val availability = availability()
        if (!availability.showsEntry) return AiEnhancement.Skipped(null)
        if (!availability.usableQuietly) {
            return AiEnhancement.Skipped(AiFailure(AiErrorKind.NETWORK, AiUnavailable.Disconnected.reason))
        }
        return invoke(request)
    }

    suspend fun call(request: AiRequest): AiResult<AiCompletion> {
        val availability = availability()
        if (!availability.showsEntry) {
            return AiResult.failed(AiErrorKind.SCHEMA, AiUnavailable.NotConfigured.reason)
        }
        return when (val outcome = invoke(request)) {
            is AiEnhancement.Applied -> AiResult.success(outcome.completion)
            is AiEnhancement.Skipped -> AiResult.failed(outcome.failure?.kind ?: AiErrorKind.NETWORK, outcome.failure?.detail)
        }
    }

    /** 流式对话：可用性与一次性请求同一套判定，增量直接回调给界面。 */
    suspend fun stream(request: AiRequest, onDelta: (String) -> Unit): AiResult<AiCompletion> {
        val availability = availability()
        if (!availability.showsEntry) {
            return AiResult.failed(AiErrorKind.SCHEMA, AiUnavailable.NotConfigured.reason)
        }
        if (!availability.usableQuietly) {
            return AiResult.failed(AiErrorKind.NETWORK, AiUnavailable.Disconnected.reason)
        }
        val target = configs.target() ?: return AiResult.failed(AiErrorKind.SCHEMA, AiUnavailable.NotConfigured.reason)
        val result = client.stream(target, request, onDelta)
        configs.applyOutcome(result.toOutcome())
        return result
    }

    private suspend fun invoke(request: AiRequest): AiEnhancement {
        val target = configs.target() ?: return AiEnhancement.Skipped(AiFailure(AiErrorKind.SCHEMA, AiUnavailable.NotConfigured.reason))
        val result = client.complete(target, request)
        configs.applyOutcome(result.toOutcome())
        return when (result) {
            is AiResult.Success -> AiEnhancement.Applied(result.value)
            is AiResult.Failure -> AiEnhancement.Skipped(result.failure)
        }
    }
}
