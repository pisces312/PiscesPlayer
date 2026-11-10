# AI 思考过程留档 + 结果页 / 提示词详情展示 方案

> 状态：**已实施（2026-09-16）** —— 实现记录、与计划的偏离及验证结果见 §9。
> 真机端到端（结果页展开 / 滚动条拖动、详情弹窗展示、覆盖安装）待接设备后补。
> 涉及代码：`data/database/PlaylistEntity.kt`、`data/database/Migrations.kt`、
> `data/database/PiscesPlayerDatabase.kt`、`di/AppModule.kt`（+ 新增 `app/schemas/.../11.json`）、
> `data/model/PlayList.kt`、`data/preferences/PlaylistPreferencesRepository.kt`、
> `presentation/viewmodel/PlaylistViewModel.kt`、`presentation/components/AiMixSheet.kt`、
> `presentation/screens/PlaylistDetailScreen.kt`。
> 测试：新增 `app/src/test/.../data/database/PlaylistAiThinkingMappingTest.kt`，
> 补 `app/src/androidTest/.../data/database/LocalPlaylistDaoTest.kt`、
> `PlaylistMigrationTest.kt`。
> 前置：`docs/ai-streaming-thinking-plan.md`（思考内容目前只存在内存，且生成成功那一刻就被丢弃）。

## 1. 现状与两个缺口

**缺口一：思考内容活不过生成结束。** `PlaylistViewModel.startPreview()`（`PlaylistViewModel.kt:582`）
在成功分支用 `_aiPlaylistPreviewState.value = NlpPlaylistPreviewState(...)` **重建**状态，
只带 `songs / sampleMode / sampleSize`，**没带 `thinkingText`**。结果页判定的
`state.hasResult == true` 恰恰等于这个终态，所以「生成结束后在结果页显示思考过程」的前置修复，
就是先让思考文本活到终态。

**缺口二：DB 里没有这一列。** AI 生成元信息已有成熟模式：`ai_prompt`（v9）、
`ai_sample_mode` / `ai_sample_size` / `ai_original_song_ids`（v10），全是 `playlists` 表上的
可空列。思考内容沿用同一模式加一列即可，**不新建表**。

两处 UI 目标：

1. 结果页（`AiMixSheet.ResultPhase`，`AiMixSheet.kt:537`）——加思考过程 + 垂直滚动条。
2. 提示词详情（`PlaylistDetailScreen.AiPromptDetailsDialog`，`PlaylistDetailScreen.kt:1268`）
   —— 曲库/播放列表 → AI 播放列表详情 → 点 AI 描述横幅（`AiPromptBanner`，:1212）打开的那个弹窗，也显示思考过程。

## 2. 数据层：schema v10 → v11

| 位置 | 改动 |
|---|---|
| `PlaylistEntity.kt` | 加 `@ColumnInfo(name = "ai_thinking") val aiThinking: String? = null`；`toPlaylist()` / `toEntity()` 双向映射补该字段 |
| `Migrations.kt` | 新增 `MIGRATION_10_11`：`db.addColumnIfMissing("playlists", "ai_thinking", "\`ai_thinking\` TEXT")`，沿用既有 `addColumnIfMissing`（Auto Backup 还原后列漂移的兜底） |
| `PiscesPlayerDatabase.kt` | `version = 10` → `11` |
| `di/AppModule.kt` | `.addMigrations(...)` 列表**末尾追加** `MIGRATION_10_11`（:142 起） |
| `app/schemas/.../11.json` | 构建时由 KSP 生成，**必须提交**（`exportSchema = true`，且 `androidTest` 的 assets 依赖它跑迁移测试） |
| `data/model/PlayList.kt` | 加 `val aiThinking: String? = null` |
| `PlaylistPreferencesRepository.createPlaylist` | 加 `aiThinking: String? = null` 参数并透传 |

设计取舍：

- **用列而不是新表**：思考与播放列表 1:1、生命周期完全一致、只整体读写、不需要单独查询；
  新表还要自己处理级联删除（`playlist_songs` 已因无外键而手写事务，`LocalPlaylistDao.deletePlaylist`）。
- **可空、无默认值**：null 精确表示「非 AI 生成」或「未记录」，与 `ai_prompt` 一致；
  旧行不受影响，无需回填。
- **不做长度限制**：TEXT 无上限，实测思考文本 1–5 KB；该列不进任何索引、不参与搜索。
- **备份不受影响**：`PlaylistsModuleHandler` 用 Gson 序列化 `Playlist` 模型，新字段自动带上；
  而 AI 播放列表本来就被 `isBackedUpPlaylistSource`（只认 `LOCAL` / 智能播放列表源）排除在备份外，
  所以这一层无需改动。

## 3. ViewModel：把思考带进终态并落库

