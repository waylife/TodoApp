package com.todoapp.viewmodel

import com.todoapp.data.SettingsStore
import com.todoapp.data.WebDavConfig
import com.todoapp.sync.SyncEngine
import com.todoapp.sync.SyncStatus
import com.todoapp.sync.WebDavClient
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 设置页状态。 */
data class SettingsUiState(
    val config: WebDavConfig = WebDavConfig(serverUrl = "", username = "", password = ""),
    val testing: Boolean = false,
    val testResult: String? = null,
    val testSuccess: Boolean = false,
    val syncStatus: SyncStatus = SyncStatus.Idle,
    val lastSyncAt: Long = 0,
    /** 远端快照的完整地址，用于「删除远端数据」确认框里写清楚要删什么。 */
    val remotePath: String = "",
)

/**
 * 拼接远端快照地址（仅用于界面展示，不做 URL 编码）。
 * 未填服务器地址时返回空串——此时按钮本就不可点，确认框只需给一句通用文案。
 */
private fun remotePathOf(config: WebDavConfig): String {
    val server = config.serverUrl.trim().trimEnd('/')
    if (server.isEmpty()) return ""
    return "$server/${config.remoteDir.trim('/')}/${WebDavClient.FILE_NAME}"
}

class SettingsViewModel(
    private val settingsStore: SettingsStore,
    private val syncEngine: SyncEngine,
    private val httpClient: HttpClient,
    private val appScope: CoroutineScope,
    /** 连接测试可注入，便于测试替身；生产用真实 WebDavClient。 */
    private val connectionTester: suspend (WebDavConfig) -> Result<String> =
        { config -> WebDavClient(httpClient, config).testConnection() },
) {
    private val _testing = MutableStateFlow(false)
    private val _testResult = MutableStateFlow<Pair<Boolean, String>?>(null)

    private val initialLastSyncAt: Long = settingsStore.lastSyncAt

    val uiState: StateFlow<SettingsUiState> = combine(
        settingsStore.config,
        _testing,
        _testResult,
        syncEngine.status,
    ) { config, testing, testResult, status ->
        SettingsUiState(
            config = config,
            testing = testing,
            testResult = testResult?.second,
            testSuccess = testResult?.first ?: false,
            syncStatus = status,
            lastSyncAt = when (status) {
                is SyncStatus.Success -> status.at
                is SyncStatus.Error -> status.lastSuccessAt ?: initialLastSyncAt
                is SyncStatus.RemoteCleared -> if (status.localCleared) 0 else initialLastSyncAt
                else -> initialLastSyncAt
            },
            remotePath = remotePathOf(config),
        )
    }.stateIn(appScope, SharingStarted.Eagerly, SettingsUiState())

    /** 保存配置并立即触发一次同步。 */
    fun saveConfig(serverUrl: String, username: String, password: String, remoteDir: String) {
        settingsStore.saveConfig(
            WebDavConfig(
                serverUrl = serverUrl.trim().trimEnd('/'),
                username = username.trim(),
                password = password,
                remoteDir = remoteDir.trim().trim('/').ifEmpty { WebDavConfig.DEFAULT_REMOTE_DIR },
            ),
        )
        syncEngine.syncNow()
    }

    /** 测试连接（使用界面当前输入，不必先保存）。 */
    fun testConnection(serverUrl: String, username: String, password: String, remoteDir: String) {
        if (serverUrl.isBlank()) {
            _testResult.value = false to "请先填写服务器地址"
            return
        }
        _testing.value = true
        _testResult.value = null
        appScope.launch {
            val config = WebDavConfig(
                serverUrl = serverUrl.trim().trimEnd('/'),
                username = username.trim(),
                password = password,
                remoteDir = remoteDir.trim().trim('/').ifEmpty { WebDavConfig.DEFAULT_REMOTE_DIR },
            )
            val result = connectionTester(config)
            _testing.value = false
            _testResult.value = result.fold(
                onSuccess = { true to it },
                onFailure = { false to (it.message ?: "连接失败") },
            )
        }
    }

    fun syncNow() = syncEngine.syncNow()

    /**
     * 删除远端数据。[clearLocal] 为真时连同本机数据一起清空。
     * 界面上必须先经过确认对话框，这里不再重复询问。
     */
    fun deleteRemoteData(clearLocal: Boolean) {
        syncEngine.deleteRemoteData(clearLocal)
    }
}
