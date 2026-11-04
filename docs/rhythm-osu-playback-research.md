# 节奏游戏：osu! 播放谱面机制调研 —— 以及为什么自动出谱走不通它的路

> 日期：2026-11-04
> 状态：**调研完成**（对 `D:\3rd-party-projects\osu`（ppy/osu）做代码走读；未做运行时实测）
> **结论先行**：从**任意歌曲**自动生成谱面时，osu 的方式**行不通**——它不做任何节拍估计，
> 「节奏严格一致化」由人工 mapper 完成，引擎只管无条件信任。能借的是**播放半边**
> （时间模型 / 时钟 / 吸附），我们已经与之同构；不能借的是它的**定拍假设**。
> 依据：osu.Game 源码路径见各节；本仓库对照实现见 `RhythmPlayLogic.kt`（A/B 弹窗）、
> `RhythmBeatTracker.kt`（估计器）、`RhythmChartDeriver.snapTime`（吸附）。
> 备注：`osu.Framework`（时钟原语 `FramedClock` / `InterpolatingFramedClock` /
> `DecouplingFramedClock` 等）是 NuGet 依赖，本 checkout 未含源码，时钟结论来自
> osu.Game 侧的调用方式与注释。

---

## 1. 一句话分工

osu = **「人工一致化 + 引擎零智能」**：

| 环节 | 谁做 | 引擎参与度 |
|---|---|---|
| 节拍/变速分析 | **人类 mapper**（放 timing point） | 零。osu 没有任何节拍检测代码 |
| 节奏严格一致化（量化） | **人类 mapper**（编辑器吸附放置） | 只提供吸附公式 |
| 音符时间 | 编辑时**烤死**成绝对毫秒 | 播放时不再推导、不再修正 |
| 播放对表 | 引擎 | 只做「绝对毫秒 ↔ 音频时钟」比较 |
| 判定 | 引擎 | 对称 ±半窗，超窗自动 miss |

所以「osu 的方式」要拆两半看：**播放模型**（可照抄）与**定拍来源**（必须自研）。

## 2. 时间模型：绝对毫秒 + timing point 列表

- `.osu` 的 `[TimingPoints]`：`time, beatLength, meter, ...`。**`BeatLength` = 每拍毫秒**
  （`BPM = 60000 / BeatLength`），默认 1000ms（`TimingControlPoint.DEFAULT`）。
  `beatLength < 0` 的绿点不是变速，是 slider velocity（`speedMultiplier = 100 / -beatLength`）。
- 查表是**二分查找**：`ControlPointInfo.TimingPointAt(time)` 返回「`Time <= time` 的最后一个
  timing point」（`osu.Game/Beatmaps/ControlPoints/ControlPointInfo.cs`）。
- **hit object 存的是绝对毫秒**（`ConvertHitObjectParser.cs`：`startTime = ParseDouble(split[2]) + offset`），
  不存拍位。拍位只用来推导时长（滑条）与编辑器吸附。
- **中途变速 = 加一个 timing point，已有音符一个都不动**。滑条时长取「滑条**起点**时刻」的
  BeatLength 算一次（`Slider.cs`：`EndTime = StartTime + SpanCount * Distance / Velocity`），
  **不做跨段插值**——横跨变速点的滑条就按老速度走完。
- 解码期有客户端修正：v4 及更老文件 `+24ms`（`EARLY_VERSION_TIMING_OFFSET`），
  边界归属用 `CONTROL_POINT_LENIENCY = 5ms` 松弛。

## 3. 游戏时钟栈（播放稳定性的全部秘密）

自底向上（`osu.Game/Beatmaps/FramedBeatmapClock.cs`）：

```
BASS 音频轨（报时很粗，5–10ms 一次）
 └─ DecouplingFramedClock        （轨道 seek 不动/卡住时游戏继续走）
 └─ InterpolatingFramedClock     （报时之间插值外推；DriftRecoveryHalfLife = 80ms 慢恢复）
 └─ OffsetCorrectionClock        （平台延迟：Windows 基准 15ms，实验 WASAPI 再 −25ms）
 └─ OffsetCorrectionClock        （用户全局音频偏移）
 └─ FramedOffsetClock            （每谱面用户偏移）
     = CurrentTime（gameplay 看到的唯一时间）
```

- **所有 offset 都挂在时钟上，绝不改音符时间**（`Seek(position)` 内部减去
  `TotalAppliedOffset`），判定路径里没有任何 offset 逻辑。
- `FrameStabilityContainer`（`osu.Game/Rulesets/UI/`）：每帧推进**上限约一帧**
  （1000/60 ms），大 seek 后确定性追帧（单次 Update 最多 10ms CPU）；BASS 报出
  **偏离预期 >500ms 的时间戳直接丢弃**（野报时防护）。
- 暂停/重开是纯时钟操作（`GameplayClockContainer.Start/Stop/Reset/Seek`），
  开局调度「after children」，保证子节点先看到一帧原起点。

## 4. 判定窗

- 对称 **±半窗**（`HitWindows.WindowFor(result)` 返回半宽），`|offset|` 落进哪档算哪档，
  超过最低成功档自动 miss（`CanBeHit`）。
- osu!standard 随 OD 两段线性（`IBeatmapDifficultyInfo.DifficultyRange`：
  `od<5 → mid + (mid-min)(od-5)/5`）：
  - 300：`floor(DifficultyRange(OD, 80, 50, 20)) − 0.5` ms
  - 100：`floor(DifficultyRange(OD, 140, 100, 60)) − 0.5` ms
  - 50：`floor(DifficultyRange(OD, 200, 150, 100)) − 0.5` ms；miss 固定 400ms
