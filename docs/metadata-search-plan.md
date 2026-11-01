# PixelPlayerOSS 在线元数据搜索与回填 — 实施方案

> 状态：**Phase 1 已实施**（2026-11-01，commit `49ec7e41`）。实际落地与本文的差异、
> 验证记录与已知限制见 [`metadata-search-status.md`](metadata-search-status.md)。
> 本文保留设计原貌；§12 的行号基于实施前基线 `624d6cda`，Phase 1 之后已发生偏移。

- 日期：2026-11-01
- 对象仓库：本仓库（branch `main`，基线 `624d6cda`）
- 许可证：GPL-3.0-or-later
- 配套调研：《音乐标签》取证结论（本地调研文档，未随仓库发布；相关结论已并入本文 §3、§5.8、§5.9）

---

## 0. 结论摘要

PixelPlayerOSS 已经具备这套功能大约 70% 的基础设施：

- 在线搜索：已有 MusicBrainz 录音搜索、Lucene 转义、1.1s 限速、候选打分。
- 标签写入：已有覆盖 MP3/FLAC/M4A/OGG/Opus/WAV/AIFF 的完整写入器，能同时更新文件标签、MediaStore、Room、封面和发行日期。
- 缺口在“候选信息不够全、没有预览选择、没有封面抓取、年份不回写、不能批量”。

交互按用户要求调整为《音乐标签》式的四步：

1. **用“文件名 + 现有标签”组查询**，标签缺失或明显是占位符时用文件名兜底；
2. 搜索结果先列表展示，**用户检查并选中候选**；
3. 选中后看字段级“旧值 → 新值”，逐项勾选；
4. 应用时再选 **“只更新数据库”** 或 **“文件和数据库都更新”**。

推荐做法不是移植《音乐标签》的多平台代码，而是：

1. **先把 MusicBrainz + Cover Art Archive 的单曲闭环做完整**（Phase 1）；
2. **再做按专辑分组的批量匹配**（Phase 2）；
3. 歌词、第二数据源、设置开关后置（Phase 3）。

不建议接入网易云 / QQ / 酷狗 / 酷我的逆向接口。原因在配套调研里已有取证：这些接口依赖私有加密、硬编码 cookie、非公开 endpoint，且服务条款明确限制第三方客户端；GPL 应用公开分发会把接口逆向和许可证风险一起放大。

**覆盖面结论：MusicBrainz 不算“全”。** 全球总量大（2026-11-01：5,896,149 个 release、40,786,058 个 recording），但语种/地区分布严重偏向英文和日本；中文主流歌的搜索命中率比统计数字好，冷门、独立、数字单曲、现场/翻唱版本和封面缺口明显。详见 §5.8。

Phase 1 预计新增 8 个代码文件 + 4 个测试文件、修改 8 个文件，**不需要数据库迁移，不需要新权限**。

---

## 1. 现状审计

### 1.1 已有能力

| 能力 | 现状 | 代码位置 |
|---|---|---|
| 读取文件标签 | TagLib 主路径 + JAudioTagger/VorbisJava 兜底 | `data/media/AudioMetadataReader.kt` |
| 写入文件标签 | 按容器路由 TagLib / JAudioTagger / VorbisJava | `data/media/SongMetadataEditor.kt:232` |
| 写封面 | `CoverArtUpdate(bytes, mime)`，同时清缓存、更新 `album_art_uri_string` | `SongMetadataEditor.kt:497`、`:1514` |
| 写 MediaStore | 标题/艺术家/专辑/专辑艺术家/流派/轨道/碟号 | `SongMetadataEditor.kt:472` |
| 写 Room | `updateSongMetadataAndArtistLinks`，并设置 `*_user_edited=1` | `MusicDao.kt:1818`、`:1846` |
| 发行日期 | 自定义字段 `DATE` 可写；写入后重读文件并更新 `release_date` | `SongMetadataEditor.kt:465`、`:554` |
| MusicBrainz 搜索 | 录音搜索、官方 release 优先、时长纠偏 | `data/musicbrainz/MusicBrainzApiService.kt:51` |
| 候选打分 | 标题/艺术家/专辑精确匹配加分，时长偏差减分 | `MusicBrainzApiService.kt:193` |
| 应用匹配结果 | **仅 DB、仅填空**，不写文件 | `data/musicbrainz/MusicBrainzRepository.kt:24` |
| 单曲入口 | SongInfoBottomSheet 的 “Find on MusicBrainz” | `presentation/components/SongInfoBottomSheet.kt:699` |
| 状态管理 | `MusicBrainzUiState`（Idle/Loading/Results/Error/Applied） | `presentation/viewmodel/SongInfoBottomSheetViewModel.kt:75` |
| 歌词 | LRCLIB 搜索/下载已存在，播放器内可手动搜 | `data/network/lyrics/LrcLibApiService.kt`、`LyricsStateHolder.kt:271` |

### 1.2 关键缺口

1. **候选信息不全**
   MusicBrainz 搜索结果只有 `recordingId/releaseId/artistId/title/artist/album/year/duration/score`，没有专辑艺术家、发行日期（完整）、轨道号、碟号、流派、封面。不能直接用来填一张完整的标签。

2. **没有预览与字段选择**
   当前点候选就直接 `applyMusicBrainzMatch`，用户看不到“旧值 → 新值”，也不能只选其中几个字段。

3. **没有封面抓取**
   `MusicBrainzMatch` 没有封面 URL；仓库里也没有 Cover Art Archive 客户端。

4. **应用结果不写文件**
   `MusicBrainzRepository.apply()` 只调用 `musicDao.applyMusicBrainzMatch()`，SQL 里甚至只填空白字段（`MusicDao.kt:1926`）。这与《音乐标签》的“写回文件”不是一回事。

5. **年份不会更新**
   `SongMetadataEditor.editSongMetadata()` 没有 `year` 参数。写入 `DATE` 只会让 `syncReleaseDateAfterEdit()` 更新 `release_date`；`songs.year` 仍是旧值，Years 视图不会变。需要补一条 DB 更新。

6. **没有批量**
   当前只能一首一首点。

7. **云曲目没有区分**
   Navidrome/Jellyfin 歌曲没有本地文件。`isSongEditable()` 已经能识别（`SongInfoBottomSheetViewModel.kt:241`），但新的应用流程必须显式分流：本地写文件，云端只写 DB。

8. **没有文件名查询**
   搜索直接用 `song.title / displayArtist / album`，标签是 `Unknown` 或空时查不到；没有文件名解析，也没有“标签失败换文件名”的兜底。

9. **没有“写库 / 写文件”选择**
   现状是两个割裂的入口：MusicBrainz 应用只写库，编辑页只写文件。用户无法在同一个确认流程里选择目标。

10. **没有简繁/别名兜底**
    实测简体艺术家查询会漏掉繁体索引（`赵雷` → 0，`趙雷` → 6）。现有查询没有变体重试。

### 1.3 现有写入链路（复用点）

```
SongInfoBottomSheet
  └─ EditSongSheet.onSave
      └─ SongInfoBottomSheetViewModel / MetadataEditStateHolder.saveMetadata()   ← 推荐复用入口
          ├─ SongMetadataEditor.editSongMetadata()
          │   ├─ 写文件标签
          │   ├─ 写封面
          │   ├─ 更新 MediaStore
          │   ├─ 更新 Room（含 *_user_edited=1）
          │   └─ 重读文件更新 release_date
          ├─ 更新歌词（如有）
          ├─ 失效封面缓存
          └─ 强制重建主题色
```

新的在线回填不应绕开 `MetadataEditStateHolder`，否则会漏掉封面缓存、主题色、歌词和错误映射。

`MetadataEditStateHolder.saveMetadata()` 的现有签名（`presentation/viewmodel/MetadataEditStateHolder.kt:56`）已经覆盖：

```kotlin
saveMetadata(
    song, newTitle, newArtist, newAlbum, newAlbumArtist,
    newComposer, newGenre, newLyrics,
    newTrackNumber, newDiscNumber,
    newReplayGainTrackGainDb, newReplayGainAlbumGainDb,
    coverArtUpdate, customMetadataChanges
)
```

---

## 2. 与《音乐标签》的能力对照

| 能力 | 《音乐标签》 | 本方案 | 说明 |
|---|---|---|---|
| 读本地标签 | TagLib | TagLib + JAudioTagger + VorbisJava | 已有 |
| 写回文件 | TagLib | 已有完整写入器 | 已有 |
| 在线源数量 | 6 个在用 + 2 个残留 | 1 个（MusicBrainz）+ CAA | 合规取舍 |
| 国内曲库覆盖 | 网易/QQ/酷狗/酷我 | 无 | 见 §3.2、§5.8 |
| 查询来源 | 标签 + 文件名 | 标签 + 文件名 | Phase 1 |
| 简繁/别名 | 有 | 需要补 | 见 §7.10 |
| 匹配算法 | 全半角/简繁/相似度 | MB score + 精确匹配 + 时长 | 够用 |
| 封面 | 各平台封面 | Cover Art Archive | Phase 1 |
| 歌词 | 网易/QQ/酷狗 | LRCLIB（已有，独立入口） | Phase 3 可选 |
| 字段预览 | 有 | 有 | Phase 1 |
| 写库 / 写文件可选 | 有 | 有 | Phase 1 |
| 批量 | 有（默认 2 线程） | 按专辑分组 | Phase 2 |
| 写入范围 | 标签 + 可选文件名 | 标签 + 封面 + DB | 不做文件重命名 |
| 合规风险 | 高 | 低 | 见 §3.3 |

