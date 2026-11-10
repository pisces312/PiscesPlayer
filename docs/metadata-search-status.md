# 在线元数据搜索与回填 — Phase 1 实施状态

> 状态：**Phase 1 已实施并构建/安装通过**（2026-11-01，commit `49ec7e41`）。
> **模拟器上的人工点击验收尚未执行**（按约定由仓库作者完成，判据见 §7）。
> 设计依据：[`metadata-search-plan.md`](metadata-search-plan.md)（总体方案）、
> [`metadata-provider-design.md`](metadata-provider-design.md)（Phase 3 设计，未实施）。

## 1. 交付范围

单曲闭环：**搜索 → 候选 → 字段级预览 → 只写库 / 文件+库 → 应用结果**。

| 能力 | 落点 |
|---|---|
| 查询来源：文件标签优先，文件名兜底，查询串可编辑 | `MetadataQueryBuilder.fromSong` / `FileNameMetadataParser.parsePath` |
| 逐步放宽搜索：专辑限定 → 仅艺人 → 仅标题 | `MetadataQueryBuilder.searchAttempts` |
| MusicBrainz 录制搜索（最多 8 条，1.1 s 限速） | `MusicBrainzApiService.searchRecording` |
| release 详情：曲序、碟号、流派、发行日期、状态/类型 | `MusicBrainzReleaseParser.parse` / `enrich` |
| 音轨匹配：recordingId → 归一化标题 → 时长 ±2 s | `MusicBrainzReleaseParser.findTrack` |
| 封面下载（front-500，404/410/超 8 MiB 视为无封面） | `CoverArtArchiveService.download` |
| 默认勾选策略：只填空、不碰用户编辑字段、流派不自动勾 | `MetadataMergePolicy.defaultSelection` |
| 预览与勾选 UI、填充开关、写入目标切换、完成页 | `MetadataMatchSheet` |
| 只写库 | `SongMetadataEditor.updateDatabaseMetadata` + `MusicDao.updateSongOnlineExtras` |
| 文件+库 | 现有 `saveMetadata`（TagLib/JAudioTagger/VorbisJava）+ `updateSongOnlineExtras` |
| 中英文文案 | `values/strings.xml`、`values-zh-rCN/strings.xml`（各 46 条 `metadata_match_*`） |

**未引入**：新 Room 列 / 数据库迁移 / 新权限 / 新依赖 / 新设置项。

## 2. 行为约定

- **默认写入目标**：本地文件存在且可读 → 「文件和资料库」；云端曲目或文件不可达 → 「仅资料库」。
- **默认只填充空白**：标题/艺人/专辑判空时识别 `unknown title` / `unknown artist` / `unknown album` 占位。
- **用户手动编辑过的字段永不自动勾选**（依据 `title/artist/album/genre_user_edited`）。
- **流派默认不勾**：MusicBrainz 流派体系与本地库习惯不一致，需手动勾选。
- **封面只在缺失时勾选**；本地曲目选「仅资料库」时封面行置灰（没有落盘位置）。
- 关闭「仅填充空白」不会清空已勾选项；重新打开时会并入自动判定为空的字段。
- 修改查询字段会清空上一次的候选与"已搜索"状态，避免用旧结果写新查询。
- **搜索只在结果为空时继续放宽**；网络失败立即报错，不换查询重试。
- 预览阶段会加载 release 详情：补全曲序/碟号/流派/日期，并按音轨重新计算默认勾选。
- **只写库**：`updateSongMetadata` 会把标题/艺人/专辑/流派标记为用户已编辑（`*_user_edited=1`），
  之后自动匹配不再覆盖；云盘曲目可把远端封面 URL 存进 `album_art_uri_string`。
- **文件+库**：发行日期以自定义字段 `DATE` 写入文件；歌词保持原样（见 §4.6）。
- 封面下载失败不阻塞文字字段；若只勾了封面且下载失败，报 `COVER_UNAVAILABLE` 而不写任何内容。
- 应用成功后刷新相关缓存并重建主题色；返回的状态里带实际应用字段数。

