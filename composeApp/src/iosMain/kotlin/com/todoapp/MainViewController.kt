package com.todoapp

import androidx.compose.ui.window.ComposeUIViewController
import com.todoapp.di.initKoin
import com.todoapp.ui.App
import platform.UIKit.UIViewController

fun MainViewController(): UIViewController {
    val container = initKoin()
    return ComposeUIViewController { App(container) }
}
