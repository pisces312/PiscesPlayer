# C 类 AI 移植计划（PixelPlayerOSS）

> 状态：**计划已定稿，待实现**
> 更新：2026-09-09
> 分支：`pisces/port`（`pisces312/PixelPlayerOSS`，GPL-3.0 fork）
> 参考源：`D:\3rd-party-projects\PixelPlayer`（china-only 分支，`com.theveloper.pixelplay`）

---

## 0. 用户最新决策（2026-09-09，覆盖此前范围）

1. **不做细粒度模型参数**：temperature / top_p / top_k / max_tokens / presence_penalty /
   frequency_penalty 等一律不要。只保留 provider、api_key、model、base_url。
2. **数据库参考 china-only**，已做合规评估（见 §2，结论：可参考）。
3. **UI 两处入口**：
   - 设置页新增一个「AI 配置」选项/分类；
   - 主页在**原有推荐区块之前**加一个 AI 入口。
4. **布局由我设计**（见 §6）。

---

## 1. 目标与边界

**做**：本地曲库 + OpenAI 兼容 REST 端点 → 自然语言生成歌单（AI Playlist）+ AI 请求文件日志（`AiRequestLogStore`）。
**不做**：Gemini / Google SDK、细粒度采样参数、
用户画像摘要（`UserProfileDigestGenerator`）、provider 故障转移链（`AiProviderSupport.buildProviderChain`）。

> 2026-09-09 追加：`AiRequestLogStore` 从「不做」改为「做」。理由：AI 功能仍在密集迭代
> （MiMo endpoint 解析、401 排查），缺完整 prompt/response 记录时只能靠 adb 抓 UI 文本反推。
> 落地形态见 §10。

**Provider 范围（最小集）**：`MIMO`（小米 MiMo）、`VOLCANO`（火山引擎 Ark）、`CUSTOM`（自定义 base_url）。
三者均为 OpenAI 兼容 `/chat/completions` 协议。

---

## 2. 合规评估（数据库部分）

| 项 | 结论 | 依据 |
|---|---|---|
| `ai_cache` 表结构 | ✅ 可参考 | china-only **原创** schema（上游 OSS 无此表）；schema 不受版权保护 |
| `ai_usage` 表结构 | ✅ 可参考 | 同上 |
| DAO / Entity 代码 | ⚠️ clean-room 重写 | 包名 `com.theveloper` → `com.pisces312.piscesplayer`；只保留 Room 注解语义，不逐行复制 |
| `AiHandler` / 网络层 / UI | ⚠️ clean-room 重写 | 原代码深度耦合 Gemini + 13 provider + 细粒度参数；直接搬会引入 Google SDK，违反版权红线 |
| GEMINI provider | ❌ 排除 | 需 Google SDK / 非 OpenAI 兼容 |

**china-only 表结构参考**（原封不动抄录为 schema 依据）：

```text
ai_cache:  promptHash TEXT PK (SHA-256), responseJson TEXT, timestamp INTEGER
ai_usage:  id INTEGER PK AUTO, timestamp INTEGER, provider TEXT, model TEXT,
           promptType TEXT, promptTokens INTEGER, outputTokens INTEGER, thoughtTokens INTEGER
```

> OSS 当前 `PixelPlayerDatabase` version = **7**，新增两表需 **MIGRATION_7_8**。
> 注：china-only 分支「Room schema 恒 v43」的限制**不适用** OSS（v7 独立演进），直接升 v8 即可。

---

## 3. 数据库实现

**新增文件**（`app/src/main/java/com/pisces312/piscesplayer/data/database/`）：
- `AiCacheEntity.kt` — `@Entity(tableName = "ai_cache")`
- `AiCacheDao.kt` — insert(REPLACE) / getCache(hash) / clearOldCache(ts) / clearAllCache
- `AiUsageEntity.kt` — `@Entity(tableName = "ai_usage")`
- `AiUsageDao.kt` — insertUsage / getAllUsagesOnce / getRecentUsages(limit):Flow /
  getTotalPromptTokens:Flow<Int?> / getTotalOutputTokens / clearAll

