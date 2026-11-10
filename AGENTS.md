# AGENTS.md

PixelPlayerOSS — Android 音乐播放器（100% Kotlin，Jetpack Compose + Material 3）。
本仓库是 `PixelPlayerHQ/PixelPlayerOSS` 的 fork：`pisces312/PixelPlayerOSS`。Gradle 模块：`:app`、`:baselineprofile`。
包名 `com.pisces312.piscesplayer`，许可证 **GPL-3.0-or-later**。

## 仓库历史关系（重要，动手前必读）

| 仓库 | 许可 | 关系 |
|---|---|---|
| `PixelPlayerHQ/PixelPlayer` | **专有**（禁止再分发源码/二进制及衍生作品） | 上游闭源版，含 Telegram / QQ 音乐 / Gemini / GMS 等集成 |
| `PixelPlayerHQ/PixelPlayerOSS` | GPL-3.0-or-later | 官方开源版，**已剥离** Telegram、NetEase、QQ 音乐、Google Drive、Gemini、Cast、Wear OS、Play 计费、Firebase、Crashlytics、GMS；云端仅保留自建 Navidrome/Subsonic 与 Jellyfin |
| `pisces312/PixelPlayer`（`china-only` 分支，本地 `D:\3rd-party-projects\PixelPlayer`） | 专有代码的派生 | 我自己的 fork，包名 `com.theveloper.pixelplay`，在上面开发了一批新功能 |

- **三者 git 历史完全独立**：`git merge-base` 为空 → **不能 cherry-pick / merge**，跨仓库只能手工重新落地。
- **无 AI 子系统**：OSS 用本地 NLP（`data/playlist/nlp/PlaylistIntentEngine`）替代云端 AI 生成播放列表。
- **版权红线**：把 china-only 的功能搬进来时，**原创算法/解析层**（parser、matcher、分桶、日志收集等）可搬，提交注明 `Original work, GPL-3.0-or-later`；**glue 层**（ViewModel / DI / DAO / 设置项 / UI 接线 / strings）必须在 OSS 侧重写；**禁止整文件复制专有 PixelPlayer 的实现**。

## 节奏游戏文档

节奏游戏相关的设计、调研与实施文档统一存放在 `D:\3rd-party-projects\RhythmChartLab\docs`，本仓库 `docs/` 不再保存这类文档。后续新增节奏游戏相关设计文档也放入该目录，不要在 PixelPlayerOSS 内另建副本。

## 分支

- `main`：**不是唯一的开发分支，而是发版起点** —— release 一般从 `main` 打 tag 构建。
  日常开发可以直接提交到 `main`，也可以开特性分支，开不开按需决定（2026-09-11 起不再要求
  经 `pisces/port` 中转）。每功能一个 commit，英文 message。
- 特性分支上的改动**发版前要先并回 `main`**，否则从 `main` 打出的 tag 会缺内容。

## 技术栈与版本

- Gradle 9.6.1（wrapper 已改腾讯云镜像）、AGP 9.4.0、Kotlin 2.4.20、KSP 2.3.12、JDK 21（JBR）。
- compileSdk = targetSdk = 37，minSdk = 30；版本号在 `gradle.properties` 的 `APP_VERSION_NAME` / `APP_VERSION_CODE`
  （**以该文件为准，本文不抄写具体值** —— 每发一版就变，抄在这里必然过期）。
- Media3 1.11.0（`libs.versions.toml` 的 `media3Session` / `media3Transformer` **保持一致**，不要只升其中一个）、
  Room 2.8.5（**数据库 schema 版本以 `PixelPlayerDatabase.kt` 的 `version` 为准，本文不抄写**；
  任何实体变更都要把 `version` +1 并新增 migration）、Hilt、OkHttp/Retrofit、TagLib + JAudioTagger 元数据。