- **`startPreview` 成功分支改为 `update { it.copy(...) }`**，而不是 `value = NlpPlaylistPreviewState(...)`：
  `copy` 天然保留 `thinkingText`，同时显式把 `isGenerating = false`、`hasResult = true`、
  `stage = AiGenerationStage.IDLE`（终态不再是「思考中」）、`songs`、`sampleMode`、`sampleSize` 写回。
  用 `copy` 而不是读 `_aiPlaylistPreviewState.value.thinkingText` 再拼新对象，是为了少一处
  「以后加字段忘了带过去」的隐患。
- **`saveAiMix`（`PlaylistViewModel.kt:803`）**：`createPlaylist(..., aiThinking = preview.thinkingText.ifBlank { null })`。
  读 `preview` 的安全性说明（会写进注释）：`viewModelScope` 的调度器是 `Dispatchers.Main.immediate`，
  `launch` 在 Main 线程上**急切启动**，协程体在第一个挂起点（`createPlaylist`）之前已经把 `preview`
  读出来了；而 `HomeScreen` 的 `onSave` 是在 `saveAiMix(...)` 返回**之后**才调
  `resetAiPlaylistPreview()`（`HomeScreen.kt:671`）。现有 `preview.sampleMode` / `preview.songs`
  依赖的是同一机制，本次不引入新风险，但要把这个前提写在注释里，避免以后有人把读取挪到挂起点之后。
- **失败分支不回填思考**（保持现有错误态布局干净），失败/取消也不落库。
- 更新 `NlpPlaylistPreviewState.thinkingText` 的注释：现在写的是「never cached, logged or persisted」，
  改为「生成中只用于界面展示；保存播放列表时随 `ai_thinking` 落库，仍不进请求日志与响应缓存」。

## 4. 结果页：思考过程 + 垂直滚动条

`ResultPhase` 重排为单一滚动区：

```
errorMessage?
[ songs 非空 ]  歌单名输入框 / 结果计数
[ songs 或 思考 非空 ]  Box(Modifier.fillMaxWidth().heightIn(max = 320.dp)) {   ← 唯一滚动区
        LazyColumn(state = listState) {
            if (思考非空) item(key = "ai_thinking") { ThinkingCard(..., innerScroll = false) }
            items(songs, key = { it.id }) { MixSongRow(...) }
        }
        ExpressiveScrollBar(listState = listState, modifier = Modifier.align(Alignment.CenterEnd))
}
[ songs 为空且无错误 ]  「没有匹配」
保存 / 播放
重新生成（FilledTonalButton）
```

三个决定及其依据：

1. **滚动区必须保持 LazyColumn。** `ExpressiveScrollBar`（`ExpressiveScrollBar.kt:213`）的签名
   只接受 `LazyListState?` / `LazyGridState?`，**不认** `verticalScroll` 的 `ScrollState`。
   所以把思考卡片做成列表的第一个 item，而不是并行开第二个滚动区——否则要么拿不到滚动条，
   要么两条滚动条。
2. **结果页里思考卡片不再内滚。** 给 `ThinkingCard`（`AiMixSheet.kt:475`）加
   `innerScroll: Boolean = true`：生成阶段（没有外层列表）保持现有 `heightIn(220.dp) + verticalScroll`；
   结果页传 `false`，正文整段展开、由外层列表滚。这样避免嵌套滚动，也只有一条滚动条。
3. **总高度基本不变。** 滚动区仍是 320dp 上限，思考卡片进来后 sheet 总高只多一行标题
   （默认收起时约 44dp），不会把底部「重新生成」顶出可视区。sheet 内容自身不可滚动
   （`AiMixSheet.kt:228` 的注释已说明这是有意为之），超出的部分会被裁掉——所以这是本方案
   **唯一需要真机确认的布局风险**，见 §7.5 与备选 §7.6。

交互细节：

- 顺序：思考在前、歌单在后，与生成时序一致。
- 结果页的思考卡片**默认收起**：复用现有 `ThinkingCard` 逻辑（`isThinking == false` 且用户没手动
  切换过 → 自动折叠），点标题行展开。默认收起是为了不把 320dp 的滚动区全让给几千字思考。
  若希望默认展开，改一行即可。
- 思考为空时（关闭思考 / 模型不返回思考内容）整块不渲染，结果页与今天完全一致。
- 生成阶段（`GeneratingPhase`）行为不变。

## 5. 提示词详情弹窗：也显示思考过程

`AiPromptDetailsDialog` 在「采样元信息」之后、「原始生成」之前插入一段：

```kotlin
if (!playlist.aiThinking.isNullOrBlank()) {
    HorizontalDivider()
    Text(
        text = stringResource(R.string.ai_thinking_section),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    SelectionContainer { Text(playlist.aiThinking, style = MaterialTheme.typography.bodyMedium) }
}
```

