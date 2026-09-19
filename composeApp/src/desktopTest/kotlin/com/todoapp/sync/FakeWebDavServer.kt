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
        val path = call.request.path().trim('/')
        when (call.request.httpMethod.value.uppercase()) {
            "MKCOL" -> {
                if (createdDirs.contains(path) || files.containsKey(path)) {
                    call.respondText("", status = HttpStatusCode.MethodNotAllowed)
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
                    ifMatch != null && (existing == null || existing.etag != ifMatch) ->
                        call.respondText("", status = HttpStatusCode.PreconditionFailed)

                    ifNoneMatch == "*" && existing != null ->
                        call.respondText("", status = HttpStatusCode.PreconditionFailed)

                    else -> {
                        etagCounter += 1
                        val entry = Entry(body, "\"etag-$etagCounter\"")
                        files[path] = entry
                        call.response.header("ETag", entry.etag)
                        call.respondText("", status = HttpStatusCode.Created)
                    }
                }
            }

            else -> call.respondText("", status = HttpStatusCode.MethodNotAllowed)
        }
    }
}