## 3. 代码落点

行号为 `49ec7e41` 时的位置。

| 文件 | 关键符号 |
|---|---|
| `data/metadata/MetadataCandidate.kt` | `MetadataApplyTarget:7`、`MetadataQuery:16`、`MetadataCandidate:30`、`MetadataFieldSelection:56` |
| `data/metadata/FileNameMetadataParser.kt` | `parsePath:35`（支持 `Artist - Title`、`Artist - Album - 03 - Title`、`01 - Artist - Title` 等） |
| `data/metadata/MetadataQueryBuilder.kt` | `fromSong:20`、`searchAttempts:55`、`displayQuery:78`、`escapeLucene:99` |
| `data/metadata/MetadataMergePolicy.kt` | `defaultSelection:33`、`resolvedReleaseDate`（仅年份时补 `YYYY-01-01`） |
| `data/musicbrainz/MusicBrainzReleaseParser.kt` | `parse:39`、`findTrack:88`、`enrich:115` |
| `data/musicbrainz/CoverArtArchiveService.kt` | `download:44`、`releaseCoverUrl` / `releaseGroupCoverUrl` |
| `data/musicbrainz/MusicBrainzApiService.kt` | `searchRecording:56`、`lookupRelease:88`、`MAX_RESULTS=8:118` |
| `data/musicbrainz/MusicBrainzRepository.kt` | `searchCandidates:26`、`loadDetails:48` |
| `data/database/MusicDao.kt` | `updateSongOnlineExtras:1955`（albumArtist / year / release_date / MBID / 可选封面 URL，CASE + COALESCE） |
| `data/media/SongMetadataEditor.kt` | `updateDatabaseMetadata:149`（复用艺术家拆分与交叉引用） |
| `presentation/viewmodel/MetadataEditStateHolder.kt` | `applyOnlineMetadata:196` |
| `presentation/viewmodel/MetadataMatchStateHolder.kt` | `open:95`、`search:183`、`selectCandidate:225`、`apply:345` |
| `presentation/viewmodel/SongInfoBottomSheetViewModel.kt` | `startMetadataMatch:126` |
| `presentation/components/MetadataMatchSheet.kt` | `MetadataMatchSheet:70`、`QueryContent:215`、`PreviewContent:454`、`AppliedContent:945` |
| `presentation/components/SongInfoBottomSheet.kt` | 入口按钮 `:688`、sheet 接线 `:931` |
| `res/values*/strings.xml` | 各 46 条 `metadata_match_*`；已删除 9 条旧 `musicbrainz_*` |

测试：`data/metadata/FileNameMetadataParserTest`(7)、`MetadataQueryBuilderTest`(6)、
`MetadataMergePolicyTest`(5)、`data/musicbrainz/MusicBrainzReleaseParserTest`(5)，原有
`MusicBrainzApiServiceTest`(3) 继续通过。

## 4. 与计划的偏差

1. **组件改名**：计划的 `MetadataCandidateSheet` 落地为 `MetadataMatchSheet`（全屏 Dialog，覆盖查询/预览两阶段）。
2. **DAO 合并**：计划中的 `applySelectedMetadata()` + `updateSongMbIdsAndDate()` 合并为一个
   `updateSongOnlineExtras()`，用 `updateXxx: Boolean` 控制是否覆盖；MBID 用 `COALESCE` 保留旧值。
3. **简繁变体未实现**：没有引入转写表。实测《成都》的简繁差异靠"标题兜底 + 查询串可编辑"覆盖
   （`searchAttempts` 的第三次尝试）。若后续量大，再加变体生成。
4. **状态机简化**：`MetadataMatchPhase` 只有 `HIDDEN / QUERY / PREVIEW / APPLIED`，
   搜索中/加载详情/应用中/错误用独立布尔位与 `MetadataMatchError` 表达，未按计划做 8 个离散状态。