---

## 3. 范围与合规边界

### 3.1 纳入

- 用“文件标签 + 文件名”组查询，查询串可编辑
- MusicBrainz 录音搜索 + release 详情补全
- Cover Art Archive 封面预览与写入
- 单曲候选列表、字段级差异预览、勾选应用
- 应用目标可选：**只更新数据库** / **文件和数据库都更新**
- 本地文件写标签；云端曲目仅写 DB
- 批量匹配（Phase 2）
- 中英文界面文案

### 3.2 明确不纳入

| 项 | 理由 |
|---|---|
| 网易云 `weapi` | 依赖逆向出的 AES 常量、硬编码 `_m_h5_tk`、非公开接口；服务条款禁止第三方客户端 |
| QQ 音乐 `musicu.fcg` | 非公开接口，依赖特定 UA/Referer；条款限制 |
| 酷狗 / 酷我 | 同上，且酷我需伪装旧版客户端 |
| Last.fm / VGMdb / MiniLyrics | 《音乐标签》里要么硬编码 key，要么已停用；不引入新的凭据与条款风险 |
| iTunes Search | 无需 key、中文曲库较强，但条款限制缓存/展示与用途；如要作为第二源，需单独评审后再做 |
| 文件重命名/整理 | 与元数据回填是两件事，另开方案 |
| 自动后台全库匹配 | 批量必须用户发起、可取消、可预览 |

### 3.3 合规红线

1. **不移植反编译代码。** 只参考公开 API 的语义（字段名、返回结构），不复制《音乐标签》的类、加密函数、常量、URL 拼接代码。
2. **不引入任何第三方密钥。** 仓库里不得出现 Last.fm key、cookie、token。
3. **MusicBrainz 必须带 UA 且 ≤1 req/s。** 现有 `MusicBrainzApiService` 已满足；新增的 release 查询必须复用**同一个**限速器，不能另起一个 Mutex。
4. **Cover Art Archive 也要带 UA、限速、校验内容类型和大小。** 封面版权归上传者/权利人，应用只写入用户本地文件，不再分发。
5. **歌词只在用户主动操作时获取。** 歌词本身有版权，不做批量自动下载。
6. **新代码按 GPL-3.0-or-later 提交。** 不触碰 PixelPlayer 专有代码；不整文件复制。

---

## 4. 目标交互（单曲）

```
SongInfoBottomSheet
  [在线匹配元数据]  ← 复用/替换现有 "Find on MusicBrainz"
        ↓
MetadataSearchSheet
  ┌──────────────────────────────────────────┐
  │ 查询来源  [x] 文件标签   [x] 文件名       │
  │ 标题   [晴天                            ] │
  │ 艺术家 [周杰伦                          ] │
  │ 专辑   [葉惠美                          ] │
  │ 实际查询: recording:"晴天" AND artist:"周杰伦" │
  │                                  [搜索]   │
  ├──────────────────────────────────────────┤
  │ [封面] 晴天 · 周杰倫 · 葉惠美 · 2003    │
  │        MusicBrainz · 100 分 · Official   │
  │ [封面] 晴天 · 周杰倫 · 演唱會 · 2013     │
  │        MusicBrainz · 100 分 · Live       │
  │ ...                                      │
  └──────────────────────────────────────────┘
        ↓ 用户选中候选
MetadataPreviewSheet
  ┌──────────────────────────────────────────┐
  │ [封面 500px]  专辑 / 艺术家 / 年份        │
  ├──────────────────────────────────────────┤
  │ ☑ 标题       旧: 晴天      新: 晴天      │
  │ ☑ 艺术家     旧: 周杰伦    新: 周杰倫    │
  │ ☑ 专辑       旧: Unknown   新: 葉惠美    │
  │ ☑ 专辑艺术家 旧: —         新: 周杰倫    │
  │ ☑ 年份/日期  旧: 0         新: 2003-07-31│
  │ ☑ 轨道/碟号  旧: 0/—       新: 3/1       │
  │ ☐ 流派       旧: —         新: Pop       │
  │ ☑ 封面       旧: 无        新: 500px     │
  ├──────────────────────────────────────────┤
  │ 应用方式  (•) 文件和数据库  ( ) 只更新数据库 │
  │ [仅填充空白] 开关（默认开）                │
  │                  [取消]  [应用所选字段]   │
  └──────────────────────────────────────────┘
```

应用后：

- 成功：关闭预览，刷新 SongInfo 与编辑页状态，Toast/Snackbar 显示“已更新 N 个字段”。
- 部分失败：保留预览并标红失败字段，允许重试。
- 云端曲目：文件选项禁用，只保留“只更新数据库”，按钮文案改为“写入资料库”。
- 本地文件不可写：不静默降级；弹窗告知并让用户选择“只更新数据库”或放弃。

### 4.1 查询串怎么来

搜索前先合成一个可编辑的查询，不要让用户从空白开始填。

**字段优先级**

| 查询字段 | 取值顺序 |
|---|---|
| 标题 | 非空且非 `Unknown Title` 的文件标签 → 文件名解析结果 |
| 艺术家 | 非空且非 `Unknown Artist` 的文件标签 → 文件名解析结果 |
| 专辑 | 非空且非 `Unknown Album` 的文件标签 → 文件名解析结果（可选） |

**文件名解析规则**

文件名只在标签缺失/占位/明显是文件名时使用，解析结果必须显示在输入框里让用户确认，不能直接拿去做写入。“明显是文件名”包括：值里带扩展名、带开头轨号、或与文件名 stem 完全相同。

1. 去掉扩展名（`.mp3`、`.flac`、`.m4a`、`.opus`、`.wav`、`.dsf` 等）。
2. 去掉开头的音轨号：`01 -`、`01.`、`01_`、`1、`、`01 `。
3. 识别常见结构：
   - `Artist - Title`
   - `Artist - Album - 01 - Title`
   - `01 - Artist - Title`
   - `Title - Artist`（有歧义，优先用标签消除；没有标签时默认按 `Artist - Title`）
   - `Title`（只有标题）
4. 清理技术性噪声：`320k`、`128k`、`FLAC`、`APE`、`WAV`、`DSD`、`Hi-Res`、`HQ`、`MV`、`Official`、`Lyrics`、`歌词`。
5. **不要**删除有版本语义的词：`Live`、`Remix`、`Instrumental`、`伴奏`、`Acoustic`、`Demo`。这些会影响候选选择。
6. 统一全角/半角分隔符：`－`、`—`、`｜`、`·`、`／` 归一为普通分隔符；连续空格压成一个。

**重试策略**

按顺序尝试，前一步有结果就停：

1. `title + artist + album`
2. `title + artist`
3. `title only`，再在本地按艺术家相似度过滤（解决简繁不一致，见 §7.10）
4. 用艺术家别名/MBID 搜索（`arid:`），再按标题过滤
5. 标题和艺术家的简体/繁体变体各试一次

每次搜索都把实际查询串展示出来，用户可以直接改。这样即使自动组词失败，也不挡住用户。

### 4.2 应用到哪：数据库 vs 文件

| 目标 | 写文件标签 | 写 MediaStore | 写 Room | 封面 | 适用场景 |
|---|---|---|---|---|---|
| **文件和数据库都更新** | ✅ | ✅ | ✅ | 写内嵌封面 + 更新 `album_art_uri_string` | 本地可写文件；默认 |
| **只更新数据库** | ❌ | ❌ | ✅ | 云端用远程 URL；本地不新增内嵌封面 | 只读存储、云曲目、用户不想改文件 |

“只更新数据库”必须注意：

- 要把 `title/artist/album/genre_user_edited` 置 1，否则下一次同步可能用文件/MediaStore 的旧值覆盖；
- 文件里的标签仍然是旧值，其他播放器看到的也是旧值；
- 如果之后执行“从文件重新扫描标签”，需要尊重 `*_user_edited` 标记，否则仍会被覆盖；
- 云端曲目只能走这一条，封面只能更新 `album_art_uri_string`。

### 4.3 默认勾选策略

| 当前值 | 默认 |
|---|---|
| 空 / `Unknown Title` / `Unknown Artist` / `Unknown Album` | 勾选 |
| `year <= 0`、`trackNumber <= 0`、无封面 | 勾选 |
| 已有值且与候选不同 | 不勾选（用户手动勾） |
| `*_user_edited = 1` | 不勾选，并显示“手动编辑过”标记 |
| 候选字段为空 | 不可勾选 |

“仅填充空白”开关打开时，等价于上表；关闭时只保留用户手动勾选。

---

## 5. 架构设计

### 5.1 分层

