package com.todoapp.viewmodel

import com.todoapp.testing.TestDb
import com.todoapp.testing.eagerTestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 清单页 ViewModel：过滤、搜索、排序、清单切换与添加待办的目标清单选择。 */
class TodosViewModelTest {

    // ---------- 添加 ----------

    @Test
    fun `添加待办时无清单则自动建默认清单`() = runTest {
        val db = TestDb()
        val vm = TodosViewModel(db.repository, eagerTestScope())

        vm.addTodo("买牛奶")

        val list = db.repository.lists.value.single()
        assertEquals("默认", list.name)
        val item = db.repository.items.value.single()
        assertEquals("买牛奶", item.title)
        assertEquals(list.id, item.listId)
    }

    @Test
    fun `添加待办进入选中的清单`() = runTest {
        val db = TestDb()
        val vm = TodosViewModel(db.repository, eagerTestScope())
        val l1 = db.repository.addList("工作")
        val l2 = db.repository.addList("生活")

        vm.selectList(l2.id)
        vm.addTodo("买菜")

        assertEquals(l2.id, db.repository.items.value.single().listId)
    }

    @Test
    fun `添加待办未选中清单时进入第一张清单`() = runTest {
        val db = TestDb()
        val vm = TodosViewModel(db.repository, eagerTestScope())
        val l1 = db.repository.addList("工作")
        db.repository.addList("生活")

        vm.addTodo("写周报")

        assertEquals(l1.id, db.repository.items.value.single().listId)
    }

    @Test
    fun `空白标题不添加`() = runTest {
        val db = TestDb()
        val vm = TodosViewModel(db.repository, eagerTestScope())

        vm.addTodo("   ")

        assertTrue(db.repository.items.value.isEmpty())
        assertTrue(db.repository.lists.value.isEmpty())
    }

    @Test
    fun `选中清单被同步删除后 新增待办回退到存活清单`() = runTest {
        val db = TestDb()
        val vm = TodosViewModel(db.repository, eagerTestScope())
        val l1 = db.repository.addList("工作")
        val l2 = db.repository.addList("生活")
        vm.selectList(l1.id)

        // 模拟远端墓碑经同步写回仓库：绕过 ViewModel 的 deleteList，选中态不会被重置
        db.repository.deleteList(l1.id)

        vm.addTodo("新任务")

        val item = db.repository.items.value.single { it.title == "新任务" }
        assertEquals(l2.id, item.listId, "应落到仍存活的清单，而不是已删除的清单")
    }

    // ---------- 过滤与搜索 ----------

    @Test
    fun `按清单过滤`() = runTest {
        val db = TestDb()
        val vm = TodosViewModel(db.repository, eagerTestScope())
        val l1 = db.repository.addList("工作")
        val l2 = db.repository.addList("生活")
        db.repository.addItem(l1.id, "写周报")
        db.repository.addItem(l2.id, "买菜")

        vm.selectList(l1.id)
        assertEquals(listOf("写周报"), vm.uiState.value.activeItems.map { it.title })

        vm.selectList(null)
        assertEquals(2, vm.uiState.value.activeItems.size)
    }

    @Test
    fun `搜索按标题与备注匹配且忽略大小写与首尾空白`() = runTest {
        val db = TestDb()
        val vm = TodosViewModel(db.repository, eagerTestScope())
        val l = db.repository.addList("清单")
        db.repository.addItem(l.id, "Buy Milk")
        db.repository.addItem(l.id, "pay bill", note = "记着买 MILK")
        db.repository.addItem(l.id, "写周报")

        vm.setSearchQuery("  milk ")

        val titles = vm.uiState.value.activeItems.map { it.title }
        assertEquals(setOf("Buy Milk", "pay bill"), titles.toSet(), "标题或备注命中即保留")

        vm.setSearchQuery("不存在")
        assertTrue(vm.uiState.value.activeItems.isEmpty())
    }

    @Test
    fun `搜索同样作用于已完成分区`() = runTest {
        val db = TestDb()
        val vm = TodosViewModel(db.repository, eagerTestScope())
        val l = db.repository.addList("清单")
        val a = db.repository.addItem(l.id, "买牛奶")
        db.repository.addItem(l.id, "写周报")
        db.repository.setDone(a.id, true)

        vm.setSearchQuery("牛奶")

        assertTrue(vm.uiState.value.activeItems.isEmpty())
        assertEquals(listOf("买牛奶"), vm.uiState.value.doneItems.map { it.title })
    }

