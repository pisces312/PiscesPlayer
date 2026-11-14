# 猜歌挑战（Music Quiz）设计与实施方案

- 日期：2026-11-14
- 状态：已完成（待真机验收）
- 所属分支：`feature/music-game-hub`
- 定位：游戏大厅中的第二款音乐小游戏，仿照经典 iPod Music Quiz 的核心体验。

## 1. 产品目标

播放用户自己曲库中的一段音乐，在倒计时结束前从五个歌名中选出正确答案。答题越快，得分越高。

首版目标是打开即玩、规则固定、反馈迅速，并与主播放器、播放队列和听歌统计完全隔离。首版不做复杂的选歌、难度设置、云端歌曲或联网排行。

## 2. 固定玩法

| 项目 | 规则 |
|---|---|
| 曲库范围 | 仅本地曲库 |
| 开局条件 | 至少 10 首符合要求的本地歌曲，且能组成 5 个不重复的歌名选项 |
| 歌曲要求 | 标题非空、时长至少 30 秒、具有可读取的本地 URI |
| 每局题数 | 固定 10 题 |
| 每题选项 | 5 个歌名：1 个正确答案、4 个干扰项 |
| 试听长度 | 10 秒 |
| 答题时间 | 与试听同步，共 10 秒 |
| 片段位置 | 在歌曲有效范围内随机选择，不固定从开头播放 |
| 歌曲重复 | 同一局内正确歌曲不重复 |
| 作答次数 | 每题只能作答一次，连续点击不重复计分 |
| 超时 | 本题得 0 分并揭示正确答案 |
| 重听/拖动 | 首版不提供，保持限时挑战规则 |
| 中途退出 | 暂停并确认；退出后不保存本局成绩 |

曲库不足时在准备页显示原因，不进入答题页面后再报错。

## 3. 计分与结果

答对时：

```text
本题得分 = 100 + round(900 × 剩余答题时间 / 总答题时间)
```

- 每题答对得 100～1000 分，答错或超时得 0 分，不倒扣。
- 一局理论最高 10000 分。
- 连对数用于 HUD 和最佳连对纪录，首版不叠加连对倍率。
- 只有完整完成一局才更新最高分与最佳连对。

结算数据：

- 总分及是否刷新纪录；
- 答对题数、正确率；
- 最长连对；
- 答对题目的平均用时；
- 每题答题明细。

## 4. 页面与交互

### 4.1 游戏大厅

在 `MusicGameHubScreen` 增加第二张卡片：

- 标题：猜歌挑战；
- 副标题：听 10 秒片段，从 5 个歌名中选出正确答案；
- 使用音符/问号风格图标，与节奏游戏区分；
- 点击进入独立猜歌页面，不经过节奏游戏选歌页。

大厅卡片可提取为内部通用组件，但首版不做大规模包结构重构。

### 4.2 准备页

显示规则、历史最高分、最佳连对和“开始挑战”按钮。曲库不足时直接显示空状态，不开放开始按钮。

首版不提供题数、片段长度等选项，保证最高分均基于同一规则。

### 4.3 答题页

按主流直板手机竖屏、参照机 411 × 914 dp 设计：

1. 顶部：返回、当前题号、暂停；
2. 状态区：当前分数、连对；
3. 中部：中性唱片/音浪视觉与倒计时；
4. 下方：五个纵向排列的大尺寸答案按钮。

防剧透要求：

- 作答前不显示封面、歌手、专辑和文件名；
- 不显示主播放器迷你条；
- 选项只显示歌名；
- 不通过 MediaSession 或系统通知暴露试听歌名。

### 4.4 作答反馈

点击后立即锁定本题并停止试听：

- 正确选项显示绿色；
- 选错时所选项显示红色，同时标出正确答案；
- 显示本题得分；
- 此时才显示封面、完整歌名和歌手；
- 反馈阶段提供“下一题”按钮，最后一题显示“查看结果”。

### 4.5 结算页

