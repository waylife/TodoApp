package com.todoapp.ui.todos

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
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
import kotlin.math.roundToInt
import kotlin.time.Instant

/**
 * 待办编辑面板：标题、任务描述、备注、整体进度、截止日期、所属清单、进度更新、删除。
 *
 * 描述与备注的区别：备注是清单页里展示的一行短注；描述是长文详情，只在编辑面板完整展示。
 * 进度更新是时间线：点「添加」立即落库（不随「保存」提交），随时记一笔进展。
 * 整体进度同理：拖动滑杆松手即落库，null 表示「未设置」，清单行不显示角标。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditTodoSheet(
    editingItem: TodoItem?,
    lists: List<TodoList>,
    onSave: (itemId: String, title: String, note: String, description: String, dueAt: Long?, dueAtChanged: Boolean, listId: String) -> Unit,
    onAddProgress: (itemId: String, text: String) -> Unit,
    onRemoveProgress: (itemId: String, entryId: String) -> Unit,
    onSetProgress: (itemId: String, percent: Int?) -> Unit,
    onDelete: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val item = editingItem ?: return

    var title by remember(item.id) { mutableStateOf(item.title) }
    var description by remember(item.id) { mutableStateOf(item.description) }
    var note by remember(item.id) { mutableStateOf(item.note) }
    var listId by remember(item.id) { mutableStateOf(item.listId) }
    var dueDate by remember(item.id) { mutableStateOf(item.dueAt?.let { Dates.toLocalDate(it) }) }
    // 拖动中显示草稿值，松手才落库；null 表示未设置
    var progressDraft by remember(item.id) { mutableStateOf(item.progressPercent?.toFloat()) }
    var progressText by remember { mutableStateOf("") }
    var showDatePicker by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        // 描述 + 进度时间线让内容高度不可控，必须可滚动
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
        ) {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("标题") },
                singleLine = true,
            )
            OutlinedTextField(
                value = description,
                onValueChange = { description = it },
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                label = { Text("任务描述") },
                minLines = 3,
            )
            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                label = { Text("备注") },
                minLines = 2,
            )

            // 整体进度：0-100%，松手即落库；与完成状态相互独立
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = progressDraft?.let { "整体进度：${it.roundToInt()}%" } ?: "整体进度未设置",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (progressDraft == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                )
                TextButton(
                    onClick = {
                        progressDraft = null
                        onSetProgress(item.id, null)
                    },
                    enabled = progressDraft != null,
                ) { Text("清除") }
            }
            Slider(
                value = progressDraft ?: 0f,
                onValueChange = { progressDraft = it },
                onValueChangeFinished = {
                    val percent = (progressDraft ?: 0f).roundToInt().coerceIn(0, 100)
                    onSetProgress(item.id, percent)
                    progressDraft = percent.toFloat()
                },
                valueRange = 0f..100f,
                steps = 19, // 5% 一档，触屏上好调准
                modifier = Modifier.fillMaxWidth(),
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

            HorizontalDivider(Modifier.padding(vertical = 6.dp))

            // 进度更新：时间线 + 快速记录
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("进度更新", style = MaterialTheme.typography.labelLarge)
                if (item.progressUpdates.isNotEmpty()) {
                    Text(
                        text = "${item.progressUpdates.size} 条记录",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (item.progressUpdates.isEmpty()) {
                Text(
                    text = "还没有进度记录，随时记一笔进展",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            } else {
                Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                    // 新的在前，最近进展一眼可见
                    item.progressUpdates.asReversed().forEach { entry ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                            verticalAlignment = Alignment.Top,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = entry.text,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                Text(
                                    text = Dates.formatSyncTime(entry.createdAt),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            IconButton(
                                onClick = { onRemoveProgress(item.id, entry.id) },
                                modifier = Modifier.size(28.dp),
                            ) {
                                Icon(
                                    Icons.Filled.Close,
                                    contentDescription = "删除该进度记录",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = progressText,
                    onValueChange = { progressText = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("记录一条进度…") },
                    singleLine = true,
                )
                IconButton(
                    onClick = {
                        onAddProgress(item.id, progressText)
                        progressText = ""
                    },
                    enabled = progressText.isNotBlank(),
                ) {
                    Icon(Icons.Filled.Add, contentDescription = "添加进度记录")
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
                            description.trim(),
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
