package io.github.guohangbk.tally.ui.common

import io.github.guohangbk.tally.domain.model.TimePrecision
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val zone: ZoneId get() = ZoneId.systemDefault()
private val monthFormatter = DateTimeFormatter.ofPattern("yyyy年M月", Locale.CHINA)
private val dayFormatter = DateTimeFormatter.ofPattern("M月d日 E", Locale.CHINA)
private val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.CHINA)
private val dateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.CHINA)
private val clockFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.CHINA)

fun monthStartOf(epochMillis: Long = System.currentTimeMillis()): Long =
    Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate().withDayOfMonth(1)
        .atStartOfDay(zone).toInstant().toEpochMilli()

fun shiftMonthStart(monthStart: Long, delta: Long): Long =
    Instant.ofEpochMilli(monthStart).atZone(zone).toLocalDate().plusMonths(delta)
        .withDayOfMonth(1).atStartOfDay(zone).toInstant().toEpochMilli()

fun startOfDay(epochMillis: Long): Long =
    Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate()
        .atStartOfDay(zone).toInstant().toEpochMilli()

fun formatMonth(epochMillis: Long): String =
    Instant.ofEpochMilli(epochMillis).atZone(zone).format(monthFormatter)

fun formatDay(epochMillis: Long): String =
    Instant.ofEpochMilli(epochMillis).atZone(zone).format(dayFormatter)

fun formatDate(epochMillis: Long): String =
    Instant.ofEpochMilli(epochMillis).atZone(zone).format(dateFormatter)

fun formatDateTime(epochMillis: Long): String =
    Instant.ofEpochMilli(epochMillis).atZone(zone).format(dateTimeFormatter)

/** 列表行上的时刻：只有精确到分的账单才显示，日粒度的行不写「00:00」。 */
fun formatClock(epochMillis: Long): String =
    Instant.ofEpochMilli(epochMillis).atZone(zone).format(clockFormatter)

/**
 * 精度是录入粒度：选「日」时把时刻归零到本地 00:00，
 * 避免同一天里同时存在 0 点和 12 点两种键；选「时刻」时原样返回。
 */
fun alignToPrecision(epochMillis: Long, precision: TimePrecision): Long =
    if (precision == TimePrecision.DATE) startOfDay(epochMillis) else epochMillis

fun localDateToMillis(date: LocalDate): Long = date.atStartOfDay(zone).toInstant().toEpochMilli()

fun millisToLocalDate(epochMillis: Long): LocalDate =
    Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate()
