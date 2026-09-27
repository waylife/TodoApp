package com.todoapp.testing

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher

/**
 * 测试专用的「即时」作用域：复用 [TestScope.backgroundScope] 的 Job（测试结束自动取消），
 * 但换成 UnconfinedTestDispatcher——stateIn(Eagerly) 的共享协程与 ViewModel 内的 launch
 * 都同步执行，变更后立即可断言 uiState，不依赖虚拟时间调度。
 */
fun TestScope.eagerTestScope(): CoroutineScope =
    CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler))
