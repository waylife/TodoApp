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
 * 3. 带乐观锁上传（If-Match），412 冲突或合并期间出现并发编辑则重新下载合并，最多 [MAX_SYNC_ATTEMPTS] 轮。
 *
 * 冲突解决依赖各设备本地时钟的 updatedAt（实体级 last-write-wins），
 * 时钟明显偏快的设备会在冲突中获胜——这是整文件快照同步的已知取舍。
 *
 * 触发时机：应用启动 / 回到前台（[syncOnForeground]）、本地修改后防抖 2 秒、手动刷新。
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
     * 应用回到前台时调用（见 `com.todoapp.ui.AppForegroundEffect`）。
     * 返回被放行的同步任务；被节流或已有同步在跑时返回 null。两层护栏：
     * - 已有同步在跑/排队 → 跳过。Android 冷启动会同时触发 `ON_START` 与首次组合，
     *   不挡一下会连发两次同步；
     * - 距上次**成功**同步不足 [FOREGROUND_MIN_INTERVAL_MILLIS] → 跳过，避免在应用间
     *   来回切换时反复打服务器。从未成功过（lastSyncAt == 0）则放行，让失败的同步能重试。
     */
    fun syncOnForeground(): Job? {
        if (syncJob?.isActive == true) return null
        val lastSuccess = settingsStore.lastSyncAt
        if (lastSuccess > 0 && clock() - lastSuccess < FOREGROUND_MIN_INTERVAL_MILLIS) return null
        return syncNow()
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
                val remote = decodeRemoteSnapshot(remoteJson)
                val versionBefore = repository.changeVersion
                val local = repository.currentSnapshot()
                val merged = SyncMerge.merge(local, remote, clock())
                // 写库前检测：快照/合并窗口内出现了用户编辑就必须重算——
                // replaceAll 是全删全插，写库之后这些编辑就真没了，重试也救不回来
                if (repository.changeVersion != versionBefore) {
                    if (++attempt >= MAX_SYNC_ATTEMPTS) throw WebDavException("同步期间数据持续变化，请稍后重试")
                    continue
                }
                repository.replaceAll(merged.lists, merged.items)
                // 写库后检测：replaceAll 执行期间恰好落库、未被事务清掉的编辑，
                // 借下一轮重新快照并入合并结果并补推（onLocalChange 在写库期间被临时关闭）
                if (repository.changeVersion != versionBefore) {
                    if (++attempt >= MAX_SYNC_ATTEMPTS) throw WebDavException("同步期间数据持续变化，请稍后重试")
                    continue
                }
                try {
                    client.uploadFile(json.encodeToString(merged), etag, isNew = remoteJson == null)
                    break
                } catch (e: WebDavConflictException) {
                    if (++attempt >= MAX_SYNC_ATTEMPTS) throw e
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

    /**
     * 解码远端快照。两道防线都在写本地库**之前**：
     * - 内容损坏 → 报错终止，绝不能用「解码失败即空快照」的方式把远端清空；
     * - schemaVersion 更新 → 报错终止。解码配置 ignoreUnknownKeys 会静默丢掉不认识的字段，
     *   若不拦截，合并回传会把新版快照降级覆写，新版应用写入的数据就此永久丢失。
     */
    private fun decodeRemoteSnapshot(text: String?): RemoteSnapshot {
        if (text == null) return RemoteSnapshot()
        val remote = try {
            json.decodeFromString<RemoteSnapshot>(text)
        } catch (e: Exception) {
            throw WebDavException("远端数据无法解析（可能已损坏），可在设置中删除远端数据后重建")
        }
        if (remote.schemaVersion > RemoteSnapshot.SCHEMA_VERSION) {
            throw WebDavException("远端数据由更新版本的应用创建（格式 v${remote.schemaVersion}），请先升级本应用")
        }
        return remote
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

        /** 单次同步的最多轮数，覆盖 412 冲突重试与「合并窗口内出现并发编辑」的重试。 */
        const val MAX_SYNC_ATTEMPTS = 5
        const val TOMBSTONE_TTL_MILLIS = 90L * 24 * 3600 * 1000
        const val FOREGROUND_MIN_INTERVAL_MILLIS = 20_000L
    }
}
