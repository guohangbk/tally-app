package io.github.guohangbk.tally.data.repository

import androidx.room.withTransaction
import io.github.guohangbk.tally.data.db.TallyDatabase
import io.github.guohangbk.tally.data.db.dao.TagCategoryRow
import io.github.guohangbk.tally.data.db.dao.TagDao
import io.github.guohangbk.tally.data.db.dao.TagLedgerRow
import io.github.guohangbk.tally.data.db.dao.TagTotalsRow
import io.github.guohangbk.tally.data.db.dao.TagUsageRow
import io.github.guohangbk.tally.data.db.entity.BillTagEntity
import io.github.guohangbk.tally.data.db.entity.TagEntity
import io.github.guohangbk.tally.data.preferences.TallyPreferences
import kotlinx.coroutines.flow.Flow
import java.util.UUID

/** 标签详情一页要的四段数：合计、整年走势、分类构成、涉及账本。 */
data class TagDetail(
    val totals: TagTotalsRow,
    val monthlyExpense: List<Long>,
    val categories: List<TagCategoryRow>,
    val ledgers: List<TagLedgerRow>,
)

/**
 * 标签读写。标签全局一份、与账本无关；它只负责「这笔账属于哪件事」，
 * 不参与预算、报销、退款与合计口径——这里的查询也只用账单原本的那套 CASE。
 */
class TagRepository(
    private val database: TallyDatabase,
    private val tagDao: TagDao,
    private val preferences: TallyPreferences,
    private val now: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    fun observeActive(): Flow<List<TagUsageRow>> = tagDao.observeActive()

    fun observeArchived(): Flow<List<TagUsageRow>> = tagDao.observeArchived()

    fun observeLinkTick(): Flow<Long> = tagDao.observeLinkTick()

    fun observeAll(): Flow<List<TagEntity>> = tagDao.observeAllOnce()

    suspend fun find(id: String): TagEntity? = tagDao.find(id)

    suspend fun tagIdsForBill(billId: String): List<String> = tagDao.tagIdsForBill(billId)

    suspend fun tagsForBill(billId: String): List<TagEntity> = tagDao.tagsForBill(billId)

    suspend fun namesForBills(billIds: List<String>): Map<String, List<String>> {
        if (billIds.isEmpty()) return emptyMap()
        val grouped = HashMap<String, MutableList<String>>()
        billIds.chunked(IN_CHUNK).forEach { chunk ->
            tagDao.namesForBills(chunk).forEach { row ->
                grouped.getOrPut(row.billId) { mutableListOf() } += row.name
            }
        }
        return grouped
    }

    /**
     * 名称唯一是标签的唯一约束：判重不忽略 emoji，也不做大小写归一——
     * 「装修」与「装修🏠」是两枚，改名撞名同样拦下。
     */
    suspend fun create(rawName: String): TagEntity {
        val name = validateName(rawName)
        require(tagDao.findByName(name) == null) { "已经有同名标签「$name」" }
        val timestamp = now()
        val tag = TagEntity(
            id = newId(),
            name = name,
            sortOrder = (tagDao.maxSortOrder() ?: 0.0) + 1.0,
            createdAt = timestamp,
            updatedAt = timestamp,
            deviceId = preferences.deviceId(),
        )
        tagDao.upsert(tag)
        return tag
    }

    suspend fun rename(id: String, rawName: String) {
        val name = validateName(rawName)
        val existing = tagDao.findByName(name)
        require(existing == null || existing.id == id) { "已经有同名标签「$name」" }
        tagDao.rename(id, name, now())
    }

    suspend fun archive(id: String, archived: Boolean) {
        tagDao.setArchivedAt(id, if (archived) now() else null, now())
    }

    /** 删除只解绑：标签行软删、关联行清掉，账单、金额与分类一行不动。 */
    suspend fun delete(id: String) = database.withTransaction {
        tagDao.clearForTag(id)
        tagDao.softDelete(id, now())
    }

    /** 管理墙拖动后整条顺序落库；选择器、筛选与管理墙读的是同一列。 */
    suspend fun reorder(orderedIds: List<String>) = database.withTransaction {
        val timestamp = now()
        orderedIds.forEachIndexed { index, id ->
            tagDao.setSortOrder(id, index.toDouble(), timestamp)
        }
    }

    /** 记账页保存时整批替换：先清后写，空列表即「这一笔没有标签」。 */
    suspend fun setBillTags(billId: String, tagIds: List<String>) {
        tagDao.clearForBill(billId)
        if (tagIds.isNotEmpty()) {
            tagDao.attach(tagIds.distinct().map { BillTagEntity(billId = billId, tagId = it) })
        }
    }

    /** 批量补标：只加不减，已挂的不重复写。 */
    suspend fun attachToBills(billIds: List<String>, tagIds: List<String>) {
        if (billIds.isEmpty() || tagIds.isEmpty()) return
        billIds.chunked(IN_CHUNK).forEach { bills ->
            tagIds.chunked(IN_CHUNK).forEach { tags ->
                tagDao.attach(bills.flatMap { billId -> tags.map { BillTagEntity(billId, it) } })
            }
        }
    }

    suspend fun detachFromBills(billIds: List<String>, tagIds: List<String>) {
        if (billIds.isEmpty() || tagIds.isEmpty()) return
        billIds.chunked(IN_CHUNK).forEach { bills ->
            tagIds.chunked(IN_CHUNK).forEach { tags ->
                tagDao.detachMany(bills, tags)
            }
        }
    }

    /** 选中账单里同时挂着的标签：批量弹层用它做预选（交集），取消勾选就解绑。 */
    suspend fun commonTags(billIds: List<String>): Set<String> {
        if (billIds.isEmpty()) return emptySet()
        val hits = HashMap<String, MutableSet<String>>()
        billIds.chunked(IN_CHUNK).forEach { chunk ->
            tagDao.tagIdsForBills(chunk).forEach { link ->
                hits.getOrPut(link.tagId) { mutableSetOf() } += link.billId
            }
        }
        return hits.filterValues { it.size == billIds.size }.keys
    }

    /** 导入按名称匹配：不存在就建一枚新的，同名自动归到同一枚。 */
    suspend fun ensureByName(rawName: String): TagEntity {
        val name = rawName.trim()
        require(name.isNotEmpty()) { "标签名不能为空" }
        return tagDao.findByName(name) ?: create(name)
    }

    suspend fun detail(
        tagId: String,
        startAt: Long,
        endAt: Long,
        yearStart: Long,
        yearEnd: Long,
    ): TagDetail {
        val months = tagDao.monthlyExpense(tagId, yearStart, yearEnd)
            .associate { it.month to it.amountMinor }
        return TagDetail(
            totals = tagDao.totals(tagId, startAt, endAt),
            monthlyExpense = (1..12).map { months[it] ?: 0L },
            categories = tagDao.categoryComposition(tagId, startAt, endAt),
            ledgers = tagDao.ledgerBreakdown(tagId, startAt, endAt),
        )
    }

    private fun validateName(raw: String): String {
        val name = raw.trim()
        require(name.isNotEmpty()) { "标签名不能为空" }
        return name
    }

    private companion object {
        /** API 28 上 SQLite 的绑定上限是 999，`IN (…)` 两个维度都要切。 */
        const val IN_CHUNK = 500
    }
}
