package io.github.guohangbk.tally.domain.ai

import io.github.guohangbk.tally.domain.model.AiStatus

/** 小莉配置的可读视图；API Key 以引用形式出现，明文不在此模型里。 */
data class AiConfiguration(
    /** 配置行自己的 id：多条配置靠它区分，也是 Key 的存放键的一部分。 */
    val id: String,
    val provider: String,
    val baseUrl: String,
    val model: String,
    val keyReference: String?,
    /** 是否正在生效：多条配置里只有一条为真。 */
    val active: Boolean = false,
    val status: AiStatus,
    val visionCapable: Boolean,
    val lastError: String?,
    val consecutiveFailures: Int,
    val updatedAt: Long,
) {
    /** 三项齐全才谈得上「有效配置」；否则无论状态列写什么都按未配置处理。 */
    val isComplete: Boolean
        get() = baseUrl.isNotBlank() && model.isNotBlank() && !keyReference.isNullOrBlank()

    val displayName: String
        get() = AiProviderPresets.byKey(provider).name

    companion object {
        /** 全部服务商都走 OpenAI 兼容协议；这个字面量代表「自定义」，旧配置也落在这里。 */
        const val PROVIDER_OPENAI_COMPATIBLE: String = "openai_compatible"
    }
}

/** 用户在配置表单里填写、尚未落库的候选配置。 */
data class AiConfigDraft(
    val provider: String = AiConfiguration.PROVIDER_OPENAI_COMPATIBLE,
    val baseUrl: String = "",
    val model: String = "",
    /** 用户勾选「图片输入」的结果；不勾选时对话页不发图，只走文字与语音。 */
    val visionCapable: Boolean = false,
    val apiKey: String = "",
    val privacyAcknowledged: Boolean = false,
) {
    val missingField: String?
        get() = when {
            baseUrl.isBlank() -> "请填写服务地址"
            !baseUrl.startsWith("https://") && !baseUrl.startsWith("http://") -> "服务地址需要以 http(s):// 开头"
            model.isBlank() -> "请填写模型名"
            apiKey.isBlank() -> "请填写 API Key"
            else -> null
        }
}

/**
 * 小莉状态机。这里只有纯函数，落库与副作用留在 data 层，
 * 方便把每一种错误类型的跳转一次性穷举验证。
 */
sealed interface AiCallOutcome {
    data object Success : AiCallOutcome

    data class Failed(val kind: AiErrorKind) : AiCallOutcome
}

data class AiState(
    val status: AiStatus,
    val consecutiveFailures: Int,
) {
    companion object {
        val UNCONFIGURED = AiState(AiStatus.UNCONFIGURED, 0)
    }
}

data class AiStateChange(
    val before: AiState,
    val after: AiState,
) {
    val droppedToDisconnected: Boolean
        get() = before.status != AiStatus.DISCONNECTED && after.status == AiStatus.DISCONNECTED

    val recovered: Boolean
        get() = before.status != AiStatus.READY && after.status == AiStatus.READY
}

object AiStateMachine {
    /** 连续这么多次链路失败即认为已断开；schema 与取消不计入。 */
    const val DISCONNECT_AFTER_FAILURES: Int = 3

    fun reduce(current: AiState, outcome: AiCallOutcome): AiStateChange {
        val after = when (outcome) {
            AiCallOutcome.Success -> current.copy(status = AiStatus.READY, consecutiveFailures = 0)

            is AiCallOutcome.Failed -> when (outcome.kind) {
                // 结果不合格只是这一次丢弃，链路本身仍然可用，不能因此降级。
                AiErrorKind.SCHEMA, AiErrorKind.CANCELLED -> current

                // 这三类不需要攒次数：重试也不会成功，直接让用户去改配置。
                AiErrorKind.AUTH, AiErrorKind.QUOTA, AiErrorKind.MODEL_NOT_FOUND ->
                    current.copy(
                        status = AiStatus.DISCONNECTED,
                        consecutiveFailures = current.consecutiveFailures + 1,
                    )

                AiErrorKind.NETWORK, AiErrorKind.SERVER -> {
                    val failures = current.consecutiveFailures + 1
                    current.copy(
                        status = if (failures >= DISCONNECT_AFTER_FAILURES) {
                            AiStatus.DISCONNECTED
                        } else {
                            AiStatus.SUSPECTED
                        },
                        consecutiveFailures = failures,
                    )
                }
            }
        }
        return AiStateChange(current, after)
    }
}

/** 网络层的结果翻译成状态机输入；成功与否之外还要带上错误类型。 */
fun AiResult<*>.toOutcome(): AiCallOutcome = when (this) {
    is AiResult.Success -> AiCallOutcome.Success
    is AiResult.Failure -> AiCallOutcome.Failed(failure.kind)
}
