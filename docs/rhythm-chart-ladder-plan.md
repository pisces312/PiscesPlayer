# 谱面层级化：唯一母谱 + 无限派生 + 每谱面独立设置

> 日期：2026-11-01（v2，按决策重写；同日实现完成）
> **v3 修订：2026-11-03 —— 派生只做选择、绝不移动时间戳，见 §17**
> 状态：**A–E 全部实现并通过构建**（单测 1030/1030、`:app:lintDebug` 无新增 error、
> `:app:assembleRelease` 已签名）；**真机未验收**。实现记录与实测数据见 §15，双键/三键见 §16
> 依据：`docs/rhythm-dsp-chart-generation-plan.md`（v1 已实现）、
> `app/src/main/java/com/lostf1sh/pixelplayeross/data/rhythm/RhythmChartGenerator.kt`（现管线）、
> `RhythmChartLab/rhythm-lab/fourier/fourier_chartgen.py`（参考实现）
> 结论等级：**【PC 实测】** = RhythmChartLab 已跑出的数字；**【本机复核】** = 本次会话重新执行；
> **【估算】** = 未实测的推算；**【计划】** = 尚未做的设计决定。

**已定决策（用户确认）**

| # | 决策 |
|---|---|
| 1 | 母谱不是"勾选项"，它就是分析结果本身；派生只产出 简单/标准/困难 三档，默认全选 |
| 2 | 每档设置开放：延迟补偿 + 下落时间（两个都是现成构造参数） |
| 3 | 侧车放兄弟目录 `settings/<chartId>.json` |
| 4 | **母谱唯一**：每首歌只有一个母谱，重新生成即覆盖 |
| 5 | **子谱无限**：每次派生都带时间戳、都是新条目，不设上限 |

---

## 1. 结论

模型从"一次生成一整梯"改成**两步**，因为母谱唯一之后，第二步根本不需要音频：

```
① 生成母谱（慢，需要 PCM）      解码 → 分析 → 全部 onset 成谱 → 覆盖旧母谱（每首歌一份）
② 派生档位（快，纯函数）        母谱 → 简单/标准/困难，带时间戳，想要几份要几份
```

三条好处，都不是"效果好一点"级别的：

1. **派生不再耗时**。分析（解码 + STFT + 峰值 + BPM）只在生成母谱时跑一次；派生是 O(note) 的纯函数，2012 条音符在手机上**【估算】**< 20 ms，可以做成"点一下立刻出"。
2. **调参不用重算**。想换个车道策略、想再要一版困难档，直接派生，不动音频。这是"母谱唯一"带来的最大红利。
3. **原始档不再重复出现**。旧方案里"原始"既是母谱又是列表条目，容易混淆；现在母谱就是母谱，派生列表里只有三档，不会出现"原始 · 20:15"和"原始 · 20:31"两个几乎一样的条目。

**必须正视的两个陷阱**：

1. **母谱文件是可变的**，这打破了 `charts/` 目录"内容寻址、文件不可变"的不变式。必须显式隔离：母谱按 song 命名、固定 id、允许覆盖（§6.2），子谱仍走内容哈希。
2. **现有夹具证明不了分层**。`dsp-beats-120bpm.wav` 是 kick 每拍 + hihat 半拍，参考实现在它上面检出 **146 个 onset，而 1/4 网格也正好 146 格**（【本机复核】）——母谱与困难档数量相同。要验证 `raw > hard > normal > easy` 的严格包含，**必须补一个含 32 分音符的密集夹具**（§11.3）。

---

## 2. 现状：三条硬约束

### 2.1 一次生成只能产出一档

`RhythmChartGenerator.generate()` 是单入口：参数里的 `difficulty.gridDiv` 同时决定网格与标签，一次调用只返回一个 `GeneratedChart`。

但**管线第 1–7 步与难度完全无关**：

| 阶段 | 内容 | 依赖 `gridDiv` |
|---|---|---|
| 1 | STFT 2048/512 → 8 个对数频带峰值 | 否 |
| 2 | running-max 白化（窗口 32 帧） | 否 |
| 3 | 半波整流谱通量 | 否 |
| 4 | 局部最大 + median/1.5·MAD 峰值挑选 | 否 |
| 5 | 自相关 BPM + 半速/倍速仲裁 | 否 |
| 6 | 梳状相位 → 全局拍偏移 | 否 |
| 7 | 可选约束（弱音过滤 / 局部密度） | 否 |
| **8** | **按 `beatMs / gridDiv` 吸附，一格一音符** | **是** |
| 9 | 车道分配（取该帧最强频带）+ 相邻同轨避让 | 仅经由第 8 步的存活集合 |

也就是说：**第 1–7 步是"分析"，第 8–9 步是"渲染"**。现在把两者钉在一个函数里，才被迫为每档重跑前七步。

### 2.2 谱面 id = 内容哈希，不能随便改

`RhythmLibraryStore` 两条路径的 id 来源已经分开并有测试守着：

- `importText(osu)` → id 来自 **osu 原文**哈希（改了会让所有已导入谱面换 id、最高分作废）
- `importChart(dsp)` → id 来自**序列化后谱面**哈希

层级化后，子谱继续走内容哈希（时间戳保证内容不同）；**母谱必须成为唯一例外**——按 song 定 id、允许覆盖，理由见 §6.2。

### 2.3 播放侧已有两个天然挂点

```kotlin
// RhythmAudioClock.kt:26
class RhythmAudioClock(private val audio: RhythmPcmAudio, override var offsetMs: Double = 0.0)
override fun nowMs(): Double = readFrame() / audio.sampleRate * 1000.0 + offsetMs

// RhythmPlaySession.kt:12
class RhythmPlaySession(chart, clock, val approachMs: Double = 1500.0, config)
```

`offsetMs` 是**延迟补偿**（正数 = 时钟超前 = 音符相对听感提前到达），`approachMs` 是**下落时间**。两个都是构造参数，**引擎一行不用改**，只差把每谱面设置读出来喂进去。

注意与生成期 `extraOffsetMs` 的区别：

| 参数 | 阶段 | 作用 |
|---|---|---|
| `RhythmChartGenParams.extraOffsetMs` | 生成母谱 | 改写 `beatOffsetMs`，**改变网格相位**，进而改变哪些 onset 被吸附到一起 |
| `RhythmChartSettings.delayMs` | 播放 | 只平移时钟，**不碰谱面内容**，随时可改 |

两者语义不同，都要保留：前者是"这歌的拍点没对齐"的补救，后者是"我的耳机有延迟"的补偿。

---

## 3. 目标与不做什么

**做**

1. 母谱：每首歌唯一，一次分析产出「全部 onset」的可游玩谱面，重新生成即覆盖。
2. 派生：纯函数 `derive(master, difficulty, laneStrategy)`，不带时间戳的输入、带时间戳的输出，次数不限。
3. 每谱面侧车设置（延迟补偿、下落时间），与谱面文件同 base name，写入不触碰索引。
4. 列表分两段：母谱卡（单张）+ 派生列表（无上限）。

