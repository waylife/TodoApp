package com.todoapp.di

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.todoapp.db.AppDatabase
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 桌面端建库/迁移：JDBC 驱动没有内置版本管理，[openTodoDbDriver] 必须自己维护
 * `PRAGMA user_version`——全新库建表，旧库补齐版本号，重开不重建、数据保留。
 */
class DesktopDbMigrationTest {

    /** 零字节文件即合法的空 SQLite 库。 */
    private fun tempDbFile(): File = File.createTempFile("todoapp-migration-", ".db")

    @Test
    fun `全新库建表并写入版本号`() {
        val dbFile = tempDbFile()
        val driver = openTodoDbDriver(dbFile)
        try {
            val db = AppDatabase(driver)
            db.todoQueries.upsertList("l1", "工作", 1, 1, 1, null)
            assertEquals(listOf("工作"), db.todoQueries.selectAllLists().executeAsList().map { it.name })
            assertEquals(AppDatabase.Schema.version, driver.readUserVersion(), "建库后应写入 schema 版本号")
        } finally {
            driver.close()
        }
    }

    @Test
    fun `旧库重开时数据保留且版本号补齐`() {
        val dbFile = tempDbFile()
        // 模拟版本管理引入之前创建的旧库：表已存在（v1 结构）但 user_version 仍为 0。
        // 该库会走 migrate(0 → 当前版本)：依次执行缺失的迁移（现在是 2.sqm 与 3.sqm）并补齐版本号。
        val legacy = JdbcSqliteDriver("jdbc:sqlite:${dbFile.absolutePath}")
        legacy.execute(null, V1_LIST_DDL, 0, null)
        legacy.execute(null, V1_ITEM_DDL, 0, null)
        legacy.execute(
            null,
            "INSERT INTO todoList(id, name, sort, createdAt, updatedAt, deletedAt) VALUES ('l1', '工作', 1, 1, 1, NULL)",
            0,
            null,
        )
        legacy.close()

        val driver = openTodoDbDriver(dbFile)
        try {
            val db = AppDatabase(driver)
            assertEquals(listOf("工作"), db.todoQueries.selectAllLists().executeAsList().map { it.name }, "重开不得重建库，数据应保留")
            assertEquals(AppDatabase.Schema.version, driverVersion(driver), "旧库应补齐版本号")
        } finally {
            driver.close()
        }
    }

    @Test
    fun `更高版本号的库重开时不降级版本号`() {
        val dbFile = tempDbFile()
        val first = openTodoDbDriver(dbFile)
        AppDatabase(first).todoQueries.upsertList("l1", "工作", 1, 1, 1, null)
        first.close()

        // 模拟应用被回滚到旧版本前，库已被更新的应用迁移到更高 schema 版本
        val reopen = openTodoDbDriver(dbFile)
        reopen.execute(null, "PRAGMA user_version = 9", 0, null)
        reopen.close()

        val third = openTodoDbDriver(dbFile)
        try {
            val db = AppDatabase(third)
            assertEquals(listOf("工作"), db.todoQueries.selectAllLists().executeAsList().map { it.name }, "数据应保留")
            assertEquals(
                9L,
                driverVersion(third),
                "版本号只升不降：否则应用再升级时会对已迁移过的库重复执行迁移",
            )
        } finally {
            third.close()
        }
    }

    @Test
    fun `v1 旧库迁移后数据保留且新增列可用`() {
        val dbFile = tempDbFile()
        // 手工搭出 v1 时代的旧库：没有 description / progress 列，user_version = 1
        val old = JdbcSqliteDriver("jdbc:sqlite:${dbFile.absolutePath}")
        old.execute(null, V1_LIST_DDL, 0, null)
        old.execute(null, V1_ITEM_DDL, 0, null)
        old.execute(
            null,
            "INSERT INTO todoList(id, name, sort, createdAt, updatedAt, deletedAt) VALUES ('l1', '工作', 1, 1, 1, NULL)",
            0,
            null,
        )
        old.execute(
            null,
            "INSERT INTO todoItem(id, listId, title, note, done, dueAt, createdAt, updatedAt, deletedAt) " +
                "VALUES ('a1', 'l1', '写周报', '备注', 0, NULL, 1, 1, NULL)",
            0,
            null,
        )
        old.execute(null, "PRAGMA user_version = 1", 0, null)
        old.close()

        val driver = openTodoDbDriver(dbFile)
        try {
            assertEquals(AppDatabase.Schema.version, driverVersion(driver), "旧库应经迁移达到当前 schema 版本")
            val db = AppDatabase(driver)
            val item = db.todoQueries.selectAllItems().executeAsList().single()
            assertEquals("写周报", item.title, "迁移不得丢数据")
            assertEquals("备注", item.note)
            assertEquals("", item.description, "迁移后描述取默认空串")
            assertEquals("[]", item.progress, "迁移后进度时间线取默认空数组")

            db.todoQueries.upsertItem("a1", "l1", "写周报", "备注", "新描述", "[]", 0L, null, 1, 1, null, null)
            assertEquals(
                "新描述",
                db.todoQueries.selectAllItems().executeAsList().single().description,
                "迁移后的库应能写入新字段",
            )
        } finally {
            driver.close()
        }
    }

