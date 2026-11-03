# PixelPlayerOSS 节奏游戏集成实现计划

> 日期：2026-11-01  
> 状态：第一阶段代码已落地（`testDebugUnitTest` 与 `assembleDebug` 通过，真机未验收）  
> 参考实现：`D:\3rd-party-projects\RhythmChartLab\android-app`  
> 目标：在歌曲上下文中导入 osu!mania 4K 谱面并游玩；后续增加本地 DSP 快速生成。

## 1. 范围与原则

### 1.1 第一阶段交付

1. 歌曲更多菜单增加“节奏游戏”入口。
2. 以 `songId` 建立歌曲与谱面的关联，不复制歌曲到另一套曲库。
3. 通过 SAF 导入单个 `.osu` 文件，支持 osu!mania 4K tap。
4. 展示谱面难度、音符数、来源和最高分，并选择难度开始游戏。
5. 四轨、多指、Perfect / Great / Good / Miss、分数、准确率、最大连击和结算。
6. 独立游戏音频时钟、暂停/继续/重开、系统返回暂停、页面销毁释放资源。
7. 正式开始游戏时暂停 PixelPlayer 普通播放；退出后恢复进入前的播放状态。
8. 谱面与成绩保存在应用私有目录，不修改 Room schema，不引入网络依赖。

### 1.2 后续阶段

- DSP / STFT 快速谱面生成，提供局部 NPS 上限、相对响度密度控制和分段 timing 选音先验。
- `.prc`、`.osz` 导入与谱面导出。
- Hold、非 4K 键数、SV 显示和更完整的 osu 兼容。
- 全局谱面库、谱面版本管理、逐输出设备偏移校准。

### 1.3 明确不做

- 不使用 PixelPlayer 的 250ms/1000ms 进度 Flow 做判定。
- 不让普通 Media3 播放与游戏音频同时输出。
- 不把云端未下载歌曲当作本地可读音频。
- 不静默丢弃 Hold 或把非 4K 谱面强行映射成四轨。
- 不复制 RhythmChartLab 的独立曲库、MainActivity 或 MNN 生成链路。

## 2. 入口与交互

### 2.1 导航层级

主入口位于统一 `SongInfoBottomSheet`，不增加第五个底部 Tab。路由只传 ID：

```text
SongInfoBottomSheet
  └─ RhythmSetup/{songId}
       ├─ 导入 .osu
       ├─ 选择已有谱面
       └─ RhythmGame/{songId}/{chartId}
            ├─ 暂停 / 继续 / 重开
            └─ 结算：重试 / 返回
```

`RhythmSetup` 是普通下钻页，保留来源页面；`RhythmGame` 隐藏主导航和迷你播放器。首版仅针对主流直板手机竖屏。

### 2.2 谱面选择页

顶部显示歌曲封面、标题、艺术家和时长。主体包含：

- “导入 osu 谱面”按钮；
- 已导入谱面列表：难度名、音符数、平均分、最高分；
- 空状态说明支持 osu!mania 4K 单点谱面；
- 删除不需要的导入谱面；
- 导入失败时展示具体原因，不只在日志中记录。

导入后默认绑定当前歌曲，不根据 `AudioFilename` 自动切换歌曲。后续从全局谱面库导入时，再增加候选歌曲匹配与确认。

### 2.3 游戏与结算

- 加载期间显示明确进度状态，不出现可点击但尚未就绪的游戏区域。
- 游戏页保持屏幕常亮，隐藏系统导航并不改变主导航返回栈。
- 系统返回、失焦和生命周期 `ON_PAUSE` 自动暂停。
- 暂停层提供继续、重新开始和退出。
- 结算显示分数、准确率、最大连击、各判定数量、平均误差和早/晚数量。
- 每个谱面只保留最高分；重试复用已解码音频和当前谱面。

## 3. 模块划分

建议新增包：

