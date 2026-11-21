package io.github.guohangbk.tally.data.ingest

import io.github.guohangbk.tally.data.debug.DebugLog
import io.github.guohangbk.tally.domain.ingest.IdempotencyRecord
import io.github.guohangbk.tally.domain.ingest.IdempotencyWindows
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 跨入口幂等台账：与待确认清单**分开**存，只记「认这笔的键」，不记账单内容。
 *
 * 先到者保留，后到者只补空字段；来源两枚都留住。容量有上限、记录有 30 天 TTL，
 * 不然长跑下去这个文件会无限长。写入一律「先写临时文件再改名」。
 */
@Serializable
internal data class IdempotencyLedgerFile(
    val version: Int = VERSION,
    val records: List<IdempotencyRecord> = emptyList(),
) {
    companion object {
        const val VERSION = 1
    }
}

class IdempotencyLedger(
    root: File,
    private val now: () -> Long = System::currentTimeMillis,
    /** 台账最多留多少条：超出就丢最旧的，反正只服务近期的幂等判定。 */
    private val capacity: Int = 200,
) {
    private val dir = File(root, DIR_NAME)
    private val file = File(dir, FILE_NAME)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** 读台账并清掉超过 30 天的记录；读不出来按空台账处理，不炸调用方。 */
    suspend fun load(): List<IdempotencyRecord> = withContext(Dispatchers.IO) {
        val text = runCatching { file.takeIf { it.isFile }?.readText() }.getOrNull() ?: return@withContext emptyList()
        val parsed = runCatching {
            json.decodeFromString(IdempotencyLedgerFile.serializer(), text)
        }.onFailure { failure ->
            DebugLog.log("识别管线", "幂等台账读不出来，按空处理：${failure.message.orEmpty()}")
        }.getOrNull() ?: return@withContext emptyList()
        parsed.records.filter { age(it.createdAt) < IdempotencyWindows.ORDER_NO_MILLIS }
    }

    /**
     * 记一条。同订单号的只更新（补空字段、并来源），不重复堆；
     * 写完清 30 天以上的，再按容量截断，保证文件有界。
     */
    suspend fun commit(record: IdempotencyRecord) {
        withContext(Dispatchers.IO) {
            runCatching {
                val existing = load()
                val merged = existing.filterNot { it.orderNo != null && it.orderNo == record.orderNo }
                val kept = (merged + record)
                    .filter { age(it.createdAt) < IdempotencyWindows.ORDER_NO_MILLIS }
                    .sortedBy { it.createdAt }
                    .takeLast(capacity)
                if (!dir.isDirectory && !dir.mkdirs()) error("建不出目录 ${dir.path}")
                val temp = File(dir, "$FILE_NAME.tmp")
                temp.writeText(json.encodeToString(IdempotencyLedgerFile.serializer(), IdempotencyLedgerFile(records = kept)))
                if (!temp.renameTo(file)) {
                    file.delete()
                    if (!temp.renameTo(file)) error("改名失败 ${temp.path}")
                }
            }.onFailure { failure ->
                DebugLog.log("识别管线", "幂等台账没写进去：${failure.message.orEmpty()}")
            }
        }
    }

    private fun age(createdAt: Long): Long = (now() - createdAt).coerceAtLeast(0L)

    private companion object {
        const val DIR_NAME = "ingest_idempotency"
        const val FILE_NAME = "ledger.json"
    }
}
