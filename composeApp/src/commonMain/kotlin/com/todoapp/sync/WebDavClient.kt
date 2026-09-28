package com.todoapp.sync

import com.todoapp.data.WebDavConfig
import io.ktor.client.HttpClient
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.put
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/** WebDAV 操作失败。 */
open class WebDavException(message: String, val statusCode: Int? = null) : Exception(message)

/** 上传冲突（服务器返回 412），由同步引擎重试。 */
class WebDavConflictException : WebDavException("远程数据已被其它设备修改", 412)

/**
 * 极简 WebDAV 客户端，只用到四个能力：
 * - MKCOL 建目录（逐级创建，405 视为已存在）
 * - GET 下载快照（404 视为首次同步）
 * - PUT 上传快照（If-Match / If-None-Match 乐观锁）
 * - DELETE 删除快照（设置页「删除远端数据」，删除后回读确认）
 * 认证使用 Basic（HTTPS 下传输）。MVP 不支持自签名证书。
 */
class WebDavClient(private val httpClient: HttpClient, private val config: WebDavConfig) {

    private val base = config.serverUrl.trim().trimEnd('/')

    /** 逐级目录段（已过滤空段）。 */
    private val dirSegments: List<String> =
        config.remoteDir.trim().trim('/').split('/').filter { it.isNotBlank() }

    private fun fileUrl(): String = buildString {
        append(base)
        for (seg in dirSegments) append('/').append(encodeSegment(seg))
        append('/').append(FILE_NAME)
    }

    private fun authHeader(): String {
        val token = Base64.encode("${config.username}:${config.password}".encodeToByteArray())
        return "Basic $token"
    }

    /** 下载远程快照。返回 (正文, ETag)；远程不存在时正文为 null（首次同步）。 */
    suspend fun downloadFile(): Pair<String?, String?> {
        val response: HttpResponse = httpClient.get(fileUrl()) {
            header(HttpHeaders.Authorization, authHeader())
            // 显式声明 identity：OkHttp 默认会加 Accept-Encoding: gzip 并透明解压，
            // 而 Apache mod_deflate 对压缩过的响应会把 ETag 改写成 "...-gzip"（RFC 7232
            // 意义上「压缩表示」与「原始表示」本就是不同的 entity-tag）。该 ETag 拿去
            // 做 PUT 的 If-Match 时，服务器按未压缩表示比较，必然 412。
            // 让 GET 与 PUT 使用同一表示，从源头避免这种不匹配。
            header(HttpHeaders.AcceptEncoding, "identity")
        }
        return when {
            response.status == HttpStatusCode.NotFound -> null to response.headers[HttpHeaders.ETag]
            response.status.isSuccess() -> response.bodyAsText() to response.headers[HttpHeaders.ETag]
            response.status == HttpStatusCode.Unauthorized ->
                throw WebDavException("认证失败（401），请检查用户名和密码", 401)
            response.status == HttpStatusCode.Forbidden ->
                throw WebDavException("服务器拒绝访问（403），请检查权限", 403)
            else -> throw WebDavException("下载失败：HTTP ${response.status.value}", response.status.value)
        }
    }

    /** 上传快照。isNew 时用 If-None-Match: * 防止覆盖别人的新文件；否则用 If-Match。 */
    suspend fun uploadFile(content: String, etag: String?, isNew: Boolean) {
        val response = putWithPrecondition(content, etag, isNew)
        when {
            response.status.isSuccess() -> Unit
            response.status == HttpStatusCode.PreconditionFailed -> throw WebDavConflictException()
            response.status == HttpStatusCode.Conflict -> {
                // 个别服务器在目录缺失时返回 409：建目录后重试一次，前置条件原样保留。
                // 重试仍可能 412（目录补建期间文件被并发创建/修改）：必须抛冲突异常，
                // 让同步引擎重走「下载→合并→上传」，而不是把冲突当普通失败中断同步。
                ensureRemoteDir()
                val retry = putWithPrecondition(content, etag, isNew)
                when {
                    retry.status.isSuccess() -> Unit
                    retry.status == HttpStatusCode.PreconditionFailed -> throw WebDavConflictException()
                    else -> throw WebDavException("上传失败：HTTP ${retry.status.value}", retry.status.value)
                }
            }
            response.status == HttpStatusCode.Unauthorized ->
                throw WebDavException("认证失败（401），请检查用户名和密码", 401)
            else -> throw WebDavException("上传失败：HTTP ${response.status.value}", response.status.value)
        }
    }