```text
com.lostf1sh.pixelplayeross
  data/rhythm/
    RhythmChart.kt             # 统一谱面、谱面元数据、成绩模型
    OsuManiaParser.kt          # .osu -> RhythmChart，纯 Kotlin
    RhythmLibraryStore.kt      # 纯文件库：幂等导入、绑定、最高分（无 Android 依赖）
    RhythmRepository.kt        # SAF 读取 + 协程外壳，委托 RhythmLibraryStore
    RhythmPcmDecoder.kt        # 本地音频 -> PCM（含多声道降混立体声）
    RhythmFrameEstimator.kt    # 时间基准与跳变保护，纯 Kotlin
    RhythmSongClock.kt         # 时钟接口 + JVM 测试用手动时钟
    RhythmAudioClock.kt        # AudioTrack + 输出帧时钟
  presentation/rhythm/
    RhythmPlaySession.kt       # 单局状态与判定，时钟注入
    RhythmJudgement.kt         # 判定引擎，纯 Kotlin
    RhythmSetupScreen.kt       # 导入和谱面选择
    RhythmGameScreen.kt        # Canvas、多指、暂停与结算
```

纯逻辑与 Android 分离：`OsuManiaParser`、`RhythmLibraryStore`、`RhythmJudgement`、`RhythmPlaySession`、`RhythmFrameEstimator`、`RhythmPcmAudio` 均可 JVM 单测。Compose 只负责渲染、输入和生命周期，不承担谱面解析或判定规则。

## 4. 谱面数据契约

### 4.1 统一模型

```kotlin
RhythmNote(id: Int, timeMs: Int, lane: Int)
RhythmTimingPoint(timeMs: Int, beatMs: Double)
RhythmChart(
    schemaVersion: Int,
    source: String,             // osu / dsp / prc
    difficultyName: String,
    timing: List<RhythmTimingPoint>,
    notes: List<RhythmNote>,
    durationMs: Int,
)
```

约束：

- 固定四轨，`lane` 为 0～3；首版只有 tap。
- `notes` 按 `timeMs`、`lane` 稳定排序，`id` 连续且唯一。
- 同轨同刻音符去重；不同轨同刻保留，允许 chord。
- 音符时间必须是歌曲绝对毫秒，不能先减 osu offset。
- **`note.timeMs` 是不可变真值**：派生只能选择/丢弃音符与重排车道，不得把它吸附到网格或改写
  （`rhythm-chart-ladder-plan.md` §17）。
- 至少一个音符，且所有音符时间非负并落在可接受的歌曲范围内。

### 4.2 持久化

```text
filesDir/rhythm/
  library.json                 # songId -> chart metadata / score
  charts/<chartId>.json        # 不可变统一谱面
```

- `chartId` 使用内容 SHA-256 前缀，重复导入同一内容幂等。
- 谱面文件先写临时文件，再原子替换；索引写入同样避免半文件。
- 同一谱面可关联一个 `songId`；第一阶段不建立跨歌曲共享。
- 删除谱面只删除应用私有副本和成绩，不删除用户原始 `.osu` 或音频。
- 云端歌曲只有在能解析到本地下载 URI 时才允许开始。

## 5. osu!mania 解析规则

### 5.1 必须解析

- `[General]`：`Mode`、`AudioFilename`（仅元数据）。
- `[Metadata]`：`Title`、`Artist`、`Version`。
- `[TimingPoints]`： uninherited timing point 的毫秒与 beatLength。
- `[Difficulty]`：`CircleSize`，必须等于 4。
- `[HitObjects]`：`x,y,time,type,...`。

### 5.2 校验与转换

1. `Mode: 3` 且 `CircleSize: 4`，否则返回可读错误。
2. lane = `floor(x * 4 / 512)`，并钳制到 0～3。
3. 只接受 tap；Hold（type bit 128）首版明确拒绝并提示“暂不支持长按谱面”。
4. 忽略普通 osu!std 对象语义、hitsound、combo、storyboard 和 SV；不得把它们误当音符。
5. 没有有效 timing point 时仍可使用绝对 note 时间游玩，但 timing 列表为空；DSP 生成不能省略 timing。
6. 解析失败不得留下已入库但不可玩的谱面。

## 6. 音频与生命周期

### 6.1 独立时钟

复用 RhythmChartLab 已验证的架构语义：

```text
MediaExtractor / MediaCodec
  -> PCM16
  -> AudioTrack MODE_STREAM（play() 前先灌满缓冲，避免起步欠载）
  -> AudioTimestamp.framePosition + nanoTime（首选，含延迟补偿）
     └ 无 timestamp 时退回 playbackHeadPosition（每台设备都有，欠载即停走）
  -> 单调时钟外推 + 每帧朝设备位置指数漂移恢复（半衰期 80ms，见 §6.1.1）
  -> SongClock.nowMs()
```