展示本局统计、答题明细、“再玩一次”和“返回大厅”。再玩一次使用新随机种子重新出题。

## 5. 出题算法

出题逻辑使用纯 Kotlin，不依赖 Android 或 Media3，以便 JVM 单元测试。

### 5.1 候选歌曲过滤

- 排除云端歌曲；
- 按歌曲 ID 去重；
- 排除空标题、短于 30 秒、无有效播放地址的歌曲；
- 曲库快照进入游戏后固定，不因答题期间的库更新改变本局题目；
- 播放时才发现损坏或不可解码的歌曲，进入运行时替换流程。

### 5.2 选项生成

- 正确答案从本局尚未使用的歌曲中随机抽取；
- 干扰项从其余歌曲中随机抽取四个；
- 标题规范化包括去首尾空白、合并连续空白和大小写无关比较；
- 同一题不能出现显示上等效的歌名；
- 同名不同歌曲不能同时成为本题选项；
- 选项生成后统一打乱，正确位置不固定；
- 题目进入答题状态后不再因重组改变。

### 5.3 片段位置

```text
clipLength = 10 秒
latestStart = 歌曲时长 - clipLength - 1 秒尾部分界
startMs = 在 0 到 latestStart 之间随机
```

首版不做整首音频分析，不依赖节奏谱面，也不要求先解码整首歌。

## 6. 状态机与计时

主要状态：

```text
Idle → Preparing → Playing → Revealing
                      ↑          |
                      |          ↓
                      |       Preparing / Finished
                      ↓
                    Paused
```

另有 Error 状态处理初始化失败或连续播放失败。

关键规则：

- Preparing 的加载、解码和缓冲时间不扣答题时间；
- 独立播放器真正开始播放后才开始倒计时；
- 使用单调时钟，不依赖系统时间；
- 得分使用点击事件时的精确时间，不使用 UI 倒计时取整值；
- 后台和暂停同时冻结音频与计时；
- 回到前台后由用户主动继续，不自动响起声音；
- 每次加载携带 roundId，忽略上一题迟到的回调；
- Playing 只接受一次答案，超时后的迟到点击直接忽略。

## 7. 独立播放与主播放器隔离

新增轻量 `MusicQuizAudioPlayer`，内部使用独立 Media3 ExoPlayer：

- 一局复用一个实例，每题设置带起止点的 MediaItem；
- 优先使用 `contentUriString`，为空时回退到本地文件路径；
- 开启音频焦点处理和拔耳机自动暂停；
- 不注册 MediaSession，不创建通知，不支持后台播放；
- 退出时停止并释放资源。

主播放器约定：

1. 进入游戏前记录主播放器是否正在播放；
2. 若正在播放，先暂停主播放器；
3. 猜歌全程不修改主队列、当前歌曲或播放进度；
4. 退出时先停止猜歌播放；
5. 只有进入前确实在播放、且当前仍暂停时才恢复；
6. 不经过正常听歌统计链路，试听不增加播放次数、最近播放或听歌时长。

## 8. 推荐代码结构

```text
data/musicquiz/
  MusicQuizModels.kt
  MusicQuizQuestionFactory.kt
  MusicQuizGameEngine.kt
  MusicQuizAudioPlayer.kt
  MusicQuizStatsRepository.kt

presentation/musicquiz/
  MusicQuizViewModel.kt
  MusicQuizScreen.kt
```

导航改动：

- `Screen` 增加 `MusicQuiz` 路由；
- `AppNavigation` 接入大厅卡片和猜歌页面；
- `MainActivity` 将猜歌路由加入隐藏底部导航的集合；
- 答题页不使用带迷你播放器的 `ScreenWrapper`；
- 共享 `PlayerViewModel` 由导航显式传入，不增加 `= hiltViewModel()` 默认值。

成绩存储使用独立 DataStore Repository：

- 不修改 Room 实体，不增加 schema 版本或 migration；
- 不属于设置开关，不注册设置搜索；
- 中英文资源同时补齐。

