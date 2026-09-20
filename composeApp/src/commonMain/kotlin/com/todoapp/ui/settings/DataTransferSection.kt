package com.todoapp.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.todoapp.transfer.ImportMode
import com.todoapp.transfer.ImportPreview
import com.todoapp.transfer.rememberDocumentTransfer
import com.todoapp.ui.common.SettingsSectionTitle
import com.todoapp.util.Dates
import com.todoapp.viewmodel.TransferViewModel

/**
 * 设置页的「数据备份」区块：导出为文件 / 从文件导入。
 *
 * 文件选择器由 [rememberDocumentTransfer] 提供，因此这一层不需要知道
 * 底下是 SAF、AWT FileDialog 还是 UIDocumentPicker。
 */
@Composable
fun DataTransferSection(viewModel: TransferViewModel) {
    val state by viewModel.state.collectAsState()
    val transfer = rememberDocumentTransfer()

    SettingsSectionTitle("数据备份")
    Text(
        text = "导出为 JSON 文件保存到本机，或从备份文件恢复。文件格式与 WebDAV 同步文件完全一致，" +
            "包含 id、时间戳与删除记录，可用于换机迁移或长期归档。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Row(modifier = Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(
            onClick = { viewModel.export(transfer) },
            enabled = !state.busy,
        ) {
            Text("导出为文件…")
        }
        OutlinedButton(
            onClick = { viewModel.pickImportFile(transfer) },
            modifier = Modifier.padding(start = 12.dp),
            enabled = !state.busy,
        ) {
            Text("从文件导入…")
        }
        if (state.busy) {
            CircularProgressIndicator(
                modifier = Modifier.padding(start = 12.dp).size(18.dp),
                strokeWidth = 2.dp,
            )
        }
    }

    state.message?.let { message ->
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = if (state.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 10.dp),
        )
    }

    state.pending?.let { preview ->
        ImportConfirmDialog(
            preview = preview,
            onConfirm = viewModel::confirmImport,
            onDismiss = viewModel::dismissImport,
        )
    }
}

/**
 * 导入确认框。展示备份里到底有什么、以及按每种方式导入会改动多少，
 * 再让用户选「合并」还是「覆盖」——这两者后果差别很大，不能用一个按钮糊过去。
 */
@Composable
private fun ImportConfirmDialog(
    preview: ImportPreview,
    onConfirm: (ImportMode) -> Unit,
    onDismiss: () -> Unit,
) {
    var mode by remember { mutableStateOf(ImportMode.MERGE) }
    val stats = preview.stats
    val tombstones = stats.deletedLists + stats.deletedItems
    val emptyBackup = stats.lists == 0 && stats.items == 0

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("导入备份数据？") },
        text = {
            Column {
                Text(preview.fileName, style = MaterialTheme.typography.labelLarge)
                if (preview.exportedAt > 0) {
                    Text(
                        text = "导出时间：${Dates.formatSyncTime(preview.exportedAt)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = "包含 ${stats.lists} 个清单、${stats.items} 条待办（已完成 ${stats.doneItems} 条）",
                    modifier = Modifier.padding(top = 6.dp),
                )
                if (tombstones > 0) {
                    Text(
                        text = "另含 $tombstones 条已删除记录，导入后本机对应条目也会被删除。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }

                Text(
                    text = "导入方式",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 14.dp),
                )

                ModeOption(
                    title = "合并",
                    detail = "保留本机现有数据，同一条目按修改时间取较新者。",
                    extra = "预计：新增 ${preview.mergeImpact.added}、" +
                        "更新 ${preview.mergeImpact.updated}、" +
                        "删除 ${preview.mergeImpact.removed}",
                    selected = mode == ImportMode.MERGE,
                    onSelect = { mode = ImportMode.MERGE },
                )
                ModeOption(
                    title = "覆盖",
                    detail = "本机数据被备份完全替换，本机独有的条目会消失。",
                    extra = if (emptyBackup) "⚠ 备份内容为空，覆盖后本机数据将全部丢失" else null,
                    extraIsWarning = emptyBackup,
                    selected = mode == ImportMode.REPLACE,
                    onSelect = { mode = ImportMode.REPLACE },
                )

                Text(
                    text = "导入后会自动同步；与其它设备的数据仍按修改时间合并，" +
                        "其它设备独有的数据不会因此丢失。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(mode) }) {
                Text(
                    text = "导入",
                    color = if (mode == ImportMode.REPLACE) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

@Composable
private fun ModeOption(
    title: String,
    detail: String,
    extra: String?,
    selected: Boolean,
    onSelect: () -> Unit,
    extraIsWarning: Boolean = false,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onSelect)
            .padding(top = 6.dp),
        verticalAlignment = Alignment.Top,
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Column(Modifier.padding(top = 10.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (extra != null) {
                Text(
                    text = extra,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (extraIsWarning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}
