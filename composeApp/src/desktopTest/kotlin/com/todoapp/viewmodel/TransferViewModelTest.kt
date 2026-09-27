package com.todoapp.viewmodel

import com.russhwolf.settings.PreferencesSettings
import com.todoapp.data.SettingsStore
import com.todoapp.model.RemoteSnapshot
import com.todoapp.model.TodoItem
import com.todoapp.model.TodoList
import com.todoapp.sync.SyncEngine
import com.todoapp.testing.TestDb
import com.todoapp.testing.eagerTestScope
import com.todoapp.transfer.DocumentTransfer
import com.todoapp.transfer.ImportMode
import com.todoapp.transfer.PickedDocument
import com.todoapp.transfer.TodoTransfer
import com.todoapp.transfer.TransferOutcome
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import java.util.prefs.Preferences
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 文件选择器假实现：按预设结果返回，并记录收到的内容。 */
private class FakeDocumentTransfer : DocumentTransfer {
    var saveOutcome: TransferOutcome<String> = TransferOutcome.Ok("/tmp/todoapp-backup.json")
    var openOutcome: TransferOutcome<PickedDocument> = TransferOutcome.Ok(PickedDocument("backup.json", "{}"))
    var saveGate: CompletableDeferred<Unit>? = null
    var saveCalls = 0
    var openCalls = 0
    var lastSuggestedName: String? = null
    var lastContent: String? = null

    override suspend fun saveJson(suggestedName: String, content: String): TransferOutcome<String> {
        saveCalls++
        lastSuggestedName = suggestedName
        lastContent = content
        saveGate?.await()
        return saveOutcome
    }

    override suspend fun openJson(): TransferOutcome<PickedDocument> {
        openCalls++
        return openOutcome
    }
}

/** 导入导出 ViewModel：导出文案、解析与确认流程、合并/覆盖语义、忙碌保护。 */
class TransferViewModelTest {

    /** 未配置 WebDAV 的环境：confirmImport 里的 scheduleSync 应安全空转。 */
    private fun TestDb.newViewModel(scope: CoroutineScope): Pair<TransferViewModel, FakeDocumentTransfer> {
        val settings = SettingsStore(
            PreferencesSettings(Preferences.userRoot().node("/com/todoapp/test/tvm-${System.nanoTime()}")),
        )
        val engine = SyncEngine(repository, settings, HttpClient(CIO), scope)
        val vm = TransferViewModel(repository, engine, scope) { 5_000_000L }
        return vm to FakeDocumentTransfer()
    }

    // ---------- 导出 ----------

    @Test
    fun `导出成功后提示数量与位置`() = runTest {
        val db = TestDb()
        val (vm, fake) = db.newViewModel(eagerTestScope())
        val l = db.repository.addList("工作")
        db.repository.addItem(l.id, "甲")
        db.repository.addItem(l.id, "乙")

        vm.export(fake)

        assertEquals("已导出 1 个清单、2 条待办\n位置：/tmp/todoapp-backup.json", vm.state.value.message)
        assertFalse(vm.state.value.isError)
        assertFalse(vm.state.value.busy)
    }

    @Test
    fun `导出含墓碑时在提示中注明`() = runTest {
        val db = TestDb()
        val (vm, fake) = db.newViewModel(eagerTestScope())
        val l = db.repository.addList("工作")
        db.repository.addItem(l.id, "甲")
        db.repository.deleteList(l.id)

        vm.export(fake)

        // 统计按「可见条目」计数：清单与待办都已墓碑化，可见数为 0，另注明 2 条已删除记录
        assertEquals("已导出 0 个清单、0 条待办（含 2 条已删除记录）\n位置：/tmp/todoapp-backup.json", vm.state.value.message)
    }

    @Test
    fun `导出内容与快照同构并透传建议文件名`() = runTest {
        val db = TestDb()
        val (vm, fake) = db.newViewModel(eagerTestScope())
        val l = db.repository.addList("工作")
        db.repository.addItem(l.id, "甲", dueAt = 42L)

        vm.export(fake)

        assertEquals(TodoTransfer.suggestedFileName(5_000_000L), fake.lastSuggestedName)
        val decoded = TodoTransfer.decode(fake.lastContent!!).getOrThrow()
        assertEquals(db.repository.currentSnapshot().lists, decoded.lists, "导出内容应与当前快照一致")
        assertEquals(db.repository.currentSnapshot().items, decoded.items)
    }

