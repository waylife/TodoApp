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

        session.save(itemId = a.id, title = " 新标题 ", note = "备注", dueAt = 42L, dueAtChanged = true, listId = l.id)

        val after = db.repository.items.value.single { it.id == a.id }
        assertEquals("新标题", after.title)
        assertEquals("备注", after.note)
        assertEquals(42L, after.dueAt)
        assertNull(session.editingItemId.value, "保存后应关闭编辑面板")
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
