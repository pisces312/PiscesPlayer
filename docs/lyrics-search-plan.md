# 歌词内容搜索 — 设计方案

> 状态：**已实施**（2026-11-14）。`assembleRelease` 成功，`testDebugUnitTest` 全绿
> （含新增 `SearchStateHolderTest` 歌词分流用例与修复后 `PlayerViewModelTest` 搜索桩），
> `app/schemas/.../16.json` 已生成入库。与本文设计的差异见 §7。
> 日期：2026-11-14
> 对象仓库：本仓库（branch `main`，基线调查时 `PiscesPlayerDatabase.kt` version = 15）
> 许可证：GPL-3.0-or-later

---

## 0. 结论摘要

在现有搜索页增加一个**搜索模式开关**（常规 / 歌词）。歌词模式通过查询 Room `lyrics`
表（FTS + LIKE 双路，照抄 `songs_fts` 的既有模式）返回匹配的歌曲，只搜**本地已缓存**
的歌词（内嵌标签 / `.lrc` 文件 / LRCLIB 已抓取入库的内容），不新增任何网络请求和权限。

预估改动：数据库 15 → 16 一次迁移，`LyricsDao` 收口 FTS 维护（所有写入方自动同步），
新增约 2 个 DAO 方法 + 1 个仓库方法 + `SearchStateHolder`/`SearchScreen` 各一处接线。

---

## 1. 现状分析

### 1.1 现有搜索链路

```
SearchScreen.kt (FilterChips: ALL/SONGS/ALBUMS/ARTISTS/PLAYLISTS, L412-416)
  → SearchStateHolder (debounce 300ms → musicRepository.searchAll(query, filter))
    → MusicRepositoryImpl.searchAll (MusicRepositoryImpl.kt:604)
      → MusicDao.searchSongs* : songs_fts MATCH + LIKE 双路合并去重 (MusicDao.kt:497-518)
```

- `songs_fts` 是 FTS4 虚表（`SongSearchFtsEntity.kt`），由 `PiscesPlayerDatabase.kt`
  里的 trigger 在 `songs` 表增删改时同步（L56-102）。索引列只有 `title` + `artist_name`。
- 匹配查询由 `buildSongSearchMatchQuery` 构造（`MusicDao.kt:31`）：最多 6 个 token，
  `AND` 连接 + `*` 前缀。unicode61 tokenizer 把连续 CJK 视为**单个 token**，
  所以 FTS 只能前缀匹配；中缀匹配靠 `searchSongsLike` 的 `LIKE '%…%'` 兜底，两路
  `combine` 后按 id 去重。歌词搜索沿用同一套路即可。

### 1.2 歌词数据现状

- `LyricsEntity`（`LyricsEntity.kt`）：`lyrics` 表，`song_id` 主键（覆盖本地与
  Navidrome/Jellyfin 云端歌曲，source_type 无关），`content` 为 LRC 文本
  （含 `[mm:ss.xx]` 时间戳与 `[ti:…]` 等元数据行）。
- 全部写入点都经过 `LyricsDao`（这是本方案的关键收口）：
  - `LyricsRepositoryImpl.kt:510 / 1096 / 1138 / 1314`（内嵌标签读取、远程抓取缓存、
    手动保存、`.lrc` 批量扫描）
  - `LyricsModuleHandler.kt:81 / 102`、`AppDataBackupManager.kt:336-338`（备份恢复）
  - 删除：`LyricsRepositoryImpl.kt:1333 / 1349`
- `SyncWorker` 经由 `LyricsRepository`，同样落入上述收口。

### 1.3 LRCLIB 的能力边界

LrcLib API（`LrcLibApiService.kt`）只有按 `track_name` / `artist_name` / `album_name`
的 `/api/get` 与 `/api/search`，**没有按歌词正文检索的 endpoint**。因此"按歌词找歌"
只能覆盖本地已缓存歌词，不做在线歌词搜索（也不引入新的网络依赖与隐私面，符合
`PRIVACY.md` 的既有口径）。

---

## 2. 方案设计

### 2.1 模式模型：与 Filter 正交的新轴 `SearchMode`

```kotlin
// data/model/SearchMode.kt（新文件）
enum class SearchMode { GENERAL, LYRICS }
```

