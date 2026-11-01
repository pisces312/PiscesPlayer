# 节奏游戏：本地 DSP（傅里叶）谱面生成方案

> 日期：2026-11-01
> 状态：**v1 已实现**（单测 994/994 通过、`:app:assembleRelease` 已签名产出）；**真机未验收**
> 已确认决策：默认 Easy 档 · 车道先与 Python 一致（`BAND_ORDER`）· 多次生成保留多份（时间戳命名）·
> 音频上限 12 分钟 · 生成完成弹框询问是否立即游玩 · 生成参数在界面上可调
> 实现记录见 §15，实测数据见 §9.2
> **后续演进**：单档生成已升级为「唯一母谱 + 无限派生」，见 `docs/rhythm-chart-ladder-plan.md`
> （同日晚完成，全量单测 1022/1022）。本文保留为算法与夹具的原始依据，§15 的数字是 v1 时点的快照。
> 依据：`docs/rhythm-game-integration-plan.md`（现有谱面接口）、
> `RhythmChartLab/docs/fourier-vs-model-comparison-2026-11-01.md`（PC 实测）、
> `RhythmChartLab/rhythm-lab/fourier/fourier_chartgen.py`（参考实现，360 行）
> 结论等级标注：**【PC 实测】** = RhythmChartLab 已跑出的数字；**【本机复核】** = 本次会话重新执行；
> **【JVM 实测】** = 本仓库单测里的数字；**【估算】** = 未实测的推算；**【计划】** = 尚未做的设计决定。

---

## 1. 目标与边界

### 1.1 目标

在现有谱面链路（`RhythmChart` / `RhythmLibraryStore` / `RhythmGameScreen`）上，新增
**不依赖模型、纯 CPU、秒级出谱**的本地生成通道，让「导入 .osu」不再是唯一来源。

### 1.2 交付内容

1. 纯 Kotlin 的 DSP 生成器（无 Android 依赖，可 JVM 单测）。
2. 选谱页新增「自动生成」入口，含难度/密度参数与进度。
3. 生成的谱面以 `source = "dsp"` 写入现有谱面库，与导入谱面**同一条游玩链路**。
4. 黄金夹具与单测，保证 Kotlin 实现与 Python 参考实现语义一致。

### 1.3 明确不做

- **不做**模型推理（Mapperatorinator / MNN 是 RhythmChartLab 的另一条主线，与本方案无关）。
- **不改**判定、时钟、游戏画面、`RhythmChart` 数据模型。
- **不做** hold、变速（SV）、非 4K。
- **不做**逐 note 的音符级响度筛选（实测无效，见 §3.2）。
- **不补偿**播放侧的系统性偏移（那是 `offsetMs` 校准问题，见 §11.1）。

---

## 2. 现状核对

### 2.1 可直接复用的部分

| 能力 | 位置 | 说明 |
|---|---|---|
| 统一谱面模型 | `data/rhythm/RhythmChart.kt` | `source` 已允许 `"dsp"`；`timing: List<RhythmTimingPoint>` 已预留 |
| 谱面持久化 | `data/rhythm/RhythmLibraryStore.kt` | `library.json` + `charts/<chartId>.json`，原子写、幂等 |
| 音频解码 | `data/rhythm/RhythmPcmDecoder.kt` | MediaCodec → PCM16 立体声，整曲一次性 |
| 游玩链路 | `RhythmGameScreen` / `RhythmPlaySession` / `RhythmJudgement` | 只读 `chart.notes`，与来源无关 |
| 云端判定 | `CloudOfflineRepository.isCloudSong` | 已在游戏页用于拦截 |

**结论：游戏侧零改动。** 生成器只负责产出 `RhythmChart`。

### 2.2 缺口（唯一硬缺口）

`RhythmLibraryStore.importText(songId, text)` 内部是 `OsuManiaParser.parse(text)` +
`contentId(text)`，**只接受 osu 文本**，无法接收内存中的 `RhythmChart`。

需要新增 `importChart(songId, chart, replaceSameDifficulty)`。

> **兼容红线**：`importText` 的 `chartId` 来自 **osu 原文**的 SHA-256 前 8 字节。
> 新增 `importChart` 时**绝不能**把它改成「统一谱面 JSON 的哈希」——否则用户已导入的谱面
> 全部换 id，最高分与绑定关系一次性失效。两条路径必须共用私有 `persist(...)`，各自传自己的 id。

---

## 3. 质量定位：能到什么程度

### 3.1 主结论（同曲对照，261.9 s / 90.84 BPM）

| 策略 | notes | NPS | F1@easy | F1@normal | F1@hard |
|---|---:|---:|---:|---:|---:|
| 裸 onset（无约束） | 2012 | 7.68 | 37.5 | 49.0 | 74.5 |
| 整拍量化 1/1 | 407 | 1.55 | **89.9** | 78.1 | 44.1 |
| **半拍量化 1/2** | 794 | 3.03 | 79.0 | **93.0** | 63.5 |
| 1/4 拍量化 | 1451 | 5.54 | 51.2 | 63.2 | 70.5 |
| 1/6 拍量化 | 1753 | 6.69 | 41.6 | 53.5 | 69.3 |
| 半拍窗内择优（保留原时间） | 794 | 3.03 | 57.4 | 65.2 | 58.7 |

