package com.todoapp.util

import com.todoapp.model.TodoItem
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlanGrouperTest {

    private val tz = TimeZone.UTC
    private val today = LocalDate(2026, 9, 20) // 周日

    private fun item(
        id: String,
        daysFromToday: Long?,
        done: Boolean = false,
        deletedAt: Long? = null,
        createdAt: Long = 0,
    ): TodoItem = TodoItem(
        id = id,
        listId = "l1",
        title = "任务 $id",
        done = done,
        dueAt = daysFromToday?.let {
            Dates.startOfDay(today.plus(DatePeriod(days = it.toInt())), tz)
        },
        createdAt = createdAt,
        updatedAt = 0,
        deletedAt = deletedAt,
    )

    @Test
    fun `无截止日期的条目不进入计划视图`() {
        val result = PlanGrouper.group(listOf(item("a", null)), PlanRange.TODAY, today, tz)
        assertTrue(result.overdue.isEmpty())
        assertTrue(result.byDay.isEmpty())
    }

    @Test
    fun `已完成与已删除的条目不进入计划视图`() {
        val items = listOf(
            item("a", 0, done = true),
            item("b", 0, deletedAt = 123),
        )
        val result = PlanGrouper.group(items, PlanRange.TODAY, today, tz)
        assertTrue(result.overdue.isEmpty())
        assertTrue(result.byDay.isEmpty(), "已完成/已删除不应出现在计划页")
    }

    @Test
    fun `逾期条目只出现在逾期分组`() {
        val items = listOf(item("late", -2), item("now", 0))
        val result = PlanGrouper.group(items, PlanRange.TODAY, today, tz)
        assertEquals(listOf("late"), result.overdue.map { it.id })
        assertEquals(listOf("now"), result.byDay.flatMap { it.second }.map { it.id })
    }

    @Test
    fun `本周范围内逾期条目不会重复出现在日期分组`() {
        // 回归测试：逾期日（周五 9月18日）落在「本周」范围内时，不得同时出现在两个分组
        val items = listOf(item("late", -2), item("today", 0))
        val result = PlanGrouper.group(items, PlanRange.WEEK, today, tz)

        assertEquals(listOf("late"), result.overdue.map { it.id })
        val groupedIds = result.byDay.flatMap { it.second }.map { it.id }
        assertEquals(listOf("today"), groupedIds, "逾期条目不应再次出现在日期分组中")
    }

    @Test
    fun `本周范围包含本周一至周日且不含今天之后的下一周`() {
        val items = listOf(
            item("mon", -6),   // 周一 9月14日
            item("wed", -4),   // 周三 9月16日
            item("nextMon", 1), // 周一 9月21日，属下一周
        )
        val result = PlanGrouper.group(items, PlanRange.WEEK, today, tz)
        // 周三、周一都在今天之前 → 归入逾期；下一周不在范围内
        assertEquals(listOf("mon", "wed"), result.overdue.map { it.id })
        assertTrue(result.byDay.isEmpty(), "本周范围内今天之后没有任务")
    }

    @Test
    fun `两周内包含今天起第 13 天`() {
        val items = listOf(item("d13", 13), item("d14", 14))
        val result = PlanGrouper.group(items, PlanRange.TWO_WEEKS, today, tz)
        val ids = result.byDay.flatMap { it.second }.map { it.id }
        assertEquals(listOf("d13"), ids, "第 14 天已超出两周范围")
    }

    @Test
    fun `一个月内按日历月计算`() {
        val items = listOf(item("d30", 30), item("d31", 31))
        val result = PlanGrouper.group(items, PlanRange.MONTH, today, tz)
        val ids = result.byDay.flatMap { it.second }.map { it.id }
        // 9月20日 + 1 个月 = 10月20日，落在区间内的任务
        assertEquals(listOf("d30"), ids)
    }

    @Test
    fun `日期分组按日期升序且组内按创建时间倒序`() {
        val items = listOf(
            item("late2", 2, createdAt = 10),
            item("late0", 0, createdAt = 20),
            item("soon1", 1, createdAt = 30),
            item("soon2", 1, createdAt = 40),
        )
        val result = PlanGrouper.group(items, PlanRange.MONTH, today, tz)
        val dates = result.byDay.map { it.first }
        assertEquals(dates.sorted(), dates, "分组应按日期升序")
        val day1 = result.byDay.first { it.first == today.plus(DatePeriod(days = 1)) }
        assertEquals(listOf("soon2", "soon1"), day1.second.map { it.id }, "同日任务按创建时间倒序")
    }
}
