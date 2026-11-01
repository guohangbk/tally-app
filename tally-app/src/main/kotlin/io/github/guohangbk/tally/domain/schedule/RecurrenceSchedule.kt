package io.github.guohangbk.tally.domain.schedule

import io.github.guohangbk.tally.domain.model.DueOccurrence
import io.github.guohangbk.tally.domain.model.RecurrenceSpec
import io.github.guohangbk.tally.domain.model.RecurrenceUnit
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/**
 * 周期规则的计算策略。每个单位一个实现，新增规则（例如每两周）只需要加一个策略，
 * 执行游标与补执行逻辑不感知具体规则。
 */
interface RecurrenceStrategy {
    /** 从 [start] 起，第一个符合规则的执行日。 */
    fun firstOnOrAfter(spec: RecurrenceSpec, start: LocalDate): LocalDate

    /** [previous] 之后的下一个执行日。 */
    fun next(spec: RecurrenceSpec, previous: LocalDate): LocalDate
}

object RecurrenceStrategies {
    private val daily = object : RecurrenceStrategy {
        override fun firstOnOrAfter(spec: RecurrenceSpec, start: LocalDate) = start
        override fun next(spec: RecurrenceSpec, previous: LocalDate) = previous.plusDays(1)
    }

    private val weekly = object : RecurrenceStrategy {
        override fun firstOnOrAfter(spec: RecurrenceSpec, start: LocalDate): LocalDate {
            val target = DayOfWeek.of((spec.dayOfWeek ?: start.dayOfWeek.value).coerceIn(1, 7))
            return start.with(TemporalAdjusters.nextOrSame(target))
        }

        override fun next(spec: RecurrenceSpec, previous: LocalDate) = previous.plusWeeks(1)
    }

    private val monthly = object : RecurrenceStrategy {
        override fun firstOnOrAfter(spec: RecurrenceSpec, start: LocalDate): LocalDate {
            val day = spec.dayOfMonth ?: start.dayOfMonth
            val candidate = clampToMonth(start.year, start.monthValue, day)
            if (candidate >= start) return candidate
            val nextMonth = YearMonth.from(start).plusMonths(1)
            return nextMonth.atDay(day.coerceIn(1, nextMonth.lengthOfMonth()))
        }

        override fun next(spec: RecurrenceSpec, previous: LocalDate): LocalDate {
            val day = spec.dayOfMonth ?: previous.dayOfMonth
            val nextMonth = YearMonth.from(previous).plusMonths(1)
            return nextMonth.atDay(day.coerceIn(1, nextMonth.lengthOfMonth()))
        }
    }

    private val lastDayOfMonth = object : RecurrenceStrategy {
        override fun firstOnOrAfter(spec: RecurrenceSpec, start: LocalDate): LocalDate {
            val endOfMonth = start.withDayOfMonth(start.lengthOfMonth())
            return if (endOfMonth < start) start.plusMonths(1).withDayOfMonth(start.plusMonths(1).lengthOfMonth())
            else endOfMonth
        }

        override fun next(spec: RecurrenceSpec, previous: LocalDate): LocalDate {
            val nextMonth = previous.plusMonths(1)
            return nextMonth.withDayOfMonth(nextMonth.lengthOfMonth())
        }
    }

    private val yearly = object : RecurrenceStrategy {
        override fun firstOnOrAfter(spec: RecurrenceSpec, start: LocalDate): LocalDate {
            val month = (spec.monthOfYear ?: start.monthValue).coerceIn(1, 12)
            val day = spec.dayOfMonth ?: start.dayOfMonth
            val candidate = clampToMonth(start.year, month, day)
            return if (candidate < start) clampToMonth(start.year + 1, month, day) else candidate
        }

        override fun next(spec: RecurrenceSpec, previous: LocalDate): LocalDate {
            val month = (spec.monthOfYear ?: previous.monthValue).coerceIn(1, 12)
            val day = spec.dayOfMonth ?: previous.dayOfMonth
            return clampToMonth(previous.year + 1, month, day)
        }
    }

    fun of(unit: RecurrenceUnit): RecurrenceStrategy = when (unit) {
        RecurrenceUnit.DAILY -> daily
        RecurrenceUnit.WEEKLY -> weekly
        RecurrenceUnit.MONTHLY -> monthly
        RecurrenceUnit.LAST_DAY_OF_MONTH -> lastDayOfMonth
        RecurrenceUnit.YEARLY -> yearly
    }

    /** 每月 31 日在 2 月落到当月最后一天；年份、月份由调用方保证合法。 */
    private fun clampToMonth(year: Int, month: Int, day: Int): LocalDate {
        val yearMonth = YearMonth.of(year, month)
        return yearMonth.atDay(day.coerceIn(1, yearMonth.lengthOfMonth()))
    }
}

/** 把策略与执行游标组合成「应生成但还没生成」的日期列表。 */
object RecurrenceSchedule {
    /** 防御性上限：即便规则被写坏，也不会在启动时生成无边界账单。 */
    private const val MAX_BATCH = 500

    fun firstOnOrAfter(spec: RecurrenceSpec, start: LocalDate): LocalDate =
        RecurrenceStrategies.of(spec.unit).firstOnOrAfter(spec, start)

    fun next(spec: RecurrenceSpec, previous: LocalDate): LocalDate =
        RecurrenceStrategies.of(spec.unit).next(spec, previous)

    /**
     * 表单里的「未来 N 期」预览：只算日期，不写库。
     * 截止日期与期数上限会截断列表，让用户在启用规则前就看到最后一期落在哪。
     */
    fun previewOccurrences(
        spec: RecurrenceSpec,
        startAt: Long,
        endAt: Long?,
        maxPeriods: Int?,
        count: Int,
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<Long> {
        val endDate = endAt?.let { java.time.Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }
        var cursor = firstOnOrAfter(spec, java.time.Instant.ofEpochMilli(startAt).atZone(zone).toLocalDate())
        val result = mutableListOf<Long>()
        while (result.size < count) {
            if (endDate != null && cursor.isAfter(endDate)) break
            result += cursor.atStartOfDay(zone).toInstant().toEpochMilli()
            if (maxPeriods != null && result.size >= maxPeriods) break
            cursor = next(spec, cursor)
        }
        return result
    }

    /**
     * @param nextDueAt 已存储的下一次应执行日（含当天）
     * @param executedPeriods 已生成期数，用于幂等期序与次数上限
     * @param maxPeriods 次数上限，null 表示不限
     * @param endAt 截止日期，null 表示不按日期结束
     */
    fun dueOccurrences(
        spec: RecurrenceSpec,
        nextDueAt: Long,
        executedPeriods: Int,
        maxPeriods: Int?,
        endAt: Long?,
        today: LocalDate,
        zone: ZoneId,
    ): List<DueOccurrence> {
        val endDate = endAt?.let { java.time.Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }
        val remaining = maxPeriods?.minus(executedPeriods)?.coerceAtLeast(0)
        if (remaining == 0) return emptyList()

        val result = mutableListOf<DueOccurrence>()
        var cursor = java.time.Instant.ofEpochMilli(nextDueAt).atZone(zone).toLocalDate()
        var index = executedPeriods + 1
        while (!cursor.isAfter(today) && result.size < MAX_BATCH) {
            if (endDate != null && cursor.isAfter(endDate)) break
            result += DueOccurrence(periodIndex = index, dueAt = cursor.atStartOfDay(zone).toInstant().toEpochMilli())
            index++
            if (remaining != null && result.size >= remaining) break
            cursor = next(spec, cursor)
        }
        return result
    }
}
