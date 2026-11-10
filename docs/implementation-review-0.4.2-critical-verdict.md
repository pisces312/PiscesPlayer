# 对 `implementation-review-0.4.2.md` 六条 Critical 的核实与修复方案

- **核实日期**：2026-09-16
- **被核实文档**：`docs/implementation-review-0.4.2.md`（基线 `0.4.2-pisces.1` / Room v11）
- **方法**：逐条读源码定位 → 反查调用点/注解确认可达性 → 实测 Gradle 依赖解析（C5）→ 与 AGP 官方 release notes 对照
- **结论摘要**：**成立 3 条（C1/C2/C3）· 部分成立 1 条（C4）· 诊断错误 1 条（C5）· 定级过高 1 条（C6）**

---

## 0. 总览

| 编号 | 原报告结论 | 核实结论 | 定级调整 |
|---|---|---|---|
| **C1** | 13 个 `@Singleton` StateHolder 缺 owner 校验 | **成立，但清单不准且严重性被低估** | Critical **保留**（理由变了） |
| **C2** | AI API Key 明文存 DataStore | **完全成立**，位置准确 | Critical 保留 |
| **C3** | 备份默认明文导出 API Key | **成立，且比报告描述的更严重** | Critical 保留 |
| **C4** | 全局允许明文 + 信任用户 CA | **事实成立，定性偏高，且给出的修法不可实现** | Critical → **High** |
| **C5** | `useConstraints=false` 可能使安全钉版本失效 | **不成立**：核心因果链错误，实测约束本来就没生效 | Critical → **Low（死配置/维护债）** |
| **C6** | `LyricsStateHolder` 的 collector 不落 Job | **事实成立，但定级过高**；其真实危害来自 C1 而非 Job | Critical → **Low** |

一句话概括错在哪：**C1 的定性方向对但证据链是假想（其实已有现成复现路径）；C5 把 AGP 自生成约束当成用户手写约束；C6 把 C1 的症状记到了 Job 头上。**

### 0.1 分组与处理顺序（2026-09-16 用户指示）

`C2` / `C3` / `C4` 指向**同一件事：凭据在静态存储与网络传输上的暴露面**，归为一组，**本轮不动代码**，待 G1 落地后单独一轮处理。

| 组 | 条目 | 共同主题 | 本轮处置 |
|---|---|---|---|
| **G1 · 状态所有权** | C1 | `@Singleton` holder 与 Activity 作用域 VM 的生命周期错配 | **本轮处理**（§1.3，推荐 A1-j） |
| **G2 · 凭据与明文传输** | C2 / C3 / C4 | API Key 明文落盘 → 明文随备份外流 → 明文信道的网络配置 | **延后**（依赖链 H1 → C2 → C3） |
| **G3 · 死配置** | C5 | 手写依赖约束从未生效 + 披露文档不实 | 本轮可顺手清（纯删除） |
| **G4 · 低危** | C6 | `LyricsStateHolder` collector 不落 Job | 并入 G1 的同一提交 |

---

## C1 · StateHolder 所有权校验 —— 成立，但清单与理由都需要重写

### 1.1 报告错在哪

**(a) 清单不准。** 报告把 `QueueUndoStateHolder.kt:96`、`PlaylistDismissUndoStateHolder.kt:160` 也列进来了，但这两个类是 **`@ViewModelScoped`**，不是 `@Singleton`：

```
QueueUndoStateHolder.kt:13        @ViewModelScoped
PlaylistDismissUndoStateHolder.kt:19  @ViewModelScoped
```

它们随 VM 生死、天生没有跨 owner 问题，**不属于 C1**。反过来，报告漏了同在 `PlayerViewModel.init` 里初始化的 `ListeningStatsTracker`（`@Singleton`，`initialize(coroutineScope)` + 裸 `onCleared()`，`PlayerViewModel.kt:833` / `:4101`）。

按「`@Singleton` 且实现 `initialize`/`onCleared` 且无 owner 守卫」精确统计，受影响的是 **8 个**：

| 类 | 协议实现 | 无守卫的后果 |
|---|---|---|
| `PlaybackStateHolder` | `initialize(scope)` / `onCleared()` | `scope=null` → 播放位置、队列快照全停 |
| `LibraryStateHolder` | `initialize(scope)` / `onCleared()` | 排序偏好/存储过滤加载停摆 |
| `LyricsStateHolder` | `initialize(scope,cb,state)` / `onCleared()` | scope 被换 + `loadCallback=null` → 歌词加载静默失效 |
| `ThemeStateHolder` | `initialize(scope)` / `onCleared()` | 专辑封面取色 collector 被取消 |
| `SleepTimerStateHolder` | `initialize(scope,…)` / `onCleared()` | 定时器回调/提示 emitter 失效 |
| `ConnectivityStateHolder` | `initialize()` / `onCleared()` | 注销系统 callback + `isInitialized=false` → 连通性监控永久失效（原 H5） |
| `DailyMixStateHolder` | `initialize(scope)` / `onCleared()` | 每日混音刷新停摆 |
| `ListeningStatsTracker` | `initialize(scope)` / `onCleared()` | 听歌统计落盘链路断 |

`MultiSelectionStateHolder` / `QueueStateHolder` / `PlaylistSelectionStateHolder` 虽也是 `@Singleton`，但没有 `initialize`/`onCleared`，**不受影响**。

**(b) 更关键：这不是「一旦出现第二个实例」的假想风险，第二个实例现在就存在。**

`ExternalPlayerActivity.kt:33`：

```kotlin
class ExternalPlayerActivity : ComponentActivity() {
    private val playerViewModel: PlayerViewModel by viewModels()   // ← 第二个 PlayerViewModel
```

它与 `MainActivity.kt:177` 的实例**互不相同**，但在同一进程内共享同一批 `@Singleton` holder。`PlayerViewModel.init`（`:834-837`）会无条件调用各 holder 的 `initialize`，`onCleared()`（`:4100-4110`）会无条件调用各 holder 的 `onCleared()`。

报告把风险写成「导航栈级 `hiltViewModel()` 残留、新增屏幕漏传」——而 `grep 'PlayerViewModel = hiltViewModel()'` 实测为 **0**，报告的正面确认里自己也写了这一点。真正的问题被漏掉了。

### 1.2 已验证的复现路径

**顺序 A —— 应用已在后台运行（最常见）**

