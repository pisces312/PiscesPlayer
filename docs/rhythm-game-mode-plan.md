# 节奏游戏模式与 PC 先行验证方案

> 状态：**方案已定稿，PC 验证待实施**
> 更新：2026-10-30
> 分支：`main`（`pisces312/PixelPlayerOSS`，GPL-3.0-or-later fork）
> 当前决策：先做 PC 端算法与玩法验证，通过验收门禁后再接 Android；PC 验证工具与真实歌曲均不进入 App 发布产物。

---

## 1. 目标与范围

在听歌时进入类似节奏大师的四轨按键游戏，并根据当前歌曲自动生成三种难度谱面：

- 简单：突出强拍与主要鼓点，适合单手或双拇指慢速游玩；
- 普通：跟随主要节奏与鼓点，加入少量双押和交替键；
- 困难：跟随更密集的鼓点与节奏细分，在高能量段落提高密度。

### 1.1 MVP 做

1. 仅分析本地可读取歌曲；PC 阶段支持 FFmpeg 能解码的常见格式。
2. 四轨、单点音符、三难度、谱面缓存。
3. 分数、准确率、最大连击、Perfect / Great / Good / Miss 判定。
4. 可重复的确定性生成：同一输入、算法版本和参数必须得到同一谱面。
5. 暂停、恢复、延迟偏移、判定统计和谱面可玩性校验。

### 1.2 MVP 不做

- Navidrome / Jellyfin 在线流直接分析；
- 长按、滑键、扫键、多押超过两键；
- 在线上传音频、云端生成谱面、分享用户歌曲；
- 文本大模型直接输出音符时间戳；
- 真实歌曲、真实歌曲特征或谱面作为仓库测试素材；
- 在 PC 阶段承诺 Android 蓝牙延迟、触控或 crossfade 已验证。

---

## 2. 核心结论

采用**音频分析 + 确定性谱面编排 + 独立音频时钟**的混合架构：

```text
本地歌曲
  → 离线解码 PCM
  → onset / BPM / 节拍相位 / 段落能量分析
  → 统一音乐锚点
  → 简单 / 普通 / 困难规则编排
  → 可玩性校验与谱面缓存
  → 四轨游戏（音频时钟驱动渲染与判定）
```

“AI”应落在**节拍、downbeat、onset 等音频特征识别**，而不是落在文本聊天模型。现有 `data/ai` 是文本歌单链路，只处理标题和艺术家；`TrackBpmRepository` 只读标签 BPM；二者都不能直接生成可信谱面。

第一版分析器可以先用 DSP 算法完成闭环。后续若要增强为端侧 AI，只需替换特征提取器，谱面格式、难度编排器和游戏引擎保持不变。

---

## 3. 为什么先做 PC 验证

Android 同时引入四类变量：音频算法、触摸事件、Media3 播放时钟、输出设备延迟。如果直接在手机上调试，出现“音符不跟音乐”时很难区分根因。

PC 阶段先固定其中两类变量：

| 验证项 | PC 能验证 | PC 不能验证 |
|---|---|---|
| BPM / onset / 节拍相位 | 能，可用合成音频做客观测量 | Android 解码器行为差异 |
| 三难度谱面质量 | 能，可导出并试玩 | 手机双拇指真实手感 |
| 谱面确定性与合法性 | 能，可自动化测试 | Room 缓存与迁移 |
| 音频时钟与判定算法 | 能，可用 PortAudio 帧时钟 | Media3 / AudioTrack 实际输出时钟 |
| 游戏玩法与判分 | 能，用键盘 D/F/J/K | 多指触控与手势冲突 |
| 输出延迟 | 仅验证 PC 输出链路和偏移机制 | 手机扬声器、有线、蓝牙延迟 |

因此 **PC 通过是 Android 实施的前置门禁，不是最终验收**。

---

## 4. PC 验证工具设计

### 4.1 技术路线

规划新增隔离目录 `tools/rhythm-lab/`，使用 Python 做算法研究和可玩预览：

- FFmpeg：压缩音频解码为 PCM；
- NumPy / SciPy：STFT、包络、峰值与节拍计算；
- librosa（可选）：作为 onset / tempo 实现参考或对照，不直接成为 App 依赖；
- sounddevice / PortAudio：PCM 播放与样本帧时钟；
- pygame：四轨渲染、键盘输入、判定与结算；
- pytest：合成夹具、谱面约束和确定性测试。

