# AI 歌单「提示词详情」弹窗方案（歌单详情页）

> 状态：已实现（横幅可点详情；Room v10 持久化采样与原始曲目快照；详情弹窗显示完整提示词、
> 采样、原始列表及移除标记），assembleDebug + testDebugUnitTest 通过，待真机走查。
> 点击区域、采样按生成当时持久化、原始列表存 id 并标注移除状态均已确认。
> 位置：曲库 → 播放列表 → 点开一张 AI 歌单后的 `PlaylistDetailScreen`，顶部提示词横幅
> `AiPromptBanner`。
> 依赖：与《不期而遇恢复高级采样选项方案》同批，**先做采样设置**，再做本方案。

## 1. 需求与痛点

1. `AiPromptBanner` 用 `AutoScrollingTextOnDemand` 显示生成提示词，溢出时 `basicMarquee`
   横向跑马灯（停 2s、25dp/s、无限循环），长句看得慢。**点横幅弹出详情**看全文。
2. 详情同时展示这张歌单**当时**的采样：随机 / 最常播放、从多少首里挑选。
3. 新增：展示**原始生成的歌曲列表**。AI 歌单保存后仍可被增删/重排，用户希望随时回看
   "AI 最初生成的是哪几首、顺序如何"，并能与当前列表对比。

### 设计取舍：不禁止修改，改为存原始快照（已与维护者讨论后的建议）

- **不采用"禁止修改 AI 歌单"**：结果页移除不喜欢的歌、详情页加歌/删歌/排序是正当的整理需求，
  禁止属于功能倒退。
- 采用"**允许自由修改 + 持久化一份原始快照**"：两全——既能整理，又能永久回看 AI 最初结果。
  原始结果在生成那一刻就在 ViewModel 的 `state.songs`（UI 删歌改的是 `resultSongs` 副本，
  不动 state），保存时顺手存下即可，成本仅数据库一列。

## 2. 现状（源码核对）

- 展示：`PlaylistDetailScreen.kt` L738-741 用 `currentPlaylist.aiPrompt` 渲染
  `AiPromptBanner`（L1189-1124，目前不可点）；当前歌曲为 `songsInPlaylist =
  uiState.currentPlaylistSongs`。
- 数据：`Playlist` / `PlaylistEntity`（表 `playlists`）只存 `aiPrompt`、`source`，**未存采样、
  未存原始曲目**；歌单当前歌曲走关联表 `playlist_songs(playlist_id, song_id, sort_order)`，
  会被增删/重排改写，不能充当原始快照。
- 生成链路：普通/不期而遇短按/长按都经 `startPreview{}` 写入 `NlpPlaylistPreviewState`，
  其中 `songs` 是**未经 UI 删改的原始生成结果（保序）**；`resultSongs` 才是可编辑副本，
  `saveAiMix(...)` 保存的是编辑后结果，经 `PlaylistPreferencesRepository.createPlaylist` 落库。
- 回查能力：`MusicRepository.getSongsByIds(ids): Flow<List<Song>>` 现成，可按原始 id 批量取歌。
- Room：当前 **version=9、exportSchema=true**（schema 在 `app/schemas/.../9.json`），最新
  `MIGRATION_8_9` 用扩展 `db.addColumnIfMissing` 给 playlists 加可空列；migration 于
  `di/AppModule.kt` L141-150 注册。本方案一次性升到 **v10**。
- 复用文案：`ai_sample_mode_random/most_played`、`ai_playlist_sample_mode_label`、
  `ai_playlist_sample_size_label/value`、通用 `dismiss`；弹窗用 M3 `AlertDialog`。

## 3. 数据层改动（Room v9 → v10，一次加三列）

| # | 文件 | 改动 |
|---|---|---|
| 1 | `data/model/PlayList.kt` | 加 `aiSampleMode: String? = null`、`aiSampleSize: Int? = null`、`aiOriginalSongIds: List<String> = emptyList()`（均有默认，手动/旧歌单为空） |
| 2 | `data/database/PlaylistEntity.kt` | 加列 `ai_sample_mode TEXT`、`ai_sample_size INTEGER`、`ai_original_song_ids TEXT`（JSON 字符串数组，保序快照，不进关联表）；`toPlaylist/toEntity` 双向映射，List↔JSON 用 kotlinx.serialization（`Playlist` 已 `@Serializable`，mapper 内一个私有 `Json`） |
| 3 | `data/database/Migrations.kt` | 新增 `MIGRATION_9_10`，对 playlists `addColumnIfMissing` 上述三列（均可空、无默认，照抄 8_9） |
| 4 | `data/database/PiscesPlayerDatabase.kt` | version 9→10（KSP 构建生成 `10.json`，一并提交） |
| 5 | `di/AppModule.kt` | 注册 `MIGRATION_9_10` |
| 6 | `data/preferences/PlaylistPreferencesRepository.kt` | `createPlaylist(...)` 加 `aiSampleMode/aiSampleSize/aiOriginalSongIds` 三个默认参数并透传 |
| 7 | `presentation/viewmodel/PlaylistViewModel.kt` | 见下 |

