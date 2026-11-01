package io.github.guohangbk.tally.data.backup

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import io.github.guohangbk.tally.data.db.TallyDatabase
import io.github.guohangbk.tally.data.db.dao.BackupDao
import io.github.guohangbk.tally.data.db.entity.AttachmentEntity
import io.github.guohangbk.tally.domain.model.BackupManifest
import io.github.guohangbk.tally.domain.model.BackupOptions
import io.github.guohangbk.tally.domain.model.BackupResult
import io.github.guohangbk.tally.domain.model.RestoreResult
import io.github.guohangbk.tally.domain.model.TallyError
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 加密备份包导出与覆盖式恢复。
 *
 * 边界：
 * - 默认不含图片；勾选后把 `files/attachments/` 里被引用的文件打进包，并给出预估体积；
 * - API Key、WebDAV 密码不进入备份；
 * - 恢复先校验密码与数据结构，再在一个事务内整体替换，失败时现有数据不变；
 * - 不含图片的备份恢复后，附件元数据保留但文件缺失，UI 显示「图片未包含在备份中」。
 *
 * 所有整表读写都走 [BackupDao]，业务 DAO 不承担备份语义。
 */
class BackupService(
    private val context: Context,
    private val database: TallyDatabase,
    private val backupDao: BackupDao,
    private val now: () -> Long = System::currentTimeMillis,
    private val appVersion: String = "0.1.0",
    private val zoneId: () -> ZoneId = ZoneId::systemDefault,
) {
    private val fileStamp = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")
    private val attachmentsDir: File
        get() = File(context.filesDir, "attachments").apply { mkdirs() }

    /** 导出前展示的预估体积：结构化 JSON 加上可选的图片文件。 */
    suspend fun estimate(includeImages: Boolean): Long {
        val snapshot = loadSnapshot()
        val jsonBytes = BackupJson.encode(snapshot).toByteArray().size.toLong()
        if (!includeImages) return jsonBytes
        return jsonBytes + collectImages(snapshot.attachments).values.sumOf { it.size.toLong() }
    }

    suspend fun export(options: BackupOptions): BackupResult {
        if (options.password.isEmpty()) throw TallyError.BackupPasswordRequired
        // 只卡新建这一侧：恢复走下面的 restore，旧的四位纯数字包照样能解开。
        if (options.password.length < BackupContainer.MIN_PASSWORD_CHARS) throw TallyError.BackupPasswordWeak
        val snapshot = loadSnapshot()
        val images = if (options.includeImages) collectImages(snapshot.attachments) else emptyMap()
        val json = BackupJson.encode(snapshot)
        val manifest = BackupManifestJson(
            formatVersion = BackupManifestJson.CURRENT_FORMAT,
            createdAt = now(),
            appVersion = appVersion,
            billCount = snapshot.billCount,
            ledgerCount = snapshot.ledgerCount,
            attachmentCount = snapshot.attachments.count { it.deletedAt == null },
            includesImages = options.includeImages,
            payloadBytes = json.toByteArray().size.toLong() + images.values.sumOf { it.size.toLong() },
        )
        val bytes = BackupContainer.pack(manifest, json, images, options.password.toCharArray())
        val stamp = Instant.ofEpochMilli(manifest.createdAt).atZone(zoneId()).format(fileStamp)
        return BackupResult(
            fileName = "tally_backup_$stamp.tally",
            bytes = bytes,
            manifest = manifest.toDomain(),
            estimatedImageBytes = images.values.sumOf { it.size.toLong() },
        )
    }

    /** 恢复前只读清单，不需要密码；用于「备份时间 / 笔数 / 是否含图片」二次确认。 */
    fun inspect(bytes: ByteArray): BackupManifest =
        runCatching { BackupContainer.readManifest(bytes).toDomain() }
            .getOrElse { throw TallyError.BackupCorrupted }

    /** 恢复为覆盖式；密码或结构校验失败时不触碰现有数据。 */
    suspend fun restore(bytes: ByteArray, password: String): RestoreResult {
        if (password.isEmpty()) throw TallyError.BackupPasswordRequired
        val manifest = inspect(bytes)
        if (manifest.formatVersion > BackupManifestJson.CURRENT_FORMAT) throw TallyError.BackupVersionUnsupported

        val payload = runCatching { BackupContainer.unpack(bytes, password.toCharArray()) }
            .getOrElse { throw TallyError.BackupPasswordMismatch }
        val snapshot = runCatching { BackupJson.decode(payload.json) }
            .getOrElse { throw TallyError.BackupCorrupted }

        database.withTransaction {
            clearAll()
            backupDao.insertLedgers(snapshot.ledgers)
            backupDao.insertCategories(snapshot.categories)
            backupDao.insertBills(snapshot.bills)
            backupDao.insertAttachments(snapshot.attachments)
            backupDao.insertImportBatches(snapshot.importBatches)
            backupDao.insertRecurringRules(snapshot.recurringRules)
            backupDao.insertInstallmentPlans(snapshot.installmentPlans)
            backupDao.insertAiConfigs(snapshot.aiConfigs)
            backupDao.insertAiConversations(snapshot.aiConversations)
            backupDao.insertAiMessages(snapshot.aiMessages)
            backupDao.insertAiMessageAttachments(snapshot.aiMessageAttachments)
            backupDao.insertAiReports(snapshot.aiReports)
            backupDao.insertKeywordRules(snapshot.keywordRules)
            backupDao.insertAppSettings(snapshot.appSettings)
        }

        return RestoreResult(
            manifest = manifest,
            restoredBills = snapshot.bills.count { it.deletedAt == null },
            restoredLedgers = snapshot.ledgerCount,
            imagesRestored = restoreImages(payload.images),
        )
    }

    fun readBytes(uri: Uri): ByteArray =
        context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IllegalArgumentException("无法读取该文件")

    /** 覆盖式恢复的清空顺序：先子表后主表，避免中途出现悬空引用。 */
    private suspend fun clearAll() {
        backupDao.clearBills()
        backupDao.clearAttachments()
        backupDao.clearCategories()
        backupDao.clearLedgers()
        backupDao.clearImportBatches()
        backupDao.clearRecurringRules()
        backupDao.clearInstallmentPlans()
        backupDao.clearAiMessageAttachments()
        backupDao.clearAiMessages()
        backupDao.clearAiConversations()
        backupDao.clearAiReports()
        backupDao.clearAiConfigs()
        backupDao.clearKeywordRules()
        backupDao.clearAppSettings()
    }

    private suspend fun loadSnapshot(): BackupSnapshot = BackupSnapshot(
        ledgers = backupDao.ledgers(),
        categories = backupDao.categories(),
        bills = backupDao.bills(),
        attachments = backupDao.attachments(),
        importBatches = backupDao.importBatches(),
        recurringRules = backupDao.recurringRules(),
        installmentPlans = backupDao.installmentPlans(),
        aiConfigs = backupDao.aiConfigs(),
        aiConversations = backupDao.aiConversations(),
        aiMessages = backupDao.aiMessages(),
        aiMessageAttachments = backupDao.aiMessageAttachments(),
        aiReports = backupDao.aiReports(),
        keywordRules = backupDao.keywordRules(),
        appSettings = backupDao.appSettings(),
    )

    /** 图片按 sha256 命名，包里天然去重；只打包未被软删除的附件。 */
    private fun collectImages(attachments: List<AttachmentEntity>): Map<String, ByteArray> {
        val result = linkedMapOf<String, ByteArray>()
        attachments.filter { it.deletedAt == null }.forEach { attachment ->
            val file = File(attachmentsDir, File(attachment.relativePath).name)
            if (file.isFile) {
                result.putIfAbsent(BackupContainer.attachmentPath(attachment.sha256, attachment.mimeType), file.readBytes())
            }
        }
        return result
    }

    private fun restoreImages(images: Map<String, ByteArray>): Int {
        if (images.isEmpty()) return 0
        attachmentsDir.mkdirs()
        images.forEach { (name, bytes) ->
            val target = File(attachmentsDir, "${sha256(bytes)}.${name.substringAfterLast('.', "webp")}")
            if (!target.exists() || target.length() != bytes.size.toLong()) target.writeBytes(bytes)
        }
        return images.size
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun BackupManifestJson.toDomain(): BackupManifest = BackupManifest(
        formatVersion = formatVersion,
        createdAt = createdAt,
        appVersion = appVersion,
        billCount = billCount,
        ledgerCount = ledgerCount,
        attachmentCount = attachmentCount,
        includesImages = includesImages,
        payloadBytes = payloadBytes,
    )

    companion object {
        val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.CHINA)
    }
}