- osu!mania（更接近我们的 4K 点按）：`floor(DifficultyRange(OD, range) * mod) + 0.5`，
  档位半宽 Perfect (22.4, 19.4, 13.9) / Great (64, 49, 34) / Good (97, 82, 67) /
  Ok (127, 112, 97) / Meh (151, 136, 121) / Miss (188, 173, 158)。
  对照我们：PERFECT/GREAT/GOOD ±60/90/140ms——**比 osu!mania 宽松一档以上**。
- 生命周期优化（可移植到 Compose/Canvas 裁剪）：`LifetimeStart = StartTime − 10s`
  （滚动模式收到「approach + 判定窗」），`LifetimeEnd = 命中/miss 动画结束`，
  只在窗口内实例化 drawable（对象池）。

## 5. 编辑器吸附公式（放置的唯一算术）

`ControlPointInfo.GetClosestSnappedTime(time, beatDivisor)`：

```
beatLength   = timingPoint.BeatLength / beatDivisor
beats        = (max(time,0) − timingPoint.Time) / beatLength
snappedTime  = timingPoint.Time + round(beats) * beatLength     // MidpointRounding.AwayFromZero
```

外加特例：**下一个 timing point 的 `Time` 更近就吸到它**（变速边界不撕裂）。
注意锚点是 **timing point**，不是上一个音符——舍入漂移不累积。
常用 divisor：`{1,2,3,4,5,6,7,8,9,12,16}`（最小 1、最大 64）。
小节线同样事前生成：`BeatLength × TimeSignature.Numerator` 每段一列（`BarLineGenerator.cs`）。

## 6. 为什么从任意歌曲自动出谱走不通 osu 的路

「osu 的方式」= 假设第 1 节那张表的左列有人干。摊开它的隐含输入：

1. **timing point 是人放的，且无条件被信任**。引擎没有节拍检测、没有置信度、没有
   漂移修正、没有相位纠错；timing 放歪了，谱面就是歪的，引擎不会救。
2. **「严格一致化」是听辨工作**：把音乐掰成一段段各自一致的区间、在变速处补点，
   live / 散板录音甚至**逐小节放 timing point**——这需要人耳，且是 mapping 里最耗时的部分。
3. **量化是强制惯例**（排名规则基本要求数值对齐），真正无法量化的段落，mapper 用
   「密到离谱的 timing point」或「干脆不吸附的音符」硬扛——两种逃生口都依赖人的判断。
4. 若照搬它的「简单」——全曲单 BPM + 硬量化（即 A/B 弹窗里的 **osu 式**）——在变速曲上
   必然走离节拍。这不是 osu 的缺陷，是它把这类歌曲交给了「多放几个 timing point」的人。

**结论**：把 osu 当**生成方法** = 把「放 timing point + 一致化」这个人工环节丢掉了，
从任意歌曲自动出谱时无人兜底 → 行不通。正确姿势是**估计器扮演 mapper**：

- 估计器输出 = timing point 列表（可以很密，模型不变）+ 一次烤死的音符时间；
- 播放侧照抄 osu（本文 §2–§5 全部有效，我们已同构）；
- 无法量化的段落保留逃生口 = **onset 模式**（不吸附，等价 osu 的「不吸附音符」）。

## 7. 我们与 osu 的逐项对照

| osu | 本仓库 | 状态 |
|---|---|---|
| `TimingControlPoint`（BeatLength） | `RhythmTimingPoint(timeMs, beatMs)` | ✅ 同构 |
| hit object 绝对毫秒、播放不再推导 | `RhythmNote.timeMs`，`RhythmPlaySession` 只对表 | ✅ 同构 |
| 吸附公式（锚 timing point + round） | `RhythmChartDeriver.snapTime`（含下一点特例） | ✅ 同构 |
| offset 全挂时钟 | `RhythmAudioClock(offsetMs = settings.delayMs)` | ✅ 同构 |
| 变速 = 加 timing point | v3 格点的局部间距 = 自动补的 timing point | ✅ 已实现（`RhythmBeatTracker`） |
| mapper 放点 | `RhythmBeatTracker.estimateTiming`（估计器） | ✅ 自研，持续打磨 |
| 插值时钟（报时 5–10ms 抖动） | `RhythmAudioClock` 逐帧采样 | ⚠️ 未做插值/慢恢复，待做 |
| 帧稳定层（推进上限 + 野报时丢弃） | 无 | ⚠️ 待做（判定/回放确定性） |
| 判定窗随 OD 缩放 | 固定 ±60/90/140ms | ⚠️ 可选增强 |
| 不吸附音符（逃生口） | onset 模式（`deriveOnsetNative`） | ✅ 已有 |
| 人工一致化 | A/B 弹窗（`RhythmPlayLogic`）量化「一致化质量」 | ✅ 已有（2026-11-04） |

## 8. 可试听的实证：A/B 弹窗

2026-11-04 起，点任意 DSP 谱面的 Play 会弹出「选择谱面播放逻辑」，同一份谱面、同一套音符，
只改放置（结果不落盘，run 标题标注所用逻辑）：

- **正常（按谱面原样）**：不重排，对照组；
- **自适应格点**：贴母谱追踪格点（= osu 的「mapper 在变速处补 timing point」的自动版）；
- **osu 式**：全曲单 BPM 硬量化（= 照搬 osu 的「简单」）——变速曲上应能**直接听出走离**；
- **旧版拟合**：格点化之前的分段拟合（回归基线，反拍/忽快忽慢的来源）。

预期听感排序（变速曲）：自适应格点 > osu 式（变速段走离）≈ 旧版拟合（中段走离/反拍）；
恒定速度曲上三家应当接近——这正是「一致化质量」的直接度量。