**不做（v1 明确排除）**

- 不做逐档独立 BPM / 独立拍偏移：三档共享母谱的 BPM 与相位，这是分层的**目的**而非限制。
- 不做"派生谱面反向影响母谱"或"编辑母谱音符"。
- 不改 `RhythmJudgement`、`OsuManiaParser`、游戏渲染逻辑。
- 不动 `library.json` 的 `schemaVersion`（只加带默认值的字段，旧文件照常解析）。

---

## 4. 架构

### 4.1 管线切分

```kotlin
// 第 1–7 步：与难度无关，重活全在这里
internal class DspAnalysis(
    val onsetTimesMs: DoubleArray,   // 升序，未吸附
    val onsetStrength: FloatArray,   // 已按 p95 归一化
    val onsetBands: IntArray,        // 每个 onset 的主导频带（0..7）
    val beatMs: Double,
    val beatOffsetMs: Double,
    val durationMs: Int,
    val diagnostics: DspDiagnostics,
)

internal object RhythmChartGenerator {
    fun analyze(pcmMono, sampleRate, durationMs, params, onProgress, isCancelled): DspAnalysis
    fun renderMaster(analysis, laneStrategy): RhythmChart
    fun render(analysis, difficulty, laneStrategy): RhythmChart   // 第 8–9 步

    // 兼容旧调用：单档 = 先建母谱再派生
    fun generate(pcm, sr, duration, params, ...): GeneratedChart
}
```

**顺带的内存整理**：现在第 9 步为了取主导频带，一直持有 `band` 矩阵（`frameCount × 8` 个 float，5 分钟曲 ≈ 0.8 MB）。把主导频带在**峰值挑选时一并算好**存进 `onsetBands`（每个 onset 一个 int，2012 个 = 8 KB），`band` 矩阵就能在第 4 步后立即释放。这不改变任何数值——第 9 步读的本来就是"该 onset 所在帧"的那一列。省下的绝对量不大，真正作用是**让"渲染"变成只依赖 `DspAnalysis` 的纯函数**。

### 4.2 母谱：唯一、可覆盖

**母谱 = 检出的全部 onset，一个不合并**，时间取整到毫秒，车道按主导频带分配。

- 它就是**能生成的最密谱面**：任何更细的网格都不会增加音符（格内取一）。
- 它**可玩**，只是很硬。参考曲 261.9 s 检出 **2012 个 onset ≈ 7.7 NPS**【PC 实测】。
- 它**每首歌只有一份**：`chartId` 由 songId 推出（§6.2），重新生成写同一个文件。
- 它是**派生的唯一输入**，所以内容里必须留强度与频带（§4.5）。

### 4.3 派生算法（v2 已实现；v3 起不再是 DSP 派生路径）

> **v3 修订（2026-11-03）**：本节"格内取最强"会把音符搬到网格上——真机诊断证明这正是
> "一会准、一会快"的来源（`rhythm-clock-drift-diagnostics.md` §6）。保留此节作为 v2 行为记录
> 与 osu/旧谱兼容路径；DSP 新算法见 §17。

```kotlin
/** 从母谱抽出某一档：格内取最强，按频带重新排车道，相邻同轨避让。纯函数，无 PCM。 */
internal fun derive(
    master: RhythmChart,
    difficulty: DspDifficulty,
    laneStrategy: LaneStrategy = LaneStrategy.BAND_ORDER,
): RhythmChart {
    val timing = master.timing.first()
    val grid = timing.beatMs / difficulty.gridDiv
    val offset = timing.timeMs.toDouble()

    // 一格一音符。母谱已按时间升序，用严格 > 比较 ⇒ 平手时保留更早的那个，
    // 与现有生成器第 8 步的 tie-break 完全一致。
    val best = HashMap<Long, RhythmNote>(master.notes.size)
    for (note in master.notes) {
        val cell = Math.round((note.timeMs - offset) / grid)
        val previous = best[cell]
        if (previous == null || note.strength > previous.strength) best[cell] = note
    }

    // 母谱的"相邻同轨避让"是按全部 onset 做的；抽稀后会出现新的相邻同轨，必须重做。
    val kept = best.values.sortedBy { it.timeMs }
    var lastLane = -1
    val notes = ArrayList<RhythmNote>(kept.size)
    for (note in kept) {
        var lane = laneOf(note, laneStrategy)
        if (lane == lastLane) lane = (lane + 1) % RhythmChartGenerator.LANE_COUNT
        notes += RhythmNote(notes.size, note.timeMs, lane, note.strength, note.band)
        lastLane = lane
    }
    return master.copy(difficultyName = difficulty.label, notes = notes)
}
```

四个细节，都是踩过的坑：

1. **格键用格序号而不是"吸附后时间 ×10"**。数值上等价（`offset + cell*grid`），但格序号不会因为浮点尾数把一个音符算到隔壁格。
2. **`>` 而不是 `>=`**：平手保留更早者，和现在一致。改成 `>=` 会让整条谱面在等强拍上系统性偏移半格——在 261 秒里能累积到肉眼可见。
3. **必须重做车道避让**：母谱里 0/1/2 三个 onset 可能是 `lane 1, 2, 1`（避让后），抽稀到 1/1 网格只剩第 0 和第 2 个 ⇒ 相邻同轨。不重做就会出现"两个连着的键都在最左"。
4. **车道从频带重算，不直接抄母谱的 lane**：这样同一份母谱可以用不同 `laneStrategy` 派生出两版做手感对比（`band < 0` 的旧谱面才回退到 `note.lane`）。

### 4.4 等价性：派生不等于降质

有人会怀疑"从母谱抽稀"和"直接从 onset 生成"结果不同。理论上只差一处：母谱时间和 onset 时间是同一组数（母谱存的是 `Math.round(onsetMs)`），**唯一差异是先把 double 取整到 ms 再算格序号**。

- 只有 onset 时间落在格边界 ±0.5 ms 内时，取整才会把格序号改一位，进而可能改变"谁更强"的判定。
- 数量级：2048 点 STFT、hop 512，1/4 格宽最小 75 ms（200 BPM）；落进这个 1 ms 陷阱的概率约 0.5/75 ≈ 0.7%，且只有当两个 onset 恰好抢同一格才影响结果。

所以验收口径是**「派生结果 == 直生成结果，逐条时间差 ≤ 1 ms、音符数相等」**，而不是逐位相等。这条断言放在 `RhythmChartDeriverTest` 里跑黄金夹具（§11.2）。

### 4.5 母谱必须留强度与频带

`RhythmChart` 现在只有 `(id, timeMs, lane)`。派生需要两样东西，而它们在母谱落盘后都无法从音频重算（要 PCM）：

| 字段 | 用途 | 方案 |
|---|---|---|
| `strength: Float` | "格内留谁" | 放 `RhythmNote`，默认 0 |
| `band: Int` (-1 = 未知) | 重算车道（换策略、避让） | 放 `RhythmNote`，默认 -1 |

代价：2012 条 note 的 JSON 约 +60 KB（母谱一份）。相对"能随时换车道策略"这个能力，值得。