参照系：模型自身 easy→normal 一致性 F1 = 87.0。

**读法**：半拍量化 + 每格 1 note 的产出，落在模型自身风格方差内；裸 onset 差 44 分。
**量化是全部收益的来源，且成本几乎为零。** 【PC 实测】

### 3.2 三个反直觉结论（决定实现取舍）

1. **ODF 强度无用**：判别「模型是否在此放 note」的 AUC = 0.508（等于抛硬币），
   按强度分位数分组的命中率 72.4%–75.2% 完全平坦 ⇒ **不要按强度排序筛密度**。
2. **局部 RMS 有判别力但只能用于段级**：AUC 0.772；逐 note 卡阈值只能把 F1 从 49 拉到 70，
   却会把 794 降到 1029 → 63.2（B4）。⇒ **默认不启用响度过滤**，仅作为可选旋钮。
3. **NPS 上限是难度旋钮，不是滤波器**：B5（NPS≤5）召回掉到 35.8。⇒ **默认关闭**。

### 3.3 难度档位 = 网格密度

```
div=1（整拍） 1.55 NPS → easy    F1 89.9
div=2（半拍） 3.03 NPS → normal  F1 93.0
div=4（1/4拍）5.54 NPS → hard    F1 70.5
```

**本方案的默认路径 = 最简路径**：STFT → ODF → 峰值 → BPM/相位 → 网格量化 → 每格 1 note →
车道。任何额外约束都会降质（除 §11.2 的漂移修正外）。

---

## 4. 算法移植规格（Python → Kotlin）

参考实现 `fourier_chartgen.py`。下表是**必须逐项对齐**的参数，任一项错都会让黄金夹具失配。

### 4.1 阶段总览

| # | 阶段 | 输入 | 输出 | 复杂度（5 min 44.1k） |
|---|---|---|---|---|
| 1 | 预处理 | PCM16 立体声 | mono float + 帧参数 | O(n) |
| 2 | STFT | mono float | 8 带能量矩阵（帧×8） | O(帧×FFT)，帧≈25840 |
| 3 | Whitening | 带矩阵 | 归一化带矩阵 | O(帧×8×W)，单调队列可 O(帧×8) |
| 4 | 谱通量 ODF | 带矩阵 | `nov[帧]` | O(帧×8) |
| 5 | 峰值拾取 | `nov` | onset 帧 + 强度 | O(帧×43) |
| 6 | BPM + 相位 | `nov` | `bpm`, `phaseFrame`, `offsetMs` | O(帧×61) |
| 7 | 网格量化 | onset 帧/强度 | 去重后的 (t, strength) | O(m log m) |
| 8 | 车道分配 | 带矩阵 + 帧索引 | `lane[]` | O(m) |
| 9 | 组装 | 上述 | `RhythmChart` | O(m) |

### 4.2 固定参数（44.1 kHz 下的实际值，已复核）

| 常量 | 值 | 推导 |
|---|---|---|
| `N_FFT` | 2048 | 固定 |
| `HOP` | 512 | 固定 |
| `N_BANDS` | 8 | 固定 |
| 频带边界（bin 索引） | `[1, 2, 6, 13, 32, 76, 181, 431, 1024]` | `unique(round(geomspace(1, 1024, 9)))` |
| whitening 窗 | 32 帧（含当前，即 `[j-32, j]`） | 固定 |
| 峰值局部极大半径 `hl` | `max(1, ⌊50 / hopMs⌋)` = **4** | 44.1k 时 hopMs=11.61 |
| 峰值上下文半径 `hc` | `max(1, ⌊200 / hopMs⌋)` = **17** | 同上 |
| 峰值阈值 | `nov > median(ctx) + 1.5 × MAD(ctx)` | `ctx = nov[max(0,j-hc) .. min(n,j+hc)]` |
| 归一化 | `nov / percentile(nov, 95)` | numpy linear 插值语义 |
| BPM 搜索范围 | 60–200 BPM | 对数先验中心 130，σ=0.9 |
| lag 范围（44.1k） | **25 … 86** 帧 | `⌊fps×60/200⌋ … ⌊fps×60/60⌋` |
| 帧→毫秒 | `(j × HOP + N_FFT − HOP/2) × 1000 / sr` | 窗中心偏移 |
| 量化容差 | 网格的 0.5 | 超过则保留原始时间 |

> 频带边界是按 **bin 索引**（几何级数），不是按 Hz。因此不同采样率下频带对应的实际频率不同
> ——这与 Python 版一致，**不要"修正"成按 Hz 划分**，否则失配。

### 4.3 各阶段实现要点

**阶段 1 · 预处理**

- `mono = (L + R) / 2 / 32768.0`（与 `ffmpeg -ac 1` 的均值下混一致）。
- 帧数 `1 + (samples − N_FFT) / HOP`；`samples < N_FFT` ⇒ 抛可读错误。
- 窗函数用 **numpy 的 symmetric Hann**：`w[n] = 0.5 − 0.5·cos(2πn/(N−1))`。
  这是最容易搞错的一处：periodic 版本（除以 `N`）会让夹具的频带能量整体偏移。