    @Test
    fun `用户取消导出则无提示`() = runTest {
        val db = TestDb()
        val (vm, fake) = db.newViewModel(eagerTestScope())
        fake.saveOutcome = TransferOutcome.Cancelled

        vm.export(fake)

        assertEquals(TransferUiState(), vm.state.value)
    }

    @Test
    fun `导出失败提示错误`() = runTest {
        val db = TestDb()
        val (vm, fake) = db.newViewModel(eagerTestScope())
        fake.saveOutcome = TransferOutcome.Failed("磁盘已满")

        vm.export(fake)

        assertEquals("导出失败：磁盘已满", vm.state.value.message)
        assertTrue(vm.state.value.isError)
    }

    @Test
    fun `导出进行中时重复点击被忽略`() = runTest {
        val db = TestDb()
        val (vm, fake) = db.newViewModel(eagerTestScope())
        val gate = CompletableDeferred<Unit>()
        fake.saveGate = gate

        vm.export(fake) // 在 gate 上挂起，busy 应已同步置位
        assertTrue(vm.state.value.busy)

        vm.export(fake)
        assertEquals(1, fake.saveCalls, "忙碌时应忽略新的导出请求")

        gate.complete(Unit)
        assertFalse(vm.state.value.busy)
        assertNotNull(vm.state.value.message)
    }

    @Test
    fun `导出流程抛异常时复位 busy 并提示`() = runTest {
        val db = TestDb()
        val (vm, _) = db.newViewModel(eagerTestScope())
        val broken = object : DocumentTransfer {
            override suspend fun saveJson(suggestedName: String, content: String): TransferOutcome<String> =
                throw IllegalStateException("保存器崩溃")

            override suspend fun openJson(): TransferOutcome<PickedDocument> =
                throw IllegalStateException("选择器崩溃")
        }

        vm.export(broken)

        assertFalse(vm.state.value.busy, "异常路径必须复位 busy，否则导出按钮永久禁用")
        assertTrue(vm.state.value.isError)
        assertEquals("导出失败：保存器崩溃", vm.state.value.message)

        vm.pickImportFile(broken)

        assertFalse(vm.state.value.busy, "读取异常路径同样必须复位 busy")
        assertEquals("读取失败：选择器崩溃", vm.state.value.message)
    }

    @Test
    fun `确认导入抛异常时提示错误且收起确认框`() = runTest {
        val db = TestDb()
        val settings = SettingsStore(
            PreferencesSettings(Preferences.userRoot().node("/com/todoapp/test/tvm-${System.nanoTime()}")),
        )
        val engine = SyncEngine(db.repository, settings, HttpClient(CIO), eagerTestScope())
        var clockBoom = false
        val vm = TransferViewModel(
            db.repository,
            engine,
            eagerTestScope(),
            clock = { if (clockBoom) throw IllegalStateException("时钟崩溃") else 5_000_000L },
        )
        val backup = RemoteSnapshot(
            lists = listOf(TodoList(id = "bl", name = "备份清单", createdAt = 0, updatedAt = 1)),
        )

        vm.previewBackup("backup.json", TodoTransfer.encode(backup))
        assertNotNull(vm.state.value.pending)
        clockBoom = true
        vm.confirmImport(ImportMode.MERGE)

        assertTrue(vm.state.value.isError)
        assertEquals("导入失败：时钟崩溃", vm.state.value.message)
        assertNull(vm.state.value.pending, "失败后应收起确认框，避免用户对着已失效的预览重复确认")
    }

    // ---------- 解析与预览 ----------

    @Test
    fun `选择非法文件提示无法导入`() = runTest {
        val db = TestDb()
        val (vm, fake) = db.newViewModel(eagerTestScope())
        fake.openOutcome = TransferOutcome.Ok(PickedDocument("backup.json", "这不是 JSON"))

        vm.pickImportFile(fake)

        assertTrue(vm.state.value.message!!.startsWith("无法导入"), "实际：${vm.state.value.message}")
        assertTrue(vm.state.value.isError)
        assertNull(vm.state.value.pending)
    }

    @Test
    fun `读取失败提示错误`() = runTest {
        val db = TestDb()
        val (vm, fake) = db.newViewModel(eagerTestScope())
        fake.openOutcome = TransferOutcome.Failed("文件已被移动")

        vm.pickImportFile(fake)

        assertEquals("读取失败：文件已被移动", vm.state.value.message)
        assertTrue(vm.state.value.isError)
    }

