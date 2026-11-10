# 首页 AI：从「纯文本清单」到「生成即听」+ Serendipity

状态：**设计已定稿**，分 4 个 Phase 实施。

---

## 0. 已确认的决策

| # | 决策 |
|---|---|
| 1 | 去掉 min/max 两个数字框，改成一句话描述 + 灵感 chips + 长度 chips（15/25/40）；采样模式/条数收进「高级」 |
| 2 | 生成完**不弹命名框**，默认名 `AI Mix · 09-10 18:03`，名字可事后改 |
| 3 | 结果卡**可编辑**：重命名、删单曲、换一首、再来 10 首、重新生成 |
| 4 | 起播用**迷你播放器**，不强制全屏播放页 |
| 5 | 首页新增「最近生成」横滑，位置＝AI 横幅正下方（Your Mix 之上，自然也在最近播放之上） |
| 6 | 结果卡双按钮：**「播放」（主）+「仅保存」**，不做无脑自动播放 |
| 7 | AI 横幅新增第二个按钮：**Serendipity** —— 零输入，按此刻情境生成（见 §4） |
| 8 | 命名本地化：中文「**不期而遇！**」/ 英文「**Serendipity!**」（都带叹号），给一个区别于普通入口的**特殊按钮**；图标用 `AutoFixHigh`（魔法棒），**不用 `AutoAwesome`** |
| 13 | 按钮位置（2026-09-12 定）：与 `Start` 胶囊**并列同一行，置于 `Start` 之前**；同日把 `Start` 更名为中文「**说一句**」/ 英文「**Describe**」 |
| 9 | Serendipity 产物用**情境短语**命名（`Rainy Evening · 19:20`） |
| 10 | **权限即开关**：经纬度/步数由系统权限决定，没授权就不进提示词；**不做**四个独立信号开关 |
| 11 | 天气**只精确到城市**，且优先用用户手动填的城市（免任何权限） |
| 12 | 首页巨型「为你\n推荐」（64sp / 256dp）降级重设计，别抢 AI 的风头（见 §5） |
| 14 | **Serendipity 交互（2026-09-12 定，覆盖 #7 的「直接生成」）**：点按钮 → 弹 sheet 显示情境 chips + **本地合成**的提示词（可编辑）→「生成」→ 走现有结果卡。**默认不调 AI 写词**（省一次往返）；另设可选按钮「让 AI 换个说法」，点了才发第二次小请求 |
| 15 | **AI 歌单存完整提示词**（2026-09-12 定）：新增 `playlists.ai_prompt` 列（DB v9 + `MIGRATION_8_9`）。歌单名仍是短标识符，**详情页标题下方用滚动文本**显示提示词 —— 名字要被搜索/导出/当作队列名，不能塞长文本 |

---

## 1. 现状与目标

### 现状（OSS）

- 入口：`HomeScreen` → `AiGenerateEntryCard` → `DescribePlaylistDialog`（`presentation/components/PlaylistCreationDialogs.kt:232`）。
- 生成：`PlaylistViewModel.generateAiPlaylistPreview()` → `aiPlaylistGenerator.generate()`。
- 结果：名称框 +「N 首」+ `LazyColumn` 两行纯文本，**只保存、不播放**，保存后即关闭。

问题：结果不可点、不可听、不可改、不可再生成；AI 的价值在最后一步断掉。

### china-only 的做法（参照，仅借鉴思想）

`AiStateHolder.generateAiPlaylist()`（`AiStateHolder.kt:119`）一条管线两个出口：

1. 候选池 `dailyMixManager.generateDailyMix(limit = 120)`（非全库）；
2. AI 返回 `List<Song>` 后**不展示清单**，sheet 只做状态动画，然后二选一：
   - `saveAsPlaylist = true`：`resolveAiPlaylistName()` 从 prompt 抠 2 个关键词（抠不出回退成
     "Workout Mix"/"Chill Vibes"），重名加序号 → `createPlaylist(..., isAiGenerated = true)` → toast → 关。
   - `saveAsPlaylist = false`：`setDailyMixSongs()` → `playSongs(songs, first(), "AI: $prompt")` → 开播放页 → 关。

**核心：生成即消费，中间没有「给你看个清单」这一步。** 但 china-only 的结果是只读的，不可编辑 —— 我们比它多一层（§2 结果层）。

---

## 2. 主流程（普通 AI 生成）

```
AI 横幅 → 一句话描述 + 长度 chips → 生成中（可取消）
      → 自动存为播放列表（AI Mix · 09-10 18:03，source = "AI"）
      → 结果卡：播放（主） / 仅保存
      → 播放 → 迷你播放器起播，队列 = 该播放列表
```

### 输入层

- 主输入：一句话描述 + 灵感 chips（深夜码字 / 通勤提神 / 周末打扫 / 睡前放松）。
- 长度：单排 chips **15 / 25 / 40**（替掉 min/max）。
- 「高级」折叠区保留 `AiLibrarySampleMode`（MOST_PLAYED / RANDOM）与 sample size（50/100/200/300/500）。
- 未配置 provider 时：卡片副标题提示并跳 AI 设置；**离线 `NlpPlaylistGenerator` 始终可用**，
  两种情况共用同一条结果管线。