1. 应用运行中，`MainActivity` 的 VM_M 持有全部 holder；
2. 从文件管理器点开一个音频文件 → `ExternalPlayerActivity` 启动（`exported=true`，`VIEW` + `audio/*` + `scheme=content`，`AndroidManifest.xml:90-104`）→ VM_E 创建，各 holder `scope = viewModelScope_E`（**被改写**）；
3. 点「打开完整播放器」→ `MainActivity` 置前，`FLAG_ACTIVITY_CLEAR_TOP|SINGLE_TOP` → 其 VM_M 仍存活，但 holder 已挂在 VM_E 的 scope 上；
4. `ExternalPlayerActivity.finish()`（`ExternalPlayerActivity.kt:103`）→ VM_E.`onCleared()` → **8 个 holder 全部被拆**（`scope=null`、job cancel、`ConnectivityStateHolder` 注销系统 callback）；
5. 由于 VM_M 早已跑完 `init`，**没有第二次 `initialize`**。此后整个进程生命周期内：播放位置/歌词/主题取色/连通性/睡眠定时器/听歌统计全部静默失效，不崩溃、无异常日志。

**顺序 B —— 进程冷启先由 `ExternalPlayerActivity` 进入**

1. 冷启动，VM_E 成为「首个 owner」；
2. 点「打开完整播放器」→ `MainActivity` 创建 VM_M → 即使加了 owner 协议，VM_M 的 `initialize` 也会被「首个 owner 胜出」挡掉；
3. VM_E `finish()` → `onCleared(VM_E)` owner 匹配 → 照样拆掉 holder。

**这条例外必须写进方案**：`SearchStateHolder` 现有的「首个 owner 胜出」只能挡住「后来者接管」，挡不住「先到者恰好是短命 VM」。**只回填 owner 协议修不了顺序 B。**

### 1.3 修复方案

#### 方案 A（推荐）· 消除第二 owner + owner 协议纵深防御

**A1 · 让 `ExternalPlayerActivity` 不再创建 `PlayerViewModel`（治本，必须做）**

关键前提（**2026-09-16 补充实测，修正本节初版判断**）：

`MainActivity` 有同一套**处理代码**（`MainActivity.kt:429-443`），但**没有对应的 intent-filter**：

```kotlin
intent.action == Intent.ACTION_VIEW && intent.data != null -> playerViewModel.playExternalUri(uri)
intent.action == Intent.ACTION_SEND && intent.type?.startsWith("audio/") == true -> playerViewModel.playExternalUri(uri)
```

manifest 里 `MainActivity` 只声明了 `MAIN/LAUNCHER`、`MUSIC_PLAYER`、`OPEN_PLAYER`；`VIEW` + `audio/*` + `scheme=content` 只挂在 `ExternalPlayerActivity` 上（`exported=true`、`launchMode=singleTop`、`excludeFromRecents=true`）。两个推论：

1. 上面两段 `MainActivity` 代码目前是**死代码**（无隐式 intent 可命中，全仓亦无显式 component 调用）；
2. `ExternalPlayerActivity` 是**外部音频的唯一入口** ⇒ 任何「消除它」的方案必须**同时把 intent-filter 迁到 `MainActivity`**，否则「从文件管理器打开音频」这个能力整体消失。

**关键前提 2：`Quick play` 是刻意的产品设计，不是随手加的入口**

这不是推测，四条证据互相印证：

| 证据 | 位置 |
|---|---|
| 队列名文案 **"Quick play"**；唯一深入出口 **"Open full player"** | `strings.xml:89,91` |
| 半透明 Activity（`windowIsTranslucent` + 透明 bg）+ 底部 sheet + scrim 点外关闭 | `themes.xml:9-17`、`ExternalPlayerOverlay.kt:118-158` |
| **只读 MediaStore、不写任何 DAO**（全文件无 `Dao/insert/upsert`）⇒ 不进曲库 | `ExternalMediaStateHolder.buildExternalSongFromUri` |
| `excludeFromRecents=true` ⇒ 不进最近任务；`finish()` 后回到文件管理器 | `AndroidManifest.xml` |

⇒ 这是**两段式交互**：轻量试听（不离开文件管理器、不污染曲库、不留痕）→ 确认是对的歌再「Open full player」。因此 `A1-a′` **不再是「UX 略有退化」，而是直接抹掉这个设计**：scrim 关闭后露出的是主界面而非文件管理器，语义不等价。`A1-c` 同理。

**A1-j · 推荐 —— 专用轻量 VM，面向 Media3 标准 API，零 holder 耦合**

本轮实测到两条既有架构事实，让这条路比初版设想的 A1-b 干净得多：

1. **`MediaController` 与 `DualPlayerEngine` 是同一个播放器**：`MusicService.kt:214` 为 `playerProvider = { mediaSession?.player ?: engine.masterPlayer }`，且 `DualPlayerEngine` 是 `@Singleton`（`:222`）。⇒ 小 VM 自建 controller 播放，与 `MainActivity` 的 VM 操作同一引擎，天然兼容；`PlaybackStateHolder.activeLocalPlayer()` 的「优先 controller、退化 engine」亦印证二者可互换。
2. **架构里已有「外部 session 消费者」支持层**：`MappingPlayer.kt` 注释写明它把 app 内部封面 URI 重写为可共享 URI，「so external session consumers (system media surfaces, notification listeners, etc.) can load the artwork」；配套 `MediaItemBuilder.buildForExternalController(context, song)`（`:114`）与 `externalControllerArtworkUri`（`:241`）已在 `MusicService.kt:2331` 实际使用。⇒ controller 侧元数据/封面**开箱可用**。

落地形态：

```kotlin
// ExternalPlayerViewModel —— 不注入、不触碰任何 @Singleton holder
@HiltViewModel
class ExternalPlayerViewModel @Inject constructor(
    externalMediaStateHolder: ExternalMediaStateHolder,   // 无状态工具，只读 MediaStore
    mediaControllerFactory: MediaControllerFactory,       // @Singleton 薄封装
    @SessionToken sessionToken: SessionToken,
    userPreferencesRepository: UserPreferencesRepository, // 仅取 navBarCornerRadius
) : ViewModel() {
    // 状态：自注册 Player.Listener，从 controller 直读
    //   isPlaying / duration / currentPosition / mediaMetadata
    //   → overlay 所需 5 字段（id / title / displayArtist / album / artworkUri）
    // 控制：controller.play / pause / seekToNext / seekToPrevious / seekTo
    // 播放：buildExternalSongFromUri → MediaItemBuilder.build(song)
    //       → controller.setMediaItem + prepare + play
}
```

