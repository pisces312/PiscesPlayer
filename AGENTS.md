# AGENTS.md

PiscesPlayer — Android 音乐播放器（100% Kotlin，Jetpack Compose + Material 3）。
本仓库是 `PixelPlayerHQ/PixelPlayerOSS` 的**独立维护分支**：`pisces312/PiscesPlayer`（上游链接见 `README.md` 与 `PROVENANCE.md`）。Gradle 模块：`:app`、`:baselineprofile`。
包名 `com.pisces312.piscesplayer`，许可证 **GPL-3.0-or-later**。

## 开发环境与路径引用（两套，2026-11-23 起）

本项目在 **PC（Windows）** 与 **手机（DSH Go · proot Ubuntu 24.04 aarch64）** 两套环境开发，
共用同一 git 仓库。**两套环境的路径不同 —— PC 用 `D:\...`，手机用 `/storage/...` / `/root/...`，引用路径必须先认准环境，手机侧不要照抄 `D:\...`**。下表是权威换算表；本文散落的路径引用一律双写「PC / 手机」两式，漏写的按下表换算。

| 概念 | PC（Windows） | 手机（proot Ubuntu） |
|---|---|---|
| 工作区（私有文档仓库 `my-projects`，本文 `D:\workspace` 即它） | `D:\workspace` | `/storage/emulated/0/my-git-projects/workspace`¹ |
| 项目根（PiscesPlayer、RhythmChartLab 等） | `D:\3rd-party-projects\<项目>` 等² | `/storage/emulated/0/my-git-projects/<项目>`³ |
| JDK | `D:\dev\jdk-25` | apt `openjdk-25`（`JAVA_HOME=/usr/lib/jvm/java-25-openjdk-arm64`） |
| Android SDK | `D:/dev/android_sdk`（`ANDROID_HOME`） | `/root/android-sdk`（`ANDROID_HOME`） |
| GRADLE_USER_HOME | `D:\dev\.gradle` | `~/.gradle`（默认，无需设置） |
| 签名 `KEY_*` 变量 | Windows 用户环境变量（注册表 `HKCU:\Environment`） | `/root/.secrets.env`（`chmod 600`，由 `~/.bashrc` source；所有项目共享） |
| 产物 APK | 仓库 `app/build/outputs/apk/...` | 同左；交付时拷到 `/sdcard/Download/DSH-Go/` |

- ¹ proot 内即 `/sdcard/my-git-projects/workspace`（`/root/手机存储/my-git-projects/workspace` 是同一位置）。
  它是 Codeup `my-projects` 的克隆，与 PC 的 `D:\workspace` **同一仓库**，`design/piscesplayer/docs` 结构一致。
- ² 例：节奏游戏文档库 PC 在 `D:\3rd-party-projects\RhythmChartLab\docs`、手机在 `/storage/emulated/0/my-git-projects/rhythm-chart-lab/docs`。
- ³ 目录名小写连字符风格（如 `rhythm-chart-lab` 对应 PC 的 `RhythmChartLab`）；内部结构一致，文档间相对引用两边通用。
- **PiscesPlayer 手机侧唯一工作克隆 = `/root/PiscesPlayer`**（2026-11-23 定）：`/sdcard` 是 FUSE
  大容量存储，Gradle 在其上构建慢且易卡，`/root` 还随运行时升级保留。
  `/sdcard/my-git-projects/PiscesPlayer` 只作镜像 / 同步副本，**不在其上改动**；其他项目仍在
  `/storage/emulated/0/my-git-projects/<项目>`。
