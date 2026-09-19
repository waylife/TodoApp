package com.todoapp.sync

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.russhwolf.settings.PreferencesSettings
import com.todoapp.data.SettingsStore
import com.todoapp.data.TodoRepository
import com.todoapp.data.WebDavConfig
import com.todoapp.db.AppDatabase
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.prefs.Preferences
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 对着**真实 WebDAV 服务器**的冒烟测试：走的是应用真实的 [WebDavClient] + [SyncEngine]，
 * 用两台独立本地库验证「上传 → 另一设备拉取 → 双向增量 → 删除传播 → 412 冲突」。
 *
 * 未提供凭据时整组用例跳过，因此可以安全地留在仓库里作为手动验收手段。
 * 凭据来源（按顺序尝试）：
 * 1. 环境变量 TODOAPP_DAV_URL / TODOAPP_DAV_USER / TODOAPP_DAV_PASS / TODOAPP_DAV_DIR
 * 2. 属性文件 TODOAPP_DAV_TEST_PROPS 指向的路径
 * 3. 默认属性文件 /tmp/todoapp-dav-test.properties
 *
 * 运行：./gradlew :composeApp:desktopTest --tests 'com.todoapp.sync.RealWebDavSmokeTest' -i
 */
class RealWebDavSmokeTest {

    private data class Creds(val url: String, val user: String, val pass: String, val dir: String)

    private fun loadCreds(): Creds? {
        val fromEnv = Creds(
            url = System.getenv("TODOAPP_DAV_URL").orEmpty(),
            user = System.getenv("TODOAPP_DAV_USER").orEmpty(),
            pass = System.getenv("TODOAPP_DAV_PASS").orEmpty(),
            dir = System.getenv("TODOAPP_DAV_DIR") ?: WebDavConfig.DEFAULT_REMOTE_DIR,
        )
        if (fromEnv.url.isNotBlank()) return fromEnv

        val propsFile = File(System.getenv("TODOAPP_DAV_TEST_PROPS") ?: "/tmp/todoapp-dav-test.properties")
        if (!propsFile.isFile) return null
        val props = java.util.Properties().apply { propsFile.inputStream().use(::load) }
        val creds = Creds(
            url = props.getProperty("url").orEmpty(),
            user = props.getProperty("user").orEmpty(),
            pass = props.getProperty("pass").orEmpty(),
            dir = props.getProperty("dir") ?: WebDavConfig.DEFAULT_REMOTE_DIR,
        )
        return creds.takeIf { it.url.isNotBlank() }
    }

    private val creds: Creds? = loadCreds()

    private val httpClient = HttpClient(CIO)
    private lateinit var scope: CoroutineScope
    private var testTime = 1_000_000L

    /** 冒烟测试专用子目录，避免污染真实同步目录。 */
    private val testDir: String get() = "${creds!!.dir}/smoke/e2e"

    @BeforeTest
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    @AfterTest
    fun tearDown() {
        scope.cancel()
    }

    private fun newDevice(name: String, remoteDir: String): Triple<TodoRepository, SyncEngine, SettingsStore> {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        AppDatabase.Schema.create(driver)
        val repository = TodoRepository(AppDatabase(driver), clock = { testTime })
        val settingsNode = Preferences.userRoot().node("/com/todoapp/smoke/${name}-${System.nanoTime()}")
        val settingsStore = SettingsStore(PreferencesSettings(settingsNode))
        settingsStore.saveConfig(
            WebDavConfig(
                serverUrl = creds!!.url,
                username = creds.user,
                password = creds.pass,
                remoteDir = remoteDir,
            ),
        )
        val engine = SyncEngine(
            repository = repository,
            settingsStore = settingsStore,
            httpClient = httpClient,
            scope = scope,
            clock = { testTime },
        )
        // 关掉 2 秒防抖触发，只保留显式 syncNow()，避免后台同步与断言抢跑
        repository.onLocalChange = null
        return Triple(repository, engine, settingsStore)
    }

    private suspend fun sync(engine: SyncEngine, label: String) {
        val job: Job? = engine.syncNow()
        job?.let { withTimeout(30_000) { it.join() } }
        val status = engine.status.value
        println("[$label] 同步结果：$status")
        assertTrue(status is SyncStatus.Success, "[$label] 同步应当成功，实际：$status")
    }

    private fun fileUrl() = "${creds!!.url.trimEnd('/')}/${testDir.trim('/')}/todoapp.json"

    @OptIn(ExperimentalEncodingApi::class)
    private fun authHeader(): String =
        "Basic " + Base64.encode("${creds!!.user}:${creds.pass}".encodeToByteArray())

    /** 直连 HTTP 读取远程文件正文（用于核对真实落盘内容）。 */
    private suspend fun remoteContent(): String? {
        val response = httpClient.get(fileUrl()) { header(HttpHeaders.Authorization, authHeader()) }
        return if (response.status.value == 404) null else response.bodyAsText()
    }

    private suspend fun cleanRemote() {
        val response = httpClient.delete(fileUrl()) { header(HttpHeaders.Authorization, authHeader()) }
        println("[setup] 清理远程测试文件：HTTP ${response.status.value}")
    }

