# 车机歌词标题（蓝牙 AVRCP）

> 状态：**已完整实现**（真实歌词 + 蓝牙 A2DP 门控 + 设置开关，默认关闭），已在模拟器上端到端验证（见 §9）。
> 相关代码：`data/service/player/LyricTitlePlayer.kt`、`data/service/player/CarLyricTitleController.kt`、`data/service/MusicService.kt`、`utils/LyricsTimelineUtils.kt`
> 姊妹文档：`docs/car-lyrics-avrcp-gate.md` —— 车机标题「卡住」的根因（蓝牙进程的 2 秒同步闸门）与短路方案，本文不含该内容。

## 1. 需求与来源

**需求**：蓝牙车机播放时，让车机界面上那一行媒体标题跟着歌词滚动，而不是固定显示歌曲名。

**来源观察**：这个交互首次见于**汽水音乐**——其车机端标题栏会随进度滚动当前歌词行。它并不是车机原生能力，而是手机端 App 主动改写了出站媒体标题。本方案是同一思路的独立实现。

## 2. 结论摘要

可行。机制是**动态改写出站 `MediaMetadata` 的 `TITLE`**。

车机上那行文字的唯一来源是 Android `MediaSession` 的 `playerInfo.mediaMetadata`；而蓝牙 AVRCP 协议本身**没有**歌词字段（见 §4），所以所有实现这个效果的 App 都是在借用 Title 字段。

本项目地基很好：歌词子系统、「当前行」算法、出站 metadata 改写层三者都已存在，缺的只是把它们接起来，并解决"**如何让下游知道要更新**"这一问题。

## 3. 机制与源码证据

### 3.1 出站链路

```
MusicService（serviceScope，长生命周期）
   ├─ 采样播放进度 currentPosition
   ├─ 取同步歌词 SyncedLine.time
   └─ 解析当前行 resolveCurrentLineIndex(lines, position)
            ↓
   LyricTitlePlayer（最外层包装）→ 改写 metadata.title
            ↓
   MediaSession.playerInfo.mediaMetadata
       ├─→ 蓝牙 AVRCP → 车机显示
       └─→ 通知栏 / Windows SMTC
```

### 3.2 关键约束：Media3 的 metadata 是事件驱动推送

这是全案唯一的高风险点。`androidx.media3:media3-session:1.11.0`，`MediaSessionImpl.java` L2249：

```java
@Override
public void onMediaMetadataChanged(MediaMetadata mediaMetadata) {
  @Nullable MediaSessionImpl session = getSession();
  if (session == null) return;
  session.verifyApplicationThread();
  @Nullable PlayerWrapper player = this.player.get();
  if (player == null) return;
  session.playerInfo = session.playerInfo.copyWithMediaMetadata(mediaMetadata);
  session.onPlayerInfoChangedHandler.sendPlayerInfoChangedMessage(true, true);
  session.dispatchRemoteControllerTaskToLegacyStub(
      (callback, seq) -> callback.onMediaMetadataChanged(seq, mediaMetadata));
}
```

三点结论：

1. `session.playerInfo.mediaMetadata` **只在 `Player.Listener.onMediaMetadataChanged` 触发时**才更新；
2. 推送目标是 **legacy stub**（`android.media.session.MediaSession` 兼容层），而蓝牙 AVRCP 栈读的正是这条通道；
3. 因此，**只覆盖 `ForwardingPlayer.getMediaMetadata()` 让车机看到新标题是不可行的**——下游根本不会收到刷新通知。

### 3.3 现有可复用资产

| 需要的东西 | 现状 | 位置 |
|---|---|---|
| 带时间轴歌词 | 已有（LRCLIB / 音频内嵌标签 / 本地 `.lrc`/`.ttml`） | `data/repository/LyricsRepositoryImpl.kt` |
| 当前歌词行 | 已有，但只在 Composable 内使用 | `presentation/components/LyricsSheet.kt:1977` `resolveCurrentLineIndex()` |
| 出站 metadata 改写层 | 已有（`ForwardingPlayer`，原本用于 artwork URI） | `data/service/player/MappingPlayer.kt` |
| MediaSession | 已有 | `MusicService : MediaSessionService`，session 建于 `MusicService.kt:703` |
| metadata 构造 | 已有 | `utils/MediaItemBuilder.kt:284` `buildMediaMetadataForSong()` |
| 长生命周期订阅点 | 已有 | `MusicService.serviceScope`（L199）+ `playerListener`（L398） |
| 设置开关样板 | 已有 | `PreferencesKeys` 的 `LYRICS_*` / `IMMERSIVE_LYRICS_ENABLED` |

覆盖面注意：Navidrome 服务端有 `getLyrics` 但**未接入** `LyricsRepository`；Jellyfin 无歌词接口；云端 AI 歌词在 OSS 版本已剥离。功能上限由"本地能拿到多少同步歌词"决定。

### 3.4 包装链

`MusicService.wrapFadingPlayer()` 返回的包装链，以及 MediaSession 实际持有的实例：

```
LyricTitlePlayer(  ← MediaSession 持有这一层，必须是它
  MappingPlayer(   ← 本来就存在的 artwork URI 映射层
    FadingPlayer(
      engine.masterPlayer
    )))
```

两个必须注意的点：

- **`LyricTitlePlayer` 必须在最外层**。MediaSession 注册的 listener 落在它上面，伪造的事件也发给这些 listener；如果它被包在里面，事件到不了 MediaSession。
- **包装链会被重建**。`publishMediaSessionPlayer()` 在交叉淡入淡出切换 display player 时会重新调用 `wrapFadingPlayer()`，所以实例引用要存在字段（`lyricTitlePlayer`）里，不能假设只创建一次。同时 `unwrap*()` 解包链要补上新的一层，否则 `publishMediaSessionPlayer` 里"解包后是否是同一个 player"的判断会失效。

### 3.5 附带结论：发的是 `Now Playing Content Changed`(0x09)，不是 `Track Changed`(0x02)

车主视角的常见顾虑是"改标题会不会被车机当成换曲、把进度条清零"。用 AOSP 源码判定：**不会**——因为我们只换 metadata、从不碰队列项 id。

证据链（`LineageOS/android_packages_modules_Bluetooth`，分支 `lineage-23.2`，即 Android 16 的蓝牙模块；该模块已从 `packages/apps/Bluetooth` 迁出）：

1. `avrcp/AvrcpTargetService.java` L175 `ListCallback implements MediaPlayerList.MediaUpdateCallback` —— Java 层**只有一个**出站入口，没有独立的 track / now-playing 方法：

   ```java
   public void run(MediaData data) {
       boolean metadata = !Objects.equals(mCurrentData.metadata, data.metadata);
       boolean state = !MediaPlayerWrapper.playstateEquals(mCurrentData.state, data.state);
       boolean queue = isQueueUpdated(mCurrentData.queue, data.queue);
       Log.d(TAG, "onMediaUpdated: track_changed=" + metadata + " state=" + state + " queue=" + queue);
       mCurrentData = data;
       mNativeInterface.sendMediaUpdate(metadata, state, queue);
   }
   ```

2. 三个标志位**互相独立**：`metadata` 由 metadata 内容差异驱动，`queue` 由 `isQueueUpdated()` 单独判定。我们的改写只让 `metadata=true`，`queue` 恒为 `false`。
3. `avrcp/AvrcpNativeInterface.java` L143 `sendMediaUpdate(boolean metadata, boolean playStatus, boolean queue)` 把三个布尔转交原生层，由原生层依 `queue` 决定是否发 `TRACK_CHANGED` 并轮换 UID。

两点补充：

- AOSP 自己的日志把 metadata 差异打印成 `track_changed=`，命名有误导性；真正决定"换曲"语义的是 `queue` 这一路。**这正是必须保持 `mediaId` 不变的原因**——一旦走 `replaceMediaItem`（路线 A），`queue` 随之变化，就会真的触发 `Track Changed`。
- **残余风险（无法离线判定）**：对端是否注册了 `EVENT_NOW_PLAYING_CONTENT_CHANGED`(0x09)。没注册的设备收不到这次刷新，标题会停在旧值直到真正换曲。这属于车机侧行为，真机 `btsnoop` 可复核（见 §11.1）。

## 4. AVRCP 到底能不能携带歌词

**不能。**

标准 `GetElementAttributes`（PDU ID 0x20）只定义以下 media attribute ID：

| ID | 含义 | ID | 含义 |
|---|---|---|---|
| 0x0001 | Title | 0x0005 | Total number of tracks |
| 0x0002 | Artist | 0x0006 | Genre |
| 0x0003 | Album | 0x0007 | Playing time |
| 0x0004 | Track number | 0x0008 | Default cover art（AVRCP 1.6 新增） |
| 其余 | Reserved | | |

依据：

- Linux 内核 `monitor/avctp.c` 的 `AVRCP_MEDIA_ATTRIBUTE_*` 定义：`TITLE=0x01`、`ARTIST=0x02`、`ALBUM=0x03`、`TRACK=0x04`、`TOTAL=0x05`、`GENRE=0x06`、`DURATION=0x07`，`default: return "Reserved"`。
- Silicon Labs AN986（A2DP/AVRCP 应用笔记）给出的 attribute_id 表与之完全一致，并列出 event ID：`0x02 Track changed`、`0x09 Now playing changed`。

> 网上有资料称"0x003E 为歌词文本"——查无实据，且与内核实现和规范均不符（该说法连 Title/Artist 的 ID 都对反了）。不要采信。

推论：

1. 想在车机上看到歌词，**只能借用 Title 字段**（或 Artist / Album / Genre 三个文本位）。汽水音乐等 App 走的都是这条路。
2. 真正的"车机端渲染歌词"只有投屏类协议做得到——Android Auto / CarPlay / HiCar / CarLife 走 app 层 UI 投影（USB 或 WiFi），不是蓝牙 AVRCP。
3. **实现约束**：AOSP 蓝牙栈只把 `MediaMetadata` 的固定字段映射成上述 attribute，`MediaMetadata.extras` 里的自定义 key **不会被转发**。所以可用文本位只有 `TITLE` / `ARTIST` / `ALBUM_TITLE` / `GENRE` 四个，别指望私有通道。

