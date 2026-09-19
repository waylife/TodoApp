package com.todoapp.ui

import androidx.compose.runtime.Composable

@Composable
internal actual fun BackHandlerCompat(onBack: () -> Unit) {
    // 桌面端无系统返回键，设置页使用左上角返回按钮
}