    @Test
    fun `用户取消选择则回到初始状态`() = runTest {
        val db = TestDb()
        val (vm, fake) = db.newViewModel(eagerTestScope())
        fake.openOutcome = TransferOutcome.Cancelled

        vm.pickImportFile(fake)

        assertEquals(TransferUiState(), vm.state.value)
    }

    @Test
    fun `预览备份后未确认前不动本机数据`() = runTest {
        val db = TestDb()
        val (vm, _) = db.newViewModel(eagerTestScope())
        val l = db.repository.addList("本地清单")
        db.repository.addItem(l.id, "本地待办")
        val backup = RemoteSnapshot(
            lists = listOf(TodoList(id = "bl", name = "备份清单", createdAt = 0, updatedAt = 1)),
            items = listOf(TodoItem(id = "ba", listId = "bl", title = "备份待办", createdAt = 0, updatedAt = 1)),
        )

        vm.previewBackup("backup.json", TodoTransfer.encode(backup))

        assertNotNull(vm.state.value.pending, "解析成功应弹出确认框")
        assertEquals(1, db.repository.lists.value.size, "未确认前不得写库")
        assertEquals(1, db.repository.items.value.size)
    }

    // ---------- 确认导入 ----------

    @Test
    fun `合并导入 本地较新者胜且新增条目进入`() = runTest {
        val db = TestDb()
        val (vm, _) = db.newViewModel(eagerTestScope())
        val l = db.repository.addList("工作")
        val a = db.repository.addItem(l.id, "本地待办")
        val backup = RemoteSnapshot(
            lists = listOf(TodoList(id = "bl", name = "备份清单", createdAt = 0, updatedAt = 1)),
            items = listOf(
                TodoItem(id = "ba", listId = "bl", title = "备份新增", createdAt = 0, updatedAt = 1),
                a.copy(title = "备份旧标题", updatedAt = 1L), // 比本地旧
            ),
        )

        vm.previewBackup("backup.json", TodoTransfer.encode(backup))
        vm.confirmImport(ImportMode.MERGE)

        val items = db.repository.items.value
        assertEquals("本地待办", items.single { it.id == a.id }.title, "同 id 冲突本地较新者应胜出")
        assertNotNull(items.firstOrNull { it.id == "ba" }, "备份新增条目应进入本机")
        assertEquals(2, db.repository.lists.value.size)
        assertTrue(vm.state.value.message!!.startsWith("已合并导入 1 个清单、2 条待办"))
        assertNull(vm.state.value.pending, "导入后确认框状态应清空")
    }

    @Test
    fun `覆盖导入整体替换本机数据`() = runTest {
        val db = TestDb()
        val (vm, _) = db.newViewModel(eagerTestScope())
        val l = db.repository.addList("本地清单")
        db.repository.addItem(l.id, "本地待办")
        val backupList = TodoList(id = "bl", name = "备份清单", createdAt = 0, updatedAt = 1)
        val backupItem = TodoItem(id = "ba", listId = "bl", title = "备份待办", createdAt = 0, updatedAt = 1)

        vm.previewBackup("backup.json", TodoTransfer.encode(RemoteSnapshot(lists = listOf(backupList), items = listOf(backupItem))))
        vm.confirmImport(ImportMode.REPLACE)

        assertEquals(listOf(backupList), db.repository.lists.value, "本机清单应被备份整体替换")
        assertEquals(listOf(backupItem), db.repository.items.value)
        assertTrue(vm.state.value.message!!.startsWith("已覆盖导入"))
    }

    @Test
    fun `没有待确认备份时确认导入是无操作`() = runTest {
        val db = TestDb()
        val (vm, _) = db.newViewModel(eagerTestScope())

        vm.confirmImport(ImportMode.MERGE)

        assertEquals(TransferUiState(), vm.state.value)
        assertTrue(db.repository.items.value.isEmpty())
    }

    @Test
    fun `dismissImport 丢弃待确认备份`() = runTest {
        val db = TestDb()
        val (vm, _) = db.newViewModel(eagerTestScope())
        vm.previewBackup(
            "backup.json",
            TodoTransfer.encode(
                RemoteSnapshot(lists = listOf(TodoList(id = "bl", name = "备份清单", createdAt = 0, updatedAt = 1))),
            ),
        )

        vm.dismissImport()

        assertNull(vm.state.value.pending)
        vm.confirmImport(ImportMode.MERGE)
        assertTrue(db.repository.lists.value.isEmpty(), "丢弃后确认导入应是无操作")
    }
}