```
presentation/
  components/MetadataCandidateSheet.kt        新增：候选列表 + 预览 + 字段勾选
  viewmodel/MetadataMatchStateHolder.kt       新增：搜索/详情/应用状态机
  viewmodel/SongInfoBottomSheetViewModel.kt   修改：注入 StateHolder，转发入口
  components/SongInfoBottomSheet.kt           修改：按钮文案与入口

data/metadata/                                新增：领域模型、查询组词与策略
  MetadataCandidate.kt                        （含 MetadataQuery、MetadataFieldSelection、MetadataApplyTarget）
  MetadataMergePolicy.kt
  FileNameMetadataParser.kt
  MetadataQueryBuilder.kt

data/musicbrainz/
  MusicBrainzApiService.kt                    修改：release 详情查询（共享限速器）
  MusicBrainzReleaseParser.kt                 新增：release JSON → 详情
  CoverArtArchiveService.kt                   新增：封面下载
  MusicBrainzRepository.kt                    修改：searchDetails/applySelected

data/media/
  SongMetadataEditor.kt                       不改核心；必要时只补 year 回写
  MetadataEditStateHolder.kt                  修改：新增 applyOnlineMetadata()

data/database/
  MusicDao.kt                                 修改：updateSongMbIdsAndDate()、applySelectedMetadata()

di/
  AppModule.kt                                修改：提供 CoverArtArchiveService（如需要）
```

### 5.2 领域模型

```kotlin
enum class MetadataSource { MUSICBRAINZ }

enum class MetadataApplyTarget {
    DATABASE_ONLY,
    FILE_AND_DATABASE
}

data class MetadataQuery(
    val title: String,
    val artist: String,
    val album: String?,
    val rawQuery: String,
    val usedFilename: Boolean
)

data class MetadataCandidate(
    val source: MetadataSource,
    val recordingId: String?,
    val releaseId: String?,
    val releaseGroupId: String?,   // release 详情补齐，CAA 回退用
    val artistId: String?,
    val title: String,
    val artist: String,
    val album: String,
    val albumArtist: String?,
    val year: Int,
    val releaseDate: String?,      // yyyy-MM-dd 或 null
    val trackNumber: Int?,
    val discNumber: Int?,
    val genre: String?,
    val durationMs: Long?,
    val score: Int,
    val disambiguation: String?,
    val releaseStatus: String?,     // Official / Bootleg ...
    val releaseType: String?,       // Album / Single / EP ...
    val coverArtUrl: String?        // front-500，可能为空
)

data class MetadataFieldSelection(
    val title: Boolean = false,
    val artist: Boolean = false,
    val album: Boolean = false,
    val albumArtist: Boolean = false,
    val releaseDate: Boolean = false,
    val trackNumber: Boolean = false,
    val discNumber: Boolean = false,
    val genre: Boolean = false,
    val coverArt: Boolean = false,
    val lyrics: Boolean = false
)
```

候选里保留 `recordingId/releaseId/artistId`，方便应用成功后写 `mb_*` 列。现有 `MusicBrainzMatch` 可以逐步并入 `MetadataCandidate`，或先做适配器，避免一次性改坏现有测试。

### 5.3 MusicBrainz release 详情

搜索结果只够列表展示。用户点开候选时再查一次 release：

```
GET https://musicbrainz.org/ws/2/release/{releaseId}
    ?inc=recordings+artist-credits+genres+release-groups
    &fmt=json
```

`recordings` 会带出 `media[]` 和 `tracks[]`。需要解析：

| 目标字段 | JSON 路径 | 处理 |
|---|---|---|
| 专辑名 | `release.title` | 直接 |
| 专辑艺术家 | `release.artist-credit` | 拼 `name + joinphrase` |
| 发行日期 | `release.date` | 优先于录音的 `first-release-date` |
| 碟号 | `media[].position` | 与 `recordingId` 匹配的那条 |
| 轨道号 | `tracks[].number` | 取数字前缀；`A1` 这类黑胶编号无法转数字则留空 |
| 流派 | `genres[].name` | 取 `count` 最高的一项；没有则 `tags[]` |
| 时长校验 | `tracks[].length` | 与本地 `song.duration` 比对 |

轨道匹配优先级：

1. `tracks[].recording.id == candidate.recordingId`；
2. 标题归一化相等；
3. 时长差 ≤ 2s。

多碟时 `trackNumber` 是碟内轨号，不是总轨号；`discNumber` 来自 `media[].position`。

**release 选择**：搜索结果里的 `releases[]` 可能包含 Bootleg、Live、Compilation。现有 `chooseRelease()` 已经偏向 Official。建议再加两条：

- 优先 `status == Official`；
- 优先 `release-group.primary-type` 为 Album/EP/Single，避开 Live/Compilation（需要 `inc=release-groups`）。

### 5.4 Cover Art Archive

```
https://coverartarchive.org/release/{releaseId}/front-500
https://coverartarchive.org/release-group/{releaseGroupId}/front-500   // release 无封面时回退
```

实现要求：

- 使用带 UA 的 OkHttp；跟随 307 到 `archive.org`。
- `404` 表示无封面，不当作错误。
- 校验 `Content-Type` 以 `image/` 开头。
- 限制最大字节数（建议 8MB），避免 OOM。
- 预览用 Coil 加载同一个 URL；写入时用 OkHttp 拿 bytes，交给 `CoverArtUpdate(bytes, mimeType)`。
- 与 MusicBrainz 共用 1 req/s 节奏；CAA 没有硬性 1 rps，但保持礼貌。
- 可选：把 `front-500` 换成 `front-1200` 作为写入尺寸，预览仍用 500。

### 5.5 默认应用策略

```kotlin
object MetadataMergePolicy {
    fun defaultSelection(
        song: Song,
        candidate: MetadataCandidate,
        fillBlankOnly: Boolean = true
    ): MetadataFieldSelection
}
```

判定“空白”的规则要集中在一处，避免 UI 和写入链路各写一套：

- `title.isBlank()` 或 `equals("Unknown Title", ignoreCase = true)`
- `displayArtist.isBlank()` 或 `equals("Unknown Artist", ...)`
- `album.isBlank()` 或 `equals("Unknown Album", ...)`
- `year <= 0`
- `trackNumber <= 0`
- `discNumber == null || discNumber <= 0`
- `albumArtUriString == null`
- `genre.isNullOrBlank()`

`*_user_edited` 标记目前没有暴露在 `Song` 模型里（只在 `SongEntity`）。应用前需要从 DAO 查一次实体，或者给 `Song` 增加只读字段。建议在 `MetadataMatchStateHolder` 里查 `SongEntity`，不扩大 `Song` 的公共模型。

### 5.6 写入链路

推荐在 `MetadataEditStateHolder` 增加一个语义明确的方法：

```kotlin
suspend fun applyOnlineMetadata(
    song: Song,
    candidate: MetadataCandidate,
    selection: MetadataFieldSelection,
    coverArt: CoverArtUpdate?,
    target: MetadataApplyTarget
): MetadataEditResult
```

**分支 A：文件和数据库都更新**（本地可写文件）

```kotlin
val customFields = buildList {
    if (selection.releaseDate) {
        candidate.releaseDate?.let { add(CustomMetadataFieldUpdate("DATE", it)) }
    }
}
val customChanges = CustomMetadataChanges(fields = customFields)

val result = saveMetadata(
    song = song,
    newTitle = if (selection.title) candidate.title else song.title,
    newArtist = if (selection.artist) candidate.artist else song.artist,
    newAlbum = if (selection.album) candidate.album else song.album,
    newAlbumArtist = if (selection.albumArtist) candidate.albumArtist ?: song.albumArtist ?: "" else song.albumArtist ?: "",
    newComposer = null,                    // MB release 查询不含 composer
    newGenre = if (selection.genre) candidate.genre else song.genre,
    newLyrics = null,                      // 歌词独立流程
    newTrackNumber = if (selection.trackNumber) candidate.trackNumber ?: song.trackNumber else song.trackNumber,
    newDiscNumber = if (selection.discNumber) candidate.discNumber ?: song.discNumber else song.discNumber,
    coverArtUpdate = if (selection.coverArt) coverArt else null,
    customMetadataChanges = customChanges
)

if (result.success) {
    musicDao.updateSongMbIdsAndDate(
        songId = resolvedSongId,
        year = if (selection.releaseDate) candidate.year else null,
        releaseDate = if (selection.releaseDate) candidate.releaseDate else null,
        recordingId = candidate.recordingId,
        releaseId = candidate.releaseId,
        artistId = candidate.artistId
    )
}
```

**分支 B：只更新数据库**（云曲目、只读存储、用户选择）

