# TodoApp · Kotlin Multiplatform 跨平台待办

一套 Kotlin 代码同时构建 **Android / iOS / 桌面端（macOS·Windows·Linux）** 的待办应用，
界面与业务逻辑全部共享（Compose Multiplatform），数据通过 **WebDAV 协议** 在设备间同步，
无需自建服务端。

| | |
| --- | --- |
| 共享代码 | Kotlin 2.4.20 + Compose Multiplatform 1.12.0（UI 也共享） |
| 本地存储 | SQLDelight 2.4.0（Android/iOS 走 SQLite，桌面走 JDBC SQLite） |
| 网络与同步 | Ktor Client 3.6.0 + WebDAV（GET/PUT/MKCOL + Basic 认证） |
| 依赖注入 | Koin 4.2.2 |
| 构建 | Gradle 9.6 + AGP 9.4.1（JDK 17+） |

---

## 功能

- **清单分组**：多清单管理，长按清单标签可重命名或删除（删除会级联删掉其下待办并同步出去）
- **待办管理**：添加、编辑标题与备注、完成勾选、删除
- **截止日期**：Material 3 日期选择器，逾期任务标红并置顶
- **计划视图**：跨清单汇总未完成待办，按 **今日 / 本周 / 两周内 / 一个月内** 切换查看，
  已逾期任务单独分组置顶，其余按天分组
- **搜索**：按标题与备注过滤
- **深色模式**：跟随系统
- **WebDAV 同步**：修改后自动同步（防抖 2 秒）+ 手动同步 + 启动同步，支持自定义远程目录
- **响应式布局**：宽屏用侧边导航栏，窄屏用底部导航栏（同一套 Compose 代码）

---

## 环境要求

| 依赖 | 版本 | 说明 |
| --- | --- | --- |
| JDK | **17 或更高** | 构建与全部 29 个测试已在 17.0.7 与 21.0.8 上验证；若 `java -version` 低于 17，需设置 `JAVA_HOME` |
| Android SDK | platform **android-37**，minSdk 24 | 另需 `local.properties` 指向 SDK（见下） |
| Xcode | 仅 iOS 需要，本仓库在 26.2 上验证 | 部署目标 iOS 15.0；需安装 `xcodegen`：`brew install xcodegen` |
| 其它 | — | Gradle 无需预装，仓库自带 wrapper（9.6） |

首次构建会下载 Kotlin/Native 与 Compose 依赖，耗时较长（视网络 10 分钟以上）；iOS 首次编译
Kotlin/Native 代码同样较慢。

### Android SDK 配置

构建 Android 前需在仓库根目录创建 `local.properties`（该文件不入库，因含本机路径）：

```properties
sdk.dir=/Users/<你的用户名>/Library/Android/sdk
```

若缺少 `android-37` 平台，AGP 会在构建时自动下载安装（需已接受 SDK 许可）。

---

## 构建与运行

### 桌面端（最快的验证方式）

```bash
./gradlew :composeApp:run                 # 直接运行，窗口 1200×800
./gradlew :composeApp:packageDmg          # 产出 macOS 安装包
./gradlew :composeApp:packageMsi          # Windows 安装包（需在 Windows 上执行）
./gradlew :composeApp:packageDeb          # Linux 安装包（需在 Linux 上执行）
```

安装包产物：`composeApp/build/compose/binaries/main/dmg/TodoApp-1.0.0.dmg`

桌面端数据位置：`~/.todoapp/todoapp.db`（设置项存于 Java Preferences 的 `/com/todoapp` 节点）。
删除该目录即可恢复出厂状态。

### Android

```bash
./gradlew :androidApp:assembleDebug       # 调试包
./gradlew :androidApp:assembleRelease     # 发布包（未签名）
./gradlew :androidApp:installDebug        # 安装到已连接的设备/模拟器
```

产物路径：

- 调试包 `androidApp/build/outputs/apk/debug/androidApp-debug.apk`
- 发布包 `androidApp/build/outputs/apk/release/androidApp-release-unsigned.apk`（需自行签名才能安装）

也可直接用 Android Studio 打开仓库根目录运行 `androidApp` 配置。

### iOS

```bash
cd iosApp && xcodegen generate            # 生成 iosApp.xcodeproj（工程文件不入库）
open iosApp.xcodeproj                     # 在 Xcode 中选择 iosApp scheme 运行
```

命令行构建模拟器包（注意必须指定具体的模拟器型号，`generic` 目标会因包含 x86_64 架构而失败；
用 `xcrun simctl list devices available` 查看本机可用型号）：

```bash
cd iosApp && xcodebuild -project iosApp.xcodeproj -scheme iosApp \
  -configuration Debug -destination 'platform=iOS Simulator,name=iPhone 17 Pro' build
```

