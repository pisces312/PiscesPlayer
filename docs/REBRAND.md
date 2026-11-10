# PiscesPlayer Rebranding — 决策、迁移与发版记录

日期：2026-11-10 · 分支：`rebrand/piscesplayer` · 版本：`1.0.0`（versionCode `1000001`）

本文记录本 fork 从 PixelPlayerOSS 更名为 PiscesPlayer 的完整决策、改动范围、验证方式与发版计划，
供后续维护者（以及未来的自己）追溯「为什么这么改、改了哪些地方、漏改会怎样」。

## 1. 背景与决策

| 决策点 | 结论 | 理由 |
| --- | --- | --- |
| 应用名 | `PiscesPlayer`（所有 locale 统一） | 与 GitHub 账号 `pisces312` 一致，简短，与上游无字面重叠 |
| 包名（applicationId + namespace + 源码包） | `com.pisces312.piscesplayer`，与上游完全切割 | 用户明确选择彻底切割；一次性承担数据断点，避免以后再断一次 |
| GitHub 仓库名 | 改名 `pisces312/PiscesPlayer`（2026-11-10 执行） | 与产品名一致；GitHub 对旧 URL 自动 301 重定向，`AppReleaseSource` 的 GitHub API、词典下载、MusicBrainz UA、README/Release 正文的 raw 图片链接已全部切换到新名 |
| 兼容 / 持久化标识 | **不改名**（`pixelplayer_database`、`pixelplayer_local_art`、`pixelplayer_music_channel` 等，`pixelplayer.*` Gradle 属性同） | 旧名已持久化（DB 文件名、封面 URI、通知渠道、旧 `.m3u` 标记），改名需数据迁移与导入兼容层，用户可见收益为零 |
| 版本号 | 从 `1.0.0` 重新起步 | 更名 + 新包名等价于新应用；`0.4.x` 系列留给上游名下的历史 |
| 许可证 | GPL-3.0-or-later 不变，`LICENSE` 原文保留 | GPL 允许改名再分发，但要求保留许可证与版权声明 |
| 上游署名 | 新增 `NOTICE`；README/About/fastlane 均注明基于 PixelPlayerOSS by @lostf1sh | GPL 合规 + 商标礼貌；`assets/PixelPlayerOSS.svg` 与原作者署名保留 |

### 为什么连 namespace 一起改（放弃了「只改 applicationId」的低成本方案）

- 本 fork 已经决定「和上游版本完全切割」，不再依赖低摩擦 merge。
- 代价已评估并接受：上游修复无法再干净 merge，需要按 patch 人工移植；Room schema、
  Compose 稳定性配置、baseline profile 全部指向新包名，一旦回切上游名需整体回滚。

## 2. 改动范围（执行清单）

### 2.1 包名迁移（脚本 `D:/Temp/pp-release/rebrand_package.py`，一次性）

| 项 | 数量 / 位置 |
| --- | --- |
| 目录搬迁 | `app/src/{main,test,androidTest}/java/com/lostf1sh/pixelplayeross` → `.../com/pisces312/piscesplayer`，`baselineprofile/src/main/java` 同步 |
| 文本替换 | 880 个文件、约 24,590 处 `com.lostf1sh.pixelplayeross` / `com/lostf1sh/pixelplayeross` |
| gradle | `namespace`、`applicationId`（`app/build.gradle.kts`）、`baselineprofile` 的 `targetPackage`、`rootProject.name = "PiscesPlayer"`、APK 输出名 `piscesplayer-<abi>-<ver>-<type>.apk` |
| Manifest | 广播 action `…action.OPEN_PLAYER` / `ACTION_WIDGET_UPDATE_PLAYBACK_STATE` 一并改为新包名前缀；`${applicationId}` 占位符自动跟随 |
| R8 / Compose | `proguard-rules.pro`、`compose_stability.conf`、baseline profile txt 内的类名全部替换 |
| 更新器 | `AppReleaseSource.PUBLISHED_PACKAGE = "com.pisces312.piscesplayer"`（漏改会导致永远提示「需重装」） |

### 2.2 品牌 / 用户可见字符串（脚本 `rebrand_brand.py` + 手工）

- `app_name`：默认、`values-zh-rCN`、`debug`（`PiscesPlayer [D]`）。
- About 页：新增 `ForkMaintainer`（@pisces312）卡片置顶，原维护者标注为 Upstream FOSS Maintainer；
  链接区分 Source repo（本 fork）与 Upstream project；赞助文案指向 @lostf1sh。
- MusicBrainz / CoverArtArchive 的 User-Agent 品牌段改为 `PiscesPlayer/<version>`（URL 仍指向上游仓库主页）。
- Beta 反馈、Changelog 弹窗的 GitHub 链接指向 `pisces312/PiscesPlayer`。
- fastlane：`title.txt` → `PiscesPlayer`；`full_description.txt` 重写（注明基于上游 + 新增节奏游戏条目）；
  `images/icon.png` 换新图标。
