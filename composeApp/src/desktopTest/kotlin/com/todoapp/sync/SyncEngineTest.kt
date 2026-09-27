package com.todoapp.sync

import com.russhwolf.settings.PreferencesSettings
import com.todoapp.data.SettingsStore
import com.todoapp.data.WebDavConfig
import com.todoapp.testing.TestDb
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import java.util.prefs.Preferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 同步引擎的触发与护栏逻辑（未配置分支、前台节流、防抖、删除前的取消）。 */
class SyncEngineTest {

    private lateinit var scope: CoroutineScope

    @BeforeTest
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    @AfterTest
    fun tearDown() {
        scope.cancel()
    }

    private fun closedPort(): Int = java.net.ServerSocket(0).use { it.localPort }

    private fun newSettingsStore(): SettingsStore =
        SettingsStore(PreferencesSettings(Preferences.userRoot().node("/com/todoapp/test/se-${System.nanoTime()}")))

    private fun configure(store: SettingsStore, port: Int) {
        store.saveConfig(
            WebDavConfig(serverUrl = "http://127.0.0.1:$port", username = "u", password = "p", remoteDir = "ToDoApp"),
        )
    }

    // ---------- 未配置 ----------

    @Test
    fun `未配置时 syncNow 置 NotConfigured 且不返回任务`() = runBlocking {
        val db = TestDb()
        val engine = SyncEngine(db.repository, newSettingsStore(), HttpClient(CIO), scope)

        assertNull(engine.syncNow())
        assertEquals(SyncStatus.NotConfigured, engine.status.value)
    }

    @Test
    fun `未配置时 scheduleSync 不触发同步`() = runBlocking {
        val db = TestDb()
        val engine = SyncEngine(db.repository, newSettingsStore(), HttpClient(CIO), scope)

        db.repository.addList("工作") // 触发 onLocalChange → scheduleSync
        engine.scheduleSync()
        engine.scheduleSync(delayMillis = 1)
        delay(300)

        assertEquals(SyncStatus.Idle, engine.status.value, "未配置时不应发起任何同步")
    }

    @Test
    fun `未配置时 deleteRemoteData 置 NotConfigured`() = runBlocking {
        val db = TestDb()
        val engine = SyncEngine(db.repository, newSettingsStore(), HttpClient(CIO), scope)

        assertNull(engine.deleteRemoteData(clearLocal = true))
        assertEquals(SyncStatus.NotConfigured, engine.status.value)
    }

    // ---------- 前台节流 ----------

    @Test
    fun `前台同步 距上次成功不足20秒时被节流`() = runBlocking {
        val db = TestDb()
        val store = newSettingsStore()
        configure(store, closedPort()) // 即便放行也连不出去，节流命中则不会有任何请求
        store.lastSyncAt = 995_000L // 固定时钟为 1_000_000，5 秒前成功过
        val engine = SyncEngine(db.repository, store, HttpClient(CIO), scope) { 1_000_000L }

        assertNull(engine.syncOnForeground(), "20 秒内回到前台应被跳过")
        assertEquals(SyncStatus.Idle, engine.status.value)
    }

    @Test
    fun `前台同步 从未成功过则放行`() = runBlocking {
        val db = TestDb()
        val store = newSettingsStore()
        configure(store, closedPort()) // 不可达端口：放行后同步应真实发生并报错
        val engine = SyncEngine(db.repository, store, HttpClient(CIO), scope) { 1_000_000L }

        val job = engine.syncOnForeground()
        assertNotNull(job, "从未成功同步过（lastSyncAt=0）时应放行，让失败能重试")
        withTimeout(15_000) { job.join() }
        assertTrue(engine.status.value is SyncStatus.Error, "对不可达服务器同步后应为 Error，实际：${engine.status.value}")
    }

    // ---------- 防抖 ----------

    @Test
    fun `防抖同步 在指定延迟后完成一次同步`() = runBlocking {
        val server = FakeWebDavServer()
        server.start()
        try {
            val db = TestDb()
            val store = newSettingsStore()
            configure(store, server.port)
            val engine = SyncEngine(db.repository, store, HttpClient(CIO), scope)

            engine.scheduleSync(delayMillis = 100)
            withTimeout(15_000) { engine.status.first { it is SyncStatus.Success } }

            assertTrue(server.fileExists("ToDoApp/todoapp.json"), "防抖到期后应完成一次完整同步")
        } finally {
            server.stop()
        }
    }

    @Test
    fun `删除远端数据 会先取消排队中的防抖同步`() = runBlocking {
        val server = FakeWebDavServer()
        server.start()
        try {
            val db = TestDb()
            val store = newSettingsStore()
            configure(store, server.port)
            val engine = SyncEngine(db.repository, store, HttpClient(CIO), scope)

            db.repository.addList("工作") // 触发 2 秒防抖
            val job = engine.deleteRemoteData(clearLocal = true)
            assertNotNull(job)
            withTimeout(15_000) { job.join() }

            val status = engine.status.value
            assertTrue(status is SyncStatus.RemoteCleared && status.localCleared, "实际：$status")
            assertTrue(db.repository.lists.value.isEmpty(), "本机数据应一并清空")

            delay(2_500) // 若防抖未被取消，这里会触发一次上传把数据原样传回去
            assertFalse(server.fileExists("ToDoApp/todoapp.json"), "排队中的防抖同步应被取消，不得再上传")
        } finally {
            server.stop()
        }
    }
}