- overlay 入参由 `PlayerViewModel` 换成新 VM；7 处成员引用与 `currentSong` 的 5 个字段改为从 `MediaMetadata` 读；`MediaItemBuilder.build(song)` 直接复用，**无逻辑复制**。
- `MediaController.Builder` 会按需拉起 `MusicService` ⇒ **纯冷启动（MainActivity 从未启动）也能工作**，反而比依赖 holder 的 A1-b 更稳（无 owner 时 holder 的 `scope` 是 null）。
- 工作量约 **120–150 行 + 一处入参改造**；与 holder 零耦合，`PlayerViewModel` 后续改动无需同步。

**A1-b · 不推荐（已被 A1-j 取代）**：小 VM 只读 holder 的形态，障碍不变——`PlaybackStateHolder` **自身不注册任何 `MediaController` listener**（全文件无 `addListener`），overlay 依赖的 `isPlaying`/`totalDuration` 全靠 `PlayerViewModel` 的 `mediaControllerPlaybackListener`（`:2596-2801`，上百行）写回；小 VM 要么复制这段，要么在 holder 未初始化（冷启动 Quick play）时拿不到数据 ⇒ **两套状态机并存**。A1-j 从 controller 直读即可绕开。

**A1-a′ · 仅在明确放弃 Quick play 时可选 —— 迁移 intent-filter + 删除第二入口**

- `AndroidManifest.xml`：把 `VIEW` + `audio/*` + `content` 的 `<intent-filter>`（含 `BROWSABLE`）从 `ExternalPlayerActivity` 移到 `MainActivity`；删除 `ExternalPlayerActivity` 条目与 `Theme.PixelPlayer.ExternalPlayer`。
- 删除 `ExternalPlayerActivity.kt`（151 行）与 `ExternalPlayerOverlay.kt`（371 行）；`ACTION_VIEW` 分支接 `playerViewModel.showPlayer()`。
- 收益：**净删约 520 行**，全进程单 VM，第二 owner 从根上消失；`initialize()` 非幂等导致的竞态不再成立。
- 代价：**Quick play 语义消失**（见关键前提 2）——进最近任务、打开主界面、关闭后不回文件管理器。

**A1-c · 折中（保住浮层视觉、仍不接受第二 VM）**：`ExternalPlayerActivity` 降级为纯转发器，`ExternalPlayerOverlay` 改由 `MainActivity` 顶层条件渲染。浮层观感保住了，但「关闭」后露出的是主界面而非文件管理器，且主界面冷启动 ⇒ 语义仍与 Quick play 不同。

> 初版对 `ExternalPlayerOverlay` 依赖面的估计（「4 个成员」）有误，实测为 **7 个**：`stablePlayerState`(`:75`)、`currentPlaybackPosition`(`:76`)、`navBarCornerRadius`(`:77`)、`seekTo`(`:287`)、`previousSong`(`:325`)、`playPause`(`:326`)、`nextSong`(`:327`)——后三个是 `::` 方法引用，`grep 'playerViewModel\.'` 抓不到。

#### 1.3.1 A1-j 落地时必须修改的实现点清单

以下按「不改则 A1-j 不成立 / 不改会产生新缺陷 / 顺手清理」三档排列。行号对应基线 `0.4.2-pisces.1`。

##### P0 · 结构性必修（不做则方案不成立）

| # | 位置 | 现状 | 必改内容 |
|---|---|---|---|
| 1 | `ExternalPlayerActivity.kt:33` | `by viewModels()` 持有 `PlayerViewModel` → 第二 owner（C1 根因） | 换成 `ExternalPlayerViewModel`。连带 `:60` 的 `ExternalPlayerOverlay(playerViewModel = …)`、`:82` / `:90` 的 `playExternalUri(uri)` 一并改参名 |
| 2 | `ExternalPlayerOverlay.kt:71` | 入参类型 `PlayerViewModel` | 改为新 VM 类型；7 处成员（`:75/76/77/287/325/326/327`）与其 5 个字段（`:183 id`、`:234 albumArtUriString`、`:235+247 title`、`:257 displayArtist`、`:265 album`）改从 `MediaMetadata` 取值。**`mediaId` 已确认为 `song.id`**（`MediaItemBuilder.kt:107` / `:116`），故 `:183` 的 `LaunchedEffect(currentSong.id)` 可 1:1 换成 `mediaId`，语义不变 |
| 3 | `PlayerViewModel.kt:313` | `currentPlaybackPosition` 直接转发 `playbackStateHolder.currentPosition` | **`PlaybackStateHolder` 全文件无 `addListener`**，该 field 由 VM 的 `mediaControllerPlaybackListener` 喂养 ⇒ 新 VM 拿不到。必须自行实现：仅在 `isPlaying` 时轮询 `controller.currentPosition`（250 ms 级），暂停时 emit 一次停止轮询，避免空转耗电 |
| 4 | `PlaybackStateHolder.kt:363/375` | `previousSong` / `nextSong` 操作 **app 内部队列** + `dualPlayerEngine.masterPlayer` | 改为 `controller.seekToPrevious()` / `seekToNext()`。**队列语义必须保留**：`buildExternalQueue(result, uri)` 返回「同文件夹后续歌曲」，因此要用 `setMediaItems(list, startIndex, 0L)` 而非 `setMediaItem(single)` —— 单 item playlist 下 Media3 的 `seekToNext` 是空操作，上一首/下一首会直接失效 |
| 5 | `PlayerViewModel.kt:2921` | `playExternalUri` 末尾调 `showPlayer()` | **不要复制这行**。它会展开 `MainActivity` 的全屏播放器 sheet，Quick play 场景下 MainActivity 可能从未启动，属多余副作用 |
| 6 | `PlayerViewModel.kt:2917-2918` | 同函数内的 `_sheetState = COLLAPSED` / `_isSheetVisible = true` | 不复制。Quick play 不显示主 sheet |

