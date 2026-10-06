package com.todoapp.data

import com.todoapp.model.TodoItem
import com.todoapp.model.TodoList
import com.todoapp.testing.TestDb
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 仓库层单测：CRUD、落库读回、变更回调、墓碑清理。 */
class TodoRepositoryTest {

    private val db = TestDb()
    private val repo get() = db.repository

    // ---------- 清单 ----------

    @Test
    fun `addList 递增排序并去除首尾空白`() {
        val l1 = repo.addList("  工作  ")
        db.advance(10)
        val l2 = repo.addList("生活")

        assertEquals("工作", l1.name)
        assertEquals(1L, l1.sort)
        assertEquals(2L, l2.sort)
        assertEquals(listOf(l1.id, l2.id), repo.lists.value.map { it.id })
        assertEquals(1_000_000L, l1.createdAt)
        assertEquals(1_000_010L, l2.createdAt)
    }

    @Test
    fun `renameList 更新名称与时间戳`() {
        val l = repo.addList("工作")
        db.advance(10)

        repo.renameList(l.id, " 工作A ")

        val after = repo.lists.value.single { it.id == l.id }
        assertEquals("工作A", after.name)
        assertEquals(1_000_010L, after.updatedAt)
    }

    @Test
    fun `renameList 空白名称被忽略`() {
        val l = repo.addList("工作")
        db.advance(10)

        repo.renameList(l.id, "   ")

        assertEquals("工作", repo.lists.value.single { it.id == l.id }.name)
    }

    @Test
    fun `renameList 不存在的清单无副作用`() {
        repo.renameList("no-such-id", "新名字")
        assertTrue(repo.lists.value.isEmpty())
    }

    @Test
    fun `deleteList 墓碑化清单并级联墓碑化待办`() {
        val l = repo.addList("工作")
        val other = repo.addList("生活")
        repo.addItem(l.id, "甲")
        db.advance(5)
        repo.addItem(l.id, "乙")
        db.advance(5)

        repo.deleteList(l.id)

        val deleted = repo.lists.value.single { it.id == l.id }
        assertEquals(1_000_010L, deleted.deletedAt, "清单应在删除时刻墓碑化")
        assertEquals(1_000_010L, deleted.updatedAt)
        assertTrue(
            repo.items.value.filter { it.listId == l.id }.all { it.deletedAt == 1_000_010L },
            "清单下所有未删除待办应级联墓碑化",
        )
        assertNull(repo.lists.value.single { it.id == other.id }.deletedAt, "其它清单不受影响")
    }

    @Test
    fun `deleteList 不存在的清单无副作用`() {
        repo.deleteList("no-such-id")
        assertTrue(repo.lists.value.isEmpty())
    }

    // ---------- 待办 ----------

    @Test
    fun `addItem 去除首尾空白并记录时间`() {
        val l = repo.addList("工作")
        db.advance(10)

        val a = repo.addItem(l.id, "  写周报  ", dueAt = 42L, note = "备注")

        assertEquals("写周报", a.title)
        assertEquals("备注", a.note)
        assertEquals(42L, a.dueAt)
        assertEquals(1_000_010L, a.createdAt)
        assertEquals(1_000_010L, a.updatedAt)
        assertEquals(a, repo.items.value.single { it.id == a.id })
    }

    @Test
    fun `updateItem 局部更新保留其它字段`() {
        val l = repo.addList("工作")
        val a = repo.addItem(l.id, "甲", dueAt = 42L, note = "备注")
        db.advance(10)

        repo.updateItem(a.id, title = "甲2")

        val after = repo.items.value.single { it.id == a.id }
        assertEquals("甲2", after.title)
        assertEquals("备注", after.note, "只传标题时备注应保留")
        assertEquals(42L, after.dueAt, "未声明 dueAtChanged 时截止日应保留")
        assertEquals(1_000_010L, after.updatedAt)
    }

    @Test
    fun `updateItem 空白标题回退为原标题`() {
        val l = repo.addList("工作")
        val a = repo.addItem(l.id, "甲")

        repo.updateItem(a.id, title = "   ")

        assertEquals("甲", repo.items.value.single { it.id == a.id }.title)
    }