## 9. 异常与边界

- 某题加载失败：不计答错、不扣分，排除该歌曲并补一道同序号新题；
- 候选耗尽或连续失败：结束本局并显示可重试错误，不写最高分；
- 应用退到后台/锁屏：立即暂停，返回后显示继续界面；
- 耳机拔出/音频焦点丢失：暂停当前题，不继续倒计时；
- 答题恰逢超时：按单调时间戳判定，只接受一种结果；
- 快速连点/重组/配置变化：不能重复加分、跳题或保存纪录；
- 中途返回：先暂停并确认，确认后不得残留声音；
- 试听文件被删除：按播放失败处理，不让整局卡死。

## 10. 测试与验收

### 10.1 JVM 单元测试

- 曲库过滤与不足 10 首时的空状态；
- 五个选项唯一、必含正确答案、无同标题歧义；
- 固定随机种子下结果可复现；
- 同一局歌曲不重复、选项顺序稳定；
- 随机片段不越过歌曲末尾；
- 得分上下界、答错、超时和临界时间；
- 双击、迟到回调、暂停恢复；
- 最终统计与最高分只写入一次。

### 10.2 构建

```powershell
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:lintDebug
.\gradlew.bat :app:assembleRelease
```

### 10.3 真机验收

- 主队列和主播放器进度不变；
- 猜歌试听不进入最近播放和听歌统计；
- 无媒体通知和歌名泄露；
- 进入、退出、后台、耳机拔出时不会双重播放或残留声音；
- 十题流程、超时、错误替换、重开和最高分正常；
- 中英文界面及大厅返回链路正常。

界面改动的最终目视验收由用户在真机完成；模拟器只用于排查启动崩溃，不用于逐页抠布局。

## 11. 首版非目标

- 云端歌曲或在线流媒体；
- 自定义题数、选项数或试听长度；
- 联网排行榜、多人模式；
- 按歌手、收藏、专辑或最近播放筛选；
- 为选择活跃片段而分析整首音频；
- 恢复进程死亡后的半局游戏。

这些能力可在核心玩法验证后再扩展。

## 12. 第二期：结算页增强（2026-11-14 方案，待实现）

结算页目前只是成绩单：十行回顾不可点，底部只有「再来一局 / 返回大厅」。第二期把它升级为
**成果处置入口**——试听歌曲、分享成绩、归档进播放列表。三项需求与关键决策：

| # | 需求 | 决策 | 关键理由 |
|---|---|---|---|
| 1 | 点击回顾行播放歌曲 | 整曲试听走**游戏自己的 ExoPlayer**，不进主播放器 | 不替换用户原队列、不混入听歌统计、与退出恢复逻辑零冲突（§7 隔离原则） |
| 2 | 分享成绩 | 「分享截图」（成绩卡片 PNG 走系统分享面板）+「复制成绩文字」（剪贴板），两者都带项目链接 | 系统分享面板覆盖微信/QQ 等一切目标，无需平台 SDK |
| 3 | 创建/加入播放列表 | 两步：选歌 Sheet → 复用现有 `PlaylistBottomSheet`；新建列表名默认「猜歌游戏 + 时间戳」 | 复用 14 处调用的成熟组件，交互与曲库页一致 |
| 4 | 揭晓跳版 / 流转优化 | 「下一题」按钮常驻预留槽位（防跳版）；答对约 1.2s 自动下一题，答错/超时停留手动 | 动量与消化错误的平衡（Duolingo 同款模式） |

### 12.1 结算页布局

底部操作区 **2×2 网格**：每行两个半宽按钮，**每个按钮都带图标**（图标 + 文字）：

```
[↻  再来一局]        [⌂  返回大厅]        ← 再来一局 filled 主色，其余 outlined
[⊕  加入播放列表]    [↗  分享]            ← 页面最底部一行
```