- 依赖仓库为官方 `google()` / `mavenCentral()`（首次构建较慢，无国内镜像）。
- **Compose 栈由 `material3` 锚定**：它当前钉在 `1.5.0-alpha27`。升它的 alpha 号会经传递依赖
  拖动整条 Compose 栈（`alpha28` 会把栈从 `1.12.1` stable 顶进 `1.13.0-alpha01`），
  而 `composeBom` 与显式声明的 `composeUi` / `foundation` / `animation` **取较高者**、
  必须同批改。动之前先读 `docs/dependency-upgrade-2026-09.md` §9 / §10。

## 目标设备与屏幕尺寸（UI 布局前提）

**只针对一类设备：主流直板手机竖屏。** 参照机 = 本机 AVD `pixel6`（411 × 914 dp）。
**明确不作为目标**（2026-09-16 定；此后不再为它们加兜底）：

- 短屏 —— 横屏、分屏 / 多窗口、小折叠外屏，即竖屏可用高度明显低于 ~640 dp 的情况；
- 极值字体缩放（系统字体 ≥ 1.3）；
- 平板 / 折叠屏展开态。

推论（照此判断，不必逐个重新论证）：

- 布局按参照机的可用高度设计即可，**不为"更小的屏幕还塞不塞得下"预留弹性**；
- 某处布局在小屏被裁 → **默认不算缺陷**：说一句即可，不要顺手改成
  `weight(1f, fill = false)` 之类的自适应；
- **既有兜底保留，不要回退**：`AiMixSheet.InputPhase` 的 `verticalScroll` 同时服务软键盘弹出
  （主流机型也需要），sheet 的 `skipPartiallyExpanded` 与各处 `heightIn(max = …)` 同理 ——
  它们同时改善主流机型的观感，不属于纯小屏适配。

已确认接受的代价：`AiMixSheet.ResultPhase` 是「固定行 + `heightIn(max = 320.dp)` 列表」，
短屏 / 大字号下底部「重新生成」理论上会被裁。**决定不修**；备选方案（滚动区改
`weight(1f, fill = false)`）见 `docs/ai-thinking-persistence-plan.md` §7.6，已否决。

## 常用命令（PowerShell，仓库根目录）

```powershell
.\gradlew.bat :app:assembleRelease              # 默认构建（可交付；无签名环境变量时产出未签名 APK）
.\gradlew.bat :app:assembleDebug                # 仅按需：release 复现不了 / 难定位时
.\gradlew.bat :app:testDebugUnitTest            # 单元测试（JUnit 5，JVM）
.\gradlew.bat :app:lintDebug                    # Lint（release 构建不做）
.\gradlew.bat :app:connectedDebugAndroidTest    # instrumented 测试（需连真机 / 模拟器）
```

- **debug / release 可共存**：`debug` 带 `applicationIdSuffix = ".debug"` + `versionNameSuffix = "-debug"`，应用名 `PixelPlayerOSS [D]`（`app/src/debug/res/values/strings.xml`），
  图标走 `app/src/debug/res` 的红底覆盖（只覆盖 `ic_launcher_background` 与 legacy 方/圆图标，foreground 与 `mipmap-anydpi-v26` XML 沿用 main）。
  改了 main 图标后重跑根目录 `gen_debug_icons.py`（Pillow）重新生成 debug 图标。
- **默认构建 `release`；`debug` 只在按需排查时构建**：R8 只跑在 `release` / `benchmark` 上，
  `debug` 是 `isMinifyEnabled = false` + `isShrinkResources = false`。
  理由：debug 的用途是「release 上复现不了、又难定位」的问题，此时未混淆的栈帧逐行对应源码，
  也不会有 R8 改名 / facade 合并造成的干扰；代价只有体积，而且只在自己点名要时才付。
  体积实测（arm64-v8a、0.4.1、同一份代码）：**release 37.5 MB / debug 无 R8 152.1 MB /
  debug 带 R8 69.2 MB**（差别几乎全在 dex：14.5 MB / 128.0 MB / 45.0 MB）。
  **不要再给 `debug` 打开 R8**：instrumented test 的 test APK 是独立程序，按**未混淆的原名**调用
  app APK 里的类与成员，而被 minify 的 app 恰好把这些改写或删掉（Kotlin facade、`RoomDatabase.close()`、
  app 自己不调用的 DAO 方法），逐个 keep 是无底洞 —— 详见下方「instrumented 测试」。
