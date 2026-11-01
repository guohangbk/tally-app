package io.github.guohangbk.tally.data.ai

import io.github.guohangbk.tally.domain.ai.AiPart
import io.github.guohangbk.tally.domain.ai.AiRequest
import io.github.guohangbk.tally.domain.ai.AiToolChoice
import io.github.guohangbk.tally.domain.ai.AiToolSpec
import io.github.guohangbk.tally.domain.ai.AiTurn
import io.github.guohangbk.tally.domain.model.AiMessageRole
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * OpenAI 兼容协议的报文形态。只有这一层知道 `content` 什么时候是字符串、
 * 什么时候是数组，domain 层的 AiTurn 不带任何协议痕迹。
 */
internal object AiWire {
    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        explicitNulls = false
    }

    /** 纯文本消息压成字符串，含图片才展开成 content 数组。 */
    fun contentOf(parts: List<AiPart>): JsonElement {
        val text = parts.filterIsInstance<AiPart.Text>().joinToString("\n") { it.value }
        val images = parts.filterIsInstance<AiPart.Image>()
        if (images.isEmpty()) return JsonPrimitive(text)
        return JsonArray(
            buildList {
                if (text.isNotEmpty()) add(JsonObject(mapOf("type" to JsonPrimitive("text"), "text" to JsonPrimitive(text))))
                images.forEach { image ->
                    add(
                        JsonObject(
                            mapOf(
                                "type" to JsonPrimitive("image_url"),
                                "image_url" to JsonObject(mapOf("url" to JsonPrimitive(image.dataUrl))),
                            ),
                        ),
                    )
                }
            },
        )
    }

    /** 回复里的 content 同样有两种形态，还可能被某些网关放到 reasoning_content 里。 */
    fun textOf(element: JsonElement?): String {
        return when (element) {
            is JsonPrimitive -> element.contentOrNull.orEmpty()
            is JsonArray -> element.joinToString("") { item ->
                (item as? JsonObject)
                    ?.get("text")
                    ?.let { (it as? JsonPrimitive)?.contentOrNull.orEmpty() }
                    .orEmpty()
            }

            is JsonObject -> element["text"]?.let { (it as? JsonPrimitive)?.contentOrNull.orEmpty() }.orEmpty()
            null -> ""
        }
    }

    fun bodyOf(request: AiRequest, model: String, responseFormat: JsonElement?, stream: Boolean = false): String {
        val messages = buildList {
            add(WireMessage("system", JsonPrimitive(request.instruction)))
            request.turns.forEach { add(it.toWire()) }
        }
        val tools = request.tools.takeIf { it.isNotEmpty() }?.map { it.toWire() }
        return json.encodeToString(
            ChatRequest(
                model = model,
                messages = messages,
                maxTokens = request.maxTokens,
                responseFormat = responseFormat,
                stream = stream.takeIf { it },
                tools = tools,
                toolChoice = toolChoiceOf(request.toolChoice),
                // 只在明确允许并行时才发这个字段；禁并行（false）会把"一句话三笔"直接掐死。
                parallelToolCalls = true.takeIf { request.allowParallelTools && tools != null },
            ),
        )
    }

    private fun AiTurn.toWire(): WireMessage =
        WireMessage(role = role.wireName(), content = contentOf(parts))

    private fun AiToolSpec.toWire(): WireTool = WireTool(
        type = "function",
        function = WireToolFunction(
            name = name,
            description = description,
            parameters = AiWire.json.parseToJsonElement(parametersSchema),
        ),
    )

    /**
     * Auto 时干脆不发这个字段：老网关收到不认识的 tool_choice 取值会整包 400，
     * 少发一个字段比多发一个字段安全。
     */
    private fun toolChoiceOf(choice: AiToolChoice): JsonElement? = when (choice) {
        AiToolChoice.Auto -> null
        AiToolChoice.Required -> JsonPrimitive("required")
        is AiToolChoice.Named -> JsonObject(
            mapOf(
                "type" to JsonPrimitive("function"),
                "function" to JsonObject(mapOf("name" to JsonPrimitive(choice.name))),
            ),
        )
    }

    private fun AiMessageRole.wireName(): String = when (this) {
        AiMessageRole.SYSTEM -> "system"
        AiMessageRole.USER -> "user"
        AiMessageRole.ASSISTANT -> "assistant"
    }
}

