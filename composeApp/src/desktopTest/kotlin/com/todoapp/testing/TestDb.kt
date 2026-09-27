package com.todoapp.testing

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.todoapp.data.TodoRepository
import com.todoapp.db.AppDatabase

/**
 * 桌面测试共用夹具：进程内 SQLite + 可控时钟的仓库。
 * 时钟从 [startAt] 起步，用 [advance] 向前拨动，让时间戳断言完全确定。
 */
class TestDb(startAt: Long = 1_000_000L) {

    private var now = startAt

    val driver: JdbcSqliteDriver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also {
        AppDatabase.Schema.create(it)
    }
    val db = AppDatabase(driver)
    val repository = TodoRepository(db) { now }

    /** 时间前进 [millis] 毫秒。 */
    fun advance(millis: Long) {
        now += millis
    }

    /** 用同一个内存库再开一个仓库实例（验证数据真正落了库）。 */
    fun reopenedRepository(): TodoRepository = TodoRepository(db) { now }
}