## 5. 两条实现路线

### 路线 A：替换播放项触发（确定可行，但有副作用）

```kotlin
player.replaceMediaItem(index, item.buildUpon()
    .setMediaMetadata(md.buildUpon().setTitle(currentLine).build()).build())
```

- 优点：一定能触发 `onMediaMetadataChanged`。
- 代价：同时触发 `onMediaItemTransition`。而 `MusicService.kt:1161` 的该方法**完全不判断 `reason`**，会连带执行：
  - `syncLocalListeningStatsFromPlayer(forceNewSession = true)` → 重复记听歌
  - `reportNavidromePlayback("starting")` → 重复 scrobble
  - `playbackTimerController.handleMediaItemTransition` → 计时器被重置
  - `schedulePlaybackSnapshotPersist()` → 每秒写一次播放快照
  - `replayGainProcessor.apply()` → 可能音量抖动
- 结论：要走 A 必须先给 `onMediaItemTransition` 加 reason / 来源守卫，改动面大，回归风险集中在听歌统计与 Navidrome 上报。

### 路线 B：伪造 metadata 事件（零副作用，本次采用）

自己维护一份 listener 引用，直接对它们调用 `onMediaMetadataChanged`，**完全不碰 inner player 的 playlist**。

- 优点：零副作用，不需要给 `onMediaItemTransition` 加守卫。
- 代价：依赖 Media3 未明确承诺的行为（callback 参数即缓存值、listener 可被直接调用），所以必须先验证——已验证通过（§9）。

## 6. 实现说明

### 6.1 `LyricTitlePlayer`

`data/service/player/LyricTitlePlayer.kt`，`ForwardingPlayer` 子类，三件事：

1. 覆盖 `addListener` / `removeListener`，在转发给 inner player 的同时**额外持有一份 listener 引用**（`CopyOnWriteArrayList`）。
2. 覆盖 `getMediaMetadata()`，返回 `metadataOverride ?: super.getMediaMetadata()`。
3. `publishMetadataOverride(override)`：记录覆盖值，然后对每个 listener 直接调用 `onMediaMetadataChanged(当前 mediaMetadata)`。传入 `null` 表示恢复原始 metadata。

注意点：

- 必须在**应用线程**调用，因为 `MediaSessionImpl` 在回调里会 `verifyApplicationThread()`。
- 直接调用绕过了 Media3 的 `ListenerSet`，这对 `MediaSession` 有效（它实现了 `onMediaMetadataChanged`），但对只实现 `onEvents` 的监听者无效——本项目里没有这种消费者。
- `metadataOverride` 在换歌时必须清空，否则旧歌的歌词会盖到新歌上。
- 内部用 `if (metadataOverride == override) return` 去重，天然满足"仅在文本变化时推送"。

### 6.2 Service 接入

- 新增字段 `lyricTitlePlayer: LyricTitlePlayer?`（最外层包装实例）与 `carLyricTitleController: CarLyricTitleController?`
- `wrapFadingPlayer()` 在最外层再包一层并记录实例
- 新增 `Player.unwrapLyricTitlePlayer()`，并把 `publishMediaSessionPlayer()` 里的解包链改为 `unwrapLyricTitlePlayer().unwrapMappingPlayer().unwrapFadingPlayer()`
- `publishMediaSessionPlayer()` 在换 wrapper 后调用 `carLyricTitleController?.onPlayerReplaced()`（见 §6.3「wrapper 身份」）
- `onCreate` 的 `serviceScope` 里调用 `startCarLyricTitle()`；蓝牙路由的真值与事件都由独立的 `CarLyricTitleOutputMonitor`（`data/service/player/`）持有：Service 只负责实例化（传入 `audioManager` / `contentResolver` / debuggable 标志 / `onRouteChanged = controller::signal`）并在 `onDestroy` 里 `stop()`。`AudioDeviceCallback` 的注册/注销、A2DP 判定与 debug 逃生标志都在该类内部
- `isCarLyricTitleOutputActive()` / `hasBluetoothA2dpOutput()`：输出路由判定（见 §6.4）

> `publishMediaSessionPlayer()`（MusicService L328-337 附近）是**唯一**的 wrapper 换代咽喉——`oldPlayer.removeListener(playerListener)` → `wrapFadingPlayer()` → `session.player = wrappedPlayer`。控制器的 listener 重挂与覆盖值重置都挂在这里，不需要另找位置。

### 6.3 运行期驱动：`CarLyricTitleController`

`data/service/player/CarLyricTitleController.kt`。单独成类而不是塞进 `MusicService`，是因为它有自己的跨歌状态（当前歌曲、cue 列表、上次推送的 cue 序号、下一个唤醒任务），放进 Service 字段会继续膨胀那个已有 30+ 播放状态字段的类。

#### 片段（cue）拆分：车机一个标题的宽度是有限的（2026-09-16 新增，2026-09-17 改为按宽度计量）

车机 title 字段的渲染**宽度**有限，整行发过去尾部会被直接截掉。实机观测：那台车机同一条标题栏放得下 **10 个汉字**，但因为西文字体明显更瘦，放得下 **约 30 个拉丁字母**。所以计量单位不能用字符数，要用**列数**（汉字/全角 = 3 列，其余 = 1 列），一个 30 列的上限同时服务两种文字，混排也落在同一条规则上。歌词加载后先摊平成 **cue 列表**再调度：

| 项 | 规则 | 常量 / 位置 |
|---|---|---|
| 列宽 | 汉字与全角形式（U+1100 Hangul Jamo / U+2E80–A4CF / U+AC00–D7A3 / U+F900–FAFF / U+FE30–FE6F / U+FF00–FF60 / U+FFE0–FFE6）= 3 列，其余 = 1 列 | `LyricsTimelineUtils.titleColumns` / `WIDE_GLYPH_COLUMNS = 3` |
| 段数 | `n = ceil(总列数 / 30)`，无上限 | `MAX_COLUMNS_PER_CUE = 30`（控制器 companion） |
| 段宽 | 尽量均衡，各段差 ≤1 列；余数给前几段 | `LyricsTimelineUtils.splitIntoSegments` |
| 切点 | 距理想切点 **10 列**内若有空格/标点就在那里切，避免把英文单词切两半；与「段不超 30 列」冲突时退回理想切点 | `MAX_CUT_LOOKBACK_COLUMNS = 10` |
| 关闭拆分 | 设置里关掉「拆分长行」时预算取 `Int.MAX_VALUE` ⇒ 每行只出 1 个 cue（`buildLyricCues` 在除法前先做 `总列数 <= 预算` 的提前返回，兼作溢出保护） | `UNSLICED_COLUMNS` |
| 每段时刻 | 第 k 段 = `行起点 + k × min(行长 ÷ 段数, 4s)`，**等分**（不按字数比例，也不用逐字时间戳） | `buildLyricCues` / `SEGMENT_DWELL_LIMIT_MS` |
| 行长 | 下一行时间戳 − 本行起点（逐字时间戳晚于下一行时按 `resolveLineEndTimeMs` 延后）；**末行**用 `player.duration − 行起点`，`C.TIME_UNSET` 时按 `SEGMENT_DWELL_LIMIT_MS`（4s）/段兜底 | `lineEndMs` |
| 空行（裸时间戳行） | 视为「上一句唱完」的记号，默认**不产生 cue**；只有它到下一条**有文字**的行（制作信息行也算）间隔 ≥ **6s**、或后面再无文字时，才产一个空文本 cue → 推送 null → 恢复真实曲名 | `standsForAGap` / `BLANK_GAP_LIMIT_MS = 6s` |

为什么回看距离也从 3 个字符改成 10 列：英文单词更长，按「3 个字符」回看常常够不到空格，切点会落在单词中间——而 10 列在中文侧正好是 3 个汉字（与旧行为一致），在英文侧是 10 个字母（够用）。这条改动有单测固定（`buildLyricCues_breaksOnWhitespaceInsteadOfMidWord` 的切点落在 `jumps` 内部，靠 10 列回看退到 `fox` 后的空格）。

**「行长」不是「这句词唱了多久」，所以每段要封顶（2026-09-18）**：上面的「行长」是这一行**占据的时间区间**——行尾换气、间奏、尾奏都算在里面，末行更是 `曲长 − 行起点`（可能几十秒）。等分后第二段就落进空档，听感是「唱完了才换字」。于是每段驻留改为 `min(行长 ÷ 段数, 4s)`：末行两段的第二段最晚 +4s（旧值可达十几秒），行尾有大空档的行由「空档中点」提前到 +4s。密词行（等分 ≤ 4s）**逐位不变**，单段行天然不受影响（只用 index 0），最后一段自然驻留到下一行。上限复用 `lineEndMs` 的未知时长兜底值——两者语义本就同源（一段最多在屏上待多久），于是「时长未知」那条兜底路径变成新规则的特例，行为逐位不变。单测见 `buildLyricCues_capsTheDwellOfALineFollowedByAGap` / `..._capsTheDwellOfTheLastLineAtTheTrackTail` / `..._capsOnlyWhenASegmentWouldOutlastTheLimit`。**上限不做成设置项**（量小、不值得走五步接线，也就不暴露给用户）。

**空行是「唱完」的记号，不是「回到曲名」的指令（2026-09-18）**：LRC 在几乎每一句唱完处都写一个裸时间戳行（`[00:34.34]`，无文字）——一个真实文件是 105 行里 44 个空行。旧实现把空行当「清屏」⇒ 每句之间推一次 null ⇒ 车机标题在歌词与曲名之间来回闪（用户报「一直跳回标题」）。现在只有「到下一条有文字的行 ≥ 6s」的空行才算真空档（前奏 / 间奏 / 尾奏），其余整条跳过、屏幕停在上一句。**被跳过的空行仍然是上一行的结束时刻**（`lineEndMs` 用原始 `lines`，不因跳过而改变跨度）——这是本次最容易写错的一处，已用 `buildLyricCues_stillEndsALineAtTheBlankMarkerItSkips` 钉死。完整规则、判据与**真实歌词验证方法**见 **`docs/car-lyrics-blank-markers.md`**。

