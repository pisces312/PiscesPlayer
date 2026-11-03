# PC 实验：把网格谱面与原曲的误差压到 20 ms 内（2026-11-03）

针对真机日志里"分段后仍严重跑偏"的问题，在 PC 上把基线歌曲
`E:/迎着风向前冲-姜月初.mp3` 作为回归基准，先离线验证出一套能稳定达标的
谱面生成配方。本轮**只改 lab（Python），未改 app 代码**。

## 结论

误差的主要来源不是时钟，也不是"分段不够细"，而是**生成侧的前端与跟拍**：

1. 8 个频带的 novelty 分辨率太低，DP 跟拍在中后段整拍漂移
   （相对 Beat This! 参考：中位 20.3 ms、p95 327 ms）；
2. 旧的 comb 相位把 30.35 帧的周期四舍五入成 30 帧再折进残差表，
   相位本身就算错了（导出 offset 139 ms，而细扫相位约 328 ms，且逐窗口漂移）；
3. 曲尾混响里的"拍"被当成真拍，最后一段网格被拖晚。

改成 **64 频带 novelty + 半速 DP 跟拍 + RMS 尾部门控 + 加权分段线性拟合** 后，
三个难度（div 1/2/4）的网格线全部落在 20 ms 内。

## 指标口径

- 谱面难度 `d` 的网格间距是 `beatMs / d`，与参考拍的 `d × 2` 等分网格逐线比较
  （本曲谱面拍 = 八分音符，参考拍 = 四分音符，ratio = 2）。
- 评估区间固定为 **2000 ms … 最后一颗参考拍（171361 ms）**，
  不延伸到曲尾：171.4 s 之后只剩混响，外推网格会让 max 失去意义。
- PASS 判据：每个难度的 `max |error| ≤ 20 ms`。

## 结果（相对 Beat This! final0，曲内 244 颗拍）

| 谱面 | div | n | 中位 | p95 | max | ≤20 ms |
|---|---|---|---|---|---|---|
| 当前 master | 1 | 480 | 157.8 | 174.6 | 180.1 | 0.8 % |
| 当前 master | 2 | 961 | 18.2 | 70.1 | 88.5 | 55.5 % |
| 当前 master | 4 | 1922 | 16.7 | 40.1 | 44.5 | 61.8 % |
| 当前 grid_refined | 1 | 501 | 112.4 | 165.5 | 178.1 | 8.6 % |
| 当前 grid_refined | 2 | 1002 | 39.6 | 82.7 | 89.3 | 22.3 % |
| 当前 grid_refined | 4 | 2004 | 24.4 | 42.0 | 44.6 | 39.6 % |
| **新配方** | 1 | 511 | 3.5 | 11.8 | 19.2 | **100 %** |
| **新配方** | 2 | 992 | 3.3 | 11.7 | 19.2 | **100 %** |
| **新配方** | 4 | 1954 | 3.3 | 11.6 | 19.6 | **100 %** |

时序点按整数毫秒落盘后复测：max 18.95 / 18.95 / 19.56 ms。

## 复现

```bash
python tools/rhythm-lab/run_lab.py decode
python tools/rhythm-lab/run_lab.py reference   # 仅首次，需要 lab venv
python tools/rhythm-lab/run_lab.py export      # 跑真实 Kotlin 管线
python tools/rhythm-lab/run_lab.py analyze     # 当前谱面 vs 参考
python tools/rhythm-lab/run_lab.py evaluate    # 新配方 vs 参考
python tools/rhythm-lab/experiments/tune_tracker.py   # 参数扫描 + 回归（失败退出码非 0）
```

配方、目录结构、指标细节见 `../README.md`；每个一次性实验回答了什么见
`../experiments/README.md`。

## Kotlin 落地方案（已实施，见下节实测）

目标：让生产管线自己产出的 `master.timing` 就是这套分段网格，GRID 派生直接使用它，
不再依赖 `RhythmTimingRefiner`。**onset 检测保持现有 8 频带不动**，64 频带只服务节拍跟踪，
这样母谱音符集合与黄金夹具都不变，改动只影响网格相位与拍长。

1. 新文件 `RhythmBeatTracker.kt`（`internal object`，纯 Kotlin/JVM，无 Android 依赖）
   - `novelty64()`：沿用现有 N_FFT=2048 / HOP=512 / 对数频带边 / 32 帧 running-max /
     `log1p` / 半波整流 / p95 归一；44.1 kHz 下实际得到 52 个不同频带。
   - `beatTrack()`：novelty 先按 std 归一；`startBpm = usedBpm / 2`（谱面拍是音乐拍的一半）；
     `tau ∈ [round(period/2), round(2*period)]`；转移罚 `600 · ln(tau/period)²`；
     回溯从末尾 `2*period` 帧内累计值最大处开始；在 ±2 帧内找局部峰并做抛物线细化。
   - `trimSilentTail()`：拍点处 100 ms 局部 RMS 低于全曲 100 ms RMS 中位数的 0.25 时，
     只从尾部丢弃（前奏安静不受影响）。
   - `fitTiming()`：每颗音乐拍一个锚点；±10 拍窗口；权重
     `clip(1 - |i-a| / 11, 1e-3)³`；最多 4 轮残差裁剪，阈值 `max(45 ms, 3×中位残差)`；
     `beatMs = slope / 2`；锚点取整到毫秒落盘；`extraOffsetMs` 整体平移所有锚点。
   - 守卫：少于 2 颗拍，或拟合中位拍长与 `60000 / usedBpm` 偏差超过 5% 时，
     退回单锚点方案（拍长沿用用户/检测 BPM），避免把错拍率写进谱面。
