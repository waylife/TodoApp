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

/** 一条进度更新记录：用户在某时刻对任务进展的文字记录，按创建时间展示。 */
@Serializable
data class ProgressEntry(
    val id: String,
    val text: String,
    val createdAt: Long,
)

/**
 * 一条待办事项。dueAt 为当地时区当天零点的毫秒时间戳，仅精确到日期。
 * note 是清单页里展示的短备注；description 是编辑面板里的长描述。
 * progressUpdates 为进度更新时间线，整体随条目按 updatedAt 做 LWW 合并。
 */
@Serializable
data class TodoItem(
    val id: String,
    val listId: String,
    val title: String,
    val note: String = "",
    val description: String = "",
    val progressUpdates: List<ProgressEntry> = emptyList(),
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
        /** v2：条目新增 description 与 progressUpdates。旧版本读到 v2 会按「版本过高」拒绝，避免合并时静默丢字段。 */
        const val SCHEMA_VERSION = 2
    }
}
