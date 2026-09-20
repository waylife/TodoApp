package com.todoapp.transfer

import com.todoapp.model.RemoteSnapshot
import com.todoapp.model.TodoItem
import com.todoapp.model.TodoList
import com.todoapp.util.Dates
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TodoTransferTest {

    private fun list(id: String, name: String, updatedAt: Long, deletedAt: Long? = null) =
        TodoList(id = id, name = name, createdAt = 0, updatedAt = updatedAt, deletedAt = deletedAt)

    private fun item(
        id: String,
        title: String,
        updatedAt: Long,
        done: Boolean = false,
        deletedAt: Long? = null,
        listId: String = "l1",
    ) = TodoItem(
        id = id,
        listId = listId,
        title = title,
        createdAt = 0,
        updatedAt = updatedAt,
        done = done,
        deletedAt = deletedAt,
    )

    private fun snap(
        lists: List<TodoList> = emptyList(),
        items: List<TodoItem> = emptyList(),
        rev: Long = 0,
        savedAt: Long = 0,
    ) = RemoteSnapshot(rev = rev, savedAt = savedAt, lists = lists, items = items)

    // ---------- 编解码 ----------

    @Test
    fun `导出再导入内容完全一致`() {
        val original = snap(
            lists = listOf(list("l1", "工作", 100)),
            items = listOf(item("i1", "写周报", 100), item("i2", "已删任务", 120, deletedAt = 120)),
            rev = 7,
            savedAt = 12345,
        )
        val decoded = TodoTransfer.decode(TodoTransfer.encode(original)).getOrThrow()
        assertEquals(original, decoded, "备份文件必须无损：id、时间戳、墓碑都要保住")
    }

    @Test
    fun `导出文件是缩进 JSON 且带 schemaVersion`() {
        val text = TodoTransfer.encode(snap(items = listOf(item("i1", "任务", 1))))
        assertTrue(text.contains("\n"), "备份文件应缩进输出，便于人工排查")
        assertTrue(text.contains("\"schemaVersion\""), "应带版本号，供未来做格式迁移")
        assertTrue(text.contains("\"items\""))
    }

    @Test
    fun `空文件被拒绝并给出可读原因`() {
        val error = TodoTransfer.decode("   \n ").exceptionOrNull()
        assertTrue(error is TransferFormatException)
        assertEquals("文件内容为空", error.message)
    }

    @Test
    fun `非法 JSON 被拒绝`() {
        val error = TodoTransfer.decode("{ 这不是 json").exceptionOrNull()
        assertTrue(error is TransferFormatException)
        assertEquals("不是合法的 JSON 文件", error.message)
    }

    @Test
    fun `缺少 lists 与 items 的 JSON 被拒绝`() {
        val error = TodoTransfer.decode("""{"foo": 1}""").exceptionOrNull()
        assertTrue(error is TransferFormatException)
        assertTrue(error.message!!.contains("不是待办备份文件"), "实际：${error.message}")
    }

    @Test
    fun `顶层是数组的 JSON 被拒绝`() {
        val error = TodoTransfer.decode("""[{"lists": []}]""").exceptionOrNull()
        assertTrue(error is TransferFormatException)
        assertTrue(error.message!!.contains("顶层应该是一个 JSON 对象"))
    }

    @Test
    fun `版本高于当前支持时拒绝导入`() {
        val error = TodoTransfer.decode("""{"schemaVersion": 99, "lists": [], "items": []}""").exceptionOrNull()
        assertTrue(error is TransferFormatException)
        assertTrue(error.message!!.contains("v99"), "错误消息应带上具体版本号，实际：${error.message}")
    }

    @Test
    fun `未来版本新增的未知字段不影响导入`() {
        val text = """{"schemaVersion": 1, "rev": 3, "lists": [], "items": [], "futureField": {"a": 1}}"""
        val decoded = TodoTransfer.decode(text).getOrThrow()
        assertEquals(3, decoded.rev)
    }

    @Test
    fun `只有 lists 字段的空备份可以导入`() {
        val decoded = TodoTransfer.decode("""{"lists": []}""").getOrThrow()
        assertTrue(decoded.items.isEmpty())
    }

    // ---------- 统计 ----------

    @Test
    fun `统计区分可见条目与墓碑`() {
        val stats = TodoTransfer.stats(
            snap(
                lists = listOf(list("l1", "工作", 1), list("l2", "已删清单", 1, deletedAt = 1)),
                items = listOf(
                    item("i1", "未完成", 1),
                    item("i2", "已完成", 1, done = true),
                    item("i3", "已删任务", 1, deletedAt = 1),
                ),
            ),
        )
        assertEquals(1, stats.lists)
        assertEquals(2, stats.items)
        assertEquals(1, stats.doneItems)
        assertEquals(1, stats.deletedLists)
        assertEquals(1, stats.deletedItems)
        assertTrue(stats.hasTombstones)
    }

    @Test
    fun `没有墓碑时 hasTombstones 为假`() {
        val stats = TodoTransfer.stats(snap(items = listOf(item("i1", "任务", 1))))
        assertFalse(stats.hasTombstones)
    }

    // ---------- 合并影响预估 ----------

    @Test
    fun `预估导入新增条目`() {
        val impact = TodoTransfer.mergeImpact(
            local = snap(),
            imported = snap(items = listOf(item("i1", "新任务", 100), item("i2", "另一条", 100))),
            now = 200,
        )
        assertEquals(MergeImpact(added = 2, updated = 0, removed = 0), impact)
    }

    @Test
    fun `预估导入更新较新的条目`() {
        val impact = TodoTransfer.mergeImpact(
            local = snap(items = listOf(item("i1", "旧标题", 100))),
            imported = snap(items = listOf(item("i1", "新标题", 300))),
            now = 400,
        )
        assertEquals(MergeImpact(added = 0, updated = 1, removed = 0), impact)
    }

    @Test
    fun `较旧的备份条目不会覆盖本地较新的修改`() {
        val local = snap(items = listOf(item("i1", "本地新标题", 300)))
        val imported = snap(items = listOf(item("i1", "备份旧标题", 100)))
        val impact = TodoTransfer.mergeImpact(local, imported, now = 400)
        assertTrue(impact.isEmpty, "LWW 下备份更旧，本地不应发生变化，实际：$impact")

        val applied = TodoTransfer.apply(local, imported, ImportMode.MERGE, now = 400)
        assertEquals("本地新标题", applied.items.single().title)
    }

    @Test
    fun `预估导入墓碑导致的删除`() {
        val impact = TodoTransfer.mergeImpact(
            local = snap(items = listOf(item("i1", "本地任务", 100))),
            imported = snap(items = listOf(item("i1", "本地任务", 100, deletedAt = 300))),
            now = 400,
        )
        assertEquals(MergeImpact(added = 0, updated = 0, removed = 1), impact)
    }

    @Test
    fun `预估不会把本地独有数据算成删除`() {
        val local = snap(items = listOf(item("keep", "本地独有", 100)))
        val imported = snap(items = listOf(item("i1", "备份任务", 100)))
        val impact = TodoTransfer.mergeImpact(local, imported, now = 200)
        assertEquals(MergeImpact(added = 1, updated = 0, removed = 0), impact)
    }

    @Test
    fun `预估的增删改与实际导入结果一致`() {
        val local = snap(
            lists = listOf(list("l1", "工作", 100)),
            items = listOf(
                item("same", "不变", 100),
                item("changed", "本地版本", 100),
                item("localOnly", "本地独有", 100),
                item("tombstoned", "将被删除", 100),
            ),
        )
        val imported = snap(
            lists = listOf(list("l1", "工作", 100)),
            items = listOf(
                item("same", "不变", 100),
                item("changed", "备份版本", 300),
                item("tombstoned", "将被删除", 100, deletedAt = 300),
                item("importedOnly", "备份独有", 100),
            ),
        )
        val impact = TodoTransfer.mergeImpact(local, imported, now = 400)
        assertEquals(MergeImpact(added = 1, updated = 1, removed = 1), impact)

        // 用同样的输入真正导入一次，比对可见条目集合，确认预估没有说谎
        val applied = TodoTransfer.apply(local, imported, ImportMode.MERGE, now = 400)
        val visibleBefore = local.items.filter { it.deletedAt == null }.associateBy { it.id }
        val visibleAfter = applied.items.filter { it.deletedAt == null }.associateBy { it.id }
        assertEquals(impact.added, (visibleAfter.keys - visibleBefore.keys).size)
        assertEquals(impact.removed, (visibleBefore.keys - visibleAfter.keys).size)
        assertEquals(impact.updated, visibleAfter.count { (id, e) -> visibleBefore[id]?.let { it != e } == true })
    }

    // ---------- 导入应用 ----------

    @Test
    fun `合并导入保留本地独有数据`() {
        val local = snap(
            lists = listOf(list("l1", "工作", 100)),
            items = listOf(item("local", "本地任务", 100)),
        )
        val imported = snap(
            lists = listOf(list("l2", "生活", 100)),
            items = listOf(item("remote", "备份任务", 100, listId = "l2")),
        )
        val applied = TodoTransfer.apply(local, imported, ImportMode.MERGE, now = 200)
        assertEquals(setOf("l1", "l2"), applied.lists.map { it.id }.toSet())
        assertEquals(setOf("local", "remote"), applied.items.map { it.id }.toSet())
    }

    @Test
    fun `覆盖导入完全替换本机数据`() {
        val local = snap(
            lists = listOf(list("l1", "工作", 100)),
            items = listOf(item("local", "本地任务", 100)),
        )
        val imported = snap(
            lists = listOf(list("l2", "生活", 100)),
            items = listOf(item("remote", "备份任务", 100, listId = "l2")),
        )
        val applied = TodoTransfer.apply(local, imported, ImportMode.REPLACE, now = 200)
        assertEquals(listOf("l2"), applied.lists.map { it.id })
        assertEquals(listOf("remote"), applied.items.map { it.id })
        assertEquals(RemoteSnapshot.SCHEMA_VERSION, applied.schemaVersion)
        assertEquals(200, applied.savedAt, "覆盖后 savedAt 应更新为导入时刻")
    }

    @Test
    fun `覆盖导入会清空本机而备份为空时本机确实变空`() {
        val local = snap(items = listOf(item("i1", "本地任务", 100)))
        val applied = TodoTransfer.apply(local, snap(), ImportMode.REPLACE, now = 200)
        assertTrue(applied.items.isEmpty())
    }

    @Test
    fun `重复合并同一份备份是幂等的`() {
        val local = snap(items = listOf(item("local", "本地任务", 100)))
        val imported = snap(
            lists = listOf(list("l1", "工作", 100)),
            items = listOf(item("i1", "备份任务", 300), item("i2", "备份已删", 300, deletedAt = 300)),
        )
        val once = TodoTransfer.apply(local, imported, ImportMode.MERGE, now = 400)
        val twice = TodoTransfer.apply(once, imported, ImportMode.MERGE, now = 500)
        assertEquals(once.lists, twice.lists, "重复导入不应产生新的变化")
        assertEquals(once.items, twice.items, "重复导入不应产生新的变化")
    }

    @Test
    fun `合并导入的墓碑会删除本地对应条目`() {
        val local = snap(items = listOf(item("i1", "本地任务", 100)))
        val imported = snap(items = listOf(item("i1", "本地任务", 100, deletedAt = 300)))
        val applied = TodoTransfer.apply(local, imported, ImportMode.MERGE, now = 400)
        val survivor = applied.items.single()
        assertTrue(survivor.deletedAt != null, "备份里该条目已删除，合并后本地也应处于删除状态")
    }

    // ---------- 预览 ----------

    @Test
    fun `预览带上文件名与统计`() {
        val preview = TodoTransfer.preview(
            fileName = "todoapp-20260921-0003.json",
            local = snap(),
            imported = snap(lists = listOf(list("l1", "工作", 1)), items = listOf(item("i1", "任务", 1)), rev = 9, savedAt = 888),
            now = 1000,
        )
        assertEquals("todoapp-20260921-0003.json", preview.fileName)
        assertEquals(1, preview.stats.lists)
        assertEquals(1, preview.stats.items)
        // 影响统计把清单与待办合并计数：1 个新清单 + 1 条新待办
        assertEquals(2, preview.mergeImpact.added)
        assertEquals(9, preview.rev)
        assertEquals(888, preview.exportedAt)
    }

    // ---------- 文件名 ----------

    @Test
    fun `建议文件名形如 todoapp-日期-时间 json`() {
        val now = 1_758_388_800_000L // 固定时刻，格式断言与时区无关
        val name = TodoTransfer.suggestedFileName(now)
        assertTrue(
            Regex("""todoapp-\d{8}-\d{4}\.json""").matches(name),
            "文件名格式不符：$name",
        )
        val date = Dates.toLocalDate(now)
        val expectedDay = "${date.year}" +
            (date.month.ordinal + 1).toString().padStart(2, '0') +
            date.day.toString().padStart(2, '0')
        assertTrue(name.contains(expectedDay), "文件名应包含本机时区日期 $expectedDay，实际：$name")
    }
}
