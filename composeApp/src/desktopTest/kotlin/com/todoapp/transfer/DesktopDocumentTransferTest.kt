package com.todoapp.transfer

import com.todoapp.model.RemoteSnapshot
import com.todoapp.model.TodoItem
import com.todoapp.model.TodoList
import java.awt.FileDialog
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * 桌面端文件读写的真实路径验证。
 *
 * 只把「弹出原生对话框挑文件」这一步换成固定返回值——原生模态框无法脚本驱动
 * （需要辅助功能权限），但「拿到文件之后怎么读写、出错怎么报」是真实代码，这里全覆盖。
 */
class DesktopDocumentTransferTest {

    private val workDir: File = Files.createTempDirectory("todoapp-transfer-test").toFile()

    @AfterTest
    fun tearDown() {
        workDir.deleteRecursively()
    }

    private fun transferReturning(file: File?) =
        DesktopDocumentTransfer { _, _, _ -> file }

    private fun transferCancelled() = DesktopDocumentTransfer { _, _, _ -> null }

    // ---------- 导出 ----------

    @Test
    fun `导出把内容写入选定文件并返回绝对路径`() = runBlocking {
        val target = File(workDir, "todoapp-20260921-0100.json")
        val content = """{"schemaVersion":1,"lists":[],"items":[]}"""

        val outcome = transferReturning(target).saveJson("todoapp-20260921-0100.json", content)

        val ok = outcome as TransferOutcome.Ok
        assertEquals(target.absolutePath, ok.value)
        assertEquals(content, target.readText())
    }

    @Test
    fun `导出时用户取消不创建文件`() = runBlocking {
        val outcome = transferCancelled().saveJson("todoapp.json", "{}")

        assertEquals(TransferOutcome.Cancelled, outcome)
        assertEquals(emptyList<File>(), workDir.listFiles()?.toList() ?: emptyList<File>())
    }

    @Test
    fun `导出覆盖已存在的文件不会残留旧内容尾部`() = runBlocking {
        val target = File(workDir, "existing.json")
        target.writeText("旧的很长很长的内容，长度明显超过新内容")

        transferReturning(target).saveJson("existing.json", "短")

        assertEquals("短", target.readText())
    }

    @Test
    fun `导出到不可写路径时给出可读错误`() = runBlocking {
        // 目标是目录：写入必然失败，且失败必须被转成 Failed 而不是抛出去
        val directory = File(workDir, "a-directory").apply { mkdirs() }

        val outcome = transferReturning(directory).saveJson("x.json", "{}")

        val failed = outcome as TransferOutcome.Failed
        assertTrue(failed.message.startsWith("写入失败："), failed.message)
    }

    // ---------- 导入 ----------

    @Test
    fun `导入读取选定文件的内容与文件名`() = runBlocking {
        val source = File(workDir, "backup.json")
        source.writeText("""{"schemaVersion":1,"lists":[],"items":[]}""")

        val outcome = transferReturning(source).openJson()

        val ok = outcome as TransferOutcome.Ok
        assertEquals("backup.json", ok.value.name)
        assertEquals(source.readText(), ok.value.content)
    }

    @Test
    fun `导入时用户取消`() = runBlocking {
        assertEquals(TransferOutcome.Cancelled, transferCancelled().openJson())
    }

    @Test
    fun `导入不存在的文件时给出可读错误`() = runBlocking {
        val outcome = transferReturning(File(workDir, "nope.json")).openJson()

        val failed = outcome as TransferOutcome.Failed
        assertTrue(failed.message.startsWith("读取失败："), failed.message)
    }

    @Test
    fun `导入超大文件时直接拒绝而不是读进内存`() = runBlocking {
        // 用 setLength 撑出稀疏文件：逻辑上是 32MB+1，几乎不占磁盘
        val huge = File(workDir, "huge.json")
        RandomAccessFile(huge, "rw").use { it.setLength(TodoTransfer.MAX_BACKUP_BYTES + 1) }

        val outcome = transferReturning(huge).openJson()

        val failed = outcome as TransferOutcome.Failed
        assertEquals(TodoTransfer.tooLargeMessage(), failed.message)
    }

    @Test
    fun `刚好等于上限的文件仍然可以导入`() = runBlocking {
        val boundary = File(workDir, "boundary.json")
        RandomAccessFile(boundary, "rw").use { it.setLength(TodoTransfer.MAX_BACKUP_BYTES) }

        // 边界条件：判定用的是 `>` 而不是 `>=`，等于上限应放行
        val outcome = transferReturning(boundary).openJson()

        val ok = outcome as TransferOutcome.Ok
        assertEquals(TodoTransfer.MAX_BACKUP_BYTES, ok.value.content.length.toLong())
    }

    // ---------- 往返 ----------

    @Test
    fun `导出的文件能被重新导入且内容完全一致`() = runBlocking {
        val target = File(workDir, "roundtrip.json")
        val backup = TodoTransfer.encode(
            RemoteSnapshot(
                rev = 7,
                savedAt = 1_758_388_800_000L,
                lists = listOf(
                    TodoList(id = "l1", name = "工作", sort = 1, createdAt = 100, updatedAt = 200),
                ),
                items = listOf(
                    TodoItem(
                        id = "i1", listId = "l1", title = "写周报", note = "含风险",
                        createdAt = 100, updatedAt = 200,
                    ),
                    TodoItem(
                        id = "i2", listId = "l1", title = "已删掉的",
                        createdAt = 100, updatedAt = 300, deletedAt = 300,
                    ),
                ),
            ),
        )

        transferReturning(target).saveJson("roundtrip.json", backup)
        val picked = (transferReturning(target).openJson() as TransferOutcome.Ok).value

        // 落盘 → 读回 → 解析，三步之后仍与原快照逐字段相等
        val decoded = TodoTransfer.decode(picked.content).getOrThrow()
        assertEquals(7, decoded.rev)
        assertEquals(1_758_388_800_000L, decoded.savedAt)
        assertEquals(listOf("工作"), decoded.lists.map { it.name })
        assertEquals(listOf("写周报", "已删掉的"), decoded.items.map { it.title })
        assertTrue(
            decoded.items.single { it.id == "i2" }.deletedAt != null,
            "墓碑必须原样穿过落盘与读回，否则换机后删除动作会丢失",
        )
        assertFalse(picked.content.isBlank())
    }

    @Test
    fun `导出使用 SAVE 模式而导入使用 LOAD 模式`() = runBlocking {
        var saveMode: Int? = null
        var loadMode: Int? = null
        val target = File(workDir, "mode.json").apply { writeText("{}") }
        val transfer = DesktopDocumentTransfer { _, _, mode ->
            if (mode == FileDialog.SAVE) saveMode = mode else loadMode = mode
            target
        }

        transfer.saveJson("mode.json", "{}")
        transfer.openJson()

        assertEquals(FileDialog.SAVE, saveMode)
        assertEquals(FileDialog.LOAD, loadMode)
    }

    @Test
    fun `导出会把建议文件名交给对话框`() = runBlocking {
        var suggested: String? = null
        val transfer = DesktopDocumentTransfer { _, name, _ ->
            suggested = name
            File(workDir, "out.json")
        }

        transfer.saveJson("todoapp-20260921-0100.json", "{}")

        assertEquals("todoapp-20260921-0100.json", suggested)
    }
}
