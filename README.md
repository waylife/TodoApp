# TodoApp · Kotlin Multiplatform 跨平台待办

[![CI](https://github.com/waylife/TodoApp/actions/workflows/ci.yml/badge.svg)](https://github.com/waylife/TodoApp/actions/workflows/ci.yml)

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
- **任务描述与进度更新**：每条待办可写长文描述，并可随时追加**带时间戳的进度记录**；
  清单页显示进度数角标，编辑面板按时间线查看（新记录在前，可单条删除）
- **截止日期**：Material 3 日期选择器，逾期任务标红并置顶
- **计划视图**：跨清单汇总未完成待办，按 **今日 / 本周 / 两周内 / 一个月内** 切换查看，
  已逾期任务单独分组置顶，其余按天分组
- **搜索**：按标题、备注、描述与进度记录过滤
- **深色模式**：跟随系统
- **WebDAV 同步**：修改后自动同步（防抖 2 秒）+ 启动/回到前台同步 + 手动同步，支持自定义远程目录
- **数据导入导出**：导出为 JSON 备份文件、从备份文件导入（合并或覆盖），三端均走系统文件选择器
- **响应式布局**：宽屏用侧边导航栏，窄屏用底部导航栏（同一套 Compose 代码）

---

## 环境要求

| 依赖 | 版本 | 说明 |
| --- | --- | --- |
| JDK | **17 或更高** | 构建与全部 207 个测试已在 17.0.7 上验证；若 `java -version` 低于 17，需设置 `JAVA_HOME` |
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

### 一键运行（日常入口）

```bash
scripts/run_tests.sh                        # 全量跑（207 个用例，JVM 上）
scripts/run_tests.sh --filter SyncMerge     # 只跑名字匹配的测试类
scripts/run_tests.sh --filter "TodosViewModel 搜索"
scripts/run_tests.sh --rerun                # 忽略 up-to-date 缓存强制重跑
scripts/run_tests.sh --ios                  # 追加 iOS 模拟器测试（需 Xcode，较慢）
scripts/run_tests.sh --help
```

跑完自动汇总通过/失败数量并列出失败用例与原因，详细报告在
`composeApp/build/reports/tests/desktopTest/index.html`。其余无法识别的参数原样透传给
`gradlew`（如 `--info`）。等价的原始命令是 `./gradlew :composeApp:desktopTest`。

### TDD 工作流（红-绿-重构）

改动业务逻辑时按下面的循环走，`scripts/run_tests.sh` 就是循环里的「跑测试」：

1. **红**：先把用例写出来描述期望行为，跑一次并确认失败。编译失败也算红——
   它通常意味着测试暴露了缺失的依赖注入接缝（见下文两处实例）；
2. **绿**：用最直接的实现让用例通过；
3. **重构**：保持全绿的前提下整理实现，随时重跑。

本仓库为可测试性做的约定：

- **时间可注入**：仓库与同步引擎收 `clock: () -> Long`，计划页收 `todayProvider`，
  测试里固定时间，不依赖真实时钟；
- **平台能力用假实现**：文件选择器（`DocumentTransfer`）、WebDAV 服务器
  （`FakeWebDavServer`）、连接测试器（注入到 `SettingsViewModel`）都有测试替身；
- **ViewModel 用即时作用域**：测试里通过 `TestScope.eagerTestScope()`
  （`desktopTest` 的 `testing` 包）构造作用域，`stateIn`/`launch` 同步执行、变更后立即可断言。
  不要用 `backgroundScope` + `advanceUntilIdle()`——当前 coroutines-test 版本下，
  后台共享协程只在测试体挂起时被调度，`advanceUntilIdle` 驱动不了它们；
- **纯函数优先**：合并（`SyncMerge`）、分组（`PlanGrouper`）、备份语义（`TodoTransfer`）
  都是无副作用的 object，单测不需要任何夹具。

### 用例分布（207 个）

| 测试类 | 用例数 | 覆盖内容 |
| --- | --- | --- |
| `TodoRepositoryTest` | 27 | 仓库层：CRUD 与局部更新语义（空白标题回退、dueAtChanged 门控、描述未传时保留）、进度记录的增删与时间戳（空白内容不落库、推进条目 updatedAt）、落库后由新实例读回、变更回调逐次触发/同步写库时静默、replaceAll、clearAll、purgeDeleted 墓碑清理、变更计数（用户变更递增、引擎写库不递增） |
| `TodosViewModelTest` | 16 | 清单页：过滤与搜索（标题/备注/描述/进度记录、忽略大小写）、未完成排序与已完成分区、添加待办的目标清单选择（自动建「默认」清单、**选中清单被远端删除后回退到存活清单**）、清单删除与选中态、toggleDone |
| `TodoTransferTest` | 26 | 备份编解码与格式校验（空文件、非 JSON、非本应用文件、版本过高、未知字段、**v1 旧备份导入时新字段取默认值**）、统计、导入影响预估与预估-实际一致性、合并/覆盖语义、重复导入幂等 |
| `WebDavSyncIntegrationTest` | 19 | 端到端同步：首次上传、多级目录创建、目录已存在时重复同步、双向同步、并发冲突收敛、删除传播、ETag 冲突重试、压缩表示的 ETag 归一化、回到前台的节流、**合并窗口内的并发编辑不被清掉**、**远端快照版本过新时报错且不降级覆写**、**远端内容损坏时报错且不动本地数据**、**内容无变化时跳过上传与建目录** |
| `TransferViewModelTest` | 16 | 导入导出界面：导出文案与快照同构、取消/失败分支、忙碌保护、解析-预览-确认流程、合并/覆盖导入语义、未确认不写库、dismiss 后确认是无操作、导出/导入异常时复位 busy 并提示 |
| `DesktopDocumentTransferTest` | 12 | 桌面端**真实**读写路径（只把弹对话框换成固定返回值）：写盘/覆盖写/路径不可写、读回、往返后墓碑不丢、SAVE 与 LOAD 模式、建议文件名透传、超大文件在读取前被拦下且边界值放行 |
| `DataTransferIntegrationTest` | 10 | 导入导出端到端：真实内存库 + 真实同步引擎，只把文件选择器换成内存实现；覆盖导出内容、取消/失败分支、解析失败不动数据、关闭确认框后不写入、导入后主动同步到远端 |
| `SyncMergeTest` | 9 | 合并算法：新增、并发编辑、删除优先、删除后复活、**描述与进度随较新条目整体胜出**、参数顺序对称性（保证多端收敛） |
| `PlanGrouperTest` | 8 | 计划页分组：过滤无日期/已完成/已删除项、逾期不重复展示、范围边界、组内排序 |
| `WebDavConfigTest` | 8 | 连接配置判定：`http://`/`https://` 前缀校验（`httpfoo` 之类不算合法）、空白地址/目录 |
| `SyncEngineTest` | 7 | 同步触发与护栏：未配置分支（syncNow/scheduleSync/deleteRemoteData）、前台节流（20 秒内跳过、从未成功则放行）、防抖到期触发、**删除远端数据前先取消排队中的防抖同步** |
| `PlanRangeTest` | 7 | 今日/本周/两周/一个月范围计算，含跨周与跨年推算 |
| `DatesFormatTest` | 6 | 日期文案：今天/明天/昨天、星期、紧凑日期、时刻补零、同步时间组合 |
| `SettingsViewModelTest` | 8 | 设置页：保存配置归一化并立即同步、非法地址置 NotConfigured、连接测试（空地址本地拦截 + 注入测试器回显、**测试器抛异常时复位 testing**、**在途时忽略重复触发**）、lastSyncAt 展示与清空后归零、远端路径拼接 |
| `PlanViewModelTest` | 6 | 计划页：范围切换、逾期置顶且不重复出现在日期分组、已完成/已删除/无日期过滤、清单名映射（today 注入固定日期） |
| `SettingsStoreTest` | 6 | 设置存储：默认值、保存归一化、空目录回退默认、新实例从持久层读回、lastSyncAt |
| `WebDavClientTest` | 4 | 客户端上传前置条件：If-Match 匹配现有 ETag、GET 无 ETag 时退化为 `If-Match: *`、文件在 GET 与 PUT 之间被删时拒绝重建、409 重试保留 isNew 前置条件且 412 走冲突重试 |
| `DesktopDbMigrationTest` | 4 | 桌面端建库/迁移：全新库建表并写入 `PRAGMA user_version`、旧库（v1 结构 / 无版本号）重开数据保留且版本号补齐、**v1→v2 迁移后新增列可用**、更高版本号的库重开时版本号只升不降 |
| `EditSessionTest` | 7 | 编辑会话：打开/保存（更新并关闭、描述落库）、进度记录的追加（不关闭面板）与单条删除、条目被外部删除后面板显示为空 |
| `RealWebDavSmokeTest` | 1 | 可选：对着**真实 WebDAV 服务器**跑完整同步闭环，未提供凭据时自动跳过 |

集成测试会在进程内启动一个**迷你 WebDAV 服务**（支持 MKCOL/GET/PUT 与 ETag），用两台独立
「设备」（各自内存数据库）跑完整同步闭环，**无需任何外部服务器或账号**。该迷你服务刻意复刻
了真实服务器（实测 Apache / Teracloud）的三处坑：集合已存在时 MKCOL 返回 301、GET 返回弱
ETag `W/"..."`、压缩响应上的 ETag 带 `-gzip` 后缀，保证这些适配不会被改回去。

### 对着真实服务器验收同步

`RealWebDavSmokeTest` 走应用真实的 `WebDavClient` + `SyncEngine`，依次验证「测试连接 → 首次
上传 → 另一设备拉取 → 双向增量 → 删除传播 → 陈旧 ETag 触发 412 → 弱 ETag 归一化后仍可上传」。
凭据按顺序从以下位置读取，读不到就整组跳过（因此不会拖垮 CI）：

1. 环境变量 `TODOAPP_DAV_URL` / `TODOAPP_DAV_USER` / `TODOAPP_DAV_PASS` / `TODOAPP_DAV_DIR`
2. 属性文件 `TODOAPP_DAV_TEST_PROPS` 指向的路径，默认 `/tmp/todoapp-dav-test.properties`

```bash
cat > /tmp/todoapp-dav-test.properties <<'EOF'
url=https://dav.example.com/dav/
user=your-user
pass=your-password
dir=ToDoApp
EOF
chmod 600 /tmp/todoapp-dav-test.properties

./gradlew :composeApp:desktopTest --tests 'com.todoapp.sync.RealWebDavSmokeTest' -i
```

用例只在 `{dir}/smoke/e2e/` 子目录下读写，不会碰真实同步目录；凭据文件在仓库之外，不会入库。

### 界面自动化验证

除了单元测试，还可以把界面离屏渲染成 PNG 做可视化验证（不依赖窗口系统，不会被其它窗口遮挡）：

```bash
# 清单页（--seed 注入跨今日/本周/两周/一个月的演示数据）
./gradlew :composeApp:run --args="--render /tmp/home.png --seed"

# 编辑面板（--edit-dialog 按标题前缀打开某条待办；--render-height 加高画布看完整面板）
./gradlew :composeApp:run --args="--render /tmp/edit.png --seed --edit-dialog 读完 --render-height 1400"

# 计划页，可指定时间范围
./gradlew :composeApp:run --args="--render /tmp/plan.png --tab plan --range month"

# 设置页
./gradlew :composeApp:run --args="--render /tmp/settings.png --tab settings"

# 设置页 + 导入确认框（--import-dialog 直接喂一份备份文件，省去模拟点击原生文件对话框）
./gradlew :composeApp:run --args="--render /tmp/import.png --tab settings --import-dialog /tmp/backup.json"
```

`--range` 取值为 `today`（默认）/ `week` / `2weeks` / `month`。另有 `--selftest <路径>`
会在真实窗口启动后截屏，适合人工确认窗口行为；`--delete-dialog` 用于展开「删除远端数据」确认框。

---

## CI（GitHub Actions）

仓库自带 CI（`.github/workflows/ci.yml`），**每天定时跑一次**（UTC 18:00 / 北京时间 02:00），
也可在 Actions 页手动触发（手动触发无条件构建）。定时那次会先对比上次成功构建对应的 commit：
没有新提交、或只改了 `*.md`，就只跑一个几秒钟的检查 job，四个构建 job 全部跳过。
同一分支只保留最新一次运行，旧构建会被自动取消：

| Job | Runner | 内容 | 产物 |
| --- | --- | --- | --- |
| 检查是否有新改动 | ubuntu | 定时触发时对比上次成功构建的 commit，无代码改动则跳过后续 job | — |
| 测试（JVM） | ubuntu | `:composeApp:desktopTest`（commonTest + desktopTest 全量） | 失败时上传测试报告 |
| Android APK | ubuntu | `assembleDebug` + `assembleRelease`（release 为未签名 APK，仅验证可构建） | `android-apk` |
| 桌面安装包 | macOS / Windows / Linux 三平台矩阵 | `:composeApp:packageDistributionForCurrentOS` | DMG / MSI / DEB |
| iOS | macOS | `iosSimulatorArm64Test` + 编译真机目标 iosArm64 | 失败时上传测试报告 |

APK 与安装包产物保留 14 天，在运行详情页底部 Artifacts 处下载；签名仍走本地脚本
`scripts/build_release_apk.sh`（密钥库不入库，CI 不做签名）。

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
  "schemaVersion": 2,
  "rev": 42,
  "savedAt": 1758326400000,
  "lists": [{ "id": "...", "name": "工作", "sort": 1, "createdAt": 0, "updatedAt": 0, "deletedAt": null }],
  "items": [{ "id": "...", "listId": "...", "title": "写周报", "note": "", "description": "汇总各组进度与风险",
              "progressUpdates": [{ "id": "...", "text": "完成调研", "createdAt": 1758300000000 }],
              "done": false, "dueAt": 1758326400000, "createdAt": 0, "updatedAt": 0, "deletedAt": null }]
}
```

- 所有实体使用 **UUID** 主键，因此多设备离线创建不会撞号
- `dueAt` 存当地时区当天零点的毫秒时间戳（只精确到日期）
- `deletedAt` 非空表示**墓碑（软删除）**，用于把删除动作传播到其它设备
- `description`（任务描述）与 `progressUpdates`（进度更新时间线）是 v2 新增字段：
  它们作为条目的一部分**整体**参与 LWW 合并（不做字段级合并），最后写过该条目的设备胜出。
  旧版本应用读到 v2 快照会按「版本过高」拒绝同步，而不是把不认识的字段静默丢掉

### 合并规则（最后写入者胜）

每次同步：下载远端快照 → 与本地逐条合并 → 写回本地 → 带乐观锁上传合并结果。

| 情况 | 结果 |
| --- | --- |
| 仅一侧存在该条目 | 采用该侧（含墓碑） |
| 两侧都有 | `updatedAt` 更新者胜 |
| 时间戳完全相同 | 墓碑（删除）者胜；再相同则按 JSON 规范串字典序决胜 |

算法对两侧参数完全对称，因此设备 A 与设备 B 各自计算的结果**必然一致**（有专门测试覆盖）。
上传时带 `If-Match: <etag>`；若返回 412（远端已被其它设备改动），或合并期间出现了用户编辑，
则重新下载合并再试，最多 5 轮。下载后先校验远端快照：内容损坏或 `schemaVersion` 高于本应用时
报错终止，绝不把解码失败当作空数据、也不把新版快照降级覆写。

两处省流优化：合并结果与远端**内容一致**时跳过上传（首次同步除外，它要真正建出 todoapp.json
作为同步就绪的信号）；远程目录只在配置变化后重新 MKCOL。因此一次无变化的例行同步只有 1 个
GET 请求，本地也不做任何写库。

### 触发时机与生命周期

- **自动**：应用启动 / **回到前台**时、本地修改后防抖 2 秒
- **手动**：顶栏同步状态指示点按、设置页「立即同步」
- 回到前台触发的同步有两层护栏：已有同步在跑时跳过；距上次**成功**同步不足 20 秒时跳过。
  Android 接系统 `ON_START`，冷启动与「从后台切回」走同一条路径，不会重复发请求。
  （只靠首次组合触发是不够的——Android 上应用极少真正「启动」，多是从后台切回，
  而切回不会重建 Activity，组合不会重跑。）
- 墓碑在 90 天后清理；若某设备**超过 90 天未同步**，其本地残留数据可能导致已删条目复活

---

## 数据导入导出

设置页「数据备份」区块：**导出为文件…** / **从文件导入…**。三端都调系统原生文件选择器，
不需要任何存储权限：

| 平台 | 实现 | 行为 | 验证程度 |
| --- | --- | --- | --- |
| Android | SAF（`CreateDocument` / `OpenDocument`） | 用户选目录并命名，或从任意位置选文件 | **已在模拟器上跑通导出与导入全流程** |
| 桌面 | AWT `FileDialog`（macOS 上是原生 NSSavePanel / NSOpenPanel） | 同上 | 除对话框点击外的全部逻辑有测试覆盖（走真实文件系统） |
| iOS | `UIDocumentPickerViewController` | 导出到「文件」，导入时系统把文件拷贝进沙盒 | 仅编译与静态框架链接 |

Android 端的验收过程：在模拟器上真实点击「导出为文件…」→ 系统 SAF 选择器弹出且带入建议文件名
→ 保存到「下载」→ 回读文件内容确认是合法快照；再把一份**含墓碑**的备份推入设备导入
→ 确认框显示「包含 2 个清单、2 条待办、另含 1 条已删除记录」「预计：新增 3、更新 0、删除 1」
→ 点导入后清单页确实多出 1 个清单 2 条待办，且备份里那条墓碑把本机对应待办删掉了。
**预估与实际完全一致。**

### 备份格式

**备份文件就是 WebDAV 上的 `todoapp.json` 本身**，不做任何额外包装（缩进输出便于人工查看）。
因此：导出的文件可以直接改名成 `todoapp.json` 丢进同步目录，反过来也可以把服务器上的同步
文件下载回来当备份导入。

因为复用同一份 [RemoteSnapshot]，备份是**无损**的——保留 `id`、时间戳与删除墓碑。
导入后多端合并的结果与「走一次同步」完全等价，不会因为换机而产生重复条目。

### 导入语义

选好文件后会先弹出确认框，展示备份里有多少清单/待办、含多少条已删除记录，以及按「合并」
导入会**新增/更新/删除**多少条，再由用户选择：

| 方式 | 行为 |
| --- | --- |
| **合并**（默认） | 与本地逐条合并，同 id 按 `updatedAt` 取较新者（同 [SyncMerge]）。本地独有的数据保留 |
| **覆盖** | 本机数据被备份整体替换，本机独有的条目会消失；备份里的墓碑同样生效 |

几点需要留意：

- **备份里的墓碑会删掉本机对应条目**。这是「忠实还原备份」的必然结果，确认框里会明确提示。
  只想补充数据、不想删东西时，应确认备份里没有涉及本机条目的删除记录。
- **合并导入不会丢数据，但也可能「没变化」**。如果备份比本机旧，LWW 会让本机版本胜出，
  此时确认框里的预估是「新增 0、更新 0、删除 0」。
- **导入后会自动触发一次同步**（导入写库时绕过了仓库的变更回调，所以必须显式推一次），
  否则导入的数据只留在本机。若未配置 WebDAV 则只是本地生效。
- 覆盖导入同样会与其它设备按 LWW 合并，**其它设备独有的数据不会因此丢失**——想彻底清空请用
  「删除远端数据」并勾选「同时清空本机数据」。
- **超过 32 MB 的文件会被直接拒绝**。三端的选择器都允许用户选到任意文件（Android 侧为了让
  那些把 `.json` 报成 `octet-stream` 的文件管理器也能选中，MIME 里带了通配），
  误选大文件时若直接读进内存会让移动端当场 OOM。因此在**读取之前**先按文件大小拦下，
  提示「不像待办备份，请确认是否选错文件」。个人待办即使上万条也只有几 MB，正常不会碰到。

---

## 项目结构

```
composeApp/                    共享模块（UI + 数据 + 同步）
├── src/commonMain/kotlin/com/todoapp/
│   ├── model/                 领域模型（TodoList / TodoItem / RemoteSnapshot）
│   ├── data/                  数据仓库与设置存储（SQLDelight 生成的 AppDatabase 在 build/generated/sqldelight）
│   ├── sync/                  WebDAV 客户端、LWW 合并算法、同步引擎
│   ├── ui/                    主题、通用组件、清单页、计划页、设置页、编辑面板、导航
│   ├── transfer/              备份编解码与导入语义（TodoTransfer）、文件选择器接口（DocumentTransfer）
│   ├── util/                  日期与范围工具、计划页分组、UUID
│   ├── viewmodel/             TodosViewModel / PlanViewModel / SettingsViewModel / TransferViewModel / EditSession
│   └── di/                    Koin 装配（platformModule 为 expect/actual）
├── src/commonMain/sqldelight/ SQLDelight 表结构与查询（Todo.sq）
├── src/androidMain/           Android 平台实现（AndroidSqliteDriver / SharedPreferences / OkHttp / SAF）
├── src/desktopMain/           桌面入口与实现（JDBC SQLite / Preferences / CIO / AWT FileDialog）
├── src/iosMain/               iOS 入口与实现（NativeSqliteDriver / NSUserDefaults / Darwin / UIDocumentPicker）
├── src/commonTest/            合并算法、计划分组、时间范围与日期文案、备份编解码与导入语义、WebDAV 配置判定单测
└── src/desktopTest/           仓库与 ViewModel 单测（内存库 + 可控时钟）+ 迷你 WebDAV 服务端到端集成测试 + 真实服务器冒烟测试

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
- **ETag 需随文件内容变化**：客户端送 `If-Match` 前会把弱校验值（`W/"..."`）与 Apache
  mod_deflate 追加的 `-gzip` 后缀（如 `"abc-gzip"`）一并归一化；GET 侧还显式声明
  `Accept-Encoding: identity`，避免拿到「压缩表示」的 ETag——该值用于 PUT 时必然 412，
  表现为同步稳定失败并提示「远程数据已被其它设备修改」。但若服务器或反向代理给出与内容无关的
  ETag，冲突重试仍会失败
- **凭据保存在各平台普通私有存储**（SharedPreferences / NSUserDefaults / Java Preferences），
  未接入 iOS Keychain 与 Android Keystore；安全性依赖设备本身的隔离
- 未见有经代理访问的成功验证；目录创建已适配服务器返回 301 重定向的情形，但代理改写 ETag
  仍可能导致同步冲突
- 长期（>90 天）离线设备上的旧数据可能导致已删除条目复活
- **导入导出只有 JSON 一种格式**，没有 CSV / Markdown 等人类可读导出；备份文件里的
  `dueAt`、`updatedAt` 都是毫秒时间戳，人工编辑时需要注意
- **导入不做跨设备 id 去重**：备份与本地是同一条待办但 id 不同（例如从别处手工拼出来的备份）
  会被当成两条。备份只能由本应用导出
- **iOS 端只做了编译与静态框架链接验证**，从未在模拟器上运行；桌面端的「点击保存/打开」也
  无法脚本驱动（macOS 需要辅助功能权限）。Android 端已在模拟器上跑通完整流程，
  详见上文「数据导入导出」一节
- **SAF 返回的 Uri 形态取决于具体 provider**，不要用 `uri.lastPathSegment` 当文件名
  （实测 Downloads provider 给的是 `document/3` 这种数字 id，界面会显示成「位置：3」）。
  正确做法是查 `ContentResolver` 的 `OpenableColumns.DISPLAY_NAME`
- **快照读取不是原子的**：`TodoRepository.currentSnapshot()` 分别读取清单与待办两个
  StateFlow，而同步写回时这两次赋值之间存在一个极短的窗口。若导出恰好落在窗口内，
  备份可能出现「待办引用了不在备份里的清单」。窗口在微秒级、实际触发概率极低，
  且这是同步路径本身也存在的性质（用的是同一个方法）。彻底修法是让仓库把两份数据放进
  同一个 StateFlow 里原子读写，属于独立改动，本次未做
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
