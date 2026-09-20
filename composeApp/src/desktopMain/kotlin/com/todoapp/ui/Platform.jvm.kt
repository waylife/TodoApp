package com.todoapp.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect

@Composable
internal actual fun BackHandlerCompat(onBack: () -> Unit) {
    // 桌面端无系统返回键，设置页使用左上角返回按钮
}

@Composable
internal actual fun AppForegroundEffect(onForeground: () -> Unit) {
    // 桌面端没有系统级前后台概念，以「首次组合」近似：窗口重建时会再次触发
    LaunchedEffect(Unit) { onForeground() }
}