- **ABI 只构建 `arm64-v8a`**（`pixelplayer.enableAbiSplits` 默认 true）；关掉该 property 时文件名中的 abi 段为 `universal`。
- **模拟器可直接安装运行 `arm64-v8a` 产物（已实测确认 2026-09-14，2026-11-01 再次确认）**：
  本机 AVD `pixel6` 是 **x86_64** 系统镜像（`system-images\android-34\google_apis\x86_64`），
  但 API 30+ 的 Google x86_64 镜像自带 **ARM 二进制翻译**，`adb install` 默认构建的
  `pixelplayeross-arm64-v8a-*.apk` 直接就能跑。
  - **不要**为了"让模拟器装得上"去构建 universal —— `-Ppixelplayer.enableAbiSplits=false`
    会覆盖并清掉同目录的 arm64 产物；
  - **别把"翻译慢"误判成"不支持"**：首帧与冷启动明显慢于真机，`am start` 后请
    `sleep` 若干秒再截图；若 logcat 里出现 `arm64` 翻译层加载或首帧延迟，那是性能问题，
    不是安装失败。
  - **但界面改动的目视验收由用户自己真机做**（2026-11-01 定）：模拟器只用于"跑起来看一眼
    有没有崩"，不要花时间在 AVD 上抠布局 / 逐页截图；构建出 release APK 交给用户即可。
  - 真机/模拟器验证统一用 `.\gradlew.bat :app:assembleRelease`（debug 与 release 的 applicationId 不同，可共存）。
- **instrumented 测试**：`.\gradlew.bat :app:connectedDebugAndroidTest`，跑在 `debug` 变体上
  （`testBuildType` 保持默认；正因 debug 不 minify，这套测试才跑得起来）。
  只跑某几个类：追加 `-Pandroid.testInstrumentationRunnerArguments.class=全限定类名,逗号分隔`。
  唯一跑不了的：`SyncWorkerTest` 需要 MockK inline（JVMTI agent），ART 拒绝从 app cache 加载，
  属既有测试设计问题，与构建配置无关。
- **APK 命名**：`pixelplayeross-<abi>-<APP_VERSION_NAME>-<buildtype>.apk`（`androidComponents.onVariants` 设置 `outputFileName`），
  例 `pixelplayeross-arm64-v8a-0.3.0-debug.apk`。改命名规则改 `app/build.gradle.kts`。
- 签名**只读 `KEY_*` 环境变量**（或 `pixelplayer.disableReleaseSigning=true` 跳过）；
  仓库里没有、也不该有任何 keystore 文件。详见下方「签名与发布」。
- `GRADLE_USER_HOME=D:\dev\.gradle`；wrapper 的 Gradle 发行版走腾讯云镜像。

## 签名与发布（自用 fork）

**凭据只存在于环境变量**，严禁写入仓库内任何文件（含 `docs/`、release notes、memory）：

| 环境变量 | 用途 |
|---|---|
| `KEY_STORE` | keystore 文件名（备查） |
| `KEY_STORE_LOCATION` | keystore 绝对路径（Windows 风格） |
| `KEY_STORE_PASSWORD` | keystore 口令 |
| `KEY_ALIAS` | 密钥别名（`pisces312`） |
| `KEY_PASSWORD` | 密钥口令 |

`app/build.gradle.kts` 的签名块**只读上表的环境变量**（走 `providers.environmentVariable(...)`，
是声明过的配置缓存输入），**没有任何文件读取路径** —— `KEY_STORE_LOCATION` 未设置时
`hasReleaseSigningConfig` 为 false，产物是未签名 APK 而不是构建失败。因此本机发版
**根本不涉及落盘口令**，环境变量在就能直接构建：

```bash
./gradlew :app:assembleRelease
```