    @Test
    fun `updateItem 仅在 dueAtChanged 时修改截止日`() {
        val l = repo.addList("工作")
        val a = repo.addItem(l.id, "甲", dueAt = 42L)

        repo.updateItem(a.id, title = "改标题", dueAt = 99L)
        assertEquals(42L, repo.items.value.single { it.id == a.id }.dueAt, "dueAtChanged=false 时新 dueAt 不生效")

        repo.updateItem(a.id, dueAt = 99L, dueAtChanged = true)
        assertEquals(99L, repo.items.value.single { it.id == a.id }.dueAt)

        repo.updateItem(a.id, dueAt = null, dueAtChanged = true)
        assertNull(repo.items.value.single { it.id == a.id }.dueAt, "dueAtChanged=true 且 dueAt=null 应清除截止日")
    }

    @Test
    fun `updateItem 可移动清单`() {
        val l1 = repo.addList("工作")
        val l2 = repo.addList("生活")
        val a = repo.addItem(l1.id, "甲")

        repo.updateItem(a.id, listId = l2.id)

        assertEquals(l2.id, repo.items.value.single { it.id == a.id }.listId)
    }

    @Test
    fun `updateItem 更新任务描述且未传时保留`() {
        val l = repo.addList("工作")
        val a = repo.addItem(l.id, "甲", description = "旧描述")
        db.advance(10)

        repo.updateItem(a.id, description = "新描述")

        val after = repo.items.value.single { it.id == a.id }
        assertEquals("新描述", after.description)
        assertEquals(1_000_010L, after.updatedAt)

        repo.updateItem(a.id, title = "改名")

        assertEquals("新描述", repo.items.value.single { it.id == a.id }.description, "未传 description 时应保留原值")
    }

    // ---------- 进度更新 ----------

    @Test
    fun `addProgressEntry 追加带时间戳的进度记录`() {
        val l = repo.addList("工作")
        val a = repo.addItem(l.id, "甲")
        db.advance(10)

        repo.addProgressEntry(a.id, "  开始调研  ")
        db.advance(5)
        repo.addProgressEntry(a.id, "完成方案")

        val after = repo.items.value.single { it.id == a.id }
        assertEquals(listOf("开始调研", "完成方案"), after.progressUpdates.map { it.text }, "进度按记录顺序追加")
        assertEquals(1_000_010L, after.progressUpdates[0].createdAt)
        assertEquals(1_000_015L, after.progressUpdates[1].createdAt)
        assertEquals(1_000_015L, after.updatedAt, "进度变更应推进条目 updatedAt，随条目参与 LWW 合并")
    }

    @Test
    fun `addProgressEntry 空白内容与不存在的条目无副作用`() {
        val l = repo.addList("工作")
        val a = repo.addItem(l.id, "甲")

        repo.addProgressEntry(a.id, "   ")
        repo.addProgressEntry("no-such-id", "x")

        assertTrue(repo.items.value.single { it.id == a.id }.progressUpdates.isEmpty())
    }

    @Test
    fun `removeProgressEntry 只删除目标记录`() {
        val l = repo.addList("工作")
        val a = repo.addItem(l.id, "甲")
        repo.addProgressEntry(a.id, "一")
        db.advance(10)
        repo.addProgressEntry(a.id, "二")
        val stale = repo.items.value.single { it.id == a.id }.progressUpdates[0]

        repo.removeProgressEntry(a.id, stale.id)
        repo.removeProgressEntry(a.id, "no-such-entry")

        assertEquals(listOf("二"), repo.items.value.single { it.id == a.id }.progressUpdates.map { it.text })
    }

    @Test
    fun `进度记录的增删按用户变更处理`() {
        val l = repo.addList("工作")
        val a = repo.addItem(l.id, "甲")
        var changes = 0
        repo.onLocalChange = { changes++ }
        val before = repo.changeVersion

        repo.addProgressEntry(a.id, "一")
        repo.addProgressEntry(a.id, "   ") // 空白内容不落库
        val entry = repo.items.value.single { it.id == a.id }.progressUpdates[0]
        repo.removeProgressEntry(a.id, entry.id)

        assertEquals(2, changes, "增、删各触发一次变更回调，空白内容不触发")
        assertEquals(before + 2, repo.changeVersion, "变更计数应与回调一致，供同步引擎检测并发编辑")
        assertTrue(repo.items.value.single { it.id == a.id }.progressUpdates.isEmpty())
    }