### 结果层

| 能力 | 说明 |
|---|---|
| 播放（主按钮） | `playSongs(songs, first(), queueName = name, playlistId = id)` |
| 仅保存 | 只落库，不动当前播放 |
| 重命名 | 点标题即可改，默认时间戳名 |
| 删单曲 / 换一首 | 换歌从同一 prompt 的候选池补 |
| 再来 10 首 | 复用 prompt 追加 |
| 重新生成 | 保留上次 prompt（Serendipity 则是重掷一次） |

---

## 3. 首页信息架构

| 顺序 | 区块 | 备注 |
|---|---|---|
| 1 | **AI 横幅**（描述输入 + `Serendipity!` 特殊按钮） | 首屏视觉主角，见 §4 |
| 2 | **最近生成** 横滑（最近 3 条 AI Mix） | 新增，点卡片直接播 |
| 3 | **为你推荐**（小标题 + 本地副标题）→ AlbumArtCollage | 标题降级，见 §5 |
| 4 | Daily Mix 横滑 | 同属本地推荐，可选合并，见 §5 |
| 5 | 最近播放 | 现状 |

「最近生成」数据：`DataStore` 存最近 N 条 `{ playlistId, name, prompt/情境标签, createdAt, songCount, coverSongId }`，
**不碰 Room**。

---

## 4. Serendipity —— 零输入情境歌单

### 命名与本地化

| 语言 | 标题 | 副标题 |
|---|---|---|
| 英文（`values/`） | `Serendipity!` | A mix for right here, right now |
| 中文（`values-zh-rCN/`） | 不期而遇！ | 此刻，此地，这些歌 |

理由：一键、零输入、结果不可预期，「不期而遇」是最准的描述；比 "Context Mix" 这种工程名有温度。
叹号是功能人格的一部分，英文中文都保留。

其他语言先留英文（按仓库惯例，翻译留给翻译流程）。

### 按钮设计（要「特殊」）— 2026-09-12 定稿

采用**方案 A：胶囊 + 魔法棒**（对比过骰子圆钮、星芒圆钮两案：骰子有赌具联想且无音乐温度，
星芒含义偏抽象，均不选）。普通入口是一句话描述的输入框，Serendipity 必须一眼不同：

| 项 | 定稿 |
|---|---|
| 图标 | `Icons.Rounded.AutoFixHigh`（魔法棒）—— **刻意不用 `AutoAwesome`**：后者已是 AI 家族的通用图标（横幅左侧、最近生成卡片、设置 AI 分类都在用），复用会消解「特殊按钮」的差异感 |
| 形状 | 胶囊，**半透明白底**（`onPrimary.copy(alpha = 0.18f)`，与现有 `Start` 胶囊同款容器规则；横幅本身已是 `primary → tertiary` 渐变，高饱和实心容器在渐变上会糊成一团） |
| 文案 | `✦ 不期而遇！` / `✦ Serendipity!`，叹号保留（是功能人格的一部分） |
| 位置 | AI 横幅按钮行内，**`Start` 胶囊之前**（左），形成「交给运气 → 交给你说」的阅读顺序 |
| 图标差异 | 「**不期而遇！**」= `Icons.Rounded.AutoFixHigh`（魔法棒）；「**说一句**」= `Icons.Rounded.AutoAwesome`（星芒）—— 两者图标不同，且不期而遇在左，一眼可辨。<br>**已知取舍**：横幅左侧引导图标也是 `AutoAwesome`，同一张卡内出现两次星芒（15dp vs 28dp）。实测若觉得重复，把「说一句」换成铅笔类图标即可（改动只有一行） |
| 动画 | **静止时完全不动**（省电）；仅生成中做 shimmer 扫光 / 魔法棒轻微旋转，生成结束即停 |
| 点击后 | **打开 Serendipity sheet**（不直接生成）：显示情境 chips + 可编辑提示词 →「生成」→ 复用现有生成中/结果态。详见下方「Serendipity 交互流程」 |
| 未配置 AI | 按钮置灰；或与主按钮一致跳 AI 设置（跟随 `isAiConfigured` 的现有分支，不额外加态） |

**横幅按钮行布局（宽度约束，重要）**

标题列 + 两个文字胶囊**无法真正同行**：360dp 屏上横幅内部可用宽约 292dp，
`[28dp 图标] + 14 + 标题列 + 12 + 两个胶囊` 里，两个胶囊（labelMedium ≈ 98dp + 56dp + 8 间距 = 162dp）
会把标题列压到 ~78dp，`AI Playlist` 会被强制折行。故定稿为**两行**：

```
┌──────────────────────────────────────────────┐
│  ✨  AI 歌单                                  │
│      描述你想听的音乐                          │
│                        [✦ 不期而遇！] [说一句] │  ← 按钮行右对齐，Serendipity 在左
└──────────────────────────────────────────────┘
```

- 按钮行右对齐（`Spacer(Modifier.weight(1f))` 起头），两个胶囊 `Arrangement.spacedBy(8.dp)`。
- **副作用（正向）**：主胶囊从第一行移走后，标题列拿回全部 ~250dp，
  `Describe the music you want to hear` 由两行折行变为**单行**；因此**不需要** `maxLines` 或缩字号。