该工具是研发验证台，**不打进 Android APK，也不要求 App 引入 Python 依赖**。算法验证通过后，将最终采用的特征提取与谱面编排逻辑用 Kotlin 重写，并用 PC 黄金结果做跨端对照。

### 4.2 规划目录

```text
tools/rhythm-lab/
  pyproject.toml
  README.md
  rhythm_lab/
    audio.py          # FFmpeg 解码与 PCM 时间轴
    features.py       # onset / tempo / beat / energy
    model.py          # 分析与谱面数据契约
    chart.py          # 三难度编排与键位分配
    validate.py       # 谱面合法性、密度与确定性校验
    clock.py          # PortAudio 播放时钟与偏移
    judge.py          # 判定、连击、分数
    play.py           # D/F/J/K 可玩预览
    cli.py            # analyze / chart / validate / play
  tests/
  fixtures/           # 仅合成 PCM、合成特征与预期结构
  output/             # 本地生成结果，gitignore，不提交
```

### 4.3 PC 命令目标

最终工具至少提供四个入口：

```powershell
python -m rhythm_lab analyze <audio> --out output/song-analysis.json
python -m rhythm_lab chart <audio> --all-difficulties --out output/song-charts/
python -m rhythm_lab validate output/song-charts/
python -m rhythm_lab play <audio> --chart output/song-charts/normal.json
```

真实歌曲从用户本机路径读取；`output/` 和本地音频路径均不得提交。

---

## 5. 音频分析层

### 5.1 PCM 解码

统一先解码为单声道浮点 PCM，默认采样率 22050 Hz，必要时用 44100 Hz 做对照。所有分析结果必须映射回原始歌曲毫秒时间轴，不能因重采样、窗口拼接或截断产生累计漂移。

初始 STFT 参数仅作为调参起点：

- FFT 窗口 1024；
- hop size 256；22050 Hz 下时间分辨率约 11.6ms；
- onset envelope 使用频谱通量；
- 自适应阈值与局部峰值选择抑制重复 onset。

### 5.2 特征输出

```text
AudioAnalysis
  schemaVersion
  sourceFingerprint
  durationMs
  sampleRate
  bpm
  beats[]             # 节拍时间，ms
  onsets[]            # timeMs + strength + 可选频段特征
  sections[]          # startMs + endMs + energy
  analyzerVersion
```

分析规则：

1. **BPM 标签只作先验提示**，不能代替 beat phase 和 onset。
2. tempo 搜索先覆盖常见 60～180 BPM，并显式处理半速/倍速歧义。
3. 简单难度优先使用稳定拍点；普通和困难再使用显著 onset。
4. onset 不一律强行量化到网格：只有接近网格且在容差内才吸附，避免 Swing、弱拍和现场演奏被拉得机械。
5. 段落能量用于控制密度，不负责猜歌曲结构标签；MVP 不必可靠识别“主歌/副歌”名称。
6. 对无稳定节拍、自由速度、长 ambient 段落允许输出低置信度，并降低密度或提示不适合生成，不能硬造高强度谱面。

### 5.3 后续 AI 替换点

若引入端侧 beat-tracking 模型，模型输出仍归一化为上述 `AudioAnalysis`。选型前必须验证：

- 模型及权重许可证允许集成与再分发；
- 不存在 AGPL 等使项目许可义务发生非预期变化的依赖；
- 模型体积、峰值内存、推理耗时和 Android 后端可用性；
- 对中文流行、摇滚、电子、轻音乐及无鼓点素材的效果；
- 与 PC 参考结果的误差和失败样本。

---

## 6. 谱面数据契约

### 6.1 格式

PC 与 Android 共用语言无关的 JSON 契约，便于黄金结果对照：

```json
{
  "schemaVersion": 1,
  "generatorVersion": "dsp-1",
  "sourceFingerprint": "local-file-fingerprint",
  "durationMs": 180000,
  "difficulty": "normal",
  "bpm": 120.0,
  "seed": 123456789,
  "notes": [
    { "id": 0, "timeMs": 1250, "lane": 0, "type": "tap" },
    { "id": 1, "timeMs": 1500, "lane": 2, "type": "tap" }
  ]
}
```

