# 音频输出方案（面向"最好的播放效果"）——双正交选项版

> 状态：**方案已收敛，待实施**（v3）
> 更新：2026-09-12（v3：纠错 + SAFE_DIRECT 保留降混 + 蓝牙/Type-C/文件体积 + 分阶段落地）
> 分支：`main`（`pisces312/PixelPlayerOSS`，GPL-3.0-or-later fork）
> 背景：`docs/direct-audio-output.md` 已分析 DIRECT 直通原理、验证方法、历史参考实现对比与听音场景定位（§9）
> 结论：**主入口 = 两个正交选项（采样率策略 × 输出精度），"档位"降级为预设快捷**；**日常蓝牙听歌以 SYSTEM_DEFAULT 为默认**；Type-C 数字有线才有机会吃到直通收益，详见 §3 / §4.3–4.4

---

## 1. 目标与原则

让用户在"最好的播放效果"上有**清晰、可预期、可自由组合**的选项。判断效果好坏涉及四个维度：

| 维度 | 好坏判定 | 现状 |
| --- | --- | --- |
| **采样率保真** | 不被系统重采样（44.1/96/192 kHz 原样直达 DAC）最好 | 只有 DIRECT 有机会，且无上限保护 |
| **位深/编码** | 整数 PCM 原样 vs 32 位浮点（处理余量） | PCM_FLOAT 可开 float |
| **应用层处理** | 越少越好（上限保护、降混都是妥协） | 默认档有 192 kHz 封顶 + Dolby 降混 |
| **功耗/兼容** | offload 省电但受 HAL 限制，部分设备有卡顿/gapless 问题 | 仅 SYSTEM_DEFAULT 允许 offload |

**核心矛盾**：采样率保真（直通）与 32 位浮点在**系统层几乎互斥**——float 编码很难命中硬件 direct profile（个别 USB Audio HAL 除外），直通大概率失效（`direct-audio-output.md` §7、§9）。因此"最好效果"不是全开，而是**按输出设备组合一个最优配置**。

**输出设备是第一分叉**（本节 v3 补强）：

| 输出 | 最大损失源 | 推荐 |
| --- | --- | --- |
| **蓝牙 A2DP / LE Audio** | BT 编码器与码率、采样率是否匹配 | **SYSTEM_DEFAULT**；DIRECT/PCM_FLOAT 基本无用 |
| 有线耳机 / 扬声器（手机 DAC） | 系统重采样（尤其 96/192k→48k）、整型音量 | 高解析文件用 SAFE_DIRECT；其余 SYSTEM 或 FLOAT |
| USB DAC / Hi-Fi 机型 | 能否拿到 direct 线程 | SAFE_DIRECT 或 FULL_DIRECT（dumpsys 验证） |

---

## 2. 可选方案对比

- **方案 A（最小改动）**：保持三选一，只调文案/默认值。缺点：缺"安全直通"选项，无法表达"直通但保留 192 kHz 保护"。
- **方案 B（双正交）**：两个独立开关自由组合。缺点：对普通用户太技术化，需配合法性约束。
- **方案 C（平级档位）**：3~4 个语义档位。缺点：档位间差异藏在背后，无法自由组合。

**结论：采用 B 为主结构、C 的档位作为预设快捷**——两者不冲突，正交选项是"引擎"，档位是"一键配方"。

---

## 3. 推荐结构：双正交选项 + 预设快捷

### 3.1 两个正交选项

**选项 1 · 采样率策略 `SampleRateStrategy`**

| 值 | 含义 | 采样率处理 |
| --- | --- | --- |
| `SYSTEM` 系统默认 | 交给系统 | 可能被重采样到 48 kHz |
| `SAFE_DIRECT` 高解析直通 | 保采样率 + 留保护 | **≤192 kHz 原样**，>192k 降采样到 192k |
| `FULL_DIRECT` 极致直通（实验） | 全速率原样 | 含 352.8/384 kHz，无保护 |

**选项 2 · 输出精度 `OutputPrecision`**

| 值 | 含义 |
| --- | --- |
| `INTEGER` 整数 PCM | 跟随源文件位深（最高 24-bit） |
| `FLOAT` 32 位浮点 | 处理余量优先 |

### 3.2 组合与合法性（3×2 = 6，合法 4）

| 采样率策略 \ 精度 | 整数 PCM | 32 位浮点 |
| --- | --- | --- |
| `SYSTEM` 系统默认 | ✅ 智能默认（offload 可开） | ✅ 32 位浮点 |
| `SAFE_DIRECT` 高解析直通 | ✅ 高解析直通 | ❌ 非法（float 无法命中硬件 direct profile） |
| `FULL_DIRECT` 极致直通 | ✅ 极致直通 | ❌ 非法（同上） |

