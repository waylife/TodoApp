package com.todoapp.di

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
    single<SqlDriver> {
        val dir = File(System.getProperty("user.home"), ".todoapp").apply { mkdirs() }
        val dbFile = File(dir, "todoapp.db")
        val isNew = !dbFile.exists()
        val driver = JdbcSqliteDriver("jdbc:sqlite:${dbFile.absolutePath}")
        if (isNew) AppDatabase.Schema.create(driver)
        driver
    }
    single<AppDatabase> { AppDatabase(get()) }

    single<Settings> { PreferencesSettings(Preferences.userRoot().node("/com/todoapp")) }

    single { HttpClient(CIO) { todoHttpConfig() } }
}