- `SearchFilterType`（ALL/SONGS/ALBUMS/…）是**结果类型过滤**，保持不变；
- `SearchMode` 是**匹配目标**（元数据 vs 歌词正文）。歌词模式下结果天然只有歌曲，
  因此歌词模式**覆盖** filter chips 的语义（进入歌词模式时隐藏 chips 行）。
- 模式状态放在 `SearchStateHolder`（`_searchMode: MutableStateFlow<SearchMode>`，
  `in-memory` 即可，与 `_selectedSearchFilter` 一致，不落盘）。

### 2.2 数据层：`lyrics_fts` 虚表 + DAO 收口维护

照抄 `songs_fts` 的成例，但**不用 trigger、改在 `LyricsDao` 内收口**——因为歌词
写入方有仓库与备份两条路径，trigger 只能覆盖其中一条，而 DAO 是所有路径的公共
祖先（§1.2）。

**迁移 15 → 16**（`PiscesPlayerDatabase.kt`）：

```sql
CREATE VIRTUAL TABLE IF NOT EXISTS lyrics_fts
    USING FTS4(content, tokenize = unicode61);

INSERT INTO lyrics_fts(rowid, content)
    SELECT song_id, content FROM lyrics WHERE content != '';
```

以**原始 LRC 文本**入索引：时间戳会被切成 `00` `12` 等数字 token，
`[ti:海阔天空]` 的 `ti` 也是无害噪声——用户极少用纯数字/元数据键作词查询，
且与 `songs_fts` 的行为一致。**不在 SQL 层清洗时间戳**（SQLite 无正则，
trigger/迁移里做不了），如需更干净的索引，后续可在 Kotlin 写入侧清洗
（见 §6 可选优化）。

**`LyricsDao` 新增**（全部包进 `@Transaction` 默认方法，调用方零改动）：

```kotlin
@Query("INSERT OR REPLACE INTO lyrics_fts(rowid, content) VALUES (:songId, :content)")
suspend fun upsertFtsRow(songId: Long, content: String)

@Query("DELETE FROM lyrics_fts WHERE rowid = :songId")
suspend fun deleteFtsRow(songId: Long)

@Transaction
suspend fun insertWithFts(lyrics: LyricsEntity) {   // insert / insertAll / replaceAll 同理
    insert(lyrics)
    if (lyrics.content.isBlank()) deleteFtsRow(lyrics.songId)
    else upsertFtsRow(lyrics.songId, lyrics.content)
}
```

空歌词视为"删除"（与 `getSongIdsWithLyrics` 的 `content != ''` 语义对齐）。
`deleteLyrics` / `deleteAll` 同步删 FTS 行。备份恢复路径走 `insertAll`/`replaceAll`
的 FTS 版本，自动保持一致。

**查询**（`MusicDao` 新增，双路合并照抄 `searchSongs`）：

```kotlin
@Query("""
    SELECT songs.* FROM songs
    INNER JOIN lyrics_fts ON lyrics_fts.rowid = songs.id
    WHERE (:applyDirectoryFilter = 0 OR songs.id < 0 OR songs.parent_directory_path IN (:allowedParentDirs))
      AND lyrics_fts MATCH :matchQuery
    ORDER BY songs.title ASC
""")
fun searchSongsByLyricsMatch(...): Flow<List<SongEntity>>

@Query("""
    SELECT songs.* FROM songs
    INNER JOIN lyrics ON lyrics.song_id = songs.id
    WHERE (:applyDirectoryFilter = 0 OR songs.id < 0 OR songs.parent_directory_path IN (:allowedParentDirs))
      AND lyrics.content LIKE '%' || :query || '%'
    ORDER BY songs.title ASC
""")
fun searchSongsByLyricsLike(...): Flow<List<SongEntity>>
```

- FTS 路解决 CJK 整段 token 的前缀匹配与多关键词 `AND`；LIKE 路解决**歌词中缀**
  匹配（"只记得半句歌词"是核心场景，FTS 前缀覆盖不了）。
- `matchQuery` 复用 `buildSongSearchMatchQuery` 的 token 构造（可抽出共用）。

**`MusicRepository` / `MusicRepositoryImpl` 新增**：

