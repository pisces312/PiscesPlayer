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

- DSP / STFT 快速谱面生成，提供局部 NPS 上限、相对响度密度控制和量化设置。
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
  -> AudioTrack MODE_STREAM
  -> AudioTimestamp.framePosition + nanoTime
  -> 单调时钟插值
  -> SongClock.nowMs()
```

`AudioTimestamp.nanoTime` 使用 `System.nanoTime()` 基准；`SystemClock.elapsedRealtimeNanos()` 是另一基准，二者不能直接混用。输出帧时钟同时驱动渲染、漏接结算和输入判定。

MVP 整曲解码约占用 40MB/5 分钟立体声 44.1kHz。流式解码和内存上限属于后续性能里程碑，不能在文档中宣称已经解决。

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
- 满分 1,000,000，权重 Perfect=1.0、Great=0.7、Good=0.4、Miss=0。
- 空按不计 Miss、不打断连击，仅记录调试事件。

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
4. 分段 timing 网格量化，不能用首个 timing point 套全曲。
5. 局部 RMS 控制段落密度，再施加滑动窗口 NPS 上限。
6. 四轨分配、同轨冲突消解和可玩性校验。
7. 输出同一 `RhythmChart`，记录生成器版本和参数哈希，复用导入谱面的游玩链路。

## 9. 测试与验收

### 9.1 JVM 测试

已实现（`app/src/test/java/.../rhythm/`）：

| 测试类 | 覆盖点 |
|---|---|
| `OsuManiaParserTest` | 4K tap、chord、车道边界（x=256→lane2、x=511→lane3）、同轨同刻去重、继承 timing 过滤、CRLF 与未知段；拒绝非 mania、非 4K、Hold、空谱面 |
| `RhythmLibraryStoreTest` | 重复导入幂等且保留最高分、同一谱面绑两首歌共享内容文件、最高分只升不降、删除清理与再删返回 false、损坏索引可恢复、解析失败不落盘 |
| `RhythmJudgementEngineTest` | 判定窗口边界、权重折算分数、空按不计 Miss 不断连击、一次输入只消费一个音符、`finish()` 补 Miss |
| `RhythmPlaySessionTest` | 注入手动时钟结算、`restart()` 重置引擎、按歌曲时长收尾 |
| `RhythmFrameEstimatorTest` | 锚点插值、MONOTONIC→BOOTTIME 换算、未知时间基准丢弃、500ms 跳变保护、暂停冻结与恢复不回退、帧数钳制 |
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
- 未实现 DSP 快速生成，选谱页只有“导入 .osu”一条来源。
- 整曲 PCM 一次性解码进内存（5 分钟立体声约 40MB），长曲或低内存机型需改为流式解码。
- 未申请音频焦点、未接入逐输出设备偏移校准；`offsetMs` 目前恒为 0。
- 谱面仓库在 Compose 内直接构造（`remember`），未走 Hilt 注入；功能等价，但与项目 `hiltViewModel()` 惯例不一致。
- 仅 `LibraryScreen`、`SearchScreen` 暴露入口，其他列表页未接。
- 真机核心链路已验收（导入/游玩/判定/结算/互斥/暂停/返回/后台/退出，见 §9.2）；多指、蓝牙与有线输出、长时漂移未覆盖，`assembleRelease` 未跑。
- 实测存在约 −57ms 的系统性提前误差，且没有偏移校准入口。
