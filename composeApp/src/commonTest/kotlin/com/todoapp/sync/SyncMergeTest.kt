package com.todoapp.sync

import com.todoapp.model.ProgressEntry
import com.todoapp.model.TodoItem
import com.todoapp.model.TodoList
import com.todoapp.model.RemoteSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SyncMergeTest {

    private fun list(id: String, name: String, updatedAt: Long, deletedAt: Long? = null) =
        TodoList(id = id, name = name, createdAt = 0, updatedAt = updatedAt, deletedAt = deletedAt)

    private fun item(
        id: String,
        title: String,
        updatedAt: Long,
        done: Boolean = false,
        deletedAt: Long? = null,
        listId: String = "l1",
    ) = TodoItem(id = id, listId = listId, title = title, createdAt = 0, updatedAt = updatedAt, done = done, deletedAt = deletedAt)

    private fun snap(lists: List<TodoList> = emptyList(), items: List<TodoItem> = emptyList(), rev: Long = 0) =
        RemoteSnapshot(rev = rev, lists = lists, items = items)

    @Test
    fun `本地新增在合并后保留`() {
        val local = snap(items = listOf(item("a", "本地任务", 100)))
        val merged = SyncMerge.merge(local, snap(), now = 200)
        assertEquals(1, merged.items.size)
        assertEquals("本地任务", merged.items[0].title)
    }

    @Test
    fun `远程新增在合并后保留`() {
        val remote = snap(items = listOf(item("b", "远程任务", 100)))
        val merged = SyncMerge.merge(snap(), remote, now = 200)
        assertEquals(1, merged.items.size)
        assertEquals("远程任务", merged.items[0].title)
    }

    @Test
    fun `两端同时编辑取较新者`() {
        val local = snap(items = listOf(item("a", "本地标题", 300)))
        val remote = snap(items = listOf(item("a", "远程标题", 200)))
        val merged = SyncMerge.merge(local, remote, now = 400)
        assertEquals("本地标题", merged.items[0].title)

        val mergedReversed = SyncMerge.merge(remote, local, now = 400)
        assertEquals("本地标题", mergedReversed.items[0].title, "交换 local/remote 后结果必须一致")
    }

    @Test
    fun `删除胜过较早的编辑`() {
        val local = snap(items = listOf(item("a", "编辑后的任务", 200)))
        val remote = snap(items = listOf(item("a", "原任务", 250, deletedAt = 250)))
        val merged = SyncMerge.merge(local, remote, now = 400)
        assertNotNull(merged.items[0].deletedAt, "删除时间晚于编辑时间，应保持删除状态")
    }

    @Test
    fun `晚于删除的编辑使条目复活`() {
        val local = snap(items = listOf(item("a", "删除后重新编辑", 400)))
        val remote = snap(items = listOf(item("a", "原任务", 200, deletedAt = 250)))
        val merged = SyncMerge.merge(local, remote, now = 500)
        assertNull(merged.items[0].deletedAt, "编辑晚于删除，条目应复活")
        assertEquals("删除后重新编辑", merged.items[0].title)
    }

    @Test
    fun `合并结果与参数顺序无关`() {
        val local = snap(
            lists = listOf(list("l1", "工作", 100), list("l2", "生活", 100)),
            items = listOf(item("a", "甲", 100, listId = "l1"), item("c", "丙", 500)),
        )
        val remote = snap(
            lists = listOf(list("l1", "工作（改名）", 200), list("l3", "学习", 150)),
            items = listOf(item("a", "甲（远程改名）", 300, done = true), item("b", "乙", 120, listId = "l3")),
            rev = 7,
        )

        val merged1 = SyncMerge.merge(local, remote, now = 999)
        val merged2 = SyncMerge.merge(remote, local, now = 999)

        assertEquals(merged1.items, merged2.items, "条目合并结果必须一致")
        assertEquals(merged1.lists, merged2.lists, "清单合并结果必须一致")
        assertEquals(8, merged1.rev, "rev 应为两侧最大值 + 1")
    }

    @Test
    fun `合并后清单内容正确`() {
        val local = snap(lists = listOf(list("l1", "工作", 100)))
        val remote = snap(lists = listOf(list("l1", "工作（改名）", 200), list("l3", "学习", 150)))
        val merged = SyncMerge.merge(local, remote, now = 300)
        assertEquals(2, merged.lists.size)
        assertEquals("工作（改名）", merged.lists.first { it.id == "l1" }.name)
        assertEquals("学习", merged.lists.first { it.id == "l3" }.name)
    }

    @Test
    fun `两端状态互不影响地收敛`() {
        // 设备 A：完成任务；设备 B：改名。合并后两台设备都应看到对方的修改
        val base = item("a", "任务", 100)
        val onA = base.copy(done = true, updatedAt = 300)
        val onB = base.copy(title = "任务（改名）", updatedAt = 200)

        val mergedOnA = SyncMerge.merge(snap(items = listOf(onA)), snap(items = listOf(onB)), 400)
        val mergedOnB = SyncMerge.merge(snap(items = listOf(onB)), snap(items = listOf(onA)), 400)
        assertEquals(mergedOnA.items, mergedOnB.items)
        assertTrue(mergedOnA.items[0].done)
        assertEquals("任务", mergedOnA.items[0].title, "完成任务时间更晚，标题保持原值")
    }

    @Test
    fun `描述与进度随较新的条目整体胜出`() {
        // 描述与进度时间线不做字段级合并：哪个端最后写过条目，就整体采用哪个端的版本
        val onA = item("a", "写周报", 300).copy(
            description = "A 端补充的描述",
            progressUpdates = listOf(ProgressEntry(id = "p1", text = "完成调研", createdAt = 300)),
        )
        val onB = item("a", "写周报", 200).copy(description = "B 端的旧描述")

        val merged = SyncMerge.merge(snap(items = listOf(onA)), snap(items = listOf(onB)), 400)
        val mergedReversed = SyncMerge.merge(snap(items = listOf(onB)), snap(items = listOf(onA)), 400)

        assertEquals(merged.items, mergedReversed.items, "交换参数后结果必须一致")
        assertEquals("A 端补充的描述", merged.items[0].description)
        assertEquals(listOf("完成调研"), merged.items[0].progressUpdates.map { it.text })
    }
}
