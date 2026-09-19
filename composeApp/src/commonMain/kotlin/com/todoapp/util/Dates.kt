package com.todoapp.util

import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.Instant

/** 日期与「计划」视图范围工具。dueAt 一律为当地时区某天零点的毫秒时间戳。 */
object Dates {

    fun timeZone(): TimeZone = TimeZone.currentSystemDefault()

    fun today(tz: TimeZone = timeZone()): LocalDate =
        nowMillis().let { Instant.fromEpochMilliseconds(it).toLocalDateTime(tz).date }

    fun nowMillis(): Long = Clock.System.now().toEpochMilliseconds()

    fun startOfDay(date: LocalDate, tz: TimeZone = timeZone()): Long =
        date.atStartOfDayIn(tz).toEpochMilliseconds()

    fun toLocalDate(epochMillis: Long, tz: TimeZone = timeZone()): LocalDate =
        Instant.fromEpochMilliseconds(epochMillis).toLocalDateTime(tz).date

    /** 本周的周一（MONDAY..SUNDAY 的 ordinal 即周一=0） */
    fun startOfWeek(today: LocalDate): LocalDate =
        today.minus(DatePeriod(days = today.dayOfWeek.ordinal))

    /** 计划视图的筛选范围，返回 [起始日, 结束日]（含两端）。 */
    fun rangeFor(range: PlanRange, today: LocalDate): Pair<LocalDate, LocalDate> = when (range) {
        PlanRange.TODAY -> today to today
        PlanRange.WEEK -> startOfWeek(today) to startOfWeek(today).plus(DatePeriod(days = 6))
        PlanRange.TWO_WEEKS -> today to today.plus(DatePeriod(days = 13))
        PlanRange.MONTH -> today to today.plus(DatePeriod(months = 1))
    }

    private val weekdayNames = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

    fun weekdayName(date: LocalDate): String = weekdayNames[date.dayOfWeek.ordinal]

    /** 紧凑日期文案：M月d日 */
    fun shortDate(date: LocalDate): String = "${date.month.ordinal + 1}月${date.day}日"

    /** 相对今天的友好文案：今天 / 明天 / 昨天 / M月d日 */
    fun relativeDate(date: LocalDate, today: LocalDate): String = when (date) {
        today -> "今天"
        today.plus(DatePeriod(days = 1)) -> "明天"
        today.minus(DatePeriod(days = 1)) -> "昨天"
        else -> shortDate(date)
    }

    /** HH:mm */
    fun formatTime(epochMillis: Long, tz: TimeZone = timeZone()): String {
        val time = Instant.fromEpochMilliseconds(epochMillis).toLocalDateTime(tz).time
        return "${time.hour.toString().padStart(2, '0')}:${time.minute.toString().padStart(2, '0')}"
    }

    /** 同步时间文案：今天 HH:mm 或 M月d日 HH:mm */
    fun formatSyncTime(epochMillis: Long, tz: TimeZone = timeZone()): String {
        val date = toLocalDate(epochMillis, tz)
        val prefix = relativeDate(date, today(tz))
        return "$prefix ${formatTime(epochMillis, tz)}"
    }
}

enum class PlanRange(val label: String) {
    TODAY("今日"),
    WEEK("本周"),
    TWO_WEEKS("两周内"),
    MONTH("一个月内"),
}
