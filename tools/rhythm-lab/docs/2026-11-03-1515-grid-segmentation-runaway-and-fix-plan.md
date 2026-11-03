# 网格模式"分段后仍然严重不准"：根因与修复方案（2026-11-03 15:15）

针对用户反馈：用最新 APK 重新生成母谱与派生谱后，**网格对齐模式的谱面玩到一半仍严重跑偏**。
真机日志 `E:\rhythm-drift-1793689275504.txt`。结论：**时钟正常，谱面生成侧的分段细化器把节拍
越收越短**——不是运行时问题，也不是全局 BPM 没测准。

## 1. 日志给出的直接证据

日志头部（`songId=999394`，`chartId=3f74ff30d244101f`，`简单 · 网格对齐 · 1103 14:53`）：

| 量 | 值 | 含义 |
|---|---|---|
| `kind` | `SAWTOOTH` | 有界锯齿，无净趋势 |
| `slopeMsPerSec` | −0.0166（−16.6 ppm） | 173 s 总共只漂 −2.9 ms |
| `endResidualMs` | −1.58 | 结尾残差可忽略 |
| `timestampCoverage` | 1.00 | 全程有 AudioTimestamp |
| `crossingSlopeMsPerSec` | 0.0216 | 音符过线残差全程平直 |

**时钟可以排除。** 再看 crossings 里谱面自己的音符间隔（`noteTimeMs` 相邻差分）：

```
0–37 s    352 / 353 ms（约 170.3 BPM）
37–60 s   347 ms
60–90 s   342 → 337 ms
90–120 s  332 → 327 ms
120–173 s 322 → 317 ms（约 189 BPM）
```

即 **谱面速度从 170 BPM 一路涨到 189 BPM**。整局唯一音符 508 个，而 170.34 BPM 下应约 491 个
（一拍一 note）；结尾累计比音乐**超前约 25 拍 ≈ 8.5 s**，90 s 处已超前约 4 s——与"玩到一半严重不准"吻合。

## 2. 离线复现（真实链路 + 同一 mp3）

`E:\迎着风向前冲-姜月初.mp3`（172.874671 s）解码为 44.1 kHz 单声道，跑真实的
`RhythmChartGenerator.analyze → renderMaster → RhythmTimingRefiner.refine`：

```
detectedBpm=170.9919  usedBpm=170.2989  beatMs=352.3218  offset=139.29  onsets=1305
refined points=61
pt[0..12]   352.322 ms
pt[13..23]  347.375 ms
pt[24..31]  342.375 ms
pt[32..]    337.375 → 332.375 → 327.375 → 322.375 → 317.375 ms
```

与真机日志的音符间隔逐段一致（352 → 347 → 342 → 337 → 332 → 327 → 322 → 317）。
61 段中有 **7 段被接受**（段 13、24、32、40、45、48、60），**每一次的最优偏移都是 −40 ms（搜索下界）**，
每接受一次拍长缩短 5 ms（−40 / 8 拍）。

**独立验证真实速度**（不共享 Kotlin 代码：对音频谱通量在 160–182 BPM 内做复数解调扫描）：

| 窗口 | 20 s 滑窗最佳 BPM |
|---|---|
| 全曲 | **170.34** |
| 0–150 s 每个 20 s 窗口 | **169.70 ~ 171.29** |

**歌曲全程没有 11% 的加速。** 分段细化器输出的 352 → 317 ms 是错误的。

## 3. 根因：L2 残差在密集 onset 上系统性偏爱更短的拍长

