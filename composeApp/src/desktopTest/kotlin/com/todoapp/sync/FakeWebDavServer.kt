package com.todoapp.sync

import io.ktor.http.HttpStatusCode
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.route
import io.ktor.server.routing.routing

/**
 * 进程内迷你 WebDAV 服务（仅测试用）：支持 MKCOL / GET / PUT，带简单 ETag 乐观锁，
 * 覆盖同步引擎所需的最小协议面。
 *
 * 刻意贴合真实服务器的两处行为（实测 Teracloud/Apache）：
 * 1. 集合已存在且 URL 缺少结尾斜杠时，MKCOL 返回 301 而非 405；
 * 2. GET 返回弱 ETag（`W/"..."`），而 If-Match 只做强比较，带 `W/` 的值一律 412。
 */
class FakeWebDavServer {

    private class Entry(val content: String, val etag: String)

    private val files = HashMap<String, Entry>()
    private val createdDirs = HashSet<String>()
    private var etagCounter = 0

    private lateinit var server: EmbeddedServer<*, *>
    var port: Int = 0
        private set

    fun start() {
        // 自行挑选一个空闲端口，避免依赖 Ktor 服务器的连接器内省 API
        val probe = java.net.ServerSocket(0)
        val freePort = probe.localPort
        probe.close()
        server = embeddedServer(CIO, host = "127.0.0.1", port = freePort) {
            routing {
                route("{...}") {
                    handle { handleRequest() }
                }
            }
        }
        server.start(wait = false)
        port = freePort
    }

    fun stop() {
        server.stop(0, 0)
    }

    fun fileExists(path: String): Boolean = files.containsKey(path)

    fun dirCreated(path: String): Boolean = createdDirs.contains(path.trim('/'))

    fun fileContent(path: String): String? = files[path]?.content

    private suspend fun RoutingContext.handleRequest() {
        val rawPath = call.request.path()
        val path = rawPath.trim('/')
        when (call.request.httpMethod.value.uppercase()) {
            "MKCOL" -> {
                if (createdDirs.contains(path) || files.containsKey(path)) {
                    // 真实服务器（Apache mod_dir）在集合已存在、URL 缺少结尾斜杠时返回 301，
                    // 带斜杠才返回 405；客户端两种都要按「目录已就绪」处理
                    if (rawPath.endsWith("/")) {
                        call.respondText("", status = HttpStatusCode.MethodNotAllowed)
                    } else {
                        call.response.header("Location", "$rawPath/")
                        call.respondText("", status = HttpStatusCode.MovedPermanently)
                    }
                } else {
                    createdDirs += path
                    call.respondText("", status = HttpStatusCode.Created)
                }
            }

            "GET" -> {
                val entry = files[path]
                if (entry == null) {
                    call.respondText("", status = HttpStatusCode.NotFound)
                } else {
                    call.response.header("ETag", entry.etag)
                    call.respondText(entry.content)
                }
            }

            "PUT" -> {
                val body = call.receiveText()
                val ifMatch = call.request.headers["If-Match"]
                val ifNoneMatch = call.request.headers["If-None-Match"]
                val existing = files[path]
                when {
                    // 弱校验值参与 If-Match 时永远不匹配（RFC 7232 §3.1 强比较）
                    ifMatch != null && (existing == null || strongForm(existing.etag) != ifMatch) ->
                        call.respondText("", status = HttpStatusCode.PreconditionFailed)

                    ifNoneMatch == "*" && existing != null ->
                        call.respondText("", status = HttpStatusCode.PreconditionFailed)

                    else -> {
                        etagCounter += 1
                        val entry = Entry(body, "W/\"etag-$etagCounter\"")
                        files[path] = entry
                        call.response.header("ETag", entry.etag)
                        call.respondText("", status = HttpStatusCode.Created)
                    }
                }
            }

            else -> call.respondText("", status = HttpStatusCode.MethodNotAllowed)
        }
    }

    private fun strongForm(etag: String): String = etag.removePrefix("W/")
}