- **卡片高度**：`heightIn(min = 76.dp)` → 约 **104dp**（两行内容 + 12dp 行间距），
  实现时把 min 提到 ~100dp 或直接放宽（内容自然撑开，不要再写死）。
- 若后续实测 360dp 下仍觉拥挤，降级顺序：① 胶囊内边距 14→10dp；② 文案「不期而遇！」→「不期而遇」；
  ③ 退回纯图标圆钮（方案 C 形态）。

**字符串改动**

| key | 英文 | 中文 | 说明 |
|---|---|---|---|
| `ai_serendipity_button`（新） | `Serendipity!` | `不期而遇！` | ✦ 前缀在 composable 里用图标实现，不写进字符串 |
| `ai_playlist_entry_action`（改） | `Start` → `Describe` | `说一句` | 顺带补 zh-rCN —— 该组 `ai_playlist_entry_*` 字符串**当前只有英文**，中文用户看到的是英文回退，属既有缺口，本次一并补上 |
| `playlist_ai_prompt_label`（新） | `AI prompt` | `AI 描述` | 歌单详情页提示词卡片的标签 |

### Serendipity 交互流程（2026-09-12 定稿）

```
点「不期而遇！」
   ↓  采集（本地，无网络开销）
时间/天气(城市)/步数  ── 拿不到的信号直接不显示，不留占位符
   ↓  本地模板合成（零 AI 调用、可离线）
「周五 19:20，傍晚。小雨 14°C，上海。今天约 8,400 步，挺活跃。挑些贴合此刻的歌。」
   ↓  sheet 展示：情境 chips + 可编辑提示词 + 两个动作
[换一句]（本地重新随机句式，不重新采集信号）
[让 AI 换个说法]（可选，第二次小请求；thinking 固定关闭，输出上限写死在代码里）
   ↓  [生成]
现有 AI 管线（1 次调用）→ 生成中 → 结果卡（播放 / 仅保存）
```

**为什么默认不调 AI 写词**：本地模板 + 句式随机化已能做到"每次不一样"，AI 此时只增益文采，
却让冷启动路径从 1 次往返变 2 次、token 翻倍。把它降级为可选按钮，两种诉求都满足。

**额外收益**：用户在 sheet 里能直接看到"这次没有天气/没有步数"，把原本静默的降级变成可见 ——
比文档早先设计的"缺什么少一句、用户不知情"透明得多。

**缓存策略**：Serendipity 路径**不读不写 `ai_cache`**（RANDOM 采样 + 随机描述 → 命中率≈0，
写进去只污染且无限增长）；但 `ai_usage` 两次请求都要记，promptType 分开
（`serendipity_prompt` / `serendipity_playlist`），否则用量报告失真。


### 信号来源

| 信号 | 获取方式 | 权限 / 网络 | 缺失时 |
|---|---|---|---|
| 时间 | 系统时钟 + 星期 + 时段（morning/afternoon/evening/late night） | 无 | 始终可用 |
| 天气 | **Open-Meteo**（免费、无 API key、CC-BY 4.0、无 GMS）；**粒度＝城市**，三级取值：① 用户手填的城市（AI 设置页「天气城市（可选）」，经 Open-Meteo geocoding 转坐标）② `ACCESS_COARSE_LOCATION` 粗定位 → `Geocoder` 反查城市名 ③ 放弃 | 无 / INTERNET | 提示词里不出现天气 |
| 步数 | `SensorManager.TYPE_STEP_COUNTER`（按需采样一次，非常驻监听）+ 当日基线 + 活跃度分档 | `ACTIVITY_RECOGNITION` | 提示词里不出现步数 |
| 随机 | `AiLibrarySampleMode.RANDOM` + `sample size = 100` | 无 | 默认开 |

**WMO 天气码收敛为 6 档**：`CLEAR`(0,1) / `CLOUDY`(2,3) / `FOG`(45,48) / `RAIN`(51–67,80–82) /
`SNOW`(71–77,85,86) / `THUNDERSTORM`(95,96,99)。细分「毛毛雨 / 阵雨」不会改变选歌结果，
却要多出两组翻译字符串 —— 收敛是对成本与收益的判断，不是遗漏。

**步数采样方式**：只在需要时注册 `TYPE_STEP_COUNTER` 监听、拿到第一个事件即注销
（`withTimeoutOrNull(2s)` 兜底），不做常驻监听。代价是**读不到「今天第一次读取之前」的步数**
（基线在当日首次读取时才建立），换来的是这个装饰性数值不耗电。

**经纬度永不进入提示词**：位置只用来换一个城市名，且城市名也只是作为天气查询的输入。
提示词里出现的最细粒度是城市（如 "in Shanghai"），**没有坐标、没有街道、没有步数原始值**
（步数还会先向下取整到百位）。

### 关键设计

1. **覆盖采样参数**：Serendipity 强制 `RANDOM` + `100`，忽略用户默认设置 —— 每次结果都不同，
   且 RANDOM 保证冷门曲目也有机会（固定排序会永久排除截断线之后的歌）。
