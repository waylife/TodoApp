import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidKmpLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.sqldelight)
}

kotlin {
    // AGP 9 KMP 插件：共享模块以 Android library 形式参与构建，APK 由 :androidApp 产出
    android {
        namespace = "com.todoapp"
        compileSdk = libs.versions.androidCompileSdk.get().toInt()
        minSdk = libs.versions.androidMinSdk.get().toInt()
    }

    jvm("desktop")

    listOf(iosArm64(), iosSimulatorArm64()).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "TodoApp"
            isStatic = true
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.ui)
            // material-icons-core：Icons 对象与核心图标集（material3 1.9+
            // 不再传递提供）；扩展图标已内联进 AppIcons.kt，勿引 extended
            implementation(libs.compose.icons.core)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.datetime)
            implementation(libs.ktor.client.core)
            implementation(libs.sqldelight.runtime)
            implementation(libs.multiplatform.settings)
            implementation(libs.koin.core)
        }

        androidMain.dependencies {
            implementation(libs.androidx.activity.compose)
            implementation(libs.ktor.client.okhttp)
            implementation(libs.sqldelight.android.driver)
        }

        getByName("desktopMain").dependencies {
            implementation(compose.desktop.currentOs)
            implementation(libs.ktor.client.cio)
            implementation(libs.sqldelight.sqlite.driver)
        }

        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
            implementation(libs.sqldelight.native.driver)
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }

        getByName("desktopTest").dependencies {
            implementation(libs.ktor.server.core)
            implementation(libs.ktor.server.cio)
        }
    }
}

compose.desktop {
    application {
        mainClass = "com.todoapp.MainKt"

        nativeDistributions {
            // 桌面端安装包：macOS 出 .dmg，Windows 出 .msi，Linux 出 .deb
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "TodoApp"
            packageVersion = "1.0.0"
            description = "Kotlin Multiplatform todo app with WebDAV sync"
            vendor = "TodoApp"

            // jlink 模块显式声明：compose 插件默认的依赖分析看不到 JDBC 的
            // 动态加载，缺 java.sql 会让安装包启动即崩；清单 = jdeps 对产物
            // 的分析结果 + java.sql / jdk.unsupported（Unsafe）/ TLS 所需的
            // jdk.crypto.ec。java.logging/java.xml 等由传递依赖自动带上。
            modules(
                "java.base",
                "java.desktop",
                "java.instrument",
                "java.management",
                "java.prefs",
                "java.sql",
                "jdk.unsupported",
                "jdk.crypto.ec",
            )
        }
    }
}

sqldelight {
    databases {
        create("AppDatabase") {
            packageName.set("com.todoapp.db")
        }
    }
}
