package com.todoapp.transfer

import androidx.compose.runtime.Composable
import java.awt.EventQueue
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 选文件对话框的动作：返回用户选定的文件，取消时返回 null。 */
internal fun interface FileChooser {
    fun choose(title: String, suggestedName: String?, mode: Int): File?
}

/** 生产实现：弹出 AWT 原生对话框。 */
private val awtFileChooser = FileChooser { title, suggestedName, mode ->
    showAwtDialog(title, suggestedName, mode)
}

/**
 * 桌面端用 AWT 的 [FileDialog]：在 macOS 上它就是原生 NSSavePanel / NSOpenPanel，
 * 在 Windows / Linux 上是系统文件对话框，不需要引入任何额外依赖。
 *
 * [chooseFile] 可注入是为了让测试能覆盖「拿到文件之后怎么读写」这一段真实代码——
 * 原生模态对话框无法脚本驱动（需要辅助功能权限），把选择动作替换掉之后，
 * 除对话框本身以外的逻辑都能被自动化验证。
 */
internal class DesktopDocumentTransfer(
    private val chooseFile: FileChooser = awtFileChooser,
) : DocumentTransfer {

    override suspend fun saveJson(suggestedName: String, content: String): TransferOutcome<String> =
        withContext(Dispatchers.IO) {
            val target = chooseFile.choose(
                title = "导出待办数据",
                suggestedName = suggestedName,
                mode = FileDialog.SAVE,
            ) ?: return@withContext TransferOutcome.Cancelled
            try {
                target.writeText(content)
                TransferOutcome.Ok(target.absolutePath)
            } catch (e: Exception) {
                TransferOutcome.Failed("写入失败：${e.message ?: "未知错误"}")
            }
        }

    override suspend fun openJson(): TransferOutcome<PickedDocument> = withContext(Dispatchers.IO) {
        val source = chooseFile.choose(
            title = "导入待办数据",
            suggestedName = null,
            mode = FileDialog.LOAD,
        ) ?: return@withContext TransferOutcome.Cancelled
        // 先看大小再读：误选到一个大文件时，读进内存会把应用撑爆
        if (source.length() > TodoTransfer.MAX_BACKUP_BYTES) {
            return@withContext TransferOutcome.Failed(TodoTransfer.tooLargeMessage())
        }
        try {
            TransferOutcome.Ok(PickedDocument(source.name, source.readText()))
        } catch (e: Exception) {
            TransferOutcome.Failed("读取失败：${e.message ?: "未知错误"}")
        }
    }
}

/**
 * 弹出模态文件对话框并返回用户选定的文件；取消时返回 null。
 *
 * AWT 的对话框必须在事件派发线程上创建，而这里调用自协程的 IO 线程，
 * 因此统一 `invokeAndWait` 切过去（`FileDialog.isVisible = true` 是阻塞的）。
 * 用 `null` 作为父窗口是 AWT 允许的写法，会挂到一个共享的隐藏 owner 上。
 */
private fun showAwtDialog(title: String, suggestedName: String?, mode: Int): File? {
    var picked: File? = null
    val show = {
        val dialog = FileDialog(null as Frame?, title, mode)
        if (suggestedName != null) {
            dialog.file = suggestedName
        }
        // 只让 .json 可选，避免用户误选到别的文件（部分平台会忽略过滤器，属可接受降级）
        dialog.setFilenameFilter { _, name -> name.endsWith(".${TodoTransfer.EXTENSION}", ignoreCase = true) }
        dialog.isVisible = true
        val directory = dialog.directory
        val name = dialog.file
        if (directory != null && name != null) picked = File(directory, name)
        dialog.dispose()
    }
    if (EventQueue.isDispatchThread()) show() else EventQueue.invokeAndWait(show)
    return picked
}

/** 无状态，进程级单例即可（与另两端保持一致的写法）。 */
private val sharedTransfer = DesktopDocumentTransfer()

@Composable
actual fun rememberDocumentTransfer(): DocumentTransfer = sharedTransfer