- 非法组合在 UI **禁用**并给出解释（一句话：浮点编码在系统层无法直通，选择浮点后采样率只能交给系统）。
- 配置映射（推导规则，落在引擎一处）：
  - 直通类（SAFE/FULL_DIRECT）→ 关闭 offload、独立 audio session id；
  - `SYSTEM` → offload 按设备可用性自动（带卡死 fallback）、共享 session；
  - `FLOAT` → `setEnableFloatOutput(true)`；直通类恒为整数输出。

### 3.3 音质梯度排序（由弱到强）

排序依据：**对高解析源，采样率保真通常 > 输出精度**。96/192 kHz 被降到 48 kHz 是可闻的降级；对普通 44.1 kHz 曲目，现代 Android SRC 往往不是最大瓶颈——**蓝牙编码、系统音效、整型域音量**可能更可闻。下表是「有线/USB + 高解析源」语境下的**潜在上限**排序，不是绝对真理。

| 排序 | 组合（采样率策略 × 精度） | 采样率处理 | 精度 | offload | 音质特征 | 适用 / 风险 |
| --- | --- | --- | --- | --- | --- | --- |
| 1（最弱） | 系统默认 × 整数 PCM | 交系统，可能重采样到 48 kHz | 16/24-bit 整数 | 可开 | 兼容性最佳，最可能被重采样 | **日常 / 蓝牙 / 在线流（默认推荐）** |
| 2 | 系统默认 × 32 位浮点 | 交系统，可能重采样 | 32-bit float | 关 | 处理余量；ReplayGain/音量衰减更干净 | 有线本地库、设备不支持直通 |
| 3 | 高解析直通 × 整数 PCM | ≤192 kHz 原样，>192k 降 192k；**保留 Dolby 降混** | 16/24-bit 整数 | 关 | 高解析文件保采样率，有保护 | 本地 96/192k FLAC + 有线 / USB DAC |
| 4（最强/最险） | 极致直通 × 整数 PCM | 全速率原样（含 352.8/384 kHz） | 16/24-bit 整数 | 关 | 无自定义处理器，采样率完全原样 | 发烧友 + 确认设备支持；**有卡死风险（实验）** |

> 蓝牙输出时上表 3/4 **不适用**：BT 路径不会授予 HAL direct 线程，音质由编解码器决定（§4.3）。FULL_DIRECT 最强 ≠ 最推荐。

### 3.4 预设快捷（"档位"按钮，一键应用常见组合）

| 预设 | 组合 |
| --- | --- |
| 智能默认（推荐） | `SYSTEM` + `INTEGER` |
| 高解析直通 | `SAFE_DIRECT` + `INTEGER` |
| 极致直通（实验） | `FULL_DIRECT` + `INTEGER` |
| 32 位浮点 | `SYSTEM` + `FLOAT` |

预设只是把两个选项一次性设置好，**不新增状态**；用户随后仍可单独改任一选项。

### 3.5 选择建议（写入设置页说明）

- **不知道选什么 / 蓝牙 / 在线流 → 智能默认（SYSTEM + INTEGER）** ← 平时蓝牙听歌选这个
- 本地高解析 FLAC（96/192 kHz）+ **有线 / USB DAC** → 高解析直通
- 发烧友 + 确认设备支持原生采样率（USB DAC / Hi-Fi 机型）→ 极致直通（用 dumpsys 验证）
- 本地库 + 有线、想要更好的 RG/音量精度 → 32 位浮点
- **连着蓝牙时不要选直通类**：无收益，还关掉 offload、多耗电

---

## 4. 概念说明

### 4.1 什么是 offload（Audio Offload）

把"解码 + 播放"整体交给设备音频 DSP（HAL 硬件）：App 不产出 PCM，直接交**压缩码流**（MP3/AAC/FLAC…），由硬件解码并直出，跳过应用处理器链与 mixer 重采样。

- **好处**：息屏播放显著省电；卸载 CPU；部分设备 DSP 直出 DAC。
- **限制**：格式/采样率受 HAL 支持范围限制（不支持自动回退普通路径）；应用侧音效/处理器全部失效；部分设备有卡顿或 gapless 接缝 bug（项目已有 `AudioOffloadStallFallback`：检测到 HAL 卡住即禁用 offload 并重建 player）；音量走硬件。
- **与方案关系**：offload 仅在 `SYSTEM` + `INTEGER`（智能默认）下允许；直通/浮点组合强制关闭（语义冲突）。

