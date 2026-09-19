package com.todoapp.util

import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus
import kotlin.test.Test
import kotlin.test.assertEquals

class PlanRangeTest {

    // 2026-09-16 是周三
    private val wednesday = LocalDate(2026, 9, 16)

    @Test
    fun `今日范围就是当天`() {
        val (start, end) = Dates.rangeFor(PlanRange.TODAY, wednesday)
        assertEquals(wednesday, start)
        assertEquals(wednesday, end)
    }

    @Test
    fun `本周范围是周一到周日`() {
        val (start, end) = Dates.rangeFor(PlanRange.WEEK, wednesday)
        assertEquals(LocalDate(2026, 9, 14), start, "周三所在周的周一应是 9月14日")
        assertEquals(LocalDate(2026, 9, 20), end)
    }

    @Test
    fun `周一是本周起点`() {
        val monday = LocalDate(2026, 9, 14)
        val (start, end) = Dates.rangeFor(PlanRange.WEEK, monday)
        assertEquals(monday, start)
        assertEquals(LocalDate(2026, 9, 20), end)
    }

    @Test
    fun `周日是本周终点`() {
        val sunday = LocalDate(2026, 9, 20)
        val (start, _) = Dates.rangeFor(PlanRange.WEEK, sunday)
        assertEquals(LocalDate(2026, 9, 14), start)
    }

    @Test
    fun `两周内含今天共14天`() {
        val (start, end) = Dates.rangeFor(PlanRange.TWO_WEEKS, wednesday)
        assertEquals(wednesday, start)
        assertEquals(wednesday.plus(DatePeriod(days = 13)), end)
        assertEquals(LocalDate(2026, 9, 29), end)
    }

    @Test
    fun `一个月内按日历月推算`() {
        val (start, end) = Dates.rangeFor(PlanRange.MONTH, wednesday)
        assertEquals(wednesday, start)
        assertEquals(LocalDate(2026, 10, 16), end)
    }

    @Test
    fun `跨月与跨年推算`() {
        val jan31 = LocalDate(2026, 1, 31)
        val (start, end) = Dates.rangeFor(PlanRange.MONTH, jan31)
        assertEquals(jan31, start)
        assertEquals(LocalDate(2026, 2, 28), end, "1月31日 + 1 个月应为 2月28日（2026 非闰年）")
    }
}
