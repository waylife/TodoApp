package com.todoapp.di

import app.cash.sqldelight.db.SqlDriver
import com.todoapp.db.AppDatabase
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

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
        val first = openTodoDbDriver(dbFile)
        AppDatabase(first).todoQueries.upsertList("l1", "工作", 1, 1, 1, null)
        first.close()

        // 模拟本改动之前创建的旧库：表已存在但 user_version 仍为 0
        val reopen = openTodoDbDriver(dbFile)
        reopen.execute(null, "PRAGMA user_version = 0", 0, null)
        reopen.close()

        val third = openTodoDbDriver(dbFile)
        try {
            val db = AppDatabase(third)
            assertEquals(listOf("工作"), db.todoQueries.selectAllLists().executeAsList().map { it.name }, "重开不得重建库，数据应保留")
            assertEquals(AppDatabase.Schema.version, driverVersion(third), "旧库应补齐版本号")
        } finally {
            third.close()
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
}