> **注意：offload ≠ MediaCodec 硬件解码器**，两者是不同层面：
> - MediaCodec 解码器（设备能力页"硬件/软件"标签）= **App 进程内**解码压缩码流 → PCM；没有硬件解码器只是改用软件解码（更耗 CPU），照样能播，**与 offload 无关**。
> - Audio Offload = **音频 HAL/DSP 层面**，App 不走 MediaCodec，直接把压缩码流交给 `AudioTrack` 由系统硬件解码直出；能否 offload 由 `AudioManager.isOffloadedPlaybackSupported()`（设备能力页 `getOffloadSupportedFormats()` 已探测，格式行上的 "Offload" 芯片即结果）决定。
> - 结论：只有软件解码器的手机，其音频 HAL 依然可能支持 MP3/AAC/FLAC offload；反之有硬件解码器也不代表支持 offload。

### 4.2 什么是自动按内容选档（远期）

播放时监听 `player.audioFormat`（解码后的采样率/位深/声道），自动选组合：普通 44.1/48k → 智能默认（可 offload）；96/192k FLAC → 高解析直通。

- **难点**：切组合 = 重建 player = 短暂中断；跨曲切换需用 auxiliary player（crossfade 的 B player）预构建目标配置才可无感，复杂度高。
- **低成本替代**：不做自动重建，检测到高解析文件且当前非直通组合时提示"建议切换"，用户一键确认。
- **建议**：本期不做自动重建；蓝牙感知提示优先于按内容选档（见 §4.3 / Phase 2）。

### 4.3 蓝牙输出（日常听歌主场景）

蓝牙音质链路：

```
解码 PCM → [App AudioSink] → AudioFlinger/mixer → BT 栈 → 编码器（SBC/AAC/aptX/LDAC/LC3）→ 耳机
```

- **DIRECT 在 BT 上无效**：BT 不走 HAL direct 线程；只会失去自定义链、独立 session、关掉 offload。
- **PCM_FLOAT 在 BT 上收益很小**：最终被 BT 编码再压一道；只减少 App 内中间精度损失。
- **真正可调的（多数在系统设置，不在 App）**：
  1. 开发者选项 → 蓝牙音频编解码器：优先 **LDAC**（或 aptX HD / LHDC），其次 AAC，尽量避免 SBC；
  2. LDAC 质量档：码率优先（990k）音质最好，连接优先最稳；隔墙/干扰大时降码率更少卡顿；
  3. 采样率：源 44.1k 时若编解码器锁 48k 会有一次 SRC；部分机型/耳机支持 44.1k A2DP，可在开发者选项里试；
  4. 系统音效：BT 路径 EQ/杜比**会**生效——觉得闷/糊先关音效再比；
  5. 蓝牙绝对音量：开着时 App 音量跟系统联动，一般更省一步衰减；异常时关掉再比。
- **App 侧推荐默认**：检测到 A2DP/LE Audio 为当前输出时，**引导使用 SYSTEM_DEFAULT**，可保留 offload（省电）。Phase 2 做「蓝牙感知」提示（不自动改用户选择，只提示）。

| 蓝牙场景下的模式 | 建议 |
| --- | --- |
| SYSTEM_DEFAULT | ✅ 默认；兼容 + 可 offload |
| PCM_FLOAT | 可选，收益小；本地库 + 在意 RG 时可开 |
| DIRECT / SAFE_DIRECT / FULL_DIRECT | ❌ 不推荐；无直通收益 |

**系统设置里的 AVRCP / MAP / PBAP（与音质无关）**：

| 协议 | 作用 | 建议 |
| --- | --- | --- |
| AVRCP | 遥控（播放/切曲/音量同步/曲目信息） | 有 1.5/1.6 就用；异常再降 1.4 |
| MAP | 短信/通知访问（车机等） | 默认即可，不影响听歌 |
| PBAP | 电话本同步 | 默认即可，不影响听歌 |

编解码器选项若为灰色：多数机型需**先连接 A2DP 音频设备**再进开发者选项；仍不可选则属 ROM 限制。

### 4.4 Type-C 有线与源文件体积（认真听歌路径）

#### 4.4.1 Type-C 有线：两种情况

| 类型 | 音频怎么走 | 音质上限 |
| --- | --- | --- |
| **数字（主流）** 外置小尾巴 / 带 DAC 的 C 口耳机 | PCM → Type-C → **耳机/解码棒 DAC** | 高；常支持 44.1/96/192k 原生采样率，**有机会命中 direct** |
| **模拟（少数旧机）** 手机内 DAC → C 口模拟 | 同 3.5mm，走手机 DAC | 中等，取决于手机 |