2. **情境合成成本地一段话，再喂给现成管线**（生成层零改动）：
   ```
   Friday 19:20, evening. Light rain, 14°C, in Shanghai. Around 8,400 steps today: an active day.
   Pick songs that fit this exact moment.
   ```
   没授权天气就是 `Friday 19:20, evening. Pick songs that fit this exact moment.`，
   没授权步数就整句不出现 —— **缺什么就少一句，不补占位符、不猜测**。
   有 provider → `AiPlaylistGenerator.generate(description)`；
   没有 → `NlpPlaylistGenerator.generate(description)` 本地打分。**除天气外全本地，无网也能用。**
3. **权限即开关，不做四个独立开关**：
   - 原提案是「时间 / 地点 / 天气 / 步数」四个独立开关。**取消** —— 用户在系统权限对话框里
     已经表达过意愿了，再设一层开关是重复决策，还要多走设置注册流程。
   - 规则很简单：**授权＝参与，拒权＝不参与**，被拒也不做二次纠缠。
   - AI 设置页只保留**一个**输入框：「天气城市（可选）」—— 填了就用它，不填才考虑定位。
     这让用户**完全不给定位权限也能有天气**。
4. **命名**：Serendipity 的产物用情境短语而非纯时间戳，辨识度更高 ——
   取「天气 · 时段 · 时间」三段用 ` · ` 连接：`Rain · Evening · 19:20` / `小雨 · 傍晚 · 19:20`。
   取不到天气就是 `Evening · 19:20` / `傍晚 · 19:20`。
   **与早期草案的差异**：草案写的是 `Rainy Evening · 19:20`（天气当形容词、和时段拼成一个词组）。
   实现改成三段并列，因为「形容词 + 时段」的拼法要求每种语言各备一套连接规则
   （英文要空格、中文不能有），而 ` · ` 并列在两种语言下都读得通，翻译量也少一半。

### 权限改动（AndroidManifest）

- 现有 `ACCESS_FINE_LOCATION` 带 `maxSdkVersion="30"`（旧蓝牙扫描用），**不能复用**；
  新增 `ACCESS_COARSE_LOCATION`（无 maxSdk，城市级足够）。
- 新增 `ACTIVITY_RECOGNITION`。
- 两者均**运行时**按需申请，未授权不影响其他功能；**首次使用 Serendipity 时**才申请，不在启动时要。

---

## 5. 首页「为你推荐」重设计

### 现状与问题

`HomeScreen.kt:681` 的 `YourMixHeader`：256dp 高的大区块，标题 `home_your_mix_title`
（中文「为你\n推荐」）用 `64sp / weight 760` 的可变字体，右下角再挂一个大号 shuffle FAB。
它下面才是 400dp 的 `AlbumArtCollage`，再下面还有一个 `DailyMixSection`。

问题：

1. **字号太强**：64sp 的「为你推荐」是首页最大的文字，把上面 AI 横幅的风头全抢了。
2. **语义含糊**：它和下面的 Daily Mix **都是本地推荐**
   （`DailyMixManager` 只注入 `Context` + `EngagementDao`，**零网络零 AI**，100% 本地评分），
   但界面上分成两块、只有一块有巨型标题，用户看不出两者关系。
3. 首页现在有三块推荐相关内容（Your Mix 拼贴 / Daily Mix 横滑 / 最近播放），层级混乱。

### 推荐方案：标题降级 + 拼贴保留（不去内容，只去「吼」）

| 元素 | 现在 | 改后 |
|---|---|---|
| 标题字号 | 64sp / weight 760 / 双行（`home_your_mix_title`） | `titleLarge` + `SemiBold` / **单行**，与「最近播放」section 标题完全同款 |
| 区块高度 | 256dp | **wrapContent**（约 72dp：标题 + 副标题两行） |
| 副标题 | 硬编码英文 `"Today's Mix for you"`（`HomeScreen.kt:231`） | **本地化小字** `home_your_mix_subtitle`：本地生成 · 基于你的听歌习惯 |
| Shuffle 按钮 | `LargeExtendedFloatingActionButton`（68dp 圆角 / 36dp 图标，压在右下角） | **56dp `FilledTonalIconButton`**，与标题同排右对齐（同「最近播放」的 `FilledIconButton` 布局） |
| AlbumArtCollage | 400dp 保留 | **保留**（它是内容不是文字，是首页的视觉亮点，不抢焦点） |

效果：首屏第一眼是 AI 横幅 → 最近生成 → 才是推荐，层级自然；推荐区靠拼贴的质感而不是字号取胜。

### 实现细节（Phase 0）

- **标题复用现有字符串**：`home_your_mix_title` 在 13 个语言里都带显式换行（`Your\nMix` / `为你\n推荐`）。
  不新增字符串、不改 13 个文件，渲染时 `.replace('\n', ' ')` 折叠成单行 —— 一行代码保住全部既有翻译。
- **新增一个字符串** `home_your_mix_subtitle`（`values/strings_screens.xml` 英文 + `values-zh-rCN` 中文）。
- **删除** `rememberYourMixTitleStyle()`（64sp 可变字体样式）与随之失效的 import
  （`ExperimentalTextApi` / `TextStyle` / `Font` / `FontFamily` / `FontVariation` / `sp` / `LargeExtendedFloatingActionButton`）。
  注意 `painterResource`、`FontWeight`、`AbsoluteSmoothCornerShape`、`ExperimentalMaterial3ExpressiveApi` **仍被别处使用，保留**。
