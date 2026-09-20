package com.todoapp.transfer

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.russhwolf.settings.PreferencesSettings
import com.todoapp.data.SettingsStore
import com.todoapp.data.TodoRepository
import com.todoapp.data.WebDavConfig
import com.todoapp.db.AppDatabase
import com.todoapp.model.RemoteSnapshot
import com.todoapp.model.TodoItem
import com.todoapp.model.TodoList
import com.todoapp.sync.FakeWebDavServer
import com.todoapp.sync.SyncEngine
import com.todoapp.sync.SyncStatus
import com.todoapp.viewmodel.TransferViewModel
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import java.util.prefs.Preferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 用内存假实现替代系统文件选择器，让整个导入导出流程可以在测试里跑完。 */
private class FakeDocumentTransfer(
    private val saveResult: TransferOutcome<String> = TransferOutcome.Ok("memory://backup.json"),
    private val openResult: TransferOutcome<PickedDocument> = TransferOutcome.Cancelled,
) : DocumentTransfer {

    /** 记录写出去的文件（建议文件名 → 内容）。 */
    val saved = mutableListOf<Pair<String, String>>()

    override suspend fun saveJson(suggestedName: String, content: String): TransferOutcome<String> {
        saved += suggestedName to content
        return saveResult
    }

    override suspend fun openJson(): TransferOutcome<PickedDocument> = openResult
}

/**
 * 导入导出端到端：真实的内存数据库 + 真实的同步引擎，只把「文件选择器」换成内存实现。
 *
 * 协程作用域用 [Dispatchers.Unconfined]：假选择器没有真实挂起点，导出/导入会在
 * 调用返回前就跑完，断言因此是确定性的；只有需要等网络的那条用例才轮询状态。
 */
class DataTransferIntegrationTest {

    private lateinit var scope: CoroutineScope
    private lateinit var repository: TodoRepository
    private lateinit var settingsStore: SettingsStore
    private lateinit var syncEngine: SyncEngine
    private lateinit var viewModel: TransferViewModel
    private var testTime = 1_000_000L

