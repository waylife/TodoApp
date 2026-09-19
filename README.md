# TodoApp · KMP 跨平台待办

基于 **Kotlin Multiplatform + Compose Multiplatform** 的待办事项应用，一套代码覆盖
**Android / iOS / 桌面端（macOS·Windows·Linux）**，数据通过 **WebDAV 协议** 在多设备间同步。

## 功能

- **清单分组**：多清单管理，支持新建 / 重命名 / 删除（长按清单 chip）
- **待办管理**：添加、编辑（标题 / 备注）、完成勾选、删除
- **截止日期**：日期选择器设置，逾期高亮
- **计划视图**：跨清单汇总未完成待办，按「今日 / 本周 / 两周内 / 一个月内」筛选，已逾期置顶
- **搜索**：按标题与备注过滤
- **深色模式**：跟随系统
- **WebDAV 同步**：多端自动/手动同步，冲突按「最后修改者胜」合并

## 同步设计

- 远端布局：`{服务器}/{远程目录}/todoapp.json`，远程目录默认 `ToDoApp`，可在设置中自定义
  （支持多级路径，应用会逐级自动创建目录）。
- 同步算法：下载远端快照 → 与本地按条目「最后写入者胜（LWW）」合并 → 带乐观锁（`If-Match`/ETag）
  上传；遇到并发冲突自动重取合并，最多重试 3 次。两端使用相同算法，必然收敛到同一结果。
- 删除采用墓碑（软删除）机制，90 天后清理；长期（>90 天）不上线的设备可能遇到已删条目复活。
- 触发时机：应用启动、本地修改后防抖 2 秒、手动刷新。
- 快照含 `rev` 版本号与 `schemaVersion` 格式版本，便于后续演进。

## 构建与运行

依赖：JDK 17+（AGP 9 需 Gradle 9.6，项目已通过 wrapper 固定）、Android SDK（`local.properties`），
iOS 构建需 macOS + Xcode。

```bash
# 桌面端（开发调试）
./gradlew :composeApp:run

# Android APK
./gradlew :androidApp:assembleDebug   # 产物在 androidApp/build/outputs/apk/debug/

# iOS：生成 Xcode 工程（需 xcodegen：brew install xcodegen），再用 Xcode 打开
(cd iosApp && xcodegen generate)
open iosApp/iosApp.xcodeproj          # 选择 iosApp scheme 运行

# 单元测试 + 同步集成测试（含进程内迷你 WebDAV 服务，共 29 个用例）
./gradlew :composeApp:desktopTest

# 界面离屏渲染为 PNG（自动化验证用，不受窗口遮挡影响）
./gradlew :composeApp:run --args="--render /tmp/home.png --seed"                # 清单页（含演示数据）
./gradlew :composeApp:run --args="--render /tmp/plan.png --tab plan --range month"  # 计划页·一个月内
./gradlew :composeApp:run --args="--render /tmp/settings.png --tab settings"    # 设置页
```

## WebDAV 服务配置

设置页填写：

| 字段 | 说明 | 示例 |
| --- | --- | --- |
| 服务器地址 | WebDAV 根地址（https） | `https://dav.jianguoyun.com/dav/` |
| 用户名 | 登录账号 | `user@example.com` |
| 密码 | 账号密码或应用密码（坚果云需在网页端生成应用密码） | `xxxx` |
| 远程目录 | 数据存放目录，默认 `ToDoApp`，可自定义 | `ToDoApp` |

支持坚果云、Nextcloud、群晖、Apache/Nginx DAV 等标准实现。填写后可先「测试连接」再「保存并同步」。

### 已知限制（MVP）

- 使用 Basic 认证，请优先使用 HTTPS 服务器；暂不支持自签名证书。
- 凭据保存在各平台应用私有存储（Android SharedPreferences / iOS NSUserDefaults / 桌面端 Java Preferences），
  后续可升级为 iOS Keychain / Android Keystore 加密。
- 不支持经代理访问 WebDAV 服务器。

## 技术栈与结构

Kotlin 2.4.20 · Compose Multiplatform 1.12.0 · Ktor 3.6 · SQLDelight 2.4 · Koin 4.2 · kotlinx-datetime/serialization

```
composeApp/          共享模块（UI + 数据 + 同步，Android 以 KMP library 形式参与）
  src/commonMain     领域模型、SQLDelight 表、仓库、WebDAV 客户端、同步引擎、全部 UI
  src/androidMain    Android 平台实现（SQLite 驱动 / SharedPreferences / OkHttp）
  src/desktopMain    桌面入口与平台实现（JDBC SQLite / Preferences / CIO）
  src/iosMain        iOS 入口与平台实现（Native SQLite / NSUserDefaults / Darwin）
  src/commonTest     合并算法与时间范围单测
  src/desktopTest    进程内 WebDAV 端到端同步集成测试
androidApp/          Android 薄壳应用（AGP 9 KMP 规范：application 与 KMP library 分离）
iosApp/              iOS 壳工程（project.yml 生成，链接共享 framework）
```