**去重键从「文本」改成「cue 序号」**：长行拆出的两段文字可能一模一样（叠句），只比文本会静默吞掉第二段。反过来，**重置切法（开关「拆分长行」）时必须把序号键一并作废**（`lastPublishedCue = CUE_UNPUBLISHED`）——新旧切法的第 5 号 cue 内容不同，沿用旧键会让开关看起来没生效。

#### 提前量：只在整曲开头加一次（2026-09-16 新增）

手机侧本来就已是「cue 起点即推送」，但 `0x09` 通知 → 车机重读 `GetElementAttributes` → 车机重绘这条**下游**链路要花 0.3–1 s，车机上看起来就是慢半拍。手机侧唯一能做的补偿是把**用于解析的 position 整体前移一个量**：

```kotlin
positionMs = player.currentPosition + syncOffsetMs + leadMs
```

- **全曲一个值**，不是每个 cue 各自算偏移——平移不改变 cue 之间的间隔（间隔仍由「行长 ÷ 段数」决定），所以误差恒定、不会累积。
- **值来自设置项**（2026-09-16 定稿）：**设置 → 播放 → 车载蓝牙 → 歌词提前量**，0–1 s 滑杆、每 0.1 s 一档；与同一子节上方的开关**联动**——开关关闭时滑杆置灰（`SliderSettingsItem(enabled = ...)`），不隐藏，免得用户找不到这个选项去哪了。存储 key `car_lyric_title_lead_ms`，默认 `500`（`UserPreferencesRepository.DEFAULT_CAR_LYRIC_TITLE_LEAD_MS`），存储与读取都 `coerceIn(0, 1000)`。
- **为什么最终做了设置项**：中途曾定为「只留一个代码常量，连 debug 标志也不要」（理由：提前量描述的是车，不是用户偏好）。那条理由本身没错，漏掉的是**换车是真实场景**——常量意味着换一台车就要重新构建一次 APK，而这个数是用户能感知、也能自己判断方向的（"歌词总是慢半拍"→ 调大）。滑杆的 0.1 s 步长则是为了不让人去猜"该填 300 还是 800"。
- 改动即时生效：控制器 `start()` 里 `collect(carLyricTitleLeadMsFlow)`，值一变就 `signal()` 重算，不等下一行边界。
- **代价**：每段的**收尾也提前同量**（唱到该段最后 `lead` ms 时屏幕已经切到下一段）。单一 Title 字段下做不到「只提前开头不提前结尾」；`lead ≤ 1 s` 时基本不可察觉。
- **与 `lyricsSyncOffset` 叠加**：后者是用户为**这首歌**在 App 内歌词页调的偏移，语义不同，各管各的。
- 只作用于车机标题这条路径；App 内歌词页不受影响（本地 60 fps 重绘，提前会让高亮提前跳字）。

#### 调度模型：按 cue 边界自调度，而不是固定间隔轮询（2026-09-14 修订）

Media3 **没有位置回调**，`getCurrentPosition()` 只能采样，所以"完全不采样"做不到——App 内的歌词滚动本身也是采样：`PlaybackStateHolder.startProgressUpdates()` 是 `_currentPosition.subscriptionCount` 门控的 ticker（滑块 250ms / 迷你播放器 1000ms / **息屏 1000ms**，订阅数归零即停）。**因此不能复用它的位置流**：车机场景恰好是"息屏 + 后台"，composable 到 STOPPED、订阅数归零，ticker 直接停掉。而且它属于 `presentation` 层，反向依赖就是跨层。

但**采样时刻没必要均匀**。本控制器按下一个 cue 边界自调度：

- 唤醒时刻 = `delay(min(下一 cue 时刻 − 当前位置, 5s) / speed)`，唤醒后用**实时** `position` 重算 → 不累积漂移。（两边的坐标系一致：`position` 已含 `LEAD_MS`，cue 时刻不含，所以提前量不参与这段算术。）
- 一次唤醒对应一个 cue（4 分钟 60 行的歌约 60–90 次，长行拆 2 段），而不是每秒 2 次；**暂停 / 关闭 / 无歌词时 0 次**。
- `WATCHDOG_INTERVAL_MS = 5s` 上限是自愈兜底：固定轮询天然"错过事件也会自愈"，事件驱动必须显式补这一层，否则漏一个事件就会让标题静默滞留。
- `MIN_WAKE_UP_DELAY_MS = 100ms` 下限：边界已经过去时不会自旋。
- **变速播放**：歌词时间戳是墙钟时间，`setPlaybackSpeed()` 之后必须 `/ speed`。固定轮询在这一点上天然免疫，这是切换方案唯一新增的算术风险。
- 首个 cue 之前（前奏）唤醒目标是 `cues.first().timeMs`；最后一个 cue 之后不再排唤醒。

两个协程：

1. **订阅开关**（`carLyricTitleEnabledFlow`）：值变化时更新 `enabled`；**关闭时立即** `publishMetadataOverride(null)` **并取消待唤醒**，不等下一个边界——否则车机会残留上一行歌词直到换歌。
2. **事件入队 → tick**（`Channel(CONFLATED)` 单消费者）：每 tick 依次判定，任一不满足就回收覆盖值、取消待唤醒并记录原因：

| 判定 | 不满足时 |
|---|---|
| 开关已打开 | 恢复原标题 |
| 当前输出是蓝牙 A2DP | 恢复原标题 |
| 已加载歌曲（`mediaId` 能查到 Song） | 恢复原标题 |
| 该歌有**同步**歌词（`synced` 非空） | 恢复原标题 |

全部通过后取 `resolveCueIndex(cues, position + syncOffsetMs + LEAD_MS)`，**仅当 cue 序号变化时**才推送，随后排下一次唤醒。

事件源（都只做一次 `signal()`，不携带语义）：

| 来源 | 覆盖的场景 |
|---|---|
| `Player.Listener.onEvents`（挂在最外层 wrapper 上） | seek / 播放暂停 / 换曲 / 变速 / 加载完成——`onEvents` 是 Media3 的合并事件回调，一个方法就够，且非热路径 |
| `AudioDeviceCallback`（`MusicService` 注册） | 蓝牙连接 / 断开——让路由变化**立即**生效，不必等下一行歌词 |
| `carLyricTitleEnabledFlow` | 开关变化 |
| `MusicService.publishMediaSessionPlayer()` → `onPlayerReplaced()` | wrapper 换代（交叉淡入淡出 / 引擎切换） |
| 歌词加载任务收尾 | 有了行才排得出唤醒 |

其他设计点：

- **换歌处理**：`mediaId` 变化时立刻清覆盖值 + 取消上一个歌词加载任务与待唤醒，再异步加载新歌歌词，避免上一首的行短暂盖在新歌标题上。
- **歌词来源**：`musicRepository.getLyrics(song)`，默认 `EMBEDDED_FIRST`（内嵌标签 → 远程 API → 本地 `.lrc`）。冷缓存时可能走网络，与打开歌词页行为一致；结果会持久化，之后离线可命中。
- **同步偏移**：叠加用户为这首歌设置的 `lyricsSyncOffset`，保证车机与 App 内歌词页显示同一行。
- **线程**：tick 全程在 `Dispatchers.Main.immediate`（`publishMetadataOverride` 必须在应用线程）；歌词加载在 IO 上，不阻塞调度。
- **去抖**：`LyricTitlePlayer` 内部的 `metadataOverride == override` 比较**不可靠**——`MediaMetadata` 内嵌 `Bundle`，新构造的 `Bundle` 不保证相等。所以 `lastPublishedCue`（存 cue 序号；`CUE_UNPUBLISHED = -2` 表示从未推送、`CUE_TRACK_TITLE = -1` 表示已恢复真实曲名）是必需的。
- **路由判定不缓存**：每次 tick 直接读 `AudioManager.getDevices()`。tick 已稀有到"每行一次"，比原先"每 500ms 一次 + 2s 缓存"的实际调用频率还低。`AudioDeviceCallback` 只作为**事件源**，不作为真值来源——真值每次重读，缓存不会滞留。`SystemClock` 与 `lastRoutingCheckUptimeMs` 随之删除（那个哨兵以 `Long.MIN_VALUE` 初始化时 `now - Long.MIN_VALUE` 溢出恒为负，曾让整条路由判定变成死代码，见 §9.3）。
- **wrapper 身份**：wrapper 每次换代都是**新实例**（`metadataOverride = null`），而 `lastPublishedCue` 会让同值不再重发。`onPlayerReplaced()` 同时把 `lastPublishedCue` 复位为 `CUE_UNPUBLISHED` 并让下次 tick 重挂 listener，避免"开启交叉淡入淡出后，换曲时约一行时长显示真实曲名"。
- **异常隔离**：tick 外层 `runCatching`，单次失败不至于杀死循环——否则功能会静默失效，用户侧毫无提示。

#### 为什么目标是"按行唤醒"而不是"零定时器"（2026-09-14 定案）

播放期间**做不到零定时唤醒**，因为 Media3 自身就在做周期性位置刷新：`MediaSessionImpl.schedulePeriodicSessionPositionInfoChanges()` 在 `isPeriodicPositionUpdateEnabled`（Builder 默认 `true`）、`sessionPositionUpdateDelayMs > 0`（常量 `DEFAULT_SESSION_POSITION_UPDATE_DELAY_MS = 3_000`）且 `isPlaying() || isLoading()` 时 `applicationHandler.postDelayed()` 重排自身；本仓没有关它（`MusicService` 只调了 `setSessionActivity`）。核实版本：`androidx.media3:media3-session:1.11.0`。

