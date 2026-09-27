package com.todoapp.di

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.russhwolf.settings.PreferencesSettings
import com.russhwolf.settings.Settings
import com.todoapp.db.AppDatabase
import com.todoapp.sync.todoHttpConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import org.koin.core.module.Module
import org.koin.dsl.module
import java.io.File
import java.util.prefs.Preferences

actual val platformModule: Module = module {
    single<SqlDriver> { openTodoDbDriver(File(System.getProperty("user.home"), ".todoapp").resolve("todoapp.db")) }
    single<AppDatabase> { AppDatabase(get()) }

    single<Settings> { PreferencesSettings(Preferences.userRoot().node("/com/todoapp")) }

    single { HttpClient(CIO) { todoHttpConfig() } }
}

/**
 * 打开（必要时创建/迁移）桌面端 SQLite 数据库。
 *
 * Android/iOS 驱动自带版本管理，桌面端 JDBC 驱动没有，必须自己维护 `PRAGMA user_version`：
 * 此前只在文件不存在时建库，schema 升级后旧库会在查询时报错。
 * 判定依据是表是否存在而非 user_version：本改动之前创建的旧库表已存在但 user_version 仍为 0，
 * 走 migrate(0 → 当前版本) 把版本号补齐；全新库则走 create。
 */
internal fun openTodoDbDriver(dbFile: File): SqlDriver {
    dbFile.parentFile?.mkdirs()
    val driver = JdbcSqliteDriver("jdbc:sqlite:${dbFile.absolutePath}")
    val schema = AppDatabase.Schema
    val hasTables = driver.executeQuery(
        null,
        "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = 'todoList'",
        { cursor -> QueryResult.Value(cursor.next().value && (cursor.getLong(0) ?: 0L) > 0L) },
        0,
        null,
    ).value
    val userVersion = if (hasTables) driver.readUserVersion() else 0L
    when {
        !hasTables -> schema.create(driver)
        userVersion < schema.version -> schema.migrate(driver, userVersion, schema.version)
        // userVersion > schema.version：库由更新的应用版本写入。不迁移也不降级版本号，
        // 否则应用再升级时会对已迁移过的库重复执行迁移（如 ADD COLUMN 直接报错）。
    }
    if (userVersion < schema.version) {
        driver.execute(null, "PRAGMA user_version = ${schema.version}", 0, null)
    }
    return driver
}

internal fun SqlDriver.readUserVersion(): Long =
    executeQuery(
        null,
        "PRAGMA user_version",
        { cursor -> QueryResult.Value(if (cursor.next().value) cursor.getLong(0) ?: 0L else 0L) },
        0,
        null,
    ).value