配置缓存是开启的（`gradle.properties` 的 `org.gradle.configuration-cache=true`）。环境变量路径
已正确声明，**不需要 `--no-configuration-cache`** —— 2026-09-18 之前之所以要加这个 flag，
是为了绕开 `keystore.properties`（`Properties().load(File)`）这个未声明输入，该读取路径已删除。

**`keystore.properties` 和 `.jks` 都不该出现在仓库里**：`.gitignore` 已覆盖，实际也不存在。
看到它们说明环境变量没生效，别当成"缺签名文件"去补。

### CI：本 fork 保持禁用（2026-09-18 定）

`.github/workflows/` 的 `nightly-apk.yml` / `pr-build.yml` 沿用上游，**在本 fork 一直禁用、从未跑过**
（fork 默认不启用 schedule，`gh api repos/pisces312/PixelPlayerOSS/actions/runs` → `total_count=0`）。
**继续保持禁用，不要为它们做适配**；它们另有三处与本 fork 不符，真要启用得先补齐：

1. `runs-on: blacksmith-4vcpu-ubuntu-2404` —— 本 fork 没有这个 runner（`pr-build.yml` 即因此从未成功）；
2. 期望产物 `app-arm64-v8a-release.apk` —— 本 fork 的 `outputFileName` 是 `pixelplayeross-<abi>-<版本>-<buildtype>.apk`；
3. 签名凭据落盘成 `keystore.properties` —— 该读取路径已删除，需改成写 `$GITHUB_ENV` 的 `KEY_*`。

因此**签名机制的真相只有 `app/build.gradle.kts` + `KEY_*` 环境变量一处**：别照 workflow 反推本机怎么签，
也别拿 CI 的运行状态判断签名是否生效（本 fork 只有本地发版这一条路）。

两个坑：

- **构建成功 ≠ 已签名**，唯一可信判据是
  `apksigner verify --print-certs -v <apk>` → `Verifies` + `v2 scheme: true` + `CN=pisces312`
  （只有 v2、没有 v1 是正常的）；`aapt2 dump badging` 核对 `versionName` / `versionCode` / `native-code`。
- 本机 build-tools：`D:/dev/android_sdk/build-tools/37.0.0/`。

发版链路：`main` → 版本号提交 → `tag v<版本名>` → `assembleRelease` → `gh release create`。
版本号带 `-pisces.N` 后缀，版本码沿用 `主版本 * 100000 + 序号`。

> 上游的 `.github/workflows/alpha-release.yml`（push `main` 自动造 `v<版本>-alpha.N` 预发布）
> **已在本 fork 删除**：它跑在 `blacksmith-4vcpu-ubuntu-2404` 上，本 fork 没有对应 runner，
> 而 `main` 现在是日常开发分支，留着会让每次代码提交都产生一个挂起/失败的 run。
> 因此本地发布**不再需要**在 message 里写 `[skip ci]`。
> 同一 runner 的 `pr-build.yml` 只在 PR 时触发（本 fork 不开 PR），`gitlab-mirror.yml` 有
> `github.repository == 'PixelPlayerHQ/PixelPlayerOSS'` 守卫会自动跳过，二者均无影响。

## 新增一个设置开关（必读，缺一不可）

OSS 有**设置搜索**，开关不注册就搜不到：

1. `data/preferences/UserPreferencesRepository.kt`：`PreferencesKeys` 加 key → `xxxFlow` → `setXxx()`。
2. `presentation/viewmodel/SettingsViewModel.kt`：`SettingsUiState` 字段 → `SettingsUiUpdate.Group2` 字段 → `init` 里 combine 的 flow 列表**末尾**追加 → `values[N]`（索引按位置对应，只能追加不能插队）→ `state.copy()` → `setXxx()`。
3. `presentation/screens/SettingsCategoryScreen.kt`：对应 `SettingsCategory` 分支里加 `SettingsSubsection` + `SwitchSettingItem`，带 `Modifier.settingHighlight("item_xxx", highlightKey)`。
4. `presentation/settings/search/SettingsRegistry.kt`：注册 `SettingSpec`（`type = SettingType.SWITCH`，带 `getValue` / `onToggle` 与关键词），`itemKey` 与第 3 步的 highlight key 一致。
5. `res/values/strings_settings.xml` 加 title/subtitle，同时加 `values-zh-rCN/` 中文版；其他语言留给翻译流程，不要手写。

