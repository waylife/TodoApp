package com.todoapp.sync

import com.todoapp.data.SettingsStore
import com.todoapp.data.TodoRepository
import com.todoapp.data.WebDavConfig
import com.todoapp.model.RemoteSnapshot
import com.todoapp.util.Dates
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** 同步状态，驱动界面上的状态角标与「上次同步时间」。 */
sealed interface SyncStatus {
    data object Idle : SyncStatus
    data object Syncing : SyncStatus
    data object NotConfigured : SyncStatus
    data class Success(val at: Long) : SyncStatus
    data class Error(val message: String, val lastSuccessAt: Long?) : SyncStatus

    /** 远端快照已被删除（设置页「删除远端数据」）。[localCleared] 表示本机数据是否一并清空。 */
    data class RemoteCleared(val at: Long, val localCleared: Boolean) : SyncStatus
}

/**
 * 同步引擎：
 * 1. 下载远程 todoapp.json（404 = 首次同步）；
 * 2. 与本地 LWW 合并（[SyncMerge]），合并结果写回本地；
 * 3. 带乐观锁上传（If-Match），412 冲突则重新下载合并，最多 3 次。
 *
 * 触发时机：应用启动、本地修改后防抖 2 秒、手动刷新。
 */
class SyncEngine(
    private val repository: TodoRepository,
    private val settingsStore: SettingsStore,
    private val httpClient: HttpClient,
    private val scope: CoroutineScope,
    private val clock: () -> Long = { Dates.nowMillis() },
) {
    private val json = Json { ignoreUnknownKeys = true }

    private val _status = MutableStateFlow<SyncStatus>(SyncStatus.Idle)
    val status: StateFlow<SyncStatus> = _status.asStateFlow()

    private val mutex = Mutex()
    private var debounceJob: Job? = null
    private var syncJob: Job? = null

    init {
        repository.onLocalChange = { scheduleSync() }
    }

    /** 本地数据变更后的防抖触发。 */
    fun scheduleSync(delayMillis: Long = DEBOUNCE_MILLIS) {
        if (!settingsStore.config.value.isConfigured) return
        debounceJob?.cancel()
        debounceJob = scope.launch {
            delay(delayMillis)
            syncNow()
        }
    }

    /** 立即同步（启动 / 手动）。返回同步任务，未配置时返回 null。 */
    fun syncNow(): Job? {
        if (!settingsStore.config.value.isConfigured) {
            _status.value = SyncStatus.NotConfigured
            return null
        }
        return scope.launch { performSync() }.also { syncJob = it }
    }

    /**
     * 删除远端快照（设置页「删除远端数据」）；[clearLocal] 为真时同时清空本机数据。
     * 未配置 WebDAV 时返回 null 并置为 [SyncStatus.NotConfigured]。
     *
     * 动手前先取消排队中的防抖同步与正在跑的同步：整文件快照同步没有增量概念，
     * 删除之后只要还有一次上传，本机数据就会被原样传回去，等于没删。
     */
    fun deleteRemoteData(clearLocal: Boolean): Job? {
        val config = settingsStore.config.value
        if (!config.isConfigured) {
            _status.value = SyncStatus.NotConfigured
            return null
        }
        return scope.launch {
            debounceJob?.cancelAndJoin()
            syncJob?.cancelAndJoin()
            mutex.withLock { doDeleteRemoteData(config, clearLocal) }
        }
    }

    private suspend fun performSync() {
        mutex.withLock { doSync() }
    }

    private suspend fun doSync() {
        val config = settingsStore.config.value
        val client = WebDavClient(httpClient, config)
        _status.value = SyncStatus.Syncing
        try {
            client.ensureRemoteDir()
            var attempt = 0
            while (true) {
                val (remoteJson, etag) = client.downloadFile()
                val remote = remoteJson?.let { json.decodeFromString<RemoteSnapshot>(it) } ?: RemoteSnapshot()
                val local = repository.currentSnapshot()
                val merged = SyncMerge.merge(local, remote, clock())
                repository.replaceAll(merged.lists, merged.items)
                try {
                    client.uploadFile(json.encodeToString(merged), etag, isNew = remoteJson == null)
                    break
                } catch (e: WebDavConflictException) {
                    if (++attempt >= MAX_CONFLICT_RETRIES) throw e
                }
            }
            settingsStore.lastSyncAt = clock()
            // 墓碑保留 90 天后再清理，降低其它设备长期未同步导致「复活」的风险
            repository.purgeDeleted(clock() - TOMBSTONE_TTL_MILLIS)
            _status.value = SyncStatus.Success(clock())
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: WebDavException) {
            _status.value = SyncStatus.Error(e.message ?: "同步失败", settingsStore.lastSyncAt.takeIf { it > 0 })
        } catch (e: Exception) {
            _status.value = SyncStatus.Error("同步失败：${e.message ?: e::class.simpleName}", settingsStore.lastSyncAt.takeIf { it > 0 })
        }
    }

    /** 删除远端快照；[clearLocal] 为真时连同本机数据一起清空。调用方需已持有 [mutex]。 */
    private suspend fun doDeleteRemoteData(config: WebDavConfig, clearLocal: Boolean) {
        _status.value = SyncStatus.Syncing
        val lastSuccessAt = settingsStore.lastSyncAt.takeIf { it > 0 }
        try {
            WebDavClient(httpClient, config).deleteFile()
            if (clearLocal) {
                repository.clearAll()
                settingsStore.lastSyncAt = 0
            }
            _status.value = SyncStatus.RemoteCleared(clock(), clearLocal)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            val reason = e.message ?: e::class.simpleName ?: "未知错误"
            _status.value = SyncStatus.Error("删除远端数据失败：$reason", lastSuccessAt)
        }
    }

    private companion object {
        const val DEBOUNCE_MILLIS = 2_000L
        const val MAX_CONFLICT_RETRIES = 3
        const val TOMBSTONE_TTL_MILLIS = 90L * 24 * 3600 * 1000
    }
}