5. **新增公开入口** `SongMetadataEditor.updateDatabaseMetadata()`：计划未列，但只写库时必须复用
   现有的艺术家拆分、`song_artist_cross_ref` 维护与孤立艺术家清理，否则会绕开多艺术家一致性。
6. **歌词语义修复**：`SongMetadataEditor.editSongMetadata` 原来把 `newLyrics = null` 强转为 `""`，
   会在"文件+库"应用时清空歌词。现在 `null` 表示保持文件原歌词，`""` 才清空。
7. **只写库的封面**：本地曲目禁用（无法落盘），云盘曲目存远端 URL；计划里"只写库不写封面"放宽了云盘这一档。
8. **未加设置项**：与计划一致，避免 5 步注册。

## 5. 验证记录（2026-11-01）

| 命令 | 结果 |
|---|---|
| `./gradlew :app:compileDebugKotlin` | 成功 |
| `./gradlew :app:testDebugUnitTest` | 全绿（新增 23 个用例 + 既有 3 个） |
| `./gradlew :app:assembleRelease` | 成功，产物 `app/build/outputs/apk/release/pixelplayeross-arm64-v8a-0.4.3-pisces.1-release.apk` |
| `adb install -r …-release.apk`（pixel6 AVD） | Success |
| 启动检查 | `topResumedActivity=com.pisces312.piscesplayer/.MainActivity`，进程存活，logcat 无 FATAL |

**未验证**（留给人工）：真实曲库的搜索/写入流程、云端曲目、封面实际落盘、文件标签在外部工具中的可读性。

## 6. 已知限制

- **SongInfo 面板刷新**：应用完成后面板仍显示打开时的 `Song` 快照，需要关闭重开才看到新值。
- **封面可达性**：CAA 会 307 跳 `archive.org`，本机实测约 21 s 超时；失败只影响封面，不影响文字字段。
- **覆盖面**：MusicBrainz 对华语老歌/网络歌曲覆盖有限；简繁不一致时需手动改查询串。
- **只写库标记**：标题/艺人/专辑/流派会变成"用户已编辑"，后续自动流程不再覆盖 —— 这是刻意行为。
- **本地只写库无封面**。
- **搜索结果上限** 8 条，无分页。
- **多艺术家**：文件路径写入 MusicBrainz 的 artist-credit 原串；只写库路径按用户的艺术家分隔符设置拆分。
- **无批量、无自定义 Provider、无第二数据源、无歌词回填**（Phase 2/3 范围）。

## 7. 人工验收清单（待执行）

1. 标签完整的本地曲目：搜索直接命中，候选分数/年份/时长合理。
2. 标签缺失、文件名为 `01 - Artist - Title.flac`：默认查询来自文件名。
3. 简繁不一致的曲目：标题兜底能出结果；必要时手动改查询串再搜。
4. 预览：勾选状态只落在空白字段；已手动编辑过的字段不被勾选；流派默认不勾。
5. 「文件和资料库」：用外部工具确认文件标签（含 `DATE`）、Room、封面、`release_date` 一致；歌词未被清空。
6. 「仅资料库」：文件字节不变（比对 mtime/哈希），Room 中选中字段更新，四个 `*_user_edited` 置位。
7. 关闭「仅填充空白」后只覆盖勾选项。
8. 云端曲目：可搜索；写入目标只剩「仅资料库」，应用不报"文件不存在"。
9. 无封面 / CAA 超时：预览显示占位图，文字字段仍可应用；只勾封面时提示封面不可用。
10. 断网：搜索阶段给出明确的搜索失败提示，不是空列表。

## 8. 下一步

- **Phase 2（批量按专辑匹配）**：`BatchMetadataMatcher` + Worker + 高置信度自动应用、其余进人工确认列表。
- **Phase 3（自定义 Provider）**：按 [`metadata-provider-design.md`](metadata-provider-design.md) 实施；
  接口契约见其 §4.4，落地时需要在 `MetadataSource` 上做扩展。
- **先量化再决定顺序**：建议用真实曲库抽样 100 首统计 MusicBrainz 命中率与封面率，
  再决定是补第二数据源还是先做批量。