    @BeforeTest
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        AppDatabase.Schema.create(driver)
        repository = TodoRepository(AppDatabase(driver), clock = { testTime })
        val node = Preferences.userRoot().node("/com/todoapp/test/transfer-${System.nanoTime()}")
        settingsStore = SettingsStore(PreferencesSettings(node))
        syncEngine = SyncEngine(
            repository = repository,
            settingsStore = settingsStore,
            httpClient = HttpClient(CIO),
            scope = scope,
            clock = { testTime },
        )
        viewModel = TransferViewModel(repository, syncEngine, scope, clock = { testTime })
    }

    @AfterTest
    fun tearDown() {
        scope.cancel()
    }

    // ---------- 导出 ----------

    @Test
    fun `导出写出可再次导入的备份`() {
        val list = repository.addList("工作")
        repository.addItem(list.id, "写周报")
        val transfer = FakeDocumentTransfer()

        viewModel.export(transfer)

        val (fileName, content) = transfer.saved.single()
        assertTrue(fileName.startsWith("todoapp-") && fileName.endsWith(".json"), "实际文件名：$fileName")
        val state = viewModel.state.value
        assertFalse(state.isError, "导出不应报错，实际：${state.message}")
        assertEquals("已导出 1 个清单、1 条待办\n位置：memory://backup.json", state.message)

        // 导出的内容必须能被自己读回来
        val decoded = TodoTransfer.decode(content).getOrThrow()
        assertEquals(listOf("工作"), decoded.lists.filter { it.deletedAt == null }.map { it.name })
        assertEquals(listOf("写周报"), decoded.items.filter { it.deletedAt == null }.map { it.title })
    }

    @Test
    fun `导出会带上已删除记录并提示数量`() {
        val list = repository.addList("工作")
        val item = repository.addItem(list.id, "要删掉的")
        repository.deleteItem(item.id)

        val transfer = FakeDocumentTransfer()
        viewModel.export(transfer)

        val content = transfer.saved.single().second
        val decoded = TodoTransfer.decode(content).getOrThrow()
        assertTrue(decoded.items.single().deletedAt != null, "备份应保留墓碑，否则换机后删除动作会丢失")
        assertTrue(viewModel.state.value.message!!.contains("含 1 条已删除记录"), viewModel.state.value.message!!)
    }

    @Test
    fun `导出取消时不报错也不留提示`() {
        val transfer = FakeDocumentTransfer(saveResult = TransferOutcome.Cancelled)

        viewModel.export(transfer)

        val state = viewModel.state.value
        assertFalse(state.isError)
        assertNull(state.message)
        assertFalse(state.busy)
    }

    @Test
    fun `导出失败时给出可读原因`() {
        val transfer = FakeDocumentTransfer(saveResult = TransferOutcome.Failed("磁盘已满"))

        viewModel.export(transfer)

        val state = viewModel.state.value
        assertTrue(state.isError)
        assertEquals("导出失败：磁盘已满", state.message)
    }

    // ---------- 导入 ----------

    @Test
    fun `合并导入并入备份数据并保留本机独有数据`() {
        val local = repository.addList("本地清单")
        repository.addItem(local.id, "本地任务")
        val transfer = FakeDocumentTransfer(
            openResult = TransferOutcome.Ok(
                PickedDocument("backup.json", backupOf(lists = listOf(backupList()), items = listOf(backupItem()))),
            ),
        )

        viewModel.pickImportFile(transfer)
        val preview = assertNotNull(viewModel.state.value.pending, "解析成功后应进入待确认状态")
        assertEquals("backup.json", preview.fileName)
        assertEquals(1, preview.stats.lists)
        assertEquals(1, preview.stats.items)
        // 1 个新清单 + 1 条新待办
        assertEquals(2, preview.mergeImpact.added)

        viewModel.confirmImport(ImportMode.MERGE)

        assertEquals(
            setOf("本地清单", "备份清单"),
            repository.lists.value.filter { it.deletedAt == null }.map { it.name }.toSet(),
        )
        assertEquals(
            setOf("本地任务", "备份任务"),
            repository.items.value.filter { it.deletedAt == null }.map { it.title }.toSet(),
        )
        assertNull(viewModel.state.value.pending)
        assertTrue(viewModel.state.value.message!!.startsWith("已合并导入"), viewModel.state.value.message!!)
    }

    @Test
    fun `覆盖导入替换本机数据`() {
        val local = repository.addList("本地清单")
        repository.addItem(local.id, "本地任务")
        val transfer = FakeDocumentTransfer(
            openResult = TransferOutcome.Ok(
                PickedDocument("backup.json", backupOf(lists = listOf(backupList()), items = listOf(backupItem()))),
            ),
        )

        viewModel.pickImportFile(transfer)
        viewModel.confirmImport(ImportMode.REPLACE)

        assertEquals(listOf("备份清单"), repository.lists.value.filter { it.deletedAt == null }.map { it.name })
        assertEquals(listOf("备份任务"), repository.items.value.filter { it.deletedAt == null }.map { it.title })
        assertTrue(viewModel.state.value.message!!.startsWith("已覆盖导入"))
    }

    @Test
    fun `解析失败时不改动本机数据也不进入确认状态`() {
        val local = repository.addList("本地清单")
        repository.addItem(local.id, "本地任务")
        val transfer = FakeDocumentTransfer(
            openResult = TransferOutcome.Ok(PickedDocument("坏文件.json", "这不是 json")),
        )

        viewModel.pickImportFile(transfer)

        val state = viewModel.state.value
        assertNull(state.pending)
        assertTrue(state.isError)
        assertEquals("无法导入：不是合法的 JSON 文件", state.message)
        assertEquals(listOf("本地任务"), repository.items.value.filter { it.deletedAt == null }.map { it.title })
    }

    @Test
    fun `取消选择文件不留提示`() {
        val transfer = FakeDocumentTransfer(openResult = TransferOutcome.Cancelled)

        viewModel.pickImportFile(transfer)

        val state = viewModel.state.value
        assertFalse(state.isError)
        assertNull(state.message)
        assertFalse(state.busy)
    }

    @Test
    fun `关闭确认框后不会再把备份写进本机`() {
        val local = repository.addList("本地清单")
        repository.addItem(local.id, "本地任务")
        val transfer = FakeDocumentTransfer(
            openResult = TransferOutcome.Ok(
                PickedDocument("backup.json", backupOf(lists = listOf(backupList()), items = listOf(backupItem()))),
            ),
        )

        viewModel.pickImportFile(transfer)
        assertNotNull(viewModel.state.value.pending)
        viewModel.dismissImport()

        assertNull(viewModel.state.value.pending)
        assertEquals(listOf("本地任务"), repository.items.value.filter { it.deletedAt == null }.map { it.title })
        // 关掉之后再点确认（模拟误触）不应写入任何东西
        viewModel.confirmImport(ImportMode.MERGE)
        assertEquals(listOf("本地任务"), repository.items.value.filter { it.deletedAt == null }.map { it.title })
    }

    @Test
    fun `导入的数据会被同步到远端`() {
        val server = FakeWebDavServer()
        server.start()
        try {
            settingsStore.saveConfig(
                WebDavConfig(
                    serverUrl = "http://127.0.0.1:${server.port}",
                    username = "user",
                    password = "pass",
                    remoteDir = "dav/ToDoApp",
                ),
            )
            val transfer = FakeDocumentTransfer(
                openResult = TransferOutcome.Ok(
                    PickedDocument("backup.json", backupOf(lists = listOf(backupList()), items = listOf(backupItem()))),
                ),
            )

            viewModel.pickImportFile(transfer)
            viewModel.confirmImport(ImportMode.MERGE)

            awaitSync()

            val remote = assertNotNull(
                server.fileContent("dav/ToDoApp/todoapp.json"),
                "导入后应主动推一次同步，否则导入的数据只留在本机",
            )
            val remoteSnapshot = TodoTransfer.decode(remote).getOrThrow()
            assertEquals(
                listOf("备份任务"),
                remoteSnapshot.items.filter { it.deletedAt == null }.map { it.title },
            )
        } finally {
            server.stop()
        }
    }

    // ---------- 辅助 ----------

    private fun backupList() = TodoList(id = "l9", name = "备份清单", sort = 1, createdAt = 500, updatedAt = 500)

    private fun backupItem() = TodoItem(
        id = "i9",
        listId = "l9",
        title = "备份任务",
        createdAt = 500,
        updatedAt = 500,
    )

    private fun backupOf(lists: List<TodoList> = emptyList(), items: List<TodoItem> = emptyList()): String =
        TodoTransfer.encode(RemoteSnapshot(lists = lists, items = items, savedAt = 500))

    private fun awaitSync(timeoutMillis: Long = 15_000) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (syncEngine.status.value is SyncStatus.Success) return
            Thread.sleep(20)
        }
        throw AssertionError("等待同步超时，当前状态：${syncEngine.status.value}")
    }
}