- `YourMixHeader` 的 `song` 参数与 `HomeScreen.kt:231` 的 `yourMixSong` 一并删除（硬编码英文，是本次改动产生的无用代码）。

### 可选（更彻底）：合并为一个推荐区

把「为你推荐」标题同时作为 Your Mix 拼贴与 Daily Mix 横滑的 section 头，两者变成一个区：

```
为你推荐                        本地生成 · 基于你的听歌习惯
[ 400dp 专辑拼贴 ]
[ Daily Mix 横向列表 ]
```

好处：明确「这些都是本地推荐」，少一个标题、少一次重复。
代价：改动更大，`DailyMixSection` 要去掉自带标题。**建议先做标题降级，真机看效果后再决定要不要合并。**

---

## 6. 技术要点

- **跨 VM 通信**：`PlaylistViewModel` 只负责「生成 + 落库」，通过 `SharedFlow<AiMixReady>(songs, playlistId, name, prompt)`
  抛事件，`HomeScreen` 收到后调 `playerViewModel.playSongs(...)`。**不要让 PlaylistVM 持有 PlayerVM。**
- **`playSongs` 已支持 `playlistId`**（`PlayerViewModel.kt:2752`），落库后带 id 播放，队列与播放列表天然一致。
- **避免 Room migration**：OSS `Playlist` 模型**没有** `isAiGenerated`（schema 仅到 v6）。
  用现成的 `source` 字段标 `"AI"`（Serendipity 标 `"AI_SERENDIPITY"`），零迁移。
- **失败态复用** `describeAiFailure()`（key / quota / model / rate-limit / network / parse）+ 重试。
- **版权红线**：只借鉴「生成即消费」的交互思想，Compose 控件 / VM 接线 / strings 全部 OSS 侧自写。

---

## 7. 实施阶段

| Phase | 内容 | 状态 |
|---|---|---|
| **0** | 首页标题降级：`YourMixHeader` 64sp → section 标题 + 本地化副标题 + 56dp shuffle 图标按钮 | **已完成**（待真机确认） |
| **1** | 结果管线改造：新 `AiMixSheet`（描述 + chips + 生成中 + 结果卡）、`PlaylistViewModel` 加 `SharedFlow<AiMixSaved>`、落库（`AI Mix · MM-dd HH:mm`，`source="AI"`）、结果卡双按钮 + 编辑能力、HomeScreen 接线 | **已完成**（待真机确认） |
| **2** | 「最近生成」横滑：**不新增存储**，从现有 playlists 流派生（`source="AI"` + `createdAt` 排序）+ `RecentAiMixesSection` + 首页插入 AI 横幅下方、Your Mix 之上 | **已完成**（待真机确认） |
| **3** | Serendipity：情境采集器（时间/天气(城市)/步数）、情境→description 合成、**横幅双胶囊按钮行（不期而遇！+ 说一句）**、强制 RANDOM+100、情境式命名 | **已完成**（3.1–3.3、3.4 决定不做、3.5–3.14 全落地）：编译 + 单测通过，真机验证待做 |
| **4** | 权限与隐私：manifest 加 `ACCESS_COARSE_LOCATION` / `ACTIVITY_RECOGNITION`、首次使用时申请、AI 设置页加「天气城市」输入框（不设四个开关）、字符串（英文 + zh-rCN）、PRIVACY.md 更新 | 待开始（依赖 3） |

每 Phase 结束后跑 `:app:assembleDebug`，UI 改动装真机验证。

### Phase 0 步骤

- [x] 0.1 文档细化（本节）
- [x] 0.2 新增 `home_your_mix_subtitle` 字符串（英文 + zh-rCN）
- [x] 0.3 重写 `YourMixHeader`：单行 `titleLarge` + 副标题 + 56dp `FilledTonalIconButton`
- [x] 0.4 删除 `rememberYourMixTitleStyle()`、`yourMixSong` 及失效 import
- [x] 0.5 `assembleDebug` 通过（BUILD SUCCESSFUL）；真机效果待确认

### Phase 1 步骤

- [x] 1.1 `PlaylistViewModel`：新增 `AiMixSaved` 事件 + `aiMixSaved: SharedFlow` + `saveAiMix()`；
      `generateAiPlaylistPreview(description, maxLength)` 加长度参数；`DEFAULT_AI_MIX_LENGTH = 25`、`AI_MIX_SOURCE = "AI"`
- [x] 1.2 新增 `AiMixSheet.kt`：输入态（一句话 + 灵感 chips + 长度 chips + 高级折叠）
      / 生成中 / 结果态（可改名 + 可删单曲 +「播放」主按钮 +「仅保存」+「重新生成」）
- [x] 1.3 字符串 `ai_mix_*`（英文 + 新建 `values-zh-rCN/strings_ai.xml`）
- [x] 1.4 `HomeScreen` 接线：sheet 替换 `DescribePlaylistDialog`，收集 `aiMixSaved` →
      `playerViewModel.playSongs(songs, first, name, playlistId)`