- **不新增字符串**：`ai_thinking_section`（`思考过程` / `Thought process`）已在
  `values/strings_ai.xml:125` 与 `values-zh-rCN/strings_ai.xml:125`，语义与此处一致。
  （若想让它与结果页标题区分，可另加 `playlist_ai_thinking_title`，需同步 en + zh-rCN。）
- 弹窗正文已是 `heightIn(max = 460.dp) + verticalScroll`（:1295），长思考文本无需额外处理；
  本次只加区块，不为弹窗加滚动条（未在需求内）。
- 可选中复制：包 `SelectionContainer`，与 prompt 的处理方式一致。
- 入口链路不变（曲库 / 播放列表 / AI 混音列表 → 播放列表详情 → AI 描述横幅 → 本弹窗），
  所以一处改动覆盖全部入口。旧播放列表 `ai_thinking` 为 null → 区块不渲染，
  与 `aiPrompt` 的 `takeIf { it.isNotBlank() }` 判断一致。

## 6. 边界与不做的事

- **旧数据**：v10 及更早保存的 AI 播放列表该列为 null，两处 UI 都不显示区块，无需回填。
- **不落库的情况**：生成失败 / 被取消（没有播放列表可存）；保存时思考为空 → 存 null。
- **隐私/体积口径不变**：思考内容仍不进 `AiRequestLog`、不进 AI 响应缓存、不写 DataStore。
- **不做**：思考内容纳入备份与导出（AI 播放列表本就不在备份范围）；思考文本的裁剪、摘要或
  默认展开；结果页给思考加复制按钮（弹窗里已可选中复制）。
- **回退**：两处 UI 改动可直接 revert；`ai_thinking` 列留在库里无害（SQLite 删列要重建表，不折腾）。

## 7. 验证清单

1. `:app:testDebugUnitTest` —— 新增 `PlaylistAiThinkingMappingTest`（纯 JVM，无需设备）：
   `Playlist` ↔ `PlaylistEntity` 往返，覆盖「有思考文本」「null」两种。
2. `:app:assembleRelease` 成功 + `apksigner verify --print-certs`（应见 v2 scheme / `CN=pisces312`）。
3. `:app:lintDebug` —— 应仍为 **4 个非语种 error**（本方案不新增字符串，预期零新增）。
4. **迁移**：
   - 确认 `app/schemas/.../11.json` 已生成并提交，且 `playlists` 的 `ai_thinking` 字段与实体一致。
   - `PlaylistMigrationTest` 补 10→11 用例：建 v10 库 → 插一行 → 迁到 11 → 校验列存在、
     旧行 `ai_thinking` 为 NULL（沿用现有 `runMigrationsAndValidate` 写法）。
   - `LocalPlaylistDaoTest` 补一条 `ai_thinking` 往返。
5. **真机（覆盖安装，保留旧库）**：
   - 开思考生成 → 结果页出现「思考过程」标题行（收起）→ 展开看全文 → 拖右侧滚动条能滚。
   - 保存 → 曲库 / 播放列表 → 详情页 → 点 AI 描述横幅 → 弹窗出现思考过程，文本可选中。
   - 关思考生成 → 结果页无思考卡片，布局与今天一致。
   - ~~短屏或大字号下确认底部「重新生成」仍可见可点（§4 的风险点）~~ —— **已否决（2026-09-16）**：
     小屏 / 大字号不再属于目标设备范围，验收只覆盖主流直板手机竖屏，见 `AGENTS.md`「目标设备与屏幕尺寸」。
6. **备选：已否决（2026-09-16）**。原方案是滚动区由 `heightIn(max = 320.dp)` 改为
   `Modifier.weight(1f, fill = false)`，让它吃掉剩余空间、底部按钮固定可见
   （同构先例 `FileExplorerBottomSheet.kt:362`：`Box(Modifier.weight(1f))` → `LazyColumn` → `ExpressiveScrollBar`）。
   否决理由：该改动只服务短屏 / 大字号，而这两者已明确排除在目标设备之外；在参照机
   （`pixel6`，411 × 914 dp）上底部按钮本来可见，改它属于无必要的约束行为实验。

## 8. 提交拆分

| 提交 | message | 内容 |
|---|---|---|
| 1 | `feat(ai): persist the model's thought process with the playlist` | `PlaylistEntity.kt`、`Migrations.kt`、`PiscesPlayerDatabase.kt`、`AppModule.kt`、`schemas/11.json`、`PlayList.kt`、`PlaylistPreferencesRepository.kt`、`PlaylistViewModel.kt`（终态保留 + `saveAiMix`）、新增映射单测、`LocalPlaylistDaoTest` / `PlaylistMigrationTest` 补用例 |
| 2 | `feat(ai): show the thought process in the result and prompt-details views` | `AiMixSheet.kt`（结果页 + 滚动条 + `ThinkingCard` 参数化）、`PlaylistDetailScreen.kt` |

