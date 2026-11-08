package io.github.guohangbk.tally.data.update

import io.github.guohangbk.tally.data.debug.DebugLog
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

/** 「检查更新」的三种结果，页面只认这一层，不关心 GitHub 的报文形态。 */
sealed interface UpdateCheck {
    data class UpToDate(val current: String) : UpdateCheck

    data class Available(val latest: String, val pageUrl: String) : UpdateCheck

    data object Failed : UpdateCheck
}

/**
 * 手动检查更新：查公开仓最新的 Release，拿 tag 里的版本号与安装包的 versionCode 比。
 *
 * 只在用户点「检查更新」时发一次请求——不轮询、不自动下载、不静默安装。
 * 版本号公式与 build.gradle 一致：`major*10000 + minor*100 + patch`。
 */
class UpdateChecker(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build(),
    private val endpoint: String = LATEST_RELEASE,
) {
    suspend fun check(installedCode: Long): UpdateCheck = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(endpoint)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "Tally-Android")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    DebugLog.log("Update", "检查更新失败 · HTTP ${response.code}")
                    return@withContext UpdateCheck.Failed
                }
                val body = response.body?.string().orEmpty()
                val release = runCatching { JSON.decodeFromString<GithubRelease>(body) }.getOrNull()
                val tag = release?.tagName?.removePrefix("v")
                val latestCode = tag?.let(::versionCodeOf)
                if (release == null || tag == null || latestCode == null || release.htmlUrl.isBlank()) {
                    DebugLog.log("Update", "检查更新失败 · 报文不完整")
                    return@withContext UpdateCheck.Failed
                }
                DebugLog.log("Update", "检查更新 · 安装 $installedCode · 最新 $tag($latestCode)")
                if (latestCode > installedCode) {
                    UpdateCheck.Available(latest = tag, pageUrl = release.htmlUrl)
                } else {
                    UpdateCheck.UpToDate(current = tag)
                }
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            DebugLog.log("Update", "检查更新失败 · ${failure.message ?: failure::class.simpleName}")
            UpdateCheck.Failed
        }
    }

    private companion object {
        const val LATEST_RELEASE = "https://api.github.com/repos/guohangbk/tally-app/releases/latest"
        val JSON = Json { ignoreUnknownKeys = true }
        val VERSION = Regex("""(\d+)\.(\d+)\.(\d+)""")

        fun versionCodeOf(tag: String): Long? {
            val parts = VERSION.matchEntire(tag)?.groupValues ?: return null
            return parts[1].toLong() * 10_000 + parts[2].toLong() * 100 + parts[3].toLong()
        }
    }
}

@Serializable
private data class GithubRelease(
    @SerialName("tag_name") val tagName: String? = null,
    @SerialName("html_url") val htmlUrl: String = "",
)
