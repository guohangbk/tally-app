package io.github.guohangbk.tally.data.attachment

import io.github.guohangbk.tally.data.db.dao.AttachmentDao
import java.util.concurrent.TimeUnit

/**
 * 附件回收：账单删除只软删行，图片文件此前永远留在磁盘上，越攒越多。
 *
 * 规则是「零引用 + 超过保留期」才物理删：**先删文件、再删行**——反过来会出现
 * 「行还在、图没了」的账单，那比留一个孤儿文件糟得多。删不掉文件的行留在原地，下次再试。
 * 挂在每日的 [io.github.guohangbk.tally.work.AutomationWorker] 上，不新建调度、不发通知。
 */
class AttachmentCleanup(
    private val store: AttachmentStore,
    private val dao: AttachmentDao,
    private val now: () -> Long = System::currentTimeMillis,
) {
    /** 回收了多少行、腾出多少字节。 */
    data class Report(val rows: Int, val freedBytes: Long)

    suspend fun run(keepDays: Long, limit: Int = BATCH): Report {
        val cutoff = now() - TimeUnit.DAYS.toMillis(keepDays)
        val expired = dao.findExpired(cutoff, limit)
        if (expired.isEmpty()) return Report(0, 0L)

        val candidates = expired.map { it.relativePath }.distinct()
        val unreferenced = candidates.filter { dao.liveCountForPath(it) == 0 }
        val (removed, freed) = store.deleteFiles(unreferenced)
        val removedPaths = removed.toSet()
        val done = expired.filter { it.relativePath in removedPaths }.map { it.id }
        if (done.isNotEmpty()) dao.hardDelete(done)
        return Report(done.size, freed)
    }

    companion object {
        /** 单次上限，避免在 Worker 里一次性扫完整库。 */
        const val BATCH = 500
    }
}
