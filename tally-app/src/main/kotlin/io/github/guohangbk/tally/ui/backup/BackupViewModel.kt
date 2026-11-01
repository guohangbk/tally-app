package io.github.guohangbk.tally.ui.backup

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.guohangbk.tally.data.preferences.TallySettings
import io.github.guohangbk.tally.data.storage.PublicDownloads
import io.github.guohangbk.tally.di.AppGraph
import io.github.guohangbk.tally.domain.model.BackupManifest
import io.github.guohangbk.tally.domain.model.RestoreResult
import io.github.guohangbk.tally.domain.model.TallyError
import io.github.guohangbk.tally.domain.model.WebDavConfig
import io.github.guohangbk.tally.domain.model.WebDavFile
import io.github.guohangbk.tally.domain.sha256Hex
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** 恢复前的待确认状态：已经读到明文清单，等用户输入密码。 */
data class PendingRestore(
    val manifest: BackupManifest,
    val bytes: ByteArray,
    val sourceName: String,
)

/** 一个已经落在公共「下载/Tally」里的备份包。 */
data class SavedBackup(
    val fileName: String,
    val uri: Uri,
    val absolutePath: String,
    val sizeBytes: Long,
    val digest: String,
    val createdAt: Long,
    val billCount: Int,
    val includesImages: Boolean,
)

data class BackupUiState(
    val settings: TallySettings = TallySettings.DEFAULT,
    val webDav: WebDavConfig = WebDavConfig(),
    val hasWebDavPassword: Boolean = false,
    val imageEstimateBytes: Long = 0L,
    val includeImages: Boolean = false,
    val busy: Boolean = false,
    val notice: String? = null,
    val error: String? = null,
    val exportResult: SavedBackup? = null,
    val history: List<SavedBackup> = emptyList(),
    val pendingRestore: PendingRestore? = null,
    val remoteFiles: List<WebDavFile> = emptyList(),
    val remoteLoaded: Boolean = false,
)

/**
 * P19 备份与同步。导出的包写进公共「下载/Tally」，屏上印出完整路径——
 * 藏在应用私有目录里等于没备份，换机时拿不到那个文件。
 */