```kotlin
val result = musicDao.applySelectedMetadata(
    songId = resolvedSongId,
    // null 表示该字段不更新
    title = candidate.title.takeIf { selection.title },
    artist = candidate.artist.takeIf { selection.artist },
    album = candidate.album.takeIf { selection.album },
    albumArtist = candidate.albumArtist.takeIf { selection.albumArtist },
    genre = candidate.genre.takeIf { selection.genre },
    trackNumber = candidate.trackNumber.takeIf { selection.trackNumber },
    discNumber = candidate.discNumber.takeIf { selection.discNumber },
    year = candidate.year.takeIf { selection.releaseDate },
    releaseDate = candidate.releaseDate.takeIf { selection.releaseDate },
    coverArtUrl = candidate.coverArtUrl.takeIf { selection.coverArt && isCloudSong },
    recordingId = candidate.recordingId,
    releaseId = candidate.releaseId,
    artistId = candidate.artistId,
    markTitleEdited = selection.title,
    markArtistEdited = selection.artist,
    markAlbumEdited = selection.album,
    markGenreEdited = selection.genre
)
```

这个分支必须写 `*_user_edited` 标记，否则下一次同步会用文件/MediaStore 的旧值覆盖数据库。只改 `artist_name` 字符串是不够的：艺术家页、分组和详情页依赖 `artist_id` / `artists_json` / `song_artist_cross_ref`，DB-only 修改艺术家时必须复用现有的 ensureArtist + cross-ref 逻辑（建议抽一个 `updateSongMetadataAndArtistLinks` 的 partial 版本），否则会出现“歌名改了、艺术家分组还是旧的”。

注意事项：

- `saveMetadata()` 会保留已有内嵌封面；只有用户勾选封面时才传 `CoverArtUpdate`。
- `newGenre` 传 `song.genre` 保持原值，不要传 `null`（`null` 在编辑器里表示不写，但 UI 语义容易混淆）。
- `DATE` 走 `CustomMetadataChanges`，因为固定编辑器没有 year 字段；`CustomMetadata.kt` 的保留键里没有 `DATE`。
- 年份回写必须在新 DAO 方法里同时更新 `year` 与 `release_date`，否则 Years 视图不刷新。
- MusicBrainz ID 建议顺手写入 `mb_recording_id/mb_release_id/mb_artist_id`。本地文件写入后需要一条 DAO 更新；云端曲目则整条走 DB。
- 文件+数据库模式下，`saveMetadata()` 已经把四个 `*_user_edited` 置 1；DB-only 模式需要显式按选中字段置位，不能沿用旧 SQL。
- DB-only 模式不写内嵌封面；本地歌曲只更新 `album_art_uri_string` 不足以保证其他播放器可见，UI 要明确提示。云端歌曲可以存远程封面 URL。

云端曲目（Navidrome/Jellyfin）：

- 不调用 `SongMetadataEditor`；
- 走分支 B；
- 封面只能更新 `albumArtUriString` 为远程 URL，不能写文件；
- 写入后需要让列表/详情页重新观察数据库，避免 UI 还显示旧值。

### 5.7 批量（Phase 2）

不要逐首调 recording 搜索。按专辑分组效率高得多：

1. 取本地歌曲，按 `album_name + album_artist` 分组；
2. 每组用 MusicBrainz release 搜索（`release:"..." AND artist:"..."`）；
3. 选中最佳 release 后，一次 release 详情就能拿到整张专辑的 tracks；
4. 用 `recordingId` / 标题 / 时长把本地歌曲映射到 tracks；
5. 生成每首的 `MetadataCandidate` 和默认选择；
6. 用户确认后写入。

批处理约束：

- 单线程 + 1 req/s，避免触发 MusicBrainz 429；
- 用 WorkManager 前台任务，显示进度和当前歌曲，可取消；
- 每首独立记录结果，失败不阻塞后续；
- 自动只应用“高置信度”字段：`score >= 90` 且标题/艺术家归一化相等且时长差 ≤ 5s；其余标记为“需人工确认”；
- 跳过云端曲目或只做 DB 更新（由开关决定）。

### 5.8 覆盖面评估（MusicBrainz 到底全不全）

#### 官方总量（2026-11-01）

| 指标 | 数量 |
|---|---:|
| Artists | 3,029,782 |
| Release groups | 4,614,860 |
| Releases | 5,896,149 |
| Recordings | 40,786,058 |
| Official releases | 5,348,024（90.7%） |
| Releases with CAA front cover | 3,920,102（66.5%） |
| Releases without front cover | 1,976,047（33.5%） |

#### 语种与地区分布

MusicBrainz 的统计页按 release 的 `language` 和 `country` 字段统计；这两个字段都可以不填，所以下面的数字是**下限**，但仍能说明结构性问题。

| 语种 | Releases | 占比 |
|---|---:|---:|
| English | 3,721,580 | 63.1% |
| Japanese | 259,293 | 4.4% |
| Spanish | 158,761 | 2.7% |
| Chinese | 24,753 | 0.42% |
| Mandarin Chinese | 2,343 | 0.04% |
| Yue Chinese | 76 | ~0% |
| Korean | 13,689 | 0.23% |
| Unknown language | 839,522 | 14.2% |

中文（Chinese + Mandarin + Yue）合计约 **27,172** 个 release，占全部 release 的 **0.46%**。

| 国家/地区 | Artists | Releases |
|---|---:|---:|
| United States | 366,854 | 881,851 |
| United Kingdom | 134,629 | 462,448 |
| Japan | 121,576 | 393,734 |
| South Korea | 17,282 | 64,394 |
| Hong Kong | 3,645 | 51,831 |
| Taiwan | 3,474 | 50,440 |
| China | 7,660 | 22,510 |

中国大陆 + 香港 + 台湾合计约 **124,781** 个 release，占 2.1%；对比美国 88 万、英国 46 万、日本 39 万。

#### 实际抽样（2026-11-01，查询 `recording:"标题" AND artist:"艺术家"`）

12 首中文歌：

| 歌曲 | 结果数 | 首条结果 |
|---|---:|---|
| 林俊杰 - 江南 | 7 | 江南 / 林俊傑 |
| 陈奕迅 - 十年 | 12 | 十年 / 陳奕迅 |
| 王菲 - 红豆 | 11 | 红豆 / 王菲 |
| 邓丽君 - 月亮代表我的心 | 31 | 月亮代表我的心 / 鄧麗君 |
| 李荣浩 - 模特 | 2 | 模特 / 李荣浩 |
| 薛之谦 - 演员 | 2 | 演员 / 薛之谦 |
| 毛不易 - 消愁 | 2 | 消愁 / 毛不易 |
| 赵雷 - 成都 | **0** | — |
| 陈粒 - 小半 | 1 | 小半 / 陈粒 |
| 房东的猫 - 云烟成雨 | 1 | 云烟成雨 / 房东的猫 |
| 隔壁老樊 - 我曾 | 2 | 我曾 / 隔壁老樊 |
| 周深 - 大鱼 | 6 | 大鱼 / 周深 |

直接命中 11/12。唯一漏掉的《成都》不是数据不存在：

- `recording:"成都" AND artist:"赵雷"`（简体）→ 0；
- `recording:"成都" AND artist:"趙雷"`（繁体）→ 6，首条正是“成都 / 赵雷”；
- `recording:"成都"`（只按标题）→ 27，首条也是“成都 / 赵雷”。

也就是说，**简繁差异会造成假阴性**；加上“标题单独搜索 + 本地艺术家过滤”或简繁转换后，这 12 首可以全部找到。这个坑必须在 Phase 1 解决（§7.10）。

对照组：

| 歌曲 | 结果数 | 首条结果 |
|---|---:|---|
| 米津玄師 - Lemon | 4 | Lemon / 米津玄師 / STRAY SHEEP |
| Taylor Swift - Anti-Hero | 26 | Anti-Hero / Taylor Swift / 2022 MegaMix |

#### 封面抽样

对上面 6 个 release 直接请求 `coverartarchive.org/release/{id}/front-500`：

| Release | 结果 |
|---|---|
| 周杰伦《葉惠美》 | 404，无封面 |
| 邓紫棋《光年之外》 | 404，无封面 |
| 五月天《天空之城》 | 404，无封面 |
| 米津玄師《STRAY SHEEP》 | 307 跳转 archive.org（有封面） |
| Taylor Swift《Midnights》 | 307 跳转 archive.org（有封面） |
| 买辣椒也用券《起风了》 | 307 跳转 archive.org（有封面） |

样本很小，不能当统计结论，但方向和总量数据一致：**中文 release 的封面覆盖明显弱于英文/日文**。另外，从本机网络访问 `archive.org` 超时，说明 CAA 的 307 跳转在部分网络环境不可达，客户端必须有超时和“无封面”降级，不能让它拖死整个应用流程。

#### 结论：适合谁，不适合谁

| 曲库类型 | 预期 |
|---|---|
| 欧美流行/摇滚/金属/爵士/古典 | 好；release 和录音版本多，封面覆盖较好 |
| 日本主流动画/游戏/流行 | 较好；`Lemon` 这类主流曲目命中稳定 |
| 华语主流（周杰伦、陈奕迅、王菲、五月天等） | **搜索命中比统计数字好**，但 release 版本、封面、曲目号经常缺失 |
| 华语独立、网络歌手、数字单曲、短视频热歌 | 弱；可能只有 recording，没有完整 release |
| 现场、翻唱、Remix、DJ、伴奏、冷门版本 | 弱且容易错配 |
| 最新发行 | 有滞后，取决于社区录入 |

