package com.todoapp.data

import com.russhwolf.settings.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** WebDAV 连接配置。remoteDir 为服务器上的目录（默认 ToDoApp），可含多级路径。 */
data class WebDavConfig(
    val serverUrl: String,
    val username: String,
    val password: String,
    val remoteDir: String = DEFAULT_REMOTE_DIR,
) {
    val isConfigured: Boolean
        get() = serverUrl.trim().startsWith("http") && remoteDir.isNotBlank()

    companion object {
        const val DEFAULT_REMOTE_DIR = "ToDoApp"
    }
}

/** 应用设置（WebDAV 配置 + 上次同步时间），持久化到各平台应用私有存储。 */
class SettingsStore(private val settings: Settings) {

    private val _config = MutableStateFlow(readConfig())
    val config: StateFlow<WebDavConfig> = _config.asStateFlow()

    var lastSyncAt: Long
        get() = settings.getLong(KEY_LAST_SYNC, 0L)
        set(value) = settings.putLong(KEY_LAST_SYNC, value)

    fun saveConfig(config: WebDavConfig) {
        settings.putString(KEY_URL, config.serverUrl.trim().trimEnd('/'))
        settings.putString(KEY_USER, config.username)
        settings.putString(KEY_PASSWORD, config.password)
        settings.putString(KEY_DIR, config.remoteDir.trim().trim('/').ifEmpty { WebDavConfig.DEFAULT_REMOTE_DIR })
        _config.value = readConfig()
    }

    private fun readConfig(): WebDavConfig = WebDavConfig(
        serverUrl = settings.getString(KEY_URL, ""),
        username = settings.getString(KEY_USER, ""),
        password = settings.getString(KEY_PASSWORD, ""),
        remoteDir = settings.getString(KEY_DIR, WebDavConfig.DEFAULT_REMOTE_DIR),
    )

    private companion object {
        const val KEY_URL = "webdav.url"
        const val KEY_USER = "webdav.username"
        const val KEY_PASSWORD = "webdav.password"
        const val KEY_DIR = "webdav.remoteDir"
        const val KEY_LAST_SYNC = "sync.lastSuccessAt"
    }
}
