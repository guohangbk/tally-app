package io.github.guohangbk.tally.domain.ai

import io.github.guohangbk.tally.domain.ai.AiConfiguration.Companion.PROVIDER_OPENAI_COMPATIBLE

/** 一个可选的模型，以及它是否能直接看图片。能力只决定截图路径，不是使用门槛。 */
data class AiModelPreset(
    val id: String,
    val vision: Boolean,
)

/**
 * 预置服务商：选中后自动带上 base URL 与可选模型。
 *
 * 全部服务商都走 OpenAI 兼容协议，差别只在地址与模型名；「自定义」保留手填能力，
 * 方便中转站或新服务商先跑起来。模型能力写在这里，是给用户看「图片 / 文本」，
 * 真正的判定仍以保存时的连接测试为准。
 */
data class AiProviderPreset(
    val key: String,
    val name: String,
    val baseUrl: String,
    val models: List<AiModelPreset>,
) {
    val isCustom: Boolean get() = key == PROVIDER_OPENAI_COMPATIBLE
}

object AiProviderPresets {
    val custom = AiProviderPreset(
        key = PROVIDER_OPENAI_COMPATIBLE,
        name = "自定义",
        baseUrl = "",
        models = emptyList(),
    )

    val all: List<AiProviderPreset> = listOf(
        AiProviderPreset(
            key = "openai",
            name = "OpenAI",
            baseUrl = "https://api.openai.com/v1",
            models = listOf(
                AiModelPreset("gpt-4o-mini", vision = true),
                AiModelPreset("gpt-4o", vision = true),
                AiModelPreset("gpt-4.1-mini", vision = true),
            ),
        ),
        AiProviderPreset(
            key = "deepseek",
            name = "DeepSeek",
            baseUrl = "https://api.deepseek.com/v1",
            models = listOf(
                AiModelPreset("deepseek-chat", vision = false),
                AiModelPreset("deepseek-reasoner", vision = false),
            ),
        ),
        AiProviderPreset(
            key = "zhipu",
            name = "智谱",
            baseUrl = "https://open.bigmodel.cn/api/paas/v4",
            models = listOf(
                AiModelPreset("glm-4-flash", vision = false),
                AiModelPreset("glm-4v-flash", vision = true),
                AiModelPreset("glm-4-plus", vision = false),
            ),
        ),
        AiProviderPreset(
            key = "qwen",
            name = "千问",
            baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1",
            models = listOf(
                AiModelPreset("qwen-plus", vision = false),
                AiModelPreset("qwen-vl-plus", vision = true),
                AiModelPreset("qwen-turbo", vision = false),
            ),
        ),
        custom,
    )

    fun byKey(key: String?): AiProviderPreset =
        all.firstOrNull { it.key == key } ?: custom

    /** 旧配置只存过 base URL，按地址反推它属于哪个预置服务商，升级后不用重填。 */
    fun byBaseUrl(baseUrl: String?): AiProviderPreset {
        val normalized = baseUrl?.trim()?.trimEnd('/').orEmpty()
        if (normalized.isEmpty()) return custom
        return all.firstOrNull { it.baseUrl.isNotBlank() && it.baseUrl.trimEnd('/').equals(normalized, ignoreCase = true) }
            ?: custom
    }

    fun visionOf(providerKey: String, model: String): Boolean? =
        byKey(providerKey).models.firstOrNull { it.id == model }?.vision
}
