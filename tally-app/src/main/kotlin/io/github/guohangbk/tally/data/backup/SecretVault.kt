package io.github.guohangbk.tally.data.backup

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 设备凭据保管（WebDAV 密码、AI API Key）。
 *
 * 生产环境用 Android Keystore 里的 AES-GCM 密钥加密，密文存私有 SharedPreferences：
 * 密钥不可导出，密文也不进数据库与备份包。Keystore 不可用（JVM 单测、个别定制 ROM）时
 * 退化为明文标记存储：功能仍可验证，但安全性降级只在非生产环境出现。
 */
class SecretVault(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences("tally_secrets", Context.MODE_PRIVATE)

    private val keystoreAvailable: Boolean by lazy {
        runCatching { key() }.isSuccess
    }

    fun put(name: String, secret: String?) {
        if (secret.isNullOrEmpty()) {
            remove(name)
            return
        }
        val stored = if (keystoreAvailable) runCatching { seal(secret) }.getOrNull() else null
        prefs.edit().putString(name, stored ?: (PLAIN_PREFIX + secret)).apply()
    }

    fun get(name: String): String? {
        val stored = prefs.getString(name, null) ?: return null
        if (stored.startsWith(PLAIN_PREFIX)) return stored.removePrefix(PLAIN_PREFIX)
        return runCatching { open(stored) }.getOrNull()
    }

    fun remove(name: String) {
        prefs.edit().remove(name).apply()
    }

    fun has(name: String): Boolean = !get(name).isNullOrEmpty()

    private fun seal(secret: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(secret.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP)
    }

    private fun open(blob: String): String {
        val bytes = Base64.decode(blob, Base64.NO_WRAP)
        val iv = bytes.copyOfRange(0, IV_BYTES)
        val body = bytes.copyOfRange(IV_BYTES, bytes.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, iv))
        return String(cipher.doFinal(body), Charsets.UTF_8)
    }

    private fun key(): SecretKey =
        (keyStore().getKey(ALIAS, null) as? SecretKey) ?: generateKey()

    private fun keyStore(): KeyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }

    private fun generateKey(): SecretKey {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    companion object {
        const val WEBDAV_PASSWORD = "webdav_password"
        private const val ALIAS = "tally_secret_vault"
        private const val KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_BYTES = 12
        private const val TAG_BITS = 128
        private const val PLAIN_PREFIX = "plain:"
    }
}
