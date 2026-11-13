package io.github.guohangbk.tally.domain.model

/** `.tally` 包内明文清单：恢复前无需密码即可展示备份时间、笔数与是否含图片。 */
data class BackupManifest(
    val formatVersion: Int,
    val createdAt: Long,
    val appVersion: String,
    val billCount: Int,
    val ledgerCount: Int,
    val attachmentCount: Int,
    val includesImages: Boolean,
    val payloadBytes: Long,
) {
    companion object {
        const val CURRENT_FORMAT = 1
    }
}

data class BackupOptions(
    val password: String,
    val includeImages: Boolean = false,
)

data class BackupResult(
    val fileName: String,
    val bytes: ByteArray,
    val manifest: BackupManifest,
    /** 含图片时的预估体积，供导出前确认。 */
    val estimatedImageBytes: Long = 0L,
)

data class RestoreResult(
    val manifest: BackupManifest,
    val restoredBills: Int,
    val restoredLedgers: Int,
    val imagesRestored: Int,
)

/** WebDAV 账号；密码单独存 Keystore，不随配置落库。 */
data class WebDavConfig(
    val baseUrl: String = "",
    val userName: String = "",
    val remoteDirectory: String = "tally",
) {
    val isConfigured: Boolean get() = baseUrl.isNotBlank() && userName.isNotBlank()
}

data class WebDavFile(
    val name: String,
    val sizeBytes: Long,
    val lastModified: Long,
) {
    val sizeText: String
        get() = when {
            sizeBytes >= 1024 * 1024 -> "%.1f MB".format(sizeBytes / 1024.0 / 1024.0)
            sizeBytes >= 1024 -> "%.1f KB".format(sizeBytes / 1024.0)
            else -> "$sizeBytes B"
        }
}

data class WebDavConnectionResult(
    val success: Boolean,
    val message: String,
)