所以：**MusicBrainz 能覆盖“主流 + 有实体/正规发行”的部分，不能覆盖整个华语曲库。** 如果用户的库以华语主流为主，配合简繁归一和标题兜底后可用；如果以冷门/独立/数字单曲为主，单靠它会明显不够。

#### 提升覆盖面的三级策略

1. **先把查询做聪明（Phase 1，必做）**
   - 简繁转换 + 别名；
   - 标签失败时用文件名；
   - 标题单独搜索 + 本地艺术家过滤；
   - 艺术家 MBID 解析后按 `arid:` 搜索；
   - 搜索无结果时给明确提示，而不是空列表。

2. **在自己的曲库上量化（Phase 1 之前/并行）**
   - 从用户库随机抽 100 首（本地文件 + 云曲目分开统计）；
   - 按 1 req/s 查询 MusicBrainz；
   - 统计：标题+艺术家命中率、release 完整率、封面可得率、简繁修正后的增益；
   - 输出按语种/艺术家的分组报告，决定是否需要第二源。
   - 这是唯一能回答“**我的库**覆盖率是多少”的办法；公开统计只能给方向。

3. **按需加第二源（Phase 2/3，单独评审）**

   | 候选源 | 优点 | 代价/风险 | 本机实测 |
   |---|---|---|---|
   | Discogs | 全球最大实体唱片库；专辑/曲序/厂牌/风格/图片完整；搜索匿名可用 | 25/min（匿名）、60/min（认证）；必须唯一 UA；数据部分 CC0、部分受限，图片使用需按条款 | 可达；`q=叶惠美` 35 条，release 详情有 11 首曲序 + 2 张图 |
   | iTunes Search | 无需 key；台湾区标题优先时华语命中好；有 100–600px 封面 | 20 calls/min；条款只允许 promotional use；`country=cn` 为空 | 可达；`country=tw` + `晴天` 首条正确，`country=us` 跑偏 |
   | KKBOX Open API | 台/港/日华语覆盖最好的官方源之一 | 需 OAuth client credentials + 申请；地区/条款限制 | API 直连不可达（http 000） |
   | Deezer | 国际目录强；封面好 | 条款限制缓存/商用；地区限制 | 直连不可达（http 000） |
   | AcoustID | 音频指纹精确识别 recording | 不增加目录覆盖，只提高匹配准确率；需 API key，非商用 | 直连不可达（http 000） |
   | Spotify | 目录大、封面好 | 2026 起 Web API 需 Premium；条款严格限制存储/展示 | 未接；门槛不适合免费开源 App |
   | Last.fm | 风格标签/简介 | 仅非商用；缓存上限 100MB；不得转授权 artwork | 未接；只适合补充标签 |
   | TheAudioDB | 艺术家/专辑图 | 测试 key 2 已 404；华语 0 命中 | 不可用 |
   | VGMdb | 游戏/动画音乐 | 非官方 API；覆盖窄 | 502/不可达 |
   | 网易/QQ/酷狗/酷我 | 华语覆盖最好 | 逆向接口 + 条款风险；不能公开分发 | 不接 |

   建议的顺序：**先量化 → 华语命中率低于阈值时，优先评估 Discogs 作为第二源**；iTunes 只在“台湾/香港区 + 标题优先查询 + 本地艺术家校验”下作为补充，且要先过条款评审。不要一开始就接多个源。

### 5.9 其他元数据源清单（2026-11-01 实测）

#### 可达性

| 源 | 本机直连 | 说明 |
|---|---|---|
| MusicBrainz / CAA | 可达（CAA 跳 archive.org 可能超时） | 已有 Phase 1 |
| Discogs | 可达 | Cloudflare，匿名 25/min |
| iTunes Search | 可达 | 台湾区可用，`country=cn` 为空 |
| Jamendo | 可达 | 仅 CC 音乐 |
| GNUDb | 可达 | CDDB，CD 曲目；无封面 |
| Deezer | 不可达（http 000） | DNS 正常，TCP/TLS 被拦 |
| KKBOX API | 不可达（http 000） | 同上 |
| AcoustID | 不可达（http 000） | 同上 |
| VGMdb | 502/不可达 | 非官方 API |
| Internet Archive | 不可达（http 000） | CAA 的 307 目标也受影响 |
| Spotify | 未接 | 2026 起需 Premium |
| Last.fm | 未接 | 非商用 + 100MB 缓存上限 |
| TheAudioDB | 404 | 测试 key 2 失效 |

#### Discogs（最现实的第二源）

- 搜索：`https://api.discogs.com/database/search?q=...&type=release`
  - 匿名 25/min、认证 60/min；必须唯一 UA，否则 403；
  - 实测 `q=叶惠美` → 35 条；`artist=周杰伦` → 0（Discogs 用 `Jay Chou` / `周杰伦*`），`artist=Jay Chou&release_title=叶惠美` → 8 条；
  - 搜索结果的 `cover_image` 常为空，但 release 详情里有 `images`。
- 详情：`https://api.discogs.com/releases/{id}`
  - 实测 `13960518`（叶惠美，2003，中国版）：11 首曲序、`Jay Chou`、厂牌“上海声像/新索音乐”、genres/styles、2 张图；
  - 曲目时长可能为空；曲序是字符串（`1`、`A1`），需要解析。
- 条款：官方文档明确“部分数据 CC0，部分 restricted”；月 dump 是 CC0；图片属于受限数据。接入前要逐条核对 API Terms 与 Application Name Policy（不能把应用叫 Discogs、需免责声明），并保留来源标注。
- 建议：只做用户触发的实时查询，不批量缓存全库；封面只在用户确认写入时下载；DB-only 模式不落盘。

#### iTunes Search（可用但有条件）

- 20 calls/min；无需 key。
- 2026-11-01 实测：
  - `country=tw` + `term=晴天` → 首条 `晴天 / 周杰倫 / 葉惠美`，第 2 条 Live 版，排序正确；
  - `country=tw` + `term=Jay Chou 晴天` → 同样正确；
  - `country=tw` + `term=周杰伦 晴天` → 首条却是《七里香》，说明“艺术家+标题”不如“标题单独搜”稳；
  - `country=hk` / `country=us` → 结果跑偏（港区出姜涛、MC 张天赋；美区出拉丁/钢琴翻奏）；
  - `country=cn` → 0 条。
- 条款：Apple 文档写明 API 内容（含专辑封面）只能用于“promote store content”，不能用于娱乐用途；拿封面给本地文件打标签不属于此用途。**因此 iTunes 不适合作为默认源**，最多作为用户可选、且经过条款评审的补充。
- 查询策略：标题优先 + 本地艺术家过滤；可选 storefront（tw/hk/us）；结果必须做字段校验，不能盲信第一条。

#### 其他源

- KKBOX：台/港/日华语最强官方源；OAuth client credentials；公开数据可读；但本机不可达，且需要申请/条款评估。若将来华语覆盖仍不够，这是优先争取的官方源。
- Deezer：公开搜索无需 key；国际目录强；本机不可达；条款限制缓存；不作为 Phase 2 默认。
- AcoustID：指纹 → MB recording；只提高匹配准确率，不增加目录；本机不可达；如要做，需服务端代理或用户网络可达。
- Last.fm：非商用；缓存 ≤100MB；不得转授权 artwork；元数据是用户标签，质量不稳定；只适合补充 genre/tag。
- TheAudioDB：测试 key 2 已 404；华语 0 命中；放弃。
- VGMdb：游戏/动画音乐；非官方 API；覆盖窄；放弃。
- GNUDb：CDDB；CD 曲目；无封面；只在“整张 CD 抓轨”场景有价值。
- Jamendo：CC 音乐；可达；与主流华语库无关。
- Wikidata：CC0；结构化但稀疏；可补 MBID/ISRC/发行日期，不做主搜索。
- Internet Archive：公共领域/现场/78rpm；本机不可达；niche。

#### 结论

- **Phase 1 不变：MusicBrainz + CAA。**
- **Phase 2 第二源：Discogs**（可达、匿名可用、专辑级数据完整、有图；先做条款核对）。
- **iTunes：可选补充**，仅台湾/香港区 + 标题优先 + 本地校验；不作为默认。
- **KKBOX：条件性候选**，只有拿到官方 OAuth 且条款允许时才做。
- **Deezer/AcoustID/VGMdb/Internet Archive：本机不可达，暂不投入。**
- **不接**：网易/QQ/酷狗/酷我、AllMusic/RYM/Bandcamp（无 API/禁爬）、Spotify（Premium+条款）、Gracenote（商业授权）。

> 如果目标是“接近全覆盖”，正确路线不是接更多网页，而是拿 CC0 数据转储（MusicBrainz + Discogs 月 dump）自建服务端索引，App 只查自己的服务；这需要服务器、带宽和索引维护，超出 Phase 1/2 范围，但可以作为长期方案。

> 实现注意：Phase 2 接 Discogs 时，`MetadataCandidate` 里的 `recordingId/releaseId` 需要泛化为 `sourceItemId/sourceReleaseId`，否则字段名会把 Discogs ID 误当 MBID 用。

