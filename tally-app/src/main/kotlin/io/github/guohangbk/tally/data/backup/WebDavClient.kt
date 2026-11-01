package io.github.guohangbk.tally.data.backup

import io.github.guohangbk.tally.domain.model.TallyError
import io.github.guohangbk.tally.domain.model.WebDavConfig
import io.github.guohangbk.tally.domain.model.WebDavConnectionResult
import io.github.guohangbk.tally.domain.model.WebDavFile
import okhttp3.Credentials
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.time.Instant
import java.util.concurrent.TimeUnit
import javax.xml.parsers.DocumentBuilderFactory

/**
 * WebDAV 手动快照客户端。只做三件事：连接测试、列远端文件、上传/下载指定备份包；
 * 不做双向合并、不做后台静默上传。
 */
class WebDavClient(
    private val client: OkHttpClient = defaultClient(),
) {
    suspend fun test(config: WebDavConfig, password: String): WebDavConnectionResult {
        if (!config.isConfigured) return WebDavConnectionResult(false, "请先填写服务器地址与用户名")
        val url = collectionUrl(config) ?: return WebDavConnectionResult(false, "服务器地址格式不正确")
        return try {
            val response = execute(Request.Builder().url(url).method("PROPFIND", propfindBody()).build(), config, password)
            response.use {
                when {
                    it.isSuccessful -> {
                        ensureDirectory(config, password)
                        WebDavConnectionResult(true, "连接成功 · ${config.baseUrl}")
                    }
                    it.code == 401 || it.code == 403 -> WebDavConnectionResult(false, "连接失败（${it.code}），请检查用户名与密码")
                    it.code == 404 -> WebDavConnectionResult(false, "连接失败（404），请检查地址或目录")
                    else -> WebDavConnectionResult(false, "连接失败（${it.code}）")
                }
            }
        } catch (error: Exception) {
            WebDavConnectionResult(false, "连接失败：${error.message ?: "网络不可达"}")
        }
    }

    suspend fun list(config: WebDavConfig, password: String): List<WebDavFile> {
        val url = directoryUrl(config) ?: throw TallyError.WebDavNotConfigured
        val response = execute(Request.Builder().url(url).method("PROPFIND", propfindBody(depth = "1")).build(), config, password)
        response.use {
            if (!it.isSuccessful) throw IllegalStateException("列目录失败（${it.code}）")
            val body = it.body?.bytes() ?: return emptyList()
            return parseEntries(body)
        }
    }

    suspend fun upload(config: WebDavConfig, password: String, fileName: String, bytes: ByteArray) {
        val url = fileUrl(config, fileName) ?: throw TallyError.WebDavNotConfigured
        val response = execute(
            Request.Builder().url(url)
                .put(bytes.toRequestBody("application/octet-stream".toMediaType()))
                .build(),
            config,
            password,
        )
        response.use {
            if (!it.isSuccessful) throw IllegalStateException("上传失败（${it.code}）")
        }
    }

    suspend fun download(config: WebDavConfig, password: String, fileName: String): ByteArray {
        val url = fileUrl(config, fileName) ?: throw TallyError.WebDavNotConfigured
        val response = execute(Request.Builder().url(url).get().build(), config, password)
        response.use {
            if (!it.isSuccessful) throw IllegalStateException("下载失败（${it.code}）")
            return it.body?.bytes() ?: throw IllegalStateException("下载内容为空")
        }
    }

    /** 目录不存在时创建；部分服务端要求目录已存在才能 PUT 文件。 */
    private suspend fun ensureDirectory(config: WebDavConfig, password: String) {
        val url = directoryUrl(config) ?: return
        val response = execute(Request.Builder().url(url).method("MKCOL", ByteArray(0).toRequestBody(null)).build(), config, password)
        response.close()
    }

    private fun execute(request: Request, config: WebDavConfig, password: String): Response {
        val withAuth = request.newBuilder()
            .header("Authorization", Credentials.basic(config.userName, password))
            .build()
        return client.newCall(withAuth).execute()
    }

    private fun parseEntries(body: ByteArray): List<WebDavFile> {
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(ByteArrayInputStream(body))
        val nodes = doc.getElementsByTagNameNS("*", "response")
        val result = mutableListOf<WebDavFile>()
        for (index in 0 until nodes.length) {
            val response = nodes.item(index) as? Element ?: continue
            val href = response.textOf("href") ?: continue
            if (href.endsWith("/")) continue
            if (!href.endsWith(".tally")) continue
            val size = response.textOf("getcontentlength")?.toLongOrNull() ?: 0L
            val modified = response.textOf("getlastmodified")
                ?.let { runCatching { java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME.parse(it, Instant::from) }.getOrNull() }
                ?.toEpochMilli()
                ?: 0L
            val name = href.substringAfterLast('/')
            if (name.isEmpty()) continue
            result += WebDavFile(name = name, sizeBytes = size, lastModified = modified)
        }
        return result.sortedByDescending { it.lastModified }
    }

    private fun Element.textOf(tag: String): String? {
        val nodes = getElementsByTagNameNS("*", tag)
        if (nodes.length == 0) return null
        return nodes.item(0)?.textContent?.trim()?.ifEmpty { null }
    }

    private fun collectionUrl(config: WebDavConfig): String? = normalize(config.baseUrl)

    private fun directoryUrl(config: WebDavConfig): String? {
        val base = normalize(config.baseUrl) ?: return null
        val directory = config.remoteDirectory.trim().trim('/')
        return if (directory.isEmpty()) base else base.trimEnd('/') + "/" + directory
    }

    private fun fileUrl(config: WebDavConfig, fileName: String): String? {
        val directory = directoryUrl(config) ?: return null
        return directory.trimEnd('/') + "/" + fileName
    }

    private fun normalize(url: String): String? {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return null
        val withScheme = if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) trimmed else "https://$trimmed"
        return withScheme.toHttpUrlOrNull()?.toString()
    }

    private fun propfindBody(depth: String = "0") = """
        <?xml version="1.0" encoding="utf-8" ?>
        <d:propfind xmlns:d="DAV:">
          <d:prop>
            <d:displayname/>
            <d:getcontentlength/>
            <d:getlastmodified/>
            <d:resourcetype/>
          </d:prop>
        </d:propfind>
    """.trimIndent().toRequestBody("application/xml".toMediaType())

    companion object {
        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }
}