`AudioTimestamp.nanoTime` 使用 `System.nanoTime()` 基准；`SystemClock.elapsedRealtimeNanos()` 是另一基准，二者不能直接混用。输出帧时钟同时驱动渲染、漏接结算和输入判定。

**时钟修正 = osu!lazer 的 `InterpolatingFramedClock` 语义（指数漂移恢复，见 §6.1.1）**：两次设备采样之间按 `sampleRate` 墙钟外推；每来一个新采样，朝设备位置**按指数比例平滑恢复**（不是硬 snap，也不是带硬上限的限幅修正）。timestamp 与 head 相差一个输出延迟，故 timestamp 新鲜（200ms 内）时忽略 head，避免两个源来回跳；timestamp 路径本身是精确的 `(framePosition, nanoTime)` 对，直接锚定外推即平滑。

**每帧只采样一次时间**：游戏循环采到 `nowMs()` 后传给 `RhythmPlayfield` 渲染，Canvas 不再自行读时钟——否则同一帧内渲染用的时间和 tick 判定用的时间会不一致，表现为开场所有音符整体抖一下。对应 osu! 的 `FrameStabilityContainer`（一帧只 `ProcessFrame` 一次，所有消费者读同一个冻结值）。

MVP 整曲解码约占用 40MB/5 分钟立体声 44.1kHz。流式解码和内存上限属于后续性能里程碑，不能在文档中宣称已经解决。

### 6.1.1 参照 osu!lazer 的计时机制（本地 `/d/3rd-party-projects/osu`，ppy/osu）

音符下落与歌曲失步的修复以 osu!lazer 为准，链路证据：

| osu! 机制 | 位置 | 我们的对应 |
|---|---|---|
| 音频位置为唯一真值，每帧读一次 | `osu.Game/Beatmaps/FramedBeatmapClock.cs:74-81`（`InterpolatingFramedClock { DriftRecoveryHalfLife = 80 }`） | `RhythmAudioClock.readFrame()` 每帧读 timestamp / head |
| 平滑指数漂移恢复（非 snap、非限幅） | osu.Framework `InterpolatingFramedClock`（NuGet，不在仓库） | `RhythmFrameEstimator.offerHeadPosition()` 的恢复公式 |
| 一帧时间冻结、所有消费者读同一值 | `osu.Game/Rulesets/UI/FrameStabilityContainer.cs`、`FramedBeatmapClock.ProcessFrame()` 对外 noop | 游戏循环单一 `songMs` 传给 `RhythmPlayfield`（Phase A） |
| 音符 Y = `(hitTime − now)/timeRange × scrollLength` | `osu.Game/Rulesets/UI/Scrolling/Algorithms/ConstantScrollAlgorithm.cs:21-22` | `RhythmPlayfieldGeometry.noteCenterY()`（一致） |

**恢复公式**（osu.Framework `InterpolatingFramedClock.ProcessFrame` + `Interpolation.DampContinuously`，已联网核对源码）：每帧先按墙钟 delta 推进本地估计，再
```
drift = sourceTime − interpolatedTime
if |drift| ≤ AllowableErrorMilliseconds(=1000/60*2≈33.33ms) × Rate:
    interpolatedTime += drift × (1 − 0.5^(frameDeltaMs / DriftRecoveryHalfLife))   // 平滑指数恢复
else:
    interpolatedTime = sourceTime                                                  // 超阈值直接 snap
```
- `DriftRecoveryHalfLife`：osu.Framework 默认 50ms，**osu!game 覆盖为 80ms**（`FramedBeatmapClock.cs:80`）。半衰期 80ms → 误差每 80ms 消一半：小误差温柔收敛（无台阶抖动），大误差快速收敛（无累积失步）。
- `AllowableErrorMilliseconds` 默认 **33.33ms**（两帧 60fps），osu!game 未覆盖。这是 snap 阈值——很紧，超过就放弃插值直接用源值。
- 首帧状态机：`IsInterpolating=false` 时直接跟随源（snap），源产生新值后**下一帧**才开始插值（osu! 注释：时钟启动后首帧可能因上一帧 seek 而极不准）。

