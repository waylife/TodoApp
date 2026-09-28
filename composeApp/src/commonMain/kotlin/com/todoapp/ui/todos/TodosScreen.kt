package com.todoapp.ui.todos

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.collectAsState
import com.todoapp.model.TodoList
import com.todoapp.sync.SyncStatus
import com.todoapp.ui.common.EmptyState
import com.todoapp.ui.common.SelectableChip
import com.todoapp.ui.common.SectionHeader
import com.todoapp.ui.common.SyncStatusRow
import com.todoapp.ui.common.TodoRow
import com.todoapp.viewmodel.EditSession
import com.todoapp.viewmodel.TodosViewModel

/** 清单页：清单筛选 + 待办列表 + 底部添加栏。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodosScreen(
    viewModel: TodosViewModel,
    editSession: EditSession,
    syncStatus: SyncStatus,
    onSyncNow: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val state by viewModel.uiState.collectAsState()
    var searchActive by rememberSaveable { mutableStateOf(false) }
    var showAddListDialog by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<TodoList?>(null) }
    var deleteTarget by remember { mutableStateOf<TodoList?>(null) }
    var chipMenuFor by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("待办", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        SyncStatusRow(status = syncStatus, onSync = onSyncNow)
                    }
                },
                actions = {
                    IconButton(onClick = { searchActive = !searchActive }) {
                        Icon(Icons.Filled.Search, contentDescription = "搜索")
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "设置")
                    }
                },
            )
        },
        bottomBar = { AddTodoBar(onAdd = viewModel::addTodo) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (searchActive) {
                OutlinedTextField(
                    value = state.searchQuery,
                    onValueChange = viewModel::setSearchQuery,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    placeholder = { Text("搜索标题或备注") },
                    singleLine = true,
                    trailingIcon = {
                        TextButton(onClick = {
                            viewModel.setSearchQuery("")
                            searchActive = false
                        }) { Text("取消") }
                    },
                )
            }

            // 清单筛选 chips
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    SelectableChip(
                        text = "全部",
                        selected = state.selectedListId == null,
                        onClick = { viewModel.selectList(null) },
                    )
                }
                items(state.lists, key = { it.id }) { list ->
                    BoxWithMenu(
                        selected = state.selectedListId == list.id,
                        text = list.name,
                        onClick = { viewModel.selectList(list.id) },
                        onLongClick = { chipMenuFor = list.id },
                        menuExpanded = chipMenuFor == list.id,
                        onDismissMenu = { chipMenuFor = null },
                        onRename = {
                            chipMenuFor = null
                            renameTarget = list
                        },
                        onDelete = {
                            chipMenuFor = null
                            deleteTarget = list
                        },
                    )
                }
                item {
                    SelectableChip(text = "＋ 清单", selected = false, onClick = { showAddListDialog = true })
                }
            }

            if (state.activeItems.isEmpty() && state.doneItems.isEmpty()) {
                EmptyState(
                    when {
                        state.searchQuery.isNotBlank() -> "没有匹配「${state.searchQuery}」的待办"
                        else -> "还没有待办，从下方输入框开始添加吧"
                    },
                )
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(state.activeItems, key = { it.id }) { item ->
                        TodoRow(
                            item = item,
                            listName = if (state.selectedListId == null) state.listNames[item.listId] else null,
                            onToggle = { viewModel.toggleDone(item) },
                            onClick = { editSession.open(item.id) },
                        )
                    }
                    if (state.doneItems.isNotEmpty()) {
                        item(key = "done-header") {
                            SectionHeader("已完成 ${state.doneItems.size}")
                        }
                    }
                    items(state.doneItems, key = { "done-" + it.id }) { item ->
                        TodoRow(
                            item = item,
                            listName = if (state.selectedListId == null) state.listNames[item.listId] else null,
                            onToggle = { viewModel.toggleDone(item) },
                            onClick = { editSession.open(item.id) },
                        )
                    }
                }
            }
        }
    }

    if (showAddListDialog) {
        TextInputDialog(
            title = "新建清单",
            placeholder = "清单名称",
            confirmLabel = "创建",
            onConfirm = {
                viewModel.addList(it)
                showAddListDialog = false
            },
            onDismiss = { showAddListDialog = false },
        )
    }

    renameTarget?.let { list ->
        TextInputDialog(
            title = "重命名清单",
            placeholder = "清单名称",
            initialText = list.name,
            confirmLabel = "保存",
            onConfirm = {
                viewModel.renameList(list.id, it)
                renameTarget = null
            },
            onDismiss = { renameTarget = null },
        )
    }

    deleteTarget?.let { list ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除清单「${list.name}」？") },
            text = { Text("清单内的所有待办也会一并删除，并同步到其它设备。") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteList(list.id)
                    deleteTarget = null
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("取消") }
            },
        )
    }
}

/** 带长按菜单的清单 chip。 */
@Composable
private fun BoxWithMenu(
    selected: Boolean,
    text: String,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    menuExpanded: Boolean,
    onDismissMenu: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface {
        androidx.compose.foundation.layout.Box {
            SelectableChip(
                text = text,
                selected = selected,
                onClick = onClick,
                onLongClick = onLongClick,
            )
            DropdownMenu(expanded = menuExpanded, onDismissRequest = onDismissMenu) {
                DropdownMenuItem(text = { Text("重命名") }, onClick = onRename)
                DropdownMenuItem(text = { Text("删除清单") }, onClick = onDelete)
            }
        }
    }
}

/** 底部添加栏。 */
@Composable
private fun AddTodoBar(onAdd: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    Surface(tonalElevation = 3.dp) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("添加待办…") },
                singleLine = true,
            )
            Spacer(Modifier.width(8.dp))
            IconButton(onClick = {
                onAdd(text)
                text = ""
            }, enabled = text.isNotBlank()) {
                Icon(Icons.Filled.Add, contentDescription = "添加待办")
            }
        }
    }
}

/** 单行文本输入对话框（新建/重命名清单）。 */
@Composable
fun TextInputDialog(
    title: String,
    placeholder: String,
    initialText: String = "",
    confirmLabel: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(initialText) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text(placeholder) },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }, enabled = text.isNotBlank()) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