- README：徽章指向本 fork、移除 F-Droid 徽章与下载段（本 fork 不上 F-Droid）、截图条加入节奏游戏两张、
  许可证块改为双署名、页脚区分 fork/upstream 维护者与图标作者。

### 2.3 图标（脚本 `make_icons3.py`，几何掩码方案）

- 源图：用户提供 1024×1024 双鱼环绕播放键渐变圆角方形，四角白色。
- 处理：实测主体占满画布、圆角半径 ≈62px；用圆角矩形几何掩码精确清除四角白色
  （颜色阈值泛洪会经抗锯齿边缘渗漏进白鱼，已弃用），边缘 2px 羽化。
- 产物：`mipmap-*` 全密度 `ic_launcher(_round/_foreground/_background).webp`
  （foreground=全幅 emblem；background=自 emblem 采样生成的三段渐变）、debug source set 同步、
  `fastlane images/icon.png`（512px）、母版存 `docs/images/piscesplayer-icon.png` 与 `assets/`。
- monochrome 层沿用旧 `new_monochrome.xml`（主题化单色glyph，与新 emblem 语义一致即可）。

### 2.4 许可与署名

- `LICENSE`：GPL-3.0 原文，未动。
- `NOTICE`（新增）：本产品包含 PixelPlayerOSS（© 2026 Theo Vilardo）的代码；列出主要修改面；
  声明上游不对修改负责；第三方组件见 `THIRD_PARTY_NOTICES.md`。
- README 许可证块：`PiscesPlayer © 2026 pisces312, based on PixelPlayerOSS © 2026 Theo Vilardo`。

## 3. 版本与 CHANGELOG

- `gradle.properties`：`APP_VERSION_NAME=1.0.0`，`APP_VERSION_CODE=1000001`
  （沿用 major×1,000,000 + minor×100,000 + patch×100 + build 的编码规则，0.4.3 → 400301 同源）。
- `CHANGELOG.md`：`[Unreleased]` 收口为 `[1.0.0] - 2026-11-10`，并补记历史上静默丢失的
  节奏游戏核心条目（DSP 生成、派生、Classic 冻结、入口、播放逻辑选择器）、AI 问歌/翻译/罗马音、
  MusicBrainz、Updates 屏、About 构建信息、更名本身。
- `ChangelogParserTest`：版本列表、日期列表、每版本 section 数（`3,3,3,3,4,5,4,5`）同步更新。

## 4. 用户数据迁移（重要）

新包名 = 全新应用。旧版（`com.lostf1sh.pixelplayeross`）数据不会自动迁移：

1. 旧版内 `Settings → Backup` 导出备份（偏好、播放列表、收藏、歌词、统计、应用状态）。
2. 安装 PiscesPlayer 1.0.0（可与旧版共存，签名不同互不覆盖）。
3. 新版内恢复备份；本地音乐文件本身不受影响。
4. 确认无误后卸载旧版。

兼容性边界：备份格式不校验旧包名，0.4.x 导出的 `.pxpl` 可直接导入；但加密备份只能在
完成初始设置后从 `Settings → Backup` 输入密码恢复（Setup 向导没有密码入口），自定义
艺术家图片建议在首次曲库扫描完成后再恢复，避免 artist 尚未入库时被跳过。

Release 正文会写明以上步骤。旧版收不到应用内更新（包名不同），只能通过 Release 页/README 得知迁移。

## 5. 发版与旧 Release 清理计划

| 步骤 | 内容 | 状态 |
| --- | --- | --- |
| 1 | `rebrand/piscesplayer` 分支提交全部更名改动 | 已提交（收尾改动合为一个提交） |
| 2 | 合并回 `main`（fast-forward 或 merge commit） | 待用户确认 |
| 3 | `gradle.properties` 已是 1.0.0；签名构建（`KEY_*` 环境变量）+ `apksigner verify` | 待执行 |
| 4 | tag `v1.0.0`，push main + tag | 待用户确认 |
| 5 | 创建 GitHub Release：双语正文（英文在前）、APK + 截图附件；正文含迁移说明与上游署名 | 待用户确认 |
| 6 | 删除旧 Release（v0.1.0…v0.4.3-pisces.1 共 4 个 tag 的 Release）| 待用户确认 |
| 7 | 词典资产保护：日语词典挂在独立 tag `dictionary-kuromoji-0.9.0`，**不在**删除范围 | 已核实 |
| 8 | CI 在本 fork 保持禁用（无 workflow 会因 tag 触发） | 已核实 |

