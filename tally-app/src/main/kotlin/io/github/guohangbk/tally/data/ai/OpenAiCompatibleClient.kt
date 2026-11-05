package io.github.guohangbk.tally.data.ai

import io.github.guohangbk.tally.data.debug.DebugLog
import io.github.guohangbk.tally.domain.ai.AiClient
import io.github.guohangbk.tally.domain.ai.AiCompletion
import io.github.guohangbk.tally.domain.ai.AiConnectionReport
import io.github.guohangbk.tally.domain.ai.AiErrorKind
import io.github.guohangbk.tally.domain.ai.AiRequest
import io.github.guohangbk.tally.domain.ai.AiResult
import io.github.guohangbk.tally.domain.ai.AiTarget
import io.github.guohangbk.tally.domain.ai.AiToolCall
import io.github.guohangbk.tally.domain.ai.AiToolChoice
import io.github.guohangbk.tally.domain.ai.AiTurn
import io.github.guohangbk.tally.domain.ai.AiUsage
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import kotlinx.serialization.json.Json

/**
 * OpenAI 兼容协议的薄客户端：只打 `POST {base}/chat/completions`，没有 SDK。
 * 支持两种结构化输出——`tools` 函数调用与 `response_format` schema，并在这两者之上做降级链；
 * 流式只回文本增量，工具调用走一次性请求（抽账那类调用不需要边收边显示）。
 *
 * 错误在这里就归成 [AiErrorKind]，domain 与 UI 不再看 HTTP 码；
 * API Key 只进请求头，日志与异常信息里都不出现。
 */
