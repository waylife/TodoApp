package com.todoapp.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable

@Composable
internal actual fun BackHandlerCompat(onBack: () -> Unit) {
    BackHandler(onBack = onBack)
}
