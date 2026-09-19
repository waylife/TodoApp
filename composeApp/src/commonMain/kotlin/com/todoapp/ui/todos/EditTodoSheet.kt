package com.todoapp.ui.todos

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.todoapp.model.TodoItem
import com.todoapp.model.TodoList
import com.todoapp.ui.common.SelectableChip
import com.todoapp.util.Dates
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/** 待办编辑面板：标题、备注、截止日期、所属清单、删除。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditTodoSheet(
    editingItem: TodoItem?,
    lists: List<TodoList>,
    onSave: (itemId: String, title: String, note: String, dueAt: Long?, dueAtChanged: Boolean, listId: String) -> Unit,
    onDelete: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val item = editingItem ?: return

    var title by remember(item.id) { mutableStateOf(item.title) }
    var note by remember(item.id) { mutableStateOf(item.note) }
    var listId by remember(item.id) { mutableStateOf(item.listId) }
    var dueDate by remember(item.id) { mutableStateOf(item.dueAt?.let { Dates.toLocalDate(it) }) }
    var showDatePicker by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("标题") },
                singleLine = true,
            )
            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                label = { Text("备注") },
                minLines = 2,
            )

            // 截止日期
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = dueDate?.let { "截止：${Dates.shortDate(it)}" } ?: "未设置截止日期",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (dueDate == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                )
                Row {
                    TextButton(onClick = { dueDate = null }, enabled = dueDate != null) { Text("清除") }
                    TextButton(onClick = { showDatePicker = true }) { Text("选择日期") }
                }
            }

            HorizontalDivider(Modifier.padding(vertical = 6.dp))

            // 所属清单
            Text("所属清单", style = MaterialTheme.typography.labelLarge)
            LazyRow(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(lists, key = { it.id }) { list ->
                    SelectableChip(
                        text = list.name,
                        selected = listId == list.id,
                        onClick = { listId = list.id },
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = { onDelete(item.id) }) {
                    Text("删除待办", color = MaterialTheme.colorScheme.error)
                }
                Button(
                    onClick = {
                        val dueAtChanged = dueDate != item.dueAt?.let { Dates.toLocalDate(it) }
                        onSave(
                            item.id,
                            title.trim(),
                            note,
                            dueDate?.let { Dates.startOfDay(it) },
                            dueAtChanged,
                            listId,
                        )
                    },
                    enabled = title.isNotBlank(),
                ) {
                    Text("保存")
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (showDatePicker) {
        val initialMillis = (dueDate ?: Dates.today()).atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds()
        val datePickerState = rememberDatePickerState(initialSelectedDateMillis = initialMillis)
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    datePickerState.selectedDateMillis?.let { millis ->
                        // DatePicker 返回 UTC 当天零点，这里只取日期，存储时再转为当地时区零点
                        dueDate = Instant.fromEpochMilliseconds(millis)
                            .toLocalDateTime(TimeZone.UTC)
                            .date
                    }
                    showDatePicker = false
                }) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text("取消") }
            },
        ) {
            DatePicker(state = datePickerState)
        }
    }
}
