package io.github.guohangbk.tally.data.ocr

import android.content.Context
import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import io.github.guohangbk.tally.data.image.TallyImages
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * 端侧 OCR（第一层）。中文识别用 ML Kit 的 bundled 模型，
 * 不联网、不消耗额度；识别失败只影响这一步，用户仍可手动录入。
 */
sealed interface OcrOutcome {
    data class Recognized(val text: String, val lineCount: Int) : OcrOutcome

    data class Failed(val reason: String) : OcrOutcome

    data object NoText : OcrOutcome
}

interface ScreenshotTextReader {
    suspend fun read(imageBytes: ByteArray): OcrOutcome
}

class MlKitTextReader(context: Context) : ScreenshotTextReader {
    private val appContext = context.applicationContext
    private val recognizer by lazy {
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    }

    override suspend fun read(imageBytes: ByteArray): OcrOutcome {
        val bitmap: Bitmap = TallyImages.decodeSampled(imageBytes, MAX_EDGE)
            ?: return OcrOutcome.Failed("图片读不出来，请手动录入")
        return suspendCancellableCoroutine { continuation ->
            recognizer.process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener { text ->
                    val joined = text.textBlocks.joinToString("\n") { block ->
                        block.lines.joinToString("\n") { it.text }
                    }.trim()
                    continuation.resume(
                        if (joined.isBlank()) OcrOutcome.NoText
                        else OcrOutcome.Recognized(joined, text.textBlocks.sumOf { it.lines.size }),
                    )
                }
                .addOnFailureListener { error ->
                    continuation.resume(OcrOutcome.Failed("识别失败：${error.message ?: "端侧模型不可用"}，可手动录入"))
                }
        }
    }

    private companion object {
        /** OCR 前先把长边压到 1600，识别精度不掉，内存却安全得多。 */
        const val MAX_EDGE = 1600
    }
}

class FakeTextReader(private val outcome: OcrOutcome) : ScreenshotTextReader {
    override suspend fun read(imageBytes: ByteArray): OcrOutcome = outcome
}