> 备注：`archive.org` 在部分网络不可达是独立于“有没有封面”的问题。即使 release 有封面，客户端也可能下载失败；实现上必须把“无封面/下载失败”当成正常分支。

### 5.10 自定义 Provider（用户自建源，Phase 3 可选）

> 详细设计（编辑器、校验规则、导入导出、测试用例、文件改动清单）已单独展开：[`metadata-provider-design.md`](metadata-provider-design.md)。

#### 定位

这是**高级用户的扩展能力**，不是覆盖方案。它让用户接入自己有权使用的 JSON API（自建服务、公司内部曲库、已获授权的商业 API），但不能让普通用户“凭空多出华语曲库”。Phase 1/2 不应依赖它。

#### 能解决 / 不能解决

| 能解决 | 不能解决 |
|---|---|
| 项目不内置任何第三方服务的逆向接口、密钥、Cookie | 用户配置的源本身禁止自动访问时，用户仍可能违约 |
| 不内置签名/加密算法，降低“提供规避手段”的风险 | 需要签名/加密的服务无法用 baseUrl+params 接入；为它们加脚本会重新引入风险 |
| 用户可接自己的合法 API，项目保持通用工具属性 | 元数据/封面本身的著作权、数据库权利仍在；不能批量缓存或再分发 |
| 导入导出便于备份/迁移 | GPL 不会覆盖第三方 API 条款；不能靠开源许可免责 |

#### Provider 定义（JSON，版本化）

```json
{
  "schemaVersion": 1,
  "id": "user-...",
  "name": "My API",
  "baseUrl": "https://api.example.com",
  "auth": { "type": "apiKey", "in": "header", "name": "X-API-Key", "secretRef": "..." },
  "search": {
    "method": "GET",
    "path": "/v1/search",
    "query": { "q": "{title} {artist}", "limit": "{limit}" },
    "headers": { "Accept": "application/json" }
  },
  "response": {
    "items": "data.results",
    "id": "id",
    "title": "title",
    "artist": "artist.name",
    "album": "album.title",
    "year": "releaseDate",
    "cover": "cover.url",
    "score": "score",
    "detailPath": "/v1/track/{id}"
  },
  "rateLimit": { "minIntervalMs": 1100, "maxConcurrent": 1 },
  "capabilities": ["search", "cover"]
}
```

- 只支持 JSON API；不做 HTML 抓取。
- 路径用简单点/下标语法（`a.b[0].c`），不引入完整 JSONPath，也不执行任何脚本。
- 模板变量：`{title}`、`{artist}`、`{album}`、`{isrc}`、`{duration}`、`{filename}`、`{rawQuery}`、`{limit}`；统一 URL 编码。
- 认证只支持 `none` / `apiKey`（header 或 query）/ `bearer`；不支持任意 Cookie、Host、Content-Length 等危险头。
- 请求只支持 GET（Phase 3 可加 POST JSON body）；不支持 `file://`、`content://`、`javascript:`，默认只允许 HTTPS。
- 响应大小上限、超时、重定向次数都要有硬限制。

#### 安全与合规护栏（必须做）

1. **零预置**：不内置网易/QQ/酷狗/酷我及任何逆向源的名称、地址、参数、示例、截图、教程。
2. **零逆向**：不实现签名、加密、Cookie 注入、WebView 抓取、JS eval、证书固定绕过、反爬规避。
3. **用户承诺**：保存 Provider 时勾选“我有权使用该 API，并会遵守其条款”；本地记录时间，不联网上报。
4. **来源标注**：候选列表显示 Provider 名称和 source URL；导出时保留 attribution。
5. **密钥**：用 Android Keystore 加密存储；导出默认剔除密钥，只留 `secretRef` 占位；日志脱敏。
6. **限速**：每个 Provider 独立限速，默认 1 req/s、并发 1；尊重 429/`Retry-After`；已知主机（MusicBrainz/Discogs）强制其官方规则。
7. **不批量**：只做用户触发的单曲/显式批量；不做后台全库爬取；不做自动重试风暴。
8. **不持久缓存**：搜索结果只放内存；可选短 TTL；封面只在用户确认写入本地文件时下载；DB-only 不落盘。
9. **不建市场**：只支持本地文件/剪贴板导入，不托管 Provider 配置、不做在线 gallery、不索引分享链接。
10. **窄职责**：只取元数据字段；不做歌词、音频流、下载、任意 HTTP 代理。

#### 导入导出

- 格式：`{ "schemaVersion": 1, "providers": [...], "secretsIncluded": false }`。
- 导入时校验：URL scheme、字段映射、限速范围、未知字段警告；冲突让用户选择覆盖/新建。
- 导出不含密钥；如用户强制包含，必须二次确认并明确风险。
- 用 SAF 文件选择器，不经过项目服务器。

#### 为什么它不能替代内置源

- 普通用户不会配置；它只服务少数高级用户。
- 它不提高“搜索命中率”，只改变“搜哪个源”。
- 需要签名/加密的华语源接不进来；能接进来的，通常已经有官方 JSON API（如 KKBOX、Discogs、MusicBrainz），不如直接做内置适配器体验好。
- 维护成本高：字段映射、错误提示、导入兼容、用户支持都会增加。

**结论：Phase 3 再做，定位为“高级 → 自定义 Provider”；Phase 1/2 仍是 MusicBrainz + CAA、Discogs。** 另：不要用“可接网易云/QQ”做宣传，文案只写“连接你有权使用的 JSON API”；避免把通用工具描述成规避手段。

---

## 6. 分阶段实施

### Phase 1：单曲搜索 + 预览 + 写入（建议先做）

**交付物**

1. `FileNameMetadataParser`：从文件名解析 artist/title/album/track；
2. `MetadataQueryBuilder`：标签优先、文件名兜底、查询串可编辑、简繁变体；
3. `MusicBrainzApiService.lookupRelease(releaseId)`，复用同一个限速 Mutex；
4. `MusicBrainzReleaseParser`，解析 media/tracks/artist-credit/genres/date；
5. `CoverArtArchiveService`，下载 `front-500`，校验类型与大小；
6. `MetadataCandidate` / `MetadataFieldSelection` / `MetadataApplyTarget` / `MetadataMergePolicy`；
7. `MetadataMatchStateHolder`，状态：Idle/Searching/Candidates/Preview/LoadingDetails/Applying/Applied/Error；
8. `MetadataCandidateSheet`，候选列表 + 预览 + 勾选 + “应用方式”选择；
9. `MetadataEditStateHolder.applyOnlineMetadata()`，支持 DB-only / file+DB；
10. `MusicDao.applySelectedMetadata()`、`updateSongMbIdsAndDate()`；
11. 中英文文案；
12. 单元测试。

**验收**

- 标签完整的文件：直接按标签搜索；
- 标签缺失/占位、文件名形如 `01 - Artist - Title.flac`：能解析并搜索；
- 简繁不一致（如 `赵雷` / `趙雷`）：能通过标题兜底或简繁变体命中；
- 本地 MP3/FLAC/M4A 各一首，能从 SongInfo 搜索、预览、应用；
- **文件+数据库**模式：文件标签、MediaStore、Room、封面、`release_date`、`year` 全部一致；
- **只更新数据库**模式：文件字节不变，Room 中选中字段更新，`*_user_edited` 置位；
- 关闭“仅填充空白”后只覆盖勾选字段；
- 云端曲目可搜索，应用只改 DB，不报“文件不存在”；
- 无封面 / CAA 404 / archive.org 超时：预览显示占位图，应用不失败；
- MusicBrainz 429/断网有明确错误提示。

**预计改动**

| 类型 | 文件 |
|---|---|
| 新增 | `data/metadata/MetadataCandidate.kt`（含 `MetadataApplyTarget`） |
| 新增 | `data/metadata/MetadataMergePolicy.kt` |
| 新增 | `data/metadata/FileNameMetadataParser.kt` |
| 新增 | `data/metadata/MetadataQueryBuilder.kt` |
| 新增 | `data/musicbrainz/MusicBrainzReleaseParser.kt` |
| 新增 | `data/musicbrainz/CoverArtArchiveService.kt` |
| 新增 | `presentation/components/MetadataCandidateSheet.kt` |
| 新增 | `presentation/viewmodel/MetadataMatchStateHolder.kt` |
| 修改 | `data/musicbrainz/MusicBrainzApiService.kt` |
| 修改 | `data/musicbrainz/MusicBrainzRepository.kt` |
| 修改 | `data/database/MusicDao.kt` |
| 修改 | `presentation/viewmodel/MetadataEditStateHolder.kt` |
| 修改 | `presentation/viewmodel/SongInfoBottomSheetViewModel.kt` |
| 修改 | `presentation/components/SongInfoBottomSheet.kt` |
| 修改 | `res/values/strings.xml`、`res/values-zh-rCN/strings.xml` |
| 测试 | `MusicBrainzReleaseParserTest.kt`、`MetadataMergePolicyTest.kt`、`FileNameMetadataParserTest.kt`、`MetadataQueryBuilderTest.kt` |

### Phase 2：批量按专辑匹配

