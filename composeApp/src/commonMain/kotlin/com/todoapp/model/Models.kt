package com.todoapp.model

import kotlinx.serialization.Serializable

/** 一张待办清单。deletedAt 非空表示已删除（墓碑），用于同步删除操作。 */
@Serializable
data class TodoList(
    val id: String,
    val name: String,
    val sort: Long = 0,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long? = null,
)

/** 一条待办事项。dueAt 为当地时区当天零点的毫秒时间戳，仅精确到日期。 */
@Serializable
data class TodoItem(
    val id: String,
    val listId: String,
    val title: String,
    val note: String = "",
    val done: Boolean = false,
    val dueAt: Long? = null,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long? = null,
)

/** WebDAV 服务器上 todoapp.json 的快照格式，同时用作合并算法的输入。 */
@Serializable
data class RemoteSnapshot(
    val schemaVersion: Int = SCHEMA_VERSION,
    val rev: Long = 0,
    val savedAt: Long = 0,
    val lists: List<TodoList> = emptyList(),
    val items: List<TodoItem> = emptyList(),
) {
    companion object {
        const val SCHEMA_VERSION = 1
    }
}
