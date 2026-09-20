package com.todoapp.transfer

import com.todoapp.model.RemoteSnapshot
import com.todoapp.sync.SyncMerge
import com.todoapp.util.Dates
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/** 备份文件格式不合法（空文件、非 JSON、非本应用文件、版本过高）。 */
class TransferFormatException(message: String) : Exception(message)

/** 导入方式。 */
enum class ImportMode {
    /** 与本地合并，冲突按 LWW 规则（同 [SyncMerge]）。本地独有的数据保留。 */
    MERGE,

    /** 用备份整体覆盖本机（含备份里的删除墓碑）。 */
    REPLACE,
}

/** 备份内容统计，全部按「可见条目」与「墓碑」分开计数。 */
data class SnapshotStats(
    val lists: Int,
    val items: Int,
    val doneItems: Int,
    val deletedLists: Int,
    val deletedItems: Int,
) {
    val hasTombstones: Boolean get() = deletedLists > 0 || deletedItems > 0
}

/** 导入对本地数据的影响预估（只统计可见条目）。 */
data class MergeImpact(val added: Int, val updated: Int, val removed: Int) {
    val isEmpty: Boolean get() = added == 0 && updated == 0 && removed == 0
}

/** 导入前的完整预览，供确认对话框展示。 */
data class ImportPreview(
    val fileName: String,
    val stats: SnapshotStats,
    /** 按「合并」方式导入时的影响。选「覆盖」时不做预估——覆盖的结果就是备份内容本身。 */
    val mergeImpact: MergeImpact,
    val exportedAt: Long,
    val rev: Long,
)

/**
 * 备份文件的编解码与导入语义。
 *
 * 备份格式**就是 WebDAV 上的 `todoapp.json` 本身**（[RemoteSnapshot]），不做额外包装。这样：
 * - 导出的文件可以直接改名成 `todoapp.json` 丢进同步目录，或反过来把服务器上的同步文件下载回来当备份导入；
 * - 无损：保留 id、时间戳与删除墓碑，导入后多端合并结果与走一次同步等价。
 *
 * 所有函数都是纯函数，不碰数据库，便于单测。
 */
object TodoTransfer {

    /** 备份文件的扩展名（不含点）。 */
    const val EXTENSION = "json"

    /**
     * 备份文件大小上限。
     *
     * 个人待办即使上万条也只有几 MB；而三端的选择器都允许用户选到任意文件
     * （Android 侧为了兼容那些把 .json 报成 octet-stream 的文件管理器，MIME 里带了通配）。
     * 误选一个大文件时若直接读进内存，移动端会当场 OOM 被杀。
     * 因此**在读之前**先卡一道，宁可报错也不要崩。
     */
    const val MAX_BACKUP_BYTES: Long = 32L * 1024 * 1024

    /** 文件超限时的提示，三端共用，避免措辞不一致。 */
    fun tooLargeMessage(): String =
        "文件超过 ${MAX_BACKUP_BYTES / 1024 / 1024} MB，不像待办备份，请确认是否选错文件"

    /**
     * 缩进输出：备份文件是给人看、给人改的，单行 JSON 不好排查问题。
     * `ignoreUnknownKeys` 让未来版本新增的字段不会导致旧版本解析失败。
     */
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /** 序列化为备份文本。 */
    fun encode(snapshot: RemoteSnapshot): String = json.encodeToString(snapshot)

    /**
     * 解析备份文本。失败时返回 [TransferFormatException]，消息可直接展示给用户——
     * 用户最需要知道的是「这个文件为什么不能用」，而不是堆栈。
     */
    fun decode(text: String): Result<RemoteSnapshot> {
        if (text.isBlank()) {
            return Result.failure(TransferFormatException("文件内容为空"))
        }
        val root = try {
            json.parseToJsonElement(text)
        } catch (e: Exception) {
            return Result.failure(TransferFormatException("不是合法的 JSON 文件"))
        }
        val obj = root as? JsonObject
            ?: return Result.failure(TransferFormatException("不是合法的备份文件：顶层应该是一个 JSON 对象"))

        // 先用字段探测「这是不是本应用的备份」，避免把任意 JSON 解析成一份空快照（那会让用户以为导入成功了）
        if ("lists" !in obj && "items" !in obj) {
            return Result.failure(
                TransferFormatException("不是待办备份文件：缺少 lists / items 字段"),
            )
        }
        val version = (obj["schemaVersion"] as? JsonPrimitive)?.intOrNull ?: 0
        if (version > RemoteSnapshot.SCHEMA_VERSION) {
            return Result.failure(
                TransferFormatException(
                    "备份格式版本 v$version 高于当前应用支持的 v${RemoteSnapshot.SCHEMA_VERSION}，请先升级应用",
                ),
            )
        }
        val snapshot = try {
            json.decodeFromString(RemoteSnapshot.serializer(), text)
        } catch (e: Exception) {
            return Result.failure(TransferFormatException("备份内容不完整：${e.message ?: "解析失败"}"))
        }
        return Result.success(snapshot)
    }