    @Test
    fun `搜索命中任务描述与进度记录`() = runTest {
        val db = TestDb()
        val vm = TodosViewModel(db.repository, eagerTestScope())
        val l = db.repository.addList("清单")
        val withDesc = db.repository.addItem(l.id, "装修", description = "重点是厨房防水")
        db.repository.addItem(l.id, "项目启动")
        db.repository.addProgressEntry(withDesc.id, "水电改造完成")

        vm.setSearchQuery("防水")
        assertEquals(listOf("装修"), vm.uiState.value.activeItems.map { it.title }, "描述命中应保留")

        vm.setSearchQuery("水电")
        assertEquals(listOf("装修"), vm.uiState.value.activeItems.map { it.title }, "进度记录命中也应保留")
    }

    @Test
    fun `已删除与已完成待办不进入未完成分区`() = runTest {
        val db = TestDb()
        val vm = TodosViewModel(db.repository, eagerTestScope())
        val l = db.repository.addList("清单")
        val a = db.repository.addItem(l.id, "完成的")
        val b = db.repository.addItem(l.id, "删除的")
        db.repository.setDone(a.id, true)
        db.repository.deleteItem(b.id)

        val titles = vm.uiState.value.activeItems.map { it.title }
        assertTrue("完成的" !in titles)
        assertTrue("删除的" !in titles)
    }

    // ---------- 排序 ----------

    @Test
    fun `未完成待办按截止日排序 同截止日新创建者靠前 无日期者靠后`() = runTest {
        val db = TestDb()
        val vm = TodosViewModel(db.repository, eagerTestScope())
        val l = db.repository.addList("清单")
        db.repository.addItem(l.id, "无日期-旧")          // t=1_000_000
        db.advance(10)
        db.repository.addItem(l.id, "后天", dueAt = 6_000_000L)   // t=1_000_010
        db.advance(10)
        db.repository.addItem(l.id, "明天", dueAt = 5_000_000L)   // t=1_000_020
        db.advance(10)
        db.repository.addItem(l.id, "无日期-新")          // t=1_000_030
        db.advance(10)
        db.repository.addItem(l.id, "后天-新", dueAt = 6_000_000L) // t=1_000_040

        assertEquals(
            listOf("明天", "后天-新", "后天", "无日期-新", "无日期-旧"),
            vm.uiState.value.activeItems.map { it.title },
        )
    }

    @Test
    fun `已完成待办按更新时间倒序单独分组`() = runTest {
        val db = TestDb()
        val vm = TodosViewModel(db.repository, eagerTestScope())
        val l = db.repository.addList("清单")
        val a = db.repository.addItem(l.id, "甲")
        db.advance(10)
        val b = db.repository.addItem(l.id, "乙")
        db.repository.setDone(a.id, true)
        db.advance(10)
        db.repository.setDone(b.id, true) // b 更晚完成

        assertEquals(listOf("乙", "甲"), vm.uiState.value.doneItems.map { it.title })
        assertTrue(vm.uiState.value.activeItems.isEmpty())
    }

    // ---------- 清单操作 ----------

    @Test
    fun `清单按 sort 排序展示`() = runTest {
        val db = TestDb()
        val vm = TodosViewModel(db.repository, eagerTestScope())
        val l1 = db.repository.addList("第一")
        val l2 = db.repository.addList("第二")

        assertEquals(listOf(l1.id, l2.id), vm.uiState.value.lists.map { it.id })
    }

    @Test
    fun `删除选中的清单后取消选中`() = runTest {
        val db = TestDb()
        val vm = TodosViewModel(db.repository, eagerTestScope())
        val l = db.repository.addList("工作")
        vm.selectList(l.id)

        vm.deleteList(l.id)

        assertEquals(null, vm.uiState.value.selectedListId)
    }

    @Test
    fun `删除未选中的清单不影响选中态`() = runTest {
        val db = TestDb()
        val vm = TodosViewModel(db.repository, eagerTestScope())
        val l1 = db.repository.addList("工作")
        val l2 = db.repository.addList("生活")
        vm.selectList(l1.id)

        vm.deleteList(l2.id)

        assertEquals(l1.id, vm.uiState.value.selectedListId)
    }

    // ---------- 勾选 ----------

    @Test
    fun `toggleDone 在未完成与已完成分区之间移动`() = runTest {
        val db = TestDb()
        val vm = TodosViewModel(db.repository, eagerTestScope())
        val l = db.repository.addList("清单")
        val a = db.repository.addItem(l.id, "甲")

        vm.toggleDone(a)
        assertTrue(vm.uiState.value.activeItems.isEmpty())
        assertEquals(listOf("甲"), vm.uiState.value.doneItems.map { it.title })

        vm.toggleDone(vm.uiState.value.doneItems.first())
        assertEquals(listOf("甲"), vm.uiState.value.activeItems.map { it.title })
        assertTrue(vm.uiState.value.doneItems.isEmpty())
    }
}
