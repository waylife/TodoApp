package com.todoapp.transfer

import androidx.compose.runtime.Composable
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CompletableDeferred
import platform.Foundation.NSData
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
        present(picker)

        val destination = deferred.await() ?: return TransferOutcome.Cancelled
        return TransferOutcome.Ok(destination.lastPathComponent ?: suggestedName)
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
        present(picker)

        val url = deferred.await() ?: return TransferOutcome.Cancelled
        val path = url.path ?: return TransferOutcome.Failed("无法定位所选文件")
        // 先看大小再读：contentsAtPath 会把整个文件拉进内存，误选大文件会 OOM
        val size = fileSize(path)
        if (size != null && size > TodoTransfer.MAX_BACKUP_BYTES) {
            return TransferOutcome.Failed(TodoTransfer.tooLargeMessage())
        }
        val text = readText(path) ?: return TransferOutcome.Failed("无法读取所选文件")
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

    /** 选择器必须从主线程弹出，且要挂在当前最上层的控制器上（否则在已弹出面板时会被丢弃）。 */
    private fun present(picker: UIViewController) {
        dispatch_async(dispatch_get_main_queue()) {
            topViewController()?.presentViewController(picker, animated = true, completion = null)
        }
    }

    private fun topViewController(): UIViewController? {
        var controller = UIApplication.sharedApplication.keyWindow?.rootViewController
        while (controller?.presentedViewController != null) {
            controller = controller.presentedViewController
        }
        return controller
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

    private fun readText(path: String): String? =
        NSFileManager.defaultManager.contentsAtPath(path)?.toByteArray()?.decodeToString()

    /** 文件字节数；取不到属性时返回 null（此时不拦截，交给读取本身去失败）。 */
    private fun fileSize(path: String): Long? {
        val attributes = NSFileManager.defaultManager.attributesOfItemAtPath(path, error = null)
            ?: return null
        return (attributes[NSFileSize] as? NSNumber)?.longLongValue
    }
}

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
