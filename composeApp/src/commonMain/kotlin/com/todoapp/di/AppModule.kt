package com.todoapp.di

import com.todoapp.data.SettingsStore
import com.todoapp.data.TodoRepository
import com.todoapp.sync.SyncEngine
import com.todoapp.util.Dates
import com.todoapp.viewmodel.EditSession
import com.todoapp.viewmodel.PlanViewModel
import com.todoapp.viewmodel.SettingsViewModel
import com.todoapp.viewmodel.TodosViewModel
import com.todoapp.viewmodel.TransferViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.core.KoinApplication
import org.koin.core.context.startKoin
import org.koin.core.module.Module
import org.koin.dsl.module

/** 各平台提供：数据库、设置存储、HttpClient。 */
expect val platformModule: Module

/** 平台入口调用，完成 Koin 装配并返回容器。 */
fun initKoin(configure: KoinApplication.() -> Unit = {}): AppContainer {
    val koin = startKoin {
        configure()
        modules(platformModule, appModule)
    }
    return koin.koin.get()
}

/** 应用装配。 */
val appModule = module {
    single { SettingsStore(get()) }
    single { TodoRepository(get()) { Dates.nowMillis() } }
    single<CoroutineScope> { CoroutineScope(SupervisorJob() + Dispatchers.Default) }
    single { SyncEngine(get(), get(), get(), get()) }
    single { EditSession(get(), get()) }
    single { TodosViewModel(get(), get()) }
    single { PlanViewModel(get(), get()) }
    single { SettingsViewModel(get(), get(), get(), get()) }
    single { TransferViewModel(get(), get(), get()) }
    single {
        AppContainer(
            repository = get(),
            syncEngine = get(),
            editSession = get(),
            todosViewModel = get(),
            planViewModel = get(),
            settingsViewModel = get(),
            transferViewModel = get(),
        )
    }
}

/** UI 层统一取用的容器。 */
class AppContainer(
    val repository: TodoRepository,
    val syncEngine: SyncEngine,
    val editSession: EditSession,
    val todosViewModel: TodosViewModel,
    val planViewModel: PlanViewModel,
    val settingsViewModel: SettingsViewModel,
    val transferViewModel: TransferViewModel,
)
