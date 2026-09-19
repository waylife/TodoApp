package com.todoapp.di

import android.content.Context
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.russhwolf.settings.Settings
import com.russhwolf.settings.SharedPreferencesSettings
import com.todoapp.db.AppDatabase
import com.todoapp.sync.todoHttpConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import org.koin.core.module.Module
import org.koin.dsl.module

actual val platformModule: Module = module {
    single<SqlDriver> {
        AndroidSqliteDriver(
            schema = AppDatabase.Schema,
            context = get<Context>(),
            name = "todoapp.db",
        )
    }
    single<AppDatabase> { AppDatabase(get()) }

    single<Settings> {
        val prefs = get<Context>().getSharedPreferences("todoapp_settings", Context.MODE_PRIVATE)
        SharedPreferencesSettings(prefs)
    }

    single { HttpClient(OkHttp) { todoHttpConfig() } }
}
