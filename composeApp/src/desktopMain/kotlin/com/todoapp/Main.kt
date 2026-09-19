package com.todoapp

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.todoapp.di.AppContainer
import com.todoapp.di.initKoin
import com.todoapp.ui.App
import java.io.File
import javax.imageio.ImageIO
import kotlinx.coroutines.delay
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.plus
import com.todoapp.util.Dates

fun main(args: Array<String>) {
    val container: AppContainer = initKoin()

    // 自动化验证模式：
    //   --render <输出路径> [--tab plan] [--seed]  离屏渲染界面为 PNG（不受窗口遮挡影响）
    //   --selftest <输出路径> [--seed] [--tab plan] 启动窗口后截屏
    val renderFlag = args.indexOf("--render")
    val renderPath = if (renderFlag >= 0) args.getOrNull(renderFlag + 1) else null
    val selftestFlag = args.indexOf("--selftest")
    val selftestPath = if (selftestFlag >= 0) args.getOrNull(selftestFlag + 1) else null
    val seedDemo = args.contains("--seed")
    val initialTab = when (args.getOrNull(args.indexOf("--tab") + 1)) {
        "plan" -> "PLAN"
        "settings" -> "SETTINGS"
        else -> "TODOS"
    }
    // --range today|week|2weeks|month：指定计划页的时间范围（自动化验证用）
    when (args.getOrNull(args.indexOf("--range") + 1)) {
        "week" -> container.planViewModel.setRange(com.todoapp.util.PlanRange.WEEK)
        "2weeks" -> container.planViewModel.setRange(com.todoapp.util.PlanRange.TWO_WEEKS)
        "month" -> container.planViewModel.setRange(com.todoapp.util.PlanRange.MONTH)
        "today" -> container.planViewModel.setRange(com.todoapp.util.PlanRange.TODAY)
    }
    if (seedDemo || renderPath != null) seedDemoData(container)

    if (renderPath != null) {
        renderToPng(container, initialTab, renderPath)
        return
    }

    application {
        val state = rememberWindowState(width = 1200.dp, height = 800.dp)
        Window(
            onCloseRequest = ::exitApplication,
            state = state,
            title = "待办 TodoApp",
        ) {
            App(container, initialTab)

            if (selftestPath != null) {
                LaunchedEffect(Unit) {
                    delay(2500)
                    // 窗口操作需在非事件线程执行
                    Thread {
                        runSelftest(selftestPath)
                        exitApplication()
                    }.start()
                }
            }
        }
    }
}

private fun runSelftest(outputPath: String) {
    try {
        val window = java.awt.Window.getWindows().firstOrNull { it.isVisible } ?: return
        // 截图前把窗口置顶，避免被其它应用窗口遮挡
        window.isAlwaysOnTop = true
        window.toFront()
        window.requestFocus()
        java.awt.Robot().waitForIdle()
        Thread.sleep(600)
        val location = window.locationOnScreen
        val size = window.size
        val capture = java.awt.Robot().createScreenCapture(
            java.awt.Rectangle(location.x, location.y, size.width, size.height),
        )
        window.isAlwaysOnTop = false
        ImageIO.write(capture, "png", File(outputPath))
        println("SELFTEST_SCREENSHOT_SAVED: $outputPath")
    } catch (e: Exception) {
        println("SELFTEST_FAILED: ${e.message}")
    }
}

/** 离屏把界面渲染为 PNG，用于自动化验证（不依赖窗口系统与前台焦点）。 */
private fun renderToPng(container: AppContainer, initialTab: String, outputPath: String) {
    try {
        val content: @androidx.compose.runtime.Composable () -> Unit = when (initialTab) {
            "SETTINGS" -> {
                { com.todoapp.ui.theme.TodoTheme { com.todoapp.ui.settings.SettingsScreen(container.settingsViewModel) {} } }
            }
            else -> {
                { com.todoapp.ui.App(container, initialTab) }
            }
        }
        val scene = androidx.compose.ui.ImageComposeScene(
            width = 1200,
            height = 800,
            density = androidx.compose.ui.unit.Density(1f),
            content = content,
        )
        // 渲染若干帧让初始状态与 LaunchedEffect 生效
        var image: org.jetbrains.skia.Image? = null
        repeat(5) { frame ->
            image = scene.render(frame * 16_000_000L)
        }
        val data = image?.encodeToData(org.jetbrains.skia.EncodedImageFormat.PNG)
        if (data == null) {
            println("SELFTEST_FAILED: 渲染结果为空")
        } else {
            File(outputPath).writeBytes(data.bytes)
            println("SELFTEST_SCREENSHOT_SAVED: $outputPath")
        }
        scene.close()
    } catch (e: Exception) {
        println("SELFTEST_FAILED: ${e.message}")
        e.printStackTrace()
    }
}

/** 演示数据：覆盖今日/本周/两周内/一个月内/已逾期各时间范围，用于验证计划页。 */
private fun seedDemoData(container: AppContainer) {
    val repo = container.repository
    if (repo.lists.value.any { it.deletedAt == null }) return

    val today = Dates.today()
    val work = repo.addList("工作")
    val life = repo.addList("生活")
    val study = repo.addList("学习")

    fun due(daysFromToday: Long): Long {
        val date = today.plus(DatePeriod(days = daysFromToday.toInt()))
        return Dates.startOfDay(date)
    }

    repo.addItem(work.id, "提交季度报告", due(-2), note = "已逾期示例")
    repo.addItem(work.id, "回复客户邮件", due(0))
    repo.addItem(work.id, "准备周会材料", due(0), note = "含本周进度与风险")
    repo.addItem(work.id, "代码评审", due(1))
    repo.addItem(work.id, "更新项目排期", due(3))
    repo.addItem(life.id, "买菜", due(0))
    repo.addItem(life.id, "预约体检", due(5))
    repo.addItem(life.id, "缴纳水电费", due(10))
    repo.addItem(study.id, "读完《Kotlin 实战》第 8 章", due(2))
    repo.addItem(study.id, "完成 KMP 练习", due(20))
    repo.addItem(study.id, "整理笔记", due(0))

    // 一条已完成，验证「已完成」分组
    val doneItem = repo.addItem(work.id, "每日站会", due(0))
    repo.setDone(doneItem.id, true)
}
