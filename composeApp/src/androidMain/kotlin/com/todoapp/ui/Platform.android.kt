package com.todoapp.ui

import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

@Composable
internal actual fun BackHandlerCompat(onBack: () -> Unit) {
    BackHandler(onBack = onBack)
}

@Composable
internal actual fun AppForegroundEffect(onForeground: () -> Unit) {
    val activity = LocalContext.current as? ComponentActivity ?: return
    val currentOnForeground by rememberUpdatedState(onForeground)
    DisposableEffect(activity) {
        // ON_START 在冷启动与「从后台切回」时都会触发，两者统一走这一条路径
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) currentOnForeground()
        }
        activity.lifecycle.addObserver(observer)
        onDispose { activity.lifecycle.removeObserver(observer) }
    }
}
