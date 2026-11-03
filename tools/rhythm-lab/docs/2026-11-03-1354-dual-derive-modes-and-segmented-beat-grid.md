# 双派生模式与分段节拍网格（2026-11-03）

> PC 实验与诊断文档存放于 `tools/rhythm-lab/docs/`；新文档按 `YYYY-MM-DD-HHMM-` 前缀命名，文件名排序即从新到旧。

> **修订（2026-11-03 15:15）**：真机验收发现本文 §4 的分段细化器在密集 onset 歌曲上
> 把拍长从 352.3 ms 一路压到 317.4 ms（反向漂移）。根因与修复方案见
> `2026-11-03-1515-grid-segmentation-runaway-and-fix-plan.md`：网格派生先回到母谱的全局 timing，
> 不再在派生时现场估计分段拍长。

## 1. 决策摘要（2026-11-03 用户确认）

| 决策点 | 结论 |
|---|---|
| 默认派生方式 | **网格对齐**（原声对齐实际效果不达预期，旋律段反而缺音符） |
| 派生默认勾选 | **原声 + 网格同时勾选**，一次生成两种版本，方便对照测试 |
| 谱面命名 | `Easy · 网格 1103 13:54`，时间用 `MMdd HH:mm`（原来的 `MM-dd HH:mm:ss` 太宽） |
| 原声对齐 | 保留，后续继续优化旋律覆盖（见 §7，不在本次范围） |
| 单一母谱 | 不变：两种方式都只读取同一份母谱，不重新解码音频 |

## 2. 背景

v3（`deriveOnsetNative`）只从母谱里挑选 onset、绝不移动时间戳，时间最贴音频；但实测听感
“旋律没了”：选择算法按强度与节拍先验取音符，鼓点/强拍占优，旋律段的弱 onset 被大量丢弃。

v2（`derive`）把音符吸附到量化网格，节奏骨架清晰、听感更“像一首谱”，代价是音符时间会移动，
且旧实现只使用全局 BPM 网格，曲末会随 BPM 误差漂移。

结论：**两条路径都保留，派生时由用户选择**；网格路径补上早已讨论过的**分段节拍**
（`docs/rhythm-dsp-chart-generation-plan.md` §11.2），把全局网格换成会跟踪速度漂移的分段网格。

## 3. 现状与关键约束

- `RhythmChartDeriver.derive`（v2）目前只读 `master.timing.firstOrNull()`，多段 timing 会被忽略。
- 母谱现在只写一个 `RhythmTimingPoint`；`RhythmChartGenerator.renderMaster` 与 `render` 都如此。
- v3 的 `beatWeight` 已按 `timing.lastOrNull { timeMs <= t }` 取点；本次**不把分段结果写回母谱**，
  所以 v3 仍按全局网格做选音先验（要让它受益，需另行把分段 timing 持久化到母谱）。
- `timing` 不参与游戏判定（判定只读 `note.timeMs`），只用于派生、BPM 展示与未来的 `.osu` 导出。
- 因此：**分段 timing 只写进派生谱自己的 `timing`，不需要存储 schema 变更。**

## 4. 分段节拍器（新增 `RhythmTimingRefiner`）

纯函数，输入母谱音符（时间 + 强度）与全局 timing，输出分段 `RhythmTimingPoint` 列表：

1. **分段**：每 8 拍一个锚点，锚点 0 = 全局相位（`timing.timeMs`）。
2. **搜索**：下一锚点以「上一锚点 + 8 × 当前局部拍长」为预测，在 ±40 ms 内以 1 ms 步长搜索，
   目标是最小化段内 onset 到最近拍线的**强度加权平方残差**。
3. **防抖与兜底**：
   - 段内 onset 少于阈值（建议 4 个）→ 不调整，直接用预测锚点；
   - 残差改进不足（建议 < 15%）→ 不调整；
   - 段间拍长变化限幅 ±3%，锚点强制单调；
   - 对拍长序列做 3 点中值平滑后再重建锚点。
4. **输出**：每个锚点一个 `RhythmTimingPoint(anchorTime, (nextAnchor - anchor) / 8)`；
   最后一段沿用当前局部拍长。若没有任何段被接受，返回原始单点 timing。
5. **确定性**：只依赖母谱数据，旧母谱**无需重新分析音频**即可在派生时现场计算。

## 5. 网格派生改为分段网格