只拆两个的原因：UI 展示（提交 2）依赖数据层与 VM 保留（提交 1），再往下拆会留下没有可读性
收益的中间态。中间提交不要求能独立编译（本仓规则），构建/测试只在最终 commit 上跑一次。

## 9. 实现记录（2026-09-16）

按 §2–§5 落地，文件与计划一致：

| 文件 | 改动 |
|---|---|
| `data/database/PlaylistEntity.kt` | 加 `ai_thinking` 列字段，`toPlaylist()` / `toEntity()` 双向映射 |
| `data/database/Migrations.kt` | 新增 `MIGRATION_10_11`（`addColumnIfMissing`） |
| `data/database/PiscesPlayerDatabase.kt` | `version = 11` |
| `di/AppModule.kt` | `addMigrations(...)` 末尾追加 `MIGRATION_10_11` |
| `app/schemas/.../11.json` | 新增（KSP 生成，`ai_thinking TEXT` 可空） |
| `data/model/PlayList.kt` | 加 `aiThinking: String? = null` |
| `data/preferences/PlaylistPreferencesRepository.kt` | `createPlaylist(..., aiThinking)` 并透传 |
| `presentation/viewmodel/PlaylistViewModel.kt` | 成功分支改 `update { it.copy(...) }`（保留 `thinkingText`、`stage` 回落 `IDLE`）；`saveAiMix` 传 `aiThinking`；更新 `thinkingText` 注释 |
| `presentation/components/AiMixSheet.kt` | `ResultPhase` 改用 `Box` + `LazyColumn(listState)` + `ExpressiveScrollBar`，思考卡片作为首个 item；`ThinkingCard` 加 `innerScroll` |
| `presentation/screens/PlaylistDetailScreen.kt` | `AiPromptDetailsDialog` 在采样元信息之后插入思考过程区块（复用 `ai_thinking_section`，无需新字符串） |
| 测试 | 新增 `PlaylistAiThinkingMappingTest`（JVM，2 例）；`LocalPlaylistDaoTest` 补 1 例；`PlaylistMigrationTest` 补 10→11 用例 |

与计划的偏离（都不改变既有行为，且都属于"计划未细化"的补足）：

1. **`ThinkingCard` 的初始展开态**由 `remember { mutableStateOf(true) }` 改为
   `remember { mutableStateOf(isThinking) }`。计划只要求加 `innerScroll`，但保留 `true` 会让结果页
   首帧先渲染整段思考、下一帧再收起（可见闪烁）。改为跟随 `isThinking` 后：生成阶段首次出现
   思考时仍展开（等价于原行为），结果页则从第一帧就是收起的。
2. **结果页「没有匹配」文案的判断拆成独立 `if`**（`songs.isEmpty() && errorMessage == null`），
   而不是原来的 `else if`：思考内容存在但一首都没匹配上时，两句提示应当同时可见（计划只写了
   滚动区在"songs 或思考非空"时渲染，没说清这种组合）。
3. **滚动条显隐用 `derivedStateOf { canScrollForward || canScrollBackward }`**，列表的 `end`
   padding 随它切换（`ArtistDetailScreen` 的既有写法）。计划只写"加一条滚动条"，未定显隐策略。
4. **`ai_thinking` 增删列不做回填**：与 `ai_prompt` 一致，旧行为 null。

验证结果（本机 + pixel6 AVD，2026-09-16）：

- `:app:testDebugUnitTest` —— **755 个用例全通过**（新增 `PlaylistAiThinkingMappingTest` 2/2）。
- `:app:connectedDebugAndroidTest`（只跑 `PlaylistMigrationTest` + `LocalPlaylistDaoTest`）——
  **6/6 通过**，其中 `migrationFromTenToElevenAddsThinkingAndLeavesExistingRowsAlone` 由
  `runMigrationsAndValidate` 校验通过，等于确认 `MIGRATION_10_11` 落地的库与导出的
  `11.json` 的 identity hash 一致（比原计划"待真机"更强的验证）。
- `:app:assembleRelease` —— 成功，`pixelplayeross-arm64-v8a-0.4.1-pisces.1-release.apk`（37.6 MB），
  `apksigner verify` → v2 scheme、`CN=pisces312`。
- `:app:lintDebug` —— 仍是 **4 errors / 912 warnings / 35 hints**，错误与改动前**逐条相同**
  （`SerendipityContextCollector` / `HomeScreen` / `CarLyricTitleController` ×2），
  本次改动文件**零新增**；任务因此仍 exit 1，但 release 交付不经过 lint。
- 仍待真机：结果页展开思考并拖滚动条、详情弹窗展示、以及**覆盖安装**（v10 库 → v11）后
  旧播放列表不显示思考区块、`ai_thinking` 列存在。

