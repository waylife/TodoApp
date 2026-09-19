package com.todoapp.viewmodel

import com.todoapp.data.TodoRepository
import com.todoapp.model.TodoItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * 跨页面共享的「编辑会话」：清单页与计划页都会打开同一条待办的编辑面板，
 * 编辑面板挂在应用根部统一渲染。
 */
class EditSession(private val repository: TodoRepository, appScope: CoroutineScope) {

    private val _editingItemId = MutableStateFlow<String?>(null)

    val editingItemId: StateFlow<String?> = _editingItemId.asStateFlow()

    val editingItem: StateFlow<TodoItem?> = combine(
        repository.items,
        _editingItemId,
    ) { items, id ->
        id?.let { itemId -> items.firstOrNull { it.id == itemId && it.deletedAt == null } }
    }.stateIn(appScope, SharingStarted.Eagerly, null)

    fun open(itemId: String) {
        _editingItemId.value = itemId
    }

    fun close() {
        _editingItemId.value = null
    }

    fun save(
        itemId: String,
        title: String,
        note: String,
        dueAt: Long?,
        dueAtChanged: Boolean,
        listId: String,
    ) {
        repository.updateItem(
            id = itemId,
            title = title,
            note = note,
            dueAt = dueAt,
            dueAtChanged = dueAtChanged,
            listId = listId,
        )
        close()
    }

    fun delete(itemId: String) {
        repository.deleteItem(itemId)
        close()
    }
}