**修改文件**：
- `PixelPlayerDatabase.kt`：`version = 8`，`entities` 加两 Entity，`abstract fun` 加两 DAO。
- `Migrations.kt`：加 `MIGRATION_7_8`（`CREATE TABLE ai_cache ...` + `CREATE TABLE ai_usage ...`）。

---

## 4. 偏好实现（精简版）

**新增文件** `data/preferences/AiPreferencesRepository.kt`（clean-room，独立于 UserPreferencesRepository，
与 china-only 结构对齐但大幅裁剪）：

仅保留键：
- `ai_provider`（String，默认 `"MIMO"`）
- 每 provider 4 键：`{provider}_api_key` / `{provider}_model` / `{provider}_base_url`（base_url 仅 CUSTOM 用）/
  `{provider}_thinking_enabled`（Boolean，**默认 `false` 关闭**）
- 固定 `DEFAULT_SYSTEM_PROMPT`（Vibe-Engine 音乐策展人设，可保留同一段英文提示词）

**「是否启用思考」（2026-09-09 增加）**：随模型配置，per-provider 存储。请求体携带
`thinking: {"type": "enabled"|"disabled"}`——MiMo（`mimo-v2.5-pro` 默认 enabled）与火山 Ark 接入点
（doubao thinking 系列）均支持该字段，语义统一。**默认关闭**：推理 token 额外计费且增加延迟，
而生成歌单只需要最终结果。

**删除**（china-only 有、本次不做）：`safe_token_limit` / `ai_temperature` / `ai_top_p` / `ai_top_k` /
`ai_max_tokens` / `ai_presence_penalty` / `ai_frequency_penalty` / `ai_sample_size` / `ai_digest_mode` /
`ai_include_extended_fields`。

Provider 枚举：`MIMO("Xiaomi MiMo (CN)", requiresApiKey=true)`、`VOLCANO("Volcano Engine (Ark)", requiresApiKey=true)`、
`CUSTOM("Custom Provider", requiresApiKey=false, hasConfigurableUrl=true)`。

默认值（**已确认，2026-09-09**）：
- MIMO：base_url `https://api.xiaomimimo.com/v1`，默认 model `mimo-v2.5-pro`
  （可选 `mimo-v2.5` / `mimo-v2-flash`；flash 不支持 thinking 字段）。
- VOLCANO：base_url `https://ark.cn-beijing.volces.com/api/v3`，model 为接入点 ID（ep-xxx）。
- CUSTOM：base_url + model 均用户填。

---

## 5. 网络层 + 业务层（全部 clean-room 重写）

依赖已具备（`gradle/libs.versions.toml`）：Retrofit 3.0.0 + OkHttp 5.4.0 + converter-gson + kotlinx-serialization-json。

**网络层** `data/ai/`：
- `OpenAiCompatibleClient.kt`：统一 POST `{base_url}/chat/completions`，Bearer auth，
  body = `{model, messages:[{role:system,content}, {role:user,content}], stream:false}`，
  解析 `choices[0].message.content` + `usage{prompt_tokens,completion_tokens}`。
  返回 `ChatResult(response, promptTokens, outputTokens)`。

**业务层** `data/ai/`：
- `AiHandler.kt`：单点入口 `generate(prompt, context)`：
  1. `prompt.sha256()` 查 `ai_cache`，命中且 < 30min 直接返回；
  2. 组装 system prompt + user prompt（含曲库样本：歌名/艺人，见下）；
  3. 调 client（withTimeout 60s）；
  4. 写 `ai_cache`（REPLACE）+ `ai_usage`（promptType="playlist"）；
  5. 失败抛 `AiProviderException`（statusCode / message），不落盘、不打断主流程。
- `AiPlaylistGenerator.kt`：prompt → 模型返回歌名列表 → 本地模糊匹配（复用现有
  `data/playlist/nlp/LocalMetadataHeuristics.kt` + DAO 查询）→ 生成 Playlist 写入
  `PlaylistPreferencesRepository` / `LocalPlaylistDao`。
  曲库样本**可切换两种模式**（用户在前一个下拉框选择，存 `ai_library_sample_mode`）：
  - `MOST_PLAYED`：播放次数 desc + 标题，稳定 → 同描述同 prompt → 命中缓存；
    未播放过的歌只在曲库小于 n 时才会出现
  - `RANDOM`：每次请求重新洗牌 → 每首歌都有机会，代价是缓存基本不命中
  n 由用户在前一个下拉框选择（50/100/200/300/500，默认 300），存 `ai_library_sample_size`。
  RANDOM 模式下 prompt 每次不同 → 缓存基本不命中，所以 `AiHandler` 写缓存前先清理过期行，
  避免 `ai_cache` 无限增长。
