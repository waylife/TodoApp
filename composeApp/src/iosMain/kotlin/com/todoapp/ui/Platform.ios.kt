package com.todoapp.ui

import androidx.compose.runtime.Composable

@Composable
internal actual fun BackHandlerCompat(onBack: () -> Unit) {
    // iOS 端 MVP 使用左上角返回按钮；手势返回留待接入
}
