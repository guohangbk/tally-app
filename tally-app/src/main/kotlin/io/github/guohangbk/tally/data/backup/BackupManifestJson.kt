package io.github.guohangbk.tally.data.backup

import org.json.JSONObject

/**
 * 备份包明文清单。字段只允许新增，不允许改名；
 * 「是否含图片」必须显式写入，恢复后据此显示「图片未包含在备份中」。
 */
data class BackupManifestJson(
    val formatVersion: Int,
    val createdAt: Long,
    val appVersion: String,
    val billCount: Int,
    val ledgerCount: Int,
    val attachmentCount: Int,
    val includesImages: Boolean,
    val payloadBytes: Long,
) {
    fun toJson(): String = JSONObject().apply {
        put("formatVersion", formatVersion)
        put("createdAt", createdAt)
        put("appVersion", appVersion)
        put("billCount", billCount)
        put("ledgerCount", ledgerCount)
        put("attachmentCount", attachmentCount)
        put("includesImages", includesImages)
        put("payloadBytes", payloadBytes)
    }.toString()

    companion object {
        /** 清单结构版本；字段只能新增不能改名，恢复时用它判断能否读懂这个包。 */
        const val CURRENT_FORMAT = 1

        fun fromJson(text: String): BackupManifestJson {
            val json = JSONObject(text)
            return BackupManifestJson(
                formatVersion = json.optInt("formatVersion", 1),
                createdAt = json.optLong("createdAt"),
                appVersion = json.optString("appVersion"),
                billCount = json.optInt("billCount"),
                ledgerCount = json.optInt("ledgerCount"),
                attachmentCount = json.optInt("attachmentCount"),
                includesImages = json.optBoolean("includesImages"),
                payloadBytes = json.optLong("payloadBytes"),
            )
        }
    }
}