> **换 VM 自动消失的两个副作用（A1-j 的附带收益，commit message 里要点明）**
> `PlayerViewModel.init:840-860` 会恢复上次播放队列快照 —— 从文件管理器冷启时会先恢复旧会话、再被 `playExternalUri` 覆盖，存在播放目标被抢占的窗口；`:862-877` 还挂了一个「每次换歌做封面取色」的 collector。此外 `:1666-1672` 在满足「未加载初始数据且 `allSongs` 为空」时会触发 `resetAndLoadInitialData("Initial Check")` ⇒ **冷启 Quick play 会全量加载一次音乐库**。三者随第二个 VM 一起消失。

##### P1 · 不改会产生新缺陷

| # | 位置 | 风险 | 修法 |
|---|---|---|---|
| 7 | `ExternalPlayerOverlay.kt:105-113` | 自动关闭逻辑为「已出现过歌曲（`awaitingSong=false`）后 `currentSong` 变 null → `onDismiss()`」。controller 的 metadata **空窗比 app 状态更大**（连接建立前 / 切歌间隙 / 播放自然结束 / controller release），**有误关 Activity 的真实风险** | 判据改为 VM 显式维护的「本轮已成功起播且随后停止」标志，不要直接用 metadata 是否为 null |
| 8 | `:296` `WavySliderExpressive` + `:324` `isPlayingProvider` | 必须取 `controller.isPlaying`，**不能取 `playWhenReady`** —— 缓冲中 `playWhenReady=true` 而实际静音，波形动画会一直跑 | 由 VM 从 `onIsPlayingChanged` 单独暴露 `isPlaying` 状态 |
| 9 | 新 VM 的 `onCleared()` | `MediaController` 必须释放，否则 Service 侧 session 泄漏 | 沿用现有写法 `controllerToRelease?.release()`（`PlayerViewModel.kt:4095`）；media3 实际版本为 **1.11.0**（`libs.versions.toml:36`，AGENTS.md 写的 1.10.1 已 drift） |
| 10 | 同回调等到 controller 就绪 | `buildAsync()` 是异步的，`handleIntent` 可能在连接完成前到达（`ExternalPlayerActivity.kt:67` 在 `setContent` 之后才调） | VM 内维护 `pendingStartUri`，controller ready 后 flush —— 结构与 `PlayerViewModel.pendingPlaybackAction`（`:1683-1685`）一致 |

##### P2 · 顺手处理（可并入同一 commit）

| # | 位置 | 说明 |
|---|---|---|
| 11 | `MainActivity.kt:429-443` | `ACTION_VIEW` / `ACTION_SEND` 分支仍是死代码（MainActivity 无对应 intent-filter）。A1-j 保留 `ExternalPlayerActivity` 为唯一入口 ⇒ 继续无用。建议保留但加注释说明入口归属，避免后人误判 |
| 12 | `ExternalMediaStateHolder.kt:23` | 只有 `@Inject constructor`、无 scope ⇒ 每次注入新建实例。它无状态（只读 MediaStore），可加 `@Singleton` 或直接 `@Inject` 到 VM 构造，影响有限 |
| 13 | `LyricsStateHolder.kt:87` | C6 的 `songObserveJob`（见 §C6） |
| 14 | `AGENTS.md` | media3 版本 1.10.1 → 实际 1.11.0，属文档 drift |

#### 1.3.2 Quick play 是否计入统计 / 最近播放 —— 两个互斥的播放器选择

> **本节修正要点**：统计的唯一驱动方是 `MusicService`，**不是 `PlayerViewModel`**。因此 §1.3.1 的 A1-j（换 VM）**无论做得多干净，都不解决统计污染**——两条路必须在此处再选一次。

##### 事实核实：污染链路与唯一收口点

```
playExternalUri → internalPlaySongs → engine.masterPlayer.setMediaItem + play
   ↓ MusicService.kt:420   engine.masterPlayer.addListener(playerListener)
syncLocalListeningStatsFromPlayer(engine.masterPlayer)      ← :347-379
   ↓
ListeningStatsTracker.ensureSession / onTrackChanged        ← :379 / :371
   ↓ finalizeCurrentSession
   ├─→ JSON  playback_history.json（听歌统计聚合的唯一数据源）
   └─→ DailyMixManager.recordPlay(...)                      ← tracker :352
          ↓ DailyMixManager.kt:244 engagementDao.recordPlay
          Room engagement 表
             ├─→ EngagementDao:68 getRecentlyPlayedSongs  → 「最近播放」UI
             └─→ AiPlaylistGenerator:113 getAllEngagements（参与 AI 选歌）
```

- `PlayerViewModel` 全程只有 4 处 tracker 引用（`:266/:314/:833/:4101`），**没有** `onTrackChanged` / `onProgress` 调用；tracker 的日常写入由 **`MusicService` 的 player listener** 驱动，且 `onCreate` 里用的是 `appScope`（`:414`）而非 VM scope。
- ⇒ **换任何 VM 都绕不开**，除非播放本身不经过 `engine.masterPlayer`。
- ⇒ 好消息：**只有 `syncLocalListeningStatsFromPlayer` 一个收口点**，在此拦下即可同时净化统计、最近播放、AI 选歌三处。

##### 现有 `EXTERNAL_EXTRA_FLAG` 不能当判据（已验证）

`MediaItemBuilder.kt:90/298` 的 `EXTERNAL_EXTRA_FLAG` 语义就是「是否外部媒体」，但它由 song.id 前缀反推：

```kotlin
// ExternalMediaStateHolder.buildExternalSongFromUri（约 :249）
val songId = mediaStoreSongId?.toString() ?: "external:${uri}"
```

- MediaStore **索引得到** → `songId` 是纯数字 ⇒ **FLAG = false**；
- MediaStore **查不到** → `songId = "external:<uri>"` ⇒ FLAG = true。

从文件管理器打开本地音频**绝大多数命中第一种**（FLAG=false），所以这个 flag **只在少数场景为真**，不可用作「是否 Quick play」的判据。

##### 方案对照

