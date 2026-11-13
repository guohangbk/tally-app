package io.github.guohangbk.tally.data.attachment

import android.content.Context
import android.net.Uri
import io.github.guohangbk.tally.data.db.dao.AttachmentDao
import io.github.guohangbk.tally.data.db.entity.AttachmentEntity
import io.github.guohangbk.tally.data.image.TallyImages
import io.github.guohangbk.tally.data.preferences.TallyPreferences
import java.io.File
import java.util.UUID

/**
 * 「保存到账单」勾选后落的图片附件（压缩与去重）。
 *
 * 二进制存 `files/attachments/<sha256>.webp`，数据库只存元数据；
 * 同一张图重复导入时文件名按指纹相同，不会复制第二份，但仍会为新账单建一条引用记录。
 * 写盘失败（空间不足）不影响账单本身，账单照常保存并提示一句（异常表）。
 */
class AttachmentStore(
    private val context: Context,
    private val dao: AttachmentDao,
    private val preferences: TallyPreferences,
    private val now: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    suspend fun attachToBill(billId: String, imageBytes: ByteArray): AttachmentOutcome {
        val prepared = TallyImages.attachment(imageBytes)
            ?: return AttachmentOutcome.NotSaved("这张图片读不出来，账单已记录，截图未保存")
        val directory = dir()
        val file = File(directory, "${prepared.sha256}.${extension(prepared.mimeType)}")
        val existed = file.exists()
        if (!existed) {
            val written = runCatching { file.writeBytes(prepared.bytes) }.isSuccess
            if (!written) {
                file.delete()
                return AttachmentOutcome.NotSaved("空间不足，截图未保存，账单已记录")
            }
        }
        val timestamp = now()
        val entity = AttachmentEntity(
            id = newId(),
            billId = billId,
            relativePath = "$DIR_NAME/${file.name}",
            sha256 = prepared.sha256,
            mimeType = prepared.mimeType,
            sizeBytes = prepared.sizeBytes,
            width = prepared.width,
            height = prepared.height,
            createdAt = timestamp,
            updatedAt = timestamp,
            deviceId = preferences.deviceId(),
        )
        dao.upsert(entity)
        return AttachmentOutcome.Saved(entity, reused = existed)
    }

    /** 图片指纹用于「同一张截图重复导入」的提示；命中已有记录就不要再静默保存一份。 */
    suspend fun findBySha(sha256: String): AttachmentEntity? = dao.findBySha(sha256)

    /**
     * 对话里发出去的图片：只留一张气泡回显用的缩略图（模型那份尺寸），
     * 相册原图不进本机存储。返回相对路径，供 ai_message_attachment 记录。
     */
    suspend fun writeChatThumb(imageBytes: ByteArray): String? {
        val prepared = TallyImages.forModel(imageBytes) ?: return null
        val file = File(dir(), "chat_${prepared.sha256.take(16)}.${extension(prepared.mimeType)}")
        if (!file.exists() && !runCatching { file.writeBytes(prepared.bytes) }.isSuccess) return null
        return "$DIR_NAME/${file.name}"
    }

    /** 按相对路径取回本机图片；账单附件与对话缩略图同在一个目录。 */
    fun fileAt(relativePath: String): File? =
        File(context.applicationContext.filesDir, relativePath).takeIf { it.exists() }

    /** 记账页「图片」chip 上的数字：这笔账单已经挂着几张。 */
    suspend fun countForBill(billId: String): Int = dao.findByBill(billId).size

    /** 这笔账单的图片相对路径（按入库顺序），详情弹层据此显示缩略图并支持点开看图。 */
    suspend fun pathsForBill(billId: String): List<String> = dao.findByBill(billId).map { it.relativePath }

    suspend fun fileFor(entity: AttachmentEntity): File? {
        val name = entity.relativePath.substringAfterLast('/')
        val file = File(dir(), name)
        return if (file.exists()) file else null
    }

    /** 读入 content:// 或 file:// 的原图字节；相册、分享、剪贴板三个入口都走这里。 */
    fun readSource(uri: Uri): ByteArray? = runCatching {
        context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
    }.getOrNull()

    private fun dir(): File = File(context.applicationContext.filesDir, DIR_NAME).apply {
        if (!exists()) mkdirs()
    }

    /**
     * 物理删除图片文件，返回**真正没了**的那些路径。
     *
     * 只认 `attachments/<文件名>` 这一种相对路径：清理任务拿到的是库里的字符串，
     * 不能让它变成任意路径删除。文件本来就不在也算「没了」，否则那行记录会永远留着重试。
     */
    fun deleteFiles(relativePaths: List<String>): Pair<List<String>, Long> {
        val root = dir().canonicalFile
        val removed = mutableListOf<String>()
        var freed = 0L
        relativePaths.forEach { relative ->
            val name = relative.removePrefix("$DIR_NAME/")
            if (name.isBlank() || name.contains('/') || name.contains('\\')) return@forEach
            val file = File(root, name)
            if (file.canonicalFile.parentFile != root) return@forEach
            val size = if (file.exists()) file.length() else 0L
            if (!file.exists() || runCatching { file.delete() }.getOrDefault(false)) {
                removed += relative
                freed += size
            }
        }
        return removed to freed
    }

    /**
     * 小于压缩触发线的图会带着原格式留下（jpeg / png），所以这里必须按 mime 分派；
     * 与 [io.github.guohangbk.tally.data.backup.BackupContainer.attachmentPath] 的映射保持一致，
     * 否则备份包里的文件名和本机对不上。
     */
    private fun extension(mimeType: String): String = when {
        mimeType.contains("png") -> "png"
        mimeType.contains("jpeg") || mimeType.contains("jpg") -> "jpg"
        else -> "webp"
    }

    private companion object {
        const val DIR_NAME = "attachments"
    }
}

sealed interface AttachmentOutcome {
    data class Saved(val entity: AttachmentEntity, val reused: Boolean) : AttachmentOutcome

    /** 账单已经记上了，只是图没留住；页面要如实说一句，而不是假装成功。 */
    data class NotSaved(val reason: String) : AttachmentOutcome
}
