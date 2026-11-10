# PiscesPlayer 1.0.0 发版交接（Handover）

> **状态**：代码、文档、图标、版本号、签名 APK **全部就绪，尚未发布**。
> 当前卡点 = 等真机 review + 用户拍板发布。
> 背景与改动明细见 `docs/REBRAND.md`；历史版本 Release 正文见 `docs/RELEASE_ARCHIVE.md`；
> 本次 Release 正文成品见 `docs/releases/1.0.0.md`。
>
> 本文档在 `v1.0.0` 发布完成后可以删除。

---

## 0. 一句话状态

分支 `rebrand/piscesplayer`（7 个提交，**未 push**）。
GitHub 仓库已改名 `pisces312/PiscesPlayer`（旧 URL 自动 301），About 描述与 topics 已更新，README 已双语重写。
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
| 项目根 | `D:\3rd-party-projects\piscesplayer` |

坑：后台 gradle 命令不传 timeout 默认 120s 会被杀，症状是停在 `Calculating task graph`，给满 `600000`。

---

## 2. 分支与提交

```
2db3b6e5 docs: add the 1.0.0 release handover checklist
a8830012 docs: archive the pre-1.0.0 release notes
eb983e84 docs(release): document the rebrand and cut 1.0.0-pisces.1
8eab7a2c feat(icon): replace the launcher icon with the PiscesPlayer emblem
69b648c1 refactor(pkg)!: rename the app to PiscesPlayer under com.pisces312.piscesplayer
e62e2f6e docs: add rhythm game screenshots for the next release
```

> 1.0.0 收尾改动（版本后缀移除、`PixelPlayer` 内部标识符改名、M3U 新旧标记兼容、仓库 URL 切换、
> README 双语重写、debug 图标重生成）已作为**单个提交**入库 —— 均为名字改造，按用户决定合并提交。

- **不要**再合 `upstream`：`origin/main` 的 3 个提交（`70b04c27` / `cb08ec34` / `7e1cc720`）已经在分支祖先里（`git merge-base --is-ancestor origin/main HEAD` = true）。
- 但**本地 `main` 落后**于 `origin/main`，发布前先 `git fetch origin` 再更新本地 main，然后 `git merge --ff-only rebrand/piscesplayer`。

---

## 3. 已完成（无需重做）

已全部合并至 `docs/REBRAND.md` **§8 最终结果**（完成清单 §8.1、PixelPlayer 字样清点 §8.2、产物 §8.3），此处不再重复。

---

## 4. 产物（已构建）

产物：`dist/piscesplayer-arm64-v8a-1.0.0-release.apk`；size / SHA-256 与重编命令见 `docs/REBRAND.md` §8.3。
`dist/` 不在 git 里（`.gitignore` 有 `*.apk`）。

**校验签名（发版前必做，唯一判据）**：

```powershell
$env:JAVA_HOME='D:\dev\jdk-25'
& 'D:\dev\android_sdk\build-tools\37.0.0\apksigner.bat' verify --print-certs -v `
  'dist\piscesplayer-arm64-v8a-1.0.0-release.apk'
```

---

## 5. 待办

### P0 —— 等用户真机验收通过后执行

- [ ] **5.1 收用户反馈**：真机安装、图标、备份恢复迁移、节奏游戏、关于页、更新器（见第 6 节）。有问题就回 `rebrand/piscesplayer` 改，改完重编 + 重算 SHA-256，并同步 `docs/releases/1.0.0.md` 与 `docs/REBRAND.md` §8.3。
- [ ] **5.2 更新 main 并合并**

  ```bash
  D:/dev/git/cmd/git.exe fetch origin
  D:/dev/git/cmd/git.exe checkout main
  D:/dev/git/cmd/git.exe merge --ff-only origin/main
  D:/dev/git/cmd/git.exe merge --no-ff rebrand/piscesplayer -m "Merge branch 'rebrand/piscesplayer'"
  ```

- [ ] **5.3 打 tag**（annotated，tag 名必须 `v1.0.0`）

  ```bash
  D:/dev/git/cmd/git.exe tag -a v1.0.0 -m "PiscesPlayer 1.0.0"
  ```

- [ ] **5.4 push**：`git push origin main` + `git push origin v1.0.0`
      **`ppchina` remote 不要 push**（本地镜像）。
      push main 是必须的前置：Release 正文里的截图走 `raw.githubusercontent.com/.../main/docs/images/...`，main 上没有图正文就是空图。
- [ ] **5.5 建 Release**（正文文件已备好，直接用）

  ```bash
  D:/dev/gh.exe release create v1.0.0 `
    "D:/3rd-party-projects/piscesplayer/dist/piscesplayer-arm64-v8a-1.0.0-release.apk" `
    -R pisces312/PiscesPlayer `
    --title "PiscesPlayer 1.0.0" `
    --notes-file "D:/3rd-party-projects/piscesplayer/docs/releases/1.0.0.md" `
    --latest
  ```

  （若要先看效果：`gh release create --draft` 建草稿，确认后 `gh release edit v1.0.0 --draft=false`）

- [ ] **5.6 删旧 Release**（4 个，正文已归档进 `docs/RELEASE_ARCHIVE.md`）

  ```bash
  for t in v0.4.3-pisces.1 v0.4.2-pisces.1 v0.4.1-pisces.1 v0.4.0-pisces.1; do
    D:/dev/gh.exe release delete "$t" -R pisces312/PiscesPlayer --yes
  done
  ```

  ⚠️ **`dictionary-kuromoji-0.9.0` 绝对不要删**：那是日语罗马音词典的下载源，App 会直接拉这个 Release 的资产。