- 再来一局：`Refresh` 图标，filled 主按钮（本行视觉重心）；
- 返回大厅：`Home` 图标，outlined；
- 加入播放列表：`PlaylistAdd` 图标，outlined；
- 分享：`Share` 图标，outlined，位于页面最底部（按需求原话「最下方」）。

回顾列表仍在操作区上方，行为见 12.2。

### 12.2 功能一：回顾行点击试听

**交互**

- 点击回顾行 = 从头播放该曲**整曲**（不限于 10 秒片段）；
- 再点正在播放的行 = 暂停 / 继续；
- 点其他行 = 立即切换到该曲（从头播），旧行指示复位；
- 播放到结尾自动复位，行指示消失；
- 试听只在结算态（`FINISHED`）可用，不影响答题、揭晓等状态。

**视觉**（与曲库歌曲行风格统一）

- 行左侧新增 40dp 圆角专辑封面（`albumArtUriString`，无封面回退音符图标占位）；
- 原轮次序号列（28dp）保留在封面左侧；
- 正在播放的行：封面上叠半透明遮罩 + `Pause` 图标；其余行封面无遮罩；
- 歌名 / 歌手 / 右侧结果文案（得分、用时、答错、超时）布局不变。

**播放通道决策**

试听复用游戏的独立 `MusicQuizAudioPlayer`（第二个 `playFull(track, token)` 入口，
不设 `ClippingConfiguration`），主播放器完全不参与：

- 不替换用户进入游戏前的播放队列（进主播放器播是破坏性操作）；
- 不与「退出时恢复主播放器」逻辑冲突（否则恢复动作会误停刚点的歌）；
- 不混入听歌统计与最近播放（试听≠听歌，与 §7 一致）；
- 无媒体通知、不泄露歌名（游戏结束后不再有防剧透需求，但保持行为一致）。

**实现要点**

- `MusicQuizAudioPlayer.playFull(track, token)`：全曲 MediaItem，无 clip 配置；
  现有 token 防串台机制照用；`onPlayerError` 回调区分预览与游戏场景；
- `MusicQuizViewModel` 新增 `togglePreview(trackId)` / `stopPreview()`，
  `MusicQuizUiState` 增加 `previewTrackId: String?` 与 `previewPaused: Boolean`；
- 停止时机：「再来一局」（`startGame`）、退出（`handleBack` / 确认退出 / `stopGame`）、
  `onDispose`、`onCleared`；
- 预览音频出错（文件被删/移动）：toast 提示无法播放，预览复位，不影响其他功能。

### 12.3 功能二：分享

**入口**：底部「分享」按钮 → `ModalBottomSheet`（与应用其他面板风格一致），两个选项：

| 选项 | 行为 |
|---|---|
| 分享截图 | 生成成绩卡片 PNG → 系统分享面板（`ACTION_SEND image/png`） |
| 复制成绩文字 | 成绩文案写入剪贴板 → toast「已复制成绩文字」 |

点按遮罩关闭，无其他按钮。

**成绩卡片（截图内容）**

专门的 `MusicQuizShareCard` composable，约 360dp 宽，内容自上而下：

1. 标题行：🏆 + 「PiscesPlayer · 猜歌挑战」；
2. 大号分数 + 「答对 8/10 · 最长连击 5 · 平均用时 3.2 秒」；新纪录时加「（新纪录！）」徽标；
3. 十首歌曲紧凑单行：✓/✗ + 「歌名 - 歌手」；
4. 页脚小字：`github.com/pisces312/PiscesPlayer`。

**截图实现**

- Compose `rememberGraphicsLayer` + `drawWithContent { record() }` 捕获；
  卡片组合在可视区外（横向 offset，不放进 `LazyColumn`，避免懒加载裁剪），
  `GraphicsLayer.toImageBitmap()` → PNG；
- 写入 `cacheDir/music_quiz_share/share-<时间戳>.png`（`cache-path` 已在 FileProvider 声明，
  节奏游戏诊断导出同款机制）；
