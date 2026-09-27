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
    fun `setDone 更新完成状态与时间戳`() {
        val l = repo.addList("工作")
        val a = repo.addItem(l.id, "甲")
        db.advance(10)

        repo.setDone(a.id, true)

        val after = repo.items.value.single { it.id == a.id }
        assertTrue(after.done)
        assertEquals(1_000_010L, after.updatedAt)
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
        repo.deleteItem("no-such-id")
        assertTrue(repo.items.value.isEmpty())
    }

    // ---------- 落库与读回 ----------

    @Test
    fun `数据落库后可由新仓库实例读回`() {
        val l1 = repo.addList("工作")
        val l2 = repo.addList("生活")
        val a = repo.addItem(l1.id, "甲", dueAt = 42L, note = "备注")
        repo.setDone(a.id, true)
        db.advance(10)
        repo.deleteItem(repo.addItem(l2.id, "乙").id)

        val reopened = db.reopenedRepository()

        assertEquals(repo.lists.value, reopened.lists.value, "清单应从库中完整读回")
        assertEquals(repo.items.value, reopened.items.value, "待办（含墓碑与完成标记）应从库中完整读回")
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