平行数组方案（`RhythmChart.strengths: List<Float>`）更省字节，但**下标对齐脆弱**——一旦有人重排 notes 就静默错位，不如放 note 里。

`RhythmChart.schemaVersion` 不升版：两个新字段都有默认值，旧 JSON 与 osu 导入路径零改动。

---

## 5. 数据模型改动

```kotlin
// RhythmChart.kt
@Serializable
data class RhythmNote(
    val id: Int,
    val timeMs: Int,
    val lane: Int,
    /** 峰值强度，仅 DSP 谱面填充；派生时用于"格内留最强"。 */
    val strength: Float = 0f,
    /** 主导频带 0..7，仅 DSP 谱面填充；派生时用于按策略重算车道。-1 = 未知。 */
    val band: Int = -1,
) { /* 现有 require 不变 */ }

@Serializable
data class RhythmChart(/* 现有字段不变 */)   // schemaVersion 仍为 1

/** 索引里的层级信息；旧条目为 null 时按"未分组"显示。 */
@Serializable
data class RhythmChartMeta(
    /* 现有字段 */,
    var bestScore: Int = 0,
    val difficultyKey: String? = null,        // "raw" | "easy" | "normal" | "hard"
    val parentChartId: String? = null,        // 子谱指向母谱；母谱自身为 null
    /** 派生时母谱的生成时刻，用于判断"母谱已被重新生成"。母谱自身为 null。 */
    val masterGeneratedAtMs: Long? = null,
)

// 新文件 RhythmChartSettings.kt
@Serializable
data class RhythmChartSettings(
    val schemaVersion: Int = 1,
    /** 延迟补偿，毫秒；正数 = 时钟超前。范围 -300..+300。 */
    val delayMs: Int = 0,
    /** 音符下落时间，毫秒；范围 700..3000。 */
    val approachMs: Int = 1500,
    val updatedAtMs: Long = 0,
)
```

兼容性三条，都有测试兜底：

1. `RhythmNote` 新字段有默认值 ⇒ 旧 `charts/*.json` 与 osu 导入路径零改动。
2. `RhythmChartMeta` 新字段有默认值 ⇒ 旧 `library.json` 照常解析（`ignoreUnknownKeys` 已开，反方向也安全）。
3. `RhythmChartSettings` 缺失 / 损坏 ⇒ 返回默认值，**绝不能因为设置读不出来就让谱面打不开**。

**没有 `groupId`，也没有 id 盐**：层级关系用 `parentChartId` 单向表达就够了，而且子谱带秒级时间戳，内容天然不同、id 天然不同（§6.3）。

---

## 6. 存储

### 6.1 目录

```
filesDir/rhythm/
├── library.json                 索引：songId → [meta]
├── charts/
│   ├── raw-<songHash>.json      母谱（每首歌一份，可变、可覆盖）   ← 唯一例外
│   └── <contentHash>.json       子谱与 osu 谱面（内容寻址、不可变）
└── settings/<chartId>.json      每谱面设置（可变、独立于内容）     ← 新增
```

`settings/<chartId>.json` 与 `charts/<chartId>.json` **base name 相同**，只是兄弟目录。

### 6.2 母谱：唯一性的实现与代价

```kotlin
// RhythmLibraryStore
fun saveMaster(songId: String, chart: RhythmChart, nowMs: Long): RhythmChartMeta {
    val chartId = masterChartId(songId)          // "raw-" + sha256(songId).take(12)
    writeAtomic(File(chartsDir, "$chartId.json"), json.encodeToString(chart))  // 总是覆盖
    // 索引里原地替换：noteCount / averageNps / importedAtMs 更新，bestScore 归零
}
```

三件事必须写清楚，否则将来会有人踩：

| 问题 | 处置 | 理由 |
|---|---|---|
| 覆盖破坏"文件不可变" | 母谱是**唯一例外**，id 按 song 派生，不含内容 | 内容哈希做不到"唯一且可覆盖"；若每次新 id，就会堆出无数份几乎相同的母谱 |
| 跨歌冲突 | id 含 songId 哈希（48 bit） | `charts/` 是全局目录，不能只用 `"raw"` |
| 旧最高分 | **覆盖时 `bestScore` 归零** | 音符都换了，旧分数不可比；保留会给出虚假的"打过了"信号 |

`importChart`（子谱/osu 辅助）保持"同内容同 id、文件已存在就不重写"的语义不变。

### 6.3 子谱：时间戳 + 无限

- 子谱 `difficultyName = "简单 · 11-01 20:15:32"`，**秒级**（v1 是分钟级）。
- 因为名字进内容、内容进哈希，**同一档派生两次必然是两个 id**，天然不需要盐、也不会互相误删。
- 派生是纯函数、无 IO 等待，用户连点三次就是三条记录——这就是"允许无限生成"。

### 6.4 母谱被重新生成之后

| 对象 | 行为 |
|---|---|
| 旧子谱 | **保留、仍可玩**（内容自包含），但 `masterGeneratedAtMs != 母谱.importedAtMs` ⇒ 列表显示「母谱已更新」小徽标 |
| 母谱最高分 | 归零（内容变了） |
| 子谱最高分 | 不变（各自内容没变） |

这条徽标不是装饰：它告诉用户"这条谱是旧母谱派生的，BPM 可能和现在这份不一样"，避免拿两种拍点混着打还以为自己退步了。

### 6.5 写入与删除

| 动作 | 行为 |
|---|---|
| 拖延迟滑杆 | **只在 `onValueChangeFinished` / sheet 关闭时写一次**，不逐帧写 |
| 删子谱 | 删 meta → 删 `settings/<id>.json` → 若 `charts/<id>.json` 已无任何 song 引用则删内容 |
| 删母谱 | 允许，子谱保留（失去派生源）；派生入口改为引导先生成母谱 |
| 删整首 | 逐条走上面流程 |

`delete()` 现在只处理 content；新增一行删 settings 即可，注意**设置文件删失败不能阻断删除**（否则用户会被一个损坏的侧车卡住）。

---

## 7. 参数矩阵

| 对象 | `gridDiv` | 每拍音符 | 参考曲音符数【PC 实测】 | NPS | 数量 |
|---|---:|---|---:|---:|---|
| 母谱 raw | —（不吸附） | 全部 onset | **2012** | 7.7 | **每首歌 1 份** |
| 困难 hard | 4 | 1/4 | 1451 | 5.5 | 无限 |
| 标准 normal | 2 | 1/2 | 794 | 3.0 | 无限 |
| 简单 easy | 1 | 1/1 | 406 | 1.6 | 无限 |

> **v3 修订（2026-11-03）**：上表是 v2 的 `gridDiv` 矩阵。v3 起难度由「每秒最多音符数」
> 定义（§17.2），`gridDiv` 只保留给 v2 旧谱与 osu 导入；音符时间不再吸附到任何网格。

**分析级参数（只影响母谱）**：`bpmOverride`、`extraOffsetMs`、`minRmsPercentile`、`maxLocalNps`。