- `FileProvider.getUriForFile(ctx, "${ctx.packageName}.provider", file)` +
  `ACTION_SEND` + `FLAG_GRANT_READ_URI_PERMISSION` + 系统分享面板；
- `EXTRA_TEXT` 附分享文案（含链接）；失败（IO/OOM/无接收方）toast 提示，不崩溃。

**文字分享内容（含链接）**

```
🎵 PiscesPlayer 猜歌挑战
得分 8650 · 答对 8/10 · 最长连击 5 · 平均用时 3.2s
来试试你能拿多少分！
https://github.com/pisces312/PiscesPlayer
```

新纪录时第一行追加「（新纪录！）」。文案构造抽成纯函数（`MusicQuizShareText`）便于单测。

**项目链接的三处落点**：卡片页脚、截图分享的 `EXTRA_TEXT`、复制文案末行。
链接统一取 `https://github.com/pisces312/PiscesPlayer`（与 About 页 `SourceRepoUrl` 一致）。

### 12.4 功能三：加入播放列表

**两步流程**

1. **选歌 Sheet**（新增 `MusicQuizSongSelectSheet`）：
   - 标题「选择歌曲」+ 副标题「已选 N/10」，右上「全选 / 清空」文字按钮；
   - 10 行 checkbox（40dp 封面 + 歌名/歌手 + Checkbox），**默认全选**；
   - FAB「下一步」，选中 ≥1 才可用；
2. **PlaylistBottomSheet**（现有组件，全仓 14 处调用不变）：
   - `songs` 传选中歌曲（`MusicQuizTrack → Song.emptySong().copy(id, title, artist)` 最小映射，
     播放列表操作只用 `id`，与曲库同 id 空间）；
   - 既有能力直接复用：多选目标列表、搜索过滤、智能列表排除、保存 FAB、完成 toast。

**新建列表默认名**

- 「猜歌游戏 yyyyMMdd-HHmm」（打开选歌 Sheet 时刻），如 `猜歌游戏 20261114-0913`；
- 落点：`CreatePlaylistDialogRedesigned` 加可选参数 `initialName: String = ""`，
  `PlaylistBottomSheet` 加可选参数 `createDialogInitialName: String = ""` 透传——
  默认值保证现有调用零改动。

**完成反馈**：沿用 `playlist_created_songs_added_toast` / `playlist_songs_added_toast`
（走 `playerViewModel.sendToast` 统一 toast 通道）。

### 12.5 功能四：作答流转优化（答题页）

两个独立改动，都发生在答题页：

**（a）揭晓按钮预留槽位（防跳版）**

现在「下一题 / 完成」按钮在揭晓后才追加到选项列表下方（`if (revealing)`），整个界面会跳一下。
改为：底部**常驻**一个整宽按钮槽位（固定高度 = 按钮高度 + 间距），未揭晓时槽位透明，
揭晓时按钮原地淡入（`AnimatedVisibility` / alpha 过渡）。无论是否揭晓，选项区位置不动。

**（b）答对自动进入下一题**

| 揭晓结果 | 流转 | 理由 |
|---|---|---|
| 答对 | 高亮正确项 + 得分提示，**约 1.2 秒后自动进入下一题** | 此时揭晓页信息价值低，动量感最重要（Duolingo 同款模式） |
| 答错 / 超时 | **停留揭晓**（正确项绿色 + 所选项红色），等用户点「下一题」 | 用户需要时间消化「原来是这首」，自动跳有惩罚感；按钮已在预留槽位内，不跳版 |

- 「下一题」按钮在自动跳的 1.2 秒内仍可点，点击立即前进并取消自动计时；
- 第 10 题同规则：答对自动 / 答错手动，之后进入结算页；
- 实现：ViewModel 侧 `revealAutoAdvanceJob`（`viewModelScope` 延时调 `nextRound()`），
  在 `nextRound()` / `startGame()` / `stopGame()` 中取消；确认退出必走 `stopGame()`，
  不会残留计时；