| | Media3 周期位置刷新 | 本控制器行边界唤醒 |
|---|---|---|
| 门控 | `isPlaying() \|\| isLoading()` | 开关 ∧ A2DP 输出 ∧ 有 synced 歌词 ∧ `isPlaying` ∧ 还有下一行 |
| 受设置开关控制 | **否**（框架既有行为，未改动的上游同样存在） | 是 |
| 播放中 | 每 3s 一次 | 每行一次 |
| 打开开关但不播放 | 无 | 无 |
| 关闭开关 | **照旧每 3s** | **0**（实测：播放中关开关后 14s 零日志） |

派发目标 `dispatchOnPeriodicSessionPositionInfoChanged()` 只遍历 `getConnectedControllers()`。本 App 自身持有 `MediaController`（`MediaControllerFactory` + `SessionToken`），所以该 tick 确实会派发进本进程并落到主线程，但**不触及应用侧 `Player.Listener`**，因此不会唤醒本控制器的 tick 循环——两条定时器同时存在却互不干扰。

三条"事件驱动"候选经回调表逐条核实，都不通：

| 候选 | 实情 |
|---|---|
| `Player.Listener` | 35 个回调里位置相关**只有** `onPositionDiscontinuity`（仅 seek / period 切换 / repeat 触发） |
| `AnalyticsListener` | 音频回调全是**一次性**的：`onAudioPositionAdvancing`（每次起播一次）、`onAudioUnderrun`、`onAudioSinkError`… 无周期性位置回调 |
| `AudioProcessor` | 接口只有 `configure` / `queueInput` / `getOutput` / `isActive` / `isEnded` / `queueEndOfStream` / `reset`，**拿不到时间戳** |

唯一能摆脱"自己的定时器"的路是**在 PCM 链上按 `AudioFormat` 数帧**（本仓有先例：`DualPlayerEngine.buildAudioSink` 用 `DefaultAudioSink.Builder(...).setAudioProcessorChain(...)` 挂了 `HiResSampleRateCapAudioProcessor` / `SurroundDownmixProcessor`）。**已否决**，一条条都是硬伤：

1. 它不是"无轮询"，只是把检查挪到音频线程——`queueInput` 按 buffer 调用（约每 10–100ms），**调用次数比现在更多**。
2. 量的是"已入队"而非"已播出"，超前一个 buffer + AudioTrack 缓冲（约 50–200ms），需要额外做延迟补偿。
3. 双引擎下不成立：交叉淡入时有两个 ExoPlayer → 两个 processor，还得判断哪个是 display player。
4. 部分输出模式链根本不存在：`audioOutputMode.usesUnmodifiedMedia3AudioSink` 为真时走 `super.buildAudioSink`，新 processor 不会被调用，需要回退路径。
5. 在音频线程上跑，阻塞即 underrun / 爆音——最不可接受的一点。

**结论：不做。** 收益上限是"每 3 秒省掉一次与框架 tick 同量级的主线程唤醒"，而框架那一半关不掉；`setPeriodicPositionUpdateEnabled(false)` 虽能一并关掉，但那是给 Android Auto / Wear / 系统 Media3 客户端保持进度实时的，关掉会让它们的位置显示变陈旧——属于**改变既有播放行为**，与本功能"关闭后零影响"的约束相反。

> 若将来动机是**同步精度**（歌词切换偏早/偏晚）而非功耗：瓶颈不在 `delay()` 的调度抖动（ms 级），而在**对端车机何时重读标题**——只能靠真机 btsnoop 观测，见 §11.1。

### 6.4 门控规则：什么时候才真的启用

三个条件同时满足才改写标题：

1. **设置开关打开**（默认关）；
2. **当前输出是蓝牙 A2DP 设备**（`AudioManager.getDevices(GET_DEVICES_OUTPUTS)` 含 `TYPE_BLUETOOTH_A2DP`）；
3. **当前歌曲有同步歌词**。

第 2 条为什么必要：覆盖值对**所有** `MediaSession` 消费者可见，不只是车机——通知栏、锁屏、Windows SMTC 读的是同一份 `MediaMetadata`。没有这条，插着有线耳机看手机时，歌曲标题的位置也会变成歌词。

关于「**车机是否支持**」：Android 没有公开 API 查询对端的 AVRCP 版本，而且**不需要**——不支持元数据的车机根本不会发 `GetElementAttributes`，覆盖值对它没有任何影响。所以"设备是否支持"在手机侧既无法探测、也不必探测；能探测且必须探测的是"我们自己是否在走蓝牙输出"。

> 调试便利：debug 构建额外识别全局设置 `pixelplayer_car_lyric_title_force_a2dp`
> （`adb shell settings put global pixelplayer_car_lyric_title_force_a2dp 1`），用于在没有 A2DP 消费端的模拟器上验证。release 构建忽略它。

### 6.5 行解析工具提取

`resolveCurrentLineIndex` / `resolveLineEndTimeMs` 原本是 `LyricsSheet.kt` 里的 `internal` 顶层函数，只有 Composable 侧能用。因为 `data` 层也需要，移到 `utils/LyricsTimelineUtils.kt`（`data` 反向依赖 `presentation` 是架构坏味道），逻辑一字未改；`LyricsSheet.kt` 与既有 `LyricsSheetLogicTest` 改为 import。新增 `LyricsTimelineUtilsTest` 覆盖边界：空时间轴、首行之前、行区间映射、末行之后、seek 回跳、逐字时间晚于下一行起点。

2026-09-16 在同一文件追加 **cue 层**（只给车机标题用，App 内歌词页仍走 `resolveCurrentLineIndex`）：`LyricCue` 数据类、`buildLyricCues`（拆段 + 等分时刻 + 末行兜底 + 按时间戳稳定排序）、`resolveCueIndex`（`indexOfLast { timeMs <= position }`，比区间判定简单，末 cue 天然保持到曲尾）、`nextCueTimeMs`。**`nextLyricBoundaryMs` 随之删除**（唯一调用点是控制器，已改用 `nextCueTimeMs`）。

> `buildLyricCues` 末尾有一次 `sortedBy { it.timeMs }`：逐字时间戳晚于下一行起点时，本行末段的时刻可能越过下一行的首段，而 `resolveCueIndex` 依赖列表有序。稳定排序保证同刻 cue 的相对顺序不变。

> 早期版本曾用 `[SPIKE] m:ss` 占位载荷来验证推送通路（§9.1 记录的就是那一版），现已替换为真实歌词行，占位代码已删除。

## 7. 设置开关（已落地）

按 AGENTS.md 的"新增设置开关五步"接线，**默认关闭**（标题是每个已配对设备都可见的字段，必须显式选择加入）：

| # | 文件 | 变更 |
|---|---|---|
| 1 | `data/preferences/UserPreferencesRepository.kt` | `PreferencesKeys.CAR_LYRIC_TITLE_ENABLED`（key `car_lyric_title_enabled`）→ `carLyricTitleEnabledFlow` → `setCarLyricTitleEnabled()`；`CAR_LYRIC_TITLE_LEAD_MS`；`CAR_LYRIC_TITLE_SPLIT_LONG_LINES`（key `car_lyric_title_split_long_lines`，**默认 true**，2026-09-17） |
| 2 | `presentation/viewmodel/SettingsViewModel.kt` | `SettingsUiState.carLyricTitleEnabled` / `.carLyricTitleLeadMs` / `.carLyricTitleSplitLongLines`、`SettingsUiUpdate.Group2` 三个字段、combine 流列表**末尾**追加（`values[17]` 开关、`values[18]` 提前量、`values[19]` 拆分长行）、`state.copy()`、三个 setter |
| 3 | `presentation/screens/SettingsCategoryScreen.kt` | PLAYBACK 分类新增 `设置子节「车载蓝牙」` + `SwitchSettingItem` + 联动的 `SliderSettingsItem`（0–1000 ms、`steps = 9`、`enabled = uiState.carLyricTitleEnabled`）+ 「拆分长行」`SwitchSettingItem`（同一门控），highlight key `item_playback_car_lyrics` / `item_playback_car_lyrics_lead` / `item_playback_car_lyrics_split` |
| 4 | `presentation/settings/search/SettingsRegistry.kt` | 三条 `SettingSpec`：`playback_car_lyrics`（SWITCH，关键词 `car / bluetooth / avrcp / lyrics / title / head unit / scroll`）、`playback_car_lyrics_lead`（NAVIGABLE_CARD，关键词含 `lead / delay / advance`）与 `playback_car_lyrics_split`（SWITCH，关键词含 `split / cut / segment / long`） |
| 5 | `res/values/strings_settings.xml` + `res/values-zh-rCN/strings_settings.xml` | `setcat_car_lyrics_section` / `_title` / `_subtitle` / `_lead_title` / `_lead_subtitle` / `_split_title` / `_split_subtitle`，中英成对 |
| 6 | `res/drawable/rounded_directions_car_24.xml` | 新增图标（Material Symbols Rounded `directions_car`） |
| 7 | `presentation/screens/SettingsComponents.kt` | `SliderSettingsItem` 新增 `enabled: Boolean = true`（默认值，既有 4 个调用点不受影响）：置灰时 label / 数值 / 滑块一起降 alpha，滑块本身 `Slider(enabled = false)` |

位置：**设置 → 播放 → 车载蓝牙 → 标题显示歌词**。放在 PLAYBACK 而不是 APPEARANCE 的「歌词页面」子节下，因为这是输出通道行为，与「耳机重连续播」同类。

**「拆分长行」（2026-09-17 新增）**：位置在同一子节的最后，**默认开**。开 = 把超过 30 列的歌词行切成多段依次发送；关 = 整行发出去，交给车机自己处理（能跑马灯滚动的车机会滚，会截断的车机会丢掉尾部）。它是给「车机能滚长标题」这一类设备留的逃生口——手机侧无法探测车机到底滚不滚，而这个判断用户一眼就能做。

**提前量做成设置项**（2026-09-16 最终定稿，推翻了当天早些时候的「只留代码常量」）：位置就在上面那个开关的正下方，**设置 → 播放 → 车载蓝牙 → 歌词提前量**，0–1 s、每 0.1 s 一档，开关关闭时**置灰**（不隐藏）。取舍与理由见 §6.3「提前量」小节——一句话：它确实描述车而不是偏好，但**换车是真实场景**，而这个数用户能感知、也能自己判断方向（"歌词总是慢半拍" → 调大），为它重新构建一次 APK 才是更大的成本。