> 注意：删除旧 Release 前需确认没有外部文章/链接依赖那些资产 URL；应用内更新器只匹配
> 最新 Release 的 `piscesplayer-*-release.apk` 资产名，不受旧 Release 删除影响。

## 6. 后续维护建议

- **上游同步**：从 upstream merge 后，`docs` 与包名可能再次冲突；合并前先
  `git diff upstream/main -- app/src/main/java/com/pisces312/piscesplayer | grep lostf1sh` 自检。
- **新增 Room 迁移**：流程不变（version 16 起），但提交 schema 时注意路径已是新包名。
- **CHANGELOG 纪律**：合并/大改动后检查 `## [Unreleased]` 是否有条目丢失（历史上丢过节奏游戏核心）。
- **应用内更新**：`AppReleaseSource` 的资产名前缀是 `piscesplayer-`，构建脚本输出名保持一致。
- **版权红线**：从专有 china-only 分支搬代码的禁令继续有效（见 AGENTS.md）；`ppchina` remote 不 push。

## 7. 验证记录（本分支）

| 项 | 结果 |
| --- | --- |
| 旧包名残留（源码与配置，排除 build 日志） | 0 处 |
| 默认英文资源汉字残留 | 0 处（4 条 rhythm 帮助文案已译英并经模拟器验证） |
| XML 合法性（全部改动 strings） | 通过 |
| 单元测试 `:app:testDebugUnitTest` | 全绿（含 M3U 新旧标记双用例、更新器版本解析用例） |
| androidTest 编译 `:app:compileDebugAndroidTestKotlin` | 通过 |
| baselineprofile 编译 `:baselineprofile:compileBenchmarkReleaseKotlin` | 通过 |
| release 构建 + 签名 | `:app:assembleRelease` 通过；`apksigner verify` = v2，`CN=pisces312` |
| PixelPlayer 字样清点 | 见 §8.2，当前产品面零残留 |
| 模拟器冒烟（安装、启动、谱面库、游戏进入） | 已过冒烟；发版前在 AVD pixel6 复验（清单见 HANDOVER §6） |

## 8. 最终结果（2026-11-10 收口，含原 HANDOVER 已完成部分）

### 8.1 完成清单

| 项 | 落点 / 结果 |
| --- | --- |
| 应用名 / 包名 / namespace 全量改 `com.pisces312.piscesplayer` | `app/build.gradle.kts`、`settings.gradle.kts`、Manifest 广播 action、`AppReleaseSource.PUBLISHED_PACKAGE`、`app/schemas/` 15 份 JSON、880 文件约 24,590 处 |
| 版本号 | `gradle.properties`：`APP_VERSION_NAME=1.0.0`（纯语义化，**无 `-pisces` 后缀**）、`APP_VERSION_CODE=1000001` |
| 新图标（四角透明） | adaptive icon + 各密度 legacy webp + fastlane；母版 `docs/images/piscesplayer-icon.png` |
| debug 图标 | 红底覆盖重新生成（`gen_debug_icons.py`），与 release 蓝/青底明确区分 |
| CHANGELOG | `[1.0.0] - 2026-11-10` 段（含此前静默丢失的节奏游戏核心条目） |
| 许可证 / 署名 | 新增 `NOTICE`；About 加 fork 维护者与上游链接；README 双署名 + fork 徽章 |
| 更新器版本解析 | `AlphaVersion.PATTERN` 接受纯 `X.Y.Z`（以及历史 `alpha` / `pisces` 预发布），`v1.0.0` 可解析 |
| M3U 播放列表标记 | 写入 `#PISCESPLAYER-PLAYLIST-ID:`，解析兼容旧 `#PIXELPLAYER-PLAYLIST-ID:`（旧 `.m3u` 备份可直接导入） |
| 内部代码标识改名 | `PiscesPlayerApplication` / `PiscesPlayerDatabase` / `PiscesPlayerTheme` / `Theme.PiscesPlayer` / widget 类 / MediaButtonReceiver / Room schema 目录 / 日志标签等 |
| GitHub 仓库改名 | `pisces312/PixelPlayerOSS` → `pisces312/PiscesPlayer`（gh 已执行，旧 URL 自动 301）；代码、测试、字符串、文档中的硬编码 URL 全部切换；About 描述与 topics 也已更新为 PiscesPlayer |
| README | 英文重写 + 新增 `README.zh-CN.md`，突出节奏游戏 / AI / 性能优化，双语互链 |
| 文档 | `docs/REBRAND.md`（本文）、`docs/RELEASE_ARCHIVE.md`（4 个旧 Release 正文归档）、`docs/releases/1.0.0.md`（Release 正文成品）、fastlane 文案 |
| 单测 / 编译 | `:app:testDebugUnitTest` 全绿；`:app:compileDebugAndroidTestKotlin`、`:baselineprofile:compileBenchmarkReleaseKotlin`、`:app:assembleRelease` 通过 |
| 模拟器冒烟 | 新旧包共存安装、10 步引导、曲库扫描、谱面生成（52 音符 / 103.7 BPM）、圆形遮罩图标正常 |
| 签名 APK | 见 §8.3，`apksigner verify` 通过（v2，`CN=pisces312`） |