    /**
     * 组装带前置条件的 PUT，三处上传（首次/覆盖/409 重试）共用，避免各写一份造成语义漂移。
     * 服务器 GET 不返回 ETag 时退化为 `If-Match: *`（「资源仍存在」即放行）：拿不到具体
     * 标签就没有真正的乐观锁，但仍能把「文件已被删除/重建」的并发变成可检测的 412，
     * 而不是静默覆盖；对根本不认识 If-Match 的极简服务器则等价于原来的裸 PUT。
     */
    private suspend fun putWithPrecondition(content: String, etag: String?, isNew: Boolean): HttpResponse =
        httpClient.put(fileUrl()) {
            header(HttpHeaders.Authorization, authHeader())
            if (isNew) {
                header(HttpHeaders.IfNoneMatch, "*")
            } else {
                header(HttpHeaders.IfMatch, etag?.let(::strongEtag) ?: "*")
            }
            contentType(ContentType.Application.Json)
            setBody(content)
        }

    /**
     * 归一化 ETag 供 If-Match 使用，剥掉两类会让服务器必然返回 412 的修饰：
     * - 弱校验前缀 `W/`。[downloadFile] 拿到的值可能带 `W/`（实测 Teracloud/Apache 会
     *   间歇性返回 `W/"..."`），而 If-Match 按 RFC 7232 §3.1 只做强比较，弱校验值会被拒。
     * - `-gzip` 后缀。Apache mod_deflate 压缩响应时会把 ETag 改写成 `"...-gzip"`，它标识的是
     *   「压缩后的表示」；而 PUT 上传的是未压缩实体，服务器按未压缩表示的 ETag 比较，
     *   原样送出同样必然 412。GET 侧已改用 identity 规避，这里作为代理强制压缩时的兜底。
     */
    private fun strongEtag(etag: String?): String? {
        var value = etag?.trim()?.removePrefix("W/") ?: return null
        if (value.endsWith("-gzip\"")) value = value.removeSuffix("-gzip\"") + "\""
        return value
    }

    /**
     * 逐级 MKCOL 创建远程目录。URL 必须带结尾斜杠：集合已存在时，Apache 系服务器
     * 会对缺少斜杠的 URL 返回 301（mod_dir），而客户端不跟随非 GET 重定向。
     * 405（已存在）与 3xx（重定向到集合本身）都视作「目录已就绪」。
     */
    suspend fun ensureRemoteDir() {
        var path = base
        for (seg in dirSegments) {
            path += "/" + encodeSegment(seg)
            val response: HttpResponse = httpClient.request("$path/") {
                method = HttpMethod("MKCOL")
                header(HttpHeaders.Authorization, authHeader())
            }
            val status = response.status
            val dirReady = status.isSuccess() ||
                status == HttpStatusCode.MethodNotAllowed ||
                status.value in 300..399
            if (!dirReady) {
                throw WebDavException("创建远程目录失败：HTTP ${status.value}", status.value)
            }
        }
    }

    /** 设置页「测试连接」：建目录 + 下载探测，返回提示信息。 */
    suspend fun testConnection(): Result<String> = try {
        ensureRemoteDir()
        val (_, _) = downloadFile()
        Result.success("连接成功，目录可用")
    } catch (e: WebDavException) {
        Result.failure(e)
    } catch (e: Exception) {
        Result.failure(WebDavException("无法连接服务器：${e.message ?: "网络错误"}"))
    }

    /**
     * 删除远程快照。404 也算成功（远端本就没有），因此可以重复执行。
     * 删除后回读一次：部分服务器（或反向代理）对 DELETE 返回 2xx 却并未真正删除，
     * 静默失败会让界面谎报「已清空」，此处宁可报错。
     */
    suspend fun deleteFile() {
        val response: HttpResponse = httpClient.delete(fileUrl()) {
            header(HttpHeaders.Authorization, authHeader())
        }
        when {
            response.status.isSuccess() -> Unit
            response.status == HttpStatusCode.NotFound -> Unit
            response.status == HttpStatusCode.Unauthorized ->
                throw WebDavException("认证失败（401），请检查用户名和密码", 401)
            response.status == HttpStatusCode.Forbidden ->
                throw WebDavException("服务器拒绝访问（403），请检查权限", 403)
            else -> throw WebDavException("服务器返回 HTTP ${response.status.value}", response.status.value)
        }
        val (remaining, _) = downloadFile()
        if (remaining != null) {
            throw WebDavException("服务器未真正删除该文件（回读仍有内容），请检查该目录的写入权限")
        }
    }

    private fun encodeSegment(segment: String): String {
        val safe = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"
        val bytes = segment.encodeToByteArray()
        return buildString {
            for (b in bytes) {
                val c = b.toInt().toChar()
                if (c in safe) append(c) else append('%').append(b.toInt().and(0xFF).toString(16).padStart(2, '0').uppercase())
            }
        }
    }

    companion object {
        /** 远端快照文件名。设置页展示远端路径时复用，避免两处各写一份。 */
        const val FILE_NAME = "todoapp.json"
    }
}