## 8. 测试分层（不需要车机即可覆盖大部分）

车机只是 MediaSession 的一个消费者，所以大部分行为可以在本地验证。

| 层级 | 目的 | 方法 |
|---|---|---|
| L1 单测 | 行解析与拆段正确性 | `resolveCurrentLineIndex`（行间空隙、seek 回跳、超末行、空列表）、`buildLyricCues`（列宽计量、拆段字数与时刻、标点优先不切词、末行兜底、关闭拆分的整行输出）、`resolveCueIndex` / `nextCueTimeMs`（前奏、边界、末 cue）；共 32 例 |
| L2 状态可见性 | **最接近车机视角，零成本** | `adb shell dumpsys media_session \| grep -A 25 com.lostf1sh`，看 `metadata: ... title=...` |
| L3 AVRCP 协议真相 | 是否被车机误判为换曲 | 开发者选项启用"蓝牙 HCI 信息收集日志" → 连蓝牙设备播放 → `adb pull /sdcard/btsnoop_hci.log` → Wireshark（原生解析 AVRCP）过滤 `btavrcp`，观察 `Track Changed`(0x02) 与 `Now playing changed`(0x09) 的次数与间隔 |
| L4 端到端显示 | 肉眼确认 | Windows SMTC（笔记本当车机）/ Android Auto DHU（走 USB+MediaBrowser，非 AVRCP）/ 真车机 |

L2 最值得先做——**它直接观察的就是车机将要读到的那一份数据**，成本几乎为零。

## 9. 验证记录

### 9.0 测试环境与过程

设备与产物：

| 项 | 值 |
|---|---|
| 目标 | `pixel6` AVD（`x86_64`，android-34），`-no-window -no-snapshot-load -no-boot-anim -no-audio -gpu swiftshader_indirect` |
| APK | `pixelplayeross-universal-0.4.1-pisces.1-debug.apk` |
| 为什么是 universal | 项目默认只出 `arm64-v8a`，`x86_64` 模拟器装不上；`-Ppixelplayer.enableAbiSplits=false` 会把同目录的 arm64 产物一起清掉，所以**先备份、验证后重建** |
| 安装 | `adb install -r -d <apk>`（`-d` 允许同版本号覆盖安装） |

无头交互（没有窗口，全靠 adb）：

1. 启动 App：`adb shell monkey -p com.pisces312.piscesplayer.debug -c android.intent.category.LAUNCHER 1`
2. 定位控件：`adb shell uiautomator dump /sdcard/ui.xml` → `tr '<' '\n<'` → grep `text=` / `checkable="true"` 取 `bounds` → `input tap`。
   **开关的判据是 `checkable="true"` 节点的 `checked` 属性**，不能靠类名——dump 出来是 `android.view.View`。
3. 滚动：`input swipe 540 1600 540 500 300`（上下若干次直到目标可见）。

三个观察点：

| 观察点 | 命令 | 期望 |
|---|---|---|
| 车机视角的数据 | `dumpsys media_session` 的 `metadata: size=…, description=<title>, …` | 与开关状态一致 |
| 队列是否被动过 | 同一份 dump 的 `active item id=` / `queueTitle=null, size=` | 全程不变 |
| 推送频率 | `adb logcat -d -s MusicService_PiscesPlayer \| grep "car lyric title"` | 开关关闭时无推送行 |

> `dumpsys media_session` 之所以能代表车机：蓝牙 AVRCP 的 `GetElementAttributes` 由 AOSP 蓝牙栈从 `MediaSession` 的 legacy stub 读**同一份** `MediaMetadata`（§3.1）。

| # | 待验证假设 | 方法 | 结论 |
|---|---|---|---|
| 1 | MediaSession 接受伪造的 `onMediaMetadataChanged`（路线 B 可行性） | 模拟器 pixel6（x86_64）+ universal debug APK，`dumpsys media_session` 观察 | **通过** |
| 2 | 开关默认关闭时标题保持原样、打开后才改写 | 同上，切换设置开关并对比 `dumpsys` | **通过**（见 §9.2） |
| 3 | `replaceMediaItem` 是否真的触发 `onMediaItemTransition`（路线 A 的守卫范围） | 打日志 + 播放观察统计是否被重复记录 | 未验证（走 B 则不需要） |
| 4 | 蓝牙栈在 mediaId 不变、仅 metadata 变化时发的是 `Now playing changed` 还是 `Track changed` | 真机 + 蓝牙设备抓 btsnoop | 未验证（模拟器无蓝牙音频链路） |
| 5 | 真实歌词行按时间轴推进（端到端） | 45s 曲目 + 8 行 LRC（每 3s 一行），连续采样 `dumpsys` 对照 `position` | **通过**（见 §9.3） |
| 6 | 三者同时满足才启用：开关开 **且** 蓝牙输出 **且** 有同步歌词 | 增删 debug 路由标志 / 切换开关 / 有歌词与无歌词曲目对照 | **通过**（见 §9.3） |

### 9.1 假设 1：通过

环境：`pixel6` AVD（x86_64，android-34）、universal debug APK（`-Ppixelplayer.enableAbiSplits=false`）、`-no-window -no-audio`。

`adb shell dumpsys media_session` 抓到的 session：

```
package=com.pisces312.piscesplayer.debug
active=true
controllers: 4
state=PlaybackState {state=STOPPED(1), position=17936, ..., active item id=88}
metadata: size=12, description=[SPIKE] idle #7, <unknown>, 1
queueTitle=null, size=89
```

logcat（`MusicService_PiscesPlayer`）同步显示埋点每秒推送，且运行在主线程（tid 与 pid 相同）：

```
09-14 00:51:09.528  4263  4263 D MusicService_PiscesPlayer: car lyric title spike: [SPIKE] idle #2
09-14 00:51:10.533  4263  4263 D MusicService_PiscesPlayer: car lyric title spike: [SPIKE] idle #3
...
09-14 00:51:14.567  4263  4263 D MusicService_PiscesPlayer: car lyric title spike: [SPIKE] idle #7
```

结论与解读：

1. 伪造的 `onMediaMetadataChanged` 被 MediaSession **接受**：`metadata.description` 变成了埋点写入的值，即 `session.playerInfo.mediaMetadata` 确实被更新。这一份数据正是蓝牙 AVRCP 栈的读取源。
2. **队列未被触碰**：`active item id=88`、`size=89` 在推送期间保持不变；`PlaybackState` 也未出现 transition 引发的抖动。代码层面同样可证——`publishMetadataOverride()` 只调用 listener 回调，不对 inner player 做任何 playlist 操作，所以不可能产生 `onMediaItemTransition` 及其副作用。这正是路线 B 相对路线 A 的核心优势。
3. 回调必须在应用线程：埋点日志的 pid/tid 相同（4263/4263），满足了 `MediaSessionImpl` 里的 `verifyApplicationThread()`。
4. **本次验证覆盖不到的部分**：模拟器没有蓝牙音频链路（`BluetoothMediaBrowserService` 的 session 一直是 `error=Bluetooth audio disconnected`），所以「蓝牙栈实际发出的是 `Track changed` 还是 `Now playing changed`」「是否被车机误判为换曲」仍需真机 + 蓝牙设备抓包验证（假设 4）。

### 9.2 假设 2：通过（开关门控）

同一环境，用 UI 自动化（`uiautomator dump` 定位 + `input tap`）在**设置 → 播放 → 车载蓝牙 → 标题显示歌词**上切换开关，每一步都对比 `dumpsys media_session`：

| 步骤 | 开关节点 | `metadata.description` | `car lyric title spike` 日志 |
|---|---|---|---|
| 默认（未动开关） | `checked="false"` | `雨落哗声-安安_豆包_20260620-171126`（真实曲名） | **0 条** |
| 打开开关 | `checked="true"` | `[SPIKE] idle #9` | 每秒 1 条，编号连续递增 |
| 关闭开关（T+3s） | `checked="false"` | `雨落哗声-安安_豆包_20260620-171126` | 停在 `#16` |
| 关闭后 T+8s | `checked="false"` | 仍为真实曲名 | 无新增 |

三条结论：

1. **默认关闭是真的**：全新安装（未触碰开关）时标题是真实曲名，埋点日志一条都没有——发布循环在 `enabled == false` 时整轮跳过，不产生任何推送。
2. **开启后标题被改写**：`metadata.description` 变成 `[SPIKE] ...`，说明开关 → 偏好项 → 控制器标志 → 轮询 → `publishMetadataOverride()` 全链路连通。
3. **关闭立即恢复**：T+3s 时（早于"等下一个 tick 才清理"的做法）标题已恢复为真实曲名，且有日志停在 `#16` 佐证推送已停止。这验证了 §6.3 里"关闭时立刻 `publishMetadataOverride(null)`"的必要性——否则车机会残留上一行歌词直到换歌。

**队列仍然零改动**：整个过程 `active item id=88` 保持不变，与假设 1 的结论一致。

**设置搜索**：在设置搜索框输入 `avrcp`（只在 `SettingsRegistry` 的 `keywordsStatic` 里出现），返回结果为「播放 › 标题显示歌词」+ 其副标题，确认第 4 步注册生效。

### 9.3 真实歌词 + 蓝牙门控：通过

这一轮把占位载荷换成真实歌词行，并补上"A2DP 输出"门控。模拟器**没有 A2DP 消费端**，因此用 debug 专用标志 `pixelplayer_car_lyric_title_force_a2dp` 伪造路由（见 §6.4）。

**准备一首带同步歌词的曲目**。模拟器曲库里的本地文件，歌词有三个可能来源，最终选了最稳的一条：