### 8.2 PixelPlayer 字样清点（最终边界）

**已清除**：当前产品文本、代码类名（`PiscesPlayer*`）、主题、日志标签、当前文档、脚本工具、测试 fixture、CI workflow 标签、构建产物符号。

**保留**（四类，均有硬理由）：

1. **其他项目名 / 仓库 URL**：`PixelPlayerHQ/PixelPlayerOSS`（上游）、`pisces312/PixelPlayer`（china-only 专有分支）—— 引用其他项目的名字；本仓库 URL 已是 `github.com/pisces312/PiscesPlayer`。
2. **版权 / 署名 / 来源声明**：`NOTICE`、README 许可证块、`THIRD_PARTY_NOTICES.md`、各文件 SPDX 头（"Original work … china-only PixelPlayer fork"）、fastlane 简介署名、About「支持上游」区块、`metadata/com.lostf1sh.pixelplayeross.yml`（上游 F-Droid 元数据参考副本）。
3. **历史记录**（不改写）：`CHANGELOG.md` 0.1.0–0.4.3 条目、`docs/RELEASE_ARCHIVE.md`、`fastlane/changelogs/1–3.txt`、旧计划文档。
4. **兼容 / 持久化标识**（不改名，见 §1 决策表）：`pixelplayer_database`、`pixelplayer_local_art`（含旧 scheme `pixelplay_local_art`）、`pixelplayer_music_channel`、`pixelplayer_easter_egg`、`pixelplayer_car_lyric_title_force_a2dp`、`.pixelplayer-` M3U 文件名段、`#PIXELPLAYER-PLAYLIST-ID:` 旧标记（只读兼容）、`pixelplayer.*` Gradle 属性。

### 8.3 产物

```
dist/piscesplayer-arm64-v8a-1.0.0-release.apk
size   25,124,980 bytes
sha256 5fc1afbc36c0df9b7bd7037eee092c189ec3da2923bfabfeccec82e9bba17e81
```

`dist/` 不在 git 里（`.gitignore` 有 `*.apk`）。重编命令：

```powershell
.\gradlew.bat :app:assembleRelease -Ppixelplayer.enableAbiSplits=true
```

**校验签名（发版前必做，唯一判据）**：

```powershell
$env:JAVA_HOME='D:\dev\jdk-25'
& 'D:\dev\android_sdk\build-tools\37.0.0\apksigner.bat' verify --print-certs -v `
  'dist\piscesplayer-arm64-v8a-1.0.0-release.apk'
```

判据：`Verifies` + `v2 scheme: true` + `CN=pisces312`；`aapt2 dump badging` 核对 `versionName=1.0.0` / `versionCode=1000001` / `arm64-v8a`。

> SHA-256 与 `docs/releases/1.0.0.md` 中的值必须一致；任何重编之后都要重算并同步两处。

### 8.4 发布完成记录（2026-11-10）

`v1.0.0` 已发布：<https://github.com/pisces312/PiscesPlayer/releases/tag/v1.0.0>（正文即 `docs/releases/1.0.0.md`）。
上传的 APK 经下载回验：SHA-256 一致，`apksigner verify` = v2 + `CN=pisces312`。
4 个旧 `v0.4.x-pisces.1` Release 已删（正文归档于 `docs/RELEASE_ARCHIVE.md`），**对应 git tag 按计划保留**；
`dictionary-kuromoji-0.9.0` Release 保留（日语罗马音词典下载源）。

### 8.5 本机发版环境速查（自原 HANDOVER 收入）

```powershell
$env:JAVA_HOME='D:\dev\jdk-25'
$env:ANDROID_HOME='D:\dev\android_sdk'
$env:GRADLE_USER_HOME='D:\dev\.gradle'
# 签名变量不在 agent 进程里，从注册表读出再注入（不要打印值）
foreach ($n in 'KEY_STORE_PASSWORD','KEY_PASSWORD','KEY_ALIAS','KEY_STORE_LOCATION','KEY_STORE') {
  $v = (Get-ItemProperty 'HKCU:\Environment' -Name $n -ErrorAction SilentlyContinue).$n
  if ($v) { Set-Item -Path "env:$n" -Value $v }
}
```

git = `D:\dev\git\cmd\git.exe`，gh = `D:\dev\gh.exe`（已登录 pisces312）。
坑：后台 gradle 命令不传 timeout 会在 120s 被杀（症状停在 `Calculating task graph`），给满 600000。
