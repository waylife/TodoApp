package com.todoapp.viewmodel

import com.russhwolf.settings.PreferencesSettings
import com.todoapp.data.SettingsStore
import com.todoapp.data.WebDavConfig
import com.todoapp.sync.FakeWebDavServer
import com.todoapp.sync.SyncEngine
import com.todoapp.sync.SyncStatus
import com.todoapp.testing.TestDb
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import java.util.prefs.Preferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** 设置页 ViewModel：配置归一化与触发同步、连接测试、lastSyncAt 展示。 */
class SettingsViewModelTest {

    private lateinit var scope: CoroutineScope

    @BeforeTest
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    @AfterTest
    fun tearDown() {
        scope.cancel()
    }

    private fun newStore(): SettingsStore =
        SettingsStore(PreferencesSettings(Preferences.userRoot().node("/com/todoapp/test/svm-${System.nanoTime()}")))

    private suspend fun syncToSuccess(engine: SyncEngine) {
        val job = engine.syncNow()
        assertNotNull(job)
        withTimeout(15_000) { job.join() }
        assertTrue(engine.status.value is SyncStatus.Success, "实际：${engine.status.value}")
    }

    // ---------- 保存配置 ----------

    @Test
    fun `saveConfig 归一化配置并立即触发同步`() = runBlocking {
        val server = FakeWebDavServer()
        server.start()
        try {
            val db = TestDb()
            val store = newStore()
            val engine = SyncEngine(db.repository, store, HttpClient(CIO), scope)
            val vm = SettingsViewModel(store, engine, HttpClient(CIO), scope)

            vm.saveConfig(
                serverUrl = "http://127.0.0.1:${server.port}/dav/",
                username = " u ",
                password = "p",
                remoteDir = "/backup/todo/",
            )

            assertEquals("http://127.0.0.1:${server.port}/dav", store.config.value.serverUrl)
            assertEquals("u", store.config.value.username)
            assertEquals("backup/todo", store.config.value.remoteDir)

            // 保存后自动发起的同步应真实跑完；uiState 由异步 combine 更新，用 first 等它算好
            withTimeout(15_000) { engine.status.first { it is SyncStatus.Success } }
            assertTrue(
                server.fileExists("dav/backup/todo/todoapp.json"),
                "同步应落到 serverUrl 路径 + 自定义远程目录下",
            )
            val state = withTimeout(5_000) {
                vm.uiState.first { it.remotePath == "http://127.0.0.1:${server.port}/dav/backup/todo/todoapp.json" }
            }
            assertEquals(
                "http://127.0.0.1:${server.port}/dav/backup/todo/todoapp.json",
                state.remotePath,
                "确认框里要展示完整的远端快照地址",
            )
        } finally {
            server.stop()
        }
    }

    @Test
    fun `saveConfig 后地址仍非法则置 NotConfigured`() = runBlocking {
        val db = TestDb()
        val store = newStore()
        val engine = SyncEngine(db.repository, store, HttpClient(CIO), scope)
        val vm = SettingsViewModel(store, engine, HttpClient(CIO), scope)

        vm.saveConfig(serverUrl = "不是地址", username = "u", password = "p", remoteDir = "ToDoApp")

        assertEquals("不是地址", store.config.value.serverUrl, "保存动作本身不拦截非法地址")
        assertEquals(SyncStatus.NotConfigured, engine.status.value, "非法地址不应发起同步")
    }

    // ---------- 测试连接 ----------

    @Test
    fun `测试连接 空地址直接失败且不发起请求`() = runBlocking {
        val db = TestDb()
        val store = newStore()
        val engine = SyncEngine(db.repository, store, HttpClient(CIO), scope)
        var testerCalls = 0
        val vm = SettingsViewModel(store, engine, HttpClient(CIO), scope) {
            testerCalls++
            Result.success("不应被调用")
        }

        vm.testConnection(serverUrl = "   ", username = "u", password = "p", remoteDir = "ToDoApp")
        withTimeout(5_000) { vm.uiState.first { it.testResult != null } }

        assertEquals(0, testerCalls, "空地址应被本地拦截，不发起任何连接")
        assertFalse(vm.uiState.value.testSuccess)
        assertEquals("请先填写服务器地址", vm.uiState.value.testResult)
    }