- 蓝牙是「先编码再传」，Type-C 数字是「PCM 直送 DAC」——本质差别。
- 有线时开发者选项里的 LDAC/编解码器**用不到**；系统 EQ 在 mixer 路径仍会生效，direct 下不生效。
- DIRECT **不保证**成功；未匹配则静默 fallback，与 SYSTEM_DEFAULT 差距缩小。
- 352.8/384 kHz 在部分设备会触发 loading 卡死（本项目 cap 处理器即为此存在）；DIRECT 无 cap，慎用。

**Type-C 推荐配置**：

| 条件 | 模式 |
| --- | --- |
| 普通曲库（MP3 / FLAC 16-44.1）+ 小尾巴 | **SYSTEM_DEFAULT** 或 SYSTEM+FLOAT |
| 96/192k FLAC + 像样 USB DAC | **SAFE_DIRECT**（Phase 1）/ 现状 DIRECT，dumpsys 验证 |
| 仅确认设备原生支持全速率的发烧友 | FULL_DIRECT（实验） |

#### 4.4.2 源文件体积（FLAC 立体声估算）

未压缩 PCM ≈ `位深 × 采样率 × 声道数 / 8`。FLAC 无损压缩约为原始 PCM 的 **50%–70%**（码率复杂度而变）。下表按 **4 分钟立体声**、FLAC 取约 **60%** 估算：

| 格式 | 未压缩约 | 实际文件约 | 每 GB 约可存 |
| --- | --- | --- | --- |
| MP3 320 kbps | — | **~9–10 MB/首**（4min） | ~100 首/GB |
| FLAC 16-bit / 44.1 kHz（CD） | ~40 MB/4min | **~20–30 MB/首** | ~35–50 首/GB |
| FLAC 24-bit / 96 kHz | ~130 MB/4min | **~65–90 MB/首** | ~12–15 首/GB |
| FLAC 24-bit / 192 kHz | ~260 MB/4min | **~130–180 MB/首** | ~6–8 首/GB |

经验换算（立体声 FLAC）：

- **16/44.1**：约 **5–8 MB/分钟**
- **24/96**：约 **16–22 MB/分钟**
- **24/192**：约 **32–45 MB/分钟**

要点：

- 24/192 大约是 CD FLAC 的 **5–6 倍**，100 首就是 **13–18 GB**。
- 对蓝牙听歌，高解析体积**买不到**对应听感（会被 BT 编码压掉）；高解析+大体积只在 **Type-C 数字有线 + DIRECT/SAFE_DIRECT** 时才可能值回票价。
- 日常蓝牙/通勤：优先 **FLAC 16/44.1 或 320k 压缩**即可，省空间、差距可闻性低。

#### 4.4.3 场景速查

| 场景 | App 模式 | 源文件建议 | 备注 |
| --- | --- | --- | --- |
| 蓝牙耳机通勤 | SYSTEM_DEFAULT | 16/44.1 FLAC 或 320k | 系统里调 LDAC/编解码器 |
| Type-C 小尾巴日常 | SYSTEM_DEFAULT / FLOAT | 16/44.1 为主 | 想再进一步试 DIRECT |
| Type-C USB DAC 认真听 | DIRECT / SAFE_DIRECT | **24/96 或 24/192 FLAC** | dumpsys 验证；注意体积 |
| 手机外放 | SYSTEM_DEFAULT | 任意 | 输出差异不重要 |

---

## 5. 实现要点（确认后动工）

### 5.1 数据层

- 新增两个枚举（`data/model/`）：
  - `SampleRateStrategy`（SYSTEM / SAFE_DIRECT / FULL_DIRECT）
  - `OutputPrecision`（INTEGER / FLOAT）
  - 合法性函数 `isValid(strategy, precision)`（直通 × FLOAT = false）
- 组合状态持久化：沿用 `audio_output_mode_v1` key，值改为组合串（如 `"sys|int"`）；`fromStorageKey` 提供 v1 旧值迁移表：

  | 旧值 | 迁移到 |
  | --- | --- |
  | `system_default` | SYSTEM + INTEGER |
  | `direct` | FULL_DIRECT + INTEGER |
  | `pcm_float` | SYSTEM + FLOAT |

  遗留 `hi_fi_mode_enabled` 迁移逻辑保留。
- 现有 `AudioOutputMode` 枚举**废弃或改造成"预设"视图**（映射到组合），避免双状态源。

### 5.2 引擎层（`DualPlayerEngine.kt`）

