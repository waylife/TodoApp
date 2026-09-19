package com.todoapp.di

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import com.russhwolf.settings.NSUserDefaultsSettings
import com.russhwolf.settings.Settings
import com.todoapp.db.AppDatabase
import com.todoapp.sync.todoHttpConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import org.koin.core.module.Module
import org.koin.dsl.module
import platform.Foundation.NSUserDefaults

actual val platformModule: Module = module {
    single<SqlDriver> { NativeSqliteDriver(AppDatabase.Schema, "todoapp.db") }
    single<AppDatabase> { AppDatabase(get()) }

    single<Settings> { NSUserDefaultsSettings(NSUserDefaults.standardUserDefaults) }

    single { HttpClient(Darwin) { todoHttpConfig() } }
}