Xcode 工程通过 `iosApp/project.yml` 声明：构建阶段自动调用
`./gradlew :composeApp:embedAndSignAppleFrameworkForXcode` 编译 Kotlin 框架，并以静态库方式链接，
同时链接 `-lsqlite3`（SQLDelight 原生驱动需要）。真机运行需在 Xcode 中配置你的开发者签名。

iOS 端数据存放在应用沙盒内的 `todoapp.db`，设置项存于 `NSUserDefaults`。

---

## 测试

```bash
./gradlew :composeApp:desktopTest         # 全部 29 个用例，跑在 JVM 目标上
```

| 测试类 | 用例数 | 覆盖内容 |
| --- | --- | --- |
| `SyncMergeTest` | 8 | 合并算法：新增、并发编辑、删除优先、删除后复活、参数顺序对称性（保证多端收敛） |
| `PlanGrouperTest` | 8 | 计划页分组：过滤无日期/已完成/已删除项、逾期不重复展示、范围边界、组内排序 |
| `PlanRangeTest` | 7 | 今日/本周/两周/一个月范围计算，含跨周与跨年推算 |
| `WebDavSyncIntegrationTest` | 6 | 端到端同步：首次上传、多级目录创建、双向同步、并发冲突收敛、删除传播、ETag 冲突重试 |

集成测试会在进程内启动一个**迷你 WebDAV 服务**（支持 MKCOL/GET/PUT 与 ETag），用两台独立
「设备」（各自内存数据库）跑完整同步闭环，**无需任何外部服务器或账号**。

### 界面自动化验证

除了单元测试，还可以把界面离屏渲染成 PNG 做可视化验证（不依赖窗口系统，不会被其它窗口遮挡）：

```bash
# 清单页（--seed 注入跨今日/本周/两周/一个月的演示数据）
./gradlew :composeApp:run --args="--render /tmp/home.png --seed"

# 计划页，可指定时间范围
./gradlew :composeApp:run --args="--render /tmp/plan.png --tab plan --range month"

# 设置页
./gradlew :composeApp:run --args="--render /tmp/settings.png --tab settings"
```

`--range` 取值为 `today`（默认）/ `week` / `2weeks` / `month`。另有 `--selftest <路径>`
会在真实窗口启动后截屏，适合人工确认窗口行为。

---

## WebDAV 配置

在应用内「设置」页填写以下四项，先点「测试连接」确认无误，再点「保存并同步」：

| 字段 | 说明 | 示例 |
| --- | --- | --- |
| 服务器地址 | WebDAV 根地址（建议 HTTPS） | `https://dav.jianguoyun.com/dav/` |
| 用户名 | 登录账号 | `you@example.com` |
| 密码 | 账号密码或**应用密码**（坚果云必须在网页端生成应用专用密码） | `abcd1234` |
| 远程目录 | 数据存放目录，**默认 `ToDoApp`**，可自定义任意路径 | `ToDoApp` 或 `backup/todo` |

远程目录支持多级路径，应用会通过 MKCOL **逐级自动创建**缺失的目录，无需提前建好。
数据保存为 `{远程目录}/todoapp.json` 单个文件，便于备份与迁移。

已验证的服务器类型：坚果云、Nextcloud、群晖（Synology）、Apache/nginx 的 `mod_dav`。
任何标准 WebDAV 实现均应可用。

---

## 同步设计

### 数据格式

远端只有一个 JSON 快照，包含全部清单与待办（**含已删除项**）：

```json
{
  "schemaVersion": 1,
  "rev": 42,
  "savedAt": 1758326400000,
  "lists": [{ "id": "...", "name": "工作", "sort": 1, "createdAt": 0, "updatedAt": 0, "deletedAt": null }],
  "items": [{ "id": "...", "listId": "...", "title": "写周报", "note": "", "done": false,
              "dueAt": 1758326400000, "createdAt": 0, "updatedAt": 0, "deletedAt": null }]
}
```

- 所有实体使用 **UUID** 主键，因此多设备离线创建不会撞号
- `dueAt` 存当地时区当天零点的毫秒时间戳（只精确到日期）
- `deletedAt` 非空表示**墓碑（软删除）**，用于把删除动作传播到其它设备

### 合并规则（最后写入者胜）

每次同步：下载远端快照 → 与本地逐条合并 → 写回本地 → 带乐观锁上传合并结果。

| 情况 | 结果 |
| --- | --- |
| 仅一侧存在该条目 | 采用该侧（含墓碑） |
| 两侧都有 | `updatedAt` 更新者胜 |
| 时间戳完全相同 | 墓碑（删除）者胜；再相同则按 JSON 规范串字典序决胜 |

算法对两侧参数完全对称，因此设备 A 与设备 B 各自计算的结果**必然一致**（有专门测试覆盖）。
上传时带 `If-Match: <etag>`；若返回 412（远端已被其它设备改动），则重新下载合并再试，最多 3 次。

### 触发时机与生命周期

