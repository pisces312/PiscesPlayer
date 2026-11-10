# 关于页展示 commit / commit 时间 / 构建时间 方案与实施

> 状态：**已实施并验证（2026-11-09）** —— 验证结果见 §9；真机目视待补。
> 涉及代码：`app/build.gradle.kts`、`presentation/screens/AboutScreen.kt`、
> `res/values/strings_screens.xml`、`res/values-zh-rCN/strings_screens.xml`；顺带
> `presentation/components/GradientTopBar.kt`、
> `presentation/screens/HomeScreen.kt`（移除首页右上角更新日志入口）。
> 约束前提：`AGENTS.md`、`gradle.properties` 的 `org.gradle.configuration-cache=true`、
> release 开启 R8 / `isShrinkResources = true`。

## 1. 目标

每次构建产出的 APK 能在「关于」页自证对应哪份代码：commit、commit 时间、构建时间、
工作区是否干净。用于自用包排查「装的是哪一版」以及反馈问题时定位源码快照。

## 2. 现状（动手前已核实）

| 项 | 事实 |
|---|---|
| 关于页生效实现 | `presentation/screens/AboutScreen.kt`，由 `AppNavigation.kt:661` 的 `composable(Screen.About.route)` 绑定；无同名死实现 |
| 现有版本号 | `AboutScreen.kt:170` 取 `PackageManager.getPackageInfo().versionName`，与 `BuildConfig` 无关 |
| 版本 chip | `AboutHeroCard`（`:378`），`pointerInput(detectTapGestures)` 的长按进彩蛋；`about_version_format` 有 12 个语言目录 |
| 构建脚本 | `app/build.gradle.kts` 已开 `buildConfig = true`；`defaultConfig` 无任何 `buildConfigField` / `resValue` |
| 现成范式 | `copyChangelog`（`:206`）经 `variant.sources.assets.addGeneratedSourceDirectory`（`:356`）注入，运行时 `ChangelogBottomSheet.kt:76` 用 `assets.open("CHANGELOG.md")` 读取 —— **同构链路已在本仓库跑通** |
| git 实测 | `git describe --tags --always --dirty` → `v0.4.3-pisces.1-114-g7e1cc720`；工作区干净，分支 `main` |

发版链路是 `main` → 版本号提交 → `tag v<版本名>` → `assembleRelease`，因此构建时 HEAD
就是 tag 指向的 commit，注入的 commit 天然等于发版 commit。

## 3. 两个决策维度

讨论分两轮：先比「信息注到哪」（载体），再比「注哪些信息」。

### 3.1 载体

| | BuildConfig / resValue（配置期） | **生成 assets（Task 执行期）** | 仅发版时 `-P` 注入 |
|---|---|---|---|
| 配置缓存 | **有陷阱**：配置期取 `Instant.now()`，第二次命中缓存沿用旧时间 | 兼容 | 兼容 |
| 每次构建代价 | Kotlin 编译 + R8 全量重跑 | 仅 `mergeAssets` + 打包 + 签名 | 平时为零，发版一次全量 |
| 运行时读取 | `BuildConfig.X` / `R.string` | `context.assets.open(...)` | 同左 |

选 assets。`resValue` 另受 `android.defaults.buildfeatures.resvalues=false` 限制，需显式开
`buildFeatures.resValues`；且 res 变化同样会触发资源编译与 Kotlin 重编。

### 3.2 携带哪些信息

| | A. 只有 commit + commit 时间 | B. commit + 构建时间（原方案） | **C. A+B 并加 dirty 标记（选定）** | D. commit + commit 时间 + 源码指纹 |
|---|---|---|---|---|
| 发版可溯源 | ✅ | ✅ | ✅ | ✅ |
| 同 commit 多次构建可区分 | ❌ | ✅ | ✅ | ✅ |
| 增量构建 | ✅ 完全 UP-TO-DATE | ⚠️ 每次重打包 | ⚠️ 每次重打包 | ✅ 源码不变即复用 |
| 额外成本 | — | — | 多一次 `git log -1 --format=%ct` + 一行 UI | 中（需对 `git diff` / src 算快照） |