**阶段 2 · STFT（必须流式分块）**

Python 一次性持有 `S`（帧 × 1025 float32）。5 分钟 44.1k 时是 **~106 MB**，Kotlin 不能照搬。

做法：每 512 帧为一块（2048×1025×4 ≈ 8 MB），算完立刻压成 8 带最大值并丢弃幅值谱。
峰值内存 ≈ 8 MB（块）+ 827 KB（带矩阵）。

代价：需要在块内复用 FFT 工作数组与窗表，不能依赖任何第三方信号库。

**阶段 3 · Whitening**

只回看的前向 running max（窗口 32 帧），用单调队列 O(n)；
朴素实现是 `O(帧×32×8)` ≈ 660 万次比较，也够快，但单调队列更稳。

**阶段 5 · 峰值拾取**

- `percentile(nov, 95)`：先复制排序一次（25840 元素，可忽略），按 numpy 的 linear 插值取值。
- 局部极大判定用 `>=`（Python 是 `nov[j] < max − 1e-12` 则跳过）；平顶段只取第一个。
- median/MAD 的窗口最多 35 个元素，直接插入排序即可。

**阶段 6 · BPM 与相位**

- 自相关只算 lag ∈ [25, 86]，共 62 个滞后 × 25840 ≈ 160 万次乘加，**不需要 FFT 自相关**。
  （Python 用 `np.correlate` 全量是为了复用 numpy；Kotlin 别学。）
- 先按 `ac × 对数先验` 取最优 lag，再在 `lag×{1,2,3,4}` 附近 ±2 找更高峰
  （长滞后分辨精度更高），最后抛物线插值，并折回单拍周期。
- 相位：`comb[ph] = Σ novn[ph::p]`，取 argmax。
- 全局 offset：取强度最高的 10% onset，各自算 `(t − phase·hopMs) mod beatLen`，取 **中位数**。
  Python 的 `%` 对正数返回非负，Kotlin 必须写 `((x % m) + m) % m`。

**阶段 6b · 半/倍速判定（新增，Python 版没有）**

Python 版把这个问题丢给用户（`--bpm` 兜底）。Android 上必须自动判：

对候选倍率 `k ∈ {0.5, 1, 2}`，用 `bpm × k` 建网格，计算**每拍平均强度**
`score(k) = Σ novn[拍点] / 拍点数`（不是总和——总和会被网格密度偏置）。
取 `score` 最大者；若 `k ≠ 1` 且优势 > 8%，采用并记录「已做倍频纠正」。

只在实际自估 BPM 落在 [90, 200] 且候选 `k×bpm` 也落在 [60, 200] 时才考虑，
避免把正常 70 BPM 的慢歌硬纠正成 140。

> 依据：同曲自估 182.408 vs 真实 90.840，正好 2.008×，未纠正时曲末相位漂移 **57.35 s**。
> 【PC 实测】

**阶段 7 · 量化与去重**

- 逐 onset：`grid = beatLen / div`，`g = offset + round((t − offset)/grid) × grid`，
  `|t − g| ≤ grid/2` 则取 `g`，否则保留 `t`。
- 去重 key = `round(t, 1)`（0.1 ms 精度），同 key 保留强度更大的。
- **每格最多 1 个音符**——这是「div 即难度」的实现基础，不要改成允许 chord。

> 舍入语义差异：Python `round` 是 half-to-even，Kotlin `roundToInt` 是 half-up。
> 只在恰好半个网格时差一格（≈117 ms），概率极低。**夹具对比因此不比对逐条时间，
> 改用「±15 ms 容差匹配率」**（见 §9.2）。

**阶段 8 · 车道分配**

Python：`lane = band × 4 / 8`（band 0,1→0；2,3→1；4,5→2；6,7→3），连续重复时
`lane = (lane + 1 + band % 2) % 4`。

文档实测提示这个映射不理想：**bass 主导的 onset 命中率最低（55.7%）**，
而 861–2153 Hz（band 4，人声/主乐器）最高（79.6%）。建议改为
**低频→外侧、高频→内侧**：`[0, 0, 3, 3, 2, 2, 1, 1]`。

处理：做成 `LaneStrategy` 枚举（`BAND_ORDER` / `LOW_OUTSIDE`），
**默认 `LOW_OUTSIDE`，黄金夹具固定用 `BAND_ORDER`**，这样既能精确对齐又保留试玩调优空间。

无论哪种策略都必须补两条：同刻同轨冲突消解（不可能出现，但要有断言）、
`lane ∈ 0..3` 钳制。

**阶段 9 · 组装**

```kotlin
RhythmChart(
    source = "dsp",
    difficultyName = "DSP Easy" | "DSP Normal" | "DSP Hard",
    timing = listOf(RhythmTimingPoint(offsetMs.roundToInt(), beatLenMs)), // 单段，v1
    notes = notes,          // id 连续、timeMs 升序、同刻不同轨
    durationMs = pcm.durationMs.roundToInt(),
)
```