- [x] 1.5 `assembleDebug` 通过；真机验证待做

**实现备注**
- `SampleModeDropdown` / `SampleSizeDropdown` 由 `private` 改为 `internal`（同一 module 内复用，
  不复制代码）；`DescribePlaylistDialog`（Library 仍在用）**未改动**。
- 结果列表用 `mutableStateListOf` 做本地可编辑副本，删单曲不与 VM state 打架。
- 「仅保存」也走 `saveAiMix(startPlayback = false)`，统一由 `aiMixSaved` 回吐 toast。

### Phase 2 步骤

- [x] 2.1 **不新增存储**：生成的 mix 就是 `source = "AI"` 的普通播放列表，
      `Playlist` 自带 `source` / `createdAt` / `songIds` → 在 `PlaylistViewModel`
      加派生流 `recentAiMixes: StateFlow<List<Playlist>>`（filter → sortByDescending(createdAt) → take(3)）
- [x] 2.2 新增 `RecentAiMixesSection.kt`：`LazyRow` + 152dp 卡片（`AutoAwesome` 图标 / 名称 2 行 / 曲目数）
- [x] 2.3 `HomeScreen` 插入（AI 横幅下方、Your Mix 之上），空列表时不渲染该 item
- [x] 2.4 点卡片 → `getPlaylistsWithSongs(listOf(mix.id))` 解析歌曲 →
      `playSongs(songs, first, mix.name, mix.id)`（复用 Phase 1 起播口径：起播不抢全屏）
- [x] 2.5 字符串 `home_recent_ai_mixes_title`（英文 + zh-rCN）
- [x] 2.6 `assembleDebug` 通过；真机验证待做

**设计修正（相对原计划）**
- 原计划「DataStore 存最近 3 次生成的 playlistId + prompt」**取消**。
  `playlistPreferencesRepository.userPlaylistsFlow` 已经带着 `source` 和 `createdAt`，
  再存一份历史等于两份真相，还要处理「播放列表被删了但历史还在」的脏数据。
  直接从现有 playlists 流派生，零存储、零同步、零脏数据。
- 代价：`prompt` 不入库，所以「最近生成」卡片不显示原始提示词（只有 mix 名称）。
  名称本身已经承载了意图（`AI Mix · 09-10 20:24`），够用；真需要 prompt 再单独加字段。

### Phase 3 步骤

- [x] 3.1 **横幅按钮行重构**（`AiGenerateEntryCard.kt`）：改为一行标题 + 一行按钮（右对齐）；
      新增 `onSerendipityClick: (() -> Unit)? = null` 参数 —— 为 `null` 时按钮不渲染、布局**退回原单行内联胶囊**
      （当前唯一调用点是 `HomeScreen`，该保留让未来其他入口零改动）。
      卡片 `heightIn(min)` 76dp → **100dp**（仅双按钮时）。
- [x] 3.2 **图标与容器**：抽出私有 `ActionPill`（胶囊、`onPrimary @ 18%` 白底、labelLarge）；
      「不期而遇！」= `Icons.Rounded.AutoFixHigh` + **24% 白底**（比普通胶囊亮一档，视为主按钮），
      「说一句」= `Icons.Rounded.AutoAwesome`（星芒，15dp）+ 18% 白底。
      注意：星芒胶囊**自身不设 onClick**（整卡已是描述入口，点击穿透到外层卡），只有不期而遇是独立可点按钮。
- [x] 3.3 **字符串**：新增 `ai_serendipity_button`（英文 `Serendipity!` / 中文 `不期而遇！`）；
      `ai_playlist_entry_action` 改 `Describe` / `说一句`；补齐整组 `ai_playlist_entry_*` 的 zh-rCN
      （title/subtitle/unconfigured/action 四条，原先只有英文）。
- [x] 3.4 **动画**：**未做，且决定不做**。原计划「生成中 shimmer 扫光 / 魔法棒微旋转」需要给
      `AiGenerateEntryCard` 的胶囊加一个常驻 `rememberInfiniteTransition` 分支，而按钮只在**打开 sheet
      之前**可见 —— sheet 一开，生成态在 sheet 里，胶囊根本看不到，等于为一个看不见的状态付动画开销。
      改为沿用现有 `LoadingIndicator`（sheet 内已有），按钮本身静止。保留「不做常驻动画」这条纪律。
- [x] 3.5 **情境采集器**：`data/ai/serendipity/SerendipityContextCollector.kt`
      —— 时间（`ZonedDateTime` + `SerendipityTimeOfDay`）、城市（① 用户手填→Open-Meteo geocoding
      ② `ACCESS_COARSE_LOCATION` 粗定位→`Geocoder` 反查 ③ 放弃）、天气（Open-Meteo `current`，
      WMO 码收敛为 6 档）、步数（`TYPE_STEP_COUNTER` 采样一次 + 当日基线）。
      **每一条信号都可缺失**：拿不到就保持 `null`，整句省略，不补占位符。
- [x] 3.6 **情境→description 合成**：`SerendipityPromptComposer`（纯 object，零 AI 调用、可离线）
      拼出英文一段话喂给现有管线，生成层零改动。
