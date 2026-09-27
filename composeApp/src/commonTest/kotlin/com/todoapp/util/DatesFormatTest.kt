package com.todoapp.util

import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlin.test.Test
import kotlin.test.assertEquals

/** Dates 的格式化文案（范围计算在 PlanRangeTest，分组在 PlanGrouperTest）。 */
class DatesFormatTest {

    private val utc = TimeZone.UTC

    @Test
    fun `相对日期文案`() {
        val today = LocalDate(2026, 9, 27)
        assertEquals("今天", Dates.relativeDate(today, today))
        assertEquals("明天", Dates.relativeDate(LocalDate(2026, 9, 28), today))
        assertEquals("昨天", Dates.relativeDate(LocalDate(2026, 9, 26), today))
        assertEquals("10月1日", Dates.relativeDate(LocalDate(2026, 10, 1), today))
    }

    @Test
    fun `星期文案从周一开始`() {
        assertEquals("周一", Dates.weekdayName(LocalDate(2026, 9, 21)))
        assertEquals("周三", Dates.weekdayName(LocalDate(2026, 9, 23)))
        assertEquals("周日", Dates.weekdayName(LocalDate(2026, 9, 27)))
    }

    @Test
    fun `紧凑日期文案`() {
        assertEquals("9月27日", Dates.shortDate(LocalDate(2026, 9, 27)))
        assertEquals("1月5日", Dates.shortDate(LocalDate(2026, 1, 5)))
    }

    @Test
    fun `时间格式补零`() {
        val millis = Dates.startOfDay(LocalDate(2026, 9, 27), utc) +
            8 * 3_600_000L + 5 * 60_000L
        assertEquals("08:05", Dates.formatTime(millis, utc))
    }

    @Test
    fun `同步时间文案由相对日期加时刻组成`() {
        val today = Dates.today(utc)
        val at = Dates.startOfDay(today, utc) + 9 * 3_600_000L + 30 * 60_000L
        val expected = "${Dates.relativeDate(Dates.toLocalDate(at, utc), today)} 09:30"
        assertEquals(expected, Dates.formatSyncTime(at, utc))
    }

    @Test
    fun `同步时间文案非今天显示月日`() {
        val past = Dates.today(utc).minus(DatePeriod(days = 3650))
        val at = Dates.startOfDay(past, utc) + 8 * 3_600_000L
        val expected = "${Dates.shortDate(past)} 08:00"
        assertEquals(expected, Dates.formatSyncTime(at, utc))
    }
}