**为 Android 偏离 osu! 的一处（必须）**：osu! 有单调护栏 `currentTime = max(lastTime, currentTime)`（仅显式倒放 seek 才允许回退），前提是桌面 BASS 不欠载、源永不落后。Android 的 AudioTrack 会 underrun——`playbackHeadPosition` 会落后于墙钟外推，此时**必须允许向后修正**才能消除失步，否则护栏把时钟永久卡在超前位置。故本移植采用 damp + snap(33.33ms / 80ms) 但**去掉硬单调护栏**；向后的小误差同样走平滑 damp，只有超过 33.33ms 才 snap。

**源选择（保持不变）**：AudioTimestamp 是精确的 `(framePosition, nanoTime)` 对、含延迟补偿，直接锚定外推即连续平滑，无需 damp；仅当 timestamp 陈旧（>200ms）时才退回 head，并对 head 应用上述 osu! 恢复。避免两个相差输出延迟的源来回跳。

> 已核对：`osu.Framework/Timing/InterpolatingFramedClock.cs`、`osu.Framework/Utils/Interpolation.cs`（`Damp`/`DampContinuously`）、`osu.Game/Beatmaps/FramedBeatmapClock.cs:74-81`。

### 6.2 与 PixelPlayer 互斥

- 设置页和导入阶段不改变普通播放。
- 进入游戏路由时记录 `isPlaying`，并暂停共享 `PlayerViewModel`。
- 游戏音频使用 `USAGE_GAME` 的独立 `AudioTrack`。
- 所有退出路径（HUD、系统返回、结算、dispose）统一停止并释放时钟。
- 退出后仅当进入游戏前正在播放时恢复普通播放；不擅自改变原歌曲位置。

## 7. 判定与分数

- Perfect：绝对误差 ≤60ms。
- Great：≤90ms。
- Good：≤140ms。
- 超出 Good 窗口仍未命中：Miss。
- 每次触摸最多消费一个音符，每个音符最多被消费一次。
- 输入时间取触摸事件发生时刻对应的歌曲时间，而不是 Compose 回调执行时刻。
- 计分遵循 osu!mania ScoreV1（1,000,000 为满分）：
  `分数 = 1,000,000 × (0.5 × 命中值/320 + 0.5 × 奖励合计) / 音符数`。命中值 Perfect=320、Great=300、
  Good=200、Miss=0；奖励条初始 100，按 osu 规则先结算本次变化再计分
  （Perfect +2、Great +1、Good −8、Miss 清空），单个音符贡献 `奖励值 × sqrt(奖励条)/320`
  （Perfect 32、Great 32、Good 16）。
- 准确率只看命中值：`命中值之和 / (320 × 已判定音符数)`，与奖励条无关。
- 空按不计 Miss、不打断连击，仅记录调试事件。
- 开局引子 1 秒（`RhythmChart.LEAD_IN_MS`）：母谱保存全部音符；派生谱在**存储时**就去掉 1 秒前的音符，
  界面上显示的预估音符数同样按去除后计算；母谱与 osu 导入仍在开局时过滤（`withoutLeadIn`）。

## 8. DSP 快速生成后续方案

### 8.1 参数交互

首屏仅保留难度、局部 NPS 上限和弱音过滤；BPM 与量化放高级设置。建议初始档位：轻松 2、标准 4、激烈 7 NPS，最终值需真机与多曲测试校准。

- NPS 上限是任意局部一秒窗口的峰值约束，不是全曲平均数。
- 弱音过滤使用本曲相对 RMS 百分位控制段落密度，不使用固定 dB，也不逐音符简单硬切。
- 默认半拍量化；BPM 支持 ÷2、×2 和手动修正，低置信度必须提示。

### 8.2 已完成的 PC 实验依据

同曲对照显示：半拍量化使 normal F1 从裸 onset 的 49.0 提升到 93.0；局部 RMS 是有判别力的段级密度特征，而 ODF 强度单独筛选无效。该结论来自单曲 PC 实验，不等于 Android 或多曲质量已验收。

### 8.3 Android 落地步骤

1. 解码/重采样到单声道 22050Hz。
2. STFT、对数频带、spectral flux、前向 whitening 和峰值选择。
3. BPM/offset 估计与半倍速纠正；失败时允许人工确认。
4. 分段 timing 只做选音先验与分段预算，不移动时间戳（v3，见 §8.4）。
5. 局部 RMS 控制段落密度，再同时施加滑动窗口 NPS 上限与相邻最小间隔（`minGapMs`）；
   选中即保留母谱原始时间。
