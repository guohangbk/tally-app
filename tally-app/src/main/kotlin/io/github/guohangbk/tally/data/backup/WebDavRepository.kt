package io.github.guohangbk.tally.data.backup

import io.github.guohangbk.tally.data.preferences.TallyPreferences
import io.github.guohangbk.tally.domain.model.TallyError
import io.github.guohangbk.tally.domain.model.WebDavConfig
import io.github.guohangbk.tally.domain.model.WebDavConnectionResult
import io.github.guohangbk.tally.domain.model.WebDavFile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * WebDAV 快照的业务门面：配置读写 + 凭据保管 + 客户端调用。
 * 密码只经过 [SecretVault]，配置（地址、用户名、目录）走 DataStore。
 */
class WebDavRepository(
    private val preferences: TallyPreferences,
    private val secrets: SecretVault,
    private val client: WebDavClient,
) {
    val config: Flow<WebDavConfig> = preferences.settings.map {
        WebDavConfig(baseUrl = it.webDavUrl, userName = it.webDavUser, remoteDirectory = it.webDavDirectory)
    }

    fun hasPassword(): Boolean = secrets.has(SecretVault.WEBDAV_PASSWORD)

    suspend fun save(config: WebDavConfig, password: String?) {
        preferences.setWebDav(config.baseUrl, config.userName, config.remoteDirectory)
        password?.takeIf { it.isNotBlank() }?.let { secrets.put(SecretVault.WEBDAV_PASSWORD, it) }
    }

    suspend fun test(config: WebDavConfig, password: String? = null): WebDavConnectionResult {
        val secret = password?.takeIf { it.isNotBlank() } ?: secrets.get(SecretVault.WEBDAV_PASSWORD).orEmpty()
        if (secret.isEmpty()) return WebDavConnectionResult(false, "请先填写 WebDAV 密码")
        val result = client.test(config, secret)
        if (result.success) {
            preferences.setWebDav(config.baseUrl, config.userName, config.remoteDirectory)
            secrets.put(SecretVault.WEBDAV_PASSWORD, secret)
        }
        return result
    }

    suspend fun list(): List<WebDavFile> {
        val (config, password) = requireCredentials()
        return client.list(config, password)
    }

    suspend fun upload(fileName: String, bytes: ByteArray) {
        val (config, password) = requireCredentials()
        client.upload(config, password, fileName, bytes)
    }

    suspend fun download(fileName: String): ByteArray {
        val (config, password) = requireCredentials()
        return client.download(config, password, fileName)
    }

    private suspend fun requireCredentials(): Pair<WebDavConfig, String> {
        val settings = preferences.settings.first()
        val config = WebDavConfig(
            baseUrl = settings.webDavUrl,
            userName = settings.webDavUser,
            remoteDirectory = settings.webDavDirectory,
        )
        if (!config.isConfigured) throw TallyError.WebDavNotConfigured
        val password = secrets.get(SecretVault.WEBDAV_PASSWORD)
            ?: throw TallyError.WebDavNotConfigured
        return config to password
    }
}
