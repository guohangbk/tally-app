package io.github.guohangbk.tally.data.ai

import io.github.guohangbk.tally.domain.ai.AiClient
import io.github.guohangbk.tally.domain.ai.AiCompletion
import io.github.guohangbk.tally.domain.ai.AiErrorKind
import io.github.guohangbk.tally.domain.ai.AiFailure
import io.github.guohangbk.tally.domain.ai.AiRequest
import io.github.guohangbk.tally.domain.ai.AiResult
import io.github.guohangbk.tally.domain.ai.toOutcome
import io.github.guohangbk.tally.domain.model.AiStatus
import io.github.guohangbk.tally.data.debug.DebugLog

/**
 * 小莉当前能不能用，以及为什么不能用。页面只认这一层，不自己拼开关与状态。
 * 文案一律说「服务商」：全系统只有这一个称呼，别再造第二个词。
 */
enum class AiUnavailable(val reason: String) {
    NotConfigured("还没配置服务商"),
    /** 同一句话也会出现在小莉对话页，所以写「AI 开关」而不是只属于自动记账的「AI 提取」。 */
    SwitchedOff("AI 开关没打开"),
    Disconnected("服务商暂时连不上"),
}

data class AiAvailability(
    val status: AiStatus,
    val enabled: Boolean,
    val consecutiveFailures: Int,
    val visionCapable: Boolean,
    /** 上一次失败的分类（NETWORK / TIMEOUT / AUTH …），只进日志，不给用户看。 */
    val lastError: String? = null,
    /** 上一次调用的时间：后台这条路没有「重新测试」按钮，靠它决定什么时候自己重试一次。 */
    val lastTriedAt: Long = 0L,
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

    /** 用不了时该说哪一句：总开关关着和没配服务商是两件事，别都说成「没配」。 */
    fun blockedReason(): String =
        if (enabled) AiUnavailable.NotConfigured.reason else AiUnavailable.SwitchedOff.reason
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
    private val now: () -> Long = System::currentTimeMillis,
) {
    suspend fun availability(): AiAvailability = configs.availability()

    suspend fun enhance(request: AiRequest): AiEnhancement {
        val availability = availability()
        if (!availability.showsEntry) return AiEnhancement.Skipped(null)
        if (!availability.usableQuietly && !probeDue(availability)) {
            val last = availability.lastError?.let { "（上次 $it）" }.orEmpty()
            DebugLog.log("AI 请求", "跳过：处于断开状态，还没到重试时间$last")
            return AiEnhancement.Skipped(AiFailure(AiErrorKind.NETWORK, AiUnavailable.Disconnected.reason))
        }
        if (!availability.usableQuietly) {
            DebugLog.log("AI 请求", "断开超过 ${RETRY_MILLIS / 60_000} 分钟，后台再试一次")
        }
        return invoke(request)
    }

    /**
     * 后台链路没有「重新测试」按钮：状态一旦落到 DISCONNECTED 就再也不会调，等于永久失效。
     * 隔几分钟放一次探测，成功即回 READY；失败就继续按断开处理。
     */
    private fun probeDue(availability: AiAvailability): Boolean =
        availability.status == AiStatus.DISCONNECTED &&
            now() - availability.lastTriedAt >= RETRY_MILLIS

    private companion object {
        const val RETRY_MILLIS = 3 * 60_000L
    }

    suspend fun call(request: AiRequest): AiResult<AiCompletion> {
        val availability = availability()
        if (!availability.showsEntry) {
            return AiResult.failed(AiErrorKind.SCHEMA, availability.blockedReason())
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
            return AiResult.failed(AiErrorKind.SCHEMA, availability.blockedReason())
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
