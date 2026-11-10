# PiscesPlayer 1.0.0 发版交接（Handover）

> **状态**：代码、文档、图标、版本号、签名 APK **全部就绪，尚未发布**。
> 当前卡点 = 等真机 review + 用户拍板发布。
> 背景与改动明细见 `docs/REBRAND.md`；历史版本 Release 正文见 `docs/RELEASE_ARCHIVE.md`；
> 本次 Release 正文成品见 `docs/releases/1.0.0-pisces.1.md`。
>
> 本文档在 `v1.0.0-pisces.1` 发布完成后可以删除。

---

## 0. 一句话状态

分支 `rebrand/piscesplayer`（4 个提交，工作区干净，**未 push**）。
剩余工作是纯发版链路：**真机验收 → 合并 main → 打 tag → push → 建 Release → 删 4 个旧 Release**。

---

## 1. 环境（本机 Windows，agent 的 PowerShell/Bash）

```powershell
$env:JAVA_HOME='D:\dev\jdk-25'
$env:ANDROID_HOME='D:\dev\android_sdk'
$env:GRADLE_USER_HOME='D:\dev\.gradle'

# 签名变量不在 agent 进程里，必须从注册表读出来再注入（不要打印值）
foreach ($n in 'KEY_STORE_PASSWORD','KEY_PASSWORD','KEY_ALIAS','KEY_STORE_LOCATION','KEY_STORE') {
  $v = (Get-ItemProperty 'HKCU:\Environment' -Name $n -ErrorAction SilentlyContinue).$n
  if ($v) { Set-Item -Path "env:$n" -Value $v }
}
```

| 工具 | 绝对路径 / 备注 |
|---|---|
| git | `D:\dev\git\cmd\git.exe`（不在 PATH） |
| gh | `D:\dev\gh.exe`（已登录 pisces312） |
| apksigner | `D:\dev\android_sdk\build-tools\37.0.0\apksigner.bat`（同一条命令里必须有 `JAVA_HOME`） |
| 项目根 | `D:\3rd-party-projects\PixelPlayerOSS` |

坑：后台 gradle 命令不传 timeout 默认 120s 会被杀，症状是停在 `Calculating task graph`，给满 `600000`。

---

## 2. 分支与提交

```
a8830012 docs: archive the pre-1.0.0 release notes
eb983e84 docs(release): document the rebrand and cut 1.0.0-pisces.1
8eab7a2c feat(icon): replace the launcher icon with the PiscesPlayer emblem
69b648c1 refactor(pkg)!: rename the app to PiscesPlayer under com.pisces312.piscesplayer
```

- **不要**再合 `upstream`：`origin/main` 的 3 个提交（`70b04c27` / `cb08ec34` / `7e1cc720`）已经在分支祖先里（`git merge-base --is-ancestor origin/main HEAD` = true）。
- 但**本地 `main` 落后**于 `origin/main`，发布前先 `git fetch origin` 再更新本地 main，然后 `git merge --ff-only rebrand/piscesplayer`。

---

## 3. 已完成（无需重做）

| 项 | 落点 |
|---|---|
| 应用名 / 包名 / namespace 全量改 `com.pisces312.piscesplayer` | `app/build.gradle.kts`（313/355 行）、`settings.gradle.kts`、Manifest 广播 action、`AppReleaseSource.PUBLISHED_PACKAGE`、`app/schemas/` 15 份 JSON、880 文件 24590 处 |
| 版本号 | `gradle.properties`：`APP_VERSION_NAME=1.0.0-pisces.1`、`APP_VERSION_CODE=1000001` |
| 新图标（四角透明） | adaptive icon + 各密度 legacy webp + fastlane；母版 `docs/images/piscesplayer-icon.png`，脚本 `D:\Temp\pp-release\make_icons3.py` |
| CHANGELOG | `[1.0.0-pisces.1] - 2026-11-10` 段（含此前静默丢失的节奏游戏核心条目） |
| 许可证 / 署名 | 新增 `NOTICE`；`AboutScreen.kt` 加 fork 维护者、本仓库与上游仓库链接；README 双署名 + fork 徽章 |
| 文档 | `docs/REBRAND.md`（改造清单）、`docs/RELEASE_ARCHIVE.md`（4 个旧 Release 全量正文+资产清单）、`README.md` 重写、fastlane 文案 |
| 单测 | `:app:testDebugUnitTest` 全绿（34 任务）；`ChangelogParserTest` 已同步新版本 |
| 模拟器冒烟 | 新旧包共存安装、10 步引导、曲库扫描、谱面生成（52 音符 / 103.7 BPM）、圆形遮罩图标正常 |
| 签名 APK | 见下节，`apksigner verify` 通过（v2，`CN=pisces312`） |