@Serializable
internal data class WireMessage(
    val role: String,
    /** 只调工具不说话的回复里 content 可能缺席或为 null，所以可空。 */
    val content: JsonElement? = null,
    @SerialName("tool_calls") val toolCalls: List<WireToolCall>? = null,
)

/**
 * `tools` 数组里的一项：目前只有 function 一种类型。
 * [type] 不给默认值——外层 Json 配置了 encodeDefaults = false，带默认值的字段会被整个跳过，
 * 而 `type` 是网关必看的字段，只能显式传、显式写。
 */
@Serializable
internal data class WireTool(
    val type: String,
    val function: WireToolFunction,
)

@Serializable
internal data class WireToolFunction(
    val name: String,
    val description: String,
    val parameters: JsonElement,
)

@Serializable
internal data class WireToolCall(
    val id: String? = null,
    /** 流式语义里的位置号：同一 index 的多个片段属于同一次调用。 */
    val index: Int? = null,
    val type: String? = null,
    val function: WireToolCallFunction? = null,
)

@Serializable
internal data class WireToolCallFunction(
    val name: String? = null,
    /**
     * 参数原文。网关有三种给法，都得收：字符串形态的 JSON（规范写法）、
     * 直接一个对象（少数网关），以及**长参数拆成多段**——后续段没有 name、只带一小截 arguments，
     * 真机上就是这么把 `{"` 之后的一半丢掉的。
     */
    val arguments: JsonElement? = null,
)

@Serializable
internal data class ChatRequest(
    val model: String,
    val messages: List<WireMessage>,
    @SerialName("max_tokens") val maxTokens: Int? = null,
    @SerialName("response_format") val responseFormat: JsonElement? = null,
    val temperature: Double? = null,
    /** 非空即为流式请求（SSE）。 */
    val stream: Boolean? = null,
    val tools: List<WireTool>? = null,
    @SerialName("tool_choice") val toolChoice: JsonElement? = null,
    @SerialName("parallel_tool_calls") val parallelToolCalls: Boolean? = null,
)

/** SSE 的一帧：`data: {"choices":[{"delta":{"content":"…"}}]}`。 */
@Serializable
internal data class ChatStreamChunk(
    val choices: List<WireStreamChoice> = emptyList(),
    val usage: WireUsage? = null,
)

@Serializable
internal data class WireStreamChoice(
    val delta: WireDelta? = null,
    @SerialName("finish_reason") val finishReason: String? = null,
)

/**
 * 流式增量。`content` 与整包回复一样有两种形态（字符串 / 部件数组），所以按 JsonElement 收，
 * 交给 [AiWire.textOf] 统一压成文本；`reasoning_content` 是思维链，故意不读。
 * 工具调用的参数在流式里是**一段一段**给的，所以这里必须带上 tool_calls，
 * 否则「网关对非流式请求也回 SSE」时抽账就一笔也拿不到。
 */
@Serializable
internal data class WireDelta(
    val content: JsonElement? = null,
    @SerialName("tool_calls") val toolCalls: List<WireToolCall>? = null,
)

@Serializable
internal data class ChatResponse(
    val choices: List<WireChoice> = emptyList(),
    val usage: WireUsage? = null,
    @SerialName("error") val error: WireError? = null,
)

@Serializable
internal data class WireChoice(
    val message: WireMessage? = null,
    /** 少数网关把 tool_calls 挂在 choice 而不是 message 上，两边都读。 */
    @SerialName("tool_calls") val toolCalls: List<WireToolCall>? = null,
    @SerialName("finish_reason") val finishReason: String? = null,
)

@Serializable
internal data class WireUsage(
    @SerialName("prompt_tokens") val promptTokens: Int? = null,
    @SerialName("completion_tokens") val completionTokens: Int? = null,
)

@Serializable
internal data class WireError(
    val code: String? = null,
    val message: String? = null,
)