`notes` 为空（静音/纯人声）必须抛可读异常，不能返回空谱面（`RhythmChart.init` 也会拦）。

### 4.4 复杂度与性能预算

| 项 | 数值 | 依据 |
|---|---|---|
| Python 60 s 音频全流程 | **0.671 s**（RTF 0.0112，89× 实时） | 【本机复核】 |
| Python 5 min 音频 | ≈ 3.5 s | 【估算】按 60 s 外推，含 `np.correlate` 的 O(n²) |
| Kotlin / PC JVM 5 min | 2–5 s | 【估算】逐帧 FFT 比批处理慢，但去掉了 n² 自相关 |
| Kotlin / Android 中端 5 min | 6–15 s | 【估算】ARM64 + JIT，未含解码 |
| 解码（MediaCodec，5 min） | 2–5 s | 【估算】与游戏加载同量级 |
| 峰值内存 | PCM 40 MB + 分析 ~9 MB | 【估算】 |

**必须显示进度**：解码（无进度，文字态）→ 分析（按帧数百分比）→ 量化/组装（一次跳满）。

---

## 5. 模块与接口设计

### 5.1 新增文件

```
app/src/main/java/com/lostf1sh/pixelplayeross/data/rhythm/
  RealFft.kt                   # 实数输入 FFT（内部半长复数 FFT + 拆分），含 Hann 窗表
  RhythmChartGenerator.kt      # 管线本体：float PCM + sr + 参数 -> RhythmChart
  RhythmChartGenParams.kt      # 参数、难度枚举、LaneStrategy、诊断结果

app/src/main/java/com/lostf1sh/pixelplayeross/presentation/rhythm/
  RhythmGenerateSheet.kt       # 参数面板 + 进度 + 取消 + 错误

app/src/test/java/com/lostf1sh/pixelplayeross/data/rhythm/
  RealFftTest.kt
  RhythmChartGeneratorTest.kt
  RhythmChartGeneratorGoldenTest.kt
  RhythmTestAudio.kt           # 测试用鼓点合成 + 极简 WAV 读取

app/src/test/resources/rhythm/
  dsp-beats-120bpm.wav         # 20 s / 44.1k / mono / 16-bit = 1.76 MB
  dsp-beats-120bpm.expected.json

gen_dsp_fixture.py             # 仓库根目录，与 gen_lyric_fixture.py 同级惯例
```

> 原计划的 `DspFft.kt` 最终命名为 `RealFft.kt`：实现的是**实数输入**变换而不是通用复数 FFT，
> 名字需要反映这一点，否则后来者会以为可以直接拿来算复数信号。

### 5.2 关键签名（草案）

```kotlin
// RhythmChartGenParams.kt
enum class DspDifficulty(val gridDiv: Int, val label: String) {
    EASY(1, "Easy"), NORMAL(2, "Normal"), HARD(4, "Hard")
}
enum class LaneStrategy { BAND_ORDER, LOW_OUTSIDE }

data class RhythmChartGenParams(
    val difficulty: DspDifficulty = DspDifficulty.NORMAL,
    val laneStrategy: LaneStrategy = LaneStrategy.LOW_OUTSIDE,
    val minRmsPercentile: Int? = null,      // null = 关闭；40 = 强；25 = 中
    val maxLocalNps: Double? = null,        // null = 关闭；1 s 滑窗上限
    val bpmOverride: Double? = null,        // 用户手动指定，跳过自动估计
    val extraOffsetMs: Int = 0,             // 整体前推/后移（校准用，默认 0）
)

data class DspDiagnostics(
    val detectedBpm: Double,
    val usedBpm: Double,
    val octaveCorrection: Double,           // 1.0 / 0.5 / 2.0
    val beatOffsetMs: Double,
    val onsetCount: Int,
    val noteCount: Int,
    val analysisMs: Long,
)

// RhythmChartGenerator.kt
object RhythmChartGenerator {
    fun generate(
        pcmMono: FloatArray,
        sampleRate: Int,
        durationMs: Int,
        params: RhythmChartGenParams = RhythmChartGenParams(),
        onProgress: (Float) -> Unit = {},
        isCancelled: () -> Boolean = { false },
    ): GeneratedChart          // chart + DspDiagnostics
}

// RhythmPcmAudio 新增
fun RhythmPcmAudio.toMonoFloat(): FloatArray
```

### 5.3 要改的现有文件

| 文件 | 改动 | 风险 |
|---|---|---|
| `RhythmLibraryStore.kt` | 加 `importChart(songId, chart, replaceSameDifficulty, nowMs)`；抽 `persist(...)`；`importText` 行为**不变** | 低，但必须先跑既有 6 个测试 |
| `RhythmRepository.kt` | 加 `suspend fun generateDsp(songId, uri, params, onProgress): RhythmChartMeta`（解码 → 转 mono → 分析 → 入库） | 中：要保证生成后释放 PCM |
| `RhythmSetupScreen.kt` | 加「自动生成」按钮、sheet 状态、成功后刷新列表 | 低 |
| `RhythmPcmDecoder.kt` | 只加 `toMonoFloat()` 扩展，不动解码逻辑 | 低 |
| `values/strings.xml` + `values-zh-rCN/strings.xml` | 约 12 条新文案 | 低 |