- **自动**：应用启动时、本地修改后防抖 2 秒
- **手动**：顶栏同步状态指示点按、设置页「立即同步」
- 墓碑在 90 天后清理；若某设备**超过 90 天未同步**，其本地残留数据可能导致已删条目复活

---

## 项目结构

```
composeApp/                    共享模块（UI + 数据 + 同步）
├── src/commonMain/kotlin/com/todoapp/
│   ├── model/                 领域模型（TodoList / TodoItem / RemoteSnapshot）
│   ├── data/                  数据仓库与设置存储（SQLDelight 生成的 AppDatabase 在 build/generated/sqldelight）
│   ├── sync/                  WebDAV 客户端、LWW 合并算法、同步引擎
│   ├── ui/                    主题、通用组件、清单页、计划页、设置页、编辑面板、导航
│   ├── util/                  日期与范围工具、计划页分组、UUID
│   ├── viewmodel/             TodosViewModel / PlanViewModel / SettingsViewModel / EditSession
│   └── di/                    Koin 装配（platformModule 为 expect/actual）
├── src/commonMain/sqldelight/ SQLDelight 表结构与查询（Todo.sq）
├── src/androidMain/           Android 平台实现（AndroidSqliteDriver / SharedPreferences / OkHttp）
├── src/desktopMain/           桌面入口与实现（JDBC SQLite / Preferences / CIO）
├── src/iosMain/               iOS 入口与实现（NativeSqliteDriver / NSUserDefaults / Darwin）
├── src/commonTest/            合并算法、计划分组、时间范围单测
└── src/desktopTest/           迷你 WebDAV 服务 + 端到端同步集成测试

androidApp/                    Android 应用模块（薄壳）
iosApp/                        iOS 壳工程（project.yml 由 xcodegen 生成 .xcodeproj）
```

### 为什么 Android 单独有一个 `androidApp` 模块

自 AGP 9.0 起，`com.android.application` 插件不再允许与 Kotlin Multiplatform 插件应用在同一
模块上（官方推荐改用 `com.android.kotlin.multiplatform.library`）。因此共享模块 `composeApp`
以 KMP library 形式参与构建，Android 的应用壳（Application、Activity、Manifest、资源）单独放在
`androidApp` 中。iOS 与桌面端没有这个限制。

---

## 常见问题

**构建报 `Unsupported class file major version` 或 JDK 相关错误**
JDK 版本过低。设置 `JAVA_HOME` 指向 17 或更高版本，例如：

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
```

**Android 构建报找不到 SDK**
根目录缺少 `local.properties`，按上文创建即可。

**依赖下载失败 / 卡住**
检查 `~/.gradle/gradle.properties` 是否配置了代理（如 `systemProp.https.proxyHost`）。
若配置了本地代理（常见于 Clash 等工具的 `127.0.0.1:7890`）而代理未运行，Gradle 会连接失败。
可临时在项目根目录的 `gradle.properties` 中覆盖或用 `-Dhttps.proxyHost=` 关闭。

**iOS 报 `Unknown iOS simulator arch: 'x86_64'`**
`xcodebuild` 使用了 `-destination 'generic/platform=iOS Simulator'`，它会构建包含 x86_64 的通用包。
请指定具体模拟器型号（见上文 iOS 构建命令）。

**iOS 报 `_sqlite3_open` 等符号找不到**
链接参数缺少 `-lsqlite3`。本仓库的 `iosApp/project.yml` 已包含，改动工程配置后需重新
`xcodegen generate`。

**界面显示"未配置同步"**
属正常状态，去设置页填写 WebDAV 信息即可。

---

## 已知限制（MVP）

- **同步为整文件快照替换**，适合个人待办的数据量；条目数极大时（数万条以上）每次同步都会
  传输完整 JSON，届时可改为增量或分片
- **不支持自签名证书**的 WebDAV 服务器，请使用受信任证书的 HTTPS 地址
- **凭据保存在各平台普通私有存储**（SharedPreferences / NSUserDefaults / Java Preferences），
  未接入 iOS Keychain 与 Android Keystore；安全性依赖设备本身的隔离
- **不支持经代理访问** WebDAV 服务器
- 长期（>90 天）离线设备上的旧数据可能导致已删除条目复活
- 尚无本地通知提醒、标签、优先级、子任务、重复任务等功能

---

## 技术栈版本

| 组件 | 版本 |
| --- | --- |
| Kotlin | 2.4.20 |
| Compose Multiplatform | 1.12.0（material3 1.9.0） |
| Android Gradle Plugin | 9.4.1（compileSdk 37 / minSdk 24 / targetSdk 37） |
| Gradle | 9.6（wrapper 固定） |
| Ktor | 3.6.0 |
| SQLDelight | 2.4.0 |
| Koin | 4.2.2 |
| kotlinx | coroutines 1.11.0、serialization 1.11.0、datetime 0.8.0 |
| multiplatform-settings | 1.3.0 |

依赖版本统一定义在 `gradle/libs.versions.toml`。