- `minRmsPercentile` 需要 PCM 的 RMS 包络 ⇒ 无法从母谱派生，只能在建母谱时用。
- `maxLocalNps` 同理放在母谱层：逐档设密度上限在分层体系下没有意义（档位本身决定密度），而"先筛掉过密事件、再抽稀"语义清晰。
- 两者默认仍关闭（v1 实测开启会降质，见前一份文档 §9.3）。

**派生级参数（只影响子谱）**：`difficulty`（三选多）、`laneStrategy`（`BAND_ORDER` / `LOW_OUTSIDE`）。

`LOW_OUTSIDE` 从"藏起来"变成"开放"——它本来就是为手感设计的（低频走外侧），有了母谱之后对比成本降到一次点击。

---

## 8. 性能与内存

| 场景 | v1（现状） | 本方案 |
|---|---|---|
| 生成三档 | 解码 3 次 + STFT 3 次 + 渲染 3 次 | 解码 1 次 + STFT 1 次（母谱） |
| 再要一版困难档 | 全套重跑 | **纯函数，< 20 ms**【估算】 |
| 换车道策略对比 | 全套重跑 | 纯函数，< 20 ms【估算】 |

- 参考曲解码 + 分析 **【本机复核】**：PC 上 20 s 音频 0.671 s（89× 实时）；261 s 曲目推算约 3 s。
- Android 中端机 **【估算】** 母谱 6–15 s；派生可做成同步完成，不需要进度条。
- 峰值内存基本持平（`band` 矩阵 5 min ≈ 0.8 MB 提前释放，母谱 2012 条 note ≈ 48 KB）；真正的收益是**解码与 STFT 不再重复**。

---

## 9. UI

### 9.1 选谱页：两段式

```
SongHeader（歌名 / 歌手）
[   导入 .osu   ]

┌ 母谱 ──────────────────────────────┐
│ 原始 · 2012 音符 · 7.7 NPS          │
│ BPM 90.84（检出 182.41，已按 ×0.5 修正）│
│ 生成于 11-01 20:15                  │
│ [▶ 游玩]   [派生谱面]   [重新生成] ⚙ │
└────────────────────────────────────┘

┌ 派生谱面 ──────────────────────────┐
│ 简单 · 11-01 20:15:32 ·  406 音符 ★1234 ⚙ [▶] │
│ 困难 · 11-01 20:15:32 · 1451 音符      ⚙ [▶] │
│ 标准 · 10-31 22:04:10 ·  794 音符      ⚙ [▶] │
└────────────────────────────────────┘

┌ 未分组（旧版本生成的谱面）─────────┐
│ DSP 简单 · 10-31 22:04 · 412 音符   ⚙ [▶] │
│ osu 4K [Normal] · 10-30 09:12       ⚙ [▶] │
└────────────────────────────────────┘
```

无母谱时，母谱卡位置显示一个引导块：

```
┌ 还没有母谱 ────────────────────────┐
│ 生成一次分析结果（全部检出的音符），  │
│ 之后所有难度都从它派生，不用再等。    │
│ [ 生成母谱 ]                        │
└────────────────────────────────────┘
```

- 母谱卡的 BPM 直接从 `chart.timing.first().beatMs` 反算，**不需要在索引里存诊断信息**。
- 派生列表按 `importedAtMs` 倒序（新的在上）；`masterGeneratedAtMs` 不匹配时行尾加「母谱已更新」小徽标。
- 延迟非 0 时行内显示 `+18 ms`，**一眼看出哪个谱面被单独调过**。

### 9.2 生成母谱 sheet

```
生成母谱
Song Title
────────────────────────────────
分析参数
  弱音过滤   [关闭] [中] [强]
  密度上限   [关闭] [每秒 8] [每秒 5]

▸ 高级
   BPM      [ 自动 ]
   对齐偏移 [ 0 ms ]
────────────────────────────────
[取消]                      [开始]
```

生成中：进度条 + 百分比 + 取消。完成后：
- 有旧母谱 ⇒ 先弹确认「重新生成会覆盖当前母谱，其最高分将清零；已派生的谱面保留但会标记为旧母谱」。
- 完成后弹对话「已生成母谱 2012 音符 · BPM 90.84」+ [派生谱面] / [稍后]。

### 9.3 派生 sheet

```
派生谱面
母谱：2012 音符 · BPM 90.84 · 11-01 20:15
────────────────────────────────
难度      [简单] [标准] [困难]        ← 多选，默认三个全选
          预计：406 / 794 / 1451 音符

车道策略  [与 PC 一致] [低频走外侧]
         低频走外侧把贝斯放到最外侧，中间两条留给主旋律
────────────────────────────────
[取消]                        [派生]
```

「预计音符数」是**实时算的**——派生是纯函数，勾选变化时直接跑一遍拿 `noteCount`，用户点之前就知道要生成什么。这一点只有"母谱唯一"才做得到（不需要解码）。

### 9.4 谱面设置 sheet

```
谱面设置 · 简单 · 11-01 20:15:32
────────────────────────────────
延迟补偿               +18 ms
[-] ────────●──────── [+]      [归零]
    -300 ms                       +300 ms
    听证时听到的拍比看到晚，就往正调

下落时间               1500 ms
──────●──────────────
    700 ms                        3000 ms

（改动即时保存，返回即生效）
```

- 滑杆 `valueRange = -300f..300f`，`steps = 59`（10 ms 一档）；两侧 `-1/+1` 微调按钮供精调。
- 只写侧车，**不重写索引、不改谱面 id、不清最高分**。
- 母谱与子谱都能开这个面板（母谱的延迟同样只影响播放）。

### 9.5 游戏页接线（改两行）

```kotlin
// RhythmGameScreen.kt 的 LaunchedEffect 内
val settings = repository.loadSettings(chartId)
val clock = RhythmAudioClock(pcm, offsetMs = settings.delayMs.toDouble())
session = RhythmPlaySession(chart = chart, clock = clock, approachMs = settings.approachMs.toDouble())
```

`settle()` 已把 `offsetMs` 带进 `RhythmSessionResult`，结果页可顺带显示"延迟补偿 +18 ms"，方便对照。

---

## 10. 兼容与迁移

| 场景 | 行为 |
|---|---|
| 旧 `charts/*.json`（无 strength/band） | 取默认 0 / -1；作为普通谱面可玩；`band < 0` 时车牌沿用原 lane |
| 旧 `library.json`（无 difficultyKey/parent/masterGeneratedAt） | 三字段为 null，归入「未分组」 |
| 旧谱面的最高分 | 完全不受影响（id 未变） |
| `settings/` 目录不存在 | 读设置返回默认值；首次保存时创建 |
| 升级后第一次生成母谱 | 新建 `raw-<hash>.json`，与旧 DSP 谱面并存，不迁移、不改动 |

**不需要任何迁移代码**。这是刻意的：层级只对"本版本之后生成的批次"有意义，历史数据按缺省值降级显示即可。

---

## 11. 测试

### 11.1 派生器（纯 JVM，新增 `RhythmChartDeriverTest`）

