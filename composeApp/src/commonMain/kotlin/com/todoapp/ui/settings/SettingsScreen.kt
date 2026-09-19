package com.todoapp.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.todoapp.sync.SyncStatus
import com.todoapp.ui.common.SettingsSectionTitle
import com.todoapp.util.Dates
import com.todoapp.viewmodel.SettingsViewModel

/** 设置页：WebDAV 服务器配置、测试连接、手动同步、删除远端数据。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
    /** 供界面自动化验证：直接展开删除确认框，省去模拟点击。 */
    autoOpenDeleteDialog: Boolean = false,
) {
    val state by viewModel.uiState.collectAsState()

    var serverUrl by rememberSaveable { mutableStateOf(state.config.serverUrl) }
    var username by rememberSaveable { mutableStateOf(state.config.username) }
    var password by rememberSaveable { mutableStateOf(state.config.password) }
    var remoteDir by rememberSaveable { mutableStateOf(state.config.remoteDir) }
    var showDeleteDialog by remember { mutableStateOf(autoOpenDeleteDialog) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
        ) {
            SettingsSectionTitle("WebDAV 同步")
            OutlinedTextField(
                value = serverUrl,
                onValueChange = { serverUrl = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("服务器地址") },
                placeholder = { Text("https://dav.example.com/dav") },
                singleLine = true,
                supportingText = { Text("支持坚果云、Nextcloud、群晖等标准 WebDAV 服务") },
            )
            OutlinedTextField(
                value = username,
                onValueChange = { username = it },
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                label = { Text("用户名") },
                singleLine = true,
            )
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                label = { Text("密码 / 应用密码") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
            )
            OutlinedTextField(
                value = remoteDir,
                onValueChange = { remoteDir = it },
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                label = { Text("远程目录") },
                singleLine = true,
                supportingText = { Text("数据保存为 远程目录/todoapp.json，默认 ToDoApp") },
            )

            Row(modifier = Modifier.padding(top = 16.dp)) {
                Button(
                    onClick = { viewModel.testConnection(serverUrl, username, password, remoteDir) },
                    enabled = !state.testing,
                ) {
                    if (state.testing) {
                        CircularProgressIndicator(Modifier.width(18.dp), strokeWidth = 2.dp)
                    } else {
                        Text("测试连接")
                    }
                }
                Button(
                    onClick = { viewModel.saveConfig(serverUrl, username, password, remoteDir) },
                    modifier = Modifier.padding(start = 12.dp),
                ) {
                    Text("保存并同步")
                }
            }

            state.testResult?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (state.testSuccess) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }

            HorizontalDivider(Modifier.padding(vertical = 20.dp))

            Text(
                text = "同步状态",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            val statusText = when (val s = state.syncStatus) {
                is SyncStatus.Syncing -> "正在同步…"
                is SyncStatus.Success -> "上次同步：${Dates.formatSyncTime(s.at)}"
                is SyncStatus.Error -> s.message
                is SyncStatus.RemoteCleared ->
                    "远端数据已于 ${Dates.formatTime(s.at)} 删除" +
                        if (s.localCleared) "，本机数据已清空" else "，本机数据保留"
                is SyncStatus.NotConfigured -> "尚未配置 WebDAV"
                is SyncStatus.Idle -> if (state.lastSyncAt > 0) {
                    "上次同步：${Dates.formatSyncTime(state.lastSyncAt)}"
                } else {
                    "尚未同步"
                }
            }
            Text(statusText, modifier = Modifier.padding(top = 6.dp))
            Button(
                onClick = viewModel::syncNow,
                modifier = Modifier.padding(top = 12.dp),
                enabled = state.syncStatus !is SyncStatus.Syncing,
            ) {
                Text("立即同步")
            }

            SettingsSectionTitle("危险操作")
            Text(
                text = "删除服务器上的同步文件，用于远端数据需要彻底清空时。删除后无法恢复。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(
                onClick = { showDeleteDialog = true },
                modifier = Modifier.padding(top = 12.dp),
                enabled = state.config.isConfigured && state.syncStatus !is SyncStatus.Syncing,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) {
                Text("删除远端数据…")
            }

            SettingsSectionTitle("说明")
            Text(
                text = "· 每次修改后约 2 秒自动同步，也可手动触发\n" +
                    "· 多端同时修改时按「最后修改时间」合并\n" +
                    "· 删除远端数据后，本机数据会在下次同步时重新上传（除非同时清空本机）\n" +
                    "· 其它设备若仍存有数据，也会在各自下次同步时重新上传\n" +
                    "· 凭据仅保存在本机应用私有存储\n" +
                    "· 暂不支持自签名证书的服务器",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "TodoApp v1.0",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 24.dp, bottom = 24.dp),
            )
        }
    }

    if (showDeleteDialog) {
        DeleteRemoteDataDialog(
            remotePath = state.remotePath,
            onConfirm = { clearLocal ->
                viewModel.deleteRemoteData(clearLocal)
                showDeleteDialog = false
            },
            onDismiss = { showDeleteDialog = false },
        )
    }
}

/**
 * 删除远端数据的确认框。
 *
 * 「同时清空本机数据」默认不勾选，此时删掉的远端文件会在下次同步时被本机数据重新写出来，
 * 因此勾选与否的文字说明必须跟着变——否则用户会以为数据已经彻底没了。
 */
@Composable
private fun DeleteRemoteDataDialog(
    remotePath: String,
    onConfirm: (clearLocal: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var clearLocal by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("删除远端数据？") },
        text = {
            Column {
                Text(
                    if (remotePath.isBlank()) {
                        "将删除服务器上的同步文件。"
                    } else {
                        "将删除服务器上的 $remotePath。"
                    },
                )
                Text(
                    text = if (clearLocal) {
                        "本机全部清单与待办也会一并清空，之后不会再上传。删除后无法恢复。"
                    } else {
                        "本机数据不受影响，但下次同步时会重新上传，远端又会恢复成当前内容。删除后无法恢复。"
                    },
                    modifier = Modifier.padding(top = 8.dp),
                )
                Row(
                    modifier = Modifier.padding(top = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = clearLocal, onCheckedChange = { clearLocal = it })
                    Text("同时清空本机数据")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(clearLocal) }) {
                Text("删除", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