- `buildAudioSink`（约 :1002-1030）按组合分支：
  - `SYSTEM` + INTEGER：现状 SYSTEM_DEFAULT 分支（cap + Dolby 降混 + float 关）；
  - `SYSTEM` + FLOAT：现状 PCM_FLOAT 分支（含能力检测）；
  - `SAFE_DIRECT` + INTEGER：自定义链 = **`HiResSampleRateCapAudioProcessor` + `SurroundDownmixProcessor`（保留降混）** + float 关 + 独立 session + 关 offload。**修正 v2**：不能去掉降混，否则 5.1/7.1 本地文件在手机上可能坏掉；
  - `FULL_DIRECT` + INTEGER：现状 DIRECT 分支（`super.buildAudioSink`，无自定义链）。
- `shouldEnableAudioOffloadForMode`（:158-163）：仅 `SYSTEM + INTEGER` 允许。
- 共享 session id（:1129-1131）：直通类不共享，其余共享。
- `setAudioOutputMode`（:1204-1217）：PCM_FLOAT 能力回退改为"组合合法性 + 能力"双层校验；变更仍走 `rebuildPlayersPreservingMasterState`。
- cap 处理器：可选补齐对 `ENCODING_PCM_24BIT_PACKED` 的透传说明或忽略策略（当前解码多为 16/float，非阻塞）。

### 5.3 UI 层

- `SettingsCategoryScreen.kt`：单选选择器改为**两个选项行**（采样率策略 3 值 + 精度 2 值），非法组合禁用；下方"预设快捷"一排按钮（仅写组合，不写状态）。
- `SettingsViewModel.kt` / `SettingsRegistry.kt` / `strings_settings.xml`（英文基准）+ `values-zh-rCN/`：同步更新；文案写明**蓝牙请用智能默认**（§4.3）。
- 蓝牙感知（Phase 2）：检测当前活动输出是否 BT，设置页模式行下加提示条。

### 5.4 测试与验证

- 单元：组合合法性、v1 迁移、offload 判定、预设→组合映射。
- 真机（每组合）：44.1/96/192/384 kHz 样本，`adb shell dumpsys audio` 核对 track 采样率与线程 flags（方法见 `direct-audio-output.md` §6）。
- `SAFE_DIRECT` 重点验证：384 kHz 不再卡死（cap 生效）且 96/192 kHz 原样；5.1 文件立体声设备可听。
- 蓝牙：确认 BT 连接下建议提示出现，且 SYSTEM_DEFAULT + offload 可正常 gapless。

---

## 6. 风险与权衡

1. 组合从 3 增至 4 个合法态，测试矩阵变大；每个切换都 rebuild player（短暂中断）。
2. `SAFE_DIRECT` 是新组合（cap + 降混 + 独立 session），需真机验证后再放推荐位。
3. 非法组合的 UI 禁用与解释要足够清晰，避免用户困惑。
4. 自动选档（§4.2）成本高，本期不做；蓝牙提示优先。
5. 蓝牙 codec 选择在系统开发者选项，App 无法强制切换——只能提示，避免越权/兼容问题。

---

## 7. 分阶段落地（v3 确定）

| Phase | 内容 | 备注 |
| --- | --- | --- |
| **0** | 纠文档 + 中文 DIRECT/PCM_FLOAT 文案（已在 2026-09-12 完成部分） | 零风险 |
| **1** | §5.1–5.4 双正交 + SAFE_DIRECT（保留降混）+ 迁移 + 预设 | **推荐下一步** |
| **2** | 蓝牙感知提示；输出模式说明强化；可选开发者诊断（是否 direct） | 你日常蓝牙，优先级高 |
| **3** | 24-bit AudioTrack 探测选项；cap 支持 24-bit；系统音效冲突提示 | 可选 |
| **远期** | AAudio/Oboe USB exclusive；按内容自动选档 | 单独立项 |

### 待确认（实施前）

- **C**：智能默认下 offload 保持现状「可用则开 + 卡死回退」？——建议**保持**（蓝牙/息屏更省电）。
- **D**：自动选档是否完全不做？——建议路线图即可，Phase 2 只做蓝牙提示。
- **E**：Phase 1 完成后是否立刻做 Phase 2？——你主听蓝牙，建议紧接着做。

---

## 8. 参考

- `docs/direct-audio-output.md`：直通原理、三模式现状、验证方法（§6）、历史参考实现对比（§7）、**听音场景定位（§9）**。
- `docs/media3-1.11.0-upgrade.md`：当前 Media3 1.11.0 音频 API 兼容结论。
- Media3 字节码核查结果见 `direct-audio-output.md` §8。