    @Test
    fun `v3 旧库迁移后数据保留且整体进度列可用`() {
        val dbFile = tempDbFile()
        // 手工搭出现行版本的旧库（v3：已有 description / progress，没有 progressPercent，user_version = 3）。
        // 注意 SQLDelight 的语义是 N.sqm 把库从版本 N 迁到 N+1：本次升级走 migrate(3 → 4)，只执行 3.sqm。
        val old = JdbcSqliteDriver("jdbc:sqlite:${dbFile.absolutePath}")
        old.execute(null, V1_LIST_DDL, 0, null)
        old.execute(null, V2_ITEM_DDL, 0, null)
        old.execute(
            null,
            "INSERT INTO todoList(id, name, sort, createdAt, updatedAt, deletedAt) VALUES ('l1', '工作', 1, 1, 1, NULL)",
            0,
            null,
        )
        old.execute(
            null,
            "INSERT INTO todoItem(id, listId, title, note, description, progress, done, dueAt, createdAt, updatedAt, deletedAt) " +
                "VALUES ('a1', 'l1', '写周报', '备注', '任务详情', '[]', 0, NULL, 1, 1, NULL)",
            0,
            null,
        )
        old.execute(null, "PRAGMA user_version = 3", 0, null)
        old.close()

        val driver = openTodoDbDriver(dbFile)
        try {
            assertEquals(AppDatabase.Schema.version, driverVersion(driver), "旧库应经迁移达到当前 schema 版本")
            val db = AppDatabase(driver)
            val item = db.todoQueries.selectAllItems().executeAsList().single()
            assertEquals("写周报", item.title, "迁移不得丢数据")
            assertEquals("任务详情", item.description)
            assertNull(item.progressPercent, "迁移后整体进度取 NULL（未设置）")

            db.todoQueries.upsertItem("a1", "l1", "写周报", "备注", "任务详情", "[]", 0L, null, 1, 1, null, 45L)
            assertEquals(
                45L,
                db.todoQueries.selectAllItems().executeAsList().single().progressPercent,
                "迁移后的库应能写入整体进度",
            )
        } finally {
            driver.close()
        }
    }

    /** 与 readUserVersion 解耦，直接从驱动读，顺便交叉验证写入值。 */
    private fun driverVersion(driver: SqlDriver): Long {
        var version = -1L
        driver.executeQuery(null, "PRAGMA user_version", { cursor ->
            if (cursor.next().value) version = cursor.getLong(0) ?: 0L
            app.cash.sqldelight.db.QueryResult.Value(Unit)
        }, 0, null).value
        return version
    }

    private companion object {
        /** v1（v2 之前）的表结构，与本仓库早期版本的 Todo.sq 一致。 */
        val V1_LIST_DDL = """
            CREATE TABLE todoList (
                id TEXT NOT NULL PRIMARY KEY,
                name TEXT NOT NULL,
                sort INTEGER NOT NULL DEFAULT 0,
                createdAt INTEGER NOT NULL,
                updatedAt INTEGER NOT NULL,
                deletedAt INTEGER
            )
        """.trimIndent()

        val V1_ITEM_DDL = """
            CREATE TABLE todoItem (
                id TEXT NOT NULL PRIMARY KEY,
                listId TEXT NOT NULL,
                title TEXT NOT NULL,
                note TEXT NOT NULL DEFAULT '',
                done INTEGER NOT NULL DEFAULT 0,
                dueAt INTEGER,
                createdAt INTEGER NOT NULL,
                updatedAt INTEGER NOT NULL,
                deletedAt INTEGER
            )
        """.trimIndent()

        /** v3（v4 之前）的条目表结构 = v1 + description / progress，即本次升级前线上库的结构。 */
        val V2_ITEM_DDL = """
            CREATE TABLE todoItem (
                id TEXT NOT NULL PRIMARY KEY,
                listId TEXT NOT NULL,
                title TEXT NOT NULL,
                note TEXT NOT NULL DEFAULT '',
                description TEXT NOT NULL DEFAULT '',
                progress TEXT NOT NULL DEFAULT '[]',
                done INTEGER NOT NULL DEFAULT 0,
                dueAt INTEGER,
                createdAt INTEGER NOT NULL,
                updatedAt INTEGER NOT NULL,
                deletedAt INTEGER
            )
        """.trimIndent()
    }
}