- [x] 3.7 **强制采样参数**：`AiPlaylistGenerator.generateSerendipity()` 固定
      `RANDOM` + `SERENDIPITY_SAMPLE_SIZE = 100`，忽略用户默认设置。
- [x] 3.8 **情境式命名**：`{天气} · {时段} · {HH:mm}`（如「雨 · 傍晚 · 19:20」/ `Rain · Evening · 19:20`），
      取不到天气退化为 `{时段} · {HH:mm}`；`source = PlaylistViewModel.SERENDIPITY_SOURCE`
      （`AI_SERENDIPITY`），同时把 `recentAiMixes` 的过滤放宽为「两种来源都收」——
      否则 Serendipity 生成的歌单会从「最近生成」里凭空消失。
- [x] 3.9 **接线**：`HomeScreen` 分流 `openAiEntry(serendipity: Boolean)`；点「不期而遇！」先申请
      `ACTIVITY_RECOGNITION` + `ACCESS_COARSE_LOCATION`（`RequestMultiplePermissions`，**首次使用才申请**），
      回调里无论授权与否都照常采集 —— 拒绝只意味着少两句。
- [x] 3.10 `assembleDebug` 通过 + 单测通过（`SerendipityPromptComposerTest`）。
      **真机验证待做**（含 360dp 窄屏按钮行、天气/步数 chips 是否出现）。
- [x] 3.11 **Serendipity sheet**：**没有新建 `SerendipitySheet.kt`**，而是给 `AiMixSheet` 加了一个
      可空的 `serendipity: SerendipityUiState?` —— 复用它已有的「生成中 / 结果 / 播放 / 仅保存 / 错误」
      四态与全部回调，Serendipity 只替换输入态并隐藏采样控件（那条路径的采样是强制的，显示出来就是撒谎）。
      多加出的参数：`serendipityChips` / `serendipityDefaultName` / `onReshuffleSerendipity` /
      `onRephraseSerendipity`，全部有默认值，其他调用点零改动。
- [x] 3.12 **本地提示词模板**：`SerendipityPromptComposer`（5 种句式变体 × 信号子集），
      纯函数、`(context, variant)` 决定输出，已单测（全信号 / 只时间 / 有天气无城市 / 变体互异 /
      越界回绕 / 步数取整 / WMO 映射 / 时段分桶，共 10 例）。
- [x] 3.13 **可选 AI 润色**：`AiHandler.generateText()`（无 library sample、不读写 `ai_cache`、
      `ai_usage` 记 `serendipity_prompt`、thinking 固定 `false`）；失败保留本地句 + 一行提示。
- [x] 3.14 **缓存策略**：`AiHandler.generate(..., promptType, useCache)` 把缓存开关提到参数上；
      播放列表路径传 `useCache = false`、`promptType = serendipity_playlist`，
      `ai_usage` 两次调用分开记账（`serendipity_prompt` / `serendipity_playlist`）。

> 依赖：Phase 3.5/3.6 依赖 Phase 1 的生成管线（已完成）。
> **遗留**：3.4 明确不做（见上，代价大于收益）。

---

## 9. AI 歌单的提示词存储与展示（2026-09-12 实施）

### 为什么不在名字里

歌单名是被 6+ 处消费的**标识符**，长文本会直接破坏这些位置：

| 消费点 | 位置 |
|---|---|
| 搜索 | `MusicRepositoryImpl:562`（`playlist.name.contains(query)`）|
| 导出文件名 | `sanitizeFileName` —— `PlaylistDetailScreen:932` / `PlaylistViewModel:1134/1141/1150` |
| 起播队列名 | `PlaylistDetailScreen:436` → `playSongs(queueName = name)` 进播放器 |
| toast / 列表 / 详情标题 | `HomeScreen:507`、Library 列表、详情页大标题 |

所以：**name 保持短**（`AI Mix · 09-12 20:24` / `小雨傍晚 · 23:20`），提示词单独存。

### 数据层（已实施）

| 文件 | 改动 |
|---|---|
| `data/model/PlayList.kt` | `Playlist` 末尾新增 `aiPrompt: String? = null`（放末尾 → 所有位置参数调用点不受影响）|
| `data/database/PlaylistEntity.kt` | `@ColumnInfo(name = "ai_prompt")` + 两个映射函数 |
| `data/database/Migrations.kt` | `MIGRATION_8_9`：`addColumnIfMissing("playlists", "ai_prompt", "`ai_prompt` TEXT")` |
| `data/database/PiscesPlayerDatabase.kt` | `version = 9`（schema `9.json` 已随编译导出）|
| `di/AppModule.kt` | `addMigrations(…, MIGRATION_8_9)` |
| `data/preferences/PlaylistPreferencesRepository.kt` | `createPlaylist(…, aiPrompt: String? = null)` |
| `presentation/viewmodel/PlaylistViewModel.kt` | `saveAiMix` 落库时带 `aiPrompt = prompt`（此前 `prompt` 只进事件、不入库）|

**备份兼容性**：`PlaylistsModuleHandler` 用 Gson 反序列化 `List<Playlist>`，还原走
`replaceAllPlaylists` → `toEntity()`，新增可空字段天然双向兼容（旧备份 → null；新备份被旧版读 → 忽略），
备份模块**无需改动**。