- [ ] **5.7 复核**：`gh release list -R pisces312/PiscesPlayer` 只剩 `v1.0.0` + 词典资产；再跑一次 5.4 的 `apksigner` 对**下载回来的** APK 验签（防止上传错文件）。

### P1 —— 收尾（可与 P0 同批）

- [ ] 删 `docs/HANDOVER-piscesplayer-1.0.0.md`（本文）；`docs/REBRAND.md` 保留。
- [ ] 旧 tag `v0.4.0~v0.4.3-pisces.1` 是否保留：删 Release 不影响 tag，建议**保留**（历史可追溯），已删的话需同步改 `docs/RELEASE_ARCHIVE.md` 里的链接说明。
- [x] `docs/RELEASE.md` 补本 fork 的 tag/资产命名（已含 `v<X.Y.Z>` 与 `piscesplayer-<abi>-<X.Y.Z>-release.apk`，示例 `1.0.0`；收尾大提交里完成）。
- [x] `AppReleaseSource.kt` 注释里还写着旧资产名 `pixelplayeross-<abi>-...`（已改为 `piscesplayer-<abi>-...`）。
- [ ] 清 `D:\Temp\pp-release\`（截图与临时脚本，可再生）。
- [x] **fastlane 与 README 截图换新**（2026-11-10 完成）：6 张真机截图（首页 AI / 正在播放 / 歌词 /
      播放列表 / 谱面派生 / 节奏游戏中）替换 `fastlane/.../images/phoneScreenshots/`（`1-home.jpg`…`6-rhythm-play.jpg`）
      与 `assets/screenshot-*.jpg`，README 双语引用同步；`fastlane/.../images/icon.png` 已是新图标，未动。

### P2 —— 待用户决策（不要擅自做）

- [x] **GitHub 仓库改名**（`PixelPlayerOSS` → `PiscesPlayer`）：已执行（2026-11-10），硬编码 URL 已全部切换
      （`AppReleaseSource.kt` 的 `PROJECT_URL` / `GITHUB_RELEASES_API`、词典下载、MusicBrainz UA、
      README 与 Release 正文的 `raw.githubusercontent.com/pisces312/PiscesPlayer/main/...`）；旧 URL 由 GitHub 自动 301。
- [x] 仓库 About 描述 / topics 改成 PiscesPlayer（2026-11-10 已执行：描述重写、topics 加 `piscesplayer` 等）。

---

## 6. 真机验收清单（用户侧）

- [ ] 与旧 PixelPlayerOSS 共存安装（两个图标同时在桌面）
- [ ] 图标：无白角、圆形/方形遮罩下都不裁鱼身
- [ ] 首次启动 10 步引导可走完（Finish 依赖精确闹钟权限；被禁用时执行 `adb shell appops set com.pisces312.piscesplayer SCHEDULE_EXACT_ALARM allow` 并退后台再回前台）
- [ ] 旧版 `设置 → 备份` 导出 → 新版恢复 → 曲库/播放列表/统计齐全
- [ ] 节奏游戏：任意歌曲生成谱面 → Easy/Normal/Hard 派生 → 播放逻辑切换 → 延迟校准
- [ ] 关于页：显示 `1.0.0`、commit、构建时间、维护者与上游链接
- [ ] 设置 → 更新：能查到（此时还没有 Release，应报「已是最新/无更新」而不是崩溃）
- [ ] 车机/蓝牙、歌词、FLAC float 输出（若方便）

---

## 7. 风险与注意

| 风险 | 说明 / 规避 |
|---|---|
| 更新器版本解析 | `AlphaVersion.PATTERN` 接受纯 `X.Y.Z`（以及历史 `alpha` / `pisces` 预发布），`v1.0.0` 可解析；**tag 不要写成别的格式**，否则更新器静默忽略 |
| Release 正文图片 404 | 截图必须先随 main 上去；本地 `docs/images/release-1.0.0-rhythm-*` 8 张已提交 |
| 上传错包 | `dist/` 里只有 1.0.0 一个 APK；上传后按 5.7 复验 SHA-256 |
| 误删词典 Release | 只删 `v0.4.x-pisces.1` 四个 tag 对应的 Release |
| 包名迁移残留 | 改动面 880 文件，若后续 `git merge upstream` 冲突量会较大；这是既定代价（与上游完全切割的取舍） |
| 版权红线 | 见 `AGENTS.md`；本项目 GPL-3.0-or-later，`NOTICE` + Release 正文都明确「基于 PixelPlayerOSS by @lostf1sh，上游对修改不负责任」 |

---

## 8. 素材位置速查

| 素材 | 路径 |
|---|---|
| Release 正文（成品，已入库） | `docs/releases/1.0.0.md` |
| CHANGELOG 头部草稿 | `D:\Temp\pp-release\changelog-head.md` |
| 英文界面截图（10 张） | `D:\Temp\pp-release\en-*.png`（未入库，正文用的是入库的 4 张） |
| 入库截图 | `docs/images/release-1.0.0-rhythm-{game-play.jpg,chart-library-en/zh,derive-en/zh,play-logic-en/zh}.png` |
| 图标脚本 | `D:\Temp\pp-release\make_icons3.py` |
| 改名脚本（包名/brand） | `D:\Temp\pp-release\rebrand_package.py`、`rebrand_brand.py` |
| 旧 Release 归档 | `docs/RELEASE_ARCHIVE.md`（正文 + 资产清单） |
| 发版操作手册 | `docs/RELEASE.md`、`docs/REBRAND.md` |
