package com.todoapp.util

import com.todoapp.model.TodoItem
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus

/** 计划页分组结果。 */
data class PlanGroups(
    /** 今天之前到期且未完成，始终置顶 */
    val overdue: List<TodoItem>,
    /** 范围内按日期升序分组，已排除逾期条目 */
    val byDay: List<Pair<LocalDate, List<TodoItem>>>,
)

/**
 * 计划页的时间范围筛选与分组（纯函数，便于测试）。
 *
 * 逾期条目只出现在 [PlanGroups.overdue] 中：即便它落在所选范围里（例如「本周」
 * 包含本周已过去的几天），也不会重复出现在日期分组中。
 */
object PlanGrouper {

    fun group(
        items: List<TodoItem>,
        range: PlanRange,
        today: LocalDate,
        tz: TimeZone = Dates.timeZone(),
    ): PlanGroups {
        val (startDay, endDay) = Dates.rangeFor(range, today)
        val startMs = Dates.startOfDay(startDay, tz)
        val endMsExclusive = Dates.startOfDay(endDay.plus(DatePeriod(days = 1)), tz)
        val startOfToday = Dates.startOfDay(today, tz)

        val uncompleted = items.asSequence()
            .filter { it.deletedAt == null && !it.done && it.dueAt != null }

        val overdue = uncompleted
            .filter { (it.dueAt ?: Long.MIN_VALUE) < startOfToday }
            .sortedBy { it.dueAt ?: 0L }
            .toList()

        // 日期分组从「今天」开始，逾期条目已由 overdue 分组负责，避免重复展示
        val groupStartMs = maxOf(startMs, startOfToday)
        val byDay = uncompleted
            .filter {
                val due = it.dueAt ?: return@filter false
                due >= groupStartMs && due < endMsExclusive
            }
            .groupBy { Dates.toLocalDate(it.dueAt ?: 0L, tz) }
            .entries
            .sortedBy { it.key }
            .map { entry ->
                val sorted = entry.value.sortedWith(
                    compareBy<TodoItem> { it.dueAt ?: Long.MAX_VALUE }.thenByDescending { it.createdAt },
                )
                entry.key to sorted
            }

        return PlanGroups(overdue = overdue, byDay = byDay)
    }
}
