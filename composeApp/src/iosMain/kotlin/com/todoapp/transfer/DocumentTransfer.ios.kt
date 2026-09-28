package com.todoapp.transfer

import androidx.compose.runtime.Composable
import java.io.ByteArrayOutputStream
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CompletableDeferred
import platform.Foundation.NSData
import platform.Foundation.NSFileHandle
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileSize
import platform.Foundation.NSNumber
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
// NSData 的工厂方法在 Kotlin/Native 里是 companion 上的扩展函数，必须单独 import 才能用
import platform.Foundation.create
import platform.UIKit.UIApplication
import platform.UIKit.UIDocumentPickerDelegateProtocol
import platform.UIKit.UIDocumentPickerViewController
import platform.UIKit.UIViewController
import platform.UIKit.UIWindow
import platform.UIKit.UIWindowScene
import platform.UIKit.UISceneActivationStateForegroundActive
import platform.UniformTypeIdentifiers.UTTypeJSON
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

/**
 * iOS 用系统文档选择器 [UIDocumentPickerViewController]：
 * - 导出：先把备份写到应用沙盒的临时目录，再交给选择器「导出到…」（用户在「文件」里选位置）；
 * - 导入：`asCopy = true` 让系统把用户选中的文件**拷贝**进沙盒，
 *   因此不必处理 security-scoped bookmark，直接读拷贝即可。
 *
 * 选择器是异步回调式的，这里用 [CompletableDeferred] 把它桥成 suspend 函数。
 * 注意 delegate 必须由自己强引用：UIKit 对 delegate 是 weak 的，只交给 picker 会被提前释放。
 */
@OptIn(ExperimentalForeignApi::class)
private class IosDocumentTransfer : DocumentTransfer {

    private var pendingSave: CompletableDeferred<NSURL?>? = null
    private var pendingOpen: CompletableDeferred<NSURL?>? = null
    private var activeDelegate: PickerDelegate? = null

    override suspend fun saveJson(suggestedName: String, content: String): TransferOutcome<String> {
        val path = NSTemporaryDirectory().trimEnd('/') + "/" + suggestedName
        if (!writeText(path, content)) {
            return TransferOutcome.Failed("无法写入临时文件")
        }
        val url = NSURL.fileURLWithPath(path)

        val deferred = replaceSave(CompletableDeferred())
        val picker = UIDocumentPickerViewController(forExportingURLs = listOf(url), asCopy = true)
        picker.delegate = attachDelegate(
            onPicked = { urls ->
                pendingSave?.complete(urls.firstOrNull() as? NSURL)
                pendingSave = null
            },
            onCancelled = {
                pendingSave?.complete(null)
                pendingSave = null
            },
        )
        present(picker) {
            // 弹不出去（拿不到 keyWindow 等）时立刻以「取消」收场，等待方不能永久挂起
            pendingSave?.complete(null)
            pendingSave = null
        }

        try {
            val destination = deferred.await() ?: return TransferOutcome.Cancelled
            return TransferOutcome.Ok(destination.lastPathComponent ?: suggestedName)
        } finally {
            // asCopy 导出在回调时已完成拷贝，临时源文件不再需要，及时清理避免堆积
            NSFileManager.defaultManager.removeItemAtPath(path, error = null)
        }
    }

    override suspend fun openJson(): TransferOutcome<PickedDocument> {
        val deferred = replaceOpen(CompletableDeferred())
        val picker = UIDocumentPickerViewController(forOpeningContentTypes = listOf(UTTypeJSON), asCopy = true)
        picker.delegate = attachDelegate(
            onPicked = { urls ->
                pendingOpen?.complete(urls.firstOrNull() as? NSURL)
                pendingOpen = null
            },
            onCancelled = {
                pendingOpen?.complete(null)
                pendingOpen = null
            },
        )
        present(picker) {
            pendingOpen?.complete(null)
            pendingOpen = null
        }

        val url = deferred.await() ?: return TransferOutcome.Cancelled
        val path = url.path ?: return TransferOutcome.Failed("无法定位所选文件")
        // 先看大小快速拒绝；属性取不到时改用受限读取兜底——
        // 无论哪种情况都绝不把未知大小的文件整段拉进内存
        val size = fileSize(path)
        if (size != null && size > TodoTransfer.MAX_BACKUP_BYTES) {
            return TransferOutcome.Failed(TodoTransfer.tooLargeMessage())
        }
        val text = try {
            readBounded(path, TodoTransfer.MAX_BACKUP_BYTES).decodeToString()
        } catch (_: FileTooLargeException) {
            return TransferOutcome.Failed(TodoTransfer.tooLargeMessage())
        } catch (_: Exception) {
            return TransferOutcome.Failed("无法读取所选文件")
        }
        return TransferOutcome.Ok(PickedDocument(url.lastPathComponent ?: "备份文件", text))
    }

    /**
     * 换上新的等待方，并让上一个永远等不到结果的等待方立刻以「已取消」结束——
     * 否则它会一直挂起，界面按钮永远处于禁用状态。
     */
    private fun replaceSave(deferred: CompletableDeferred<NSURL?>): CompletableDeferred<NSURL?> {
        val previous = pendingSave
        pendingSave = deferred
        previous?.complete(null)
        return deferred
    }

