package io.github.guohangbk.tally.domain.ai

import io.github.guohangbk.tally.domain.model.AiMessageRole

/**
 * 小莉的调用契约。domain 层只描述「发什么、回什么、怎么错」，
 * 不关心 HTTP、JSON 与序列化，具体实现放在 data/ai 的薄客户端里。
 */

/** 一次调用需要的端点信息。API Key 只在内存里流动，不落库、不进日志、不进备份。 */
data class AiTarget(
    val baseUrl: String,
    val model: String,
    val apiKey: String,
)

/** 消息内容只有文本与图片两种；图片走 data URL，只有截图视觉路径会用到。 */
sealed interface AiPart {
    data class Text(val value: String) : AiPart

    /** [dataUrl] 形如 `data:image/webp;base64,...`，由调用方预先压缩与编码。 */
    data class Image(val dataUrl: String) : AiPart
}

data class AiTurn(
    val role: AiMessageRole,
    val parts: List<AiPart>,
) {
    val containsImage: Boolean get() = parts.any { it is AiPart.Image }

    val plainText: String get() = parts.filterIsInstance<AiPart.Text>().joinToString("\n") { it.value }

    companion object {
        fun system(text: String) = AiTurn(AiMessageRole.SYSTEM, listOf(AiPart.Text(text)))

        fun user(text: String) = AiTurn(AiMessageRole.USER, listOf(AiPart.Text(text)))

        /** 一条消息可以附几张图（小莉对话最多三张），顺序就是用户选图的顺序。 */
        fun userWithImages(text: String, dataUrls: List<String>) =
            AiTurn(
                AiMessageRole.USER,
                buildList {
                    if (text.isNotEmpty()) add(AiPart.Text(text))
                    dataUrls.forEach { add(AiPart.Image(it)) }
                },
            )

        fun assistant(text: String) = AiTurn(AiMessageRole.ASSISTANT, listOf(AiPart.Text(text)))
    }
}

/**
 * 一个可被模型调用的工具（OpenAI 兼容协议里的 function）。
 * [parametersSchema] 直接放 JSON Schema 文本，data 层原样拼进请求体，domain 层不碰序列化。
 */
data class AiToolSpec(
    val name: String,
    val description: String,
    val parametersSchema: String,
)

/**
 * 模型怎么选工具。[Named] 最省事但会堵死「这条不是账」的出路，
 * 所以抽账用的是 [Required]：必须调一个工具，调哪个由模型判断。
 */
sealed interface AiToolChoice {
    /** 不发送 tool_choice 字段，按服务端默认（等价 auto）；兼容性最好。 */
    data object Auto : AiToolChoice

    /** 必须调用工具之一，不支持该取值的网关会报 400，由降级链接住。 */
    data object Required : AiToolChoice

    /** 只能调用指定工具。 */
    data class Named(val name: String) : AiToolChoice
}

/**
 * 一次补全请求。[tools] 非空时优先走工具调用；网关不支持工具时，
 * 若同时给了 [jsonSchema] 就降级成结构化输出，再降级成纯文本围栏——三条路共用同一份字段定义。
 * [jsonSchema] 存在且没有工具时，优先用 response_format 的结构化模式，
 * 服务端不支持时由实现降级到 `json_object`，再降级到纯文本围栏。
 */
data class AiRequest(
    val instruction: String,
    val turns: List<AiTurn>,
    val jsonSchema: String? = null,
    val maxTokens: Int? = null,
    val tools: List<AiToolSpec> = emptyList(),
    val toolChoice: AiToolChoice = AiToolChoice.Auto,
    /** 允许模型在一次响应里调用多个工具。抽账要开：一句「早20 中5 地铁30」该出三笔。 */
    val allowParallelTools: Boolean = false,
)

data class AiUsage(
    val promptTokens: Int?,
    val completionTokens: Int?,
) {
    val totalTokens: Int?
        get() = promptTokens?.plus(completionTokens ?: 0)
}

/**
 * 模型点名的工具调用。[arguments] 是模型写的参数原文，属于不可信输入，
 * 必须过本地 schema 校验与账本数据比对后才能生效（提示注入防护）。
 */
data class AiToolCall(
    val name: String,
    val arguments: String,
)