```kotlin
fun searchSongsByLyrics(query: String): Flow<List<Song>>
```

内部复用 `searchSongs` 的 allowed/blocked 目录过滤框架与 `toSongsWithFavoriteState()`。

### 2.3 状态层：`SearchStateHolder`

- 新增 `_searchMode` / `updateSearchMode(mode)`；模式切换时若当前 query 非空，
  立即 `performSearch(query)` 重搜。
- `observeSearchRequests` 里分流：

```kotlin
val results = when (_searchMode.value) {
    SearchMode.GENERAL -> musicRepository.searchAll(normalizedQuery, currentFilter)
    SearchMode.LYRICS  -> musicRepository.searchSongsByLyrics(normalizedQuery)
        .map { list -> list.map { SearchResultItem.SongItem(it) } }
}
```

- 现有 `latestSearchRequestId` 防竞态、`debounce`、`sortedWith` 分组排序逻辑原样复用
  （歌词模式结果全是 SongItem，排序无影响）。

### 2.4 UI 层：`SearchScreen.kt`

- **模式开关入口**：在搜索栏 trailing 加一个图标按钮（常规 ⇄ 歌词，用
  `Icons.Outlined.MusicNote` / `Icons.Outlined.EditNote` 或 Lyrics 语义图标），
  带 `Modifier.settingHighlight` 不需要（非设置页）。备选：chips 行尾加一个
  样式区分的「歌词」toggle chip——二选一，**推荐搜索栏 trailing 图标**，
  因为 chips 行在歌词模式下要整体隐藏，开关放在被隐藏的组件里会找不到。
- 歌词模式下：隐藏 FilterChips `FlowRow`（L405-417），结果列表复用现有
  `SearchResultsList`（SongItem 渲染路径不变）。
- 空态文案区分：`EmptySearchResults` 在歌词模式下显示
  「未找到包含该歌词的歌曲」（新字符串，`values/` 与 `values-zh-rCN/` 各一条，
  其他语言留给翻译流程）。
- 搜索历史与歌词模式共用，不做前缀区分（历史条目点击后沿用当前模式搜索即可）。

### 2.5 不做的事

- ❌ 在线歌词搜索（LRCLIB 无此能力，见 §1.3）。
- ❌ 歌词模式下的专辑/艺人/歌单结果（歌词只能定位到歌曲）。
- ❌ 模式持久化（与现有 filter 一致，进程内内存态）。
- ❌ 搜索结果内联歌词片段预览（§6 可选优化）。

---

## 3. 改动文件清单

| 文件 | 改动 |
|---|---|
| `data/model/SearchMode.kt` | 新增枚举 |
| `data/database/PiscesPlayerDatabase.kt` | version 15→16，新增 Migration（建虚表 + 回填） |
| `data/database/LyricsDao.kt` | FTS 收口方法（upsert/delete + @Transaction 包装） |
| `data/database/MusicDao.kt` | `searchSongsByLyricsMatch` / `searchSongsByLyricsLike` / 双路合并方法 |
| `data/repository/MusicRepository.kt` / `MusicRepositoryImpl.kt` | `searchSongsByLyrics()` |
| `presentation/viewmodel/SearchStateHolder.kt` | `_searchMode`、模式分流、切换重搜 |
| `presentation/screens/SearchScreen.kt` | 搜索栏 trailing 模式开关、歌词模式隐藏 chips、空态文案 |
| `res/values/strings.xml`、`res/values-zh-rCN/strings.xml` | 模式开关 contentDescription / 空态文案 |
| `app/src/test/.../SearchStateHolderTest.kt` | 模式分流与切换重搜的单测 |
| `app/src/androidTest/.../`（DAO 测试，如有对应套件） | lyrics_fts 增删改同步与查询的 instrumented 测试 |

备份注意：`lyrics_fts` 不入备份模块（`LyricsModuleHandler` 备份的是 `lyrics` 表本体，
恢复时经 DAO 收口自动重建 FTS，无需迁移备份格式）。

---

## 4. 数据库迁移细节