6. 四轨分配、同轨冲突消解和可玩性校验。
7. 输出同一 `RhythmChart`，记录生成器版本和参数哈希，复用导入谱面的游玩链路。

### 8.4 v3 决策：下落与判定只依赖绝对时间（2026-11-03）

游玩侧本来就不读 BPM：`RhythmPlayfield` 只用 `note.timeMs` 与 `approachMs`，滚动速度恒定，
判定窗口也是毫秒。谱面与音乐的不匹配因此只可能来自生成侧的两个自由度——**选哪些音符**、
**是否移动它们的时间戳**；真机日志已排除时钟（`rhythm-clock-drift-diagnostics.md` §6）。

v3 起：DSP 派生只在母谱上选择（每秒最多音符数 + 相邻最小间隔 + 可选全曲上限 +
分段 timing 先验），时间戳原样保留；BPM 不再生成音符时间。算法见
`rhythm-chart-ladder-plan.md` §17。
osu 导入与 v2 旧谱仍按原时间戳游玩，不受影响。

## 9. 测试与验收

### 9.1 JVM 测试

已实现（`app/src/test/java/.../rhythm/`）：

| 测试类 | 覆盖点 |
|---|---|
| `OsuManiaParserTest` | 4K tap、chord、车道边界（x=256→lane2、x=511→lane3）、同轨同刻去重、继承 timing 过滤、CRLF 与未知段；拒绝非 mania、非 4K、Hold、空谱面 |
| `RhythmLibraryStoreTest` | 重复导入幂等且保留最高分、同一谱面绑两首歌共享内容文件、最高分只升不降、删除清理与再删返回 false、损坏索引可恢复、解析失败不落盘 |
| `RhythmJudgementEngineTest` | 判定窗口边界、osu ScoreV1 折算（命中值 + 奖励条、Miss 清空奖励条）、空按不计 Miss 不断连击、一次输入只消费一个音符、`finish()` 补 Miss |
| `RhythmChartLeadInTest` | 去掉 1 秒前音符、整谱都在引子内时保留原谱 |
| `RhythmPlaySessionTest` | 注入手动时钟结算、`restart()` 重置引擎、按歌曲时长收尾 |
| `RhythmFrameEstimatorTest` | 锚点插值、MONOTONIC→BOOTTIME 换算、未知时间基准丢弃、500ms 跳变保护、playbackHead 全量对齐（含起步释放、timestamp 新鲜时忽略、陈旧后接管）、暂停冻结与恢复不回退、帧数钳制 |
| `RhythmPcmAudioTest` | 多声道降混立体声保持帧数与时长、单/立体声不变 |

实测结果（2026-11-01）：`:app:testDebugUnitTest` 全量 968 个测试通过；`:app:assembleDebug` 成功。

### 9.2 构建与真机

每次集成至少运行 `:app:testDebugUnitTest` 和 `:app:assembleRelease`。真机验收必须单独标注，覆盖：

- 导入真实 4K `.osu` 后绑定当前歌曲并游玩。
- 四轨多指不吞键，音频时钟无累计漂移。
- 普通播放与游戏不双路输出，所有退出路径无残留音频。
- 暂停、失焦、系统返回、重试和结算成绩保存。
- 扬声器、有线、蓝牙分别校准偏移；不能用模拟器结果替代。

#### 真机实测记录（2026-11-01，SM-G9810 / Android 13 / arm64-v8a）

测试素材：自建 20s、120 BPM 节拍音轨（1000Hz 短脉冲，MP3 128k）+ 与之逐拍对齐的 4K 谱面（37 tap，1.9 NPS）。

