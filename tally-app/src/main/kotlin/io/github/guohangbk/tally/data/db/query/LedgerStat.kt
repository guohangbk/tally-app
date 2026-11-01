package io.github.guohangbk.tally.data.db.query

/** 账本维度聚合，供「我的账本」列表与统计口径使用。金额单位为分。 */
data class LedgerStat(
    val ledgerId: String,
    val billCount: Int,
    val lastOccurredAt: Long?,
    val expenseMinor: Long,
    val incomeMinor: Long,
    /** 报销记录等「不计入收支」金额，独立展示。 */
    val excludedMinor: Long,
)
