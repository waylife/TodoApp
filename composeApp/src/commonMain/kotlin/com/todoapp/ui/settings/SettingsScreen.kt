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
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.todoapp.sync.SyncStatus
import com.todoapp.ui.common.SettingsSectionTitle
import com.todoapp.util.Dates
import com.todoapp.viewmodel.SettingsViewModel

/** 设置页：WebDAV 服务器配置、测试连接、手动同步。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.uiState.collectAsState()

    var serverUrl by rememberSaveable { mutableStateOf(state.config.serverUrl) }
    var username by rememberSaveable { mutableStateOf(state.config.username) }
    var password by rememberSaveable { mutableStateOf(state.config.password) }
    var remoteDir by rememberSaveable { mutableStateOf(state.config.remoteDir) }

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
                is SyncStatus.Error -> "同步失败：${s.message}"
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

            SettingsSectionTitle("说明")
            Text(
                text = "· 每次修改后约 2 秒自动同步，也可手动触发\n" +
                    "· 多端同时修改时按「最后修改时间」合并\n" +
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
}
