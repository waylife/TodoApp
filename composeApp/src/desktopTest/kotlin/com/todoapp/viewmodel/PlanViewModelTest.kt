package com.todoapp.viewmodel

import com.todoapp.testing.TestDb
import com.todoapp.testing.eagerTestScope
import com.todoapp.util.Dates
import com.todoapp.util.PlanRange
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 计划页 ViewModel：范围切换、逾期置顶、清单名映射。today 可注入以便测试。 */
class PlanViewModelTest {

    private val tz = TimeZone.UTC
    private val today = LocalDate(2026, 9, 27) // 周日

    private fun due(date: LocalDate): Long = Dates.startOfDay(date, tz)

    @Test
    fun `今日范围只含今天到期的未完成条目`() = runTest {
        val db = TestDb()
        val l = db.repository.addList("工作")
        db.repository.addItem(l.id, "昨天到期", dueAt = due(LocalDate(2026, 9, 26)))
        db.repository.addItem(l.id, "今天到期", dueAt = due(LocalDate(2026, 9, 27)))
        db.repository.addItem(l.id, "明天到期", dueAt = due(LocalDate(2026, 9, 28)))
        val vm = PlanViewModel(db.repository, eagerTestScope()) { today }

        val state = vm.uiState.value
        assertEquals(listOf("今天到期"), state.groups.single().second.map { it.title })
        assertEquals(listOf("昨天到期"), state.overdue.map { it.title })
        assertEquals(today, state.today, "today 应来自注入的时钟")
        assertFalse(state.isEmpty)
    }

    @Test
    fun `切换到本周范围纳入本周各天`() = runTest {
        val db = TestDb()
        val l = db.repository.addList("工作")
        db.repository.addItem(l.id, "本周三", dueAt = due(LocalDate(2026, 9, 23)))   // 本周已过去的日子
        db.repository.addItem(l.id, "下周一", dueAt = due(LocalDate(2026, 9, 28)))   // 本周之外
        val vm = PlanViewModel(db.repository, eagerTestScope()) { today }

        vm.setRange(PlanRange.WEEK)

        val state = vm.uiState.value
        assertEquals(listOf("本周三"), state.overdue.map { it.title }, "本周内已过期的日子只进逾期分组")
        assertTrue(state.groups.isEmpty(), "逾期条目不得重复出现在日期分组")
        assertTrue("下周一" !in state.overdue.map { it.title })
    }

    @Test
    fun `两周内含下周一`() = runTest {
        val db = TestDb()
        val l = db.repository.addList("工作")
        db.repository.addItem(l.id, "下周一", dueAt = due(LocalDate(2026, 9, 28)))
        db.repository.addItem(l.id, "一个月后", dueAt = due(LocalDate(2026, 10, 27)))
        val vm = PlanViewModel(db.repository, eagerTestScope()) { today }

        vm.setRange(PlanRange.TWO_WEEKS)

        assertEquals(listOf("下周一"), vm.uiState.value.groups.single().second.map { it.title })
    }

    @Test
    fun `已完成与已删除条目不出现`() = runTest {
        val db = TestDb()
        val l = db.repository.addList("工作")
        val done = db.repository.addItem(l.id, "已完成的", dueAt = due(today))
        val deleted = db.repository.addItem(l.id, "已删除的", dueAt = due(today))
        db.repository.setDone(done.id, true)
        db.repository.deleteItem(deleted.id)
        val vm = PlanViewModel(db.repository, eagerTestScope()) { today }

        assertTrue(vm.uiState.value.isEmpty, "仅剩已完成/已删除条目时计划页应为空")
    }

    @Test
    fun `无截止日条目不进入计划页`() = runTest {
        val db = TestDb()
        val l = db.repository.addList("工作")
        db.repository.addItem(l.id, "没有日期")
        val vm = PlanViewModel(db.repository, eagerTestScope()) { today }

        assertTrue(vm.uiState.value.isEmpty)
    }

    @Test
    fun `listNames 提供清单名映射`() = runTest {
        val db = TestDb()
        val l = db.repository.addList("工作")
        db.repository.addItem(l.id, "今天到期", dueAt = due(today))
        val vm = PlanViewModel(db.repository, eagerTestScope()) { today }

        assertEquals(mapOf(l.id to "工作"), vm.uiState.value.listNames)
    }
}
