package io.github.guohangbk.tally.data.image

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import io.github.guohangbk.tally.domain.sha256Hex
import java.io.ByteArrayOutputStream

/**
 * 图片预处理。两处用途共用一套缩放：
 * 「保存到账单」走 WebP 有损压缩并控制体积，发给视觉模型只送长边约 1024 的副本。
 */
data class PreparedImage(
    val bytes: ByteArray,
    val width: Int,
    val height: Int,
    val sha256: String,
    val mimeType: String,
) {
    val sizeBytes: Long get() = bytes.size.toLong()

    fun dataUrl(): String = "data:$mimeType;base64," + android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)

    override fun equals(other: Any?): Boolean =
        other is PreparedImage && other.sha256 == sha256 && other.width == width && other.height == height

    override fun hashCode(): Int = sha256.hashCode()
}

object TallyImages {
    const val ATTACHMENT_MIME: String = "image/webp"

    /**
     * 压缩触发线：**原图超过 50 KB 才重编码**。
     *
     * 小图不压不是因为省那一次编码，而是 WebP 在几十 KB 的图上经常越压越大，
     * 而且重编码会改掉字节、连带 `sha256` 去重也失效。
     */
    private const val COMPRESS_FROM_BYTES = 50 * 1024

    /**
     * 压缩档：长边保持 1600 不动，只降质量。
     *
     * 小票和聊天截图上是**字**，尺寸砍一半最先糊掉的就是字；质量 65 在 1600 长边下
     * 通常已经把几 MB 的原图压到几百 KB，够省了，不必再拿清晰度去换。
     * 不设体积上限——压到看得清为止，而不是压到某个字节数为止。
     */
    private const val COMPRESS_QUALITY = 65

    /** 视觉模型输入长边，太大只会白花钱额度。 */
    private const val MODEL_LONG_EDGE = 1024
    private const val MODEL_QUALITY = 78

    fun decode(bytes: ByteArray): Bitmap? =
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)

    /** 先按尺寸上限采样解码，避免一张原图把堆撑爆。 */
    fun decodeSampled(bytes: ByteArray, maxEdge: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return decode(bytes)
        val sample = sequenceOf(1, 2, 4, 8, 16)
            .firstOrNull { maxOf(bounds.outWidth, bounds.outHeight) / it <= maxEdge * 2 }
            ?: 16
        return BitmapFactory.decodeByteArray(
            bytes,
            0,
            bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sample },
        )
    }

    /** 图片字节 → 落库要存的那一份。小于触发线的原样保留（连格式一起），大于的按压缩档重编码。 */
    fun attachment(bytes: ByteArray): PreparedImage? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        if (bytes.size <= COMPRESS_FROM_BYTES) {
            return PreparedImage(
                bytes = bytes,
                width = bounds.outWidth,
                height = bounds.outHeight,
                sha256 = sha256Hex(bytes),
                mimeType = bounds.outMimeType ?: ATTACHMENT_MIME,
            )
        }
        val bitmap = decodeSampled(bytes, MAX_EDGE) ?: return null
        val scaled = scaleTo(bitmap, MAX_EDGE)
        return prepared(scaled, encodeWebp(scaled, COMPRESS_QUALITY))
    }

    /** 发给视觉模型的最小副本；与保存到账单的那份是两条独立路径。 */
    fun forModel(bytes: ByteArray): PreparedImage? {
        val bitmap = decodeSampled(bytes, MODEL_LONG_EDGE * 2) ?: return null
        val scaled = scaleTo(bitmap, MODEL_LONG_EDGE)
        return prepared(scaled, encodeWebp(scaled, MODEL_QUALITY))
    }

    private fun prepared(bitmap: Bitmap, bytes: ByteArray): PreparedImage = PreparedImage(
        bytes = bytes,
        width = bitmap.width,
        height = bitmap.height,
        sha256 = sha256Hex(bytes),
        mimeType = ATTACHMENT_MIME,
    )

    private fun encodeWebp(bitmap: Bitmap, quality: Int): ByteArray {
        val out = ByteArrayOutputStream(bitmap.byteCount / 8)
        bitmap.compress(Bitmap.CompressFormat.WEBP, quality, out)
        return out.toByteArray()
    }

    private fun scaleTo(bitmap: Bitmap, maxEdge: Int): Bitmap {
        val longEdge = maxOf(bitmap.width, bitmap.height)
        if (longEdge <= maxEdge) return bitmap
        val ratio = maxEdge.toFloat() / longEdge
        val matrix = Matrix().apply { setScale(ratio, ratio) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private const val MAX_EDGE = 1600
}