    @Test
    fun `测试连接 走注入的测试器并回显结果`() = runBlocking {
        val db = TestDb()
        val store = newStore()
        val engine = SyncEngine(db.repository, store, HttpClient(CIO), scope)
        val received = mutableListOf<WebDavConfig>()
        val vm = SettingsViewModel(store, engine, HttpClient(CIO), scope) { config ->
            received += config
            if (config.serverUrl.startsWith("https://good")) Result.success("连接成功，目录可用")
            else Result.failure(IllegalStateException("拒绝连接"))
        }

        vm.testConnection(" https://good.example.com/dav/ ", "u", "p", remoteDir = "")
        withTimeout(5_000) { vm.uiState.first { it.testResult != null } }

        assertEquals(1, received.size)
        assertEquals("https://good.example.com/dav", received[0].serverUrl, "测试器收到的是归一化后的配置")
        assertEquals(WebDavConfig.DEFAULT_REMOTE_DIR, received[0].remoteDir, "空白目录应回退默认")
        assertTrue(vm.uiState.value.testSuccess)
        assertEquals("连接成功，目录可用", vm.uiState.value.testResult)
        assertFalse(vm.uiState.value.testing)

        vm.testConnection("https://bad.example.com", "u", "p", "ToDoApp")
        withTimeout(5_000) { vm.uiState.first { !it.testSuccess && it.testResult != null } }

        assertFalse(vm.uiState.value.testSuccess)
        assertEquals("拒绝连接", vm.uiState.value.testResult)
    }

    // ---------- lastSyncAt 与删除远端 ----------

    @Test
    fun `同步成功后 lastSyncAt 展示最近一次成功时间`() = runBlocking {
        val server = FakeWebDavServer()
        server.start()
        try {
            val db = TestDb()
            val store = newStore()
            val engine = SyncEngine(db.repository, store, HttpClient(CIO), scope)
            val vm = SettingsViewModel(store, engine, HttpClient(CIO), scope)
            store.saveConfig(
                WebDavConfig(serverUrl = "http://127.0.0.1:${server.port}", username = "u", password = "p"),
            )
            db.repository.addList("工作")

            syncToSuccess(engine)

            val state = withTimeout(5_000) { vm.uiState.first { it.lastSyncAt > 0 } }
            // Success.at 与 store.lastSyncAt 是两次独立的时钟读数（毫秒级可能不同），
            // 这里断言界面展示的就是状态里那次成功同步的时间
            val successAt = (engine.status.value as SyncStatus.Success).at
            assertEquals(successAt, state.lastSyncAt)
            assertTrue(store.lastSyncAt > 0)
        } finally {
            server.stop()
        }
    }

    @Test
    fun `删除远端数据并清空本机时 lastSyncAt 归零`() = runBlocking {
        val server = FakeWebDavServer()
        server.start()
        try {
            val db = TestDb()
            val store = newStore()
            val engine = SyncEngine(db.repository, store, HttpClient(CIO), scope)
            val vm = SettingsViewModel(store, engine, HttpClient(CIO), scope)
            store.saveConfig(
                WebDavConfig(serverUrl = "http://127.0.0.1:${server.port}", username = "u", password = "p"),
            )
            val l = db.repository.addList("工作")
            db.repository.addItem(l.id, "甲")
            syncToSuccess(engine)

            val job = engine.deleteRemoteData(clearLocal = true)
            assertNotNull(job)
            withTimeout(15_000) { job.join() }

            val state = withTimeout(5_000) { vm.uiState.first { it.syncStatus is SyncStatus.RemoteCleared } }
            assertEquals(0L, state.lastSyncAt, "本机数据已清空，不应再显示旧的同步时间")
            assertTrue(db.repository.items.value.isEmpty())
        } finally {
            server.stop()
        }
    }
}
