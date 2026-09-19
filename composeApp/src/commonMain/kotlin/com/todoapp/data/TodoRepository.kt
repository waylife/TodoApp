package com.todoapp.data

import com.todoapp.db.AppDatabase
import com.todoapp.model.RemoteSnapshot
import com.todoapp.model.TodoItem
import com.todoapp.model.TodoList
import com.todoapp.util.newId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.todoapp.db.TodoList as DbList
import com.todoapp.db.TodoItem as DbItem

/**
 * 本地数据仓库：全部数据常驻内存（StateFlow），每次变更同步落库。
 * 个人待办的数据量远小于内存压力，换来最简单的查询/搜索/过滤实现。
 */
class TodoRepository(
    private val db: AppDatabase,
    private val clock: () -> Long,
) {
    /** 本地数据变更后回调，用于触发防抖同步；同步引擎整体写库时会临时关闭。 */
    var onLocalChange: (() -> Unit)? = null

    private val _lists = MutableStateFlow<List<TodoList>>(emptyList())
    val lists: StateFlow<List<TodoList>> = _lists.asStateFlow()

    private val _items = MutableStateFlow<List<TodoItem>>(emptyList())
    val items: StateFlow<List<TodoItem>> = _items.asStateFlow()

    init {
        reloadFromDb()
    }

    private fun reloadFromDb() {
        _lists.value = db.todoQueries.selectAllLists().executeAsList().map(DbList::toModel)
        _items.value = db.todoQueries.selectAllItems().executeAsList().map(DbItem::toModel)
    }

    // ---------- 清单 ----------

    fun addList(name: String): TodoList {
        val now = clock()
        val maxSort = _lists.value.maxOfOrNull { it.sort } ?: 0L
        val list = TodoList(
            id = newId(),
            name = name.trim(),
            sort = maxSort + 1,
            createdAt = now,
            updatedAt = now,
        )
        persistList(list)
        return list
    }

    fun renameList(id: String, newName: String) {
        val current = _lists.value.firstOrNull { it.id == id } ?: return
        if (newName.isBlank()) return
        persistList(current.copy(name = newName.trim(), updatedAt = clock()))
    }

    fun deleteList(id: String) {
        val now = clock()
        // 清单墓碑化，其下所有事项一并墓碑化，保证删除同步到其它设备
        val tomb = _lists.value.firstOrNull { it.id == id }?.copy(deletedAt = now, updatedAt = now) ?: return
        persistList(tomb)
        _items.value.filter { it.listId == id && it.deletedAt == null }.forEach {
            persistItem(it.copy(deletedAt = now, updatedAt = now))
        }
    }

    // ---------- 待办 ----------

    fun addItem(listId: String, title: String, dueAt: Long? = null, note: String = ""): TodoItem {
        val now = clock()
        val item = TodoItem(
            id = newId(),
            listId = listId,
            title = title.trim(),
            note = note,
            dueAt = dueAt,
            createdAt = now,
            updatedAt = now,
        )
        persistItem(item)
        return item
    }

    fun updateItem(
        id: String,
        title: String? = null,
        note: String? = null,
        dueAt: Long? = null,
        dueAtChanged: Boolean = false,
        listId: String? = null,
    ) {
        val current = _items.value.firstOrNull { it.id == id } ?: return
        val newTitle = (title ?: current.title).trim().ifEmpty { current.title }
        val updated = current.copy(
            title = newTitle,
            note = note ?: current.note,
            dueAt = if (dueAtChanged) dueAt else current.dueAt,
            listId = listId ?: current.listId,
            updatedAt = clock(),
        )
        persistItem(updated)
    }

    fun setDone(id: String, done: Boolean) {
        val current = _items.value.firstOrNull { it.id == id } ?: return
        persistItem(current.copy(done = done, updatedAt = clock()))
    }

    fun deleteItem(id: String) {
        val now = clock()
        val current = _items.value.firstOrNull { it.id == id } ?: return
        persistItem(current.copy(deletedAt = now, updatedAt = now))
    }

    // ---------- 同步支撑 ----------

    fun currentSnapshot(): RemoteSnapshot = RemoteSnapshot(
        rev = 0, // rev 由合并结果决定
        savedAt = clock(),
        lists = _lists.value,
        items = _items.value,
    )

    /** 用合并结果整体替换本地库。同步引擎调用，不再触发 onLocalChange。 */
    fun replaceAll(lists: List<TodoList>, items: List<TodoItem>) {
        val quiet = onLocalChange
        onLocalChange = null
        try {
            db.transaction {
                db.todoQueries.deleteAllLists()
                db.todoQueries.deleteAllItems()
                lists.forEach { db.todoQueries.upsertList(it.id, it.name, it.sort, it.createdAt, it.updatedAt, it.deletedAt) }
                items.forEach { db.todoQueries.upsertItem(it.id, it.listId, it.title, it.note, boolToInt(it.done), it.dueAt, it.createdAt, it.updatedAt, it.deletedAt) }
            }
            reloadFromDb()
        } finally {
            onLocalChange = quiet
        }
    }

    /** 清理 [before] 之前的墓碑，避免库无限膨胀。 */
    fun purgeDeleted(before: Long) {
        db.todoQueries.purgeListsDeletedBefore(before)
        db.todoQueries.purgeItemsDeletedBefore(before)
        reloadFromDb()
    }

    // ---------- 内部 ----------

    private fun persistList(list: TodoList) {
        db.todoQueries.upsertList(list.id, list.name, list.sort, list.createdAt, list.updatedAt, list.deletedAt)
        _lists.value = _lists.value.filterNot { it.id == list.id } + list
        onLocalChange?.invoke()
    }

    private fun persistItem(item: TodoItem) {
        db.todoQueries.upsertItem(item.id, item.listId, item.title, item.note, boolToInt(item.done), item.dueAt, item.createdAt, item.updatedAt, item.deletedAt)
        _items.value = _items.value.filterNot { it.id == item.id } + item
        onLocalChange?.invoke()
    }
}

/** 库中 done 为 INTEGER，落库前转换。 */
private fun boolToInt(value: Boolean): Long = if (value) 1L else 0L

private fun DbList.toModel() = TodoList(id, name, sort, createdAt, updatedAt, deletedAt)
private fun DbItem.toModel() = TodoItem(id, listId, title, note, done = done != 0L, dueAt, createdAt, updatedAt, deletedAt)