| 用例 | 断言 |
|---|---|
| 档位单调 | `raw.size >= hard >= normal >= easy`，各档时间集合是母谱时间集合的子集 |
| 格约束 | 每个音符都落在 `offset + k*grid`（±1 ms），同一格最多一个 |
| 车道避让 | 相邻音符 lane 不相等 |
| 换策略 | 同一母谱用两种 `laneStrategy` 派生 ⇒ 音符数相同、车道分布不同 |
| 空档保护 | 母谱只有 1 个音符时，三档都产出 1 个而非抛异常 |
| 幂等 | 同一母谱 + 同参数派生两次结果完全相等 |

### 11.2 与直生成的一致性（扩展 `RhythmChartGeneratorGoldenTest`）

复用现有夹具，三种口径：

1. **既有口径不变**：派生出的 easy/normal/hard 对 Python 期望值，±15 ms 匹配率 ≥ 95%、反向 ≥ 95%。
2. **新增**：`derive(generate().master, d)` 与 `generate(params + d)` 逐条时间差 ≤ 1 ms、音符数相等（§4.4）。
3. **新增**：母谱与各档 `timing` 完全相同（同一母谱派生必须共享 BPM 与相位）。

### 11.3 密集夹具（必须新增）

现有夹具 raw = hard = 146 条，**无法验证分层**。新增 `dsp-dense-120bpm.wav`：

- 20 s / 44.1 kHz / mono / 120 BPM，kick 每拍 + **32 分音符 hihat**（62.5 ms 间隔，高于峰值挑选 50 ms 的局部半径，能存活）。
- 期望 `raw ≈ 8/拍`、`hard ≈ 4/拍`，即母谱约为困难档两倍。
- `gen_dsp_fixture.py` 需同时输出 `RAW` 段（参考脚本取 `pick_peaks` 的 idx 直接成谱），并保留 `--check` 自校验。

### 11.4 存储（扩展 `RhythmLibraryStoreTest`）

| 用例 | 断言 |
|---|---|
| 母谱唯一 | 同一 song 生成两次 ⇒ 同一 chartId、同一文件、meta 只有一条 |
| 跨歌隔离 | 两首不同 song ⇒ 两个不同母谱 id |
| 覆盖清分 | 先 `recordScore` 再 `saveMaster` ⇒ `bestScore == 0`，noteCount 已更新 |
| 子谱无限 | 同档派生两次（时间戳不同）⇒ 两条 meta、两个文件 |
| 母谱标记 | 覆盖母谱后，旧子谱 `masterGeneratedAtMs != 新母谱.importedAtMs` |
| 删母谱 | 子谱仍在、仍可读，母谱 meta 消失 |
| 设置默认值 | 文件不存在 ⇒ `delayMs = 0`, `approachMs = 1500` |
| 设置往返 | 写入后读出一致；损坏 JSON ⇒ 返回默认值且不抛 |
| 删除清理 | 删谱面同时删侧车；侧车删失败不影响谱面删除 |
| 旧索引兼容 | 无新字段的 library.json 能解析；osu id 仍等于 osu 原文哈希 |

### 11.5 手工验收（真机）

1. 首次生成母谱 → 耗时；再次派生三档 → **应当没有进度条、立刻出现**。
2. 重新生成母谱 → 弹确认、覆盖后最高分归零、旧子谱出现「母谱已更新」。
3. 同一档连点两次派生 → 两条记录（时间戳差 1 秒）。
4. 改某一行延迟 → **只有该行出现 ±ms 徽标**；进游戏听感方向正确（正数 = 音符更早到）。
5. 删母谱 → 子谱仍能打开游玩；派生入口提示先生成母谱。
6. 旧谱面（升级前生成的）仍能打开、仍保留最高分。

---

## 12. 风险

| 风险 | 等级 | 处置 |
|---|---|---|
| 母谱 7.7 NPS 在手机上几乎不可玩 | 中 | 定位为"派生源 + 参考"，派生默认只选三档；母谱卡文案说明 |
| 母谱可覆盖破坏内容寻址不变式 | 中 | 只允许 `saveMaster` 走覆盖路径，id 按 song；子谱仍走 `importChart`，两条路径各自有测试 |
| 派生与原路径 ≤1 ms 差异 | 低 | 门禁 1 ms + 数量相等，不做逐位比较 |
| 密集夹具仍需人工确认合理性 | 中 | `--check` 比对参考实现；raw≈2×hard 作为显式断言 |
| 覆盖后旧子谱"看起来一样"其实拍点变了 | 中 | `masterGeneratedAtMs` 徽标 + 设置面板显示母谱时间 |
| 侧车写入频率 | 低 | 只在拖动结束/关闭时写 |
| 秒级时间戳仍可能撞（同秒连点） | 低 | 撞了就命中已有内容、幂等返回同一条；可接受 |

---

## 13. 实施顺序

| 阶段 | 内容 | 验收 |
|---|---|---|
| **A** | `RhythmNote.strength/band` + `RhythmChartDeriver` + 单测（不动 UI/存储） | 11.1 全绿 |
| **B** | `RhythmChartGenerator` 切 analyze/render + `onsetBands` + 密集夹具 + 黄金扩展 | 11.2 / 11.3 全过，既有 994 个测试不回归 |
| **C** | `saveMaster`（唯一/覆盖/清分）+ `RhythmChartSettings` 侧车 + meta 三字段 + store 单测 | 11.4 全过 |
| **D** | Repository（`generateMaster` / `deriveCharts` / `loadSettings` / `saveSettings`）+ 母谱卡 + 生成 sheet + 派生 sheet + 列表 + 设置 sheet + 中英文案 | `assembleDebug` / `assembleRelease` 通过 |
| **E** | 游戏页接线 + 真机验收 + 文档回写 | 11.5 全过 |

A→B 必须串行；C 可与 A/B 并行；D 依赖 A–C；E 依赖 D。

**改动文件清单**

新增：
```
data/rhythm/RhythmChartDeriver.kt
data/rhythm/RhythmChartSettings.kt
presentation/rhythm/RhythmDeriveSheet.kt          (派生：难度多选 + 车道策略 + 预计音符数)
presentation/rhythm/RhythmChartSettingsSheet.kt   (每谱面：延迟 + 下落时间)
app/src/test/java/.../data/rhythm/RhythmChartDeriverTest.kt
app/src/test/resources/rhythm/dsp-dense-120bpm.wav + .expected.json
```

修改：
```
data/rhythm/RhythmChart.kt            (strength / band / meta 三字段)
data/rhythm/RhythmChartGenerator.kt   (analyze / render / renderMaster / onsetBands)
data/rhythm/RhythmLibraryStore.kt     (saveMaster / 侧车读写 / 删除清理)
data/rhythm/RhythmRepository.kt       (generateMaster / deriveCharts / loadSettings / saveSettings)
data/rhythm/RhythmChartGenParams.kt   (派生参数：难度集合 + 车道策略)
presentation/rhythm/RhythmSetupScreen.kt     (母谱卡 / 派生列表 / 未分组 / 两个 sheet)
presentation/rhythm/RhythmGameScreen.kt      (读设置 → clock / session)
presentation/rhythm/RhythmGenerateSheet.kt   (改成"生成母谱"：去掉难度选择，加覆盖确认)
app/src/main/res/values{,-zh-rCN}/strings.xml
gen_dsp_fixture.py                    (RAW 段 + 密集夹具)
```

