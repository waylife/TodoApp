package com.todoapp.ui

import androidx.compose.material.icons.Icons
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * 用到的 material-icons-extended 图标的内联版本（路径数据取自
 * google/material-design-icons 官方 SVG）。只为此引入几万行的扩展包
 * 不值得：它显著拖慢编译与 R8/链接，debug 包也会大好几 MB。
 */

private fun appIcon(
    name: String,
    autoMirrored: Boolean = false,
    block: ImageVector.Builder.() -> Unit,
): ImageVector = ImageVector.Builder(
    name = name,
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
    autoMirror = autoMirrored,
).apply(block).build()

private var _checklist: ImageVector? = null

val Icons.Filled.Checklist: ImageVector
    get() {
        _checklist?.let { return it }
        return appIcon(name = "Filled.Checklist") {
            path(fill = SolidColor(Color.Black)) {
                // 两条清单横线
                moveTo(22f, 7f)
                horizontalLineToRelative(-9f)
                verticalLineToRelative(2f)
                horizontalLineToRelative(9f)
                verticalLineTo(7f)
                close()
                moveTo(22f, 15f)
                horizontalLineToRelative(-9f)
                verticalLineToRelative(2f)
                horizontalLineToRelative(9f)
                verticalLineTo(15f)
                close()
                // 两个对勾
                moveTo(5.54f, 11f)
                lineTo(2f, 7.46f)
                lineToRelative(1.41f, -1.41f)
                lineToRelative(2.12f, 2.12f)
                lineToRelative(4.24f, -4.24f)
                lineToRelative(1.41f, 1.41f)
                lineTo(5.54f, 11f)
                close()
                moveTo(5.54f, 19f)
                lineTo(2f, 15.46f)
                lineToRelative(1.41f, -1.41f)
                lineToRelative(2.12f, 2.12f)
                lineToRelative(4.24f, -4.24f)
                lineToRelative(1.41f, 1.41f)
                lineTo(5.54f, 19f)
                close()
            }
        }.also { _checklist = it }
    }

private var _eventNote: ImageVector? = null

val Icons.AutoMirrored.Filled.EventNote: ImageVector
    get() {
        _eventNote?.let { return it }
        return appIcon(name = "Filled.EventNote", autoMirrored = true) {
            path(fill = SolidColor(Color.Black)) {
                // 日历页上的横线
                moveTo(17f, 10f)
                horizontalLineTo(7f)
                verticalLineToRelative(2f)
                horizontalLineToRelative(10f)
                verticalLineTo(10f)
                close()
                // 日历外框（含顶部两个挂环）
                moveTo(19f, 3f)
                horizontalLineToRelative(-1f)
                verticalLineTo(1f)
                horizontalLineToRelative(-2f)
                verticalLineToRelative(2f)
                horizontalLineTo(8f)
                verticalLineTo(1f)
                horizontalLineTo(6f)
                verticalLineToRelative(2f)
                horizontalLineTo(5f)
                curveToRelative(-1.11f, 0f, -1.99f, 0.9f, -1.99f, 2f)
                lineTo(3f, 19f)
                curveToRelative(0f, 1.1f, 0.89f, 2f, 2f, 2f)
                horizontalLineToRelative(14f)
                curveToRelative(1.1f, 0f, 2f, -0.9f, 2f, -2f)
                verticalLineTo(5f)
                curveToRelative(0f, -1.1f, -0.9f, -2f, -2f, -2f)
                close()
                moveTo(19f, 19f)
                horizontalLineTo(5f)
                verticalLineTo(8f)
                horizontalLineToRelative(14f)
                verticalLineToRelative(11f)
                close()
                // 备注下横线
                moveTo(14f, 14f)
                horizontalLineTo(7f)
                verticalLineToRelative(2f)
                horizontalLineToRelative(7f)
                verticalLineTo(14f)
                close()
            }
        }.also { _eventNote = it }
    }
