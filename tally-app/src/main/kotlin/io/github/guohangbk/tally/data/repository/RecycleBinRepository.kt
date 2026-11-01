package io.github.guohangbk.tally.data.repository

import io.github.guohangbk.tally.data.attachment.AttachmentStore
import io.github.guohangbk.tally.data.db.dao.AttachmentDao
import io.github.guohangbk.tally.data.db.dao.BillDao
import io.github.guohangbk.tally.domain.model.PurgePreview
import io.github.guohangbk.tally.domain.model.PurgeResult
import io.github.guohangbk.tally.domain.model.RecycleCleanupReport
import io.github.guohangbk.tally.domain.model.RecycleGroup
import io.github.guohangbk.tally.domain.model.RecycleItem
import io.github.guohangbk.tally.domain.model.RecycleKeepDays
import io.github.guohangbk.tally.domain.model.RecycleSnapshot
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/**
 * 回收站。
 *
 * 没有新表：每一次删除都写了 `deleted_at`，回收站只是把这份一直存在的数据变成看得见、
 * 点得回。三条硬规则都来自已经踩过的坑：
 *
 * 1. **先删文件，再删行**。反过来会留下「行说它有图、文件已经没了」的账单；
 *    文件没删掉的账单整批留在原地，下一次 Worker 再试，所以既不漏文件也不说谎。
 * 2. **图片按内容哈希共享**。同一张图可能被两笔账单引用，只有集合外没有任何引用时才回收。
 * 3. **恢复时清掉指向已消失分类的引用**。分类可以在名下 0 笔未删账单时被硬删，
 *    而回收站里的软删账单还挂着它的 id；不清就恢复出一笔记账页选不回去的账。
 */
class RecycleBinRepository(
    private val billDao: BillDao,
    private val attachmentDao: AttachmentDao,
    private val store: AttachmentStore,
    private val settings: SettingRepository,
    private val zoneId: () -> ZoneId = ZoneId::systemDefault,
    private val now: () -> Long = System::currentTimeMillis,
) {
    /** 恢复了一条账单的结果；[recategorized] 是那些原分类已经不在了、只能先归为未分类的笔数。 */
    data class RestoreResult(val restored: Int, val recategorized: Int)

    suspend fun snapshot(limit: Int = LIST_LIMIT): RecycleSnapshot {
        val keepDays = settings.recycleKeepDays()
        val summary = billDao.recycledSummary()
        val moment = now()
        val items = billDao.findRecycledRows(limit).mapNotNull { row ->
            val deletedAt = row.bill.deletedAt ?: return@mapNotNull null
            RecycleItem(
                item = row.toListItem(),
                deletedAt = deletedAt,
                daysLeft = daysLeftOf(deletedAt, keepDays, moment),
            )
        }
        return RecycleSnapshot(
            loaded = true,
            groups = items
                .groupBy { item -> Instant.ofEpochMilli(item.deletedAt).atZone(zoneId()).toLocalDate() }
                .toSortedMap(compareByDescending { it })
                .map { (day, group) -> RecycleGroup(day, group) },
            totalBills = summary.bills,
            totalMinor = summary.minor,
            keepDays = keepDays,
            lastCleanup = settings.lastRecycleCleanup(),
            truncated = summary.bills > items.size,
        )
    }

    /**
     * 恢复：先清掉已经不存在分类的引用，再把账单与它的附件一起放回未删状态。
     * 附件必须跟着走——`BillRepository.delete` 是账单与附件一起软删的，只恢复一半
     * 就得到一笔「有 N 张图片」却打不开的账。
     */
    suspend fun restore(ids: List<String>): RestoreResult {
        if (ids.isEmpty()) return RestoreResult(0, 0)
        val timestamp = now()
        val recategorized = billDao.detachGoneCategories(ids, timestamp)
        val restored = billDao.restoreRecycled(ids, timestamp)
        attachmentDao.restoreByBills(ids)
        return RestoreResult(restored, recategorized)
    }

    suspend fun setKeepDays(days: RecycleKeepDays) = settings.setRecycleKeepDays(days)

    /** 确认框要提前说清的代价：几笔、多少钱、回收几张图、几张因为共用而留下。 */
    suspend fun previewPurge(ids: List<String>): PurgePreview {
        if (ids.isEmpty()) return PurgePreview(0, 0L, 0, 0)
        val rows = attachmentDao.findAllForBills(ids)
        val paths = rows.map { it.relativePath }.distinct()
        val reclaimable = paths.filter { attachmentDao.referencesOutside(it, ids) == 0 }
        return PurgePreview(
            bills = ids.size,
            totalMinor = billDao.recycledAmountOf(ids),
            images = paths.size,
            sharedImages = paths.size - reclaimable.size,
        )
    }

    /** 用户点「彻底删除」与每日超期清理共用这一条路径。 */
    suspend fun purge(ids: List<String>): PurgeResult {
        if (ids.isEmpty()) return PurgeResult(0, 0L)
        val rows = attachmentDao.findAllForBills(ids)
        val paths = rows.map { it.relativePath }.distinct()
        val reclaimable = paths.filter { attachmentDao.referencesOutside(it, ids) == 0 }
        val (removed, freed) = store.deleteFiles(reclaimable)
        val gone = removed.toSet()
        // 有一张图没删干净的账单先不动：它的行留着，下次清理会再试一遍这些路径。
        val blocked = ids.filter { id -> rows.any { it.billId == id && it.relativePath !in gone } }
        val cleared = ids.filterNot { it in blocked.toSet() }
        if (cleared.isNotEmpty()) {
            attachmentDao.hardDeleteByBills(cleared)
            // 原单被彻底删除后，指向它的退款不能再留一个悬空 id：退回「未关联原单」，不冲抵任何分类。
            billDao.detachRefunds(cleared, now())
            billDao.hardDeleteByIds(cleared)
        }
        return PurgeResult(purgedBills = cleared.size, freedBytes = freed, deferredBills = blocked.size)
    }

    /**
     * 每日 Worker 调这一条。[days] 由调用方从设置里解析一次——附件回收要用同一个值，
     * 两条清理各自读一次设置就会在用户改设置的那一刻跑出两个口径。
     * 选了「永不清理」时 Worker 根本不会调到这里。
     */
    suspend fun purgeExpired(days: Long, limit: Int = BATCH): PurgeResult {
        val cutoff = now() - TimeUnit.DAYS.toMillis(days)
        val expired = billDao.findExpiredRecycledIds(cutoff, limit)
        if (expired.isEmpty()) return PurgeResult(0, 0L)
        val result = purge(expired)
        if (result.purgedBills > 0) {
            settings.recordRecycleCleanup(
                RecycleCleanupReport(now(), result.purgedBills, result.freedBytes),
            )
        }
        return result
    }

    private fun daysLeftOf(deletedAt: Long, keepDays: RecycleKeepDays, moment: Long): Long? {
        val days = keepDays.days ?: return null
        val remaining = deletedAt + TimeUnit.DAYS.toMillis(days) - moment
        return if (remaining <= 0L) 0L else (remaining + TimeUnit.DAYS.toMillis(1) - 1) / TimeUnit.DAYS.toMillis(1)
    }

    companion object {
        /** 首屏上限。汇总行始终按全库算，所以截断不会把「38 笔」说成「20 笔」。 */
        const val LIST_LIMIT = 500

        /** 单次清理上限，与附件回收同一档，避免在 Worker 里扫完整库。 */
        const val BATCH = 500
    }
}
