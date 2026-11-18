package io.github.guohangbk.tally.data.ingest

import android.net.Uri
import io.github.guohangbk.tally.data.attachment.AttachmentStore
import io.github.guohangbk.tally.data.debug.DebugLog
import io.github.guohangbk.tally.data.ocr.OcrOutcome
import io.github.guohangbk.tally.data.ocr.ScreenshotTextReader
import io.github.guohangbk.tally.data.preferences.TallyPreferences
import io.github.guohangbk.tally.data.preferences.TallySettings
import io.github.guohangbk.tally.domain.ingest.IngestRequest
import io.github.guohangbk.tally.domain.ingest.IngestSource
import io.github.guohangbk.tally.domain.ingest.IngestRedactor
import io.github.guohangbk.tally.domain.ingest.IngestStage
import kotlinx.coroutines.flow.first
import java.io.FileNotFoundException
import java.io.IOException

/** 素材通道唯一的取图与本机 OCR 入口；人亲手打的字不在这里改写。 */
class MaterialReader(
    private val attachments: AttachmentStore,
    private val textReader: ScreenshotTextReader,
    private val preferences: TallyPreferences,
) {
    /**
     * 取图 + 本机 OCR。**失败必须变成一句人话**：这条链的尽头是「正在本机识别…」那张卡，
     * 异常直接掀掉协程会让它永远停在那里（真机撞过：分享进来的图拿不到读授权）。
     */
    suspend fun requestOf(
        uris: List<Uri>,
        source: IngestSource,
        userText: String = "",
        onStage: ((IngestStage, Int, Int) -> Unit)? = null,
    ): MaterialOutcome = guarded("这张图片读不出来") { readMaterial(uris, source, userText, onStage) }

    private suspend fun readMaterial(
        uris: List<Uri>,
        source: IngestSource,
        userText: String,
        onStage: ((IngestStage, Int, Int) -> Unit)?,
    ): MaterialOutcome {
        val now = System.currentTimeMillis()
        val images = readSources(uris) ?: return MaterialOutcome.Failure("这张图片读不出来")
        if (images.isEmpty()) return MaterialOutcome.Failure("这张图片读不出来")
        val ocrStartedAt = System.nanoTime()
        val texts = images.mapIndexed { index, bytes ->
            onStage?.invoke(IngestStage.OCR, index + 1, images.size)
            when (val outcome = textReader.read(bytes)) {
                is OcrOutcome.Recognized -> outcome.text
                is OcrOutcome.NoText -> ""
                is OcrOutcome.Failed -> return MaterialOutcome.Failure(outcome.reason)
            }
        }
        DebugLog.log(
            "识别管线",
            "本机 OCR ${images.size} 张 · ${(System.nanoTime() - ocrStartedAt) / 1_000_000}ms",
        )
        onStage?.invoke(IngestStage.REDACT, images.size, images.size)
        val allowLlm = source.aiEnhanced(preferences.settings.first())
        val requests = texts.mapIndexed { index, text ->
            val redaction = IngestRedactor.redact(text)
            IngestRequest(
                source = source,
                userText = if (index == 0) userText else "",
                ocrText = text,
                redactedOcrText = redaction.text,
                images = listOf(images[index]),
                nowMillis = now,
                fallbackMillis = now,
                singleMaterial = true,
                allowLlm = allowLlm,
                materialIndex = index,
                onStage = onStage,
            )
        }
        return MaterialOutcome.Ready(
            request = requests.first(),
            requests = requests,
        )
    }

    /** 任何异常都收成可展示的失败；取消防抖照旧往上抛。 */
    private suspend inline fun guarded(fallback: String, block: () -> MaterialOutcome): MaterialOutcome =
        try {
            block()
        } catch (cancellation: kotlin.coroutines.cancellation.CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            MaterialOutcome.Failure(failure.message ?: fallback)
        }

    /** 对话里亲手发的图：只做本机 OCR 复核，仍然按原图进对话与模型。 */
    suspend fun ocrText(images: List<ByteArray>): MaterialOutcome =
        guarded("这张图片读不出来") { readChatImages(images) }

    /**
     * 语音这一路的素材：没有图可读，只有被识别出来的一句话。
     * 转写是机器出的字，外发一律走脱敏版；原文只留在本机做规则与判级。
     * 它与取图那条路交出同一种 [IngestRequest]，后面判级、去重、落库完全共用。
     */
    suspend fun spokenRequest(
        text: String,
        source: IngestSource = IngestSource.VOICE,
        onStage: ((IngestStage, Int, Int) -> Unit)? = null,
    ): IngestRequest {
        val now = System.currentTimeMillis()
        return IngestRequest(
            source = source,
            userText = text,
            redactedUserText = IngestRedactor.redact(text).text,
            nowMillis = now,
            fallbackMillis = now,
            singleMaterial = true,
            allowLlm = source.aiEnhanced(preferences.settings.first()),
            onStage = onStage,
        )
    }

    private suspend fun readChatImages(images: List<ByteArray>): MaterialOutcome {
        val now = System.currentTimeMillis()
        val texts = images.map { bytes ->
            when (val outcome = textReader.read(bytes)) {
                is OcrOutcome.Recognized -> outcome.text
                is OcrOutcome.NoText -> ""
                is OcrOutcome.Failed -> return MaterialOutcome.Failure(outcome.reason)
            }
        }
        return MaterialOutcome.Ready(
            IngestRequest(
                source = IngestSource.CHAT,
                ocrText = texts.joinToString("\n"),
                redactedOcrText = IngestRedactor.redact(texts.joinToString("\n")).text,
                images = images,
                nowMillis = now,
                fallbackMillis = now,
                singleMaterial = images.size == 1,
                allowLlm = true,
            ),
        )
    }

    private fun readSources(uris: List<Uri>): List<ByteArray>? = buildList {
        uris.forEach { uri ->
            val bytes = try {
                attachments.readSource(uri)
            } catch (notFound: FileNotFoundException) {
                null
            } catch (io: IOException) {
                null
            } catch (security: SecurityException) {
                null
            } ?: return null
            add(bytes)
        }
    }
}

/**
 * 这一路素材要不要 AI 提取。三个开关各管一个入口，对话通道不受它们影响
 * ——那里用户是自己要跟小莉说话，本来就走模型。
 */
private fun IngestSource.aiEnhanced(settings: TallySettings): Boolean = when (this) {
    IngestSource.VOICE -> settings.aiVoiceEnabled
    IngestSource.SCREENSHOT -> settings.aiImageEnabled
    IngestSource.SHARE -> settings.aiQuickNoteEnabled
    IngestSource.CHAT -> true
}

sealed interface MaterialOutcome {
    data class Ready(
        val request: IngestRequest,
        /** 批量素材：一张图一份请求；单张与对话为空。 */
        val requests: List<IngestRequest> = emptyList(),
    ) : MaterialOutcome
    data class Failure(val reason: String) : MaterialOutcome
}