- **文档仓库手机侧同步差异（2026-11-24 核对）**：`workspace/design/piscesplayer/docs` 与
  `rhythm-chart-lab/docs` 手机上都在、结构与本文一致，可直接读写；差别在 git 同步——
  `rhythm-chart-lab` 的 remote 是 GitHub（`github.com:pisces312/rhythm-chart-lab`），手机可直接
  pull/push；`workspace`（my-projects）的 remote 是 Codeup（SSH），手机公钥未在 Codeup 授权，
  `git fetch/push` 报 `Permission denied (publickey)`，手机上只能改本地树、提交同步靠 PC 侧
  （把手机 `~/.ssh/id_ed25519.pub` 加进 Codeup 公钥即恢复）。另：`/sdcard` FUSE 偶尔 `ls` 不到
  实际存在的目录，核对目录存否用 `git ls-tree HEAD`，别单凭一次 `ls` 判定不存在。

### 构建命令差异

- **PC**：`.\gradlew.bat :app:assembleRelease`（发版走 `build-release.bat`，见「签名与发布」）。
- **手机**：`source ~/.bashrc && ./gradlew :app:assembleRelease` —— DSH 对话里执行命令的是**非交互 shell，
  不读 `.bashrc`**，必须先 source（终端 App 里则已自动生效）。
- daemon JVM 两套环境都钉 **JDK 25**（`gradle/gradle-daemon-jvm.properties`，故意不带 foojay 下载链接，
  靠本机 `JAVA_HOME` 指向 JDK 25）；编译目标仍是 jvmTarget / sourceCompatibility **21**，不受影响。
- **手机侧已知差异**：项目 `gradle.properties` 的 `-XX:+ParallelRefParsingEnabled` 是 JBR 系 JVM 的
  flag，Ubuntu apt 的 OpenJDK 25 不认识（daemon 直接拒绝启动）。手机侧在 `~/.gradle/gradle.properties`
  用 `org.gradle.jvmargs` 覆盖（**用户级优先于项目级**，已实测）；PC 的 JBR 不受影响。
  不要"顺手"删项目文件里的这个 flag——它是上游为 JBR 加的。
- 凭据（`KEY_*` 的值、keystore 文件）**只放环境变量与仓库外路径，严禁写进本文件或任何入库文件**。

## 仓库历史关系（重要，动手前必读）

| 仓库 | 许可 | 关系 |
|---|---|---|
| `PixelPlayerHQ/PixelPlayer` | **专有**（禁止再分发源码/二进制及衍生作品） | 上游闭源版，含 Telegram / QQ 音乐 / Gemini / GMS 等集成 |
| `PixelPlayerHQ/PixelPlayerOSS` | GPL-3.0-or-later | 官方开源版，**已剥离** Telegram、NetEase、QQ 音乐、Google Drive、Gemini、Cast、Wear OS、Play 计费、Firebase、Crashlytics、GMS。**本仓库的直接上游**，基线见 `PROVENANCE.md`。本 fork 在此基线上另有**用户可选**的在线服务（LRCLIB、Deezer、MusicBrainz / CoverArtArchive、ListenBrainz、Open-Meteo、OpenAI 兼容 LLM）与自建 Navidrome/Subsonic、Jellyfin 集成，清单见 `PRIVACY.md`；这些均非上游专有服务 |

- **专有 PixelPlayer 与 OSS 的 git 历史完全独立**：`git merge-base` 为空 → **不能 cherry-pick / merge**，跨仓库只能手工重新落地。本仓库与 OSS 有共同历史，可按需 merge upstream。
- **AI 子系统**：本 fork 自研 `data/ai/`（OpenAI 兼容协议，用户自配 endpoint / key / 模型，请求日志只存本地）与 OSS 基线的本地 NLP（`data/playlist/nlp/PlaylistIntentEngine`）**并存**；没有内置云端 AI 凭据或默认上报。
- **版权红线**：从**专有 `PixelPlayerHQ/PixelPlayer`** 或其派生实现搬功能时，**原创算法/解析层**（parser、matcher、分桶、日志收集等）可搬，提交注明 `Original work, GPL-3.0-or-later`；**glue 层**（ViewModel / DI / DAO / 设置项 / UI 接线 / strings）必须在本仓库重写；**禁止整文件复制专有 PixelPlayer 的实现**。

## 设计文档（私有仓库 workspace）