---

## 4. 产物（已构建，可直接上传，无需重编）

```
dist/piscesplayer-arm64-v8a-1.0.0-pisces.1-release.apk
size   25,124,978 bytes
sha256 25c1c64f024d5fdc3c86951d6fd640db62912d9025d402b483c281761215be54
```

`dist/` 不在 git 里（`.gitignore` 有 `*.apk`）。若 APK 丢失，重编命令：

```powershell
.\gradlew.bat :app:assembleRelease -Ppixelplayer.enableAbiSplits=true
```

**校验签名（发版前必做，唯一判据）**：

```powershell
$env:JAVA_HOME='D:\dev\jdk-25'
& 'D:\dev\android_sdk\build-tools\37.0.0\apksigner.bat' verify --print-certs -v `
  'dist\piscesplayer-arm64-v8a-1.0.0-pisces.1-release.apk'
```

---

## 5. 待办

### P0 —— 等用户真机验收通过后执行

- [ ] **5.1 收用户反馈**：真机安装、图标、备份恢复迁移、节奏游戏、关于页、更新器（见第 6 节）。有问题就回 `rebrand/piscesplayer` 改，改完重编 + 重算 SHA-256，并同步 `docs/releases/1.0.0-pisces.1.md` 与本文第 4 节。
- [ ] **5.2 更新 main 并合并**

  ```bash
  D:/dev/git/cmd/git.exe fetch origin
  D:/dev/git/cmd/git.exe checkout main
  D:/dev/git/cmd/git.exe merge --ff-only origin/main
  D:/dev/git/cmd/git.exe merge --no-ff rebrand/piscesplayer -m "Merge branch 'rebrand/piscesplayer'"
  ```

- [ ] **5.3 打 tag**（annotated，tag 名必须 `v1.0.0-pisces.1`）

  ```bash
  D:/dev/git/cmd/git.exe tag -a v1.0.0-pisces.1 -m "PiscesPlayer 1.0.0-pisces.1"
  ```

- [ ] **5.4 push**：`git push origin main` + `git push origin v1.0.0-pisces.1`
      **`ppchina` remote 不要 push**（本地镜像）。
      push main 是必须的前置：Release 正文里的截图走 `raw.githubusercontent.com/.../main/docs/images/...`，main 上没有图正文就是空图。
- [ ] **5.5 建 Release**（正文文件已备好，直接用）

  ```bash
  D:/dev/gh.exe release create v1.0.0-pisces.1 `
    "D:/3rd-party-projects/PixelPlayerOSS/dist/piscesplayer-arm64-v8a-1.0.0-pisces.1-release.apk" `
    -R pisces312/PixelPlayerOSS `
    --title "PiscesPlayer 1.0.0-pisces.1" `
    --notes-file "D:/3rd-party-projects/PixelPlayerOSS/docs/releases/1.0.0-pisces.1.md" `
    --latest
  ```

  （若要先看效果：`gh release create --draft` 建草稿，确认后 `gh release edit v1.0.0-pisces.1 --draft=false`）

- [ ] **5.6 删旧 Release**（4 个，正文已归档进 `docs/RELEASE_ARCHIVE.md`）

  ```bash
  for t in v0.4.3-pisces.1 v0.4.2-pisces.1 v0.4.1-pisces.1 v0.4.0-pisces.1; do
    D:/dev/gh.exe release delete "$t" -R pisces312/PixelPlayerOSS --yes
  done
  ```

  ⚠️ **`dictionary-kuromoji-0.9.0` 绝对不要删**：那是日语罗马音词典的下载源，App 会直接拉这个 Release 的资产。
- [ ] **5.7 复核**：`gh release list -R pisces312/PixelPlayerOSS` 只剩 `v1.0.0-pisces.1` + 词典资产；再跑一次 5.4 的 `apksigner` 对**下载回来的** APK 验签（防止上传错文件）。

### P1 —— 收尾（可与 P0 同批）

