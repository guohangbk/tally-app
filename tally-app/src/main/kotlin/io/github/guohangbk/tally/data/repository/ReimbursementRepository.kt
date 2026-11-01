package io.github.guohangbk.tally.data.repository

import io.github.guohangbk.tally.data.db.dao.ReimbursementDao
import io.github.guohangbk.tally.data.db.query.BillListItemRow
import io.github.guohangbk.tally.domain.model.BillListItem
import io.github.guohangbk.tally.domain.model.ReimburseStatus
import io.github.guohangbk.tally.domain.model.ReimbursementSnapshot
import io.github.guohangbk.tally.domain.model.TallyError
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/**
 * 报销管理：普通支出的报销状态与钱迹「报销记录」严格分开展示。
 * 普通状态只改变管理视图，不改统计归属；「报销记录」在统计层已按 excluded_from_stats 排除。
 */
class ReimbursementRepository(
    private val dao: ReimbursementDao,
    private val now: () -> Long = System::currentTimeMillis,
) {
    fun observe(ledgerId: String?): Flow<ReimbursementSnapshot> = combine(
        dao.observeByReimburseStatus(ReimburseStatus.PENDING.name, ledgerId),
        dao.observeByReimburseStatus(ReimburseStatus.DONE.name, ledgerId),
        dao.observeReimbursementRecords(ledgerId),
    ) { pending, done, records ->
        ReimbursementSnapshot(
            pending = pending.map(::toItem),
            done = done.map(::toItem),
            records = records.map(::toItem),
        )
    }

    suspend fun setStatus(billId: String, status: ReimburseStatus) {
        val updated = dao.setReimbursed(billId, status.name, now())
        if (updated == 0) throw TallyError.ReimbursementNotAllowed
    }

    /** 一次确认一批报销，返回真正被改动的笔数（收入与被排除的账单不会被写）。 */
    suspend fun setStatusMany(billIds: List<String>, status: ReimburseStatus): Int =
        dao.setReimbursedByIds(billIds, status.name, now())

    private fun toItem(row: BillListItemRow) = BillListItem(
        bill = row.bill,
        ledgerName = row.ledgerName,
        categoryL1Name = row.categoryL1Name,
        categoryL2Name = row.categoryL2Name,
        categoryL1Icon = row.categoryL1Icon,
        categoryL2Icon = row.categoryL2Icon,
        installmentPeriodCount = row.installmentPeriodCount,
        attachmentCount = row.attachmentCount,
    )
}