| 方案 | 结果 |
|---|---|
| 同目录同名 `.lrc` | ✗ App 未声明 `MANAGE_EXTERNAL_STORAGE`，targetSdk 37 的分区存储下读不到非媒体文件（`appops set` 也补不上：未声明的权限无法授予） |
| 内嵌标签 | ✓ ffmpeg 写 ID3v2 `USLT`（`-metadata lyrics="[mm:ss.xx]..."`），TagLib 读出 `LYRICS` 键，`LyricsUtils.parseLyrics` 解析成逐行时间轴 |
| Room `songs.lyrics` 字段 | ✓ 直接 `UPDATE`，不动文件、不改 id（最终采用） |

**踩坑：MediaStore 的 id 不稳定。** 用内嵌标签方案覆盖 mp3 后，MediaStore 把它当成"删除 + 新增"，id 随之变化；而播放队列里的项仍持有旧 id（如 `1000001209`），于是 `getSong(mediaId)` 查不到 → 走"无歌曲"降级。这不是缺陷，而是"恢复历史队列快照 + 曲库重扫"的正常组合，且降级行为正确（保持原标题）。为了把"有歌词"这一环验证稳，最终改为写 Room 字段，避免触碰文件：

```bash
# 给 id=1000001891 的曲目写入 8 行 LRC（每 3 秒一行）
# char(10) 拼换行，绕开 adb shell 的转义问题
adb shell "run-as com.pisces312.piscesplayer.debug sqlite3 databases/pixelplayer_database \
  \"update songs set lyrics = '[00:00.00]CARLYRIC alpha' || char(10) || '[00:03.00]CARLYRIC bravo' ... where id = 1000001891\""
```

**正路径**（开关开 + 路由标志开 + 有同步歌词）：从搜索结果点播该曲目，采样 `position` 与 `metadata.description`：

| `position` | `metadata.description` |
|---|---|
| 3241 | `CARLYRIC bravo` |
| 6162 | `CARLYRIC charlie` |
| 9090 | `CARLYRIC delta` |
| 12241 | `CARLYRIC echo` |
| 15136 | `CARLYRIC foxtrot` |
| 18033 | `CARLYRIC golf` |
| 21225 | `CARLYRIC hotel` |

日志时间戳间隔恰好 ~3.0s，与 LRC 时间轴一致：

```
01:34:55.300  car lyric title: CARLYRIC alpha
01:34:57.819  car lyric title: CARLYRIC bravo
01:35:00.838  car lyric title: CARLYRIC charlie
01:35:03.853  car lyric title: CARLYRIC delta
```

**路由门控实时生效**（播放中直接增删标志，无需重启 App）：

| 操作 | 日志 | `metadata.description` |
|---|---|---|
| `settings delete`（≈蓝牙断开） | `bluetooth output inactive` → `idle: bluetooth output not active` | 恢复为 `Night Tone` |
| `settings put ... 1`（≈蓝牙连上） | `bluetooth output active` → `active: 8 synced lines` | 回到 `CARLYRIC hotel`（当前行） |

重新连上时**没有重新加载歌词**（复用已加载的 8 行），缓存路径也正确。

**开关门控**（在正在播放有歌词曲目的状态下切换）：

| 开关 | 日志 | `metadata.description` |
|---|---|---|
| 关 | `toggle off` → `idle: toggle off` | `Night Tone`（真实曲名） |
| 开 | `toggle on` → `active: 8 synced lines` | `CARLYRIC hotel` |

**无歌词曲目**：切到没有歌词的曲目，日志 `loaded 0 synced lines` → `idle: no synced lyrics`，标题保持真实曲名。

**全程队列零改动**：`active item id` 与队列长度始终不变，与 §9.1 结论一致。

**排查记录（一个值得留下的失败）**：第一版实现把路由缓存哨兵写成 `lastRoutingCheckUptimeMs = Long.MIN_VALUE`，判定式为 `now - lastRoutingCheckUptimeMs < INTERVAL` —— `now - Long.MIN_VALUE` **溢出**成负数，判定恒为真，"缓存值 false"被永久返回，**路由检查一次都没执行过**。表象极具迷惑性：门控"看起来正常工作"（标题确实没被改），实际机制根本没跑。改成 `0L` 初值、并把缓存变量改为可空类型（使首次结果必定打日志）之后才暴露。教训：拿极值当哨兵并参与减法之前，先想溢出。

### 9.4 调度模型改造（固定轮询 → 行边界自调度）：通过（2026-09-16 起边界改按 cue，见 §6.3）

2026-09-14 把 500ms 固定轮询换成"按行边界自调度 + 事件重算"（见 §6.3）。为了让唤醒节奏**可被观察**，控制器在每次排程时打一条 verbose 日志 `car lyric title: next wake in N ms (line K)`（release 由 `ReleaseTree` 抑制）——固定轮询没有这种日志，这条埋点本身是"设计可验证"的一部分。**2026-09-16 起该日志带 cue 序号与当前提前量**：`car lyric title: next wake in N ms (cue K, lead L ms)`；下面这张表是 2026-09-14 按「行」记录的原貌，当时长行尚未拆段、也还没有提前量，**数字无需按新实现重测**（拆段只把"每行一次"改成"每 cue 一次"，其余门控与自愈逻辑未动）。

| 场景 | 观察到的行为 | 结论 |
|---|---|---|
| 稳态播放 | 每条歌词恰好一条 `next wake in ~2800-3000 ms (line K)`，紧跟一次行发布；发布间隔 2.88–3.00s（与 LRC 的 3s 对齐） | 唤醒数 = 行数，不再与 500ms 挂钩 |
| 冷启动 | 只有 **1** 条排程（改造前同场景在 900ms 内出现过 25 次重复排程） | 幂等判断生效 |
| 暂停 9s | `car lyric title` 日志 **0 条**，无排程无发布，标题停在当前行 | 暂停 = 零唤醒 |
| 跳转（`dispatch previous`，30.9s → 2.9s） | 跳转后**立即**发布 `alpha`（~0.3s 内），旧位置的定时器被事件取代 | 事件重算覆盖 seek / 位置跳变 |
| 播放中删除路由标志 | 打点后 **1.88s** 出现 `bluetooth output inactive`，标题恢复 `Night Tone` | 看门狗 ≤5s 内自愈 |
| 关闭设置开关 | 打点后 **105ms** 出现 `toggle off`，标题立即恢复真实曲名；随后 7s 内 **0 条**日志 | 关闭态零唤醒（旧实现恒 2 次/秒） |
| 重新打开开关 | 打点后 **89ms** 内发布 `CARLYRIC hotel`，与 `position=26792`（最后一行）一致 | 开启即时生效 |
| 全程 | `active item id=0` 不变 | 队列零改动 |

单测 709 通过（本轮未新增/修改测试；控制器仍无单测，原因见 §11.6）。

**模拟器特有的两个现象**（均为环境属性，不是缺陷）：

1. **少数边界需要两次 tick**：定时器按墙钟到点，而模拟器的媒体时钟略微滞后，于是到点时 `position` 还没跨过边界 → 触发 `MIN_WAKE_UP_DELAY_MS`（100ms）那次短排程，100ms 后发布。表现为每行 1–2 次唤醒，仍比 500ms 轮询少 3–6 倍。真机以音频时钟为准，该现象应消失。
2. **事件源覆盖不到的一格**：当路由变化**不产生设备增删事件**时（例如本测试用的 `settings` 标志，或"在已连接的多个输出之间切换"），若此刻**播放正在推进**，靠看门狗（≤5s）自愈；若此刻已暂停/播完（没有任何已排程的定时器），则要等下一个播放事件才会重算。真实场景的蓝牙连/断都走 `AudioDeviceCallback` → 立刻 `signal()`，不受影响。**`AudioDeviceCallback` 这条路径在本模拟器上无法验证**（没有 A2DP 设备可连），是本轮唯一未覆盖的分支。

### 9.5 列宽计量 + 「拆分长行」开关：通过（2026-09-17）

环境：`pixel6` AVD（x86_64, android-34）、debug `0.4.2-pisces.1`、**arm64-v8a 产物直接安装**（不再需要 universal，见 AGENTS.md）、debug 路由标志 `pixelplayer_car_lyric_title_force_a2dp`。歌词仍走 Room 注入（不动文件）：一行 40 个拉丁字母、一行 21 个汉字、一行 5 个字母。

| 场景 | 观察 | 结论 |
|---|---|---|
| 拆分开（默认） | 日志 `active: 6 cues`；标题依次为 `abcdefghijklmnopqrstu` → `vwxyzabcdefghijklmnop` → 中文 7 字 ×3（`一二三四五六七` / `八九十一二三四` / `五六七八九十一`）→ `short line` | **英文 40 字母 = 20+20**（旧实现是 10×4，被切四段且每段只占半行宽）；**中文 21 字 = 7×3，与改前完全一致** |
| 拆分关 | 标题 = **整行** `abcdefghijklmnopqrstuvwxyzabcdefghijklmnop`（1 个 cue） | 关闭后不再切片，整行交给车机 |
| 开关即时生效 | 播放位置停在 0（模拟器音频管线 `BUFFERING`）时切开关，标题立刻从整行变回 `abcdefghijklmnopqrstu`，日志 `active: 6 cues` | `rebuildCues()` + 序号键复位生效——不必等换歌或下一行 |
| 反向验证门控 | 删掉路由标志 → `bluetooth output inactive` → `idle: bluetooth output not active`，标题回到 `Night Tone` | 门控未受影响 |

**设置项接线**：设置搜索输入 `car` 同时命中「标题显示歌词」「歌词提前量」「拆分长行」三条，开关 `checked="true"`（默认开），主开关关闭时它与滑杆一起置灰。

**本轮一个未定论的环境现象（记录备查）**：中途出现过约 2 分钟「控制器完全无日志」——进程存活、Activity 是 `ResumedActivity`、MediaSession 的 metadata 仍在更新、无 `FATAL`、无 `car lyric title: tick failed`；`am force-stop` 重启进程后恢复，此后一路正常。当时的使用方式是 `cmd media_session dispatch play/next` + 刚启动就恢复历史队列（不是 UI 点播）。**未定位根因**，也**未在改动前的 APK 上复现过**（本轮没跑旧版本对照），所以既不能认定与本改动有关、也不能排除。真机复核时留意「标题是否会在中途停住不动」；若出现，先看 logcat 里 `car lyric title` 行是否断流（断流 = tick 没跑；不断流 = tick 在跑但判定为 idle 或无变化）。

