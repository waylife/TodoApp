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
        description: String,
        dueAt: Long?,
        dueAtChanged: Boolean,
        listId: String,
    ) {
        repository.updateItem(
            id = itemId,
            title = title,
            note = note,
            description = description,
            dueAt = dueAt,
            dueAtChanged = dueAtChanged,
            listId = listId,
        )
        close()
    }

    /** 追加一条进度更新；面板保持打开，方便连续记录。 */
    fun addProgress(itemId: String, text: String) {
        repository.addProgressEntry(itemId, text)
    }

    fun removeProgress(itemId: String, entryId: String) {
        repository.removeProgressEntry(itemId, entryId)
    }

    /** 设置整体进度百分比（0-100），null 表示清除；同进度更新一样立即落库，不随「保存」提交。 */
    fun setProgress(itemId: String, percent: Int?) {
        repository.setProgress(itemId, percent)
    }

    fun delete(itemId: String) {
        repository.deleteItem(itemId)
        close()
    }
}
