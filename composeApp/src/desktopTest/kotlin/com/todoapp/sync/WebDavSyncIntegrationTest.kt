package com.todoapp.sync

import com.russhwolf.settings.PreferencesSettings
import com.todoapp.data.SettingsStore
import com.todoapp.data.TodoRepository
import com.todoapp.data.WebDavConfig
import com.todoapp.model.RemoteSnapshot
import com.todoapp.model.TodoItem
import com.todoapp.model.TodoList
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.todoapp.db.AppDatabase
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.prefs.Preferences
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

    @Test
    fun `GET 显式声明 identity 以避免服务器返回压缩表示的 ETag`() = runBlocking {
        val (repoA, engineA, _) = newDevice("A")
        val list = repoA.addList("工作")
        repoA.addItem(list.id, "写周报")
        testTime += 100

        sync(engineA)

        assertEquals(
            "identity",
            server.lastGetAcceptEncoding,
            "客户端应显式声明 identity：OkHttp 默认发送 Accept-Encoding: gzip 并透明解压，" +
                "服务器（Apache mod_deflate）会据此把 ETag 改写成带 -gzip 后缀的压缩表示，" +
                "该值用于 PUT 的 If-Match 时必然 412",
        )
    }

    @Test
    fun `服务器强制以压缩表示返回 ETag 时仍可上传`() = runBlocking {
        val (repoA, engineA, _) = newDevice("A")
        val list = repoA.addList("工作")
        repoA.addItem(list.id, "写周报")
        testTime += 100
        sync(engineA)

        // 服务器或反向代理无视 Accept-Encoding，一律以压缩表示返回 ETag
        server.forceGzipEtag = true
        repoA.addItem(list.id, "买牛奶")
        testTime += 100
        sync(engineA)

        val uploaded = server.fileContent("dav/ToDoApp/todoapp.json")
        assertNotNull(uploaded, "远程应存在快照文件")
        assertTrue(uploaded.contains("买牛奶"), "剥离 -gzip 后缀后 If-Match 应匹配成功")
    }

    @Test
    fun `回到前台触发同步，但距上次成功过近会被节流`() = runBlocking {
        val (repoA, engineA, _) = newDevice("A")
        repoA.onLocalChange = null // 关掉防抖，避免与节流断言抢跑

        val list = repoA.addList("工作")
        repoA.addItem(list.id, "任务")
        testTime += 100
        sync(engineA)

        // 距上次成功同步仅 1 秒（小于 20 秒节流窗口）：应被跳过，不产生任何上传
        repoA.addItem(list.id, "节流期内的改动")
        testTime += 1_000
        assertNull(engineA.syncOnForeground(), "距上次成功同步不足 20 秒时应被节流")
        assertFalse(
            server.fileContent("dav/ToDoApp/todoapp.json")!!.contains("节流期内的改动"),
            "被节流时不应发起同步",
        )

        // 超过节流窗口后放行
        testTime += 30_000
        val job = engineA.syncOnForeground()
        assertNotNull(job, "超过节流窗口后应放行")
        withTimeout(15_000) { job.join() }
        assertTrue(
            server.fileContent("dav/ToDoApp/todoapp.json")!!.contains("节流期内的改动"),
            "放行后应把本地改动同步出去",
        )
    }

    /** 删除远端任务，[clearLocal] 对应设置页确认框里的勾选项。 */
    private suspend fun deleteRemote(engine: SyncEngine, clearLocal: Boolean) {
        val job = engine.deleteRemoteData(clearLocal)
        assertNotNull(job, "已配置 WebDAV，删除任务不应为 null")
        withTimeout(15_000) { job.join() }
    }

    @Test
    fun `删除远端数据保留本机数据`() = runBlocking {
        val (repoA, engineA, _) = newDevice("A")
        val list = repoA.addList("工作")
        repoA.addItem(list.id, "写周报")
        testTime += 100
        sync(engineA)
        assertTrue(server.fileExists(PATH))

        deleteRemote(engineA, clearLocal = false)

        assertFalse(server.fileExists(PATH), "远端快照应被删除")
        assertEquals(1, repoA.lists.value.size, "未勾选清空本机时，本机数据应保留")
        assertEquals("写周报", repoA.items.value.first().title)
        assertTrue(engineA.status.value is SyncStatus.RemoteCleared, "状态应为已清空远端，实际：${engineA.status.value}")
    }

    @Test
    fun `删除远端数据并清空本机后不会把数据传回`() = runBlocking {
        val (repoA, engineA, settingsA) = newDevice("A")
        val list = repoA.addList("工作")
        repoA.addItem(list.id, "写周报")
        testTime += 100
        sync(engineA)

        deleteRemote(engineA, clearLocal = true)

        assertFalse(server.fileExists(PATH))
        assertTrue(repoA.lists.value.isEmpty(), "本机清单应被清空")
        assertTrue(repoA.items.value.isEmpty(), "本机待办应被清空")
        assertEquals(0L, settingsA.lastSyncAt, "清空后不应再显示上次同步时间")

        // 关键：清空后再同步，远端不会复活旧数据
        sync(engineA)
        assertFalse(
            server.fileContent(PATH)?.contains("写周报") ?: false,
            "清空本机后同步不应把旧数据传回远端",
        )
    }

    @Test
    fun `远端数据从未存在时删除也不报错`() = runBlocking {
        val (_, engineA, _) = newDevice("A")

        deleteRemote(engineA, clearLocal = false)

        assertTrue(
            engineA.status.value is SyncStatus.RemoteCleared,
            "远端本就没有快照（404）时应视作删除成功，实际：${engineA.status.value}",
        )
    }

    @Test
    fun `删除远端后待触发的自动同步不会把数据传回`() = runBlocking {
        val (repoA, engineA, _) = newDevice("A")
        val list = repoA.addList("工作")
        repoA.addItem(list.id, "写周报")
        testTime += 100
        sync(engineA)
        assertTrue(server.fileExists(PATH))

        // 本机再改一次，让防抖任务排队；紧接着删除远端，排队中的上传必须被取消
        repoA.addItem(list.id, "还没同步的事项")
        testTime += 100
        engineA.scheduleSync(delayMillis = 100)
        deleteRemote(engineA, clearLocal = true)
        delay(600)

        assertFalse(server.fileExists(PATH), "防抖同步不应在删除之后把数据重新传上去")
    }

    @Test
    fun `远端删除后其它设备仍可重新上传自己的数据`() = runBlocking {
        val (repoA, engineA, _) = newDevice("A")
        val (repoB, engineB, _) = newDevice("B")
        val listA = repoA.addList("A 的清单")
        repoA.addItem(listA.id, "A 的事项")
        testTime += 100
        sync(engineA)
        sync(engineB)
        assertEquals(1, repoB.lists.value.size)

        // A 清空远端（含本机），B 的数据不受影响
        deleteRemote(engineA, clearLocal = true)
        assertFalse(server.fileExists(PATH))
        assertEquals(1, repoB.lists.value.count { it.deletedAt == null }, "B 本机数据不应被牵连")

        // B 的下一次同步把它的数据重新写回远端，这是快照同步的固有行为
        sync(engineB)
        assertTrue(server.fileExists(PATH))
        assertEquals(
            listOf("A 的清单"),
            repoB.lists.value.filter { it.deletedAt == null }.map { it.name },
        )
    }

    @Test
    fun `合并窗口内的用户编辑不会被整体写库清掉`() = runBlocking {
        // 独立构造设备：时钟要在「取快照之后、合并写库之前」注入一次用户编辑，
        // 模拟同步窗口内（下载完成 → replaceAll 执行）的并发修改
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        AppDatabase.Schema.create(driver)
        val repo = TodoRepository(AppDatabase(driver), clock = { testTime })
        val settingsNode = Preferences.userRoot().node("/com/todoapp/test/race-${System.nanoTime()}")
        val store = SettingsStore(PreferencesSettings(settingsNode))
        store.saveConfig(
            WebDavConfig(serverUrl = "http://127.0.0.1:${server.port}", username = "user", password = "pass", remoteDir = "dav/ToDoApp"),
        )
        val list = repo.addList("工作")
        repo.addItem(list.id, "既有事项")
        testTime += 100

        var armed = false
        var clockCalls = 0
        val engine = SyncEngine(
            repository = repo,
            settingsStore = store,
            httpClient = HttpClient(CIO),
            scope = scope,
            clock = {
                // 引擎时钟只在 merge 与同步收尾处调用（快照 savedAt 走仓库自己的时钟）。
                // 第 1 次调用 = 第 1 轮 merge，编辑注入此处，恰好落在「快照之后、
                // replaceAll 之前」的竞态窗口内；写前检测应放弃本轮、重走一轮把编辑并入。
                if (armed) {
                    clockCalls++
                    if (clockCalls == 1) repo.addItem(list.id, "窗口内的编辑")
                }
                testTime
            },
        )

        sync(engine) // 先同步一次，让远端有内容，覆盖更危险的非首次同步场景
        armed = true
        clockCalls = 0
        sync(engine)

        val uploaded = server.fileContent("dav/ToDoApp/todoapp.json")
        val surviving = assertNotNull(
            repo.items.value.firstOrNull { it.title == "窗口内的编辑" },
            "窗口内的编辑不应被 replaceAll 清掉",
        )
        assertTrue(
            uploaded!!.contains("窗口内的编辑"),
            "窗口内的编辑应进入合并结果并被上传，实际：${uploaded.take(600)}",
        )
        assertTrue(surviving.deletedAt == null, "编辑应保持存活而非墓碑")
    }

    @Test
    fun `远端快照版本过新时同步报错且不降级覆写`() = runBlocking {
        val (repoA, engineA, _) = newDevice("A")
        val list = repoA.addList("工作")
        repoA.addItem(list.id, "本地事项")
        testTime += 100
        sync(engineA)

        // 模拟未来版本的应用写入了 v4 快照，含本端不认识的数据
        val future = RemoteSnapshot(
            schemaVersion = 4,
            rev = 9,
            savedAt = testTime,
            lists = listOf(TodoList(id = "future-list", name = "新版清单", createdAt = 1, updatedAt = 1)),
            items = listOf(TodoItem(id = "future-item", listId = "future-list", title = "新版待办", createdAt = 1, updatedAt = 1)),
        )
        server.putFile(PATH, Json.encodeToString(future))

        val job = assertNotNull(engineA.syncNow())
        withTimeout(15_000) { job.join() }

        val status = assertNotNull(
            engineA.status.value as? SyncStatus.Error,
            "实际：${engineA.status.value}",
        )
        assertTrue(status.message.contains("升级"), "应提示升级应用，实际：${status.message}")
        assertNull(repoA.items.value.firstOrNull { it.id == "future-item" }, "新版数据不得被合并进本机")
        assertEquals("本地事项", repoA.items.value.single { it.deletedAt == null }.title, "本机数据不得被整体替换")
        assertTrue(
            server.fileContent(PATH)!!.contains("新版待办"),
            "远端 v3 快照不得被降级覆写",
        )
    }

    @Test
    fun `远端内容损坏时报错且不动本地数据`() = runBlocking {
        val (repoA, engineA, _) = newDevice("A")
        val list = repoA.addList("工作")
        repoA.addItem(list.id, "本地事项")
        testTime += 100
        sync(engineA)

        // 远端文件被外部写坏（截断/乱码）：绝不能当成「远端为空」清掉本地，也不能把合并结果覆写回去
        val garbage = "这不是 JSON{{{"
        server.putFile(PATH, garbage)

        val job = assertNotNull(engineA.syncNow())
        withTimeout(15_000) { job.join() }

        val status = assertNotNull(
            engineA.status.value as? SyncStatus.Error,
            "实际：${engineA.status.value}",
        )
        assertTrue(status.message.contains("无法解析"), "应提示远端数据无法解析，实际：${status.message}")
        assertEquals("本地事项", repoA.items.value.single { it.deletedAt == null }.title, "本机数据不得被改动")
        assertEquals(garbage, server.fileContent(PATH), "损坏的远端内容不得被合并结果覆写")
    }

    @Test
    fun `内容无变化时跳过上传与建目录，本地变化后恢复`() = runBlocking {
        val (repoA, engineA, _) = newDevice("A")
        val list = repoA.addList("工作")
        repoA.addItem(list.id, "任务")
        testTime += 100
        sync(engineA)

        // 无任何修改的例行同步：rev 虽然恒递增，但内容一致时不应重写远端文件，
        // 也不再重复逐级 MKCOL
        val putsBefore = server.putCount
        val mkcolsBefore = server.mkcolCount
        val contentBefore = server.fileContent(PATH)
        sync(engineA)

        assertEquals(putsBefore, server.putCount, "内容无变化时不应重写远端文件")
        assertEquals(mkcolsBefore, server.mkcolCount, "目录已建过就不应再发 MKCOL")
        assertEquals(contentBefore, server.fileContent(PATH), "远端内容应保持原样")

        // 本地出现修改后必须恢复上传
        repoA.addItem(list.id, "新事项")
        testTime += 100
        sync(engineA)

        assertEquals(putsBefore + 1, server.putCount, "本地修改后应恢复上传")
        assertTrue(server.fileContent(PATH)!!.contains("新事项"))
    }

    private companion object {
        const val PATH = "dav/ToDoApp/todoapp.json"
    }
}