    @Test
    fun `setDone 更新完成状态与时间戳`() {
        val l = repo.addList("工作")
        val a = repo.addItem(l.id, "甲")
        db.advance(10)

        repo.setDone(a.id, true)

        val after = repo.items.value.single { it.id == a.id }
        assertTrue(after.done)
        assertEquals(1_000_010L, after.updatedAt)
    }

    // ---------- 整体进度 ----------

    @Test
    fun `setProgress 设置百分比并推进时间戳`() {
        val l = repo.addList("工作")
        val a = repo.addItem(l.id, "甲")
        db.advance(10)

        repo.setProgress(a.id, 45)

        val after = repo.items.value.single { it.id == a.id }
        assertEquals(45, after.progressPercent)
        assertEquals(1_000_010L, after.updatedAt, "进度变更应推进条目 updatedAt，随条目参与 LWW 合并")
    }

    @Test
    fun `setProgress 越界值收敛到 0-100`() {
        val l = repo.addList("工作")
        val a = repo.addItem(l.id, "甲")

        repo.setProgress(a.id, 130)
        assertEquals(100, repo.items.value.single { it.id == a.id }.progressPercent)

        repo.setProgress(a.id, -5)
        assertEquals(0, repo.items.value.single { it.id == a.id }.progressPercent)
    }

    @Test
    fun `setProgress 传 null 清除进度`() {
        val l = repo.addList("工作")
        val a = repo.addItem(l.id, "甲")
        repo.setProgress(a.id, 45)
        db.advance(10)

        repo.setProgress(a.id, null)

        val after = repo.items.value.single { it.id == a.id }
        assertNull(after.progressPercent, "null 应回到「未设置」状态，与 0% 区分")
        assertEquals(1_000_010L, after.updatedAt)
    }

    @Test
    fun `setProgress 相同值不重复落库`() {
        val l = repo.addList("工作")
        val a = repo.addItem(l.id, "甲")
        repo.setProgress(a.id, 45)
        val before = repo.changeVersion

        repo.setProgress(a.id, 45)

        assertEquals(before, repo.changeVersion, "值未变化不应视为一次用户变更，避免触发无谓同步")
    }

    @Test
    fun `setDone 与 setProgress 相互独立`() {
        val l = repo.addList("工作")
        val a = repo.addItem(l.id, "甲")
        db.advance(10)
        repo.setProgress(a.id, 100)

        repo.setDone(a.id, true)

        val after = repo.items.value.single { it.id == a.id }
        assertTrue(after.done)
        assertEquals(100, after.progressPercent, "勾选完成不应改动整体进度，100% 也不应自动勾选")
    }

    @Test
    fun `deleteItem 墓碑化待办`() {
        val l = repo.addList("工作")
        val a = repo.addItem(l.id, "甲")
        db.advance(10)

        repo.deleteItem(a.id)

        val after = repo.items.value.single { it.id == a.id }
        assertEquals(1_000_010L, after.deletedAt)
        assertEquals(1_000_010L, after.updatedAt)
    }

    @Test
    fun `对不存在的条目操作无副作用`() {
        repo.updateItem("no-such-id", title = "x")
        repo.setDone("no-such-id", true)
        repo.setProgress("no-such-id", 50)
        repo.deleteItem("no-such-id")
        assertTrue(repo.items.value.isEmpty())
    }

    // ---------- 落库与读回 ----------

    @Test
    fun `数据落库后可由新仓库实例读回`() {
        val l1 = repo.addList("工作")
        val l2 = repo.addList("生活")
        val a = repo.addItem(l1.id, "甲", dueAt = 42L, note = "备注", description = "任务详情")
        repo.setDone(a.id, true)
        repo.addProgressEntry(a.id, "进度一笔")
        repo.setProgress(a.id, 45)
        db.advance(10)
        repo.deleteItem(repo.addItem(l2.id, "乙").id)

        val reopened = db.reopenedRepository()

        assertEquals(repo.lists.value, reopened.lists.value, "清单应从库中完整读回")
        assertEquals(repo.items.value, reopened.items.value, "待办（含墓碑、描述、进度时间线与整体进度）应从库中完整读回")
    }

    // ---------- 变更回调 ----------