| | **甲 · 打标过滤（推荐）** | **乙 · 完全独立播放器** |
|---|---|---|
| 播放器 | 仍走 `MediaController` / `engine.masterPlayer`（即 §1.3.1 的 A1-j 原样） | `ExternalPlayerViewModel` 自建 `ExoPlayer` 实例，**不绑 `MusicService`** |
| 实现方式 | `MediaItemBuilder` 写 `EXTRA_QUICK_PLAY` 到 `extras`；`syncLocalListeningStatsFromPlayer` 开头读标记后 `return` | 从依赖图上隔离：不经 MusicService ⇒ listener 根本收不到事件 |
| 改动量 | 约 20–30 行 | 约 80–120 行 + 独立生命周期管理 |
| 统计/最近播放 | 干净（单一收口点拦截） | 干净（天然不触发） |
| 媒体通知 / 锁屏 / 耳机按键 | **保留** | **丢失**（不在 MediaSession 内） |
| dismiss 语义 | 不变：播放继续，通知可回控 | 必须改为「关闭 = 停止并 release」，否则产生不可控的孤儿播放 |
| 与 C1 的关系 | A1-j + 少量改动 | A1-j + 换播放器实现（**不是 A1-j 的替代品，仍须先消除第二 owner**） |

**推荐甲**，理由：

1. 成本约为乙的四分之一，且改动集中在 Statistics 入口，语义单一、可测试；
2. 保住媒体通知——「从下载目录点开一首歌、退回桌面继续听」是 Quick play 的高频后续动作，乙会直接抹掉；
3. 拦截点是 `MusicService` 自身的 player listener，**A1-j 之后仍长期有效**（将来任何入口播放标记 item 都不会记账）。

### 实施记录（2026-09-17）

方案甲已落地，改动 4 个文件共 +65 行，`:app:compileReleaseKotlin` 通过。与上文骨架有两点差异，以此处实际实现为准：

1. **标记在 `PlayerViewModel.buildPlaybackMediaItem` 上合并，而不是改 `MediaItemBuilder.build(song, quickPlay)` 签名**。后者会波及全部调用点；前者只在既有的 extras 合并分支里加一个 `putBoolean`（该文件原本就有为 `playlistId` 合并 extras 的结构）。
2. **队列分段必须一并标记** —— 这是骨架漏掉的。`preparePlaybackQueueSegments` 独立调用 `buildPlaybackMediaItem` 构造前后续 item，若不透传 `quickPlay`，用户点「下一首」播到同文件夹的第二首歌时会**重新被记账**。

具体改动：

| 文件 | 改动 |
|---|---|
| `utils/MediaItemBuilder.kt` | 新增常量 `EXTRA_QUICK_PLAY`，注释里写明为何不能复用 `EXTERNAL_EXTRA_FLAG` |
| `presentation/viewmodel/PlayerViewModel.kt` | `buildPlaybackMediaItem` / `buildResolvedPlaybackMediaItem` / `preparePlaybackQueueSegments` / `internalPlaySongs` 逐级加 `quickPlay` 参数并透传；仅 `playExternalUri` 传 `true`；其余三个 `internalPlaySongs` 调用点走默认 `false`，行为不变 |
| `data/service/MusicService.kt` | `syncLocalListeningStatsFromPlayer` 开头读 extras，命中则 `onPlaybackStopped()` 后 `return` |

两个已确认的安全边界：

- `DualPlayerEngine.resolveMediaItem`（`:1255-1275`）在需要改写 URI 时走的是 `mediaItem.buildUpon().setUri(...)`，**mediaMetadata 与 extras 保留**，标记不会被丢；
- 拦截后 tracker 内不存在 session，因此 `:988 / :1136 / :2193` 的 `finalizeCurrentSession` 均为 no-op；ListenBrainz 的 `ScrobbleManager` 同样由 tracker 供给，**一并豁免**，符合"一次性预览不留痕"的语义。

**未做**：历史数据的回溯清理（见下一节），以及为这段逻辑补单测 —— 现有 `MediaItemBuilderTest` 不覆盖 extras 合并，而拦截点在 Android Service 内难以 JVM 化；已跑通过该测试确认无回归。

甲的落地骨架（不受 A1-j 影响，可并行）：

```kotlin
// MediaItemBuilder
const val EXTRA_QUICK_PLAY = "com.pisces312.piscesplayer.QUICK_PLAY"
fun build(song: Song, quickPlay: Boolean = false): MediaItem { … putBoolean(EXTRA_QUICK_PLAY, quickPlay) … }

// MusicService.syncLocalListeningStatsFromPlayer（:347 开头）
if (mediaItem.mediaMetadata.extras?.getBoolean(EXTRA_QUICK_PLAY, false) == true) {
    listeningStatsTracker.onPlaybackStopped()   // 收尾已有 session，不新开
    return
}
```

> **必须在 `handleIntent`→`internalPlaySongs` 这条路径上显式传 `quickPlay = true`**，不要依赖 `EXTERNAL_EXTRA_FLAG` 反推（见上文验证）。

**乙若要采用，需先确认三点**：① 是否接受 Quick play 期间无通知/锁屏控制；② 「关闭浮层即停止播放」是否符合预期；③ 是否接受与 `DualPlayerEngine` 的 ReplayGain、crossfade、`preparePlaybackQueueSegments` 等既有链路彻底脱钩（音量为独立实例，不受主播放器设置管理）。

##### A1-j 不解决、需另行决策的遗留问题

- ~~库外歌曲进入听歌统计~~ → **已转为本节处理**，不再是未决项。
- **`ExternalMediaStateHolder.persistExternalAudioForPlayback`** 会把 content URI 拷到 `filesDir`。随 Cold-start Quick play 反复触发会累积临时文件，未见清理路径 —— 与 C1 无关，单独记一项。
- **历史数据不会被净化**：已经混进 `playback_history.json` / engagement 表的 Quick play 记录不会因上述改动消失。若要求「现在就是干净的」，需额外做一次性清理（按 songId 不在 `allSongs` 内剔除），属独立任务且有误删风险，不建议默认执行。

**A2 · 抽 `OwnedStateHolder` 并回填 8 个 holder（纵深防御）**

清掉 A1 后顺序 A/B 都安全，但 AGENTS.md 记录的 `hiltViewModel()` 回归仍然可能再犯，所以这层要留：

```kotlin
// presentation/viewmodel/OwnedStateHolder.kt
interface OwnedStateHolder {
    /** 首个 owner 胜出；非 owner 的重复调用被忽略并打 Timber.w。 */
    fun claim(owner: Any, scope: CoroutineScope)
    fun release(owner: Any)
}

abstract class BaseOwnedStateHolder : OwnedStateHolder {
    private var owner: Any? = null
    protected var scope: CoroutineScope? = null
        private set

    override fun claim(owner: Any, scope: CoroutineScope) { …首次胜出… }
    protected abstract fun onScopeReleased()
    override fun release(owner: Any) { …owner 不匹配则忽略…; onScopeReleased(); scope = null; this.owner = null }
}
```

