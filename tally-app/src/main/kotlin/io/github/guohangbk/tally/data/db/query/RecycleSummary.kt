package io.github.guohangbk.tally.data.db.query

import androidx.room.ColumnInfo

/** 回收站顶部那一行的汇总：笔数与合计，由 SQL 一次算出。 */
data class RecycleSummary(
    @ColumnInfo("bills") val bills: Int,
    @ColumnInfo("minor") val minor: Long,
)