约束：

- `lane` 固定为 0～3；MVP `type` 只有 `tap`；
- `notes` 按 `timeMs`、`lane` 稳定排序；`id` 连续且唯一；
- 所有音符必须落在歌曲有效区间内；
- 文件指纹、生成器版本、难度和参数共同决定缓存键；
- 算法升级必须递增 `generatorVersion`，不得静默覆盖旧谱面语义。

### 6.2 三难度编排

三难度共享同一份 `AudioAnalysis`，保留相同的主要音乐锚点，不能分别随机生成三套互不相关的节奏。

| 规则 | 简单 | 普通 | 困难 |
|---|---:|---:|---:|
| 初始目标密度 | 1～2.5 NPS | 2.5～4.5 NPS | 4.5～7 NPS |
| 主要来源 | 强拍、重鼓点 | 节拍 + 主要 onset | 细分节拍 + 高强度 onset |
| 节奏细分 | 每拍约 1 个 | 每拍 1～2 个 | 高能量段每拍 2～4 个 |
| 双押 | 无 | 少量，仅强锚点 | 可更多，仍最多两键 |
| 同轨连打 | 避免 | 低速交替 | 允许短连打，受最小间隔限制 |
| 低能量段 | 大幅抽稀 | 适度抽稀 | 仍控制密度 |

NPS 是初始调参区间，不是必须强行填满的指标；慢歌不应为达到密度而加入无音乐依据的音符。

### 6.3 键位分配与可玩性校验

键位由事件强度、前序键位、同轨间隔和双押约束共同决定，使用确定性 seed，不做无约束随机。

生成后必须通过 `ChartValidator`：

1. 时间、轨道、排序、ID、曲长全部合法；
2. 同轨与全局最小间距符合对应难度配置；
3. 同时按键最多两个，且不存在同轨同刻重复音符；
4. 高密度只出现在有足够 onset 支撑的区段；
5. 简单 ≤ 普通 ≤ 困难的总体密度趋势成立；
6. 相同输入重复生成的谱面规范化 JSON 哈希一致；
7. 不输出超出有效曲长或落在解码尾部的孤立音符。

---

## 7. PC 可玩预览与同步判定

### 7.1 游戏方式

- 四轨分别映射 `D / F / J / K`；
- 音符向判定线下落，显示当前连击、分数和实时判定；
- 支持暂停、恢复、重新开始、三难度切换和输出偏移调整；
- 结算显示准确率、最大连击、各判定数量及早/晚分布；
- 可导出判定日志，用于区分“谱面不准”和“时钟/输出不准”。

### 7.2 音频时钟

不能用帧数累加，也不能仅依赖低精度播放器进度回调。PC 预览使用 PortAudio 输出样本帧作为锚点，并结合输出延迟和手动偏移计算可听歌曲时间。

渲染与判定分离：

```text
PCM 输出帧 / 音频时钟 → 当前可听歌曲时间
                         ├─ Canvas 只负责绘制此刻应出现的音符
                         └─ 键盘事件时间戳 → 歌曲时间 → 判定
```

暂停、恢复或重新播放必须重新建立锚点；不得沿用暂停前的系统时钟差值。

### 7.3 判定与计分

初始判定窗口：

| 判定 | 绝对时间误差 |
|---|---:|
| Perfect | ≤45ms |
| Great | ≤90ms |
| Good | ≤140ms |
| Miss | 超过窗口或漏接 |

判定输入使用键盘事件发生时间对应的歌曲时间，而不是下一帧 UI 回调时间。每个音符最多消费一次，每次输入最多命中一个可判定音符；同时输入分别处理。

分数公式可在 PC 阶段调整，判定窗口先保持三难度一致，避免把“难度”变成判定规则不一致。

---

## 8. PC 验收门禁

### Gate A：解码与时间轴

- 本地 MP3、FLAC、M4A、Ogg 样本均能解码；
- 解码后时长与源时长误差处于容器和解码正常范围；
- 合成点击音在不同采样率、声道和响度下，时间戳无累计漂移。

### Gate B：合成音频客观指标

