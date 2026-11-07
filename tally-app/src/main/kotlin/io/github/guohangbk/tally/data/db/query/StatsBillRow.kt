package io.github.guohangbk.tally.data.db.query

import androidx.room.ColumnInfo

/**
 * 聚合专用的瘦行：只带统计口径会读到的字段与分类名。
 *
 * 与 [BillListItemRow] 的区别是没有 `b.*`、没有附件/已退子查询、没有账本与分期 JOIN，
 * 也不排序——概览只要合计与排行，把这些一并取回来纯属白搬。
 */
data class StatsBillRow(
    val type: String,
    @ColumnInfo("amount_minor") val amountMinor: Long,
    @ColumnInfo("category_l1_id") val categoryL1Id: String?,
    @ColumnInfo("category_l2_id") val categoryL2Id: String?,
    @ColumnInfo("is_refund") val isRefund: Boolean,
    @ColumnInfo("refund_of_bill_id") val refundOfBillId: String?,
    val reimbursed: String,
    @ColumnInfo("excluded_from_stats") val excludedFromStats: Boolean,
    @ColumnInfo("category_l1_name") val categoryL1Name: String?,
    @ColumnInfo("category_l1_icon") val categoryL1Icon: String?,
    @ColumnInfo("category_l2_name") val categoryL2Name: String?,
    @ColumnInfo("category_l2_icon") val categoryL2Icon: String?,
)
