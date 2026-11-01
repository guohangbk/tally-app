package io.github.guohangbk.tally.data.backup

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * `.tally` 包格式：
 *
 * ```text
 * manifest.json          明文：版本、时间、笔数、是否含图片
 * data.enc               AES-256-GCM（PBKDF2 派生密钥）
 * attachments/<sha256>   仅在 includeImages 时出现，同样加密
 * ```
 *
 * 清单保持明文，是为了恢复前能在不输入密码的情况下展示「备份时间 / 账单数 / 是否含图片」。
 * 结构化数据与图片始终加密；密码错误时 GCM 校验失败，调用方据此拒绝恢复。
 */
object BackupContainer {
    private const val MANIFEST = "manifest.json"
    private const val DATA = "data.enc"

    /**
     * 新建备份与设置 WebDAV 口令的下限。四位不是"强密码"，但把"1234"这类
     * 一位两位的随手输入挡在外面；旧包一律照原样可解，恢复侧不读这条。
     */
    const val MIN_PASSWORD_CHARS = 4
    private const val ATTACHMENT_PREFIX = "attachments/"
    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128
    private const val ITERATIONS = 120_000

    data class Payload(
        val manifest: BackupManifestJson,
        val json: String,
        val images: Map<String, ByteArray>,
    )

    /** 加密后的单块数据；salt/iv 随块存放，允许每张图片独立解密。 */
    fun encrypt(plain: ByteArray, password: CharArray): ByteArray {
        val salt = ByteArray(SALT_BYTES).also(SecureRandom()::nextBytes)
        val iv = ByteArray(IV_BYTES).also(SecureRandom()::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, deriveKey(password, salt), GCMParameterSpec(TAG_BITS, iv))
        val encrypted = cipher.doFinal(plain)
        return salt + iv + encrypted
    }

    fun decrypt(blob: ByteArray, password: CharArray): ByteArray {
        require(blob.size > SALT_BYTES + IV_BYTES + TAG_BITS / 8) { "加密数据不完整" }
        val salt = blob.copyOfRange(0, SALT_BYTES)
        val iv = blob.copyOfRange(SALT_BYTES, SALT_BYTES + IV_BYTES)
        val body = blob.copyOfRange(SALT_BYTES + IV_BYTES, blob.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, deriveKey(password, salt), GCMParameterSpec(TAG_BITS, iv))
        return cipher.doFinal(body)
    }

    fun pack(manifest: BackupManifestJson, json: String, images: Map<String, ByteArray>, password: CharArray): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry(MANIFEST))
            zip.write(manifest.toJson().toByteArray(Charsets.UTF_8))
            zip.closeEntry()

            zip.putNextEntry(ZipEntry(DATA))
            zip.write(encrypt(json.toByteArray(Charsets.UTF_8), password))
            zip.closeEntry()

            images.forEach { (path, bytes) ->
                zip.putNextEntry(ZipEntry(ATTACHMENT_PREFIX + path))
                zip.write(encrypt(bytes, password))
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }

    /** 只读清单，不需要密码；用于恢复前的二次确认。 */
    fun readManifest(bytes: ByteArray): BackupManifestJson {
        val entries = unzip(bytes)
        val manifest = entries[MANIFEST] ?: throw IllegalArgumentException("缺少 manifest.json")
        return BackupManifestJson.fromJson(String(manifest, Charsets.UTF_8))
    }

    fun unpack(bytes: ByteArray, password: CharArray): Payload {
        val entries = unzip(bytes)
        val manifest = entries[MANIFEST]?.let { BackupManifestJson.fromJson(String(it, Charsets.UTF_8)) }
            ?: throw IllegalArgumentException("缺少 manifest.json")
        val data = entries[DATA] ?: throw IllegalArgumentException("缺少 data.enc")
        val json = String(decrypt(data, password), Charsets.UTF_8)
        val images = entries
            .filterKeys { it.startsWith(ATTACHMENT_PREFIX) }
            .mapKeys { it.key.removePrefix(ATTACHMENT_PREFIX) }
            .mapValues { (_, blob) -> decrypt(blob, password) }
        return Payload(manifest, json, images)
    }

    fun attachmentPath(sha256: String, mimeType: String): String {
        val extension = when (mimeType) {
            "image/png" -> "png"
            "image/jpeg" -> "jpg"
            else -> "webp"
        }
        return "$sha256.$extension"
    }

    private fun unzip(bytes: ByteArray): Map<String, ByteArray> {
        val result = linkedMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry: ZipEntry? = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) result[entry.name] = zip.readBytes()
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        return result
    }

    private fun deriveKey(password: CharArray, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(password, salt, ITERATIONS, 256)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        return SecretKeySpec(factory.generateSecret(spec).encoded, "AES")
    }
}