    /** 统计备份里的条目数。 */
    fun stats(snapshot: RemoteSnapshot): SnapshotStats = SnapshotStats(
        lists = snapshot.lists.count { it.deletedAt == null },
        items = snapshot.items.count { it.deletedAt == null },
        doneItems = snapshot.items.count { it.deletedAt == null && it.done },
        deletedLists = snapshot.lists.count { it.deletedAt != null },
        deletedItems = snapshot.items.count { it.deletedAt != null },
    )

    /** 构造确认框所需的预览。[fileName] 仅用于展示。 */
    fun preview(
        fileName: String,
        local: RemoteSnapshot,
        imported: RemoteSnapshot,
        now: Long,
    ): ImportPreview = ImportPreview(
        fileName = fileName,
        stats = stats(imported),
        mergeImpact = mergeImpact(local, imported, now),
        exportedAt = imported.savedAt,
        rev = imported.rev,
    )

    /**
     * 预估按「合并」导入后本地会发生什么。做法是先用 [SyncMerge] 预演一遍，
     * 再与本地现状逐条比对——预估与实际导入走的是同一个函数，不会出现「说的和做的对不上」。
     */
    fun mergeImpact(local: RemoteSnapshot, imported: RemoteSnapshot, now: Long): MergeImpact {
        val merged = SyncMerge.merge(local, imported, now)
        val lists = tally(local.lists, merged.lists, { it.id }, { it.deletedAt == null })
        val items = tally(local.items, merged.items, { it.id }, { it.deletedAt == null })
        return MergeImpact(
            added = lists.added + items.added,
            updated = lists.updated + items.updated,
            removed = lists.removed + items.removed,
        )
    }

    /**
     * 把备份应用到本地，返回新的完整快照（由调用方写库）。
     *
     * - [ImportMode.MERGE]：与本地合并。本地独有的数据保留，同 id 冲突按 LWW 决胜，
     *   因此备份里的墓碑同样会删掉本地对应条目——这是「忠实还原备份」的必然结果。
     * - [ImportMode.REPLACE]：本机数据被备份整体替换，备份里没有的本地条目会消失。
     */
    fun apply(local: RemoteSnapshot, imported: RemoteSnapshot, mode: ImportMode, now: Long): RemoteSnapshot =
        when (mode) {
            ImportMode.MERGE -> SyncMerge.merge(local, imported, now)
            ImportMode.REPLACE -> imported.copy(
                schemaVersion = RemoteSnapshot.SCHEMA_VERSION,
                savedAt = now,
            )
        }

    /** 建议的文件名，形如 `todoapp-20260921-0003.json`（按本机时区）。 */
    fun suggestedFileName(now: Long): String {
        val date = Dates.toLocalDate(now)
        val month = (date.month.ordinal + 1).toString().padStart(2, '0')
        val day = date.day.toString().padStart(2, '0')
        val time = Dates.formatTime(now).replace(":", "")
        return "todoapp-${date.year}$month$day-$time.$EXTENSION"
    }

    private data class Tally(var added: Int = 0, var updated: Int = 0, var removed: Int = 0)

    /** 比对前后两份列表里「可见条目」的增删改。墓碑的变化不计入（用户关心的是看得见的待办）。 */
    private fun <T> tally(
        before: List<T>,
        after: List<T>,
        idOf: (T) -> String,
        isVisible: (T) -> Boolean,
    ): Tally {
        val beforeVisible = before.filter(isVisible).associateBy(idOf)
        val afterVisible = after.filter(isVisible).associateBy(idOf)
        val result = Tally()
        for ((id, entity) in afterVisible) {
            val previous = beforeVisible[id]
            when {
                previous == null -> result.added++
                previous != entity -> result.updated++
            }
        }
        result.removed = beforeVisible.keys.count { it !in afterVisible }
        return result
    }
}
