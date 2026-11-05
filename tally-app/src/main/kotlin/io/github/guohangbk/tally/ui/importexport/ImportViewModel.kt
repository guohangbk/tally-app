package io.github.guohangbk.tally.ui.importexport

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.guohangbk.tally.data.repository.LedgerListItem
import io.github.guohangbk.tally.data.storage.PublicDownloads
import io.github.guohangbk.tally.di.AppGraph
import io.github.guohangbk.tally.domain.model.ImportOptions
import io.github.guohangbk.tally.domain.model.ImportOutcome
import io.github.guohangbk.tally.domain.model.ImportPreview
import io.github.guohangbk.tally.domain.model.ImportSourceType
import io.github.guohangbk.tally.domain.parser.ParsedTable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class ImportUiState(
    val sourceType: ImportSourceType? = null,
    val fileName: String? = null,
    val table: ParsedTable? = null,
    val preview: ImportPreview? = null,
    val ledgers: List<LedgerListItem> = emptyList(),
    val targetLedgerId: String? = null,
    val dedupe: Boolean = true,
    val skipInvalid: Boolean = false,
    val busy: Boolean = false,
    val outcome: ImportOutcome? = null,
    val error: String? = null,
    /** 模板落到哪个路径了——保存成功要给出位置，不能只说「已保存」。 */
    val savedNotice: String? = null,
)

class ImportViewModel(
    private val graph: AppGraph,
    private val context: Context,
) : ViewModel() {
    private val local = MutableStateFlow(ImportUiState())
    private var fileBytes: ByteArray? = null

    val state: StateFlow<ImportUiState> = combine(
        local,
        graph.ledgerRepository.items,
    ) { current, ledgers ->
        current.copy(ledgers = ledgers.filter { !it.ledger.isArchived })
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ImportUiState())

    init {
        viewModelScope.launch {
            graph.ledgerRepository.bootstrap()
            val default = graph.billRepository.defaultLedgerId()
            local.value = local.value.copy(targetLedgerId = default)
        }
    }

    fun selectSource(source: ImportSourceType) {
        local.value = local.value.copy(sourceType = source, error = null, outcome = null)
    }

    fun selectFile(uri: Uri) {
        viewModelScope.launch {
            local.value = local.value.copy(busy = true, error = null)
            try {
                val bytes = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: throw IllegalStateException("无法读取该文件")
                }
                val name = withContext(Dispatchers.IO) { displayName(uri) }
                val table = withContext(Dispatchers.Default) {
                    graph.importService.parse(bytes, name, local.value.sourceType)
                }
                val existing = withContext(Dispatchers.IO) { graph.importService.existingKeys() }
                val preview = withContext(Dispatchers.Default) { graph.importService.preview(table, existing) }
                fileBytes = bytes
                local.value = local.value.copy(
                    busy = false,
                    fileName = name,
                    table = table,
                    preview = preview,
                    sourceType = table.sourceType,
                    error = null,
                )
            } catch (error: Exception) {
                local.value = local.value.copy(busy = false, error = error.message ?: "文件解析失败")
            }
        }
    }

    fun selectTargetLedger(id: String) {
        local.value = local.value.copy(targetLedgerId = id)
    }

    /** SAF 的 lastPathSegment 是文档 ID（msf:57 这类），真正的文件名得查 DISPLAY_NAME。 */
    private fun displayName(uri: Uri): String {
        val fromProvider = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        }.getOrNull()
        if (!fromProvider.isNullOrBlank()) return fromProvider
        val segment = uri.lastPathSegment?.substringAfterLast('/').orEmpty()
        return if (segment.endsWith(".csv", true) || segment.endsWith(".xlsx", true)) segment else "import.csv"
    }

    fun setDedupe(enabled: Boolean) {
        local.value = local.value.copy(dedupe = enabled)
    }

    fun setSkipInvalid(enabled: Boolean) {
        local.value = local.value.copy(skipInvalid = enabled)
    }

    fun importNow() {
        val table = local.value.table ?: return
        val bytes = fileBytes ?: return
        if (table.sourceType == ImportSourceType.ALIPAY && local.value.targetLedgerId == null) {
            local.value = local.value.copy(error = "请选择导入到哪个账本")
            return
        }
        viewModelScope.launch {
            local.value = local.value.copy(busy = true, error = null)
            val outcome = withContext(Dispatchers.IO) {
                graph.importService.import(
                    table = table,
                    options = ImportOptions(
                        sourceType = table.sourceType,
                        targetLedgerId = local.value.targetLedgerId,
                        dedupe = local.value.dedupe,
                        skipInvalidRows = local.value.skipInvalid,
                    ),
                    fileName = local.value.fileName ?: "import",
                    sourceBytes = bytes,
                )
            }
            local.value = local.value.copy(
                busy = false,
                outcome = outcome,
                error = outcome.report.message.takeIf { outcome.rolledBack },
            )
            // 写进去了就是有账，首次引导该替他结掉；回滚的那次不算，留在引导页让他重来。
            if (!outcome.rolledBack) graph.preferences.completeOnboarding()
        }
    }

    fun reset() {
        fileBytes = null
        local.value = ImportUiState(targetLedgerId = local.value.targetLedgerId)
    }

    fun clearError() {
        local.value = local.value.copy(error = null)
    }

    /** 模板下载失败也要就地说明，不要静默。 */
    fun reportError(message: String) {
        local.value = local.value.copy(error = message)
    }

    /**
     * 模板真的写进手机「下载」目录：唤起分享面板不算保存，
     * 用户下一步是在文件管理器里选到这个文件。
     */
    fun saveTemplate() {
        viewModelScope.launch {
            try {
                val bytes = withContext(Dispatchers.IO) { graph.exportService.templateCsv() }
                val saved = withContext(Dispatchers.IO) {
                    PublicDownloads.write(context, "tally_template.csv", "text/csv", bytes)
                }
                local.value = local.value.copy(savedNotice = saved.location, error = null)
            } catch (failure: Exception) {
                local.value = local.value.copy(error = failure.message ?: "模板保存失败")
            }
        }
    }
}
