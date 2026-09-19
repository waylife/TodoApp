package com.todoapp.sync

import com.todoapp.model.RemoteSnapshot
import com.todoapp.model.TodoItem
import com.todoapp.model.TodoList
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 快照合并：以「最后写入者胜」(LWW) 规则逐条合并本地与远程实体。
 *
 * 设备 A 以 (local=A, remote=B) 调用，设备 B 以 (local=B, remote=A) 调用，
 * 算法对两侧完全对称，因此两端总是收敛到同一结果：
 * - 仅一侧存在 → 采用该侧（含墓碑）；
 * - 两侧都有 → updatedAt 新者胜；
 * - 时间戳相等 → 删除（墓碑）者胜；仍相同 → 按 JSON 规范串字典序决胜。
 * - 合并后 rev 取两侧最大值 + 1。
 */
object SyncMerge {

    private val json = Json

    fun merge(local: RemoteSnapshot, remote: RemoteSnapshot, now: Long): RemoteSnapshot = RemoteSnapshot(
        schemaVersion = RemoteSnapshot.SCHEMA_VERSION,
        rev = maxOf(local.rev, remote.rev) + 1,
        savedAt = now,
        lists = mergeEntities(local.lists, remote.lists, { it.id }, ::preferList),
        items = mergeEntities(local.items, remote.items, { it.id }, ::preferItem),
    )

    private fun preferList(a: TodoList, b: TodoList): TodoList =
        prefer(a, b, { it.updatedAt }, { it.deletedAt }, { json.encodeToString(it) })

    private fun preferItem(a: TodoItem, b: TodoItem): TodoItem =
        prefer(a, b, { it.updatedAt }, { it.deletedAt }, { json.encodeToString(it) })

    private inline fun <T, K : Comparable<K>> mergeEntities(
        local: List<T>,
        remote: List<T>,
        keyOf: (T) -> K,
        prefer: (T, T) -> T,
    ): List<T> {
        val merged = HashMap<K, T>()
        for (e in local) merged[keyOf(e)] = e
        for (e in remote) {
            val key = keyOf(e)
            val existing = merged[key]
            merged[key] = if (existing == null) e else prefer(existing, e)
        }
        // 按 id 排序，输出与合并顺序无关，保证两端快照内容一致
        return merged.entries.sortedBy { it.key }.map { it.value }
    }

    private inline fun <T> prefer(
        a: T,
        b: T,
        updatedAtOf: (T) -> Long,
        deletedAtOf: (T) -> Long?,
        canonical: (T) -> String,
    ): T {
        val au = updatedAtOf(a)
        val bu = updatedAtOf(b)
        if (au != bu) return if (au > bu) a else b

        val aDeleted = deletedAtOf(a) != null
        val bDeleted = deletedAtOf(b) != null
        if (aDeleted != bDeleted) return if (aDeleted) a else b

        return if (canonical(b) > canonical(a)) b else a
    }
}