PiscesPlayer 的设计、调研、计划、评审类文档统一存放在**私有仓库 workspace**
（PC `D:\workspace` / 手机 `/storage/emulated/0/my-git-projects/workspace`，
Codeup `my-projects`，远端 `git@codeup.aliyun.com:63f6dbf33c5c77b98fc5cfd1/my-projects.git`）
的 `design/piscesplayer` 子目录，不入本仓库；文档在其 `docs/` 子目录下
（沿用原目录结构，文档间互引 `docs/xxx.md` 依旧有效）。2026-11-17 起本仓库 `docs/` 只保留
运维/发布类文档（`RELEASE.md`、`REBRAND.md`、`FDROID.md`、`DEPENDENCY_LICENSES.md`、
`RELEASE_ARCHIVE.md`、`releases/`、`images/`）；存量设计文档已整体迁往该仓库。

- **新设计文档写入** `D:\workspace\design\piscesplayer\docs\`（手机：`/storage/emulated/0/my-git-projects/workspace/design/piscesplayer/docs/`），不要在本仓库 `docs/` 新建。
- 代码注释与旧文档中的 `docs/<设计文档>.md` 引用一律对应
  `design/piscesplayer/docs/<设计文档>.md`（PC 前缀 `D:\workspace\`、手机前缀
  `/storage/emulated/0/my-git-projects/workspace/`；散落引用不逐个改）；
  引用运维文档（RELEASE/REBRAND/FDROID 等）的仍指本仓库 `docs/`。

## 节奏游戏文档

节奏游戏相关的设计、调研与实施文档统一存放在 `D:\3rd-party-projects\RhythmChartLab\docs`（手机：`/storage/emulated/0/my-git-projects/rhythm-chart-lab/docs`），本仓库 `docs/` 不再保存这类文档。后续新增节奏游戏相关设计文档也放入该目录，不要在 PiscesPlayer 内另建副本。

## 分支

- `main`：**不是唯一的开发分支，而是发版起点** —— release 一般从 `main` 打 tag 构建。
  日常开发可以直接提交到 `main`，也可以开特性分支，开不开按需决定（2026-09-11 起不再要求
  经 `pisces/port` 中转）。每功能一个 commit，英文 message。
- 特性分支上的改动**发版前要先并回 `main`**，否则从 `main` 打出的 tag 会缺内容。

## 技术栈与版本

- Gradle 9.6.1（wrapper 已改腾讯云镜像）；AGP / Kotlin / KSP 版本以 `gradle/libs.versions.toml` 为准（本文不抄写具体值）。
  JDK **25**（PC `D:\dev\jdk-25`、手机 apt `openjdk-25`，daemon 钉 25，见「开发环境与路径引用」），编译目标 jvmTarget / sourceCompatibility **21** 不变。
- compileSdk = targetSdk = 37，minSdk = 29（Android 10，2026-11-19 起）；版本号在 `gradle.properties` 的 `APP_VERSION_NAME` / `APP_VERSION_CODE`
  （**以该文件为准，本文不抄写具体值** —— 每发一版就变，抄在这里必然过期）。
- **minSdk 29 的 Android 10 特例（只有 API 29 受影响，30+ 行为不变）**：MediaStore 的
  write/delete request 是 API 30，29 上回退直接文件访问，依赖
  `requestLegacyExternalStorage` + `WRITE_EXTERNAL_STORAGE`（manifest `maxSdkVersion=29`，
  Setup 时与 READ 同一批申请，见 `SetupScreen.MediaPermissionPage` / `MainActivity` 的
  权限列表；**30+ 切勿申请 WRITE**，manifest 里没有该权限会直接拒绝）。
  `StorageVolume.directory`、per-volume `getContentUri(volume, id)` 都是 API 30，
  29 上的分支见 `StorageUtils.getVolumePath`（反射 `getPath`）与
  `MediaStorePermissionHelper`。给写/删路径加代码时保持这些回退可用。
- Media3 1.11.0（`libs.versions.toml` 的 `media3Session` / `media3Transformer` **保持一致**，不要只升其中一个）、
  Room 2.8.5（**数据库 schema 版本以 `PiscesPlayerDatabase.kt` 的 `version` 为准，本文不抄写**；
  任何实体变更都要把 `version` +1 并新增 migration）、Hilt、OkHttp/Retrofit、TagLib + JAudioTagger 元数据。
- 依赖仓库为官方 `google()` / `mavenCentral()`（首次构建较慢，无国内镜像）。
- **Compose 栈由 `material3` 锚定**：它当前钉在 `1.5.0-alpha27`。升它的 alpha 号会经传递依赖
  拖动整条 Compose 栈（`alpha28` 会把栈从 `1.12.1` stable 顶进 `1.13.0-alpha01`），
  而 `composeBom` 与显式声明的 `composeUi` / `foundation` / `animation` **取较高者**、
  必须同批改。动之前先读（私有文档仓库 PC `D:\workspace\design\piscesplayer\docs\` /
  手机 `/storage/emulated/0/my-git-projects/workspace/design/piscesplayer/docs/`）
  `dependency-upgrade-2026-09.md` §9 / §10。

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
`weight(1f, fill = false)`）见（私有文档仓库 PC `D:\workspace\design\piscesplayer\docs\` /
手机 `/storage/emulated/0/my-git-projects/workspace/design/piscesplayer/docs/`）
`ai-thinking-persistence-plan.md` §7.6，已否决。

## 常用命令（PowerShell = PC；手机用 `bash`，见「开发环境与路径引用 · 构建命令差异」）

```powershell
.\build-release.bat                             # 正式版构建（推荐，见「签名与发布」）：签名变量缺失时快速失败并提示，构建后自动 apksigner 验签
.\gradlew.bat :app:assembleRelease              # 裸构建：无签名环境变量时静默产出未签名 APK（不报错！）
.\gradlew.bat :app:assembleDebug                # 仅按需：release 复现不了 / 难定位时
.\gradlew.bat :app:testDebugUnitTest            # 单元测试（JUnit 5，JVM）
.\gradlew.bat :app:lintDebug                    # Lint（release 构建不做）
.\gradlew.bat :app:connectedDebugAndroidTest -Ppixelplayer.enableDebugMinify=false    # instrumented 测试（需连真机 / 模拟器；必须关 debug minify）
```

- **debug / release 可共存**：`debug` 带 `applicationIdSuffix = ".debug"` + `versionNameSuffix = "-debug"`，应用名 `PiscesPlayer [D]`（`app/src/debug/res/values/strings.xml`），
  图标走 `app/src/debug/res` 的「琥珀橙渐变 + 玫红主体」覆盖（`ic_launcher_background` / `ic_launcher_foreground` / legacy 方、圆图标四层）：
  foreground 自带不透明青色渐变，**只染 background 层是看不见的**；且 launcher 圆形遮罩只留 16.3% 背景、主体占 83.7%，
  所以还要把深蓝鱼腹/圆盘/尾鳍的 HSV 色相转到 325°（只改 H，保留 S/V 与白鱼），圆内平均色差从 27.6 提到约 91。
  `mipmap-anydpi-v26` 的 XML 在 debug 源集覆盖一份、故意去掉 `monochrome`，否则 Android 13+ 主题图标会把颜色差异抹平。
  改了 main 图标后重跑根目录 `gen_debug_icons.py`（Pillow + numpy + scipy，默认 `amber` / `--subject-hue 325`，可用 `--colorway` 换配色）；
  脚本自带断言：白鱼像素零变化、圆内色差 ≥ 80。
- **默认构建 `release`；`debug` 只在按需排查时构建**：2026-11-19 起 `debug` 也跑 R8，
  但是**只裁剪、不混淆、不优化**（`app/proguard-debug.pro` 的 `-dontobfuscate -dontoptimize`，
  keep 规则与 release 同一份 `proguard-rules.pro`）：类名 / 方法名 / 行号在栈帧里原样保留，
  logcat 不需要 retrace；`-dontoptimize` 同时保证不内联、不合并类（否则名字对了栈也会缺帧），
  且 `proguard-rules.pro` 里的 `-assumenosideeffects` 不生效，debug 保留 Timber/Log 日志。
  开关：`-Ppixelplayer.enableDebugMinify=false` 回到完全未 minify 的历史形态
  （instrumented 测试必须用它，见下条；怀疑 bug 是 R8 裁剪本身引起时也用它）。
  体积实测（arm64-v8a、0.4.1、同一份代码）：**release 37.5 MB / debug 无 R8 152.1 MB /
  debug 带 R8 69.2 MB**（差别几乎全在 dex：14.5 MB / 128.0 MB / 45.0 MB）。
  历史教训（为什么曾经禁止给 debug 开 R8）：instrumented test 的 test APK 是独立程序，按**原名**调用
  app APK 里的类与成员，而 R8 裁剪会删掉 app 自己不调用的成员（Kotlin facade、`RoomDatabase.close()`、
  闲置 DAO 方法），逐个 keep 是无底洞 —— 所以机上测试一律走 `enableDebugMinify=false`。
- **ABI 只构建 `arm64-v8a`**（`pixelplayer.enableAbiSplits` 默认 true）；关掉该 property 时文件名中的 abi 段为 `universal`。
- **模拟器可直接安装运行 `arm64-v8a` 产物（已实测确认 2026-09-14，2026-11-01 再次确认）**：
  本机 AVD `pixel6` 是 **x86_64** 系统镜像（`system-images\android-34\google_apis\x86_64`），
  但 API 30+ 的 Google x86_64 镜像自带 **ARM 二进制翻译**，`adb install` 默认构建的
  `piscesplayer-arm64-v8a-*.apk` 直接就能跑。
  - **不要**为了"让模拟器装得上"去构建 universal —— `-Ppixelplayer.enableAbiSplits=false`
    会覆盖并清掉同目录的 arm64 产物；
  - **别把"翻译慢"误判成"不支持"**：首帧与冷启动明显慢于真机，`am start` 后请
    `sleep` 若干秒再截图；若 logcat 里出现 `arm64` 翻译层加载或首帧延迟，那是性能问题，
    不是安装失败。
  - **但界面改动的目视验收由用户自己真机做**（2026-11-01 定）：模拟器只用于"跑起来看一眼
    有没有崩"，不要花时间在 AVD 上抠布局 / 逐页截图；构建出 release APK 交给用户即可。
  - **构建成功后不要自动做冒烟测试**（2026-11-16 定）：`assembleRelease` / `assembleDebug`
    跑完、产物路径确认即可交付，**不必**每次都 `adb install` + `am start` + 截图 + 查 logcat。
    冒烟只在两种情况下做：① 用户明确要求；② 本次改动直接动启动链路（DB 迁移/初始化、
    Application/Activity 依赖注入、崩溃修复）且用户提出要复现或确认。其余情况把 APK 交给用户，
    由用户在真机上验。
  - 真机/模拟器验证统一用 `.\gradlew.bat :app:assembleRelease`（debug 与 release 的 applicationId 不同，可共存）。
- **instrumented 测试**：`.\gradlew.bat :app:connectedDebugAndroidTest -Ppixelplayer.enableDebugMinify=false`，
  跑在 `debug` 变体上（`testBuildType` 保持默认）。**必须带 `enableDebugMinify=false`**：
  debug 默认跑 R8 裁剪，test APK 按原名调用的成员可能已被删掉（见上条历史教训）。
  只跑某几个类：追加 `-Pandroid.testInstrumentationRunnerArguments.class=全限定类名,逗号分隔`。
  唯一跑不了的：`SyncWorkerTest` 需要 MockK inline（JVMTI agent），ART 拒绝从 app cache 加载，
  属既有测试设计问题，与构建配置无关。
- **APK 命名**：`piscesplayer-<abi>-<APP_VERSION_NAME>-<buildtype>.apk`（`androidComponents.onVariants` 设置 `outputFileName`），
  例 `piscesplayer-arm64-v8a-1.0.0-debug.apk`。改命名规则改 `app/build.gradle.kts`。
- 签名**只读 `KEY_*` 环境变量**（或 `pixelplayer.disableReleaseSigning=true` 跳过）；
  仓库里没有、也不该有任何 keystore 文件。详见下方「签名与发布」。
- `GRADLE_USER_HOME=D:\dev\.gradle`（手机为默认 `~/.gradle`）；wrapper 的 Gradle 发行版走腾讯云镜像。

## 签名与发布（自用 fork）

**凭据只存在于环境变量**，严禁写入仓库内任何文件（含 `docs/`、release notes、memory）：

| 环境变量 | 用途 |
|---|---|
| `KEY_STORE` | keystore 文件名（备查） |
| `KEY_STORE_LOCATION` | keystore 绝对路径（PC 为 Windows 路径，手机为 Linux 路径） |
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

### debug 签名全平台统一（2026-11-26 起）

PC 与手机的 `~/.android/debug.keystore` 用**同一把** debug key（alias `androiddebugkey`、
口令 `android`，即 PC 原生 debug key），debug 包可跨机器互相覆盖安装、不再报
`INSTALL_FAILED_UPDATE_INCOMPATIBLE`。备份在私有仓库 workspace `keys/engine/debug.keystore`
（换机 / 重置后放回手机 `/root/.android/debug.keystore`）；2026-11-26 前的旧引擎 key 留档
`keys/engine/debug-cde0-legacy.keystore`，它签名的存量 debug 包换 key 后只能卸载重装。
**不需要任何构建配置改动** —— AGP 默认 debug 签名就读 `~/.android/debug.keystore`。

### 正式版必须签名：`build-release.bat`（2026-11-17 起）

凡是交付 / 安装给他人的 release APK **必须带 `CN=pisces312` 签名**。签名变量缺失时
`assembleRelease` **静默产出未签名 APK（构建照常成功、无任何报错）**，所以裸跑
`gradlew :app:assembleRelease` 有踩坑风险——发版/交付构建**一律走根目录 `build-release.bat`**
（封装 `build-release.ps1`）：

- 进程环境里缺 `KEY_*` 时**自动从注册表 `HKCU:\Environment` 读取**（长驻会话继承不到新变量的对策）；
- 注册表里也没有 → 打印缺少的变量名 + 设置指引，**直接退出、不开始构建**（省几分钟白跑）；
- 构建成功后自动 `apksigner verify --print-certs -v` 验签（`Verifies` + `v2 scheme: true` +
  `CN=pisces312`），未签名则报错退出。
- 注意：`build-release.ps1` 现为**纯英文（ASCII）**，不再要求 UTF-8 with BOM——旧版中文输出依赖
  BOM 才能让 Windows PowerShell 5.1 正确解码；纯英文从根上免掉了这一类编码问题。

### 环境变量在哪、会话为什么可能拿不到（2026-11-14 补）

`KEY_*` 是**Windows 用户级环境变量**，**权威来源是注册表 `HKCU:\Environment`**（用户自己维护，
机器上已配齐；keystore 在仓库外的本机路径，具体位置不入库）。

**手机侧的对应物是 `/root/.secrets.env`**（`chmod 600`，由 `~/.bashrc` 自动 source；Linux 用户级、
所有项目共享；在 `/root` 下、任何 git 仓库之外，不会被提交）。两个注意点：① DSH 对话里执行命令的
是非交互 shell、不读 `.bashrc`，构建命令统一写 `source ~/.bashrc && ./gradlew ...`；② 口令只写进
`/root/.secrets.env`，**不写进本文件或任何入库文件**。
新启动的进程能继承，但**早于变量设置而启动的进程继承的是旧环境**——长驻会话（包括 DSH/IDE）里
`$env:KEY_STORE_LOCATION` 可能是空的，此时构建**静默产出未签名 APK**（构建成功、无报错）。

约定：

1. 发版/交付构建前先检查 `$env:KEY_STORE_LOCATION`。**推荐直接跑 `build-release.bat`，
   它会自动完成下述同步**；必须裸跑 `gradlew` 时，为空则**从注册表 `HKCU:\Environment`
   读取并同步**再构建（DSH 里**每次 pwsh 调用都是新进程**，上一句
   `$env:X = ...` 赋值在下一条命令里不生效，所以要把同步和 `gradlew` 调用**写在同一条命令里**）：

   ```powershell
   foreach ($v in 'KEY_STORE','KEY_STORE_LOCATION','KEY_STORE_PASSWORD','KEY_ALIAS','KEY_PASSWORD') {
       Set-Item -Path "env:$v" -Value ([Environment]::GetEnvironmentVariable($v, 'User'))
   }
   .\gradlew.bat :app:assembleRelease
   ```

   注意 `$env:$v` 不是合法语法，批量赋值用 `Set-Item -Path "env:$v"`；
   只检查存在性，**不要把口令值打印到输出里**。
2. 交付前必须跑 `apksigner verify`（命令见上节「构建成功 ≠ 已签名」），确认
   `Verifies` + `v2 scheme: true` + `CN=pisces312`；缺签名变量时的产物
   报 `DOES NOT VERIFY` + `Missing META-INF/MANIFEST.MF`，这不是构建故障，是签名块没启用。
3. 日常调试构建不签也罢；凡是给用户安装的 APK 都必须签名。

### CI：无（2026-11-11 起，上游 workflow 全部删除）

`.github/workflows/` 已清空：`alpha-release.yml` 早前删除，`claude.yml` 与
`nightly-apk.yml` / `pr-build.yml` / `gitlab-mirror.yml` 于 2026-11-11 删除。
它们沿用上游、从未在本仓库跑过（`actions/runs` → `total_count=0`），且没有一处能跑绿：
blacksmith runner 不存在、产物名不匹配、签名走已删除的 `keystore.properties` 路径、
nightly 缺 secret 时退化成随机一次性钥匙。detach 后"fork 默认禁 schedule"的保护也不复存在，
留着 nightly 的 cron 是定时红灯。

**本仓库只有本地发版一条路**：签名机制的真相只有 `app/build.gradle.kts` + `KEY_*` 环境变量一处。
将来若要 CI，新写 `ubuntu-latest` 的 lint+单测入口（不签名、不发布），不要恢复上游壳；
合并上游时若上游改动这些 workflow，冲突解法 = 保持删除。

两个坑：

- **构建成功 ≠ 已签名**，唯一可信判据是
  `apksigner verify --print-certs -v <apk>` → `Verifies` + `v2 scheme: true` + `CN=pisces312`
  （只有 v2、没有 v1 是正常的）；`aapt2 dump badging` 核对 `versionName` / `versionCode` / `native-code`。
- 本机 build-tools：PC `D:/dev/android_sdk/build-tools/37.0.0/`、手机 `/root/android-sdk/build-tools/37.0.0/`。

发版链路：`main` → 版本号提交 → `tag v<版本名>` → `assembleRelease` → `gh release create`。
版本号使用纯语义化 `X.Y.Z`（不再带 `-pisces.N` 后缀），版本码沿用 `主版本 * 100000 + 序号`。

> 上游 workflow 已全部删除（见上节），本地发布**不需要**在 message 里写 `[skip ci]`。

## 新增一个设置开关（必读，缺一不可）

OSS 有**设置搜索**，开关不注册就搜不到：

1. `data/preferences/UserPreferencesRepository.kt`：`PreferencesKeys` 加 key → `xxxFlow` → `setXxx()`。
2. `presentation/viewmodel/SettingsViewModel.kt`：`SettingsUiState` 字段 → `SettingsUiUpdate.Group2` 字段 → `init` 里 combine 的 flow 列表**末尾**追加 → `values[N]`（索引按位置对应，只能追加不能插队）→ `state.copy()` → `setXxx()`。
3. `presentation/screens/SettingsCategoryScreen.kt`：对应 `SettingsCategory` 分支里加 `SettingsSubsection` + `SwitchSettingItem`，带 `Modifier.settingHighlight("item_xxx", highlightKey)`。
4. `presentation/settings/search/SettingsRegistry.kt`：注册 `SettingSpec`（`type = SettingType.SWITCH`，带 `getValue` / `onToggle` 与关键词），`itemKey` 与第 3 步的 highlight key 一致。
5. `res/values/strings_settings.xml` 加 title/subtitle，同时加 `values-zh-rCN/` 中文版；只维护中英两语，不加其他语言。

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
   是上面第 1 条约束的现存违反案例，修复方案见（私有文档仓库 PC `D:\workspace\design\piscesplayer\docs\` /
   手机 `/storage/emulated/0/my-git-projects/workspace/design/piscesplayer/docs/`）
   `implementation-review-0.4.2-critical-verdict.md` §1.3。
   **注意 owner 协议挡不住冷启顺序**：首个 owner 若恰好是短命 VM，它死亡时 owner 匹配、照样拆 holder ——
   所以 owner 校验只是纵深防御，**不能替代消除第二 owner**。
2. **新增屏幕 / 覆盖层要接进传递链**：`AppNavigation` 的 `composable(route)` → `ScreenWrapper` → 目标 composable；
   播放器覆盖层走 `UnifiedPlayerOverlaysLayer.kt` 的 host → `UnifiedPlayerQueueLayer` → `QueueBottomSheet`。
3. **新增 `@Singleton` StateHolder 照抄 `SearchStateHolder` 的所有权校验**：`initialize(owner, scope)` /
   `onCleared(owner)`，**首个 owner 胜出**（"接管"语义无效：第二个 VM 会拿到所有权，销毁时照样清空），
   非 owner 的调用忽略并打 `Timber.w`，调用点传 `this@PlayerViewModel`。回归测试见 `SearchStateHolderTest`。

**探针**：`logcat` / `files/logs/piscesplayer.log` 里 grep `SearchStateHolder` 的 `Timber.w` 告警 ——
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
- 提示统一走 `PlayerViewModel._toastEvents`；脚本 `run-tests.bat` / `test_summary.py` 尚未移植（历史参考实现有）。

## 约定

- commit message 用英文；不修改上游 `CHANGELOG.md`（自用分支不提 PR，**指不向上游仓库提 PR；fork 自己的 `CHANGELOG.md` 每版都要改**）。
- **合并上游后必须人工补 CHANGELOG**：上游的条目写在它自己的 `## [Unreleased]` 段，合并时只有上下文能对上 fork 段落的那条会被带进来，**其余 `### Fixed` 会静默丢失**（2026-10-11 那次丢了 4 条，只捞回 Updates 一条）。
  合并后先 `git show upstream/main:CHANGELOG.md` 看它的 Unreleased 段，把丢掉的条目补进 fork 的 `## [Unreleased]` 段（`ChangelogParser` 会跳过无日期的块，应用内 changelog 不显示）；发版时把该段标题改成 `## [<版本名>] - <日期>`。
  上游条目里与 fork 已有条目重复的（例：Jellyfin playlists 那条 0.3.0 段已有）不重复补。
- **应用内语言只支持中文和英文两种**（2026-11-21 定）：跟随系统 / English / 简体中文。
  新功能字符串同时加中文（`values-zh-rCN/`）与英文（`values/`）两种语言，不手写其他语言；
  上游的其他语言译文（`values-ar` 等 10 个目录）已删除 —— 它们只覆盖 ~63% 字符串，
  会造成本 fork 新界面半中半英的混杂观感。合并上游时若这些目录被带回来，保持删除。
- 改动后至少跑 `assembleRelease`（默认构建）；UI 类改动装真机验证（**验收范围见「目标设备与屏幕尺寸」**，小屏被裁不算缺陷）。需要逐行调试时才额外构建 `debug`。
