package com.todoapp.viewmodel

import com.todoapp.data.TodoRepository
import com.todoapp.model.RemoteSnapshot
import com.todoapp.sync.SyncEngine
import com.todoapp.transfer.DocumentTransfer
import com.todoapp.transfer.ImportMode
import com.todoapp.transfer.ImportPreview
import com.todoapp.transfer.TodoTransfer
import com.todoapp.transfer.TransferOutcome
import com.todoapp.util.Dates
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 导入导出界面状态。 */
data class TransferUiState(
    /** 正在读写文件或等待用户在选择器里操作，此时按钮应禁用。 */
    val busy: Boolean = false,
    /** 上一次操作的结果提示；成功与失败共用，靠 [isError] 区分颜色。 */
    val message: String? = null,
    val isError: Boolean = false,
    /** 已选好并解析成功的备份，等用户在确认框里选择导入方式。 */
    val pending: ImportPreview? = null,
)

/**
 * 数据导入导出。
 *
 * 文件读写的平台差异全部收敛在 [DocumentTransfer] 里，这里只负责：
 * 组装备份内容 → 交给选择器 → 解析用户选中的文件 → 让用户确认导入方式 → 写库并触发同步。
 *
 * 导入**不直接**用「清空再写入」：默认走与同步相同的 LWW 合并（[TodoTransfer.apply]），
 * 这样从旧备份导入不会把本机后来新增的待办抹掉。
 */
class TransferViewModel(
    private val repository: TodoRepository,
    private val syncEngine: SyncEngine,
    private val scope: CoroutineScope,
    private val clock: () -> Long = { Dates.nowMillis() },
) {
    private val _state = MutableStateFlow(TransferUiState())
    val state: StateFlow<TransferUiState> = _state.asStateFlow()

    /** 解析好的备份，确认导入时使用；取消或完成后置空，避免误用上一次的文件。 */
    private var pendingSnapshot: RemoteSnapshot? = null

    /** 导出为文件。备份内容与 WebDAV 上的 todoapp.json 完全同构。 */
    fun export(transfer: DocumentTransfer) {
        if (_state.value.busy) return
        _state.value = _state.value.copy(busy = true, message = null)
        scope.launch {
            try {
                val snapshot = repository.currentSnapshot()
                val content = TodoTransfer.encode(snapshot)
                val fileName = TodoTransfer.suggestedFileName(clock())
                _state.value = when (val outcome = transfer.saveJson(fileName, content)) {
                    is TransferOutcome.Ok -> {
                        val stats = TodoTransfer.stats(snapshot)
                        val tombstones = stats.deletedLists + stats.deletedItems
                        TransferUiState(
                            message = "已导出 ${stats.lists} 个清单、${stats.items} 条待办" +
                                (if (tombstones > 0) "（含 $tombstones 条已删除记录）" else "") +
                                "\n位置：${outcome.value}",
                        )
                    }
                    // 用户点了取消，不是错误，也不该留提示
                    TransferOutcome.Cancelled -> TransferUiState()
                    is TransferOutcome.Failed -> TransferUiState(message = "导出失败：${outcome.message}", isError = true)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // 快照/编码等本进程错误：必须复位 busy 并提示，否则导出按钮永久禁用
                _state.value = TransferUiState(message = "导出失败：${e.message ?: e::class.simpleName}", isError = true)
            }
        }
    }

    /** 选择备份文件并解析，成功则弹出确认框（此时还没动本机数据）。 */
    fun pickImportFile(transfer: DocumentTransfer) {
        if (_state.value.busy) return
        _state.value = _state.value.copy(busy = true, message = null)
        scope.launch {
            try {
                _state.value = when (val outcome = transfer.openJson()) {
                    is TransferOutcome.Ok -> parse(outcome.value.name, outcome.value.content)
                    TransferOutcome.Cancelled -> TransferUiState()
                    is TransferOutcome.Failed -> TransferUiState(message = "读取失败：${outcome.message}", isError = true)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = TransferUiState(message = "读取失败：${e.message ?: e::class.simpleName}", isError = true)
            }
        }
    }

    /**
     * 直接拿一段备份文本进入「待确认」状态，跳过文件选择。
     * [pickImportFile] 内部也走这里；同时让界面自动化验证可以直接喂一段 JSON 把确认框渲染出来。
     */
    fun previewBackup(fileName: String, content: String) {
        _state.value = parse(fileName, content)
    }

    /** 用户确认导入。[mode] 决定合并还是覆盖。 */
    fun confirmImport(mode: ImportMode) {
        val imported = pendingSnapshot ?: return
        pendingSnapshot = null
        try {
            val now = clock()
            val applied = TodoTransfer.apply(repository.currentSnapshot(), imported, mode, now)
            repository.replaceAll(applied.lists, applied.items)

            // 整文件快照同步没有增量概念：不主动推一次，导入的数据就只留在本机。
            // replaceAll 会临时关闭仓库的变更回调，所以这里必须显式触发。
            syncEngine.scheduleSync(delayMillis = 0)

            val stats = TodoTransfer.stats(imported)
            val label = if (mode == ImportMode.MERGE) "已合并导入" else "已覆盖导入"
            _state.value = TransferUiState(
                message = "$label ${stats.lists} 个清单、${stats.items} 条待办，正在同步到其它设备…",
            )
        } catch (e: Exception) {
            // 写库失败：提示错误并收起确认框（pending 已置空），不留半死状态
            _state.value = TransferUiState(message = "导入失败：${e.message ?: e::class.simpleName}", isError = true)
        }
    }

    /** 关掉确认框，丢弃已解析的备份。 */
    fun dismissImport() {
        pendingSnapshot = null
        _state.value = _state.value.copy(pending = null)
    }

    private fun parse(fileName: String, content: String): TransferUiState =
        TodoTransfer.decode(content).fold(
            onSuccess = { imported ->
                pendingSnapshot = imported
                TransferUiState(
                    pending = TodoTransfer.preview(fileName, repository.currentSnapshot(), imported, clock()),
                )
            },
            onFailure = { error ->
                TransferUiState(message = "无法导入：${error.message ?: "文件无法解析"}", isError = true)
            },
        )
}