不动：`RhythmJudgement.kt`、`OsuManiaParser.kt`、`RhythmPlaySession.kt`（仅传参）、`RhythmAudioClock.kt`。

---

## 14. 待确认项（已定）

| # | 问题 | 结论 |
|---|---|---|
| 1 | 重新生成母谱时，是否顺手重派生一次？ | **不自动**。完成后只弹「派生谱面」入口。自动重派生会静默增加条目，容易让人以为谱面"自己变多了" |
| 2 | 派生 sheet 里的「车道策略」是否现在就开？ | **开**。母谱已存频带，成本为零，默认「与 PC 一致」 |
| 3 | 母谱删除是否允许？ | **允许**。子谱保留，派生入口改为引导重建母谱 |

---

## 15. 实施记录（2026-11-01）

### 15.1 落地文件

| 类型 | 文件 | 说明 |
|---|---|---|
| 新增 | `data/rhythm/RhythmChartDeriver.kt` | 纯函数 `derive(master, difficulty, laneStrategy)`，80 行 |
| 新增 | `data/rhythm/RhythmChartSettings.kt` | `delayMs` / `approachMs` / `updatedAtMs` / `clamped()` |
| 新增 | `presentation/rhythm/RhythmDeriveSheet.kt` | 难度多选 + 实时预计音符数 + 车道策略 |
| 新增 | `presentation/rhythm/RhythmChartSettingsSheet.kt` | 延迟滑杆（±1 微调 + 归零）与下落时间 |
| 新增 | `RhythmChartDeriverTest`（13 例）、`dsp-dense-120bpm.wav/.expected.json` | 见 §11.1 / §11.3 |
| 修改 | `data/rhythm/RhythmChartGenerator.kt` | 切 `analyze` / `renderMaster` / `render` / `generate`；`onsetBands` 在峰值挑选时一并算出 |
| 修改 | `RhythmLibraryStore.kt` | `saveMaster` / `masterChartId` / `settingsDir` / `readSettings` / `writeSettings` / 删除清侧车 |
| 修改 | `RhythmRepository.kt` | `generateMaster` / `deriveCharts` / `previewDerivedCounts` / `loadSettings` / `saveSettings` / `settingsFor` |
| 修改 | `RhythmSetupScreen.kt` | 母谱卡 / 缺母谱引导 / 派生列表（旧母谱徽标 + 延迟徽标）/ 未分组 / 两个 sheet |
| 修改 | `RhythmGenerateSheet.kt` | 去掉难度选择，改为「生成母谱」+ 覆盖警告 |
| 修改 | `RhythmGameScreen.kt` | 两行接线（§9.5） |
| 修改 | `values{,-zh-rCN}/strings.xml` | 各 +30 条 |
| 修改 | `gen_dsp_fixture.py` | 双夹具 + `raw` 段 + `--check` |

### 15.2 与方案的三处偏离（都是被测试逼出来的）

1. **派生必须写格点时间，不能保留母谱时间**。§4.3 的伪代码本来是对的，但首版实现
   `RhythmNote(timeMs = note.timeMs, …)`。`everyDerivedNoteSitsOnTheGrid` 立刻报
   `EASY: 299 off grid (520.0)` —— 抽稀后的谱面如果留着原始 onset 时间，就根本不是量化谱。
   现在重算 `Math.round(offset + cell * grid)`。
2. **`render()` 的相位偏移必须先取整**。方案 §4.4 预测派生与直生成「≤1 ms 差异」，
   实测却发现 Easy 档 **41 vs 40 整整差一个音符**。根因不是浮点噪声，而是两路用了不同的
   相位：直生成用 `analysis.beatOffsetMs`（如 23.9888），派生用 `timing.timeMs`
   （`Math.round` 之后的 24）——0.01 ms 的差在格边界上把 `Math.round` 翻了一位。
   修法：`render()` 也改用 `Math.round(analysis.beatOffsetMs).toDouble()`，即"真正落进谱面的那个值"。
   **副作用是好的**：现在 `derived == direct` 是逐条相等，而不是 ≤1 ms。
3. **`render()` 的越界过滤要放在分格之前**。末尾一个超出曲长的 onset 可能赢下某一格，
   随后整格被丢弃，导致少一个音符。派生器一开始就有这条，直生成没有——现在两边一致。

### 15.3 验证结果

| 项 | 结果 |
|---|---|
| `:app:testDebugUnitTest` | **1022 个测试，0 失败**（节奏相关：Deriver 13 / Golden 4 / Generator 15 / Store 20） |
| `:app:lintDebug` | 4 个 error，与 v1 基线完全相同（`SerendipityContextCollector`、`HomeScreen`、`CarLyricTitleController`×2），本次未新增 |
| `:app:assembleRelease` | 成功，`pixelplayeross-arm64-v8a-0.4.3-pisces.1-release.apk`，38,053,944 字节（v1 为 38,016,480，+37 KB） |
| 签名 | `apksigner verify --print-certs` → `CN=pisces312, O=pisces312` |
| 版本 | `versionName='0.4.3-pisces.1'` / `versionCode='400301'`（未 bump） |
| 产物核对 | `aapt2 dump strings` 命中 `Generate master chart` / `No master chart yet` / `Derive charts` / `%1$d charts derived` / `Latency offset` |

**黄金夹具实测（`GOLDEN` 行，来自测试 stdout）**

| 夹具 | 档位 | 音符数 | 前向匹配 | 反向匹配 | 车道一致 |
|---|---|---:|---:|---:|---:|
| beats | RAW | 146/146 | 100% | 100% | **146/146** |
| beats | HARD | 146/146 | 100% | 100% | **146/146** |
| beats | NORMAL | 80/81 | 100% | 98.8% | 34/80 |
| beats | EASY | 40/41 | 100% | 97.6% | 12/40 |
| dense | RAW | 317/317 | 100% | 100% | **317/317** |
| dense | HARD | 160/160 | 100% | 100% | 114/160 |
| dense | NORMAL | 80/81 | 100% | 98.8% | 28/80 |
| dense | EASY | 40/41 | 100% | 97.6% | 6/40 |

读法：

- **母谱是唯一两个夹具都 100% 时间 + 100% 车道一致的档位**。原因是母谱不吸附网格，
  每个 onset 独占自己的时间与频带 argmax，没有"同格竞争"这种含糊地带。这条正是
  「母谱 = 分析结果」的量化证据。
- **粗网格的车道一致性天然低**（EASY 6–12/40）。一个格里有 kick + hihat 时，参考实现的
  `argmax` 自己在相邻格之间来回翻，不是 Kotlin 侧的问题（v1 §9.2 已记录）。所以车道门禁
  只压在 RAW / HARD 上，时间与数量仍是全档硬门禁。
