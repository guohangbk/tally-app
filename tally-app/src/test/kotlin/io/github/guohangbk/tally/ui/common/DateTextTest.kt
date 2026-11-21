package io.github.guohangbk.tally.ui.common

import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.test.assertEquals

class DateTextTest {

    @Test
    fun `只选日期时补上当前记录时刻`() {
        val zone = ZoneId.systemDefault()
        val selectedDay = LocalDate.of(2026, 11, 20)
        val now = LocalDate.of(2026, 11, 21).atTime(LocalTime.of(14, 30)).atZone(zone).toInstant().toEpochMilli()

        val recorded = dayWithCurrentClock(selectedDay.atStartOfDay(zone).toInstant().toEpochMilli(), now)
        val expected = selectedDay.atTime(LocalTime.of(14, 30)).atZone(zone).toInstant().toEpochMilli()

        assertEquals(expected, recorded)
    }
}