**不要动**：`RhythmChart.kt`、`RhythmGameScreen.kt`、`RhythmPlaySession.kt`、`RhythmJudgement.kt`。

### 5.4 Repository 生成流程

```
1. isCloudSong(song)          -> 直接报可读错误（与游戏页同一文案）
2. song.duration > 12 分钟    -> 直接报错，不解码
3. RhythmPcmDecoder.decode(context, uri)      // 已有
4. pcm.toMonoFloat()                          // 新增；pcm 随之出作用域可被回收
5. RhythmChartGenerator.generate(...)         // Dispatchers.Default
6. chart.copy(difficultyName = "DSP Normal · 11-01 19:31")
7. store.importChart(songId, chart)           // 保留多份
```

**不做同难度替换**（已确认决策）：每次生成的 `difficultyName` 带 `MM-dd HH:mm` 时间戳，
chartId 因此必然不同，列表里并排显示多份，由用户自行删除。
代价是连点十次会留十条 —— 靠时间戳与「DSP」徽标区分，不做自动清理。

---

## 6. UI 与交互

### 6.1 选谱页

在「导入 .osu」下方加一个 `OutlinedButton`：「自动生成谱面」。
`enabled = song != null && !importing && !generating`。

### 6.2 参数面板（`ModalBottomSheet`）

```
标题：自动生成谱面
副标题：用本曲音频做频谱分析，几秒内生成 4 轨谱面

难度      [ Easy | Normal | Hard ]        ← 分段按钮，默认 Easy
          说明：Easy 每拍 1 键 · Normal 每半拍 1 键 · Hard 每 1/4 拍 1 键

弱音过滤   [ 关闭 | 中 | 强 ]              ← 默认关闭（实测开启会降质）
局部密度   [ 关闭 | 每秒 8 | 每秒 5 ]      ← 默认关闭，仅作可玩性保险

▸ 高级
    BPM     [ 自动 | 手动输入 ]            ← 手动时显示数字输入，范围 60–200
    整体偏移 [ -100 … +100 ms ]            ← Slider，5 ms 一档

[ 取消 ]  [ 开始生成 ]
```

生成中：同一 sheet 内换成进度视图（阶段文字 + `LinearProgressIndicator` + 取消）。
完成后：关闭 sheet、刷新列表、在列表顶部插入一张提示卡显示
`已生成 · BPM 128.0 · 269 个音符 · 4.5 音符/秒`，含「播放」与「删除」。

失败：sheet 内红字，不关闭，允许改参数重试。

> 参数不持久化（每次默认 Normal/关闭/自动）。理由：写入 `UserPreferences` 要走 AGENTS
> 的「新增设置开关五处注册」流程，而这是低频操作，不值得。

### 6.3 谱面卡片

`ChartCard` 增加来源徽标：`source == "dsp"` 显示「DSP」，否则「osu」。
难度名本身已带 `DSP ` 前缀，徽标用于快速扫视。

---

## 7. 数据契约与持久化

### 7.1 `chartId`

```kotlin
// osu 路径（不变）
chartId = sha256(osuText).take(8).hex()
// DSP 路径（新增）
chartId = sha256(json.encodeToString(RhythmChart.serializer(), chart)).take(8).hex()
```

同参数重复生成 ⇒ 同一份 JSON ⇒ 同一 chartId ⇒ 幂等。
改了参数（难度/策略/过滤）⇒ 新 chartId ⇒ 视为新谱面（旧的在 §5.4 的替换规则下已被删）。

### 7.2 不变量

- `notes` 按 `timeMs` 升序，`id` = 下标，连续唯一。
- 同刻不同轨允许（本次不会产生，仍要断言）。
- 所有 `timeMs ≥ 0` 且 `≤ durationMs`。
- `timing` 恰好 1 段（v1）；`timeMs = offset`，`beatMs = 60000 / bpm`。
- `source = "dsp"`，`schemaVersion = 1`。

### 7.3 名额与清理

不新增清理策略：沿用现有「删除谱面只删应用私有副本」。生成产物约 40–120 KB/首，可忽略。

---

## 8. 参数与预设的实测依据

| 预设 | `gridDiv` | 预期 NPS（90–130 BPM 区间） | 实测 F1（该档） |
|---|---:|---|---:|
| Easy | 1 | 1.5–2.2 | 89.9 @easy |
| Normal | 2 | 3.0–4.3 | 93.0 @normal |
| Hard | 4 | 5.5–8.5 | 70.5 @hard |

NPS 随 BPM 线性变化，因此**同一档位在不同曲目上密度不同**——这是刻意的，
「每秒几个音符」才是难度旋钮（文档实测）。真机若发现 Hard 过快，
优先调 `gridDiv`（4→3）而不是加 NPS 上限（后者召回暴跌）。

---