- `AiSystemPromptEngine.kt`：固定 Vibe-Engine 人设 + 拼接曲库统计（总量/艺人/流派样本，脱敏不含绝对路径）。

---

## 6. UI 设计（布局）

### 6.1 主页 AI 入口（推荐区之前，第一区块）

插入位置：`HomeScreen.kt` 的 LazyColumn 中，**`YourMixHeader` / Your Mix 大卡之前**（当前
Your Mix 卡在 `dailyMixSongs` 区块之前，约 `HomeScreen.kt:349` 附近）。

```
┌────────────────────────────────────────┐
│  顶栏：PixelPlayer              ⚙️     │
├────────────────────────────────────────┤
│  ┌──────────────────────────────────┐  │  ← 新增 AI 入口 banner（全宽卡片）
│  │  ✨  AI 生成歌单                  │  │     渐变背景（主题色/紫蓝）
│  │      用一句话描述想听的音乐        │  │
│  │                     [开始 →]     │  │
│  └──────────────────────────────────┘  │
│                                        │
│  ┌──────────────────────────────────┐  │
│  │  🎵 Today's Mix for you          │  │  ← 原有推荐（Your Mix 大卡）
│  │      [专辑拼贴]                  │  │
│  └──────────────────────────────────┘  │
│                                        │
│  Daily Mix 区块 / 最近播放 / ...       │
└────────────────────────────────────────┘
```

卡片规格（`AiGenerateEntryCard`，新组件 `presentation/components/`）：
- 全宽圆角卡片（同 DailyMix 圆角风格），高度约 72–88dp，主题渐变底 + 白色文字。
- 左：`Icons.Rounded.AutoAwesome`（✨）图标。
- 中：标题「AI 生成歌单」+ 副标题「描述你想听的音乐」。
- 右：胶囊按钮「开始」。
- 状态：未配置 API key 时副标题改为「先在设置里配置 AI」，点击跳转到设置 AI 配置屏；
  已配置则点击直接打开 `AiPlaylistSheet`。

### 6.2 设置页 AI 配置

`SettingsCategory`（OSS 当前有 LIBRARY/APPEARANCE/PLAYBACK/BEHAVIOR/BACKUP_RESTORE/DEVELOPER/
EQUALIZER/DEVICE_CAPABILITIES/ABOUT）新增 **`AI`** 分类（置于 PLAYBACK 与 BEHAVIOR 之间或 BEHAVIOR 后，
用 `Icons.Rounded.AutoAwesome` 图标），点击进入 `AiSettingsScreen`：

```
┌────────────────────────────────────────┐
│  ←  AI 配置                            │
├────────────────────────────────────────┤
│  Provider                     [MIMO ▼] │  ← 下拉：MiMo / 火山 / Custom
│  ──────────────────────────────────── │
│  API Key                     ••••••••  │  ← 非空时自动密码遮蔽
│  Base URL（仅 Custom）   https://…/v1 │  ← 仅 CUSTOM 显示
│  Model              [mimo-v2.5     ▼] │  ← 下拉（列表为空时退化为手输）
│  ──────────────────────────────────── │
│  🧠 启用思考                    [ ○ ]  │  ← 默认关闭（Custom 不支持，禁用）
│  ──────────────────────────────────── │
│  [ 拉取模型 ]  [ 测试连接 ]  [ 清缓存 ] │
├────────────────────────────────────────┤
│  用量统计                              │
│    Prompt 1,234 · Output 567 · 思考 89 │
└────────────────────────────────────────┘
```

