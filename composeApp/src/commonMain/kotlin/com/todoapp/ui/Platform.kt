package com.todoapp.ui

import androidx.compose.runtime.Composable

/** 系统返回键处理：Android 接入系统返回，桌面/iOS 为空实现。 */
@Composable
internal expect fun BackHandlerCompat(onBack: () -> Unit)