- `RhythmChartDeriver.derive` 增加可选 `timing` 参数（默认 `master.timing`），仓库在网格模式下传入
  分段结果。
- 按音符时间找到所属段，用该段的 `beatMs` 与相位吸附到 `beatMs / gridDiv` 的局部格；
  段边界落在整拍上，不会撕开网格；吸附到同一毫秒的边界格合并为一格。
- 和弦逻辑（70 ms 窗口、`maxChord`、车道避让）完全复用。
- **单点 timing 的输出与今天逐条一致**：现有黄金夹具与回归测试不动。

## 6. 派生模式、UI、仓库与命名

- 新增 `RhythmDeriveMode { GRID, ONSET }`。
- 仓库：
  - `deriveCharts(..., modes: Set<RhythmDeriveMode>, displayNames: Map<Pair<DspDifficulty, RhythmDeriveMode>, String>)`
  - `previewDerivedCounts(..., modes)` 返回同样的键，预览数与落盘数一致。
  - 网格模式的分段 timing 只计算一次，整批复用。
- 派生 sheet：
  - 顶部新增「派生方式」多选 FilterChip，**默认全选**；
  - 难度行同时显示已选方式的预计音符数（如 `网格 123 · 原声 98`）；
  - 任一方式未选时禁用派生按钮。
- 命名：`Easy · 网格 1103 13:54` / `Easy · 原声 1103 13:54`；
  `difficultyKey` 仍是 `easy/normal/hard`，存储格式不变。
- 生成顺序保持“从难到易”，网格版本最后创建、排在最上。
- 新字符串同时补 `values/` 与 `values-zh-rCN/`。

## 7. 原声对齐的后续优化（本次不做，仅记录方向）

1. **频带配额**：给中高频旋律带保留最低占比，避免鼓点把预算吃光。
2. **频带内强度归一化**：不同乐器的 onset 强度尺度不同，先按频带归一再加权。
3. **旋律显著性**：用谐波/音高变化补充候选，而不是只看谱通量峰值。
4. **选择目标**：在现有节拍先验之外加入频带均衡项，并评估“每段至少 N 个旋律音符”。

## 8. 测试与验收

- `RhythmTimingRefinerTest`：
  - 合成“120 BPM → 119.7 BPM”的 240 拍漂移，断言分段后残差显著小于全局；
  - 锚点单调、段间拍长在 ±3% 内；
  - onset 不足 / 完美全局网格 → 返回单点 timing。
- 网格派生：多段 timing 的吸附测试；单段 timing 的既有测试逐条不变。
- 两种模式的预览数量 == 实际落盘数量（可按仓库纯函数测试）。
- `:app:testDebugUnitTest` 全绿；`:app:assembleRelease` 通过。
- 真机手感（尤其网格模式后半段是否还跟得上、原声模式旋律覆盖）由用户验收。

## 9. 风险

| 风险 | 缓解 |
|---|---|
| 分段锚点被弱段拉偏 | 强度加权、最少 onset 数、改进阈值、±3% 限幅、中值平滑 |
| 拍长抖动造成“忽快忽慢” | 同上；必要时把 ±3% 收紧或加大段长（8 → 16 拍） |
| 旧母谱的分段结果与新版生成不一致 | 同一纯函数、同一输入（母谱音符）；单测覆盖 |
| 名称去掉秒后同一分钟重复派生重名 | 名称只用于展示；谱面以内容哈希区分，重名不影响游玩 |

## 10. 实施顺序

1. `RhythmTimingRefiner` + 单测；
2. `derive` 分段网格 + 单测（保单点回归）；
3. `RhythmDeriveMode` + 仓库 API + 预览；
4. 派生 sheet 多选 UI + 字符串；
5. `RhythmSetupScreen` 命名/透传 + 文档回写；
6. 全量单测 + release 构建。

## 11. 实施状态（2026-11-03 14:01）

- 已落地：`RhythmTimingRefiner`、`derive` 分段吸附、`RhythmDeriveMode` 与仓库 API、
  派生 sheet 方式多选（默认全选、估计值分方式显示）、命名改 `MMdd HH:mm`。
- 单测：`RhythmTimingRefinerTest` 4/4、`RhythmChartDeriverTest` 22/22；全量
  `:app:testDebugUnitTest` 通过；`:app:assembleRelease` 通过（v2 签名，`CN=pisces312`）。
- 待真机验收：网格模式后半段是否仍跟拍；原声模式的旋律覆盖属于 §7 的后续优化。
