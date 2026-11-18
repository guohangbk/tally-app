package io.github.guohangbk.tally.data.ingest

import io.github.guohangbk.tally.data.debug.DebugLog
import io.github.guohangbk.tally.domain.model.DraftRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** 一条等着人逐笔确认的草稿：重建卡上那一行所需的全部字段，**不含原图**。 */
@Serializable
data class PendingIngestEntry(
    val key: Int,
    val imageIndex: Int,
    val checked: Boolean = true,
    val row: DraftRow,
)

/** 一张没识别到的图：卡头要按它说「N 张里 M 张没认出来」。 */
@Serializable
data class PendingFailure(val imageIndex: Int, val reason: String)

/** 待确认的一整批（一次识别就是一批）。[version] 变了就把旧文件当过期直接丢掉。 */
@Serializable
data class PendingIngestBatch(
    val version: Int = VERSION,
    val savedAt: Long = 0L,
    val source: String = "",
    val droppedCount: Int = 0,
    val failures: List<PendingFailure> = emptyList(),
    val entries: List<PendingIngestEntry> = emptyList(),
) {
    companion object {
        /** 文件格式版本；结构一变就抬，旧版直接丢弃而不是硬解。 */
        const val VERSION = 1
    }
}

/**
 * 多图逐笔确认的那份待办清单。
 *
 * 识别完就落盘，确认一笔删一笔，全部处理完删文件；中途退出、被系统杀掉都还在，
 * 下次进首页靠 [count] 把那枚入口亮出来。选文件缓存而不是建表，是因为它**不属于账本**：
 * 不进备份、不参与同步、处理完就该消失，为它加两张表和一套迁移不划算；
 * 以后真要做多批次、强事务、按批次查询时再考虑入库。
 *
 * 写入一律「先写临时文件再改名」：写到一半被杀，旧文件还在，不会留下半个 JSON。
 */
class PendingIngestStore(
    root: File,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val dir = File(root, DIR_NAME)
    private val file = File(dir, FILE_NAME)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val mutableCount = MutableStateFlow(0)

    /** 待确认笔数：首页那枚入口只认它，不自己读文件。 */
    val count: StateFlow<Int> = mutableCount.asStateFlow()

    suspend fun load(): PendingIngestBatch = withContext(Dispatchers.IO) {
        val batch = read()
        // 入口只数「还能动」的行：老文件里可能留着「已经记过」那些，它们点不了也不该占数。
        mutableCount.value = batch.entries.count { !it.row.duplicate }
        batch
    }

    suspend fun save(
        source: String,
        droppedCount: Int,
        failures: List<PendingFailure>,
        entries: List<PendingIngestEntry>,
    ) {
        if (entries.isEmpty()) {
            clear()
            return
        }
        val batch = PendingIngestBatch(
            savedAt = now(),
            source = source,
            droppedCount = droppedCount,
            failures = failures,
            entries = entries,
        )
        // 先上状态再落盘：入口跟着内存立刻亮，写盘失败最多丢一次恢复能力，不影响这一轮。
        mutableCount.value = entries.size
        withContext(Dispatchers.IO) {
            runCatching {
                if (!dir.isDirectory && !dir.mkdirs()) error("建不出目录 ${dir.path}")
                val temp = File(dir, "$FILE_NAME.tmp")
                temp.writeText(json.encodeToString(PendingIngestBatch.serializer(), batch))
                if (!temp.renameTo(file)) {
                    file.delete()
                    if (!temp.renameTo(file)) error("改名失败 ${temp.path}")
                }
            }.onFailure { failure ->
                DebugLog.log("识别管线", "待确认清单没写进去：${failure.message.orEmpty()}")
            }
        }
    }

    suspend fun clear() {
        mutableCount.value = 0
        withContext(Dispatchers.IO) {
            runCatching {
                file.delete()
                File(dir, "$FILE_NAME.tmp").delete()
            }
        }
    }

    private fun read(): PendingIngestBatch {
        val text = runCatching { file.takeIf { it.isFile }?.readText() }.getOrNull() ?: return PendingIngestBatch()
        val batch = runCatching {
            json.decodeFromString(PendingIngestBatch.serializer(), text)
        }.onFailure { failure ->
            DebugLog.log("识别管线", "待确认清单读不出来，按没有处理：${failure.message.orEmpty()}")
        }.getOrNull() ?: return PendingIngestBatch()
        return batch.takeIf { it.version == PendingIngestBatch.VERSION } ?: PendingIngestBatch()
    }

    private companion object {
        const val DIR_NAME = "ingest_pending"
        const val FILE_NAME = "v1.json"
    }
}