**模型下拉（2026-09-09 增加）**：填好 url / key 后点「拉取模型」，调 `GET {base_url}/models`
取 `data[].id` 生成下拉菜单供选择。小米接口已验证：`https://api.xiaomimimo.com/v1/models`
返回 `mimo-v2.5` / `mimo-v2.5-pro` / `mimo-v2.5-tts` 等。
- 自动过滤非对话模型（含 `tts` / `asr` / `voice` / `embed` / `rerank` 的 id）。
- 列表为空（未拉取或拉取失败）时退化为可编辑文本框，手输 model id，不阻塞使用。
- 小米默认模型 **mimo-v2.5**。

- `AiSettingsScreen.kt`（新）+ `AiSettingsViewModel.kt`（新）。
- 「测试连接」：发一条最小 chat/completions 请求，Toast/状态提示成功或错误码。
- 「清除缓存」：`ai_cache` 清空。
- 「用量报告」：复用 `ai_usage` 汇总（provider/model/token 数），可做成简单列表弹窗
  （不做独立 Screen，保持最小）。

### 6.3 AI 生成歌单 Sheet

`AiPlaylistSheet.kt`（新组件，参考 china-only 交互但 clean-room 重写）：
- 底部弹窗：标题「AI 生成歌单」+ 多行输入框（自然语言描述）+ 「生成」按钮。
- 生成中：进度指示；成功后：展示匹配到的歌单，点「加入播放队列」或「保存为歌单」。
- 失败：展示 `AiProviderException` 摘要（如 401 未授权 / 402 配额不足 / 404 模型不存在）。

---

## 7. 文件清单

### 新增（`com.pisces312.piscesplayer`）
```
data/database/AiCacheEntity.kt
data/database/AiCacheDao.kt
data/database/AiUsageEntity.kt
data/database/AiUsageDao.kt
data/preferences/AiPreferencesRepository.kt
data/ai/OpenAiCompatibleClient.kt
data/ai/AiHandler.kt
data/ai/AiPlaylistGenerator.kt
data/ai/AiSystemPromptEngine.kt
data/ai/provider/AiProvider.kt            # MIMO/VOLCANO/CUSTOM
data/ai/provider/AiProviderException.kt   # 精简错误分类
presentation/screens/AiSettingsScreen.kt
presentation/viewmodel/AiSettingsViewModel.kt
presentation/components/AiGenerateEntryCard.kt
presentation/components/AiPlaylistSheet.kt
data/ai/AiRequestLog.kt                    # 单请求模型（JSON 落盘）
data/ai/AiRequestLogStore.kt               # cacheDir/ai_logs/，50 条上限
presentation/viewmodel/AiRequestLogViewModel.kt
presentation/screens/AiRequestLogScreen.kt
```
资源：`res/values/strings_ai.xml` + `res/values-zh-rCN/strings_ai.xml`、图标按需复制（AutoAwesome 等）。

### 修改
```
data/database/PixelPlayerDatabase.kt       # version 8 + 两 DAO
data/database/Migrations.kt                # MIGRATION_7_8
presentation/model/SettingsCategory.kt     # 新增 AI 分类
presentation/screens/SettingsScreen.kt     # 接线 AI 分类 → AiSettingsScreen
presentation/screens/SettingsCategoryScreen.kt  # 若 AI 需在分类列表里渲染
navigation/Screen.kt / AppNavigation.kt    # 路由 AiSettings
presentation/screens/HomeScreen.kt         # 推荐区前插入 AiGenerateEntryCard
presentation/viewmodel/PlayerViewModel.kt  # 暴露 AI 状态（showAiPlaylistSheet / 生成触发）
di/ 相关模块（AppModule 等，注册 client/dao/handler/repo）
```

---

## 8. 分阶段 checklist（可验证成功标准）

- [x] **S1 数据层**：两 Entity + 两 DAO + DB v8 + MIGRATION_7_8。验证：`compileDebugKotlin` 通过；
      Room schema 导出（若启用）生成 v8。
