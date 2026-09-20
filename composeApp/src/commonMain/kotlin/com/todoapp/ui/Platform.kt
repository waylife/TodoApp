package com.todoapp.ui

import androidx.compose.runtime.Composable

/** 系统返回键处理：Android 接入系统返回，桌面/iOS 为空实现。 */
@Composable
internal expect fun BackHandlerCompat(onBack: () -> Unit)

/**
 * 应用进入前台（对用户可见）时回调，用于触发同步。
 *
 * Android 接系统生命周期（`ON_START`）：从后台切回时会触发。**这一步不能省**——
 * [App] 里的组合只在 Activity 被重建时重跑，而 Android 上应用极少真正「启动」，
 * 多数是从后台切回；只靠首次组合触发的话，切回前台永远拉不到其它设备的改动。
 * 桌面/iOS 暂无系统级前后台概念，以「首次组合」近似（与改动前行为一致）。
 */
@Composable
internal expect fun AppForegroundEffect(onForeground: () -> Unit)