    private fun replaceOpen(deferred: CompletableDeferred<NSURL?>): CompletableDeferred<NSURL?> {
        val previous = pendingOpen
        pendingOpen = deferred
        previous?.complete(null)
        return deferred
    }

    /** 选择器必须从主线程弹出，且要挂在当前最上层的控制器上（否则在已弹出面板时会被丢弃）。
     *  弹出失败时调用 [onPresentationFailed]——通常意味着拿不到窗口，等待方必须立刻收场。 */
    private fun present(picker: UIViewController, onPresentationFailed: () -> Unit) {
        dispatch_async(dispatch_get_main_queue()) {
            val top = topViewController()
            if (top == null) {
                onPresentationFailed()
            } else {
                top.presentViewController(picker, animated = true, completion = null)
            }
        }
    }

    private fun topViewController(): UIViewController? {
        var controller = keyWindow()?.rootViewController
        while (controller?.presentedViewController != null) {
            controller = controller.presentedViewController
        }
        return controller
    }

    /** iOS 13 起 keyWindow 已废弃且多 scene 下可能为 nil：优先从激活中的 scene 取 key window。 */
    private fun keyWindow(): UIWindow? {
        val activeScene = UIApplication.sharedApplication.connectedScenes
            .filterIsInstance<UIWindowScene>()
            .firstOrNull { it.activationState == UISceneActivationStateForegroundActive }
        return activeScene?.windows?.firstOrNull { it.isKeyWindow }
            ?: UIApplication.sharedApplication.keyWindow
    }

    private fun attachDelegate(
        onPicked: (List<*>) -> Unit,
        onCancelled: () -> Unit,
    ): PickerDelegate = PickerDelegate(onPicked, onCancelled).also { activeDelegate = it }

    private fun writeText(path: String, content: String): Boolean =
        NSFileManager.defaultManager.createFileAtPath(
            path = path,
            contents = content.encodeToByteArray().toNSData(),
            attributes = null,
        )

    /** 读到的字节数超过 [maxBytes] 时抛 [FileTooLargeException]，与「打不开文件」相区分。 */
    private class FileTooLargeException : Exception()

    /**
     * 边读边计数的受限读取。contentsAtPath 会把整个文件拉进内存，而选择器允许选中任意
     * 文件——即便大小属性读取失败（此时无法预检），也必须在超限的第一时间停下而不是 OOM。
     */
    private fun readBounded(path: String, maxBytes: Long): ByteArray {
        val handle = NSFileHandle.fileHandleForReadingAtPath(path)
            ?: throw IllegalStateException("无法打开文件")
        try {
            val buffer = ByteArrayOutputStream()
            while (true) {
                val chunk = handle.readDataOfLength(READ_CHUNK_BYTES.toULong()) ?: break
                val bytes = chunk.toByteArray()
                if (bytes.isEmpty()) break
                buffer.write(bytes)
                if (buffer.size() > maxBytes) throw FileTooLargeException()
            }
            return buffer.toByteArray()
        } finally {
            handle.closeFile()
        }
    }

    /** 文件字节数；取不到属性时返回 null（此时由 [readBounded] 的上限兜底）。 */
    private fun fileSize(path: String): Long? {
        val attributes = NSFileManager.defaultManager.attributesOfItemAtPath(path, error = null)
            ?: return null
        return (attributes[NSFileSize] as? NSNumber)?.longLongValue
    }
}

/** 受限读取的分块大小。 */
private const val READ_CHUNK_BYTES = 64 * 1024

private class PickerDelegate(
    private val onPicked: (List<*>) -> Unit,
    private val onCancelled: () -> Unit,
) : NSObject(), UIDocumentPickerDelegateProtocol {

    override fun documentPicker(controller: UIDocumentPickerViewController, didPickDocumentsAtURLs: List<*>) {
        onPicked(didPickDocumentsAtURLs)
    }

    override fun documentPickerWasCancelled(controller: UIDocumentPickerViewController) {
        onCancelled()
    }
}

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private fun ByteArray.toNSData(): NSData = usePinned { pinned ->
    NSData.create(bytes = pinned.addressOf(0), length = size.toULong())
}

@OptIn(ExperimentalForeignApi::class)
private fun NSData.toByteArray(): ByteArray {
    val count = length.toInt()
    if (count == 0) return ByteArray(0)
    val pointer = bytes ?: return ByteArray(0)
    return pointer.readBytes(count)
}

/**
 * 进程级单例。与 Android 侧同理：等待选择器回结果的等待方必须活得比组合久，
 * 否则组合一旦重建，回调就找不到等待方。iOS 不会像 Android 那样因旋转重建界面，
 * 但单例的代价为零，同时也保证 delegate 不会被提前释放。
 */
private val sharedTransfer = IosDocumentTransfer()

@Composable
actual fun rememberDocumentTransfer(): DocumentTransfer = sharedTransfer