调用点（`PlayerViewModel.init` / `onCleared`）统一改成 `holder.claim(this@PlayerViewModel, viewModelScope)` / `holder.release(this@PlayerViewModel)`。
`SearchStateHolder` 的现有实现可作为**语义参照**保留（`SearchStateHolderTest` 的 4 个用例即回归网），但建议一并收敛到 `BaseOwnedStateHolder`，避免两套写法。

**A3 · 补回归测试**
- JVM 单测：对每个 holder 断言「非 owner 的 `claim`/`release` 无效」（直接套用 `SearchStateHolderTest.kt:78-98` 的写法）；
- 加一个**顺序 B 的显式用例**：owner1.claim → owner2.claim（被拒）→ owner1.release → 断言 holder 已拆。用例本身应断言「这种顺序下 holder 确实被拆」，以此固化「必须消除第二 owner」这条约束（若将来有人 A1 回退，测试会失败）。

#### 方案 B（备选，不动 `ExternalPlayerActivity`）· 把 holder 的 scope 提升为进程级

新增 `@Singleton HolderScope`（`CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)`），`initialize(owner, scope = holderScope.scope)`，`onCleared` 只做**带 owner 守卫**的资源注销。测试仍可注入 `TestScope`。

**但必须明确否掉「只做 B 不做 A1」**：顺序 B 下首个 owner 是 VM_E，VM_E 死亡时的 `release(VM_E)` owner 匹配，照样注销 `ConnectivityStateHolder` 的系统 callback、`LyricsStateHolder` 的 `loadCallback` 也已被置空。B 只解决顺序 A，**不能单独交付**。

> **结论：无论走 A 还是 B，`ExternalPlayerActivity` 这个第二 owner 都必须消除。**

---

## C2 · AI API Key 明文存 DataStore —— 完全成立

**核实**：

- key 定义 `AiPreferencesRepository.kt:119`：`stringPreferencesKey("${provider.keyPrefix}_api_key")`；
- 写入 `:171-172`：`dataStore.edit { it[Keys.getApiKey(provider)] = apiKey.trim() }`，无任何加密；
- 注入的是**全局共享的 `Preferences DataStore`**（`AppModule.kt:107-109` → `context.dataStore`），即 AI Key 与普通设置同库，未隔离；
- 同期 Navidrome / Jellyfin / ListenBrainz 已用 ESP（`MasterKey AES256_GCM`），`androidx.security:security-crypto:1.1.0` 已在依赖里。

**定级**：Critical 保留。「root / 备份导出 / 侧载可直接读走第三方 API Key」成立——尤其配合 C3，默认导出就把 key 一并带走。

**修复方案**

1. **先做 H1**（去掉 ESP 失败回退明文）。否则引入了 ESP 也可能悄悄退回明文，等于白改。
2. 新增 `AiSecretStore`（`data/preferences/`，`@Singleton`），构造/读写与 `NavidromeRepository.kt:104-115` 同路径（ESP + `MasterKey`）：
   `putApiKey(provider, value)` / `getApiKey(provider): String?` / `removeApiKey(provider)`。
3. `AiPreferencesRepository.setApiKey` 改写 `AiSecretStore`；`getApiKey` 改为 `flow { emit(secretStore.get(provider).orEmpty()) }`，并加一层内存 `MutableStateFlow` 缓存，避免每次订阅都走一遍 ESP/Keystore。
4. **一次性迁移**：首次访问时若 DataStore 里残留 `*_api_key` → 写入 ESP → 同一次 `edit` 里 `remove()`；迁移用 `AtomicBoolean`/`Mutex` 做幂等。
5. **坑位**：ESP 的构造与解密都是 IO，且有失败面；**不要**把它放进 `dataStore.data.map { }` 的同步转换路径（会让 `providerFlow` 等无关流也被 Keystore 拖慢/拖挂）。独立的 suspend 入口更安全。

---

## C3 · 备份默认明文导出 API Key —— 成立，且比报告写的更严重

**核实**：报告说「用户不勾加密时」泄露，实际是**默认路径就泄露**：

- `BackupSection.kt:90`：`val defaultSelection: Set<BackupSection> = entries.toSet()` → **`AI_PROVIDER_CONFIG` 默认被勾选**；
- `BackupSection.kt:80-83`：该区块描述文案自陈含 API key：`"AI provider, model, endpoint and API key used for playlist generation."`
- `SettingsCategoryScreen.kt:2906`：`var encryptEnabled by remember { mutableStateOf(false) }` → 加密开关**默认关闭**；
- `AiProviderConfigModuleHandler.kt:22-24` + `AiPreferencesRepository.kt:294-299`：注释明确「payload includes API keys in clear text」；
- `BackupManager.export(..., passphrase: String? = null)`（`BackupManager.kt:67`）→ `BackupWriter.write(..., passphrase=null)` → `BackupWriter.kt:55` 不套 `BackupCrypto.encryptingStream`，ZIP 直接落盘。

即：**默认配置下导出的 `.pxpl` 里就是明文 API Key。**

**修复方案**（三选一，按推荐度排序）

- **(b) 备份不再导出 API Key（推荐）**：`AiPreferencesRepository.exportForBackup()` 过滤掉 `allAiPreferenceKeyNames()` 里的 `*_api_key`；恢复时该字段留空，设置页提示「请重新填写 API Key」。改动量最小、彻底消除泄露面，且 provider / model / base_url / thinking 等仍能一键还原。
- **(a) 强制加密**：拆分加密不可行——`BackupCrypto.encryptingStream` 是**整包 AES-GCM 容器**（`BackupWriter.kt:54-56`），做不到「只加密 AI 段」。所以只能：当 `sections` 含 `AI_PROVIDER_CONFIG` 时，把 `BackupEncryptionDialog` 的开关**锁定为开且必填密码**（`canConfirm = password.length >= 4 && password == confirmPassword`），并在弹窗文案里点明原因。保留换机体验，改动约 30 行。
- **(c) 应用内密钥加密**：用固定/设备派生密钥加密 key。只防「文件被顺手看到」，不防设备被拿走，**价值有限，不推荐**。

> 若两者都要，建议 (b) 为默认行为、(a) 作为「用户显式勾选『包含 API Key 并加密』」的进阶选项。

---