    @Test
    fun `onLocalChange 在每次用户变更时触发`() {
        var changes = 0
        repo.onLocalChange = { changes++ }

        val l = repo.addList("工作")
        val a = repo.addItem(l.id, "甲")
        repo.renameList(l.id, "工作A")
        repo.setDone(a.id, true)
        repo.deleteItem(a.id)

        assertEquals(5, changes, "五次用户变更各触发一次回调")
    }

    @Test
    fun `replaceAll 整体替换且不触发变更回调`() {
        val old = repo.addList("旧清单")
        repo.addItem(old.id, "旧待办")
        var changes = 0
        repo.onLocalChange = { changes++ }

        val l = TodoList(id = "l1", name = "新清单", sort = 1, createdAt = 1L, updatedAt = 1L)
        val a = TodoItem(id = "a1", listId = "l1", title = "新待办", createdAt = 1L, updatedAt = 1L)
        repo.replaceAll(listOf(l), listOf(a))

        assertEquals(listOf(l), repo.lists.value)
        assertEquals(listOf(a), repo.items.value)
        assertEquals(0, changes, "同步整体写库是引擎行为，不应再触发防抖同步")
    }

    @Test
    fun `clearAll 清空全部数据且不触发变更回调`() {
        val l = repo.addList("工作")
        repo.addItem(l.id, "甲")
        var changes = 0
        repo.onLocalChange = { changes++ }

        repo.clearAll()

        assertTrue(repo.lists.value.isEmpty())
        assertTrue(repo.items.value.isEmpty())
        assertEquals(0, changes, "清空本机数据的意图是让数据消失，不应再同步出去")
    }

    // ---------- 变更计数 ----------

    @Test
    fun `changeVersion 随用户变更单调递增`() {
        val before = repo.changeVersion
        val l = repo.addList("工作")
        val a = repo.addItem(l.id, "甲")
        repo.setDone(a.id, true)
        repo.renameList(l.id, "工作A")
        repo.deleteItem(a.id)
        repo.deleteList(l.id)

        assertTrue(repo.changeVersion > before, "六次用户变更至少各递增一次")
    }

    @Test
    fun `replaceAll 与 purgeDeleted 不递增变更计数`() {
        val l = repo.addList("工作")
        val a = repo.addItem(l.id, "甲")
        repo.deleteItem(a.id)
        repo.deleteList(l.id)
        val before = repo.changeVersion

        val synced = TodoList(id = "l1", name = "同步来的清单", sort = 1, createdAt = 1L, updatedAt = 1L)
        val syncedItem = TodoItem(id = "a1", listId = "l1", title = "同步来的待办", createdAt = 1L, updatedAt = 1L)
        repo.replaceAll(listOf(synced), listOf(syncedItem))
        repo.purgeDeleted(0L)

        assertEquals(before, repo.changeVersion, "引擎整体写库不是用户变更，不应递增，否则同步会误判并发编辑")
    }

    // ---------- 同步支撑 ----------

    @Test
    fun `currentSnapshot 携带当前全部实体`() {
        val l = repo.addList("工作")
        repo.addItem(l.id, "甲")

        val snapshot = repo.currentSnapshot()

        assertEquals(0L, snapshot.rev, "rev 由合并结果决定，快照本身恒为 0")
        assertEquals(1_000_000L, snapshot.savedAt)
        assertEquals(repo.lists.value, snapshot.lists)
        assertEquals(repo.items.value, snapshot.items)
    }

    @Test
    fun `purgeDeleted 只清理早于阈值的墓碑`() {
        val l1 = repo.addList("工作")
        val a1 = repo.addItem(l1.id, "甲")
        repo.deleteList(l1.id) // t=1_000_000
        repo.deleteItem(a1.id)
        db.advance(100)
        val l2 = repo.addList("生活")
        val a2 = repo.addItem(l2.id, "乙")
        repo.deleteList(l2.id) // t=1_000_100
        repo.deleteItem(a2.id)

        repo.purgeDeleted(1_000_050L)

        assertFalse(repo.lists.value.any { it.id == l1.id }, "早于阈值的清单墓碑应被物理删除")
        assertFalse(repo.items.value.any { it.id == a1.id }, "早于阈值的待办墓碑应被物理删除")
        assertNotNull(repo.lists.value.firstOrNull { it.id == l2.id }?.deletedAt, "阈值之后的墓碑应保留以同步删除")
        assertNotNull(repo.items.value.firstOrNull { it.id == a2.id }?.deletedAt)
    }
}