| 验收项 | 结果 | 依据 |
|---|---|---|
| 歌曲入口显示「节奏游戏」 | 通过 | `SongInfoBottomSheet` 中出现按钮，点击进入 `RhythmSetup` |
| SAF 导入 `.osu` 并绑定当前歌曲 | 通过 | 选谱页出现 `Test4K · 37 个音符 · 每秒 1.9 个` |
| 四轨游玩与判定 | 通过 | 定时点击 8 次得到 `Perfect 3 / Great 2 / Good 1 / Miss 2`，分数 129730，最大连击 6 |
| 音频时钟与歌曲长度一致 | 通过 | 重开后 24s 结算 = 3s 倒计时 + 20s 歌曲，无提前或挂起 |
| 结算与最高分落盘 | 通过 | 退出到选谱页显示 `最高分：129730`（`ON_RESUME` 刷新生效） |
| 与普通播放互斥 | 通过 | 游戏中 `USAGE_MEDIA` 为 paused、`USAGE_GAME` 为 started，无双路输出 |
| 暂停/继续 | 通过 | 暂停后 `USAGE_GAME` paused；继续后回到 started |
| 系统返回键 | 通过 | 不退出页面，进入暂停层 |
| HOME 切后台 | 通过 | `USAGE_GAME` 自动 paused，返回应用仍停在暂停层 |
| 退出释放资源并恢复播放 | 通过 | 退出后 `USAGE_GAME` 消失，`USAGE_MEDIA` 从 13.1s 处继续 |

未覆盖：多指同时按下、蓝牙/有线输出、`assembleRelease`、长时间播放漂移。

#### 实测发现的已知偏差

定时点击的平均误差为 **−56.6 ms（5 次偏早 / 1 次偏晚）**，属于系统性提前。可能来源有三：MP3 编码器延迟使音频落后名义时间线、AudioTrack 输出缓冲带来的额外延迟、以及 `input tap` 约 70ms 的注入抖动。当前 `offsetMs` 恒为 0 且没有校准入口，因此**这个偏差尚不能归因到单一环节**；需要先做逐输出设备的手动偏移校准，再用真机手动游玩复测。

#### 验证工具注意

游戏画面每帧重绘，`uiautomator dump` 在此状态下会失败并保留上一次的 `/sdcard/ui.xml`，读到的是**过期界面**。真机验证游戏内状态必须以 `screencap` 为准，dump 只用于静态页面（选谱页、暂停层、结算层）。

## 10. 实施顺序

1. 写统一模型、osu 解析器与判定核心测试。
2. 写谱面仓库、PCM 解码和独立音频时钟。
3. 接谱面选择页与歌曲菜单入口。
4. 接四轨游戏、暂停、结算和普通播放器互斥。
5. 跑 JVM 测试与 release 构建，修复后做真机验收。
6. 第一阶段稳定后，再单独评审 DSP 生成器的实现 diff。

## 11. 来源与许可说明

PixelPlayerOSS 为 GPL-3.0-or-later。此次仅参考同一作者本地 RhythmChartLab 实验实现的架构与算法语义，在 PixelPlayerOSS 内重新组织包、存储、导航和生命周期；不复制其独立 App 曲库或 UI 整文件。若后续引入第三方 osu 解析或音频实现，必须单独核对许可证并保留 notices。

## 12. 实施状态（2026-11-01）

### 12.1 已落地

- 新增 `data/rhythm/` 七个文件与 `presentation/rhythm/` 四个文件（清单见 §3）。
- `Screen.RhythmSetup` / `Screen.RhythmGame` 路由与 `AppNavigation` 注册；游戏路由不套 `ScreenWrapper`，并加入隐藏迷你播放器的路由集合。
- `SongInfoBottomSheet` 增加可选“节奏游戏”按钮（默认不显示），已在曲库页与搜索页接上 `navigateSafely`；其余页面复用同一组件时传入即可启用。
- 中英文案已补齐（`song_info_rhythm_game`、`rhythm_*`）。
- 修掉三处实现期缺陷：暂停状态下的倒计时不再出声、返回选谱页会刷新最高分、多声道音频降混后再交给 AudioTrack。

### 12.2 已知限制

- Hold 直接整谱拒收（明确报错），不做单点转换；`.prc`、`.osz` 未接入。
- ~~未实现 DSP 快速生成~~：已实现，见 `docs/rhythm-chart-ladder-plan.md`（母谱 + 派生难度）。
- 整曲 PCM 一次性解码进内存（5 分钟立体声约 40MB），长曲或低内存机型需改为流式解码。
- 未申请音频焦点；`offsetMs` 已改为**逐谱面**侧车设置（`settings/<chartId>.json`）并可在暂停面板里 ±10 ms 现场校准，但仍是手动值，没有自动校准。
- 谱面仓库在 Compose 内直接构造（`remember`），未走 Hilt 注入；功能等价，但与项目 `hiltViewModel()` 惯例不一致。
- 仅 `LibraryScreen`、`SearchScreen` 暴露入口，其他列表页未接。
- 真机核心链路已验收（导入/游玩/判定/结算/互斥/暂停/返回/后台/退出，见 §9.2）；多指、蓝牙与有线输出、长时漂移未覆盖，`assembleRelease` 未跑。
- 实测存在约 −57ms 的系统性提前误差，且没有偏移校准入口。

