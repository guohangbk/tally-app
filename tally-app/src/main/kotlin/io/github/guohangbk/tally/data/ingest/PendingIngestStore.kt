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
    /** 这一条从哪个入口来（通知 / 支付页 / 截图 / 语音 / 分享），卡上据此写来源。 */
    val source: String = "",
    /** 入队时刻：自动草稿靠它算 30 天过期。 */
    val createdAt: Long = 0L,
    /** 订单号：跨入口幂等按它认同一笔。 */
    val orderNo: String? = null,
    /** 自动草稿（通知 / 支付页抓到的）：只服务「一直没人管」的那批，30 天过期。 */
    val auto: Boolean = false,
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
        const val VERSION = 2
    }
}

/**
 * 多图逐笔确认的那份待办清单。
 *
 * 通知草稿与识别结果**落在同一份清单**里，用同一张卡确认，所以这里既要接得住识别批次，
 * 也要接得住后台通知追加进来的自动草稿。
 *
 * 识别完就落盘，确认一笔删一笔，全部处理完删文件；中途退出、被系统杀掉都还在，
 * 下次进首页靠 [count] 把那枚入口亮出来。选文件缓存而不是建表，是因为它**不属于账本**：
 * 不进备份、不参与同步、处理完就该消失，为它加两张表和一套迁移不划算；
 * 以后真要做多批次、强事务、按批次查询时再考虑入库。
 *
 * 写入一律「先写临时文件再改名」：写到一半被杀，旧文件还在，不会留下半个 JSON。
 * [save] 是**合并追加**而不是整批替换：通知一律追加、同笔合并；识别批次开新一批时，
 * 上一批还没确认的行（尤其自动草稿）必须带走，不能被整批替换无声丢掉。
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
        val live = live(batch.entries)
        mutableCount.value = live.size
        batch.copy(entries = live)
    }

    suspend fun save(
        source: String,
        droppedCount: Int,
        failures: List<PendingFailure>,
        entries: List<PendingIngestEntry>,
    ) {
        merge(entries) { _, merged ->
            PendingIngestBatch(
            savedAt = now(),
            source = source,
            droppedCount = droppedCount,
            failures = failures,
            entries = merged,
            )
        }
    }

    /**
     * 通知路径的追加：只加行，批次元信息（来源构成、丢掉的张数）沿用文件里已有的，
     * 不被通知这一下覆盖掉——通知没有「几张图」的概念。
     */
    suspend fun append(entries: List<PendingIngestEntry>) {
        merge(entries) { existing, merged ->
            existing.copy(savedAt = now(), entries = merged)
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

    /** 已在记账页保存/确认的行：按 key 精确摘掉，不能被 [save] 的合并逻辑再写回来。 */
    suspend fun removeKeys(keys: Set<Int>) {
        if (keys.isEmpty()) return
        val batch = withContext(Dispatchers.IO) { read() }
        val remaining = batch.entries.filterNot { it.key in keys }
        if (remaining.isEmpty()) {
            clear()
            return
        }
        mutableCount.value = remaining.size
        write(batch.copy(savedAt = now(), entries = remaining))
    }

    /**
     * 合并写盘：已有行里**已经解决**的（记过 / 已存 / 已撤）丢掉，过期的自动草稿丢掉，
     * 其余与新行按 [PendingIngestEntry.key] 合并（新行覆盖同 key 的旧行）。
     * 这样识别批次整批替换时，上一批没确认的行与后台追加的自动草稿都留得下来。
     */
    private suspend fun merge(
        entries: List<PendingIngestEntry>,
        build: (existing: PendingIngestBatch, merged: List<PendingIngestEntry>) -> PendingIngestBatch,
    ) {
        val (existing, merged) = withContext(Dispatchers.IO) {
            val batch = read()
            val map = LinkedHashMap<Int, PendingIngestEntry>()
            batch.entries.filter { !resolved(it.row) && !expired(it) }.forEach { map[it.key] = it }
            entries.forEach { map[it.key] = it }
            batch to map.values.toList()
        }
        // 合并完一行都不剩：把文件收掉，别留一个空壳让首页入口变成点开就消失的死入口。
        if (merged.isEmpty()) {
            mutableCount.value = 0
            withContext(Dispatchers.IO) {
                runCatching { file.delete(); File(dir, "$FILE_NAME.tmp").delete() }
            }
            return
        }
        val batch = build(existing, merged)
        // 先上状态再落盘：入口跟着内存立刻亮，写盘失败最多丢一次恢复能力，不影响这一轮。
        mutableCount.value = batch.entries.size
        write(batch)
    }

    private suspend fun write(batch: PendingIngestBatch) = withContext(Dispatchers.IO) {
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

    private fun read(): PendingIngestBatch {
        val text = runCatching { file.takeIf { it.isFile }?.readText() }.getOrNull() ?: return PendingIngestBatch()
        val batch = runCatching {
            json.decodeFromString(PendingIngestBatch.serializer(), text)
        }.onFailure { failure ->
            DebugLog.log("识别管线", "待确认清单读不出来，按没有处理：${failure.message.orEmpty()}")
        }.getOrNull() ?: return PendingIngestBatch()
        return batch.takeIf { it.version == PendingIngestBatch.VERSION } ?: PendingIngestBatch()
    }

    /** 还能动的行：入口与卡都只认它们，已经解决的不占数、不出现。 */
    private fun live(entries: List<PendingIngestEntry>): List<PendingIngestEntry> =
        entries.filter { !resolved(it.row) && !expired(it) }

    private fun resolved(row: DraftRow): Boolean = row.saved || row.duplicate || row.reverted

    /** 自动草稿 30 天没处理就过期：不落库、不再出现、不计入 N。只服务「一直没人管」的通知。 */
    private fun expired(entry: PendingIngestEntry): Boolean =
        entry.auto && entry.createdAt > 0L && (now() - entry.createdAt) >= AUTO_DRAFT_MILLIS

    private companion object {
        const val DIR_NAME = "ingest_pending"
        const val FILE_NAME = "v1.json"
        const val AUTO_DRAFT_MILLIS = 30L * 24 * 60 * 60 * 1000
    }
}
