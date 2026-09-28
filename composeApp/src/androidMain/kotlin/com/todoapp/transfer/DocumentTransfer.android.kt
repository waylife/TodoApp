package com.todoapp.transfer

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import java.io.ByteArrayOutputStream
import java.io.InputStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 等待系统选择器回结果的槽位。
 *
 * 刻意放在**进程级单例**而不是组合里：Android 上设备旋转会重建 Activity，组合随之重来，
 * 但「用户正在选择器里挑文件」这件事并没有结束。若把等待方存在组合里，旋转后回调找不到
 * 等待方，那个协程就会永远挂着，界面按钮一直处于禁用状态。
 *
 * 选择器的结果回调本身是按 `rememberSaveable` 的 key 注册的，Activity 重建后仍能收到结果，
 * 所以只要等待方活得比组合久，整条链路就是安全的。进程被杀时什么都不剩，应用重启即恢复，
 * 也不存在残留状态。
 */
private object PendingPicker {
    /**
     * 每次发起选择对应一个等待方，SAF 的结果回调按发起顺序一一抵达，
     * 因此用 FIFO 出队保证「第 N 个回调完成第 N 个等待方」的身份对应。
     * 单槽位实现有串台竞态：若第 2 次发起先把槽位换成新等待方，晚到的
     * 第 1 个回调会把旧 URI 完成给新等待方——「选 A 却写进了 B 挑的文件」。
     */
    private val save = ArrayDeque<CompletableDeferred<Uri?>>()
    private val open = ArrayDeque<CompletableDeferred<Uri?>>()

    fun enqueueSave(deferred: CompletableDeferred<Uri?>) {
        save.addLast(deferred)
    }

    fun completeSave(uri: Uri?) {
        save.removeFirstOrNull()?.complete(uri)
    }

    fun enqueueOpen(deferred: CompletableDeferred<Uri?>) {
        open.addLast(deferred)
    }

    fun completeOpen(uri: Uri?) {
        open.removeFirstOrNull()?.complete(uri)
    }
}

/**
 * Android 走 SAF（Storage Access Framework），不需要任何存储权限：
 * - 导出用 `CreateDocument`，系统会让用户选目录并新建文件；
 * - 导入用 `OpenDocument`。
 *
 * 两者都必须在组合内注册结果回调，所以整个实现挂在 [rememberDocumentTransfer] 里。
 */
@Composable
actual fun rememberDocumentTransfer(): DocumentTransfer {
    val context = LocalContext.current

    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        PendingPicker.completeSave(uri)
    }
    val openLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        PendingPicker.completeOpen(uri)
    }

    return object : DocumentTransfer {

        override suspend fun saveJson(suggestedName: String, content: String): TransferOutcome<String> {
            val deferred = CompletableDeferred<Uri?>()
            PendingPicker.enqueueSave(deferred)
            saveLauncher.launch(suggestedName)
            val uri = deferred.await() ?: return TransferOutcome.Cancelled
            return withContext(Dispatchers.IO) {
                try {
                    // "wt" = 覆盖写入并截断，避免目标文件已存在时残留旧内容尾部
                    context.contentResolver.openOutputStream(uri, "wt")?.use { output ->
                        output.write(content.encodeToByteArray())
                    } ?: return@withContext TransferOutcome.Failed("无法写入所选文件")
                    TransferOutcome.Ok(displayName(context, uri, suggestedName))
                } catch (e: Exception) {
                    TransferOutcome.Failed("写入失败：${e.message ?: "未知错误"}")
                }
            }
        }

        override suspend fun openJson(): TransferOutcome<PickedDocument> {
            val deferred = CompletableDeferred<Uri?>()
            PendingPicker.enqueueOpen(deferred)
            // 部分文件管理器把 .json 报成 application/octet-stream，带上通配避免用户根本选不中
            openLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
            val uri = deferred.await() ?: return TransferOutcome.Cancelled
            return withContext(Dispatchers.IO) {
                try {
                    val text = context.contentResolver.openInputStream(uri)?.use { input ->
                        // 边读边计数：选择器允许任意文件，误选大文件时直接读进内存会 OOM
                        input.readBounded(TodoTransfer.MAX_BACKUP_BYTES)
                    } ?: return@withContext TransferOutcome.Failed("无法读取所选文件")
                    if (text == null) {
                        return@withContext TransferOutcome.Failed(TodoTransfer.tooLargeMessage())
                    }
                    TransferOutcome.Ok(PickedDocument(displayName(context, uri, "备份文件"), text.decodeToString()))
                } catch (e: Exception) {
                    TransferOutcome.Failed("读取失败：${e.message ?: "未知错误"}")
                }
            }
        }
    }
}

/**
 * 取一个能展示给用户的名字。
 *
 * **不能只看 `uri.lastPathSegment`**：SAF 返回的 Uri 形态取决于具体 provider，
 * 实测 Downloads provider 给的是 `document/3` 这种数字 id，直接展示会变成「位置：3」。
 * 正确做法是查 ContentResolver 的 `OpenableColumns.DISPLAY_NAME`；
 * 查不到再退回路径段，最后退回建议名。
 */
private fun displayName(context: Context, uri: Uri, fallback: String): String {
    val fromResolver = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
    }.getOrNull()
    return fromResolver?.takeIf { it.isNotBlank() }
        ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
        ?: fallback
}

/**
 * 最多读 [maxBytes] 字节，超出返回 null（表示「太大，不读」）。
 *
 * 刻意不用 `readBytes()`：它会一次性把整个文件拉进内存。SAF 允许用户选中任意文件，
 * 误选一个大文件时会直接把应用 OOM 掉，而这里在超限的第一时间就停下。
 */
private fun InputStream.readBounded(maxBytes: Long): ByteArray? {
    val buffer = ByteArrayOutputStream()
    val chunk = ByteArray(64 * 1024)
    var total = 0L
    while (true) {
        val read = read(chunk)
        if (read < 0) break
        total += read
        if (total > maxBytes) return null
        buffer.write(chunk, 0, read)
    }
    return buffer.toByteArray()
}
