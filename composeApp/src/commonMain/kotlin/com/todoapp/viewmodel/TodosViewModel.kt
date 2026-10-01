package com.todoapp.viewmodel

import com.todoapp.data.TodoRepository
import com.todoapp.model.TodoItem
import com.todoapp.model.TodoList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/** 清单页状态：选中清单、搜索词、过滤后的待办。 */
data class TodosUiState(
    val lists: List<TodoList> = emptyList(),
    /** 清单 id → 名称，供行内角标 O(1) 查询（预计算，避免每行线性扫描）。 */
    val listNames: Map<String, String> = emptyMap(),
    val selectedListId: String? = null, // null = 全部
    val searchQuery: String = "",
    val activeItems: List<TodoItem> = emptyList(),
    val doneItems: List<TodoItem> = emptyList(),
)

class TodosViewModel(
    private val repository: TodoRepository,
    appScope: CoroutineScope,
) {
    private val _selectedListId = MutableStateFlow<String?>(null)
    private val _searchQuery = MutableStateFlow("")

    val uiState: StateFlow<TodosUiState> = combine(
        repository.lists,
        repository.items,
        _selectedListId,
        _searchQuery,
    ) { lists, items, selectedListId, query ->
        val aliveLists = lists.filter { it.deletedAt == null }.sortedBy { it.sort }
        val queryNorm = query.trim()
        val scoped = items.asSequence()
            .filter { it.deletedAt == null }
            .filter { selectedListId == null || it.listId == selectedListId }
            .filter {
                queryNorm.isEmpty() ||
                    it.title.contains(queryNorm, ignoreCase = true) ||
                    it.note.contains(queryNorm, ignoreCase = true) ||
                    it.description.contains(queryNorm, ignoreCase = true) ||
                    it.progressUpdates.any { entry -> entry.text.contains(queryNorm, ignoreCase = true) }
            }
            .toList()
        val active = scoped.filter { !it.done }.sortedWith(
            compareBy<TodoItem> { it.dueAt == null }
                .thenBy { it.dueAt ?: Long.MAX_VALUE }
                .thenByDescending { it.createdAt },
        )
        val done = scoped.filter { it.done }.sortedByDescending { it.updatedAt }
        TodosUiState(
            lists = aliveLists,
            listNames = aliveLists.associate { it.id to it.name },
            selectedListId = selectedListId,
            searchQuery = query,
            activeItems = active,
            doneItems = done,
        )
    }.stateIn(appScope, SharingStarted.Eagerly, TodosUiState())

    fun selectList(listId: String?) {
        _selectedListId.value = listId
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    /** 底部输入栏添加待办；没有可用清单时自动建「默认」清单。 */
    fun addTodo(title: String) {
        if (title.isBlank()) return
        val aliveLists = repository.lists.value.filter { it.deletedAt == null }
        val targetListId = _selectedListId.value
            // 选中清单可能已被其它设备删除并经同步墓碑化，此时回退到存活清单
            ?.takeIf { id -> aliveLists.any { it.id == id } }
            ?: aliveLists.firstOrNull()?.id
            ?: repository.addList("默认").id
        repository.addItem(targetListId, title)
    }

    fun toggleDone(item: TodoItem) {
        repository.setDone(item.id, !item.done)
    }

    fun addList(name: String) {
        if (name.isBlank()) return
        repository.addList(name)
    }

    fun renameList(listId: String, newName: String) {
        repository.renameList(listId, newName)
    }

    fun deleteList(listId: String) {
        repository.deleteList(listId)
        if (_selectedListId.value == listId) _selectedListId.value = null
    }
}