### 9.6 每段驻留上限 + 唤醒键 / 缓冲态：通过（2026-09-18）

环境同 §9.5，另：主开关与「拆分长行」均为**开**，`lead` 为默认 500 ms（偏好里没有 `car_lyric_title_lead_ms`，即从未拖过滑杆）。注入两行歌词（Room）：

```
[00:00.00]abcdefghijklmnopqrstuvwxyzabcdefghijklmnop   20+20 字母 → 2 段
[00:20.00]0123456789abcdefghijklmnopqrstuvwxyz          2 段，末行跨度 = 45.0 − 20 = 25.0 s
```

| 断言 | 观察（logcat `MusicService_PiscesPlayer`，2026-09-18 00:46:08 起） | 结论 |
|---|---|---|
| ① 每段驻留封顶 4 s | `08.062` 发第 1 段 → `12.351` 发第 2 段（**4.29 s**）；中间三条 `next wake` 依次 `5000` → `3500` → `100` ms | 生效。`3500 = 4000 − 500(lead)` 正是「第 2 段边界 = 行起点 + 4 s」；**旧公式**此行跨度 20 s ⇒ 边界在 10 s 位置，残差会是 9500 ms（被看门狗截成 5000 再重排），实际看到的是 3500 |
| ② 末行（跨度最大）同样封顶 | 第 2 行两段间隔 **4.86 s**（`28.329` → `33.187`） | 旧公式该段落在 32.5 s 位置（跨度 25 s ÷ 2）。多出的 0.86 s 来自模拟器音频管线停顿后的重排（同段内有 `next wake in 618 ms` 的重排行），不是公式误差 |
| ③ 缓冲态不再丢定时器 | 第 1 段与第 2 段之间连续三条 `next wake in 5000 ms (cue 1, lead 500 ms)`（`12.351` / `17.425` / `22.446`），间隔恰好 5 s 且 cue 序号不变 | 这三条只可能出自 `stalled` 分支（位置冻结 ⇒ 残差算不出，按看门狗兜底）。改前此处会 `stopScheduling()` 后**彻底安静**，与 §9.5 记录的那次「约 2 分钟无日志」机理吻合——但**本轮没有复现该现象本身**，所以只算「给已知现象提供了一条可解释路径」，不算定论 |
| ④ 蓝牙侧未受连带影响 | `media update timeout` 计数 **0**；`trySendMediaUpdate(): Metadata has been updated` 正常；`active item id=-1` 保持 | 闸门短路未回退（与 `avrcp-emulator-verification.md` §4 一致） |

**顺带观察（不是缺陷）**：进程重启后歌词是异步加载的，第 1 段在 position ≈ 3.65 s 才发出，于是第 2 段只隔 **0.8 s** 就跟着出现（边界是绝对时刻，不因迟发出而顺延）。真机上表现为「刚上车接续播放时前两句一闪而过」。

**未验证**：改动 ② 的运行时表现（播放中拖「歌词提前量」滑杆应立即重排）。运行时验证需要在播放过程中改 DataStore 偏好，而外部改文件 DataStore 不会重读、改完重启进程又会重建整个 cue 状态，等于测不到「中途改」；模拟器 UI 自动化又基本点不动（`avrcp-emulator-verification.md` §5）。**代码层依据**：`WakeUp` 已含 `leadMs` / `syncOffsetMs`，键不等即重排。真机验收时顺手拖一下滑杆即可，判据是当场出现新的 `next wake in … (cue K, lead L ms)`。

### 9.7 空行标记阈值：通过（2026-09-18）

数据来源与方法是本轮新增的部分，完整写在 **`docs/car-lyrics-blank-markers.md`**（含离线「抽真实歌词跑规则对照」与在线「模拟器装 debug 包跑管线」两条路径）。结论摘要：

| 指标（`如愿-王菲`，105 行 = 44 空行 + 61 有文字行） | 改前 | 改后 |
|---|---|---|
| cue 总数 | 130 | 89 |
| 回退真实曲名的次数 | **44** | **3** |

三处保留的回退都是真空档：`19.51 s`（后面 12.01 s 无文字，前奏）、`1:29.82`（30.56 s，间奏）、`3:12.93`（6.20 s）；其余 41 个换气空行（间隔 0.5–2.4 s）全部跳过。4:20 之后的制作名单区原本每 0.15 s 闪一次曲名（16 次）→ 0。

### 复现步骤

```bash
# 1. 构建（2026-09-18 更正）：模拟器虽是 x86_64，但本机 AVD 装了 arm64 转译，**默认的
#    arm64-v8a 产物直接可装**（AGENTS.md 已实测确认）。不要再用 -Ppixelplayer.enableAbiSplits=false
#    —— 那会顺手清掉同目录的 arm64 产物，还得重构建一遍。
./gradlew :app:assembleDebug

# 2. 装 & 授权 & 启动
adb install -r -d app/build/outputs/apk/debug/pixelplayeross-arm64-v8a-*-debug.apk
adb shell pm grant com.pisces312.piscesplayer.debug android.permission.READ_MEDIA_AUDIO
adb shell monkey -p com.pisces312.piscesplayer.debug -c android.intent.category.LAUNCHER 1

# 3. 打开开关：设置 → 播放 → 车载蓝牙 → 标题显示歌词
#    无头环境下先 dump 拿到标题的 bounds，switch 与标题同一垂直位置
adb shell input tap 970 213                   # 右上角齿轮
adb shell input tap 500 1234                  # 「播放」分类
adb shell input swipe 540 1600 540 500 300    # 向下滚 ×3
adb shell input swipe 540 700 540 1300 300    # 回滚一点，让该行进入可视区
adb shell uiautomator dump /sdcard/ui.xml && adb shell cat /sdcard/ui.xml | tr '<' '\n<' \
  | grep -oE 'text="标题显示歌词"[^/]*bounds="[^"]+"'
adb shell uiautomator dump /sdcard/ui.xml && adb shell cat /sdcard/ui.xml | tr '<' '\n<' \
  | grep 'checkable="true"' | grep -oE 'checked="[^"]+"[^/]*bounds="[^"]+"'
adb shell input tap 927 789                   # 与标题同一垂直位置的 switch

# 3'. 更快的等价路径：设置 → 顶部搜索框输入 avrcp（ASCII 才能 input text）→ 点结果行的 Switch
adb shell input tap 540 567                   # 搜索框
adb shell input text "avrcp"
adb shell uiautomator dump /sdcard/ui.xml && adb shell cat /sdcard/ui.xml | tr '<' '\n<' \
  | grep 'checkable="true"' | grep -oE 'checked="[^"]+"[^/]*bounds="[^"]+"'
adb shell input tap 927 625                   # 1080x2400 下结果行的 Switch

# 4. 伪造蓝牙 A2DP 路由（仅 debug 构建识别；模拟器没有 A2DP 消费端，见 §6.4）
adb shell settings put global pixelplayer_car_lyric_title_force_a2dp 1

# 5. 观察车机视角的数据 + 门控日志
adb shell dumpsys media_session | grep -A 12 com.lostf1sh | grep -E "metadata:|active item"
adb logcat -s MusicService_PiscesPlayer | grep "car lyric title"

# 6. 给某首歌注入同步歌词（不动文件，id 稳定），再播放它
#    char(10) 拼换行以避开 adb shell 的转义问题
adb shell "run-as com.pisces312.piscesplayer.debug sqlite3 databases/pixelplayer_database \
  \"update songs set lyrics = '[00:00.00]L0' || char(10) || '[00:03.00]L1' || char(10) || '[00:06.00]L2' \
     where id = <songId>\""

# 7. 反向验证：删掉门控标志应立刻恢复真实曲名
adb shell settings delete global pixelplayer_car_lyric_title_force_a2dp
```

## 10. 改动文件清单（截至本文档）

| 文件 | 状态 |
|---|---|
| `data/service/player/LyricTitlePlayer.kt` | 新增（出站 metadata 覆盖层） |
| `data/service/player/CarLyricTitleController.kt` | 新增（门控 + 歌词 cue → 标题；2026-09-16 改为按 cue 调度 + 全曲提前量 `LEAD_MS`） |
| `data/service/player/CarLyricTitleOutputMonitor.kt` | 新增（A2DP 路由真值 + 设备增删事件，2026-09-15 从 `MusicService` 收编） |
| `utils/LyricsTimelineUtils.kt` | 新增（`resolveCurrentLineIndex` / `resolveLineEndTimeMs` 从 `LyricsSheet.kt` 移入；2026-09-16 追加 `LyricCue` / `buildLyricCues` / `resolveCueIndex` / `nextCueTimeMs`，删除 `nextLyricBoundaryMs`） |
| `data/service/MusicService.kt` | 改（包装链 + 解包链 + 启动控制器 + A2DP 路由判定 + debug 逃生标志 + `publishMediaSessionPlayer()` 里通知 wrapper 换代 + `AudioDeviceCallback` 注册/注销；2026-09-16 把 `isDebuggable` 提为局部变量，供 A2DP 路由的 debug 标志使用） |
| `data/preferences/UserPreferencesRepository.kt` | 改（偏好项：`car_lyric_title_enabled` 开关 + `car_lyric_title_lead_ms` 提前量，默认值与上限见同文件 companion） |
| `presentation/viewmodel/SettingsViewModel.kt` | 改（UiState / Group2 / 开关与提前量方法） |
| `presentation/screens/SettingsCategoryScreen.kt` | 改（PLAYBACK 子节 + 开关 UI + 联动的提前量滑杆） |
| `presentation/screens/SettingsComponents.kt` | 改（`SliderSettingsItem` 加 `enabled`，供联动置灰；默认 `true`，既有调用点不变） |
| `presentation/settings/search/SettingsRegistry.kt` | 改（设置搜索注册：开关 + 提前量两条） |
| `presentation/components/LyricsSheet.kt` | 改（行解析改为 import utils） |
| `res/values/strings_settings.xml`、`res/values-zh-rCN/strings_settings.xml` | 改（中英成对） |
| `res/drawable/rounded_directions_car_24.xml` | 新增 |
| `test/.../utils/LyricsTimelineUtilsTest.kt` | 新增（行解析边界） |
| `test/.../presentation/components/LyricsSheetLogicTest.kt` | 改（改 import） |