    @Test
    fun `真实服务器端到端同步`() = runBlocking {
        if (creds == null) {
            println("跳过：未提供 WebDAV 凭据（TODOAPP_DAV_* 或 /tmp/todoapp-dav-test.properties）")
            return@runBlocking
        }
        println("[info] 目标服务器：${creds.url} 用户：${creds.user} 目录：$testDir")
        cleanRemote()

        // ---- 1. 设置页「测试连接」：真实目录 + 真实服务器 ----
        val probe = WebDavClient(httpClient, WebDavConfig(creds.url, creds.user, creds.pass, creds.dir))
        val conn = probe.testConnection()
        println("[1] testConnection（目录 ${creds.dir}）：$conn")
        assertTrue(conn.isSuccess, "测试连接应成功：${conn.exceptionOrNull()?.message}")

        // ---- 2. 首次同步：本地数据上传到真实服务器 ----
        val (repoA, engineA, _) = newDevice("A", testDir)
        val list = repoA.addList("工作")
        val item = repoA.addItem(list.id, "写周报")
        repoA.addItem(list.id, "买牛奶")
        testTime += 100
        sync(engineA, "2-A首次上传")

        val uploaded = remoteContent()
        assertTrue(uploaded != null, "远程应出现 todoapp.json")
        assertTrue(uploaded.contains("写周报"), "远程正文应包含中文标题（验证 UTF-8 编码）")
        println("[2] 远程文件已生成，长度=${uploaded.length}")

        // ---- 3. 第二台设备拉取 ----
        val (repoB, engineB, _) = newDevice("B", testDir)
        sync(engineB, "3-B首次拉取")
        assertEquals(1, repoB.lists.value.count { it.deletedAt == null }, "B 应拉到 1 个清单")
        assertEquals(
            setOf("写周报", "买牛奶"),
            repoB.items.value.filter { it.deletedAt == null }.map { it.title }.toSet(),
            "B 应拉到两条待办",
        )

        // ---- 4. 双向增量：B 勾选 + 新增，A 拉取 ----
        repoB.setDone(item.id, true)
        repoB.addItem(list.id, "B 新增的事项")
        testTime += 100
        sync(engineB, "4-B上传变更")
        sync(engineA, "4-A拉取变更")
        assertTrue(repoA.items.value.first { it.id == item.id }.done, "A 应看到 B 的勾选")
        assertTrue(
            repoA.items.value.any { it.title == "B 新增的事项" },
            "A 应看到 B 新增的事项",
        )

        // ---- 5. 删除传播（墓碑） ----
        val toDelete = repoA.items.value.first { it.title == "买牛奶" }
        repoA.deleteItem(toDelete.id)
        testTime += 100
        sync(engineA, "5-A删除并上传")
        sync(engineB, "5-B拉取删除")
        assertEquals(
            setOf("写周报", "B 新增的事项"),
            repoB.items.value.filter { it.deletedAt == null }.map { it.title }.toSet(),
            "B 上存活条目应只剩两条",
        )

        // ---- 6. 乐观锁：陈旧 ETag 必须被服务器拒绝为 412 ----
        val client = WebDavClient(httpClient, WebDavConfig(creds.url, creds.user, creds.pass, testDir))
        val (_, etag) = client.downloadFile()
        assertTrue(etag != null, "服务器应返回 ETag")
        client.uploadFile("""{"schemaVersion":1,"rev":99,"savedAt":0,"lists":[],"items":[]}""", etag, isNew = false)
        assertFailsWith<WebDavConflictException>("陈旧 ETag 应触发 412 冲突") {
            client.uploadFile("""{"schemaVersion":1,"rev":100,"savedAt":0,"lists":[],"items":[]}""", etag, isNew = false)
        }
        println("[6] 陈旧 ETag 已被服务器以 412 拒绝，乐观锁生效")

        // 冲突重试后收敛：两台设备都同步一次，远端回到真实数据
        sync(engineA, "6-A冲突后收敛")
        sync(engineB, "6-B冲突后收敛")
        assertEquals(
            setOf("写周报", "B 新增的事项"),
            repoB.items.value.filter { it.deletedAt == null }.map { it.title }.toSet(),
            "冲突后数据应仍收敛",
        )

        // ---- 7. 反复写入后读取：服务器可能返回弱 ETag（W/"..."），归一化后上传必须仍然成功 ----
        var weakRounds = 0
        repeat(6) { round ->
            val probeDir = "$testDir/etag-probe-$round"
            val probe = WebDavClient(httpClient, WebDavConfig(creds.url, creds.user, creds.pass, probeDir))
            probe.ensureRemoteDir()
            probe.uploadFile("""{"round":$round}""", null, isNew = true)
            val (_, rawEtag) = probe.downloadFile()
            val weak = rawEtag?.startsWith("W/") == true
            if (weak) weakRounds += 1
            // 关键断言：无论 GET 返回弱还是强 ETag，这一次上传都应当成功
            probe.uploadFile("""{"round":$round,"rewritten":true}""", rawEtag, isNew = false)
            println("[7] 第 $round 轮：GET etag=$rawEtag（${if (weak) "弱" else "强"}），归一化后 If-Match 上传成功")
            httpClient.delete("${creds.url.trimEnd('/')}/${probeDir.trim('/')}/todoapp.json") {
                header(HttpHeaders.Authorization, authHeader())
            }
        }
        println("[7] 6 轮中服务器返回弱 ETag 的轮数：$weakRounds（弱 ETag 直接用于 If-Match 必然 412）")

        println("[done] 端到端同步全部通过，远程文件：${fileUrl()}")
        println("[done] 远程正文预览：${remoteContent()?.take(400)}")
    }
}