### ViewModel 记录逻辑

- `NlpPlaylistPreviewState` 加 `sampleMode: AiLibrarySampleMode? = null`、`sampleSize: Int? = null`。
- `startPreview` 增加"本次采样"提供者：普通入口读 library 偏好，不期而遇（短按/长按）读
  serendipity 偏好（默认 RANDOM/200，来自前置的采样设置任务）；成功时连同 `songs` 写入 state。
- `saveAiMix(...)` 落库时：
  - 采样取 state 的 `sampleMode?.name / sampleSize`；
  - **原始曲目取 `_aiPlaylistPreviewState.value.songs.map { it.id }`（保序，未经删改）**；
  - `onSave`/`AiMixSheet`/`HomeScreen` 签名不变。重新生成会刷新原始快照，保存的是最后一次
  生成的结果，符合预期。

## 4. UI 改动（PlaylistDetailScreen）

### 4.1 横幅入口

`AiPromptBanner` 整卡 `clip(形状).clickable { showDetails = true }`（跑马灯无可点滚动条，整卡
可点最稳），标题行右侧加展开小图标（`contentDescription` 用新串）。

### 4.2 详情弹窗 `AiPromptDetailsDialog`

M3 `AlertDialog`，标题"提示词详情"，正文 `Column + verticalScroll + 高度上限`，分三块：

1. **完整提示词**：`SelectionContainer` 包裹，自动换行、可滚动、可长按复制（只读）。
2. **采样信息**：曲库切片（随机/最常播放）、发给模型的歌曲数（N 首）；值为空则该行不显示。
3. **原始生成列表（N 首）**：`aiOriginalSongIds` 非空时显示。
   - 详情 ViewModel 用 `getSongsByIds(原始ids)` 回查，**严格按快照顺序**列出"标题 - 艺术家"；
   - 与当前歌单 id 集合对比做状态标记：仍在当前歌单=正常；已被移除=置灰 + 「已移除」小标签；
     id 在曲库查不到（文件已删/移出）= 「已不在曲库」占位；
   - 最多 40 行，直接铺在弹窗滚动区，不嵌套独立列表。
- `confirmButton`=关闭（复用 `dismiss`），点外部/返回可关。
- **旧歌单降级**：三列皆空的旧 AI 歌单，弹窗只显示完整提示词；手动歌单不显示横幅。

## 5. 新增字符串（中英文都加；其余复用）

写入 PlaylistDetail 现用串文件与 `values-zh-rCN/` 同名文件：

| key | 英文 | 简体中文 |
|---|---|---|
| `playlist_ai_prompt_details_title` | Prompt details | 提示词详情 |
| `playlist_ai_prompt_expand_cd` | View full prompt | 查看完整提示词 |
| `playlist_ai_original_title` | Originally generated (%1$d) | 原始生成（%1$d 首） |
| `playlist_ai_song_removed` | Removed | 已移除 |
| `playlist_ai_song_unavailable` | No longer in library | 已不在曲库 |

模式名/采样标签/数量/关闭复用现有串；不手写其他语言。

## 6. 提交顺序（三个英文 commit）

1. `Give Serendipity its own sampling settings (random / 200 by default) and show the advanced controls`
2. `Persist AI playlist generation metadata: sampling and original song snapshot`（Room v10 + 链路）
3. `Show AI prompt details (prompt, sampling, original songs) from the playlist detail banner`

## 7. 验证计划

`.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest`（确认生成 `10.json`、Room 校验通过）；
真机：

1. 新装与 v9 升级不崩，旧歌单/手动歌单完好；
2. 生成并保存普通（最常播放/300）与不期而遇（随机/200）歌单，详情点横幅：提示词全文可滚可复制、
   采样与生成时一致、原始列表顺序与生成结果一致；
3. 保存前在结果页移除 2 首：保存后的"当前列表"少 2 首，详情"原始生成"仍完整且这 2 首标「已移除」；
   保存后在详情页再删 1 首，重开详情标记同步更新；
4. 生成后改高级设置，已保存歌单详情仍显示生成当时的采样；
5. 旧 AI 歌单只显示提示词；长按快速生成的歌单同样记录随机/200 与原始快照；弹窗三种方式可关。

## 8. 待确认

1. 原始快照**存歌曲 id 有序列表**（回查曲库显示名字、可对比"已移除"、为将来"一键恢复原始列表"
   留可能；文件被删则显示"已不在曲库"）——认可吗？备选是存纯文本快照（无需回查、文件删了也有
   名字，但不能对比/恢复），不推荐。
2. 原始列表是否按上文**标注「已移除 / 仍保留」对比状态**（推荐标注）？
3. 本次**只回看**、不加"一键恢复原始列表"按钮（留作后续），可以吗？
