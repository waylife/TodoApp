package com.todoapp.android

import android.app.Application
import android.content.Context
import com.todoapp.di.AppContainer
import com.todoapp.di.initKoin
import org.koin.dsl.module

class TodoAppApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = initKoin {
            modules(module {
                single<Context> { this@TodoAppApplication }
            })
        }
    }
}