建立 60 / 90 / 120 / 150 / 180 BPM 的合成点击与鼓点夹具，并加入静音、噪声和强弱变化：

- 稳定 4/4 夹具 BPM 误差 ≤0.5 BPM；
- 不出现半速/倍速误锁；
- 稳定段落节拍相位中位绝对误差 ≤20ms；
- 清晰 onset 夹具的峰值检测达到预设 precision / recall 目标，初始目标均 ≥0.90；
- 无 onset 的纯静音不得生成音符。

这些指标只针对可控合成夹具，不能直接宣称真实音乐也达到相同准确率。

### Gate C：谱面自动校验

- 全部谱面通过 §6.3；
- 同输入重复生成哈希一致；
- 三难度均不存在超曲长、重复、非法双押和不可达同轨连打；
- 参数变化只影响预期区段，黄金结果变化必须可解释。

### Gate D：真实歌曲人工验收

使用至少 10 首本地歌曲，覆盖中文流行、摇滚、电子、嘻哈、轻音乐及弱节拍素材，每首选取前奏、中段和高潮试玩：

- 简单主要跟强拍，普通主要跟清晰鼓点，困难不是随机加密；
- 不存在全曲系统性提前或滞后；
- 前奏、间奏、尾奏的稀疏处理合理；
- 三难度均有可玩性，弱节拍歌曲能低置信度降级而不是硬造谱面；
- 记录失败歌曲、失败区段和参数，不用单一“总体感觉”作为结论。

真实歌曲只保留本地验证记录和匿名化指标，不把歌曲文本、音频、特征或谱面提交仓库。

### Gate E：可玩与稳定性

- D/F/J/K 多键输入、暂停、恢复、重开和偏移调整均正常；
- 连续游玩不存在由帧率引起的累计漂移；
- 判定日志可解释每次 Perfect / Miss 的时间误差；
- PC 输出设备偏移可配置，并验证偏移方向没有写反。

全部 Gate 通过后，才进入 Android 播放链与触控实现。

---

## 9. Android 落地架构（PC 通过后）

### 9.1 模块划分

```text
app/src/main/java/com/lostf1sh/pixelplayeross/
  data/rhythm/
    AudioFeatureExtractor
    RhythmChartGenerator
    RhythmChartValidator
    RhythmChartRepository
    RhythmAnalysisWorker
  presentation/rhythm/
    RhythmGameScreen
    RhythmGameViewModel
    RhythmAudioClock
    RhythmJudgementEngine
    RhythmGameCanvas
```

纯算法部分不依赖 Android UI，判定逻辑注入时钟，以便 JVM 单测复用 PC 黄金夹具。

### 9.2 现有代码复用与限制

| 能力 | 复用点 | 注意事项 |
|---|---|---|
| 当前歌曲与播放状态 | `MainActivity` 共享的 `PlayerViewModel.stablePlayerState` | 游戏页不得新建第二个 `PlayerViewModel` |
| 播放服务 | `MusicService`、`DualPlayerEngine.masterPlayer` | A/B crossfade 会使显示曲目与实际发声曲目短暂不同 |
| 导航 | `Screen`、`AppNavigation` | 新增 `RhythmGame`，隐藏底部导航、迷你播放器与播放器 Sheet |
| 后台生成 | HiltWorker / WorkManager | 唯一任务去重、进度、取消、失败与文件失效 |
| Canvas 帧循环 | `BrickBreakerOverlay` 的 `withFrameNanos` 模式 | 不复用其单点手势充当四轨多指输入 |
| 设置 | `UserPreferencesRepository`、`SettingsRegistry` | 默认难度、偏移、触觉反馈均需注册搜索 |
| 数据持久化 | Room + Migration | 新增表时 DB 从 15 升 16，提交 schema 与迁移测试 |

### 9.3 播放时钟

现有 `PlaybackStateHolder` 的 250ms / 1s 位置轮询只服务进度条和歌词，不能承担节奏判定。Android 需要独立 `RhythmAudioClock`：

- 定期从实际发声 Player 获取位置锚点；
- 锚点之间使用单调系统时钟插值；
- 暂停、seek、切歌、position discontinuity、输出路由变化时重新锚定；
- 渲染只读取时钟，不逐帧累加歌曲位置；
- 触摸事件发生时间转换为歌曲时间后立即判定。