## 13. 游戏界面重做（2026-11-01：自 RhythmChartLab 迁移）

第一阶段只做了「能玩」的极简画布（纯色轨道 + 矩形音符 + 三个文字按钮）。本节记录把
`D:\3rd-party-projects\RhythmChartLab\android-app` 的游玩界面整体迁入后的形态。
来源均为**同一作者**的本地原型，GPL-3.0-or-later 自持，不涉及第三方。

### 13.1 文件对照

| 源（RhythmChartLab） | 目标（本项目） | 变化 |
|---|---|---|
| `game/PlayfieldGeometry.kt` | `presentation/rhythm/RhythmPlayfieldGeometry.kt` | 判定线 80%；线以下整条轨道可点；音符落点改为 `hitLineY / approachMs`（原实现是 `(judgeY-40)/approachMs`，approachMs 到时到不了顶） |
| `game/LaneTouchTracker.kt` | `RhythmLaneTouchTracker.kt` | 原样迁移（pointerId → 车道锁定） |
| `game/GameFeedback.kt` | `RhythmGameFeedback.kt` | 判定文字/连击/命中环；文案改为 `R.string` |
| `game/GamePlayfield.kt` | `RhythmPlayfield.kt` | 删掉 Hold 分支（本项目谱面无尾点） |
| `game/GameScreen.kt`（HUD / Pause / Result） | `RhythmGameScreen.kt` | 保留本项目生命周期，替换 HUD 与两个覆盖层 |

### 13.2 视觉与交互

- **四色键区**：判定线以下整条轨道是青/品红/绿/橙渐变（`LANE_KEYS`），按下时该轨在
  70 ms 内提亮、150 ms 回落；
- **音符**：圆角 + 顶部 28% 高光；进入 ±Good 窗口（140 ms）变亮青色 `NOTE_ACTIVE`；
- **命中反馈**：判定线上扩散环（210 ms），屏幕中央 PERFECT/GREAT/GOOD/MISS 大字
  （440 ms，先放大后回落）+ `±ms · 偏早/偏晚`，连击常驻在判定区上方；
- **HUD**：千分位分数 + 难度名 + 准确率 + 暂停按钮 + 2 dp 进度条；
- **暂停面板**：继续 / 设备偏移 ±10 ms / 重新开始 / 退出。偏移**实时**改
  `clock.offsetMs` 并写回 `settings/<chartId>.json`（不重开本局）；
- **结算面板**：分数大字、准确率、最大连击与音符数、四判定分布、平均误差与早晚分布。

### 13.3 有意不迁

- **Hold（长条）判定**：`RhythmNote` 只有单点时间，DSP 生成与 osu 导入都只有 tap；
  迁入尾判等于为不存在的谱面付双倍渲染与判定复杂度。
- **autoplay / 难度热切换**：本项目一个谱面文件即一个难度，切难度 = 换谱面。
- **源 App 的主题与曲库页**：本项目有自己的 MaterialTheme、导航与选谱页。

### 13.4 与和弦谱面的关系

上一轮加入的双键/三键让「同一毫秒多个音符」成为常态。旧画布只处理 down、按下标 x 直接
算车道，两指落同一轨会重复判定；迁移后由 `RhythmLaneTouchTracker` 保证：
一指一判定、同轨多指不重复、抬手只释放自己那根手指。

### 13.5 测试与未验证项

- 纯 JVM 单测：`RhythmPlayfieldGeometryTest`（5 例，线上下命中/车道边界/退化尺寸/落点）、
  `RhythmLaneTouchTrackerTest`（5 例，重复 down/四指并按/同轨两指释放/clear/越界车道）。
- 全量：**1040 tests / 0 failures**；`:app:lintDebug` 仍是既有 4 个基线 error（与本特性无关）；
  `:app:assembleRelease` 已签名产出（38,072,252 字节）。
- **界面未目视验收**：本机 AVD 为 x86_64 + ARM 翻译，进游戏能跑但帧率不可用，真机截图待补。