## 4. 决策：选 C（assets 载体 + A/B 信息并集）

两点关键判断：

1. **「要求所有修改都提交才准」是伪约束。** 不需要强制提交，`git status --porcelain`
   直接给出 dirty 标记即可；真正要求干净的只有发版那一次，且应做成可选校验开关，
   而不是加在每次构建上。
2. **commit 时间在脏工作区下具有误导性，构建时间是必要的安全网。** 假设基线是 11-01
   的 `7e1cc720`，11-09 带着未提交改动构建：只记 commit 时间会显示成 8 天前的包，
   实际里头是今天的代码；带上构建时间 + `-dirty` 才能一眼看出「基线旧、含未提交改动」。
   构建时间的真正作用不是记录时刻，而是**区分同一 commit 下的多次构建** —— 这正是
   调试时最常见的情形。

assets 载体的代价在上一轮已隔离干净：assets 变化只触发 `mergeAssets` + 打包 + 签名重跑，
Kotlin 编译与 R8 保持 UP-TO-DATE（assets 不参与这两者）。为省这部分打包开销而牺牲
信息完备性不划算；C 相对 B 只多读一个 git 字段，成本可忽略。

呈现上分主次：**commit + commit 时间为主行（稳定、可溯源），构建时间 + dirty 为副行
（区分同 commit 的多次构建）**。

## 5. 数据设计

生成 `build/generated/assets/build-info/build-info.properties`，随 `mergeAssets` 进入
APK 的 `assets/build-info.properties`：

| 字段 | 来源 | 说明 |
|---|---|---|
| `commit` | `git rev-parse --short=8 HEAD` | 短 hash，`unknown` 降级 |
| `commitFull` | `git rev-parse HEAD` | 复制到剪贴板时给出 |
| `commitTimeEpochSec` | `git log -1 --format=%ct` | **committer date**，非 author date；amend / rebase / cherry-pick 后它才反映代码最终成型时刻 |
| `branch` | `git rev-parse --abbrev-ref HEAD` | |
| `describe` | `git describe --tags --always --dirty` | 发版时即 tag 名 |
| `dirty` / `changedFiles` | `git status --porcelain` | 非空行数即改动文件数 |
| `buildTimeEpochMs` / `buildTimeIso` | `Instant.now()` | epoch 用于 UI 本地化格式化，ISO 便于 `unzip -p` 直接看 |
| `versionName` | `APP_VERSION_NAME` | 与 `defaultConfig` 同源 |

时间一律存 epoch，UI 用系统默认时区格式化，避免 UTC / 本地时区混淆。

## 6. 实现要点（三个必须遵守的坑）

1. **时间必须在 Task 执行期取，不能在配置阶段。** 配置阶段读 `Instant.now()` 会被
   序列化进配置缓存，第二次构建命中缓存后时间不刷新。
2. **`generateBuildInfo` 必须 `outputs.upToDateWhen { false }`。** 否则 Gradle 判定
   UP-TO-DATE，构建时间不刷新。
3. **`git` 可执行文件默认 `git`，可用 `-Ppixelplayer.gitExecutable=<path>` 覆盖**；
   执行失败一律写 `unknown`，不阻断构建（源码 tarball / 无 `.git` 环境仍可用构建时间）。
   本机 WorkBuddy 的 PowerShell 会话 git 不在 PATH，可在 `~/.gradle/gradle.properties`
   配一次。Task 内用 `@get:Inject ExecOperations` 执行外部进程，配置缓存兼容。

另外：`addGeneratedSourceDirectory` 会挂第二个 generator（第一个是 `copyChangelog`）。
实测 AGP 9.4 支持两个 generator（构建通过），无需启用「合并到同一输出目录」的回退方案。

## 7. UI 呈现

`AboutHeroCard` 在版本 chip **下方**（不能放进 chip 的 `pointerInput` 区域，避免与
长按彩蛋抢手势）新增两行：

```
Version v0.4.3-pisces.1-debug        ← 现有 chip，不动
7e1cc720 · 2026-11-01 14:32          ← 主行：commit + commit 时间（dirty 时 hash 后加 -dirty）
Built 2026-11-09 09:29 · 3 changed   ← 副行：构建时间（dirty 时附改动文件数）
```