### 9.4 游戏期间播放规则

1. 锁定播放速度为 1.0x，退出后恢复原状态；
2. 禁止游戏内 seek，外部 seek 直接结束本局；
3. 音乐暂停或缓冲时暂停游戏；
4. 切歌时结算或结束，不把旧谱面用于新歌曲；
5. 游戏期间关闭 crossfade / 自动连播，退出后恢复原设置；
6. 处理沉浸式系统栏、返回手势、keep-screen-on 和输出设备切换；
7. 第一版只保留视觉与可选触觉反馈，不增加可能产生额外延迟的按键音效。

### 9.5 触控与延迟校准

四轨输入必须跟踪 pointer ID，支持真正多指与双押，不能只依赖 `detectTapGestures`。

设置中提供节拍校准：

- 全局手动偏移；
- 后续按扬声器、有线、蓝牙输出分别保存；
- 校准只调整统一时间偏移，不修改谱面原始时间；
- PC 验证必须确认偏移符号，Android 再验证真实设备延迟。

---

## 10. 缓存与持久化

Android 阶段建议将谱面正文存为应用私有文件，Room 保存索引与成绩。

谱面索引至少包含：

```text
songId / difficulty / sourceFingerprint / generatorVersion
chartPath / durationMs / noteCount / createdAt
```

成绩至少包含：

```text
songId / difficulty / score / accuracy / maxCombo / playedAt
```

文件替换、时长变化或生成器升级使旧缓存失效；歌曲删除时清理索引、谱面文件和成绩。设置 DataStore 只存默认难度、偏移和反馈选项，不存大量音符。

---

## 11. 实施顺序

### Phase 0：PC 分析器

- 建隔离工具、FFmpeg 解码、合成夹具；
- 输出 `AudioAnalysis` 与可视化；
- 完成 Gate A / B。

### Phase 1：PC 三难度谱面

- 实现统一 JSON、三难度编排、键位分配、校验器；
- 导出谱面和统计；
- 完成 Gate C，并开始真实歌曲人工记录。

### Phase 2：PC 可玩预览

- D/F/J/K、音频时钟、判定、暂停恢复、偏移与结算；
- 完成 Gate D / E；
- 固化黄金夹具与参数版本。

### Phase 3：Android 核心移植

- Kotlin 重写最终算法，与 PC 黄金结果对照；
- WorkManager 生成、缓存、Room migration；
- 接入共享播放链和独立音频时钟。

### Phase 4：Android 游戏与真机验收

- 四轨多指、游戏 UI、设置、延迟校准；
- 分别验证扬声器、有线、蓝牙；
- 验证暂停、seek、切歌、crossfade、变速恢复与输出路由变化。

### Phase 5：可选 AI 增强与玩法扩展

- 评估端侧模型替换特征提取器；
- 长按、滑键、练习模式、谱面预览或编辑；
- 云端歌曲仅在离线下载和版权边界明确后考虑。

---

## 12. 主要风险与停止条件

| 风险 | 处理 |
|---|---|
| BPM 正确但 beat phase 偏移 | 合成夹具单独测相位，真实歌曲导出判定日志 |
| 困难谱面只是随机加密 | 高密度必须有 onset / 细分节拍支撑，并受能量段落约束 |
| 自由速度或无鼓点歌曲效果差 | 输出低置信度、降低密度或提示不适合，不强行生成 |
| PC 与 Android 算法结果不同 | 使用同一 JSON 契约、固定参数和黄金夹具做跨端对照 |
| 手机听感提前/滞后 | 独立音频时钟 + 输出偏移校准，不能靠改谱面整体平移掩盖 |
| 分析影响播放性能 | 整曲分析放后台，禁止在实时 AudioProcessor 中执行 |
| 真实素材进入仓库 | 仅提交合成夹具和匿名指标，真实音频、特征和谱面保持本地 |

若 PC 阶段无法稳定通过合成音频的 BPM/相位门禁，或真实歌曲普遍需要逐首手工修谱，则暂停 Android 接入，先改进分析器；不能用游戏判定窗口或随机抽稀掩盖基础分析问题。