- 延时抽成常量（暂定 1200ms，真机试玩后可调）。

> 备选：答错 / 超时也自动跳（如 3 秒），按钮仅作提前跳过——完全免按键，但答错时观感偏惩罚，
> 不作默认。若想要「全程自动」，改一处延时分支即可。

### 12.6 边界与异常

- 预览播放中打开分享 / 播放列表面板：**不打断**，试听继续；「再来一局」和退出才停止；
- 试听到结尾：预览复位，行指示消失；
- 歌曲文件失效：toast「无法播放该歌曲」，预览复位；
- 截图失败（IO/OOM）：toast 失败文案，可改用文字分享兜底；
- 播放列表目标为空：`PlaylistBottomSheet` 现有行为（仍可走新建列表），不加新兜底；
- 屏幕旋转锁定竖屏；`onDispose` 停预览（配置变更下预览不恢复，可接受）。

### 12.7 代码落点

| 文件 | 改动 |
|---|---|
| `data/musicquiz/MusicQuizAudioPlayer.kt` | 新增 `playFull(track, token)` 全曲入口 |
| `data/musicquiz/MusicQuizShareText.kt`（新增） | 分享文案纯函数构造 |
| `presentation/musicquiz/MusicQuizViewModel.kt` | `togglePreview` / `stopPreview`、UI 状态加预览字段、`revealAutoAdvanceJob` 自动跳 |
| `presentation/musicquiz/MusicQuizScreen.kt` | 结算页 2×2 图标按钮组、行点击、分享面板、截图捕获、答题页按钮预留槽位 |
| `presentation/musicquiz/MusicQuizSongSelectSheet.kt`（新增） | 选歌 Sheet |
| `presentation/musicquiz/MusicQuizShareCard.kt`（新增） | 成绩卡片 composable |
| `presentation/components/PlaylistBottomSheet.kt` | 加可选 `createDialogInitialName` |
| `presentation/components/PlaylistContainer.kt` | `CreatePlaylistDialogRedesigned` 加可选 `initialName` |
| `res/values/strings.xml` + `values-zh-rCN/strings.xml` | `music_quiz_share_*` / `music_quiz_playlist_*` 双语 |

### 12.8 测试与验收

**JVM 单元测试**

- `MusicQuizShareText`：得分文案含链接、新纪录措辞、平均用时为 0 的措辞；
- 默认名格式：`猜歌游戏 yyyyMMdd-HHmm`；
- ViewModel 预览状态机：切换 / 暂停 / `startGame` 与 `stopGame` 时停止；
- 自动跳计时：答对延时后进下一题、`nextRound()` 提前触发时取消计时（`kotlinx-coroutines-test`）。

**构建**：`assembleRelease` + `testDebugUnitTest`；`lintDebug` 不引入新错误（既有 8 个不计）。

**真机验收**

- 答题页揭晓不跳版（按钮原地淡入，选项区不动）；
- 答对自动进入下一题，答错 / 超时停留等待手动；
- 回顾行点击试听、暂停、切歌正常；退出 / 再来一局后无残留声音；
- 试听不进听歌统计与最近播放，主队列不变；
- 截图分享：出图完整（分数 + 十首歌 + 页脚链接），能发到微信等目标；
- 复制文字：粘贴内容含项目链接；
- 播放列表：新建默认名正确、加歌数量正确；加入已有列表正确；智能列表不出现在目标里；
- 底部按钮完整可点（配合 mini player 隐藏修复）。

### 12.9 第二期非目标

- 分享图自定义水印 / 昵称 / 皮肤；
- 微信、QQ 等平台 SDK 一键直发（系统分享面板已覆盖）；
- 试听带进度条、通知栏控制（试听定位为快速回放）；
- 写入云端歌单（Navidrome / Jellyfin），仅本地播放列表；
- 结算页内直接跳转主播放器播放整曲。
