package com.todoapp.viewmodel

import com.todoapp.data.TodoRepository
import com.todoapp.model.TodoItem
import com.todoapp.util.Dates
import com.todoapp.util.PlanGrouper
import com.todoapp.util.PlanRange
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.datetime.LocalDate

/** 计划页状态：按选定时间范围分组的未完成待办。 */
data class PlanUiState(
    val range: PlanRange = PlanRange.TODAY,
    val today: LocalDate = Dates.today(),
    /** 已逾期（今天之前到期且未完成），始终置顶展示 */
    val overdue: List<TodoItem> = emptyList(),
    /** 范围内按日期分组的待办，日期升序 */
    val groups: List<Pair<LocalDate, List<TodoItem>>> = emptyList(),
    /** listId → 清单名，用于角标 */
    val listNames: Map<String, String> = emptyMap(),
) {
    val isEmpty: Boolean get() = overdue.isEmpty() && groups.all { it.second.isEmpty() }
}

class PlanViewModel(
    private val repository: TodoRepository,
    appScope: CoroutineScope,
    /** 「今天」可注入，便于测试固定日期；生产用系统当前日。 */
    private val todayProvider: () -> LocalDate = { Dates.today() },
) {

    private val _range = MutableStateFlow(PlanRange.TODAY)

    val uiState: StateFlow<PlanUiState> = combine(
        repository.items,
        repository.lists,
        _range,
    ) { items, lists, range ->
        val today = todayProvider()
        val grouped = PlanGrouper.group(items, range, today)
        PlanUiState(
            range = range,
            today = today,
            overdue = grouped.overdue,
            groups = grouped.byDay,
            listNames = lists.associate { it.id to it.name },
        )
    }.stateIn(appScope, SharingStarted.Eagerly, PlanUiState())

    fun setRange(range: PlanRange) {
        _range.value = range
    }

    /** 计划页只展示未完成任务，勾选完成后条目即从视图消失。 */
    fun toggleDone(item: TodoItem) {
        repository.setDone(item.id, !item.done)
    }
}