## 11. 未完成 / 后续

1. **真机复核（已降级为可选）**：「改标题会不会被当成换曲」已由 AOSP 源码判定——只改 metadata 时 `queue=false`，不会触发 `Track Changed`(0x02)，故不会重置进度条，见 §3.5。**仍无法离线确认的只有对端行为**：车机是否注册了 `0x09`；没注册则收不到本次刷新。复核方法：开发者选项开启「蓝牙 HCI 信息收集日志」→ `adb pull /sdcard/btsnoop_hci.log` → Wireshark 过滤 `btavrcp`，看是否出现 `Now Playing Content Changed`。**顺带一提**：验证正路径不必等车机——任何蓝牙音频设备（耳机/音箱）都走同一条 A2DP 路径，标题同样会被改写。
2. **通知栏文案**：只改 `TITLE`，`ARTIST` 保持歌手名。曾考虑把 ARTIST 换成原曲名（车机两行都能用上），但通知栏与锁屏读的是同一份 metadata，会显示成「歌名 / 歌名」，得不偿失，故不采用。
3. **播放结束后**：实测标题停在最后一行歌词（歌曲播完 `state=STOPPED` 时仍是 `CARLYRIC hotel`）。若希望播完恢复曲名，在 `STATE_ENDED` 时清一次覆盖值即可。**注意**：播完后没有任何已排程的定时器，所以这时若外部条件变了（比如路由），要等下一个播放事件才会重算（§9.4 现象 2）。
4. **调试逃生口**：`pixelplayer_car_lyric_title_force_a2dp` 只在 debuggable 构建读取，release 忽略。不建议放开给 release。**改这个标志不会触发任何事件**（它不是设备增删），所以删除标志后最多等一个看门狗周期（≤5s）才恢复真实标题；若此刻已暂停/播完，需要手动触发一次播放事件（如 `adb shell cmd media_session dispatch previous`）让它立刻重算。
5. **云端曲目的歌词**：Navidrome 有 `getLyrics` 但未接入 `LyricsRepository`，Jellyfin 无该接口 → 云端曲目基本拿不到歌词，会走"保持原标题"的降级路径。想支持的话要先把服务端歌词接进 `LyricsRepository`。
6. **无同步歌词的歌**：只有 `plain` 歌词不会启用（没有时间轴就无法定位当前行）。若将来想做"整段歌词滚动"，那是另一套切片机制。
7. **控制器没有单测**：`CarLyricTitleController` 依赖 `Player` + 协程 + 真实时间，目前靠 §9.4 的日志验证。边界算术那部分已经能测了——`utils/LyricsTimelineUtils.kt` 的纯函数现有 38 例单测（2026-09-18）覆盖：`resolveCurrentLineIndex` 的空时间轴 / 首行前 / 区间映射 / 末行保持 / seek 回跳 / 逐字时间待定；`buildLyricCues` 的 30 字母整行不拆 / 31 字母拆两段 / 11 汉字拆两段（按 3 列计） / 中英混排落在同一条列预算上 / 短行 1 段 / 21 字 3 段 / 余数分配 / 空格优先不切词（10 列回看） / 段间隔等分 / 末行 `trackDuration` 与 `C.TIME_UNSET` 兜底 / `Int.MAX_VALUE` 预算下的整行输出 / 空行标记（换气跳过 / 长空档保留 / 结尾空行 / 跳过后仍终止上一行跨度） / 逐字时间戳导致的乱序 / 序号连续；`resolveCueIndex` 的前奏 -1 / 边界切换 / seek 回跳 / 末 cue 保持；`nextCueTimeMs` 的空表 / 首 cue 前 / 逐段推进 / 末 cue / 单调性。剩下的控制器单测需要注入时钟与协程调度器，暂未做。
8. **`AudioDeviceCallback` 路径未在模拟器验证**：模拟器没有可供连/断的 A2DP 设备。真机或任何蓝牙音频设备（耳机/音箱）都能覆盖这条分支。
9. **Media3 自带的每 3s 周期位置刷新**（已定案，不动）：`MediaSessionImpl` 在播放/加载中会排一次位置刷新，**默认开启，且与本功能的设置开关无关**——它是框架既有行为，在未改动的上游版本里同样存在。未播放时没有，关闭开关也照旧。详见 §6.3 的定案表与"为什么不做零定时器"。
10. **（历史）2026-09-15 那轮调研未改动任何 Kotlin 代码**：当轮的 §6.3 定案、门控矩阵与 §11.9 均为源码核实 + 模拟器实测的结论。2026-09-16 的 cue 拆分与提前量**已落地**，见 §6.3 两个新增小节与下面两条。
11. **提前量的默认值仍是待验证初值**：`UserPreferencesRepository.DEFAULT_CAR_LYRIC_TITLE_LEAD_MS = 500`，唯一依据是下游链路 0.3–1.0 s 这个量级。真机若确认别的值更合适，改这个默认常量即可（只影响从未调过滑杆的设备）；用户在设置里选过的值存在 `car_lyric_title_lead_ms` 里，优先于默认值。**注意默认值只在这一处定义**——控制器里没有常量，它读的是偏好流（`leadMs` 字段初值为 0，即"不提前"，是流首次发射前的安全方向）。
12. **下游刷新时机仍未实测**：`lead` 要补偿的究竟是"手机发通知 → 车机重绘"的全链路还是其中一段，只有真车能确认。`btsnoop` 能看到 `GetElementAttributes` 的响应时刻，但看不到车机把它画到屏上的时刻，所以**耳朵 + 车机是唯一判据**。此外 `MAX_COLUMNS_PER_CUE = 30`（= 10 个汉字 = 约 30 个拉丁字母，3 列/汉字）也是实机观测值（车机 title 字段宽度），换车可能需要复核——复核方法就是关掉「拆分长行」看整行是否被截断，或直接调这个常量。
13. **（2026-09-17）列宽计量与「拆分长行」开关**：旧实现按**字符数**拆（`MAX_CHARS_PER_CUE = 10`），对英文等于每段只发 10 个字母、白白浪费三分之二的标题宽度，一行 40 字母被切成 4 段。现改为按**显示列**（汉字 3 列、其余 1 列、上限 30 列），中文行为不变、英文每段 30 字母；回看距离同步改为 10 列。同轮加入设置项「拆分长行」（默认开）作为逃生口。模拟器端到端已验（§9.5），**未做**车机侧实测：新列宽是否真的正好填满宽度、关闭拆分后该车机是滚动还是截断，只有真车能定。
14. **控制器 tick 中途断流（未定位，2026-09-17 记录）**：见 §9.5 末尾。模拟器上出现过一次「进程/会话都正常但控制器一条日志都不出」，`force-stop` 后恢复；无法判定根因，也无法判定是否与本次改动相关。真机若复现「歌词标题停住不动」，这是第一个要看的线索。
15. **（2026-09-17）车机标题「卡住」= 蓝牙进程的 AVRCP「元数据同步」闸门**：现象是车机标题长时间停住、安静 2 秒后跳到当前行。根因不在我方（发布是 fire-and-forget、不等 ack），而在 AOSP 蓝牙的 `MediaPlayerWrapper.trySendMediaUpdate()`：它要求「当前队列项 == 当前元数据」才转发，不满足就撤掉待发的 2 秒重试定时器并 `return`；我们的功能按设计改 Title，于是每次变更都踩中，**变更间隔 < 2 秒就永久饿死**（这也解释了上一版放宽列宽后更容易卡：一行 2 段 ⇒ 间隔正好压到 2 秒以下）。**绕过不需要改蓝牙**：闸门在 `activeQueueItemId == -1` 时短路，而这个值由 media3 决定 ⇒ 在 `LyricTitlePlayer` 上按需摘掉 `COMMAND_GET_TIMELINE` 即可（media3 官方支持的做法，且不掉任何按钮能力）。**代价**：窗口内 media3 的 `MediaController` 只见「当前曲目」（timeline 被 `getCurrentTimelineWithCommandCheck()` 降级），本仓库有 6 处队列读取会静默退化；App 内**歌词显示不受影响**。完整根因、源码坐标、实施方案与验证计划见 **`docs/car-lyrics-avrcp-gate.md`**。**已落地**（`77e0e196`）：模拟器验证结果见 `docs/avrcp-emulator-verification.md` §4（2 s 延迟路径 0 次、APP→BT 0–22 ms）；**真车未复测**。
16. **中文文案补全**（2026-09-16，独立于车机歌词）：`values-zh-rCN/` 此前缺 `strings_import.xml`（57 条）与 `strings_logs.xml`（12 条）**两个完整文件**，另有 `strings.xml` 35 / `strings_settings.xml` 19 / `strings_components.xml` 10 / `strings_presentation_batch_g.xml` 2 / `strings_screens.xml` 1 条缺失，共 136 条已补齐。**有意保留英文的**：`setcat_language_*` 与 `language_zh_rCN`（`translatable="false"`，语言名要显示各自语言）、`app_name` / `accounts_listenbrainz_title` / `screen_subsonic_dashboard_title` / `*_logo`（品牌）、`lrclib_uri` / `about_link_source_subtitle` / `ai_base_url_placeholder`（URI）、`backup_file_name_format` / `playlist_export_folder_display`（**真实路径与文件名模板，翻译会改变实际落盘位置**）、`ai_api_key_label` / `ai_base_url_label` / `stats_slice_top_1|2_3`（技术术语，且 `API Key` 已被 `ai_error_unauthorized` 引用）。