- [x] **S2 偏好层**：精简 `AiPreferencesRepository`（provider/key/model/base_url/**thinking_enabled**）。
      验证：编译通过。
- [x] **S3 网络层**：`OpenAiCompatibleClient`（chat + **listModels**）+ `AiProvider`/`AiProviderException`。
      验证：编译通过。
- [x] **S4 业务层**：`AiHandler`（缓存/用量/超时/降级）+ `AiSystemPromptEngine`。
      验证：编译通过。`AiPlaylistGenerator`（本地模糊匹配）待 S5 主页入口时实现。
- [x] **S4b 歌单生成**：`AiPlaylistGenerator` — 解析 `Title - Artist` 行 → 归一化 + bigram 相似度
      匹配本地曲库（阈值 0.5，艺人加权 0.25）→ 只返回设备上真实存在的歌。
- [x] **S5 UI**：设置 AI 分类 + `AiSettingsScreen`（含模型下拉）+ 用量 + 诊断按钮；
      主页 `AiGenerateEntryCard` + 复用 `DescribePlaylistDialog` 作为生成弹窗（加 title/subtitle
      参数与 error 展示）。
      验证：`assembleDebug` 通过。
- [ ] **S6 联调**：装真机，配置火山/自定义端点，跑通「主页入口 → 描述 → 生成歌单 → 播放」。
- [x] **S7 提交推送**：分「数据/偏好/网络/业务」一个 commit、「UI + strings」一个 commit，
      push `pisces/port`（不 pick 到 master，master 无 AI 需求）。
- [x] **S8 AI 请求日志**（见 §10）：`AiRequestLog` + `AiRequestLogStore` + `AiHandler` 三处埋点
      + 浏览页 + 设置入口 + 单测。
- [ ] **S9 AI 配置纳入备份**：`BackupSection.AI_PROVIDER_CONFIG`（含明文 api key）。
- [ ] **S10 正向链路真机联调**：「描述 → 生成 → 播放」完整跑通（错误路径已验）。

---

## 10. AI 请求日志（S8）

**形态**：一次请求 = `cacheDir/ai_logs/` 下一个 JSON 文件，最多 50 条（超出删最旧）。
放 cacheDir 是刻意的——调试产物，不进备份、系统可回收。单条字段超 64k 字符截断并打 `truncated` 标记。

**埋点三处**（`AiHandler.generate`）：缓存命中 / 请求失败（catch 后原样 rethrow）/ 成功返回。
凭据 `?key=…`、`?access_token=…`、`?secret=…` 在落盘前由 `AiRequestLogStore.redactUrl` 打码。

**入口**：设置 → AI → Request log。详情页支持复制全文 / 复制 response / 通过 FileProvider 分享原文件。

**调试方式**：`adb shell run-as <pkg> ls cache/ai_logs` 或直接 `adb pull`，单文件即完整
prompt + response + token 用量。

**合规**：`AiRequestLog` / `AiRequestLogStore` 为原作者原创实现，从 china-only 分支移植，
文件头注明 `Original work, GPL-3.0-or-later`；Screen/ViewModel/设置接线在 OSS 侧重写。

---

## 9. 恢复上下文速查（压缩后重建用）

- OSS 根：`D:\3rd-party-projects\PixelPlayerOSS`，分支 `pisces/port`，包 `com.pisces312.piscesplayer`。
- china-only 根：`D:\3rd-party-projects\PixelPlayer`，分支 `china-only`，包 `com.theveloper.pixelplay`。
- 参考文件（china-only，只读参考 schema/交互，不复制）：
  - `data/database/AiCacheEntity.kt`、`AiCacheDao.kt`、`AiUsageEntity.kt`、`AiUsageDao.kt`
  - `data/preferences/AiPreferencesRepository.kt`
  - `data/ai/AiHandler.kt`、`AiPlaylistGenerator.kt`、`AiSystemPromptEngine.kt`
  - `data/ai/provider/AiProvider.kt`、`AiProviderSupport.kt`（错误分类逻辑）
  - `presentation/components/AiPlaylistSheet.kt`、`presentation/screens/AiRequestLogScreen.kt`（交互参考）
- OSS 已有网络依赖：Retrofit 3.0.0 / OkHttp 5.4.0 / converter-gson / kotlinx-serialization-json。
- OSS 本地匹配可复用：`data/playlist/nlp/LocalMetadataHeuristics.kt`。
- OSS DB 版本：7 → 8。OSS 无 `AiPreferencesRepository`、无 `AiStateHolder`、无 AI 分类、无 AiPlaylistSheet。
- 版权红线：AI 代码一律 clean-room 重写，不引 Gemini/Google SDK，只做 OpenAI 兼容端点。