class BackupViewModel(
    private val graph: AppGraph,
    private val context: Context,
) : ViewModel() {
    private val local = MutableStateFlow(BackupUiState())
    private val remote = MutableStateFlow<List<WebDavFile>>(emptyList())
    private val remoteLoaded = MutableStateFlow(false)

    val state: StateFlow<BackupUiState> = combine(
        combine(graph.preferences.settings, graph.webDavRepository.config) { settings, webDav ->
            settings to webDav
        },
        combine(local, remote, remoteLoaded) { current, files, loaded ->
            Triple(current, files, loaded)
        },
    ) { (settings, webDav), (current, files, loaded) ->
        current.copy(
            settings = settings,
            webDav = webDav,
            hasWebDavPassword = graph.webDavRepository.hasPassword(),
            remoteFiles = files,
            remoteLoaded = loaded,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BackupUiState())

    init {
        refreshHistory()
        refreshEstimate()
    }

    fun setIncludeImages(value: Boolean) {
        local.value = local.value.copy(includeImages = value)
        refreshEstimate()
    }

    fun clearMessages() {
        local.value = local.value.copy(notice = null, error = null)
    }

    fun dismissExport() {
        local.value = local.value.copy(exportResult = null)
    }

    /** 备份历史就是下载目录里那批 .tally 文件：本地优先，不额外建表。 */
    fun refreshHistory() {
        viewModelScope.launch(Dispatchers.IO) {
            val rows = runCatching {
                PublicDownloads.list(context, ".tally").map { file ->
                    SavedBackup(
                        fileName = file.name,
                        uri = file.uri,
                        absolutePath = file.location,
                        sizeBytes = file.sizeBytes,
                        digest = "",
                        createdAt = file.writtenAt,
                        billCount = 0,
                        includesImages = false,
                    )
                }
            }.getOrDefault(emptyList())
            local.value = local.value.copy(history = rows)
        }
    }

    fun export(password: String) = runAction {
        val result = graph.backupService.export(
            io.github.guohangbk.tally.domain.model.BackupOptions(
                password = password,
                includeImages = local.value.includeImages,
            ),
        )
        val saved = write(result.fileName, result.bytes)
        graph.preferences.setLastBackupAt(result.manifest.createdAt)
        local.value = local.value.copy(
            exportResult = saved.copy(
                digest = sha256Hex(result.bytes).take(12),
                billCount = result.manifest.billCount,
                includesImages = result.manifest.includesImages,
            ),
            notice = "备份已生成 · ${result.manifest.billCount} 笔账单",
        )
        refreshHistory()
    }

    /** 先读清单，不要求密码；确认页展示备份时间与是否含图片。 */
    fun prepareRestore(bytes: ByteArray, sourceName: String) {
        viewModelScope.launch {
            try {
                val manifest = withContext(Dispatchers.IO) { graph.backupService.inspect(bytes) }
                local.value = local.value.copy(pendingRestore = PendingRestore(manifest, bytes, sourceName), error = null)
            } catch (error: TallyError) {
                local.value = local.value.copy(error = error.message)
            } catch (error: Exception) {
                local.value = local.value.copy(error = error.message ?: "无法读取该备份包")
            }
        }
    }

    fun cancelRestore() {
        local.value = local.value.copy(pendingRestore = null)
    }

    fun restore(password: String) {
        val pending = local.value.pendingRestore ?: return
        runAction {
            val result = graph.backupService.restore(pending.bytes, password)
            graph.notifyDataReplaced()
            local.value = local.value.copy(
                pendingRestore = null,
                notice = "恢复完成 · ${result.restoredBills} 笔账单",
            )
        }
    }

    fun testWebDav(config: WebDavConfig, password: String) = runAction {
        val result = graph.webDavRepository.test(config, password)
        local.value = local.value.copy(notice = result.message)
        if (!result.success) throw IllegalStateException(result.message)
    }

    /** 配置页的「测试并保存」：只有测试通过，WebDavRepository 才写入 DataStore 与 Keystore。 */
    fun saveWebDav(config: WebDavConfig, password: String) = testWebDav(config, password)

    fun refreshRemote() = runAction {
        remote.value = graph.webDavRepository.list()
        remoteLoaded.value = true
        local.value = local.value.copy(notice = "远端文件 ${remote.value.size} 个")
    }

    fun uploadToWebDav(password: String) = runAction {
        val result = graph.backupService.export(
            io.github.guohangbk.tally.domain.model.BackupOptions(
                password = password,
                includeImages = local.value.includeImages,
            ),
        )
        write(result.fileName, result.bytes)
        graph.webDavRepository.upload(result.fileName, result.bytes)
        remote.value = runCatching { graph.webDavRepository.list() }.getOrDefault(remote.value)
        remoteLoaded.value = true
        local.value = local.value.copy(notice = "已上传 ${result.fileName}")
        refreshHistory()
    }

    fun downloadFromWebDav(file: WebDavFile) = runAction {
        val bytes = graph.webDavRepository.download(file.name)
        val manifest = graph.backupService.inspect(bytes)
        local.value = local.value.copy(
            pendingRestore = PendingRestore(manifest, bytes, file.name),
            notice = "已下载 ${file.name}，请确认后恢复",
        )
    }

    private suspend fun write(fileName: String, bytes: ByteArray): SavedBackup {
        val saved = PublicDownloads.write(context, fileName, "application/octet-stream", bytes)
        return SavedBackup(
            fileName = saved.name,
            uri = saved.uri,
            absolutePath = saved.location,
            sizeBytes = saved.sizeBytes,
            digest = "",
            createdAt = saved.writtenAt,
            billCount = 0,
            includesImages = false,
        )
    }

    private fun refreshEstimate() {
        viewModelScope.launch {
            val bytes = withContext(Dispatchers.IO) { graph.backupService.estimate(local.value.includeImages) }
            local.value = local.value.copy(imageEstimateBytes = bytes)
        }
    }

    private fun runAction(block: suspend () -> Unit) {
        viewModelScope.launch {
            local.value = local.value.copy(busy = true, error = null)
            try {
                withContext(Dispatchers.IO) { block() }
                local.value = local.value.copy(busy = false)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: TallyError) {
                local.value = local.value.copy(busy = false, error = error.message)
            } catch (error: Exception) {
                local.value = local.value.copy(busy = false, error = error.message ?: "操作没有完成")
            }
        }
    }
}
