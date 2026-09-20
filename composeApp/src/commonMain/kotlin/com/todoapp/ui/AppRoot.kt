package com.todoapp.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.automirrored.filled.EventNote
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.todoapp.di.AppContainer
import com.todoapp.ui.plan.PlanScreen
import com.todoapp.ui.settings.SettingsScreen
import com.todoapp.ui.theme.TodoTheme
import com.todoapp.ui.todos.EditTodoSheet
import com.todoapp.ui.todos.TodosScreen

private enum class AppTab(val label: String) {
    TODOS("清单"),
    PLAN("计划"),
}

/** 平台入口持有 [AppContainer] 并传入。[initialTab] 用于自动化验证时指定起始页。 */
@Composable
fun App(container: AppContainer, initialTab: String = "TODOS") {
    LaunchedEffect(Unit) { container.syncEngine.syncNow() }

    TodoTheme {
        AppNav(container, initialTab)
    }
}

@Composable
private fun AppNav(container: AppContainer, initialTab: String) {
    var tab by rememberSaveable { mutableStateOf(initialTab) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    val currentTab = AppTab.valueOf(tab)

    val syncStatus by container.syncEngine.status.collectAsState()
    val editingItem by container.editSession.editingItem.collectAsState()

    BoxWithConstraintsCompat { maxWidth ->
        val wide = maxWidth >= 700.dp

        if (showSettings) {
            BackHandlerCompat { showSettings = false }
            SettingsScreen(
                viewModel = container.settingsViewModel,
                transferViewModel = container.transferViewModel,
                onBack = { showSettings = false },
            )
        } else {
            val todos: @Composable (Modifier) -> Unit = { modifier ->
                Box(modifier) {
                    TodosScreen(
                        viewModel = container.todosViewModel,
                        editSession = container.editSession,
                        syncStatus = syncStatus,
                        onSyncNow = container.syncEngine::syncNow,
                        onOpenSettings = { showSettings = true },
                    )
                }
            }
            val plan: @Composable (Modifier) -> Unit = { modifier ->
                Box(modifier) {
                    PlanScreen(
                        viewModel = container.planViewModel,
                        editSession = container.editSession,
                        syncStatus = syncStatus,
                        onSyncNow = container.syncEngine::syncNow,
                    )
                }
            }

            if (wide) {
                Row(Modifier.fillMaxSize()) {
                    NavigationRail {
                        NavigationRailItem(
                            selected = currentTab == AppTab.TODOS,
                            onClick = { tab = AppTab.TODOS.name },
                            icon = { Icon(Icons.Filled.Checklist, contentDescription = null) },
                            label = { Text(AppTab.TODOS.label) },
                        )
                        NavigationRailItem(
                            selected = currentTab == AppTab.PLAN,
                            onClick = { tab = AppTab.PLAN.name },
                            icon = { Icon(Icons.AutoMirrored.Filled.EventNote, contentDescription = null) },
                            label = { Text(AppTab.PLAN.label) },
                        )
                    }
                    when (currentTab) {
                        AppTab.TODOS -> todos(Modifier.weight(1f).fillMaxSize())
                        AppTab.PLAN -> plan(Modifier.weight(1f).fillMaxSize())
                    }
                }
            } else {
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    bottomBar = {
                        NavigationBar(modifier = Modifier.fillMaxWidth()) {
                            NavigationBarItem(
                                selected = currentTab == AppTab.TODOS,
                                onClick = { tab = AppTab.TODOS.name },
                                icon = { Icon(Icons.Filled.Checklist, contentDescription = null) },
                                label = { Text(AppTab.TODOS.label) },
                            )
                            NavigationBarItem(
                                selected = currentTab == AppTab.PLAN,
                                onClick = { tab = AppTab.PLAN.name },
                                icon = { Icon(Icons.AutoMirrored.Filled.EventNote, contentDescription = null) },
                                label = { Text(AppTab.PLAN.label) },
                            )
                        }
                    },
                ) { padding ->
                    Column(Modifier.fillMaxSize().padding(padding)) {
                        when (currentTab) {
                            AppTab.TODOS -> todos(Modifier.weight(1f).fillMaxWidth())
                            AppTab.PLAN -> plan(Modifier.weight(1f).fillMaxWidth())
                        }
                    }
                }
            }
        }
    }

    // 编辑面板挂在根部，两个页面共用
    EditTodoSheet(
        editingItem = editingItem,
        lists = container.repository.lists.collectAsState().value.filter { it.deletedAt == null }.sortedBy { it.sort },
        onSave = { id, title, note, dueAt, dueAtChanged, listId ->
            container.editSession.save(id, title, note, dueAt, dueAtChanged, listId)
        },
        onDelete = container.editSession::delete,
        onDismiss = container.editSession::close,
    )
}

/** BoxWithConstraints 的稳定封装。 */
@Composable
private fun BoxWithConstraintsCompat(content: @Composable (androidx.compose.ui.unit.Dp) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        content(maxWidth)
    }
}
