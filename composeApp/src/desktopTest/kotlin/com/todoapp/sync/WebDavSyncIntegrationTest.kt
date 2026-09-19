package com.todoapp.sync

import com.russhwolf.settings.PreferencesSettings
import com.todoapp.data.SettingsStore
import com.todoapp.data.TodoRepository
import com.todoapp.data.WebDavConfig
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.todoapp.db.AppDatabase
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import java.util.prefs.Preferences
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 端到端同步闭环：两台「设备」（各自独立内存库 + 同步引擎）通过进程内迷你
 * WebDAV 服务互相同步，验证首次上传、拉取、增量修改、冲突收敛与删除传播。
 */
class WebDavSyncIntegrationTest {

    private lateinit var server: FakeWebDavServer
    private lateinit var scope: CoroutineScope
    private var testTime = 1_000_000L

    @BeforeTest
    fun setUp() {
        server = FakeWebDavServer()
        server.start()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    @AfterTest
    fun tearDown() {
        scope.cancel()
        server.stop()
    }

    private fun newDevice(name: String): Triple<TodoRepository, SyncEngine, SettingsStore> {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        AppDatabase.Schema.create(driver)
        val repository = TodoRepository(AppDatabase(driver), clock = { testTime })
        val settingsNode = Preferences.userRoot().node("/com/todoapp/test/${name}-${System.nanoTime()}")
        val settingsStore = SettingsStore(PreferencesSettings(settingsNode))
        settingsStore.saveConfig(
            WebDavConfig(
                serverUrl = "http://127.0.0.1:${server.port}",
                username = "user",
                password = "pass",
                remoteDir = "dav/ToDoApp",
            ),
        )
        val engine = SyncEngine(
            repository = repository,
            settingsStore = settingsStore,
            httpClient = HttpClient(CIO),
            scope = scope,
            clock = { testTime },
        )
        println("device $name ready")
        return Triple(repository, engine, settingsStore)
    }

    private suspend fun sync(engine: SyncEngine) {
        val job: Job? = engine.syncNow()
        job?.let { kotlinx.coroutines.withTimeout(15_000) { it.join() } }
        val status = engine.status.value
        assertTrue(status is SyncStatus.Success, "同步应当成功，实际：$status")
    }

    @Test
    fun `首次同步上传本地数据`() = runBlocking {
        val (repoA, engineA, _) = newDevice("A")
        val list = repoA.addList("工作")
        repoA.addItem(list.id, "写周报")
        testTime += 100

        sync(engineA)
        assertTrue(server.fileExists("dav/ToDoApp/todoapp.json"), "远程应出现快照文件")
        assertTrue(server.fileContent("dav/ToDoApp/todoapp.json")!!.contains("写周报"))
    }

    @Test
    fun `多级远程目录自动创建`() = runBlocking {
        val (repoA, engineA, _) = newDevice("A")
        val list = repoA.addList("工作")
        repoA.addItem(list.id, "任务")
        testTime += 100

        sync(engineA)
        assertTrue(server.dirCreated("dav"), "父目录应被逐级创建")
        assertTrue(server.dirCreated("dav/ToDoApp"))
        assertTrue(server.fileExists("dav/ToDoApp/todoapp.json"))
    }

    @Test
    fun `两台设备互相同步`() = runBlocking {
        val (repoA, engineA, _) = newDevice("A")
        val (repoB, engineB, _) = newDevice("B")

        // A 建数据并上传
        val list = repoA.addList("工作")
        val item = repoA.addItem(list.id, "写周报")
        testTime += 100
        sync(engineA)

        // B 拉取
        sync(engineB)
        assertEquals(1, repoB.items.value.size)
        assertEquals("写周报", repoB.items.value.first().title)
        assertEquals(1, repoB.lists.value.size)

        // B 勾选完成并上传
        repoB.setDone(item.id, true)
        testTime += 100
        sync(engineB)

        // A 再次拉取，看到完成状态
        sync(engineA)
        assertTrue(repoA.items.value.first { it.id == item.id }.done, "A 应看到 B 的勾选")
    }

    @Test
    fun `并发修改按最后写入者收敛`() = runBlocking {
        val (repoA, engineA, _) = newDevice("A")
        val (repoB, engineB, _) = newDevice("B")

        val list = repoA.addList("工作")
        val item = repoA.addItem(list.id, "原标题")
        testTime += 100
        sync(engineA)
        sync(engineB)

        // A、B 在同一基础版本上各自修改标题（B 更晚）
        repoA.updateItem(item.id, title = "A 的标题")
        testTime += 100
        repoB.updateItem(item.id, title = "B 的标题")
        testTime += 100

        sync(engineA)
        sync(engineB)
        sync(engineA) // A 拉取 B 的最终结果

        assertEquals("B 的标题", repoA.items.value.first { it.id == item.id }.title)
        assertEquals("B 的标题", repoB.items.value.first { it.id == item.id }.title, "两端收敛到同一结果")
    }

    @Test
    fun `删除传播到其它设备`() = runBlocking {
        val (repoA, engineA, _) = newDevice("A")
        val (repoB, engineB, _) = newDevice("B")

        val list = repoA.addList("工作")
        val item = repoA.addItem(list.id, "待删除")
        val kept = repoA.addItem(list.id, "保留")
        testTime += 100
        sync(engineA)
        sync(engineB)
        assertEquals(2, repoB.items.value.size)

        repoA.deleteItem(item.id)
        testTime += 100
        sync(engineA)
        sync(engineB)

        val bTitles = repoB.items.value.filter { it.deletedAt == null }.map { it.title }
        assertEquals(listOf("保留"), bTitles, "B 上存活条目应只剩「保留」")
        assertNotNull(repoB.items.value.firstOrNull { it.id == item.id }?.deletedAt, "被删条目应以墓碑形式存在")
        assertNull(repoB.items.value.firstOrNull { it.id == kept.id }?.deletedAt)
    }

    @Test
    fun `目录已存在时重复同步不报错`() = runBlocking {
        val (repoA, engineA, _) = newDevice("A")
        val list = repoA.addList("工作")
        repoA.addItem(list.id, "任务")
        testTime += 100

        // 第一次同步创建目录，之后每次 MKCOL 都命中「已存在」分支：
        // 服务器对缺少结尾斜杠的 URL 返回 301，客户端必须仍然成功
        repeat(3) { round ->
            repoA.addItem(list.id, "第 $round 条")
            testTime += 100
            sync(engineA)
        }
        assertEquals(1, repoA.lists.value.count { it.deletedAt == null }, "清单数量应保持不变")
        assertEquals(4, repoA.items.value.count { it.deletedAt == null }, "三条新增加一条初始，共四条")
        assertTrue(server.fileExists("dav/ToDoApp/todoapp.json"))
    }

    @Test
    fun `ETag 冲突触发重试并最终成功`() = runBlocking {
        val (repoA, engineA, _) = newDevice("A")
        val (repoB, engineB, _) = newDevice("B")

        val list = repoA.addList("工作")
        repoA.addItem(list.id, "任务")
        testTime += 100
        sync(engineA)

        // B 基于旧状态先上传，A 的下一次上传遇到 ETag 冲突后应重取合并
        sync(engineB)
        repoB.addList("B 新清单")
        testTime += 100
        sync(engineB)
        sync(engineA)

        assertEquals(2, repoA.lists.value.filter { it.deletedAt == null }.size, "A 应合并进 B 新增的清单")
    }
}