- [ ] 删 `docs/HANDOVER-piscesplayer-1.0.0.md`（本文）；`docs/REBRAND.md` 保留。
- [ ] 旧 tag `v0.4.0~v0.4.3-pisces.1` 是否保留：删 Release 不影响 tag，建议**保留**（历史可追溯），已删的话需同步改 `docs/RELEASE_ARCHIVE.md` 里的链接说明。
- [ ] `docs/RELEASE.md` 里仍是上游口吻的示例版本（`0.1.0`）与 alpha 命名，有空时补一段「本 fork 的 tag/资产命名 `piscesplayer-<abi>-<version>-release.apk`」。
- [ ] `AppReleaseSource.kt` 注释里还写着旧资产名 `pixelplayeross-<abi>-...`（逻辑不用改，注释过期，顺手改）。
- [ ] 清 `D:\Temp\pp-release\`（截图与临时脚本，可再生）。

### P2 —— 待用户决策（不要擅自做）

- [ ] **GitHub 仓库是否改名**（`PixelPlayerOSS` → `PiscesPlayer`）。改名会动三处硬编码：
      `AppReleaseSource.kt:47`（`PROJECT_URL`）、`AppReleaseSource.kt:52`（`GITHUB_RELEASES_API`）、
      Release 正文与 README 里的 `raw.githubusercontent.com/pisces312/PixelPlayerOSS/main/...`。
      不改也完全能用（App 名与包名已独立），建议先不改。
- [ ] 仓库 About 描述 / topics 是否改成 PiscesPlayer（不影响功能）。

---

## 6. 真机验收清单（用户侧）

- [ ] 与旧 PixelPlayerOSS 共存安装（两个图标同时在桌面）
- [ ] 图标：无白角、圆形/方形遮罩下都不裁鱼身
- [ ] 首次启动 10 步引导可走完（Finish 依赖精确闹钟权限；被禁用时执行 `adb shell appops set com.pisces312.piscesplayer SCHEDULE_EXACT_ALARM allow` 并退后台再回前台）
- [ ] 旧版 `设置 → 备份` 导出 → 新版恢复 → 曲库/播放列表/统计齐全
- [ ] 节奏游戏：任意歌曲生成谱面 → Easy/Normal/Hard 派生 → 播放逻辑切换 → 延迟校准
- [ ] 关于页：显示 `1.0.0-pisces.1`、commit、构建时间、维护者与上游链接
- [ ] 设置 → 更新：能查到（此时还没有 Release，应报「已是最新/无更新」而不是崩溃）
- [ ] 车机/蓝牙、歌词、FLAC float 输出（若方便）

---

## 7. 风险与注意

| 风险 | 说明 / 规避 |
|---|---|
| 更新器版本解析 | `AlphaVersion.PATTERN` 已含 `(?:alpha\|pisces)`，`v1.0.0-pisces.1` 可解析；**tag 不要写成别的格式**，否则更新器静默忽略 |
| Release 正文图片 404 | 截图必须先随 main 上去；本地 `docs/images/release-1.0.0-rhythm-*` 8 张已提交 |
| 上传错包 | `dist/` 里只有 1.0.0 一个 APK；上传后按 5.7 复验 SHA-256 |
| 误删词典 Release | 只删 `v0.4.x-pisces.1` 四个 tag 对应的 Release |
| 包名迁移残留 | 改动面 880 文件，若后续 `git merge upstream` 冲突量会较大；这是既定代价（与上游完全切割的取舍） |
| 版权红线 | 见 `AGENTS.md`；本项目 GPL-3.0-or-later，`NOTICE` + Release 正文都明确「基于 PixelPlayerOSS by @lostf1sh，上游对修改不负责任」 |

---

## 8. 素材位置速查

| 素材 | 路径 |
|---|---|
| Release 正文（成品，已入库） | `docs/releases/1.0.0-pisces.1.md` |
| CHANGELOG 头部草稿 | `D:\Temp\pp-release\changelog-head.md` |
| 英文界面截图（10 张） | `D:\Temp\pp-release\en-*.png`（未入库，正文用的是入库的 4 张） |
| 入库截图 | `docs/images/release-1.0.0-rhythm-{game-play.jpg,chart-library-en/zh,derive-en/zh,play-logic-en/zh}.png` |
| 图标脚本 | `D:\Temp\pp-release\make_icons3.py` |
| 改名脚本（包名/brand） | `D:\Temp\pp-release\rebrand_package.py`、`rebrand_brand.py` |
| 旧 Release 归档 | `docs/RELEASE_ARCHIVE.md`（正文 + 资产清单） |
| 发版操作手册 | `docs/RELEASE.md`、`docs/REBRAND.md` |