## 共享 PlayerViewModel（必读：别加回 `= hiltViewModel()` 默认值）

全应用**只有一个** `PlayerViewModel`：`MainActivity` 以 `by viewModels()` 持有（activity 级），传给
`AppNavigation`，再经 `ScreenWrapper` 与显式参数逐层下传。**screen / component 的参数上不要写
`playerViewModel: PlayerViewModel = hiltViewModel()`** —— 这不是"方便的默认值"，是 bug：

- 在 `composable(route) { }` 内部，`hiltViewModel()` 解析的是**该路由自己的 `NavBackStackEntry`** → 新建**第二个**实例；
- 该实例随路由出栈销毁，其 `onCleared()` 会连带清理 `SearchStateHolder`、`PlaybackStateHolder` 等 `@Singleton` →
  **全局状态被静默拆掉**（不崩溃、无异常日志）。实测：进一次 AI 混音页再返回，搜索从此永远返回空列表，只能重启应用恢复。

三条约束，缺一即回归：

1. `PlayerViewModel` 型参数**一律不写默认值**（现在全仓 `grep 'PlayerViewModel = hiltViewModel()'` 为 0），
   调用点显式传入，让编译器强制接线。
   `PlaylistViewModel` / `SettingsViewModel` / `EqualizerViewModel` 等的默认值**保留** —— 它们没有清理单例状态的行为。
   **已知例外（待修）**：`ExternalPlayerActivity.kt` 仍独立持有第二个实例（`by viewModels()`），
   是上面第 1 条约束的现存违反案例，修复方案见 `docs/implementation-review-0.4.2-critical-verdict.md` §1.3。
   **注意 owner 协议挡不住冷启顺序**：首个 owner 若恰好是短命 VM，它死亡时 owner 匹配、照样拆 holder ——
   所以 owner 校验只是纵深防御，**不能替代消除第二 owner**。
2. **新增屏幕 / 覆盖层要接进传递链**：`AppNavigation` 的 `composable(route)` → `ScreenWrapper` → 目标 composable；
   播放器覆盖层走 `UnifiedPlayerOverlaysLayer.kt` 的 host → `UnifiedPlayerQueueLayer` → `QueueBottomSheet`。
3. **新增 `@Singleton` StateHolder 照抄 `SearchStateHolder` 的所有权校验**：`initialize(owner, scope)` /
   `onCleared(owner)`，**首个 owner 胜出**（"接管"语义无效：第二个 VM 会拿到所有权，销毁时照样清空），
   非 owner 的调用忽略并打 `Timber.w`，调用点传 `this@PlayerViewModel`。回归测试见 `SearchStateHolderTest`。

**探针**：`logcat` / `files/logs/pixelplayeross.log` 里 grep `SearchStateHolder` 的 `Timber.w` 告警 ——
有告警就说明又有第二个 VM 在调 `initialize`/`onCleared`，即传递链又漏了一处。

## 关键架构速查

- **听歌统计的唯一驱动方是 `MusicService`，不是 `PlayerViewModel`**（后者只有注入/暴露/`initialize`/`onCleared` 四处引用）：
  `MusicService` 给 `engine.masterPlayer` 挂 listener → `syncLocalListeningStatsFromPlayer` → `ListeningStatsTracker`
  → `finalizeCurrentSession` → JSON + Room engagement → 最近播放 UI 与 AI 选歌。**要过滤某类播放，只改这一处即四处同时生效。**
