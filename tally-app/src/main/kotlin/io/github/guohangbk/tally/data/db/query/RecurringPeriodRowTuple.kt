package io.github.guohangbk.tally.data.db.query

import androidx.room.ColumnInfo

/**
 * 周期任务「明细」弹层的一行，由一条查询直接取回。
 *
 * 包含回收站里的期次（`deleted_at` 非空）：删掉的那一期也占着排期里的期号，
 * 明细要把缺号补成灰行，否则「第 2 期」会显得凭空跳号。
 */
data class RecurringPeriodRowTuple(
    @ColumnInfo(name = "bill_id") val billId: String,
    @ColumnInfo(name = "period_index") val periodIndex: Int?,
    @ColumnInfo(name = "occurred_at") val occurredAt: Long,
    @ColumnInfo(name = "amount_minor") val amountMinor: Long,
    @ColumnInfo(name = "type") val type: String,
    @ColumnInfo(name = "deleted_at") val deletedAt: Long?,
    @ColumnInfo(name = "note") val note: String?,
)
