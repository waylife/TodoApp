package com.todoapp.sync

import com.todoapp.data.WebDavConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * WebDavClient 的上传前置条件语义：ETag 乐观锁、GET 无 ETag 时退化为 `If-Match: *`、
 * 409 重试保留 isNew 前置条件——这些分支决定了并发场景下会不会静默覆盖他人数据。
 */
class WebDavClientTest {

    private lateinit var server: FakeWebDavServer
    private lateinit var client: WebDavClient

    @BeforeTest
    fun setUp() {
        server = FakeWebDavServer()
        server.start()
        client = WebDavClient(HttpClient(CIO), config())
    }

    @AfterTest
    fun tearDown() {
        server.stop()
    }

    private fun config() = WebDavConfig(
        serverUrl = "http://127.0.0.1:${server.port}",
        username = "u",
        password = "p",
        remoteDir = "ToDoApp",
    )

    @Test
    fun `覆盖上传使用 If-Match 匹配现有 ETag`() = runBlocking {
        client.uploadFile("""{"v":1}""", etag = null, isNew = true)
        val (_, etag) = client.downloadFile()
        client.uploadFile("""{"v":2}""", etag = etag, isNew = false)
        assertEquals("""{"v":2}""", server.fileContent("ToDoApp/todoapp.json"))
    }

    @Test
    fun `GET 无 ETag 时覆盖上传退化为 If-Match 星号`() = runBlocking {
        client.uploadFile("""{"v":1}""", etag = null, isNew = true)
        server.omitGetEtag = true
        val (_, etag) = client.downloadFile()
        assertNull(etag, "前置条件：模拟的服务器不返回 ETag")

        // 文件仍存在：If-Match: *（「资源仍存在」）放行，上传成功
        client.uploadFile("""{"v":2}""", etag = etag, isNew = false)
        assertEquals("""{"v":2}""", server.fileContent("ToDoApp/todoapp.json"))
    }

    @Test
    fun `If-Match 星号在文件被删除后拒绝覆盖`() = runBlocking {
        client.uploadFile("""{"v":1}""", etag = null, isNew = true)
        server.omitGetEtag = true
        val (_, etag) = client.downloadFile()
        server.removeFile("ToDoApp/todoapp.json")

        assertFailsWith<WebDavConflictException> {
            client.uploadFile("""{"v":2}""", etag = etag, isNew = false)
        }
        assertNull(
            server.fileContent("ToDoApp/todoapp.json"),
            "GET 与 PUT 之间被删除的文件不得被静默重建",
        )
    }

    @Test
    fun `409 重试保留 isNew 前置条件`() = runBlocking {
        // 首次上传（isNew）遇 409（个别服务器在目录缺失时如此）：建目录重试时文件已被
        // 并发创建，必须带着 If-None-Match: * 重试并得到 412，而不是裸 PUT 覆盖它
        server.putFile("ToDoApp/todoapp.json", """{"other":true}""")
        server.failNextPutWith409 = true

        assertFailsWith<WebDavConflictException> {
            client.uploadFile("""{"mine":true}""", etag = null, isNew = true)
        }
        assertEquals("""{"other":true}""", server.fileContent("ToDoApp/todoapp.json"), "他人的新文件不得被覆盖")
    }
}