## 9. 测试与验收

### 9.1 单测清单

| 测试 | 覆盖点 | 预期 |
|---|---|---|
| `RealFftTest` | 与 naive DFT 对比（n=16/64/256/2048）、脉冲平谱、单正弦单 bin、Hann 窗归一、非 2 的幂拒绝 | 相对误差 < 1e-4 |
| `RhythmChartGeneratorTest` | 合成 120 BPM 节拍 → BPM ∈ [119.5, 120.5]；note 数随 div 单调递增；全部 note 落在网格 ±1 ms；lane ∈ 0..3；timing 单段；空数组/超短数组抛可读异常 | 全绿 |
| `RhythmChartGeneratorGoldenTest` | 读 `dsp-beats-120bpm.wav` → 与 `.expected.json` 对比 | 见 §9.2 |
| `RhythmLibraryStoreTest`（补充） | `importChart` 幂等；`replaceSameDifficulty` 只删同源同名；**osu 谱面 id 与成绩不受影响** | 全绿 |

### 9.2 黄金夹具口径

夹具：20 s / 44.1 kHz / mono / 16-bit，120 BPM，kick+snare+hihat+bass（与
`synth_and_bench.py` 同配方），由 `gen_dsp_fixture.py` 生成，
**同时产出 wav 与 `expected.json`（bpm / offset / notes 列表）**。

判定口径（三条全过）：

1. `|bpm − expected.bpm| ≤ 0.5`
2. `|noteCount − expected| / expected ≤ 5%`
3. **±15 ms 容差匹配率 ≥ 95%**（双向：每个 Kotlin note 在 expected 里有邻居，
   且每个 expected note 在 Kotlin 输出里有邻居）

为什么不逐条严格相等：Python `round` 是 half-to-even、Kotlin 是 half-up，
半个网格的边界会差一格；另有 float32/float64 累积差异。用容差匹配才能既严格又稳定。

> 夹具必须用**当前版本**脚本重新生成。仓库里 `test128.json`（337 notes）是 14:45 的旧产物，
> 16:56 的脚本在同一个 wav 上产出 **269 notes**（【本机复核】）——历史产物已过期，
> 不能直接当期望值。

#### 实测结果（2026-11-01，【JVM 实测】）

夹具 20 s / 120 BPM，参考实现给出 41 / 81 / 146 notes（div 1/2/4），BPM 120.066、offset 26.5 ms。

| 难度 | Kotlin notes | BPM | 正向匹配 | 反向匹配 | lane 一致 |
|---|---:|---:|---:|---:|---:|
| Easy (1/1) | 40 / 41 | 120.06634 | 100.0% | 97.6% | 12/40 |
| Normal (1/2) | 80 / 81 | 120.06634 | 100.0% | 98.8% | 34/80 |
| Hard (1/4) | 146 / 146 | 120.06634 | 100.0% | 100.0% | **146/146** |

**BPM 与参考实现逐位一致**（120.06633892445564 vs 120.066），说明 STFT、频带、whitening、
谱通量、峰值拾取整条链路与 Python 语义一致 —— 自相关对这些环节极其敏感，任何一处偏差都会挪动 BPM。

**lane 在 Hard 档 100% 一致，在 Easy/Normal 明显分叉。** 这不是实现错误：

- 量化格越粗，越多 onset 竞争同一个 cell（Easy 的 kick 与半拍 hihat 会被吸附到同一拍），
  cell 里只保留谱通量最强者；
- 「保留谁」取决于 `argmax(whitened bands)`，而两个频带同时达到各自 running-max 时（≈1.0）
  排序由浮点尾数决定，参考实现自己在相邻音符之间也会来回跳。

因此黄金测试**只在 Hard 档断言 lane 一致率 ≥95%**（该档每个 onset 独占一格，无竞争），
Easy/Normal 只输出统计不设门禁。这也解释了 40 vs 41 的 1 个音符差异（同一歧义的另一种表现）。
【JVM 实测】

### 9.3 构建与真机

- 每次改动跑 `:app:testDebugUnitTest`；合并前跑 `:app:assembleRelease`。
- 真机验收（单独标注，不与单测混谈）：
  1. 选一首 3–5 分钟本地曲，Normal 档生成，记录耗时、note 数、BPM；
  2. 实际游玩，主观判断「踩点是否跟上」；
  3. 与同曲的 osu 谱面（若有）对照 note 密度分布；
  4. 切后台再回、旋转、退出，确认无残留音频与 ANR；
  5. 长曲（>8 分钟）确认不 OOM。

---

## 10. 风险与缓解（按严重度）

