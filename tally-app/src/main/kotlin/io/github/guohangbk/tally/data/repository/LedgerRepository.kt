package io.github.guohangbk.tally.data.repository

import io.github.guohangbk.tally.data.db.dao.BillDao
import io.github.guohangbk.tally.data.db.dao.LedgerDao
import io.github.guohangbk.tally.data.db.entity.LedgerEntity
import io.github.guohangbk.tally.data.db.query.LedgerStat
import io.github.guohangbk.tally.data.preferences.TallyPreferences
import io.github.guohangbk.tally.domain.model.DefaultCategoryTree
import io.github.guohangbk.tally.domain.model.TallyError
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import java.util.UUID

/** 账本连同它的聚合口径，供 P07 列表直接展示。 */
data class LedgerListItem(
    val ledger: LedgerEntity,
    val stat: LedgerStat,
) {
    val isEmpty: Boolean get() = stat.billCount == 0
}

/**
 * 账本职责：创建、设默认、归档、删除迁移。
 * 默认账本全局唯一，删除默认账本必须先转移默认身份。
 */
class LedgerRepository(
    private val ledgerDao: LedgerDao,
    private val billDao: BillDao,
    private val categories: CategoryRepository,
    private val preferences: TallyPreferences,
    private val now: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    val items: Flow<List<LedgerListItem>> = combine(
        ledgerDao.observeActive(),
        billDao.observeLedgerStats(),
    ) { ledgers, stats ->
        val byId = stats.associateBy { it.ledgerId }
        ledgers.map { ledger -> LedgerListItem(ledger, byId[ledger.id] ?: emptyStat(ledger.id)) }
    }.distinctUntilChanged()

    /**
     * 只要账本身份（标题、切换列表），不带任何聚合。首页用它，避免为了显示几个笔数
     * 去跑 [items] 里的整表 GROUP BY（observeLedgerStats 扫全部历史 bill）。
     */
    val active: Flow<List<LedgerEntity>> = ledgerDao.observeActive().distinctUntilChanged()

    /** 首次启动创建「日常账本」并补齐全机默认分类。 */
    suspend fun bootstrap(): LedgerEntity {
        categories.ensureDefaultTree()
        ledgerDao.findDefault()?.let { return it }
        val all = ledgerDao.findAllOnce()
        val any = all.firstOrNull { !it.isArchived } ?: all.firstOrNull()
        if (any != null) {
            ledgerDao.setDefault(any.id, now())
            return any
        }
        return create(name = DefaultCategoryTree.DEFAULT_LEDGER_NAME, iconKey = "ledger", asDefault = true)
    }

    val defaultLedger: Flow<LedgerEntity?> = ledgerDao.observeDefault()

    suspend fun defaultLedger(): LedgerEntity? = ledgerDao.findDefault()

    suspend fun create(name: String, iconKey: String = "ledger", asDefault: Boolean? = null): LedgerEntity {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) throw TallyError.EmptyName
        val deviceId = preferences.deviceId()
        val timestamp = now()
        val isFirst = ledgerDao.countActive() == 0
        val ledger = LedgerEntity(
            id = newId(),
            name = trimmed,
            iconKey = iconKey,
            isDefault = asDefault ?: isFirst,
            isArchived = false,
            sortOrder = timestamp.toDouble(),
            createdAt = timestamp,
            updatedAt = timestamp,
            deviceId = deviceId,
        )
        ledgerDao.upsert(ledger)
        if (ledger.isDefault) ledgerDao.setDefault(ledger.id, timestamp)
        return ledger
    }

    /** 编辑入口一次改两样：名称与图标。改名不影响已有账单与分类。 */
    suspend fun updateProfile(id: String, name: String, iconKey: String) {
        val ledger = ledgerDao.findById(id) ?: throw TallyError.LedgerNotFound
        val trimmed = name.trim()
        if (trimmed.isEmpty()) throw TallyError.EmptyName
        ledgerDao.update(ledger.copy(name = trimmed, iconKey = iconKey, updatedAt = now(), version = ledger.version + 1))
    }

    suspend fun setDefault(id: String) {
        val ledger = ledgerDao.findById(id) ?: throw TallyError.LedgerNotFound
        if (!ledger.isDefault) ledgerDao.setDefault(id, now())
    }

    suspend fun setArchived(id: String, archived: Boolean) {
        val ledger = ledgerDao.findById(id) ?: throw TallyError.LedgerNotFound
        if (ledger.isDefault && archived) throw TallyError.DefaultLedgerProtected
        ledgerDao.setArchived(id, archived, now(), deletedAt = null)
    }

    suspend fun isDefault(id: String): Boolean = ledgerDao.findById(id)?.isDefault == true

    /**
     * 删除账本。有账单时必须显式选择迁移或连同删除；默认账本受保护。
     */
    suspend fun delete(id: String, disposition: DeleteDisposition): Int {
        val ledger = ledgerDao.findById(id) ?: throw TallyError.LedgerNotFound
        if (ledger.isDefault) throw TallyError.DefaultLedgerProtected
        val billCount = billDao.countByLedger(id)
        val affected: Int
        when (disposition) {
            is DeleteDisposition.Migrate -> {
                if (disposition.targetId == id) throw TallyError.MigrationTargetIsSelf
                val target = ledgerDao.findById(disposition.targetId) ?: throw TallyError.LedgerNotFound
                affected = billDao.migrateToLedger(id, target.id, now())
            }

            DeleteDisposition.DeleteBills -> {
                affected = billDao.softDeleteByLedger(id, now())
            }

            DeleteDisposition.RequireChoice -> {
                if (billCount > 0) throw TallyError.MigrationTargetRequired
                affected = 0
            }
        }
        ledgerDao.setArchived(id, archived = ledger.isArchived, now = now(), deletedAt = now())
        return affected
    }

    private fun emptyStat(ledgerId: String) = LedgerStat(
        ledgerId = ledgerId,
        billCount = 0,
        lastOccurredAt = null,
        expenseMinor = 0,
        incomeMinor = 0,
        excludedMinor = 0,
    )
}

sealed interface DeleteDisposition {
    /** 尚未确认处置方式：有账单时直接阻断。 */
    data object RequireChoice : DeleteDisposition

    data class Migrate(val targetId: String) : DeleteDisposition

    data object DeleteBills : DeleteDisposition
}