class OpenAiCompatibleClient(
    private val client: OkHttpClient = OkHttpClient(),
    private val json: Json = AiWire.json,
) : AiClient {

    /**
     * 连通性探测：能不能鉴权、能不能路由到模型，与「这次有没有吐出文字」是两件事。
     *
     * 先打 `GET {base}/models`——不生成 token、几百毫秒就回，地址与 Key 一次验完；
     * 列表里点名了目标模型就算通，不必再等一个 ping 想几十秒。
     * 网关没实现这个端点、或者列表里没有这个模型，才退回真实补全。
     *
     * 退回那条不带 `max_tokens`：推理型模型会把小额预算全花在 `reasoning_content` 上，
     * 限额反而可能把回复截断成空 content。平台按 200 返回即算链路正常，所以走 [tolerant]。
     */
    override suspend fun testConnection(target: AiTarget): AiResult<AiConnectionReport> {
        val startedAt = System.nanoTime()
        if (modelsListContains(target)) {
            return AiResult.success(
                AiConnectionReport(
                    model = target.model,
                    usage = AiUsage(promptTokens = null, completionTokens = null),
                    latencyMillis = (System.nanoTime() - startedAt) / 1_000_000L,
                ),
            )
        }
        val request = AiRequest(
            instruction = PING_INSTRUCTION,
            turns = listOf(AiTurn.user("ping")),
        )
        return send(target, request, tolerant = true, budgetMillis = PROBE_BUDGET_MS)
            .map { completion ->
                AiConnectionReport(model = target.model, usage = completion.usage, latencyMillis = completion.latencyMillis)
            }
            .asProbeFailure()
    }

    /** 探测失败在配置页上就是「没测通」，别把 Unable to resolve host 这类英文异常直接甩给用户。 */
    private fun <T> AiResult<T>.asProbeFailure(): AiResult<T> = when (this) {
        is AiResult.Failure if failure.kind == AiErrorKind.NETWORK -> AiResult.failed(AiErrorKind.NETWORK, PROBE_NETWORK_MESSAGE)
        else -> this
    }

    /**
     * `GET {base}/models`：200 且列表里点名了目标模型才算验通。
     * 端点不存在、返回看不懂、超时一律 false 交给真实补全去判——探测多花几秒，
     * 也比把一个好配置误判成不通强。
     */
    private suspend fun modelsListContains(target: AiTarget): Boolean = withContext(Dispatchers.IO) {
        val url = modelsEndpoint(target.baseUrl) ?: return@withContext false
        val http = client.newBuilder()
            .connectTimeout(MODELS_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(MODELS_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .callTimeout(MODELS_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .build()
        val call = http.newCall(
            Request.Builder()
                .url(url)
                .get()
                .addHeader("Authorization", "Bearer ${target.apiKey}")
                .build(),
        )
        runCatching {
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    DebugLog.log("小莉探测", "GET models → HTTP ${response.code}，退回 chat/completions 探测")
                    return@use false
                }
                val listed = response.body?.string().orEmpty().contains("\"${target.model}\"")
                DebugLog.log("小莉探测", "GET models → 200 · 列表含「${target.model}」=$listed")
                listed
            }
        }.getOrElse {
            DebugLog.log("小莉探测", "GET models 失败 ${it.javaClass.simpleName}，退回 chat/completions 探测")
            false
        }
    }

    override suspend fun complete(target: AiTarget, request: AiRequest): AiResult<AiCompletion> =
        sendStructuredFallbacks(target, request)

    /**
     * 流式：`stream: true` + 逐行读 SSE，`data:` 里取 delta.content 边收边回调。
     * 网关拒绝流式（400）时归到 SCHEMA，调用方会退回一次性请求。
     */
    override suspend fun stream(
        target: AiTarget,
        request: AiRequest,
        onDelta: (String) -> Unit,
    ): AiResult<AiCompletion> = withContext(Dispatchers.IO) {
        val startedAt = System.nanoTime()
        val url = chatEndpoint(target.baseUrl) ?: return@withContext AiResult.failed(AiErrorKind.SCHEMA, "服务地址不合法")
        val body = runCatching { AiWire.bodyOf(request, target.model, responseFormat = null, stream = true) }
            .getOrElse { return@withContext AiResult.failed(AiErrorKind.SCHEMA, "请求构造失败") }
        val http = client.newBuilder().readTimeout(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS).build()
        val call = http.newCall(
            Request.Builder()
                .url(url)
                .post(body.toRequestBody(JSON_MEDIA))
                .addHeader("Authorization", "Bearer ${target.apiKey}")
                .addHeader("Content-Type", "application/json")
                .addHeader("Accept", "text/event-stream")
                .build(),
        )
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext classify(response.code, response.body?.string().orEmpty())
                }
                val source = response.body?.source()
                    ?: return@withContext AiResult.failed(AiErrorKind.NETWORK, "返回为空")
                val state = StreamState()
                feedSse(state, generateSequence { source.readUtf8Line() }.iterator(), onPiece = onDelta)
                replyOf(
                    text = state.content.toString(),
                    toolCalls = stitchToolCalls(state.fragments),
                    finishReason = state.finishReason,
                    usage = state.usage,
                    latencyMillis = (System.nanoTime() - startedAt) / 1_000_000L,
                    tolerant = false,
                    rawForLog = "",
                )
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (io: IOException) {
            if (call.isCanceled()) AiResult.failed(AiErrorKind.CANCELLED) else AiResult.failed(AiErrorKind.NETWORK, io.message)
        }
    }

    /**
     * 结构化输出降级链：
     * tools → json_schema → json_object + schema 写进提示 → 纯文本（由 AiDraftSchema 剥围栏）。
     * 只有网关明确拒绝（400 → SCHEMA）才继续降级，其余错误直接返回；
     * 四条路输出的是同一份字段定义，调用方拿到的校验结果因此不依赖网关支持哪一种。
     */
    private suspend fun sendStructuredFallbacks(
        target: AiTarget,
        request: AiRequest,
    ): AiResult<AiCompletion> {
        if (request.tools.isNotEmpty()) {
            val viaTools = send(target, request, responseFormat = null)
            if (viaTools !is AiResult.Failure || viaTools.kind != AiErrorKind.SCHEMA) return viaTools
            // 最先被网关拒掉的往往是 tool_choice:"required" 和 parallel_tool_calls 这两个字段，
            // 不是 tools 本身。先摘掉它们再走下一级：一步跳到 JSON Schema 就只能返回一个对象，
            // "一句话三笔"当场被压成一张卡——那正是卡片少一笔的来处。
            val relaxed = request.copy(toolChoice = AiToolChoice.Auto, allowParallelTools = false)
            val viaRelaxed = send(target, relaxed, responseFormat = null)
            if (viaRelaxed !is AiResult.Failure || viaRelaxed.kind != AiErrorKind.SCHEMA) return viaRelaxed
            if (request.jsonSchema == null) return viaRelaxed
            return sendSchemaFallbacks(target, relaxed.copy(tools = emptyList()))
        }
        return sendSchemaFallbacks(target, request)
    }

    private suspend fun sendSchemaFallbacks(
        target: AiTarget,
        request: AiRequest,
    ): AiResult<AiCompletion> {
        val schema = request.jsonSchema
        if (schema == null) return send(target, request)

        val plain = request.copy(jsonSchema = null)
        val first = send(target, plain, responseFormat = jsonSchemaFormat(schema))
        if (first !is AiResult.Failure || first.kind != AiErrorKind.SCHEMA) return first

        val embedded = plain.copy(
            instruction = request.instruction + "\n\n只按以下 JSON Schema 输出，不要输出别的内容：\n" + schema,
        )
        val second = send(target, embedded, responseFormat = jsonObjectFormat())
        if (second !is AiResult.Failure || second.kind != AiErrorKind.SCHEMA) return second

        return send(target, embedded, responseFormat = null)
    }

    private suspend fun send(
        target: AiTarget,
        request: AiRequest,
        responseFormat: JsonElement? = structuredFormat(request.jsonSchema),
        tolerant: Boolean = false,
        budgetMillis: Long = 0L,
    ): AiResult<AiCompletion> {
        // 探测给的是整次调用的预算，三个超时都要抬起来：OkHttp 默认 readTimeout 是 10 秒，
        // 只设 callTimeout 的话，网关 10 秒没吐字节就先被判成网络异常，15 秒那条线根本走不到。
        val http = if (budgetMillis > 0L) {
            client.newBuilder()
                .connectTimeout(budgetMillis, TimeUnit.MILLISECONDS)
                .readTimeout(budgetMillis, TimeUnit.MILLISECONDS)
                .callTimeout(budgetMillis, TimeUnit.MILLISECONDS)
                .build()
        } else {
            client.newBuilder().readTimeout(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS).build()
        }
        val url = chatEndpoint(target.baseUrl) ?: return AiResult.failed(AiErrorKind.SCHEMA, "服务地址不合法")

        var attempt = 0
        while (true) {
            attempt++
            val outcome = postOnce(http, url, target, request, responseFormat, tolerant)
            // 服务端错误重试一次仍失败才计入失败次数。
            if (outcome is AiResult.Failure && outcome.kind == AiErrorKind.SERVER && attempt < SERVER_RETRY) continue
            return when (outcome) {
                is AiResult.Success -> AiResult.success(outcome.value)
                is AiResult.Failure -> outcome
            }
        }
    }

    private suspend fun postOnce(
        http: OkHttpClient,
        url: String,
        target: AiTarget,
        request: AiRequest,
        responseFormat: JsonElement?,
        tolerant: Boolean,
    ): AiResult<AiCompletion> =
        withContext(Dispatchers.IO) {
            val startedAt = System.nanoTime()
            val body = runCatching { AiWire.bodyOf(request, target.model, responseFormat) }
                .getOrElse { return@withContext AiResult.failed(AiErrorKind.SCHEMA, "请求构造失败") }
            DebugLog.log(
                "小莉请求",
                "→ ${target.model} · ${request.turns.size} 轮 · tokens=${request.maxTokens ?: "-"} · " +
                    "tools=${request.tools.size} · format=${responseFormat?.let { shapeOf(it) } ?: "-"} · ${body.length}B",
            )
            val call = http.newCall(
                Request.Builder()
                    .url(url)
                    .post(body.toRequestBody(JSON_MEDIA))
                    .addHeader("Authorization", "Bearer ${target.apiKey}")
                    .addHeader("Content-Type", "application/json")
                    .build(),
            )
            try {
                call.execute().use { response ->
                    val text = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        DebugLog.log("小莉请求", "← HTTP ${response.code} ${text.take(MAX_ERROR_DETAIL)}")
                        return@withContext classify(response.code, text)
                    }
                    val elapsed = (System.nanoTime() - startedAt) / 1_000_000L
                    decode(text, elapsed, tolerant)
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (io: IOException) {
                // callTimeout 到点是「取消这次 call」实现的，抛出来的也是 IOException("canceled")。
                // 这一条链路上没有任何地方 call.cancel()（协程 Job 取消传不进阻塞的 execute()），
                // 所以到这里只可能是超时——以前按 isCanceled 判成「已取消」，用户就以为是自己点了停止。
                DebugLog.log("小莉请求", "网络失败 ${io.javaClass.simpleName} ${io.message}")
                if (io.isTimeout()) {
                    AiResult.failed(AiErrorKind.NETWORK, TIMEOUT_MESSAGE)
                } else {
                    AiResult.failed(AiErrorKind.NETWORK, io.message)
                }
            }
        }

    private fun decode(text: String, latencyMillis: Long, tolerant: Boolean): AiResult<AiCompletion> {
        val body = text.trim()
        // 有些网关对**非流式请求**也回 SSE：整包不是一份 JSON，而是一串 `data: {…}` 分片。
        // 以前这里直接判「返回不是合法 JSON」，而 SCHEMA 又会被降级链当成网关拒绝，
        // 于是一路降到最后一档仍然失败——抽账跳过、聊天报错，其实差的就是这一步合并。
        if (body.startsWith(SSE_DATA_PREFIX)) {
            val state = StreamState()
            feedSse(state, body.lineSequence().iterator(), onPiece = {})
            val toolCalls = stitchToolCalls(state.fragments)
            DebugLog.log(
                "小莉请求",
                "← 收到的是流式分片，已合并 · content=${state.content.length}字 · " +
                    "tools=${toolCalls.size} · 分片=${state.chunks}个",
            )
            return replyOf(
                text = state.content.toString(),
                toolCalls = toolCalls,
                finishReason = state.finishReason,
                usage = state.usage,
                latencyMillis = latencyMillis,
                tolerant = tolerant,
                rawForLog = body,
            )
        }
        val response = runCatching { json.decodeFromString<ChatResponse>(body) }.getOrNull()
            ?: return AiResult.failed(AiErrorKind.SCHEMA, "返回不是合法 JSON").also { DebugLog.log("小莉请求", "← JSON 解析失败 ${text.take(MAX_ERROR_DETAIL)}") }
        response.error?.message?.let { return AiResult.failed(AiErrorKind.SCHEMA, it) }
        val choice = response.choices.firstOrNull()
        return replyOf(
            text = AiWire.textOf(choice?.message?.content),
            toolCalls = stitchToolCalls(choice?.message?.toolCalls ?: choice?.toolCalls),
            finishReason = choice?.finishReason,
            usage = response.usage,
            latencyMillis = latencyMillis,
            tolerant = tolerant,
            rawForLog = text,
        )
    }

    /** 两种返回形态（整包 JSON / 流式分片）汇到这一条出口，判空与日志只写一遍。 */
    private fun replyOf(
        text: String,
        toolCalls: List<AiToolCall>,
        finishReason: String?,
        usage: WireUsage?,
        latencyMillis: Long,
        tolerant: Boolean,
        rawForLog: String,
    ): AiResult<AiCompletion> {
        DebugLog.log(
            "小莉请求",
            "← ${latencyMillis}ms · finish=${finishReason ?: "-"} · content=${text.length}字 · " +
                "tools=${toolCalls.size} · prompt=${usage?.promptTokens ?: "-"}/out=${usage?.completionTokens ?: "-"}",
        )
        if (text.isBlank() && toolCalls.isEmpty()) {
            // 探测能容忍空内容；真正要答案的调用仍然是错误，界面才会提示重试。
            if (tolerant) {
                DebugLog.log("小莉请求", "内容为空但链路正常，按连通成功处理 · 原文 ${rawForLog.take(MAX_EMPTY_BODY)}")
                return AiResult.success(
                    AiCompletion(text = "", usage = AiUsage(usage?.promptTokens, usage?.completionTokens), latencyMillis = latencyMillis, finishReason = finishReason),
                )
            }
            DebugLog.log("小莉请求", "没有内容，原文 ${rawForLog.take(MAX_EMPTY_BODY)}")
            return AiResult.failed(AiErrorKind.SCHEMA, "返回里没有内容")
        }
        return AiResult.success(
            AiCompletion(
                text = text,
                usage = AiUsage(usage?.promptTokens, usage?.completionTokens),
                latencyMillis = latencyMillis,
                toolCalls = toolCalls,
                finishReason = finishReason,
            ),
        )
    }

    /** 一次流式回复攒下来的东西：正文、还没拼完的工具碎片、结束原因、用量。 */
    private class StreamState {
        val content = StringBuilder()
        val fragments = mutableListOf<WireToolCall>()
        var finishReason: String? = null
        var usage: WireUsage? = null
        var chunks = 0
    }

    /** 逐行吃 SSE：[DONE] 收尾，其余分片并进 [state]，正文增量实时回调出去。 */
    private fun feedSse(state: StreamState, lines: Iterator<String>, onPiece: (String) -> Unit) {
        while (lines.hasNext()) {
            val payload = ssePayload(lines.next()) ?: continue
            if (payload == SSE_DONE) return
            val chunk = runCatching { json.decodeFromString<ChatStreamChunk>(payload) }.getOrNull() ?: continue
            state.chunks++
            chunk.usage?.let { state.usage = it }
            val choice = chunk.choices.firstOrNull() ?: continue
            choice.finishReason?.let { state.finishReason = it }
            val delta = choice.delta ?: continue
            delta.toolCalls?.let { state.fragments += it }
            val piece = AiWire.textOf(delta.content)
            if (piece.isNotEmpty()) {
                state.content.append(piece)
                onPiece(piece)
            }
        }
    }

    private fun ssePayload(line: String): String? {
        if (!line.startsWith(SSE_DATA_PREFIX)) return null
        return line.removePrefix(SSE_DATA_PREFIX).trim().takeIf { it.isNotEmpty() }
    }

    /**
     * 把碎片拼回调用：带 name 的那条开一次新调用，后面没有 name 的片段接在它尾巴上。
     * 早先按"没有 name 就丢掉"处理，结果参数只剩开头一个 `{`，整条草稿被判成不合格。
     */
    private fun stitchToolCalls(raw: List<WireToolCall>?): List<AiToolCall> {
        val merged = mutableListOf<AiToolCall>()
        raw.orEmpty().forEach { call ->
            val name = call.function?.name?.trim()?.takeIf { it.isNotEmpty() }
            val arguments = call.function?.arguments.argumentText()
            when {
                name != null -> merged += AiToolCall(name = name, arguments = arguments)
                // 无名片段又没前一次调用可接：这种碎片拼不回任何字段定义，只能当没给。
                merged.isEmpty() || arguments.isEmpty() -> Unit
                else -> merged[merged.lastIndex] = merged.last().let { it.copy(arguments = it.arguments + arguments) }
            }
        }
        return merged
    }

    /** 参数既可能是字符串形态的 JSON，也可能直接是个对象；统一成文本交给调用方解析。 */
    private fun JsonElement?.argumentText(): String = when (this) {
        null -> ""
        is JsonPrimitive -> contentOrNull.orEmpty()
        else -> toString()
    }

    /** 只取 response_format 的 `type` 字段，日志里看得懂走的是哪一级降级就够了。 */
    private fun shapeOf(format: JsonElement): String =
        ((format as? JsonObject)?.get("type") as? JsonPrimitive)?.contentOrNull ?: "json"

    private fun classify(code: Int, body: String): AiResult<AiCompletion> {
        val detail = body.take(MAX_ERROR_DETAIL)
        val kind = when {
            code == 401 || code == 403 -> AiErrorKind.AUTH
            code == 402 || code == 429 -> AiErrorKind.QUOTA
            code == 404 -> AiErrorKind.MODEL_NOT_FOUND
            code in 500..599 -> AiErrorKind.SERVER
            // 400 一类多半是 response_format 或参数不被支持，交给降级链处理，不计入连接失败。
            code in 400..499 -> AiErrorKind.SCHEMA
            else -> AiErrorKind.SERVER
        }
        return AiResult.failed(kind, detail)
    }

    companion object {
        /** 正常调用的读超时：网关边想边吐字节时每次读到数据都会重置，60 秒够推理型模型想。 */
        private const val READ_TIMEOUT_MS = 60_000L

        /** 探测的整次预算。以前是 15 秒，而推理型模型回一个 ping 就能想二十几秒，时不时撞线。 */
        private const val PROBE_BUDGET_MS = 45_000L

        /** 模型列表端点的预算：这一条本该几百毫秒回，8 秒还不回就说明网关没实现它。 */
        private const val MODELS_TIMEOUT_MS = 8_000L

        /** SSE 的行前缀与收尾帧。整包响应以它开头就说明这个网关在按流式回话。 */
        private const val SSE_DATA_PREFIX = "data:"
        private const val SSE_DONE = "[DONE]"
        private const val SERVER_RETRY = 2
        private const val MAX_ERROR_DETAIL = 240

        /** 空内容时打进调试控制台的原文长度——只够看形状，不放大段用户数据。 */
        private const val MAX_EMPTY_BODY = 400

        /** 给用户看的措辞，不带秒数：超时多少是我们自己的配置，不是他要决策的事。 */
        private const val TIMEOUT_MESSAGE = "连接超时，请稍后重试"
        private const val PROBE_NETWORK_MESSAGE = "测试连接超时，请检查网络或服务地址后重试"

        private const val PING_INSTRUCTION = "只回复一个字符 ok，不要解释。"
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

        /** OkHttp 的读超时与 callTimeout 都归到 [InterruptedIOException]，取消式超时则只有消息可认。 */
        private fun IOException.isTimeout(): Boolean =
            this is InterruptedIOException || message.equals("canceled", ignoreCase = true)
        /** 尾斜杠可有可无；协议路径固定拼在 `/chat/completions`。 */
        fun chatEndpoint(baseUrl: String): String? {
            val trimmed = baseUrl.trim().trimEnd('/')
            if (trimmed.isEmpty()) return null
            if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) return null
            return if (trimmed.endsWith("/chat/completions")) trimmed else "$trimmed/chat/completions"
        }

        /** 列表端点从 chat 端点退回去拼，两处对 baseUrl 的校验因此只有一份。 */
        fun modelsEndpoint(baseUrl: String): String? =
            chatEndpoint(baseUrl)?.removeSuffix("/chat/completions")?.plus("/models")

        private fun structuredFormat(schema: String?): JsonElement? =
            schema?.let { jsonSchemaFormat(it) }

        private fun jsonSchemaFormat(schema: String): JsonElement = JsonObject(
            mapOf(
                "type" to JsonPrimitive("json_schema"),
                "json_schema" to JsonObject(
                    mapOf(
                        "name" to JsonPrimitive("tally_draft"),
                        "strict" to JsonPrimitive(true),
                        "schema" to AiWire.json.parseToJsonElement(schema),
                    ),
                ),
            ),
        )

        private fun jsonObjectFormat(): JsonElement =
            JsonObject(mapOf("type" to JsonPrimitive("json_object")))
    }
}
