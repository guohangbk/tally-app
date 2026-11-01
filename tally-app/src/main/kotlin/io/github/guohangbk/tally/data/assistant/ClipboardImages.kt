package io.github.guohangbk.tally.data.assistant

import android.content.ClipboardManager
import android.content.Context
import android.net.Uri

/**
 * 剪贴板里的图片。只取 URI 引用，不把内容复制走，也不做后台轮询；
 * 用户在这个页面主动点「读剪贴板」时才读一次（三个入口之一）。
 */
object ClipboardImages {
    fun imageUri(context: Context): Uri? {
        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return null
        val clip = manager.primaryClip ?: return null
        val description = manager.primaryClipDescription ?: return null
        if (clip.itemCount == 0) return null

        val declaredImage = (0 until description.mimeTypeCount)
            .map { description.getMimeType(it) }
            .any { it != null && it.startsWith("image/") }
        val uri = clip.getItemAt(0).uri ?: return null
        if (!declaredImage) {
            // 有些相册只把条目写成 text/plain 而把真实类型留在 URI 上，这里再问一次 ContentResolver。
            val resolved = runCatching { context.contentResolver.getType(uri) }.getOrNull()
            if (resolved == null || !resolved.startsWith("image/")) return null
        }
        return uri
    }
}
