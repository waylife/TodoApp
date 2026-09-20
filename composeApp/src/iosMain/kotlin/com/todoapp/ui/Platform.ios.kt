package com.todoapp.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect

@Composable
internal actual fun BackHandlerCompat(onBack: () -> Unit) {
    // iOS 端 MVP 使用左上角返回按钮；手势返回留待接入
}

@Composable
internal actual fun AppForegroundEffect(onForeground: () -> Unit) {
    // TODO: iOS 同样存在「从后台切回不触发」的问题，需观察
    // UIApplicationWillEnterForegroundNotification；暂以「首次组合」近似
    LaunchedEffect(Unit) { onForeground() }
}
