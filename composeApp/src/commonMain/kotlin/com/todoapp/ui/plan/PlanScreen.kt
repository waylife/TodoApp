package com.todoapp.ui.plan

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.todoapp.sync.SyncStatus
import com.todoapp.ui.common.EmptyState
import com.todoapp.ui.common.SectionHeader
import com.todoapp.ui.common.SelectableChip
import com.todoapp.ui.common.SyncStatusRow
import com.todoapp.ui.common.TodoRow
import com.todoapp.util.Dates
import com.todoapp.util.PlanRange
import com.todoapp.viewmodel.EditSession
import com.todoapp.viewmodel.PlanViewModel

/**
 * 计划页：跨清单汇总未完成待办，按「今日 / 本周 / 两周内 / 一个月内」筛选，
 * 已逾期任务始终置顶。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlanScreen(
    viewModel: PlanViewModel,
    editSession: EditSession,
    syncStatus: SyncStatus,
    onSyncNow: () -> Unit,
) {
    val state by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("计划", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        SyncStatusRow(status = syncStatus, onSync = onSyncNow)
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(PlanRange.entries, key = { it.name }) { range ->
                    SelectableChip(
                        text = range.label,
                        selected = state.range == range,
                        onClick = { viewModel.setRange(range) },
                    )
                }
            }

            if (state.isEmpty) {
                EmptyState("该时间范围内没有待完成的任务")
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    if (state.overdue.isNotEmpty()) {
                        item(key = "overdue-header") {
                            SectionHeader(
                                "已逾期 ${state.overdue.size}",
                                color = androidx.compose.material3.MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                    items(state.overdue, key = { "overdue-" + it.id }) { item ->
                        TodoRow(
                            item = item,
                            listName = state.listNames[item.listId],
                            onToggle = { viewModel.toggleDone(item) },
                            onClick = { editSession.open(item.id) },
                        )
                    }
                    state.groups.forEach { (date, itemsInDay) ->
                        item(key = "day-" + date.toEpochDays()) {
                            SectionHeader(dayHeader(date, state.today))
                        }
                        items(itemsInDay, key = { it.id }) { item ->
                            TodoRow(
                                item = item,
                                listName = state.listNames[item.listId],
                                showDueDate = false,
                                onToggle = { viewModel.toggleDone(item) },
                                onClick = { editSession.open(item.id) },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 日期分组标题：今天（周日 · 9月20日）或 周二 · 9月22日 */
private fun dayHeader(date: kotlinx.datetime.LocalDate, today: kotlinx.datetime.LocalDate): String {
    val relative = Dates.relativeDate(date, today)
    val detail = "${Dates.weekdayName(date)} · ${Dates.shortDate(date)}"
    // 相对文案与具体日期重合时（如 9月22日）只显示一次
    return if (relative == Dates.shortDate(date)) detail else "$relative（$detail）"
}