/** 传输成功且拿到了可用文本或工具调用。是否合格由调用方的 schema 校验决定。 */
data class AiCompletion(
    val text: String,
    val usage: AiUsage,
    val latencyMillis: Long,
    val toolCalls: List<AiToolCall> = emptyList(),
    /** 服务端给的回填原因（stop / length / tool_calls…）。`length` 意味着参数是被 token 预算截断的，不是模型写错。 */
    val finishReason: String? = null,
)

/**
 * 失败类型直接喂给状态机：AUTH/QUOTA/MODEL_NOT_FOUND 一票否决，
 * NETWORK/SERVER 计入连续失败，SCHEMA 与 CANCELLED 不影响连接健康度。
 */
enum class AiErrorKind(
    /** 是否计入连续失败次数；schema 错误只是这一次结果不可用，链路本身没问题。 */
    val countsTowardDisconnect: Boolean,
    /** 界面提示语，错误分类表。 */
    val userMessage: String,
) {
    NETWORK(true, "网络异常，请检查连接后重试"),
    AUTH(true, "鉴权失败，请重新配置 API Key"),
    QUOTA(true, "额度不足或被限流，请稍后再试"),
    MODEL_NOT_FOUND(true, "模型名不存在，请检查模型名"),
    SERVER(true, "服务商暂时不可用，已重试一次"),
    SCHEMA(false, "解析结果异常，已使用本地规则"),
    CANCELLED(false, "已取消"),
}

data class AiFailure(
    val kind: AiErrorKind,
    val detail: String? = null,
)

sealed interface AiResult<out T> {
    data class Success<T>(val value: T) : AiResult<T>

    data class Failure(val failure: AiFailure) : AiResult<Nothing>

    val kind: AiErrorKind?
        get() = (this as? Failure)?.failure?.kind

    fun <R> map(transform: (T) -> R): AiResult<R> = when (this) {
        is Success -> Success(transform(value))
        is Failure -> this
    }

    fun getOrNull(): T? = (this as? Success)?.value

    fun errorOrNull(): AiFailure? = (this as? Failure)?.failure

    companion object {
        fun <T> success(value: T): AiResult<T> = Success(value)

        fun failed(kind: AiErrorKind, detail: String? = null): AiResult<Nothing> = Failure(AiFailure(kind, detail))
    }
}

/**
 * 连接测试与能力探测的返回。目前只有 OpenAI 兼容协议，探测结果就是「能不能用」与「能不能看图」。
 */
data class AiConnectionReport(
    val model: String,
    val usage: AiUsage,
    val latencyMillis: Long,
)

interface AiClient {
    /** 最小真实请求：一个 token 的对话。测试通过才允许保存配置。 */
    suspend fun testConnection(target: AiTarget): AiResult<AiConnectionReport>

    suspend fun complete(target: AiTarget, request: AiRequest): AiResult<AiCompletion>

    /**
     * 流式对话：每收到一段增量回调一次 [onDelta]，返回的 [AiCompletion] 是拼好的全文。
     * 网关不支持流式时回 SCHEMA，由调用方回退到 [complete]，界面不会因此少一段回复。
     */
    suspend fun stream(target: AiTarget, request: AiRequest, onDelta: (String) -> Unit): AiResult<AiCompletion>
}

/**
 * 模型偶尔无视「不要输出 markdown」的叮嘱，把 `**加粗**`、`# 标题`、表格、链接带回来。
 * 对话与报告都是纯文本展示，落库前统一剥掉，页面上就不会出现星号与方括号。
 *
 * 只剥「语法」，不折行：剥完仍然是一行一条，按行认标签的兜底才认得出来。
 */
fun assistantPlainText(text: String): String = text
    .replace(Regex("""\*\*|__|`"""), "")
    .replace(Regex("""(?m)^#{1,6}\s*"""), "")
    .replace(Regex("""(?m)^\s*[*-]\s+"""), "· ")
    .replace(Regex("""(?m)^\s*>\s?"""), "")
    .replace(Regex("""(?m)^\s*\d+[.、)]\s*"""), "")
    .replace(Regex("""!?\[([^\]]*)\]\([^)]*\)"""), "$1")
    .replace(Regex("""\|"""), " ")
    .trim()