### 展示（已实施）

`PlaylistDetailScreen` 歌曲列表顶部插入一条 `item(key = "ai_prompt")`，仅当
`currentPlaylist?.aiPrompt` 非空时渲染 `AiPromptBanner`：

- 标签行：`AutoAwesome`（14dp，tertiary 色）+ `playlist_ai_prompt_label`（`AI prompt` / `AI 描述`）。
- 正文：`AutoScrollingTextOnDemand`（`MarqueeText.kt:35`，`expansionFractionProvider = { 1f }`
  → 常驻可滚动；`gradientEdgeColor` 传卡片底色做渐变遮罩），**溢出即滚动**，不截断。
- 容器：`surfaceContainerLow` + 16dp 圆角，比列表底（`surfaceContainerHigh`）低一档，读作卡片。

### 后续可选

- 「最近生成」卡片（`RecentAiMixesSection`）改用提示词前若干字符做副标题 —— 现在有字段了，一行取用。
- 「重新生成」可读回 `aiPrompt` 直接复用原描述。

---

## 8. 首页三区块分区 + 统计界面统一（2026-09-10 晚追加）

### 背景

首页有 AI 横幅 / 本地推荐（拼贴 + 每日精选）/ 最近播放三块，但各用一套互不相干的容器、
图标和标题规则，靠 24dp 行间距硬分，看起来「融在一起」。

### 方案：统一分区语法，不靠分隔线

**原则：区分靠「身份色」，不靠分隔线。** 三区块各领一个色相，只用在 section 头的图标块
和少量描边上；标题结构完全一致。

| 区块 | 身份色 | 图标 | 含义 |
|---|---|---|---|
| AI | 紫（`primary → tertiary` 渐变，横幅原色保留） | `AutoAwesome` | 生成出来的 |
| 本地推荐 | 青绿（`tertiaryContainer` 系） | `queue_music` | 设备算出来的 |
| 最近播放 | 暖橙（`secondaryContainer` 系） | `History` | 已经发生的 |

### 改动清单

1. **新组件 `HomeSectionHeader.kt`**：统一头（34dp 图标块 + 标题 + 副标题 + 可选动作胶囊）。
   图标块用 `AbsoluteSmoothCornerShape`（12dp），与 AI 横幅圆角同族。
2. **`YourMixHeader`** → 换成 `HomeSectionHeader`（`tertiaryContainer` + 随机播胶囊，
   图标 `rounded_queue_music_24`）。
3. **`RecentlyPlayedSection`** → 标题行换成 `HomeSectionHeader`（`secondaryContainer` + `History`），
   右侧 40×64 箭头按钮换成「收听统计 →」胶囊，**跳 `Screen.Stats` 而非 `RecentlyPlayedScreen`**。
   `RecentlyPlayedScreen` 保留（时间轴完整列表），只是入口换了。
   - 新增字符串 `home_recently_played_subtitle`：What you have been listening to / 你最近在听的歌。
4. **`DailyMixHeader`**：**去掉 `primary → tertiary` 渐变**（与 AI 横幅撞色，等于自称 AI），
   改成 `tertiaryContainer` 纯色，文字 `onTertiaryContainer`。
5. **区块间距** `24dp → 32dp`。

### 统计界面统一

- **行样式**：新增 `StatRankRow`（`surfaceContainerLow` / 10dp 圆角 / 13·12 内边距，
  与 `EnhancedSongListItem` 同款规则），热门歌曲、热门歌手、热门专辑三处共用；
  `SongRow` 改为基于它的薄封装（带 42dp 封面），全部从 private 升 internal 供详情页复用。
- **按钮**：排序切换（Plays / Duration）由 `ToggleButton` 换成 10dp 圆角 `Surface` 胶囊；
  Range tabs 圆角 14dp → 10dp 对齐。
- **热门歌曲限量 15 条**：`HOT_SONGS_VISIBLE_COUNT = 15`，超过 15 条出现
  「显示全部 %d 首」`FilledTonalButton`。
- **「显示更多」→ 独立页面而非对话框**：新增 `Screen.StatsHotSongs`（`stats_hot_songs`）+
  `StatsHotSongsScreen.kt`（TopAppBar + 返回 + 排序按钮 + 全量 LazyColumn）。
  不选对话框的原因：统计歌曲上限 100 条（`MAX_SONG_STATS_COUNT`），对话框里还是得滚，
  只是把长滚动搬了个家；独立页有返回手势、不占弹窗、与「最近播放 → 完整列表」同一模式。
- 新增字符串 `stats_hot_songs_show_more`：Show all %1$d songs / 显示全部 %1$d 首。

### 实现备注

- `SongSortMetric`、`SortToggleButton`、`StatsEmptyState`、`SongRow`、`StatRankRow`
  统一升 `internal`（StatsScreen 与 StatsHotSongsScreen 共享，不复制代码）。
- 热门歌手/专辑卡片的 `secondaryContainer` / `tertiaryContainer` 卡片底色**去掉**，
  行直接浮在页面上 —— 原来三种底色卡片叠在同一页也是一种「融」。
