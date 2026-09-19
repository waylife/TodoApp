package com.todoapp.sync

import com.todoapp.data.WebDavConfig
import io.ktor.client.HttpClient
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
 * 极简 WebDAV 客户端，只用到三个能力：
 * - MKCOL 建目录（逐级创建，405 视为已存在）
 * - GET 下载快照（404 视为首次同步）
 * - PUT 上传快照（If-Match / If-None-Match 乐观锁）
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
        val response: HttpResponse = httpClient.put(fileUrl()) {
            header(HttpHeaders.Authorization, authHeader())
            if (isNew) {
                header(HttpHeaders.IfNoneMatch, "*")
            } else {
                etag?.let { header(HttpHeaders.IfMatch, it) }
            }
            contentType(ContentType.Application.Json)
            setBody(content)
        }
        when {
            response.status.isSuccess() -> Unit
            response.status == HttpStatusCode.PreconditionFailed -> throw WebDavConflictException()
            response.status == HttpStatusCode.Conflict -> {
                // 个别服务器在目录缺失时返回 409：建目录后重试一次
                ensureRemoteDir()
                val retry: HttpResponse = httpClient.put(fileUrl()) {
                    header(HttpHeaders.Authorization, authHeader())
                    etag?.let { header(HttpHeaders.IfMatch, it) }
                    contentType(ContentType.Application.Json)
                    setBody(content)
                }
                if (!retry.status.isSuccess()) {
                    throw WebDavException("上传失败：HTTP ${retry.status.value}", retry.status.value)
                }
            }
            response.status == HttpStatusCode.Unauthorized ->
                throw WebDavException("认证失败（401），请检查用户名和密码", 401)
            else -> throw WebDavException("上传失败：HTTP ${response.status.value}", response.status.value)
        }
    }

    /** 逐级 MKCOL 创建远程目录；405（已存在）视为成功。 */
    suspend fun ensureRemoteDir() {
        var path = base
        for (seg in dirSegments) {
            path += "/" + encodeSegment(seg)
            val response: HttpResponse = httpClient.request(path) {
                method = HttpMethod("MKCOL")
                header(HttpHeaders.Authorization, authHeader())
            }
            val status = response.status
            if (!(status.isSuccess() || status == HttpStatusCode.MethodNotAllowed)) {
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

    private companion object {
        const val FILE_NAME = "todoapp.json"
    }
}
