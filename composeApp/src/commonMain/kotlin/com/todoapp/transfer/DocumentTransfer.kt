package com.todoapp.transfer

import androidx.compose.runtime.Composable

/** 文件选择的结果。取消不算失败，界面上不应报错。 */
sealed interface TransferOutcome<out T> {
    data class Ok<T>(val value: T) : TransferOutcome<T>

    /** 用户主动取消（点了取消/关掉对话框）。 */
    data object Cancelled : TransferOutcome<Nothing>

    /** 真实失败，[message] 可直接展示给用户。 */
    data class Failed(val message: String) : TransferOutcome<Nothing>
}

/** 用户挑中的文件。[name] 仅用于展示，[content] 是完整文本。 */
data class PickedDocument(val name: String, val content: String)

/**
 * 平台文件选择器：把备份文本以文件形式交给用户，或让用户挑一个备份文件读进来。
 *
 * 三端各自走系统原生实现（Android SAF / 桌面 AWT FileDialog / iOS UIDocumentPicker），
 * 因此界面层只依赖这个接口，业务逻辑（[TodoTransfer]）完全不感知平台差异。
 */
interface DocumentTransfer {

    /**
     * 弹出「保存」对话框，把 [content] 写入用户选定的文件。
     * 成功时返回可展示的位置描述（桌面端是绝对路径，移动端是文件名）。
     */
    suspend fun saveJson(suggestedName: String, content: String): TransferOutcome<String>

    /** 弹出「打开」对话框读取一个备份文件。 */
    suspend fun openJson(): TransferOutcome<PickedDocument>
}

/** 取得当前平台的文件选择器。Android 侧需要在组合内注册 Activity 结果回调，故为 Composable。 */
@Composable
expect fun rememberDocumentTransfer(): DocumentTransfer