2. `RhythmChartGenerator.analyze()`（`RhythmChartGenerator.kt:99`）
   - tempo 估计之后运行 tracker；`DspAnalysis` 增加 `timing: List<RhythmTimingPoint>`；
   - 删除 comb 相位段（当前 264–286 行）；`beatOffsetMs` 改为首个锚点，仅保留诊断含义；
   - `renderMaster()` 写完整 timing；`render()` 改用同一份 timing 做吸附，
     维持"直接渲染 == 派生"的既有约束。
3. `RhythmRepository.gridTimingFor()`（`RhythmRepository.kt:232`）
   - `master.timing.size > 1` 时直接返回；否则退回 `RhythmTimingRefiner`，只服务旧母谱。
     重新生成的母谱自动走新网格；旧母谱重生成一次即可受益。
4. 测试
   - 新增 `RhythmBeatTrackerTest`：恒速合成鼓（锚点间距 ≈ 期望拍长）、淡出尾部被丢弃、
     BPM 守卫回退。
   - 更新 `RhythmChartGeneratorTest` 中"单段 timing"的三处断言。
   - 黄金夹具（8 频带 onset 未动）预期仍通过；若时移超过 ±15 ms，先查吸附相位，
     不擅自重新生成夹具。
5. 验收
   - PC：`run_lab.py export`（真实 Kotlin 管线）→ `analyze`，要求 div 1/2/4 的
     `max ≤ 20 ms` 且 100% 覆盖；
   - `:app:testDebugUnitTest` 与 `:app:assembleRelease`；
   - 装 release APK，对 `迎着风向前冲` 重新生成母谱 + 两种派生，试玩确认。

## Kotlin 移植实测（2026-11-03）

移植已完成并验收。`run_lab.py export`（真实 Kotlin 管线）→ `analyze` 对 Beat This!：

| 谱面 | div | n | 中位 | p95 | max | ≤20 ms |
|---|---|---|---|---|---|---|
| master（新 timing） | 1 | 520 | 3.4 | 11.8 | 18.9 | **100 %** |
| master（新 timing） | 2 | 1001 | 3.4 | 11.7 | 18.9 | **100 %** |
| master（新 timing） | 4 | 1963 | 3.3 | 11.5 | 19.6 | **100 %** |

与 Python 原型逐项一致（原型整数落盘 18.95 / 18.95 / 19.56），**≤20 ms 目标由生产管线达成**。
派生谱（grid_global_*）走 `master.timing`，同一组数字。

落地文件：`RhythmNovelty.kt`（64 频带前端，与 8 频带共用）、`RhythmBeatTracker.kt`
（DP 跟拍 + RMS 尾部门控 + 三分权重局部线性拟合 + 5% 拍长守卫）、
`RhythmChartGenerator.analyze()`（删除 comb 相位段，timing 进 `master.timing`）、
`RhythmChartDeriver.snapTime`（段边界吸附归并，见下）与 `RhythmRepository.gridTimingFor`。

### 一个真实的边界 bug（已修）

锚点取整成毫秒而 `beatMs` 保留小数，段内最后一条网格线会四舍五入到下个锚点 ±1 ms，
同一拍被拆成两个相差 1 ms 的格子（基线曲 19 个段边界里 10 个如此，EASY 谱多出 8 个音符）。
`snapTime` 现在把"段内最后一条格线"直接吸附到下一个锚点，边界拍唯一。

### 黄金夹具的相位冲突（待用户裁决）

`RhythmChartGeneratorGoldenTest` 的难度谱对比现在只剩**相位**不通过（计数已通过）：
match 55–77%（15 ms 容差、要求 95%）。三方网格相位实测（dsp-beats-120bpm 合成曲，
真实拍点在 0+k·500 ms）：

| 网格 | 相位偏差 | 拍长 | 20 s 末偏差 |
|---|---|---|---|
| 真实（合成拍点） | 0 | 500.000 | — |
| 新 tracker | +5.5 ms | 500.02 | ~10 ms |
| 夹具 oracle（Python 原型） | +26.5 ms | 499.72 | ~25 ms |

夹具的期望值来自原型自己的 offset 估计，比 tracker 差约 21 ms；tracker 是对的
（对 Beat This! 18.9 ms 也印证）。**未擅自重新生成夹具**，选项：
A. 用 tracker 网格重生成难度谱期望值（改 `gen_dsp_fixture.py`）；B. 难度谱对比先对齐相位再比 15 ms；
C. 容差放宽到 ~40 ms。母谱（raw onset）对比不受影响，仍然 15 ms 全过。

## 生产化前必须知道的风险

- 达标区在 DP tightness 上很窄：400 会退化到 max ~42 ms，600–1000 才稳定；
  目前只在**一首歌**上调过，应再取 3–5 首不同曲风验证后再认为稳定。
- Beat This! 是 50 fps 模型，即使做了亚帧插值，参考自身仍有约 10–20 ms 噪声；
  p95 ≈ 11.7 ms、max ≈ 19.6 ms 已接近该参考的分辨率下限。
- 新 timing 只在重新生成母谱后生效；旧母谱（单段 timing）派生网格仍走 `RhythmTimingRefiner`。