- `PiscesPlayerDatabase.kt` `version = 16`，新增 `MIGRATION_15_16`：
  1. `CREATE VIRTUAL TABLE IF NOT EXISTS lyrics_fts USING FTS4(content, tokenize = unicode61)`；
  2. `INSERT INTO lyrics_fts(rowid, content) SELECT song_id, content FROM lyrics WHERE content != ''`。
- 迁移纯 SQL、无 Kotlin 回调，符合现有迁移风格（参照 `songs_fts` 的 Migration 14→15，
  `PiscesPlayerDatabase.kt:56` 附近）。回填量级 = 已缓存歌词的歌曲数，几千行文本的
  INSERT 在迁移里可接受（FTS 建索引本身较快）。

## 5. 风险与对策

| 风险 | 对策 |
|---|---|
| CJK 歌词 FTS 只能前缀匹配，"只记得中间半句"搜不到 | LIKE 路兜底（§2.2），这是双路设计的动机 |
| LIKE 全表扫描每击键一次 | 300ms debounce + IO 线程 + 结果 LIMIT（照抄 `SEARCH_RESULTS_LIMIT`）；歌词行数 ≪ 歌曲行数，实测后再评估 |
| 时间戳 token 污染 FTS | 无害噪声（§2.2）；确有必要再 Kotlin 侧清洗 |
| 备份恢复绕过 FTS | DAO 收口保证所有路径一致（§2.2） |
| 歌词模式下 filter chips 语义冲突 | 歌词模式隐藏 chips，模式优先级高于 filter |

## 6. 可选优化（后续迭代）

## 7. 实施记录（2026-11-14）

与本文设计的差异与补充：

1. **`LyricsDao` 的具体收口形态**：原抽象方法改名为 `insertEntity` / `insertAllEntities` /
   `deleteLyricsEntity` / `deleteAllEntities`（保持 @Insert/@Query 抽象），公开入口
   `insert` / `insertAll` / `deleteLyrics` / `deleteAll` / `replaceAll` 为带 `@Transaction`
   的默认方法，内部调用私有 `upsertFtsRow`（空歌词删 FTS 行、非空 upsert）。
   所有既有调用点（`LyricsRepositoryImpl`、`.lrc` 扫描、`LyricsModuleHandler`、
   `AppDataBackupManager`）零改动自动获得 FTS 同步。
2. **模式开关位置**：按 §2.4 推荐落在搜索栏 `trailingIcon`——歌词图标按钮，
   激活时实心 primary 底 + onPrimary 图标，未激活时与清空按钮同款的
   `primaryContainer.copy(alpha=0.2f)` 圆形底。`LaunchedEffect(searchQuery, currentFilter,
   currentSearchMode)` 在模式切换时自动重搜。
3. **UI 状态接线**：`PlayerUiState` 新增 `searchMode` 字段；`PlayerViewModel` 原
   Triple combine 扩为四路（新增私有 `SearchStateSlice`）。
4. **测试修复**：`PlayerViewModelTest` 的 `mockSearchStateHolder` 需补打桩
   `searchMode`（否则 relaxed mock 的 StateFlow 永不发射、combine 卡死导致
   4 个既有搜索测试失败）；`SearchStateHolderTest` 因 holder 跨用例共享，
   `stubRepository()` 里重置 `SearchMode.GENERAL` 防模式泄漏。
5. **新增字符串**：`search_placeholder_lyrics`、`cd_switch_to_lyrics_search`、
   `cd_switch_to_general_search`、`search_no_results_for_lyrics_query`、
   `search_try_different_lyrics`（`strings_screens.xml` 中英双语）。

1. **匹配片段预览**：SQL 用 `instr(lyrics.content, :query)` 取出命中行，
   `SearchResultItem.SongItem` 增加可选 snippet 字段，在结果列表副标题展示命中歌词行。
2. **写入侧清洗**：`LyricsDao` 收口处用 Kotlin 正则剥掉 `[mm:ss.xx]` / `[ti:…]` 后再入
   FTS，索引更干净；迁移回填需配一次性重建。
3. **bm25 排序**：`ORDER BY bm25(lyrics_fts)` 按相关度排，命中多的歌靠前。
4. **模式持久化**：若用户反馈希望记住上次的模式，再按「新增设置开关」流程落
   `UserPreferencesRepository`（本方案默认不做）。