- 新增 `data/metadata/BatchMetadataMatcher.kt`；
- 新增 `data/worker/MetadataBatchWorker.kt`；
- 新增批量入口（资料库多选或设置页）；
- 进度、取消、结果汇总；
- 只对高置信度自动应用，其余进入人工确认列表。

### Phase 3：可选增强

- 设置项：默认“仅填充空白”、批量置信度阈值、是否写封面；
- 第二数据源（需单独合规评审）；
- 歌词回填：复用 LRCLIB，但仅用户主动、单曲；
- 云端曲目元数据推送到 Navidrome/Jellyfin（需要服务端 API，另行设计）。

> 如果 Phase 3 增加设置开关，必须按 `AGENTS.md` 的 5 步注册：`UserPreferencesRepository` → `SettingsViewModel` → `SettingsCategoryScreen` → `SettingsRegistry` → `strings_settings.xml`（含 zh-rCN），索引只能末尾追加。

---

## 7. 关键改造点与坑

### 7.1 年份与发行日期

现状：

- 固定编辑器没有 `year` 字段；
- `DATE` 可通过自定义字段写入；
- `syncReleaseDateAfterEdit()` 会重读文件更新 `release_date`；
- `songs.year` 不会自动更新。

方案：

- `CustomMetadataChanges(fields = [DATE])` 写文件；
- 应用成功后调用新 DAO `updateSongMbIdsAndDate(songId, year, releaseDate, recordingId, releaseId, artistId)`；
- 不修改 `SongMetadataEditor` 的 15 个参数签名，降低回归面。

### 7.2 用户手动编辑标记

`MusicDao.updateSongMetadata()` 会把 `title/artist/album/genre_user_edited` 全部置 1。也就是说，只要走一次 `saveMetadata()`，这四个字段就会被标记为用户编辑，后续同步不会覆盖。这符合“用户主动应用在线元数据”的语义，但要注意：

- 预览阶段读取标记要用 `SongEntity`，不能只看 `Song`；
- 只勾选部分字段时，现有 DAO 仍会把四个标记全部置 1。若要求精确，需要扩展 `updateSongMetadata` 传入四个 flag，但收益不大，Phase 1 可不做。

### 7.3 多艺术家

MusicBrainz 的 `artist-credit` 用 `joinphrase` 拼接（如 `A & B`）。现有 `SongMetadataEditor` 会通过 `collectArtistNames` / `ArtistParsingUtils` 拆分并建立 artist 关联。需要实测：

- `A & B` 是否被拆成两个艺术家；
- `A feat. B` 是否被误拆；
- 如果拆分不符合预期，Phase 1 先按整串写入，后续再调解析规则。

### 7.4 时长与版本

同一首歌可能有 album version、live、remix。现有打分对时长偏差 ≥30s 扣 12 分，但仍可能排在前面。建议 UI 上显示 `disambiguation` 和 release 类型，让用户判断，而不是完全依赖分数。

### 7.5 限速

`MusicBrainzApiService` 的 `requestMutex` 是实例级，当前是 `@Singleton`，所以全应用共用。新增的 release 查询必须放在同一个类里，或抽一个共享的 `MusicBrainzRateLimiter`；**不要**新建一个带独立 Mutex 的 service。

### 7.6 封面缓存

写入封面后，`SongMetadataEditor` 已经调用 `AlbumArtUtils.clearCacheForSong` 并更新 `album_art_uri_string`；`MetadataEditStateHolder` 还会 `imageCacheManager.invalidateCoverArtCaches` 和 `themeStateHolder.forceRegenerateColorScheme`。预览页自己下载的封面不要写进 Coil 的全局缓存键，避免和专辑封面 URI 冲突。

### 7.7 文件写入失败

`SongMetadataEditor` 已区分 `FILE_NOT_FOUND / NO_WRITE_PERMISSION / UNSUPPORTED_FORMAT / TAGLIB_ERROR / TIMEOUT / FILE_CORRUPTED / IO_ERROR`。预览页要直接复用 `MetadataEditResult.getUserFriendlyErrorMessage()`，不要新造一套错误文案。

### 7.8 数据库

- 不需要新增列：`release_date`、`mb_recording_id`、`mb_release_id`、`mb_artist_id` 都已存在；
- 不需要新增迁移；
- 如果后续要记录“元数据来源/匹配时间”，再单独加列，Phase 1 不做。

### 7.9 权限

`AndroidManifest.xml` 已有 `READ_MEDIA_AUDIO` 和 `READ_EXTERNAL_STORAGE (maxSdk 32)`；现有编辑器已在用 SAF/MediaStore 写入路径。Phase 1 不新增权限。

### 7.10 简体/繁体与别名（实测会漏结果）

实测：`recording:"成都" AND artist:"赵雷"` 返回 0；把艺术家换成繁体 `趙雷` 后返回 6 条，首条正确。MusicBrainz 的索引和显示名不保证使用同一种字形，直接拿简体标签去查会假阴性。

处理顺序：

1. 标题 + 艺术家原样查；
2. 艺术家做简→繁、繁→简各查一次；
3. 仍无结果，退化为标题单独查，再在本地用艺术家相似度过滤；
4. 仍无结果，用艺术家搜索（`artist:"..."`）拿到 MBID，再用 `arid:` 查录音。

不要引入庞大的转换库；可以先内置常用字的映射表，或只在“原样查询 0 结果”时做一次转换。转换只影响查询，不改变写回值。

### 7.11 “只更新数据库”的同步覆盖

DB-only 之后，文件标签仍是旧值。下一次 `SyncWorker` / deep scan 可能把旧值写回数据库。必须：

- 按选中字段设置 `*_user_edited=1`；
- 确认 `SyncWorker` 的合并逻辑（`SongEntity.mergingUserOwnedFieldsFrom`）尊重这些标记；
- 如果用户主动执行“从文件重新读取标签”，要明确这是覆盖操作，或者提供“保留数据库值”的选项。

这不是 Phase 1 的阻塞项，但必须写进实现和测试，否则用户会觉得“改了又变回去”。

### 7.12 CAA 的网络可达性

`coverartarchive.org/release/{id}/front-500` 会 307 跳到 `archive.org`。实测本机访问 `archive.org:443` 超时（21 秒），而 `coverartarchive.org` 本身可返回 307。实现要求：

- 连接/读取超时分别设置（建议 5s/15s），不要用默认无限等待；
- 跳转失败等同于“无封面”，不要阻塞字段应用；
- 封面下载与 release 详情并行发起，但共用限速和取消；
- UI 先显示占位图，下载成功后再替换。

---

## 8. 测试计划

### 8.1 单元测试

| 测试 | 内容 |
|---|---|
| `MusicBrainzReleaseParserTest` | 单碟/多碟、`A1` 轨号、缺日期、缺 genres、多艺术家 joinphrase |
| `MetadataMergePolicyTest` | 空白判定、`Unknown *` 占位符、`user_edited` 优先、部分勾选 |
| `FileNameMetadataParserTest` | `Artist - Title`、`01 - Artist - Title`、`Artist - Album - 01 - Title`、技术噪声、保留 `Live/Remix` |
| `MetadataQueryBuilderTest` | 标签优先、文件名兜底、标题兜底、简繁变体、空查询 |
| `MusicBrainzApiServiceTest` | 现有 Lucene 转义、候选排序不能回归 |
| `CoverArtArchiveServiceTest` | 404、非图片 Content-Type、超大响应、307 到 archive.org 超时 |
| `ReleaseDateTest` | `yyyy`、`yyyy-MM-dd`、`year<=0` 的派生 |
| DAO 测试 | DB-only 只改选中字段、`*_user_edited` 置位、`year/release_date` 同步 |

### 8.2 构建

```bash
cd D:/3rd-party-projects/PixelPlayerOSS
./gradlew :app:assembleRelease
```

> Windows 下若 `gradlew` 不可执行，用 `gradlew.bat`。仓库默认构建任务是 `:app:assembleRelease`（见 `AGENTS.md`）。

### 8.3 真机/模拟器验收

按 `AGENTS.md` 要求，UI 改动需要真机验证：

1. 标签完整 + 标签缺失但文件名规范，两种都能发起搜索；
2. 简繁不一致的艺术家能通过兜底命中；
3. MP3（ID3v2.3/v2.4）用“文件+数据库”应用全部字段；
4. FLAC（Vorbis Comment）用“文件+数据库”应用全部字段；
5. M4A（MP4 `©day`）应用日期与封面；
6. Opus 走 VorbisJava 路径；
7. 同一首歌分别测试“文件+数据库”和“只更新数据库”，确认文件字节是否变化；
8. 云端 Navidrome/Jellyfin 曲目只写 DB；
9. 取消批量任务后不残留半写文件；
10. 断网 / 429 / CAA 404 / archive.org 超时的错误提示；
11. 应用后重启应用，标签、封面、年份仍在；
12. 只更新数据库后执行一次同步，确认选中字段没有被旧文件值覆盖。

---

## 9. 风险与对策

