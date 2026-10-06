package com.todoapp.viewmodel

import com.todoapp.testing.TestDb
import com.todoapp.testing.eagerTestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** 跨页面编辑会话：打开、保存、删除与条目消失时的表现。 */
class EditSessionTest {

    @Test
    fun `打开后编辑面板取到对应条目`() = runTest {
        val db = TestDb()
        val session = EditSession(db.repository, eagerTestScope())
        val l = db.repository.addList("工作")
        val a = db.repository.addItem(l.id, "甲")

        session.open(a.id)

        assertEquals(a.id, session.editingItemId.value)
        assertEquals("甲", session.editingItem.value?.title)
    }

    @Test
    fun `close 后编辑面板清空`() = runTest {
        val db = TestDb()
        val session = EditSession(db.repository, eagerTestScope())
        val l = db.repository.addList("工作")
        val a = db.repository.addItem(l.id, "甲")
        session.open(a.id)

        session.close()

        assertNull(session.editingItemId.value)
        assertNull(session.editingItem.value)
    }

    @Test
    fun `save 更新仓库并关闭会话`() = runTest {
        val db = TestDb()
        val session = EditSession(db.repository, eagerTestScope())
        val l = db.repository.addList("工作")
        val a = db.repository.addItem(l.id, "甲")
        session.open(a.id)

        session.save(
            itemId = a.id,
            title = " 新标题 ",
            note = "备注",
            description = "任务详情",
            dueAt = 42L,
            dueAtChanged = true,
            listId = l.id,
        )

        val after = db.repository.items.value.single { it.id == a.id }
        assertEquals("新标题", after.title)
        assertEquals("备注", after.note)
        assertEquals("任务详情", after.description)
        assertEquals(42L, after.dueAt)
        assertNull(session.editingItemId.value, "保存后应关闭编辑面板")
    }

    @Test
    fun `addProgress 追加进度记录且不关闭会话`() = runTest {
        val db = TestDb()
        val session = EditSession(db.repository, eagerTestScope())
        val l = db.repository.addList("工作")
        val a = db.repository.addItem(l.id, "甲")
        session.open(a.id)
        db.advance(10)

        session.addProgress(a.id, " 完成调研 ")
        db.advance(10)
        session.addProgress(a.id, "开始开发")

        val after = db.repository.items.value.single { it.id == a.id }
        assertEquals(listOf("完成调研", "开始开发"), after.progressUpdates.map { it.text }, "进度按记录顺序追加")
        assertEquals(1_000_010L, after.progressUpdates[0].createdAt)
        assertEquals(1_000_020L, after.progressUpdates[1].createdAt)
        assertEquals(a.id, session.editingItemId.value, "记录进度后面板应保持打开，方便连续记录")
    }

    @Test
    fun `removeProgress 删除指定进度记录`() = runTest {
        val db = TestDb()
        val session = EditSession(db.repository, eagerTestScope())
        val l = db.repository.addList("工作")
        val a = db.repository.addItem(l.id, "甲")
        session.open(a.id)
        session.addProgress(a.id, "第一条")
        session.addProgress(a.id, "第二条")

        val stale = db.repository.items.value.single { it.id == a.id }.progressUpdates.first { it.text == "第一条" }
        session.removeProgress(a.id, stale.id)

        val after = db.repository.items.value.single { it.id == a.id }
        assertEquals(listOf("第二条"), after.progressUpdates.map { it.text })
        assertEquals(a.id, session.editingItemId.value)
    }

    @Test
    fun `setProgress 更新整体进度且不关闭会话`() = runTest {
        val db = TestDb()
        val session = EditSession(db.repository, eagerTestScope())
        val l = db.repository.addList("工作")
        val a = db.repository.addItem(l.id, "甲")
        session.open(a.id)
        db.advance(10)

        session.setProgress(a.id, 60)

        val after = db.repository.items.value.single { it.id == a.id }
        assertEquals(60, after.progressPercent, "整体进度应立即落库，不随「保存」提交")
        assertEquals(1_000_010L, after.updatedAt)
        assertEquals(a.id, session.editingItemId.value, "设置进度后面板应保持打开")
    }

    @Test
    fun `delete 删除条目并关闭会话`() = runTest {
        val db = TestDb()
        val session = EditSession(db.repository, eagerTestScope())
        val l = db.repository.addList("工作")
        val a = db.repository.addItem(l.id, "甲")
        session.open(a.id)

        session.delete(a.id)

        assertNotNull(db.repository.items.value.single { it.id == a.id }.deletedAt, "条目应被墓碑化删除")
        assertNull(session.editingItemId.value, "删除后应关闭编辑面板")
    }

    @Test
    fun `条目被外部删除后编辑面板显示为空`() = runTest {
        val db = TestDb()
        val session = EditSession(db.repository, eagerTestScope())
        val l = db.repository.addList("工作")
        val a = db.repository.addItem(l.id, "甲")
        session.open(a.id)

        db.repository.deleteItem(a.id)

        assertNull(session.editingItem.value, "已删除条目不应再出现在编辑面板里")
    }
}
