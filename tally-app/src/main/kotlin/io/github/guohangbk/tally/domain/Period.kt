package io.github.guohangbk.tally.domain

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/** 左闭右开的周期段。全站只有 [Period] 能生产边界，页面与仓库都不许自己拼。 */
data class PeriodRange(
    val startAt: Long,
    val endAtExclusive: Long,
) {
    fun lengthDays(zone: ZoneId = ZoneId.systemDefault()): Int =
        ChronoUnit.DAYS.between(Period.toDate(startAt, zone), Period.toDate(endAtExclusive, zone)).toInt()

    operator fun contains(epochMillis: Long): Boolean =
        epochMillis >= startAt && epochMillis < endAtExclusive
}

/**
 * 月起止日的唯一出口（R-06）。首页汇总、统计月维度、预算周期与 AI 报告都调这里，
 * 任何页面自己 `withDayOfMonth(1)` 都会在改了起始日之后与别处对不上。
 *
 * 起始日取 1–28：短月不需要「月末对齐」这条额外规则，2 月也永远有 28 号。
 */
object Period {
    const val DEFAULT_START_DAY = 1
    const val MIN_START_DAY = 1
    const val MAX_START_DAY = 28

    private val ISO_DATE = DateTimeFormatter.ISO_LOCAL_DATE

    fun normalizeStartDay(day: Int): Int = day.coerceIn(MIN_START_DAY, MAX_START_DAY)

    fun monthRange(
        anchorMillis: Long,
        startDay: Int = DEFAULT_START_DAY,
        zone: ZoneId = ZoneId.systemDefault(),
    ): PeriodRange {
        val day = normalizeStartDay(startDay)
        val start = monthStartOnOrBefore(toDate(anchorMillis, zone), day)
        return PeriodRange(
            startAt = start.atStartOfDay(zone).toInstant().toEpochMilli(),
            endAtExclusive = start.plusMonths(1).atStartOfDay(zone).toInstant().toEpochMilli(),
        )
    }

    /** 年月选择器选「哪个月的周期」：起始日一定落在所选月份里。 */
    fun monthStart(
        year: Int,
        month: Int,
        startDay: Int = DEFAULT_START_DAY,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Long = LocalDate.of(year, month, normalizeStartDay(startDay))
        .atStartOfDay(zone).toInstant().toEpochMilli()

    fun yearRange(
        anchorMillis: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): PeriodRange {
        val start = toDate(anchorMillis, zone).withDayOfMonth(1).withMonth(1)
        return PeriodRange(
            startAt = start.atStartOfDay(zone).toInstant().toEpochMilli(),
            endAtExclusive = start.plusYears(1).atStartOfDay(zone).toInstant().toEpochMilli(),
        )
    }

    fun dayRange(
        startMillis: Long,
        endExclusiveMillis: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): PeriodRange = PeriodRange(
        startAt = toDate(startMillis, zone).atStartOfDay(zone).toInstant().toEpochMilli(),
        endAtExclusive = toDate(endExclusiveMillis, zone).atStartOfDay(zone).toInstant().toEpochMilli(),
    )

    /** 周期整体前后挪：月维度按起始日滚动，年维度按年，区间按天数。 */
    fun shift(epochMillis: Long, delta: Long, zone: ZoneId = ZoneId.systemDefault()): Long =
        toDate(epochMillis, zone).plusMonths(delta)
            .atStartOfDay(zone).toInstant().toEpochMilli()

    fun shiftDays(epochMillis: Long, delta: Long, zone: ZoneId = ZoneId.systemDefault()): Long =
        toDate(epochMillis, zone).plusDays(delta)
            .atStartOfDay(zone).toInstant().toEpochMilli()

    fun daysBetween(startMillis: Long, endMillis: Long, zone: ZoneId = ZoneId.systemDefault()): Int =
        ChronoUnit.DAYS.between(toDate(startMillis, zone), toDate(endMillis, zone)).toInt()

    /** 顶栏那一行：1 号起仍是「2026年10月」，改了起始日就写「10/15–11/14」。 */
    fun label(
        range: PeriodRange,
        startDay: Int = DEFAULT_START_DAY,
        zone: ZoneId = ZoneId.systemDefault(),
    ): String {
        val start = toDate(range.startAt, zone)
        val end = toDate(range.endAtExclusive, zone).minusDays(1)
        if (normalizeStartDay(startDay) == DEFAULT_START_DAY) {
            return "${start.year}年${start.monthValue}月"
        }
        return if (start.year == end.year) {
            "${start.monthValue}/${start.dayOfMonth}–${end.monthValue}/${end.dayOfMonth}"
        } else {
            "${start.year}/${start.monthValue}/${start.dayOfMonth}–${end.year}/${end.monthValue}/${end.dayOfMonth}"
        }
    }

    /** 区间统计的标题：「9/12–10/11」，跨年带上年份。 */
    fun rangeLabel(
        startMillis: Long,
        endExclusiveMillis: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): String {
        val start = toDate(startMillis, zone)
        val end = toDate(endExclusiveMillis, zone).minusDays(1)
        return if (start.year == end.year) {
            "${start.monthValue}/${start.dayOfMonth}–${end.monthValue}/${end.dayOfMonth}"
        } else {
            "${start.year}/${start.monthValue}/${start.dayOfMonth}–${end.year}/${end.monthValue}/${end.dayOfMonth}"
        }
    }

    /**
     * AI 报告的周期键：两端都是实际取数的日子，`2026-10-01_2026-10-20`。
     *
     * 右端不越过今天——本月只过了 20 天就写到 10-20，整月与历史年自然到期末；
     * 三种粒度同一个形状，键里带足年份，2025 与 2026 的同一段区间各存各的缓存。
     */
    fun key(
        range: PeriodRange,
        zone: ZoneId = ZoneId.systemDefault(),
        today: LocalDate = LocalDate.now(zone),
    ): String {
        val start = toDate(range.startAt, zone)
        val end = toDate(range.endAtExclusive, zone).minusDays(1).coerceAtLeast(start)
        val clipped = minOf(end, maxOf(today, start))
        return "${ISO_DATE.format(start)}_${ISO_DATE.format(clipped)}"
    }

    fun toDate(epochMillis: Long, zone: ZoneId = ZoneId.systemDefault()): LocalDate =
        Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate()

    private fun monthStartOnOrBefore(date: LocalDate, day: Int): LocalDate =
        if (date.dayOfMonth >= day) date.withDayOfMonth(day)
        else date.minusMonths(1).withDayOfMonth(day)
}