- **Quick play（外部一次性预览）不计入统计**：标记 `MediaItemBuilder.EXTRA_QUICK_PLAY` 随 MediaItem 流动，
  在 `syncLocalListeningStatsFromPlayer` 开头拦截。**不要用 `EXTERNAL_EXTRA_FLAG` 代替** —— 它由 media id 前缀反推，
  MediaStore 索引到的文件 id 是纯数字，该 flag 为 false（而这正是从文件管理器打开的常见情形）。
- **删除歌曲**：所有 UI 的 `onDeleteFromDevice` 最终都走 `PlayerViewModel.deleteSelectedFromDevice`（批量）/ `deleteFromDevice`（单曲）—— 拦截删除只需改这两处。`removeSongFromLibrary` 是"仅移出曲库、不删文件"。
- **五星评分**：入口 `SongInfoBottomSheet`（Info/Edit 两页）→ `EditSongSheet`，写**音频文件内嵌标签**（`SongMetadataEditor`，读键 `RATING` / `POPULARIMETER`），不是数据库字段；云端曲目无法写入。
- **设备能力**：`DeviceCapabilitiesScreen` 展示解码器支持（`supportedCodecs` / `isDecoderAvailable`）。
- **返回键**：`LibraryScreen` 的文件夹返回 / 选择态清除走 `PlayerSheetAwareBackHandler` + `PlayerViewModel.playerSheetBackCoordinator`
  （主线程直接改 `OnBackPressedCallback.isEnabled`，绕开 Flow→重组的一帧延迟），播放器或队列展开时它让位给播放器。
  **不要把它改回普通 `BackHandler`**：会重现 #136 —— 文件夹内按返回退出文件夹而不是收起播放器（该机制是三步修成的，见 2026-10-11 记录）。
- **应用内更新器**：`data/update/` —— 只认本 fork 的 GitHub releases：`AppReleaseSource.parseLatestRelease()` 按 ABI 整段匹配 asset（`-x86` 不能命中 `-x86_64`），`AppUpdateManager` 下载后走 `PackageInstaller` 安装。
  **没有 F-Droid/Stable 通道**（2026-10-11 移除）：本 fork 没上架 F-Droid、国内也访问不到，所以 `UpdateChannel`、`update_channel` 偏好、跨签名「备份→下载→卸载」引导与 `REQUEST_DELETE_PACKAGES` 都已删除，**不要从上游恢复**。无法覆盖安装的场景（debug 包 / 异签名）只提示并打开发布页。
- **性能诊断**：`data/diagnostics/`（`AdvancedPerformanceDiagnostics`、`MainThreadStallMonitor`）。
- 提示统一走 `PlayerViewModel._toastEvents`；脚本 `run-tests.bat` / `test_summary.py` 尚未移植（china-only 有）。

## 约定

- commit message 用英文；不修改上游 `CHANGELOG.md`（自用分支不提 PR，**指不向上游仓库提 PR；fork 自己的 `CHANGELOG.md` 每版都要改**）。
- **合并上游后必须人工补 CHANGELOG**：上游的条目写在它自己的 `## [Unreleased]` 段，合并时只有上下文能对上 fork 段落的那条会被带进来，**其余 `### Fixed` 会静默丢失**（2026-10-11 那次丢了 4 条，只捞回 Updates 一条）。
  合并后先 `git show upstream/main:CHANGELOG.md` 看它的 Unreleased 段，把丢掉的条目补进 fork 的 `## [Unreleased]` 段（`ChangelogParser` 会跳过无日期的块，应用内 changelog 不显示）；发版时把该段标题改成 `## [<版本名>] - <日期>`。
  上游条目里与 fork 已有条目重复的（例：Jellyfin playlists 那条 0.3.0 段已有）不重复补。
- 新功能字符串同时加中文（`values-zh-rCN/`）与英文（`values/`）两种语言，不手写其他语言。
- 改动后至少跑 `assembleRelease`（默认构建）；UI 类改动装真机验证（**验收范围见「目标设备与屏幕尺寸」**，小屏被裁不算缺陷）。需要逐行调试时才额外构建 `debug`。
