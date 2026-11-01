package io.github.guohangbk.tally.data.repository

import io.github.guohangbk.tally.data.db.dao.CategoryDao
import io.github.guohangbk.tally.data.db.dao.LedgerDao
import io.github.guohangbk.tally.data.db.entity.CategoryEntity
import io.github.guohangbk.tally.data.db.entity.LedgerEntity
import io.github.guohangbk.tally.domain.model.BillType
import io.github.guohangbk.tally.domain.model.TallyError

/**
 * 周期与分期共用的落库前校验：账本必须存在且未归档，分类必须同收支类型、同层级。
 */
internal suspend fun requireTargets(
    ledgerDao: LedgerDao,
    categoryDao: CategoryDao,
    ledgerId: String,
    categoryL1Id: String?,
    categoryL2Id: String?,
    type: BillType,
): Pair<LedgerEntity, CategoryEntity?> {
    val ledger = ledgerDao.findById(ledgerId) ?: throw TallyError.LedgerNotFound
    if (ledger.isArchived) throw TallyError.LedgerNotFound
    if (categoryL1Id == null) return ledger to null

    val l1 = categoryDao.findById(categoryL1Id) ?: throw TallyError.CategoryNotFound
    if (l1.type != type.name) throw TallyError.CategoryTypeMismatch
    if (l1.parentId != null) throw TallyError.CategoryTargetInvalid
    if (l1.isArchived) throw TallyError.CategoryNotFound

    if (categoryL2Id == null) return ledger to null
    val l2 = categoryDao.findById(categoryL2Id) ?: throw TallyError.CategoryNotFound
    if (l2.parentId != l1.id) throw TallyError.CategoryTargetInvalid
    if (l2.type != type.name) throw TallyError.CategoryTypeMismatch
    if (l2.isArchived) throw TallyError.CategoryNotFound
    return ledger to l2
}

/** 分类路径统一拼装，列表页与账单行使用同一口径。 */
internal fun categoryPath(l1: CategoryEntity?, l2: CategoryEntity?): String =
    listOfNotNull(l1?.name, l2?.name).joinToString(" · ").ifBlank { "未分类" }