## C4 · 全局允许明文 + 信任用户 CA —— 事实成立，定性偏高，修法不可实现

**核实**：

- `app/src/main/res/xml/network_security_config.xml:3-7`：`base-config cleartextTrafficPermitted="true"` + `<certificates src="user" />`；
- `AndroidManifest.xml:45` 另有 `android:usesCleartextTraffic="true"`。在 API 24+ 已声明 NSC 的情况下以 NSC 为准，**该属性冗余**（与 NSC 取值一致，不额外放大风险）；
- `app/src/debug/res/xml/network_security_config.xml` 已单独存在（内容为 base-config 明文 + user 锚，无 localhost domain-config）。

**报告的两处问题**

1. **修法写不出来**：报告建议「仅对 LAN host 开 domain-config，复用 `CloudStreamSecurity.isLocalServerHost`」。但 `isLocalServerHost`（`CloudStreamSecurity.kt:160`，`internal`）是**运行时字符串/网段判定**，而 NSC 的 `<domain>` 只能在编译期枚举静态域名或 IP 字面量，**不支持私有网段通配**；应用又允许用户填任意自建服务器地址（`NavidromeCredentials` / `JellyfinCredentials`），无法预先枚举。这条路在 NSC 层面不存在。
2. **定性偏高**：自建 Navidrome / Jellyfin 用 LAN `http://` 与自签证书是**主流用法**，全局明文与 `user` 锚是**功能性需求**，不是疏忽。要利用它，用户得主动装一个恶意 CA、或主动把服务器地址填成 http 公网域名。

**可行修法（推荐组合 1 + 3）**

1. **代码层收口（推荐，真正能落地）**：OkHttp 拦截器里加 host 分类——非 `isLocalServerHost` 且非 `https` 的请求直接拒绝并 `Timber.w`。现成积木已有：`CloudStreamSecurity.isLocalServerHost` / `isPrivateIpv4Literal` / `isSafeRemoteStreamUrl`。这等价于把「允许明文」的范围从「所有域名」收为「私有网段」，正是报告想达到的效果，只是实现位置从 NSC 挪到拦截器。
2. base-config 禁明文 + 仅为 `127.0.0.1` / `localhost` 保留 domain-config（服务 `CloudStreamProxy`，`network_security_config.xml:8-11` 已有这段）。**会让 LAN http 服务器用户连不上**，属产品决策，需先确认。
3. **release 移除 `<certificates src="user" />`，debug 保留**（与报告一致，`app/src/debug/res/xml/` 那份已就位）。会打断自签证书用户；若不接受，至少在设置页对「自签/明文」连接给出显式提示。

**建议定级 High**：需要用户侧前置条件，且与产品功能正面冲突，不宜与 C2/C3 并列 Critical。

---

## C5 · `android.dependency.useConstraints=false` —— 不成立，诊断错误

### 5.1 该 property 的真实语义

AGP 9.0 官方 release notes 原文：

> `android.dependency.useConstraints` — **Controls the use of dependency constraints between configurations.** The default in AGP 9.0 is `false` which only uses constraints in application device tests (AndroidTest). Setting this to `true` will revert back to the 8.13 behavior. （true → false）

它管的是 **AGP 自己生成、用于跨 configuration 对齐的约束**（同时还影响「多个 library 子项目」的导入期优化），**与你在 `dependencies { constraints { … } }` 里手写的约束是两回事**——后者是纯 Gradle 解析逻辑，AGP 无权关闭。

另外：`false` **就是 AGP 9.0 的默认值**，`gradle.properties:25` 这行是显式重述默认值，属冗余（对应当前文件里那句注释 "Aligning with AGP 9.0+ defaults and removing deprecated settings"，可以直接删）。

### 5.2 实测：约束块本来就是死配置

本机已跑：

```bash
./gradlew :app:dependencies --configuration releaseRuntimeClasspath        # 受约束模块命中 0
./gradlew :app:dependencies --configuration debugRuntimeClasspath          # 受约束模块命中 0
./gradlew :app:dependencies --configuration debugAndroidTestRuntimeClasspath  # 受约束模块命中 0
./gradlew :app:dependencies                                              # 全配置 37340 行
```

`app/build.gradle.kts:401-412` 的 10 个模块，只在两处出现，且**都没有被提升到约束版本**：

| 出现的 configuration | 解析到的版本 | 约束版本 | 是否提升 |
|---|---|---|---|
| `androidLintTool`（lint 工具类路径，**不随 APK 分发**） | `commons-lang3:3.16.0`、`httpclient:4.5.6`、`bcprov/bcpkix-jdk18on:1.79` | 3.20.0 / 4.5.14 / 1.85 | ❌ |
| `unified-test-platform-*`（AGP 测试基础设施） | `netty:4.1.110.Final` | 4.2.16.Final | ❌ |
| `releaseRuntimeClasspath` / `debugRuntimeClasspath` / `debugAndroidTestRuntimeClasspath` | **不存在** | — | — |

两条硬事实：

- **Gradle 约束只会提升已入选模块的版本，永不引入模块。**模块不在图里 = 约束完全不起作用；
- 受约束的 6 个库**根本不进 APK**（`releaseRuntimeClasspath` 里没有），所以**不存在 CVE 暴露面**。

### 5.3 正确结论与修法

- **不是安全漏洞，是「虚假安全感」+ 维护债**：约束块 10 行 + `libs.versions.toml:54-59,197-206` 的 10 个 alias / 6 个 version，全仓无人消费；
- `THIRD_PARTY_NOTICES.md:55` 与 `docs/DEPENDENCY_LICENSES.md:37,41` 把它们当作生效的 "constraints" 披露，**属不实陈述**，需同步修正；
- **修法**：
  1. 删除 `app/build.gradle.kts:401-412` 的 `constraints { }` 块；
  2. 删除 `gradle/libs.versions.toml` 中对应 alias 与 version ref（先确认无其他引用）；
  3. 修正 `THIRD_PARTY_NOTICES.md` / `docs/DEPENDENCY_LICENSES.md` 的相应条目；
  4. `gradle.properties:25` 可直接删（= 默认值）；
  5. **不要**按报告建议改用 `resolutionStrategy.force` —— `force` 会把不存在的模块**强行拉进依赖图**，等于凭空给 APK 塞进 netty / bouncycastle / httpclient / jdom2 / jose4j / commons-lang3 六个库，是**明显倒退**。

