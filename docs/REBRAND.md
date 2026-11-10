# PiscesPlayer Rebranding — 决策、迁移与发版记录

日期：2026-11-10 · 分支：`rebrand/piscesplayer` · 版本：`1.0.0-pisces.1`（versionCode `1000001`）

本文记录本 fork 从 PixelPlayerOSS 更名为 PiscesPlayer 的完整决策、改动范围、验证方式与发版计划，
供后续维护者（以及未来的自己）追溯「为什么这么改、改了哪些地方、漏改会怎样」。

## 1. 背景与决策

| 决策点 | 结论 | 理由 |
| --- | --- | --- |
| 应用名 | `PiscesPlayer`（所有 locale 统一） | 与 GitHub 账号 `pisces312` 一致，简短，与上游无字面重叠 |
| 包名（applicationId + namespace + 源码包） | `com.pisces312.piscesplayer`，与上游完全切割 | 用户明确选择彻底切割；一次性承担数据断点，避免以后再断一次 |
| GitHub 仓库名 | 保留 `pisces312/PixelPlayerOSS` | fork 身份标识；改名会使 raw 图片链接、`AppReleaseSource` 的 GitHub API URL 全部失效 |
| 版本号 | 从 `1.0.0-pisces.1` 重新起步 | 更名 + 新包名等价于新应用；`0.4.x` 系列留给上游名下的历史 |
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
- Beta 反馈、Changelog 弹窗的 GitHub 链接指向 `pisces312/PixelPlayerOSS`。
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

- `gradle.properties`：`APP_VERSION_NAME=1.0.0-pisces.1`，`APP_VERSION_CODE=1000001`
  （沿用 major×1,000,000 + minor×100,000 + patch×100 + build 的编码规则，0.4.3 → 400301 同源）。
- `CHANGELOG.md`：`[Unreleased]` 收口为 `[1.0.0-pisces.1] - 2026-11-10`，并补记历史上静默丢失的
  节奏游戏核心条目（DSP 生成、派生、Classic 冻结、入口、播放逻辑选择器）、AI 问歌/翻译/罗马音、
  MusicBrainz、Updates 屏、About 构建信息、更名本身。
- `ChangelogParserTest`：版本列表、日期列表、每版本 section 数（`3,3,3,3,4,5,4,5`）同步更新。

## 4. 用户数据迁移（重要）

新包名 = 全新应用。旧版（`com.lostf1sh.pixelplayeross`）数据不会自动迁移：

1. 旧版内 `Settings → Backup` 导出备份（偏好、播放列表、收藏、歌词、统计、应用状态）。
2. 安装 PiscesPlayer 1.0.0（可与旧版共存，签名不同互不覆盖）。
3. 新版内恢复备份；本地音乐文件本身不受影响。
4. 确认无误后卸载旧版。

Release 正文会写明以上步骤。旧版收不到应用内更新（包名不同），只能通过 Release 页/README 得知迁移。

## 5. 发版与旧 Release 清理计划

| 步骤 | 内容 | 状态 |
| --- | --- | --- |
| 1 | `rebrand/piscesplayer` 分支提交全部更名改动 | 待提交 |
| 2 | 合并回 `main`（fast-forward 或 merge commit） | 待用户确认 |
| 3 | `gradle.properties` 已是 1.0.0；签名构建（`KEY_*` 环境变量）+ `apksigner verify` | 待执行 |
| 4 | tag `v1.0.0-pisces.1`，push main + tag | 待用户确认 |
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
| 单元测试 `:app:testDebugUnitTest` | 见提交信息 / CI 记录 |
| 签名验证 | `apksigner verify` + `CN=pisces312`（发版时执行） |
| 模拟器冒烟（安装、启动、谱面库、游戏进入） | 发版前在 AVD pixel6 复验 |