- **dense 夹具确实分出了阶梯**：母谱 317 ≈ HARD 160 的 **1.98 倍**（断言门槛 1.5 倍），
  且 `EASY 40 < NORMAL 80 < HARD 160` 严格递增。20 s / 120 BPM = 40 拍，
  所以 40 / 80 / 160 正好是"每拍 1 / 2 / 4 个"。
- `beats` 夹具上 `RAW == HARD == 146`，与 §1 的判断一致：它只能锁住管线，证明不了分层。

### 15.4 尚未验证（不得当作已验收）

- **真机耗时**：派生是纯函数的 `<20 ms` 仍是【估算】；母谱的 6–15 s 同理。
- **真机手感**：延迟补偿的方向（正数 = 音符更早到）能否对上蓝牙耳机，只有戴耳机打一次才知道。
- **派生 sheet 的预计音符数**：走的是与 `deriveCharts` 同一条代码路径，但界面上"点了才准"
  这一点没在真机上对比过。
- **`masterGeneratedAtMs` 徽标的视觉**：列表里三条徽标（旧母谱 / 延迟非 0 / 来源）同时出现时
  是否会挤爆一行，未在 411×914dp 上目视确认。

### 15.5 复现命令

```powershell
# 夹具（需要 RhythmChartLab 存在）
python gen_dsp_fixture.py
python gen_dsp_fixture.py --check

# 只跑本特性相关测试
.\gradlew.bat :app:testDebugUnitTest --tests "com.lostf1sh.pixelplayeross.data.rhythm.*"
```

---

## 16. 双键 / 三键（同日追加）

### 16.1 根因：检测器把"同时"合并成了一击

管线第 3–4 步只在**一条** onset 函数（8 个频带的半波整流谱通量之和）上做峰值挑选，
所以同一瞬间的 kick + snare **只会产生一个 peak**，`onsetBands` 取该帧的 argmax 频带。
换言之：**旧谱面里根本不存在"同一时刻的两个 onset"，和弦无从谈起。** 这不是遗漏，
是单 ODF 检测的必然结果。

两种补法：

| 方案 | 做法 | 代价 |
|---|---|---|
| **改检测器** | 每帧按频带分别判定活跃频带数，一个 peak 出多个 onset | 改变分析输出 ⇒ 母谱音符数与黄金夹具全部变化，且与 Python 参考实现永久分叉 |
| **改渲染（采用）** | 分格阶段允许一格多音符，车道取自各 onset 自己的频带 | 纯函数、母谱不动、参考对照仍成立 |

选后者的关键理由：它落在我们已有的"派生层"里，母谱依旧是"检测到的全部 onset"，
**和弦只在派生时按参数展开**，随时可以关掉重来。

### 16.2 规则

一格内可以留多个音符，但三个条件同时满足才算和弦：

1. **车道不同**（同一车道的两个音符在同一毫秒没有意义，只保留最强的那个）；
2. **强度不低于该格最强者的 20%**（这是噪声下限，不是音乐门槛——否则一个 barely
   detected 的杂音会被提升成和弦）；
3. **与最强者相距 ≤ 70 ms**（`CHORD_WINDOW_MS`）。

第 3 条是实测逼出来的。最初只做前两条，结果 **EASY 从 40 个音符涨到 116 个**：1/1 的格子
宽达一整拍（120 BPM 时 500 ms），一拍里的 8 个 onset 全被当成"同时"，于是每一拍都是三键，
简单档直接变成密集档。加上 70 ms 后，只有真正挨在一起的才配对。

70 ms 的取值依据：判定窗口 PERFECT = 60 ms、GREAT = 90 ms，**取两者之间**——
比一个 GREAT 还近，玩家用两个手指按下去会读成"一次"，而不是两下。

`maxChord`（1/2/3）由派生 sheet 的「每拍同时音符」控制，默认 2；`1` 完全等价于旧行为
（黄金夹具与 Python 参考对照都跑这一档，所以夹具零改动）。

### 16.3 不变式

- **和弦只能"捞回"被网格丢掉的 onset，绝不会凭空造音符** ⇒
  `easy ≤ normal ≤ hard ≤ 母谱` 在任何 `maxChord` 下都成立（有测试逐档遍历 1..3）。
- 派生与直生成的逐条相等性同样扩到 `maxChord ∈ 1..3`（`CHORD ...` 断言的同一张表）。
- 单音符档（`maxChord = 1`）的 `derived == direct` 依旧是**逐条完全相等**。
- 游戏侧零改动：判定引擎本来就按车道分组、每车道独立游标，同毫秒多音符天然成立；
  绘制循环按车道画矩形，和弦就是相邻两列同时下落。**`RhythmJudgement` / `RhythmPlaySession`
  / `RhythmGameScreen` 一行没改。**

### 16.4 实测（黄金夹具，`maxChord = 3`）

| 夹具 | EASY | NORMAL | HARD | 母谱 |
|---|---:|---:|---:|---:|
| `dsp-beats-120bpm`（八分音符，间隔 250 ms） | 40 → **40** | 80 → 80 | 146 → 146 | 146 |
| `dsp-dense-120bpm`（32 分音符，间隔 62.5 ms） | 40 → **88** | 80 → **153** | 160 → **267** | 317 |

- 八分音符夹具**一个和弦都不产**——它上面没有任何两个 onset 落在 70 ms 内。这正是想要的
  行为：素材里没有真正同时的音，就不该造和弦。
- 32 分音符夹具才出现和弦，且三档严格递增、都 ≤ 母谱。
- 全量单测 **1030/1030**（和弦新增 8 例：Deriver 7 + Golden 1）。

### 16.5 仍未验证

- **真机手感**：70 ms 是否真的是"一次按键"的边界，只有戴上耳机打一遍才知道。可调手段是
  `CHORD_WINDOW_MS`（改大更密）与派生 sheet 的三档。
- **EASY + 双键在真密集曲目上是否过难**：dense 夹具给出 2.2 音符/拍，仍属可打范围，
  但那是合成鼓点，不是真实混音。
- 母谱本身不含和弦（它是"每个 onset 一个音符"），所以母谱卡上的「▶ 游玩」依旧是单点谱。

---

## 17. v3 修订：派生只做选择，绝不移动时间戳（2026-11-03）

### 17.1 不变式

**母谱的 `note.timeMs` 是唯一真值；任何 DSP 派生路径都不得改写它。**
派生只允许做两件事：决定保留哪些母谱音符、重排车道。被保留的音符沿用母谱的毫秒时间。

依据是完整的误差归因（`rhythm-clock-drift-diagnostics.md` §6–§7）：真机 173 s 日志证明音频
时钟稳定（p50 速率 999.8 ms/s、无累积趋势），在时钟被排除之后，谱面与音乐不匹配只剩两个自由度：

1. **选哪些 onset 成谱**——决定"这个音符有没有真实发声"；
2. **是否移动时间戳**——网格吸附是当时唯一会移动它的环节。

