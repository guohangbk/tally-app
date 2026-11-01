package io.github.guohangbk.tally.data.ai

import io.github.guohangbk.tally.data.backup.SecretVault
import io.github.guohangbk.tally.domain.ai.AiTarget

/**
 * API Key 的落处：密文进 Keystore 加密后的私有存储，数据库只记引用名。
 *
 * 复用备份模块已有的 [SecretVault]，不再抄一遍 AES/GCM；
 * 这样 Key 既不进 Room 数据库，也不进备份包。
 *
 * **一条配置一把 Key**：存放键带配置 id（`ai_api_key:<id>`），切到另一条配置时
 * 上一家的 Key 还在原处，切回来还能用。用一个全局槽的话，先存 A 再存 B，
 * A 的 Key 就被覆盖，切回 A 直接废。
 */
class AiKeyVault(private val secrets: SecretVault) {
    /** 数据库里存的引用名，同时也是密文的存放键。 */
    fun referenceFor(configId: String): String = "$KEY_ENTRY:$configId"

    fun save(configId: String, apiKey: String) = secrets.put(referenceFor(configId), apiKey)

    fun read(configId: String): String? {
        secrets.get(referenceFor(configId))?.takeIf { it.isNotBlank() }?.let { return it }
        // 老数据只有一把全局 Key，挂在 id = "default" 的那一条上；
        // 没重新保存过之前，那条配置还得靠它活着。
        return if (configId == LEGACY_CONFIG_ID) secrets.get(KEY_ENTRY) else null
    }

    fun clear(configId: String) = secrets.remove(referenceFor(configId))

    /** 三项齐全才拼得出可调用目标，否则视为未配置。 */
    fun targetOf(configId: String, baseUrl: String?, model: String?): AiTarget? {
        val key = read(configId) ?: return null
        val url = baseUrl?.takeIf { it.isNotBlank() } ?: return null
        val name = model?.takeIf { it.isNotBlank() } ?: return null
        return AiTarget(baseUrl = url, model = name, apiKey = key)
    }

    private companion object {
        const val KEY_ENTRY = "ai_api_key"
        const val LEGACY_CONFIG_ID = "default"
    }
}
