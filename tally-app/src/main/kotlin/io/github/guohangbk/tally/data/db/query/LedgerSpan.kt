package io.github.guohangbk.tally.data.db.query

import androidx.room.ColumnInfo

/** 一本账的账目时间跨度：概览页那行「07-03 ～ 08-12 · 41 天」。空账本两端都是 null。 */
data class LedgerSpan(
    @ColumnInfo("start_at") val startAt: Long?,
    @ColumnInfo("end_at") val endAt: Long?,
)