| 级别 | 风险 | 现状 | 缓解 |
|---|---|---|---|
| **高** | BPM 半/倍速歧义 | 实测锁到 2.008×，未纠正时曲末漂移 57.35 s | §4.3 阶段 6b 自动判定 + 高级面板允许手动 BPM |
| **高** | 全局 BPM 的累积漂移 | 0.364 BPM 误差 → 曲末 0.46 s ≈ 0.7 拍 | v1 接受并显示 BPM；v2 做分段 timing（锚点法，§11.2） |
| 中 | 4 分钟以上的后半段错位 | 同上，随曲长线性放大 | 真机先用 3 分钟内曲目验收；文档标注为已知限制 |
| 中 | 解码 + 分析峰值内存 | PCM 40 MB（5 min）+ 分析 9 MB | v1 接受；超 12 分钟拒绝并提示；v2 改流式解码 |
| 中 | 车道分配质量 | 主导频带映射的命中率证据不利（bass 55.7%） | 默认 `LOW_OUTSIDE`，留 `BAND_ORDER` 对照；真机试玩后定 |
| 中 | 播放侧 −57 ms 系统性提前 | 已有记录，与生成器无关 | 生成器不补偿；靠 `offsetMs` 校准单独解决 |
| 低 | 采样率差异（44.1k vs 48k） | 算法自适应 hopMs，行为近似 | 夹具固定 44.1k；真机差异 < 1 ms，不处理 |
| 低 | 静音/纯人声曲目 0 onset | — | 抛出「未检测到节奏事件，请换一首或手动指定 BPM」 |
| 低 | 与 Python 的舍入差异 | half-even vs half-up | 夹具用容差匹配，不追求逐条相等 |

---

## 11. 后续演进（不在本次范围）

### 11.1 逐输出设备偏移校准

真机实测平均误差 −56.6 ms（5 早 / 1 晚），来源未拆分：MP3 编码器延迟 / AudioTrack
缓冲 / `input tap` 注入抖动。需要在游戏内做「校准」入口（播放节拍音 + 用户跟拍），
把结果写入 `offsetMs`。与本方案解耦。

### 11.2 分段 timing 校正（v2 的核心）

做法：以全局网格为初值，每 8 拍在 ±40 ms 内搜索使「网格残差加权和」最小的相位偏移，
得到锚点序列；相邻锚点间线性插值 ⇒ 分段 `RhythmTimingPoint`。
这等价于模型用的 14 段自校正，可把 0.46 s 漂移压到 < 50 ms。
`RhythmChart.timing` 已是 List，数据结构无需改动，**只需生成器多输出几段**。

### 11.3 其他

- 流式解码（省 40 MB）
- 重采样到 22050 单声道（STFT 提速约 4×，需重验质量）
- `.osu` 导出（让生成的谱面能在 PC 上试玩）
- Hard 档的键型结构（1/4 量化 F1 仅 70.5，纯 DSP 做不出模型 placement）

---

## 12. 实施顺序

| 阶段 | 内容 | 验收 | 状态 |
|---|---|---|---|
| **A** | `RealFft` + `RhythmChartGenerator` + 参数模型 + 单测（合成音频，不动 UI） | `RealFftTest` / `RhythmChartGeneratorTest` 全绿 | **完成** |
| **B** | `gen_dsp_fixture.py` + 夹具 + 黄金测试 | 三条口径全过 | **完成** |
| **C** | `RhythmLibraryStore.importChart` + Repository 生成入口 + 存储单测 | 既有 store 测试不回归；新测试通过 | **完成** |
| **D** | 选谱页按钮 + 参数 sheet + 进度 + 中英文案 | `assembleDebug` / `assembleRelease` 通过 | **完成** |
| **E** | 真机验收 + 文档回写（本文 §9.3 结果、§10 风险状态） | 单独标注真机结论 | **待做** |

A/B 可并行于 C，但 **D 必须等 A–C**。

---

## 13. 已确认的决策（2026-11-01）

| 决策点 | 结论 | 落点 |
|---|---|---|
| 默认难度 | **Easy** | `RhythmChartGenParams.difficulty` 默认 `EASY` |
| 车道策略 | **先与 Python 一致**（`BAND_ORDER`）—— 已在 PC 上用傅里叶谱试玩过 | `LaneStrategy` 默认值；sheet 不暴露 |
| 同难度重复生成 | **保留多份**，难度名带 `MM-dd HH:mm` 时间戳 | `RhythmSetupScreen.startGeneration` |
| 音频上限 | **12 分钟**，超限在解码前拒绝 | `RhythmRepository.MAX_ANALYSIS_DURATION_MS` |
| 生成完成后 | **弹对话框**问「立即游玩 / 稍后」 | `RhythmSetupScreen` 的 `lastGenerated` 对话框 |
| 生成参数界面 | 难度 / 弱音过滤 / 密度上限 + 高级（BPM、偏移）全部可见可调 | `RhythmGenerateSheet.kt` |

> 车道策略保持 `BAND_ORDER` 的同时，`LaneStrategy.LOW_OUTSIDE` 已实现但未接线。
> 想切换只需改 `RhythmChartGenParams.laneStrategy` 的默认值 —— 但那会同时让黄金夹具的
> Hard 档 lane 断言失效，必须同步调整测试。

---

## 14. 来源与许可