| 风险 | 影响 | 对策 |
|---|---|---|
| MusicBrainz 中文曲库覆盖弱 | 用户搜不到或只有不完整 release | 先量化自己的库；查询做简繁/标题兜底；按需评审第二源 |
| 简繁/别名造成假阴性 | 明明有数据却 0 结果 | 原样 → 简繁变体 → 标题兜底 → `arid:` 四级重试 |
| release 选错（live/remix） | 写错标签 | 显示类型/年份/时长；默认不选非 Official；批量阈值 90 |
| 封面缺失或 archive.org 不可达 | 预览空白、应用慢 | 404/超时视为无封面；短超时；不阻塞字段写入 |
| 封面版权 | 法律风险 | 只写用户本地文件；不在应用内分发；保留 CAA 来源说明 |
| 年份不刷新 | 功能看起来没生效 | Phase 1 必须补 `year` 回写 |
| 覆盖用户手动编辑 | 数据丢失 | 默认“仅填充空白”；`user_edited` 不勾选 |
| DB-only 被同步覆盖 | 改了又变回去 | 按字段置 `*_user_edited=1`；验证 `mergingUserOwnedFieldsFrom` |
| 限速触发 429 | 搜索失败 | 全局限速器；指数退避；批量单线程 |
| 大封面 OOM | 崩溃 | 限制 8MB；预览用缩略图；写入前 decode bounds |
| 多艺术家拆分错误 | 艺术家库变乱 | 先整串写入并实测；必要时只更新显示名，不动 artist 关联 |
| GPL 合规 | 发布风险 | 不移植反编译代码；不引入第三方 key；保留 NOTICE |

---

## 10. 文件改动清单（Phase 1）

### 新增

```
app/src/main/java/com/lostf1sh/pixelplayeross/data/metadata/MetadataCandidate.kt
app/src/main/java/com/lostf1sh/pixelplayeross/data/metadata/MetadataMergePolicy.kt
app/src/main/java/com/lostf1sh/pixelplayeross/data/metadata/FileNameMetadataParser.kt
app/src/main/java/com/lostf1sh/pixelplayeross/data/metadata/MetadataQueryBuilder.kt
app/src/main/java/com/lostf1sh/pixelplayeross/data/musicbrainz/MusicBrainzReleaseParser.kt
app/src/main/java/com/lostf1sh/pixelplayeross/data/musicbrainz/CoverArtArchiveService.kt
app/src/main/java/com/lostf1sh/pixelplayeross/presentation/components/MetadataCandidateSheet.kt
app/src/main/java/com/lostf1sh/pixelplayeross/presentation/viewmodel/MetadataMatchStateHolder.kt
app/src/test/java/com/lostf1sh/pixelplayeross/data/musicbrainz/MusicBrainzReleaseParserTest.kt
app/src/test/java/com/lostf1sh/pixelplayeross/data/metadata/MetadataMergePolicyTest.kt
app/src/test/java/com/lostf1sh/pixelplayeross/data/metadata/FileNameMetadataParserTest.kt
app/src/test/java/com/lostf1sh/pixelplayeross/data/metadata/MetadataQueryBuilderTest.kt
```

### 修改

```
app/src/main/java/com/lostf1sh/pixelplayeross/data/musicbrainz/MusicBrainzApiService.kt
app/src/main/java/com/lostf1sh/pixelplayeross/data/musicbrainz/MusicBrainzRepository.kt
app/src/main/java/com/lostf1sh/pixelplayeross/data/database/MusicDao.kt
app/src/main/java/com/lostf1sh/pixelplayeross/presentation/viewmodel/MetadataEditStateHolder.kt
app/src/main/java/com/lostf1sh/pixelplayeross/presentation/viewmodel/SongInfoBottomSheetViewModel.kt
app/src/main/java/com/lostf1sh/pixelplayeross/presentation/components/SongInfoBottomSheet.kt
app/src/main/res/values/strings.xml
app/src/main/res/values-zh-rCN/strings.xml
```

### 不动

```
data/media/SongMetadataEditor.kt        # 核心写入逻辑保持不动；年份用 DAO 回写
data/database/SongEntity.kt             # 无需新列
data/database/Migrations.kt             # 无需迁移
AndroidManifest.xml                     # 无需新权限
```

---

## 11. 默认决策与待确认项

以下是我建议的默认值，若没有异议就按这个开工：

| 项 | 默认 |
|---|---|
| 首个数据源 | MusicBrainz + Cover Art Archive |
| 首个范围 | 单曲，不做批量 |
| 查询来源 | 文件标签优先，文件名兜底；查询串可编辑 |
| 简繁处理 | 原样 → 简繁变体 → 标题兜底 → `arid:` |
| 默认应用目标 | 本地文件：文件和数据库都更新；云端/只读：只更新数据库 |
| 默认应用策略 | 仅填充空白 |
| 是否覆盖用户手动编辑 | 默认不覆盖 |
| 流派 | 默认不勾选（MB 流派体系与用户库不一致） |
| 歌词 | 不做，沿用播放器现有 LRCLIB 入口 |
| 封面尺寸 | 预览 500px，写入 500px（可后续加 1200px 选项） |
| 云端曲目 | 可搜索，只写 DB |
| 设置项 | Phase 1 不加，避免 5 步注册的额外面 |
| 批量 | Phase 2，按专辑分组 |
| 第二数据源 | 先用用户自己的库量化，再决定是否评审 iTunes Search 等 |
| 文件名整理 | 不做 |

建议的开工顺序：

1. 先做 Phase 1 单曲闭环（含文件名查询、简繁兜底、DB-only / file+DB 两种目标）；
2. 并行用你的实际曲库抽样 100 首，量化 MusicBrainz 命中率和封面率；
3. 根据量化结果决定是否进入 Phase 2（批量）或先补第二数据源。

---

## 12. 证据索引

### 12.1 本仓库

> 以下行号记录的是实施前基线 `624d6cda` 的位置，仅供理解当时的链路；当前代码请以
> [`metadata-search-status.md`](metadata-search-status.md) §3 的落点表为准。

- `AGENTS.md`：GPL-3.0、版权红线、设置 5 步注册、构建与真机验证要求
- `data/musicbrainz/MusicBrainzApiService.kt:17`：`MusicBrainzMatch`
- `...:51`：`searchRecording`
- `...:112`：`parseSearchResponse`
- `...:193`：`adjustedScore`
- `data/musicbrainz/MusicBrainzRepository.kt:24`：仅 DB 的 `apply`
- `data/database/MusicDao.kt:1926`：`applyMusicBrainzMatch`（只填空）
- `data/database/MusicDao.kt:1818`：`updateSongMetadata`（设置 `*_user_edited=1`）
- `data/database/MusicDao.kt:1695`：`updateSongReleaseDate`
- `data/media/SongMetadataEditor.kt:232`：`editSongMetadata`
- `...:465`：编辑后同步 `release_date`
- `...:497`：封面写入与缓存清理
- `presentation/viewmodel/MetadataEditStateHolder.kt:56`：`saveMetadata`
- `presentation/viewmodel/SongInfoBottomSheetViewModel.kt:75`：`MusicBrainzUiState`
- `...:134`：`searchMusicBrainz`
- `...:151`：`applyMusicBrainzMatch`
- `presentation/components/SongInfoBottomSheet.kt:699`：MusicBrainz 按钮
- `...:923`：MusicBrainz 结果 UI
- `docs/year-release-date-sort-plan.md`：`release_date` 四态语义与 `DATE` 写入路径

### 12.2 配套调研

《音乐标签》取证为本地调研文档，**未随仓库发布**（其中包含对第三方 APK 的逆向记录）。并入本文的结论：

- 6 个在用数据源、2 个历史残留源；
- 未接入的国内源依赖私有加密常量、硬编码 cookie 与非公开 endpoint —— 这是 §3.2 排除它们的依据；
- TagLib / SACD Ripper / Apache / MIT 的许可证结论；
- 各平台服务条款与版权风险。

### 12.3 外部文档与实测

- MusicBrainz WS/2：`https://musicbrainz.org/doc/MusicBrainz_API`
- MusicBrainz 限速与 UA：`https://musicbrainz.org/doc/MusicBrainz_API/Rate_Limiting`
- MusicBrainz 统计（2026-11-01）：`https://musicbrainz.org/statistics`
- MusicBrainz 语种统计：`https://musicbrainz.org/statistics/languages-scripts`
- MusicBrainz 地区统计：`https://musicbrainz.org/statistics/countries`
- Cover Art Archive API：`https://musicbrainz.org/doc/Cover_Art_Archive/API`
- LRCLIB API：`https://lrclib.net/docs`

本次实测记录（2026-11-01，使用 UA `PixelPlayerOSS-research/1.0`，查询间隔 ≥1.15s）：

- 12 首中文歌：11 首直接命中；《成都》简繁差异导致 0 结果，繁体艺术家查询命中 6 条；
- 对照：米津玄師《Lemon》4 条，Taylor Swift《Anti-Hero》26 条；
- CAA：抽样 6 个 release，中文 3 个 404、1 个有封面；日文/英文 2 个有封面；
- 封面服务 307 跳转 `archive.org`，本机访问超时约 21s。