> 如果将来确实需要某个传递依赖的版本护栏，正确做法是：先确认该模块**当前在 `releaseRuntimeClasspath` 里**，再用 `constraints`（或 `strictly`）钉版本，并在 PR 描述里贴出 `./gradlew :app:dependencies` 的前后对比作为证据。

---

## C6 · `LyricsStateHolder` 的 collector 不落 Job —— 事实成立，定级过高

**核实**：

```kotlin
// LyricsStateHolder.kt:79-97
fun initialize(coroutineScope: CoroutineScope, callback: LyricsLoadCallback, stablePlayerState: StateFlow<StablePlayerState>) {
    scope = coroutineScope                       // :84  无条件改写
    loadCallback = callback
    coroutineScope.launch {                      // :87  未保存 Job
        stablePlayerState.map { it.currentSong?.id }.distinctUntilChanged()
            .collect { songId -> if (songId != null) updateSyncOffsetForSong(songId) }
    }
}

// :396-400
fun onCleared() { loadingJob?.cancel(); scope = null; loadCallback = null }
```

**但危害与报告描述的不同**：

- 该 coroutine 跑在**传入的 scope** 上（生产即 `viewModelScope`）。VM 被销毁 → scope 取消 → collector 随之结束，**不存在永久泄漏**；
- 两个 VM 并存期间确实会有两个重复 collector，但两者都只是把同一个 `_currentSongSyncOffset` 写成同一值，**结果幂等，无可观测错误**；
- 真正被这个文件放大的是 **C1**：`:84` 的 `scope = coroutineScope` 会把 holder 的 scope 换成第二个 VM 的，于是 VM#1 后续的 `loadLyricsForSong` / `setSyncOffset` 跑在**即将被取消的 VM#2 scope** 上 → 歌词加载静默失效。这是 C1 的症状，不是「Job 没存」造成的。

**定级**：Critical → **Low**。

**修法**（顺手做，不单独开清单项）：
1. 把 collector 存进 `songObserveJob`，`initialize` 开头 `songObserveJob?.cancel()`，`onCleared(owner)` 里一并 cancel——同时解决「重复 collector」；
2. scope 重绑问题由 **C1 的方案**统一解决，`LyricsStateHolder` 不再自行处理。

---

## 修正后的修复路线图

原路线图把 C5 排进第一梯队、C6 单列一项，均需调整。每项独立 commit、英文 message。

### 第一梯队（本迭代 · G1 + G3 + G4）

| 序 | 项 | 改动量 | 说明 |
|---|---|---|---|
| 1 | **C1-A1-j** 新建 `ExternalPlayerViewModel`（自建 `MediaController`，零 holder 耦合），`ExternalPlayerActivity` 保留 Quick play 浮层 UX | 0.5–1 天 | **必须先行**；替代原 A1-a′（A1-a′ 会抹掉 Quick play 设计，见 §1.3 关键前提 2） |
| 2 | **C1-A2** 抽 `BaseOwnedStateHolder`，回填 8 个 holder + 补回归测试 | 1 天 | 顺序 A/B 都不再受影响 |
| 2.5 | **§1.3.2 甲** Quick play 不计入统计 / 最近播放：`EXTRA_QUICK_PLAY` 标记 + `syncLocalListeningStatsFromPlayer` 单一收口拦截 | 0.5 小时 | **独立于第 1 项**，无先后依赖，可随时做；甲被否则换乙（见该节对照表） |
| 3 | **C6 附带** `LyricsStateHolder` 存 `songObserveJob` | 0.5 小时 | 并入第 2 项同一提交亦可 |
| 4 | **C5** 删除死约束块与 Toml 条目，修正两份 NOTICES 文档 | 0.5 小时 | 纯清理，无行为变更 |

### 延后一轮 · G2 凭据与明文传输（用户指示，本轮不动）

依赖链为 **H1 → C2 → C3**，按此顺序做；C4 可并行：

| 序 | 项 | 改动量 | 说明 |
|---|---|---|---|
| D1 | **H1** 去掉 ESP 的 plain 回退（C2 的前置） | 1 小时 | |
| D2 | **C2** AI API Key 迁 ESP + 一次性迁移清理 DataStore 残留 | 1 天 | 依赖 D1 |
| D3 | **C3** 备份不导出 API Key（`exportForBackup()` 过滤 `*_api_key`）或强制加密 | 0.5–1 小时 | 与 C2 有耦合；`defaultSelection = entries.toSet()` 使 AI 配置默认勾选，故优先级不低 |
| D4 | **C4** OkHttp host 分类拦截器（非 LAN 且非 https 直接拒绝）+ release 去 user 锚 | 1–2 小时 | 需真机回归自建云；user 锚是否保留需产品决策 |

### 第二梯队

原 H3（FileProvider 收窄 `file_paths.xml` 的 `path="."`）、H2（Navidrome TOKEN 模式不落盘密码）、M5（补 8 个 migration 测试）优先级不变。

### 第三梯队

M1（`DailyMixScreen.kt:98` 死参数 `mainViewModel`）可随时清；其余不变。

---

## 回归测试建议（修订）

原清单第 1 条不足以覆盖 C1，补两条：

1. **C1 顺序 A**：应用运行中 → 从文件管理器打开音频（触发 `ExternalPlayerActivity`）→ 点「打开完整播放器」→ `ExternalPlayerActivity` 关闭后，验证播放位置更新、歌词加载、主题取色、连通性指示、睡眠定时器、听歌统计**全部仍在工作**（对照 `files/logs/pixelplayeross.log` 中 holder 的 `Timber.w` 告警）；
2. **C1 顺序 B**：`adb shell am force-stop` 后冷启，`adb shell am start -a android.intent.action.VIEW -d <content-uri> -t audio/mpeg`，进入 `ExternalPlayerActivity` → 点「打开完整播放器」→ 重复上述验证；
3. 冷启动 → 播放 → 锁屏 → 解锁（`PlaybackStateHolder` 未被误清）；
4. 进 AI 混音页 → 返回 → 搜索（历史 Search 回归场景）；
5. Navidrome 登录 → 杀进程 → 重开（凭据未降级明文）；
6. 导出 `.pxpl` 后用 `unzip -p <file> manifest.json` / `grep` 抽查是否还能搜到 API Key 明文；
7. `.\gradlew.bat :app:assembleRelease` + `apksigner verify --print-certs -v`（签名仍正常）。