v2 的"格内取最强"同时动了这两件事；v2.1 的 BPM 细化只是把网格贴得更准，**仍然是搬移**。
v3 直接去掉搬移：BPM / timing 不再是音符时间的来源。

### 17.2 难度参数：密度而不是网格

| 参数 | 含义 | 取代 v2 的 |
|---|---|---|
| `maxNotesPerSecond` | 任意 1 s 滑窗内的保留上限（局部 NPS cap）；初始值沿用现有工坊建议：easy 2 / normal 4 / hard 7 | `gridDiv` 的"每拍几分音符" |
| `minGapMs` | **相邻两个保留音符的最小间隔**；初始建议 easy 300 / normal 150 / hard 75 ms | 格宽对"最近可落音符"的约束 |
| `maxNotes`（可选） | 全曲总音符上限，防止超长曲的绝对数量失控 | — |
| 分段 timing | 每 8 拍一段的局部 BPM 与相位，**只作选音先验** | 全局 timing 作量化网格 |

- `maxNotesPerSecond` 是 1 s 滑窗平均，`minGapMs` 是相邻两组之间的硬约束（**上一组的最后一个
  音符到下一组的第一个音符**，所以和弦音符也占满了间隔，不会被下一组挤进来），**两者必须同时生效**：
  只有 NPS 时，4 个音符可以挤在 100 ms 内、再用 900 ms 空窗补回平均；只有 minGap 时，
  长曲仍可能整体过密。取两者中较严的那个。
- 分段 timing 的三种用途：给拍点上的 onset 加权（强拍优先保留）、按段分配音符预算
  （例如每 8 拍最多 N 个）、写谱面元数据 / 未来 `.osu` 导出。**没有一种用途会改变 `timeMs`。**
- 旧谱面的 `timing` / `gridDiv` 保留：读取 v2 谱面、osu 导入，以及用户在派生时显式选择的
  「网格对齐」模式（2026-11-03 恢复为可选项，按 `RhythmTimingRefiner` 的分段网格吸附；
  原声对齐模式仍遵守上面的“不移动时间戳”不变式）。

### 17.3 算法

```text
输入：母谱 notes(timeMs, strength, band) + DspDeriveProfile + timing（只作选音先验）
分组：从第一个未消费 onset 起，70 ms 内的 onset 归为一组；组内取最强为主音符，
      其余按强度补足（车道互斥、强度 ≥ 主音符 20%），最多 maxChord 个
打分：groupScore = Σ (0.05 + strength) × beatWeight(t)
      beatWeight = 1 + 0.25 × cos(2π × 拍内相位)
选组：上一组末音符到下一组首音符的间隔 ≥ minGapMs 的加权区间调度 DP，最大化总得分
密度：按真实音符时间做 1 s 滑窗；候选整组放不进窗内就放弃（先到先得，不淘汰已保留的）
总量：可选 maxNotes，超出时按总分从低到高整组摘除
输出：保留音符 timeMs 原样；按 BAND_ORDER / LOW_OUTSIDE 重排车道并做相邻避让
```

- 选组用 DP 而不是"能放就放"的贪心：贪心先取最早的一组，会在
  "弱而早 / 强而晚（两者只隔一个 minGap）"的位置上系统性丢掉强音符；DP 的
  复杂度是 O(n log n)，没有理由退让。
- 和弦沿用 §16 的 70 ms 规则，但**不再由格宽放大**：同组 onset 的时间戳全部保留，
  只是允许同刻多键。**NPS 预算按音符个数计**——一个三键和弦占 3 个名额。
- `maxNotesPerSecond` 按小数向下取整为 1 s 窗内允许的音符数；2.5 即窗口内最多 2 个。
- 密度收缩用**准入**（放不进窗口的候选组整组丢弃）而不是"超限后摘掉窗内最弱"：
  后者会淘汰已经保留的音符，实测在 20 s 夹具上把 Easy 压到 **21** 个（名义 2 NPS ≈ 40 个），
  而准入给 **29** 个，与同一约束下穷举的最优（约 30）基本持平【2026-11-03 本机实测】。
- 车道避让必须在选择之后重做（与 §4.3 细节 3 相同的原因）。

### 17.4 验收口径（与 v2 不同）

- **音符 → 最近真实 onset 的残差**：按构造为 0（只剩 onset 检测误差），不再有"格内取最强
  搬走最多半格"这一项。
- **onset 检测误差**：当前帧移 512（44.1 kHz 下 11.6 ms/帧）且时间取整到帧 ⇒ ±5.8 ms；
  给 novelty 峰加抛物线插值后约 ±3 ms。另需实测检测器的系统性滞后（谱通量峰通常比真实起音晚
  1–2 帧），必要时在生成期做固定补偿。
- **不能用 RhythmChartLab 的 F1 直接验收 v3**：那组 F1 的参考谱本身是网格量化产物
  （`rhythm-dsp-chart-generation-plan.md` §3.1），它衡量"像不像参考谱"，不衡量"贴不贴音频"。
  v3 在该指标上会低于 v2，但在真实音频对齐上更好。
- 旧谱面不会自动改变：v3 只需重新派生（母谱可复用，不必重新解码 / 分析）。

### 17.5 为什么不做「每难度全量重新生成」（2026-11-03 定）

结论：**保持单母谱 + 选择参数**。三档难度都从同一个母谱选出，不引入 per-difficulty 的
解码 / STFT / onset 检测。

理由按重要性排序：

1. **母谱已经是候选超集。** 重新生成能带来的"新 onset"只可能来自检测层本身的变化
   （灵敏度、分频带候选、峰值间距），而这些与难度无关：为 Easy 再跑一遍同一个检测器，
   得到的仍是同一批 onset。难度差异 100% 是"选哪些"，而选择不需要 PCM。
2. **成本与收益不成比例。** 一次母谱分析 = 解码 + STFT；三档重生 = 三次解码 + 三次 STFT。
   16 GB 内存的设备即使在 30 s 内跑得完，换来的也只是同一批候选的又一次挑选。
3. **可追溯性。** 派生谱通过 `parentChartId` / `masterGeneratedAtMs` 指向母谱，
   "母谱已更新"提示、重新派生、失败重试都依赖这一层；全量生成没有母谱可指。
4. **维护面。** 两条生成路径 = 两套参数、两套测试、两套 F1 基线。v2 的 `render()` 与
   `derive()` 逐条相等的黄金测试之所以存在，就是在为这种重复付息。

唯一真正的检测层缺口：**单条 ODF 上同时发声的两个音会塌成一个峰**（底鼓 + 军鼓/踩镲），
母谱因此缺第二个音。正确修法是在母谱层面**补分频带候选**（8 个频带各自峰值拾取后并集），
一次分析、所有难度共享——而不是为某个难度重新分析。

保留的后门：`deriveOnsetNative` 的 `DspDeriveProfile` 是显式参数。若以后某一档确实需要
独立的检测参数（例如极慢速难度需要更低的 onset 阈值），仍可在母谱之外补一条独立路径，
但它不是默认架构，也不应该成为三档各跑一遍的理由。