整块点击复制完整字段（`commitFull` / `describe` / `branch` / `dirty` / `buildTimeIso` /
`versionName`），用平台 `ClipboardManager` + `ClipData`，不依赖 Compose 版本相关的
clipboard API。文件缺失或解析失败时整块不显示，不影响现有页面。

为可读性新增 2 个 format string 到 `values/strings_screens.xml` 与
`values-zh-rCN/strings_screens.xml`，其余语言目录按 Android 资源回退机制显示英文，
不逐个补 12 份翻译。

## 8. 顺带：移除首页右上角更新日志入口

生效路径已核实：`HomeGradientTopBar`（`GradientTopBar.kt:167-178`）的报纸图标 →
`onMoreOptionsClick` → `HomeScreen.kt:318` 置 `showChangelogBottomSheet` → `:542-549`
弹出 `ChangelogBottomSheet`。左上角「正式版 / 调试版」按钮走 `onBetaClick` →
`BetaInfoBottomSheet`，用户判定两者入口重复。

按外科式改动只删入口：参数、图标、`state`、`ModalBottomSheet` 块、无用 import。
**保留** `ChangelogBottomSheet` / `ChangelogParser` 与 `CHANGELOG.md` 注入任务 ——
它们从此没有 UI 入口，属于 half-landed（功能存在但没接线），不是「功能已删除」。
后续可选：从关于页接线，或彻底清理组件与资源。

## 9. 验证结果（2026-11-09）

| # | 判据 | 结果 |
|---|---|---|
| 1 | `:app:assembleRelease` 编译通过 | ✅ `BUILD SUCCESSFUL in 2m 46s`（首次，含 Kotlin 编译与 R8 全量） |
| 2 | 二次构建配置缓存复用、编译与 R8 不重跑 | ✅ `Reusing configuration cache` / `Configuration cache entry reused`；`compileReleaseKotlin`、`minifyReleaseWithR8` 均 UP-TO-DATE；仅 `generateBuildInfo` → `mergeReleaseAssets` → `compressReleaseAssets` → `packageRelease` → `assembleRelease` 执行，`BUILD SUCCESSFUL in 2s` |
| 3 | APK 内构建信息正确且时间递增 | ✅ `unzip -p <apk> assets/build-info.properties`：`commit=7e1cc720`、committer date `2026-11-09 08:57:44 +0800`、`dirty=true / changedFiles=7`（正是本次 7 个改动文件）；两次 `buildTimeEpochMs` 为 `1794188598824` → `1794188779931` |
| 4 | 签名不受影响 | ✅ `apksigner verify --print-certs -v` → `Verifies`、`v2 scheme: true`、`CN=pisces312` |
| 5 | 关于页目视 / `-dirty` 显示 | ⏳ 真机目视按仓库约定由用户做；`-dirty` 路径已在生成的 properties 中触发 |
| 6 | 首页右上角图标消失、左上角入口保留 | ⏳ 待真机目视（代码路径已删净，全仓 grep 无残留引用） |

产物：`app/build/outputs/apk/release/pixelplayeross-arm64-v8a-0.4.3-pisces.1-release.apk`。

## 10. 明确未实施的可选项

| 选项 | 说明 |
|---|---|
| `-Ppixelplayer.requireCleanTree=true` | 发版前脏工作区直接 `GradleException`，把「必须干净」从全局约束收敛到发版动作 |
| `-Ppixelplayer.buildInfo.timestamp=false` | 省略构建时间 → 文件内容稳定 → 干净工作区下整个 `assembleRelease` 可 UP-TO-DATE，用于复现构建 |
| 源码内容指纹（讨论中的方案 D） | 用 `git diff HEAD` 短哈希代替构建时间，缓存友好且能区分 dirty 构建，但看不到「什么时候打的」 |
| 日志头带 commit | `AppLogCollector.kt:261` / `DebugPerformanceReportCollector` 导出时可附 commit |
| APK 文件名带 hash | **不改**：发版链路依赖 `pixelplayeross-<abi>-<版本>-<buildtype>.apk` 固定格式 |