`RhythmTimingRefiner.residual()`（[RhythmTimingRefiner.kt:150](file:///D:/3rd-party-projects/PixelPlayerOSS/app/src/main/java/com/lostf1sh/pixelplayeross/data/rhythm/RhythmTimingRefiner.kt#L150)）
的评分是「每个 onset 到最近拍线的强度加权平方距离」。对**相位近似均匀**的 onset 集合，
期望残差 ≈ `P²/12`（P 为拍长）——**拍越短，分数必然越低**，与音乐的真实速度无关。

这首歌恰好是这种材料：

- 1305 个 onset / 173 s ≈ 7.5 个/s ≈ **每拍 2.6 个**；
- 相对全局网格的相位直方图 16 个桶几乎均匀，圆统计浓度 **R = 0.179**（top 30% 也只有 0.317）；
- 相位信息不足，L2 景观的全局极小值就是"尽可能短的拍"。

于是每一段的最优偏移都贴在下界（−40 ms）；`±3%` 限幅只限制了每段能走多快，
15% 改进门槛（[RhythmTimingRefiner.kt:105](file:///D:/3rd-party-projects/PixelPlayerOSS/app/src/main/java/com/lostf1sh/pixelplayeross/data/rhythm/RhythmTimingRefiner.kt#L105)）
被随机跨过几次；而接受后的拍长又被**原样带到下一段**
（[RhythmTimingRefiner.kt:113](file:///D:/3rd-party-projects/PixelPlayerOSS/app/src/main/java/com/lostf1sh/pixelplayeross/data/rhythm/RhythmTimingRefiner.kt#L113)），
形成**只降不升的棘轮**：7 次 × 5 ms = 35 ms/拍，352.3 → 317.4。

`RhythmRepository.gridTimingFor()`（[RhythmRepository.kt:232](file:///D:/3rd-party-projects/PixelPlayerOSS/app/src/main/java/com/lostf1sh/pixelplayeross/data/rhythm/RhythmRepository.kt#L232)）
把母谱全部 onset 喂给它，`RhythmChartDeriver.snapTime()`（[RhythmChartDeriver.kt:150](file:///D:/3rd-party-projects/PixelPlayerOSS/app/src/main/java/com/lostf1sh/pixelplayeross/data/rhythm/RhythmChartDeriver.kt#L150)）
再按各段拍长吸附——错的是"分段拍长"本身，吸附按设计执行。

## 4. 为什么旧的全局网格也会漂，以及这次为什么不同

08:10 那局的旧谱（`rhythm-drift-1793664622148.txt`）间隔**恒为 351 ms**，即 171.0 BPM——
正是 `analyze` 的自相关粗估 `detectedBpm=170.99`，**尚未经过 `refineTempo()`**。
`tools/rhythm-lab/docs/rhythm-clock-drift-diagnostics.md` §6.2/§6.3 已确认：0.4% 的 BPM 误差会造成
约 94 s 一个循环的锯齿，这才是旧谱"一会准、一会不准"的原因；整曲相位细化后的
`usedBpm=170.299` 与独立测得的 170.34 只差 0.024%。

也就是说：**旧漂移的根因（全局 BPM）已经在母谱生成侧修好了；新的分段细化器是在修好的
基础上又引入了一个更大的错误。** 用户这次重新生成了母谱，`timing.first().beatMs` 已经是
352.3218（170.30 BPM），只要不被细化器改坏，网格谱就会回到"接近整曲最优"的状态。

## 5. 修复方案

### 5.1 立即（推荐先做）：网格模式直接用母谱的全局 timing

- `gridTimingFor()` 不再调用 `RhythmTimingRefiner`，直接返回 `master.timing`；
- 删除 `RhythmTimingRefiner` 及其测试（派生侧不再做任何"估计"）；
- 网格派生仍走 `derive(..., timing = master.timing)`，单点 timing 与旧行为逐条一致；
- 旧母谱不改也能用；要享受 170.30 BPM 必须重新生成母谱。

预期：按 `rhythm-clock-drift-diagnostics.md` §6.3，整曲相位残差从旧谱的 ±175 ms（整拍回绕）
降到约 **−84 … +76 ms**（GREAT 窗 90 ms 内），不会再出现"后半段整体跑飞"。
这是用最小改动把"严重不准"消掉，先让用户真机验收全局 BPM 细化后的手感。

### 5.2 若 5.1 仍觉得结尾略偏：在母谱生成时做音频相位分段（正确做法）

分段本身没有错，错在**从 onset 时间戳估计节拍相位**——这首歌的 onset 相位近乎均匀，
信息不足。真实节拍在音频的**谱通量包络**里，而母谱生成时本来就持有 `novn`：

1. `refineTempo()` 已有"整曲复数解调"；把它扩展为**滑窗相位曲线**：
   每 8 拍取一个锚点，对 ±N 拍的 `novn` 做 `Σ novn·g·e^{−iωt}`，取相位；
2. 以第一段相位为参考做**相对修正**（只修漂移，不改全局 offset 的起始对齐）；
3. 置信度（`|z|/Σg`）不足的段回退到全局预测；锚点限幅 ±40 ms，拍长回 ±3%；
4. 把结果写进**母谱的 `timing`**（`RhythmChart.timing` 本就是 List，无 schema 改动），
   网格派生直接复用；原声模式不受影响；
5. 这样"派生时不需要音频"的约束仍然成立——分段在生成母谱时一次算好。

离线估计：这首歌的音频相位相对全局网格的漂移约 **+82 ms @ 80 s / −75 ms @ 165 s**；
若只修相对漂移，网格可望贴到 ±20 ms 量级，明显好于 5.1 的 ±80 ms。
代价是要重新生成母谱，且需要为相位曲线写单测与置信度兜底。

### 5.3 不推荐：继续调参

把 ±3% 收紧、把门槛提高、或只用强 onset——都只是在"相位信息不足"的数据上换个噪声阈值，
仍可能出现方向性漂移（离线试过只把评分换成 `1−cos`：结尾仍从 352.3 滑到 342.4）。
问题在信息来源，不在参数。

## 6. 验收

- 5.1：网格谱结尾不再出现整体跑飞；单测（`derive` 单点 timing 逐条不变）全绿；release 可装。
- 5.2（如做）：合成"恒定 170 BPM + 密集细分 onset"输入，分段拍长必须保持在全局 ±1% 内；
  真机日志的 crossings 间隔应全程≈352 ms，而不是 317 ms。