PixelPlayerOSS 为 GPL-3.0-or-later。本方案的算法来自公开方法
（cascade 的 STFT/whitening/谱通量配方、ISMIR 2014 的 onset 检测惯用做法），
参考实现是**作者本人**本地实验仓库 `RhythmChartLab`（同一作者，无第三方代码）。
Kotlin 侧全部重写，不复制 Python 源码行；新增文件沿用现有
`SPDX-License-Identifier: GPL-3.0-or-later / Original work, adapted in 2026 from the author's
local RhythmChartLab prototype` 头。

不引入任何第三方 DSP 库：FFT 自写（约 80 行 radix-2），避免许可证与体积问题。

---

## 15. 实施记录（2026-11-01）

### 15.1 落地文件

| 类型 | 文件 | 说明 |
|---|---|---|
| 新增 | `data/rhythm/RealFft.kt` | 实数 FFT：半长复数 FFT + 奇偶拆分，预计算 twiddle/位反转表 |
| 新增 | `data/rhythm/RhythmChartGenerator.kt` | 9 阶段管线，纯 Kotlin，支持进度回调与取消 |
| 新增 | `data/rhythm/RhythmChartGenParams.kt` | 参数、`DspDifficulty`、`LaneStrategy`、`DspDiagnostics`、`GeneratedChart` |
| 新增 | `presentation/rhythm/RhythmGenerateSheet.kt` | 参数面板 + 进度 + 取消 |
| 新增 | `gen_dsp_fixture.py` | 夹具生成器，`--check` 可校验夹具是否与参考实现一致 |
| 新增 | 测试 4 个 + 夹具 2 个 | 见 §5.1 |
| 修改 | `RhythmLibraryStore.kt` | 新增 `importChart`，抽出 `persist`；`importText` 的 id 来源未改 |
| 修改 | `RhythmRepository.kt` | 新增 `generateDsp` + `MAX_ANALYSIS_DURATION_MS` |
| 修改 | `RhythmPcmDecoder.kt` | 新增 `RhythmPcmAudio.toMonoFloat()` |
| 修改 | `RhythmSetupScreen.kt` | 生成按钮、sheet 状态、时间戳命名、完成对话框、来源徽标 |
| 修改 | `values/strings.xml`、`values-zh-rCN/strings.xml` | 各 30 条 |

未改动：`RhythmChart.kt`、`RhythmGameScreen.kt`、`RhythmPlaySession.kt`、`RhythmJudgement.kt`、
`RhythmAudioClock.kt`、导航图。**游戏侧确实零改动**，验证了 §2 的判断。

### 15.2 与方案的三处偏离

1. **`DspFft.kt` → `RealFft.kt`**：实现的是实数输入变换，命名需准确（见 §5.1 注）。
2. **黄金测试的 lane 门禁只覆盖 Hard 档**：原因是参考实现自身在同格竞争时不稳定
   （见 §9.2 实测）。这不是放宽标准 —— 时间/BPM/数量三项仍是全档硬门禁。
3. **`importChart` 没有 `replaceSameDifficulty` 参数**：用户要求保留多份，
   替换逻辑整个删掉了，而不是留一个恒为 false 的开关。

### 15.3 验证结果

| 项 | 结果 |
|---|---|
| `:app:testDebugUnitTest` | **994 个测试，0 失败**（新增 26 个：6 FFT + 12 生成器 + 2 黄金 + 4 存储 + 2 PCM 转换） |
| `:app:assembleRelease` | 成功，`pixelplayeross-arm64-v8a-0.4.3-pisces.1-release.apk`，38,016,480 字节 |
| 签名 | `apksigner verify` → `Verifies` + v2 scheme + `CN=pisces312` |
| `:app:lintDebug` | 4 个 error，全部是既有问题（`SerendipityContextCollector`、`HomeScreen`、`CarLyricTitleController`×2），本次未新增 |
| 黄金夹具 | 三档 BPM 与参考实现逐位一致；Hard 档 146/146 note 与 lane 完全一致 |

### 15.4 尚未验证（不得当作已验收）

- **真机耗时**：§4.4 的 6–15 s 仍是【估算】。模拟器跑 arm64 翻译层不能代表真机，必须用直板机实测。
- **真机听感**：生成谱面是否「踩得上点」，只有实际游玩能判断；PC 侧 F1 指标不等于手感。
- **长曲漂移**：§10 的 0.46 s/261 s 换算未在真机上复核，Easy 档对漂移最敏感（拍点间隔 500 ms）。
- **48 kHz 设备**：算法自适应 `hopMs`，但只在 44.1 kHz 夹具上对齐过。
- **取消路径**：单测覆盖了 progress 单调性，但没有覆盖「生成中途按取消」后的状态复位。
- **配置变化中断**：生成跑在 `rememberCoroutineScope` 上，选谱页没有锁竖屏，
  旋转屏幕会随 composition 一起取消生成。v1 接受（重来一次即可），未做 ViewModel 托管。

### 15.5 复现命令

```powershell
# 夹具（需要 RhythmChartLab 存在，或用 --chartgen 指定）
python gen_dsp_fixture.py
python gen_dsp_fixture.py --check      # 校验夹具是否已过期

# 只跑本次新增的测试
.\gradlew.bat :app:testDebugUnitTest --tests "com.lostf1sh.pixelplayeross.data.rhythm.*"
```
