# 年份视图按发行日期排序（release_date）— 实施方案

状态：已实施（2026-09-18）。单测通过，`schemas/12.json` 已生成；模拟器实测（§8 后两项）待做。决策记录见 §9。

## 1. 背景与目标

年份视图（Years smart category）内的歌曲目前无法按「发行年月日」排序：

- 现有默认排序 `YearSongRelease` 实际按 **专辑名 + 碟号 + 轨道号** 排（`MusicDao.getSongsByYear`），与日期无关；
- `Date Added` 排序用的是文件入库时间（MediaStore `DATE_ADDED`），不是发行日期。

目标：读取文件的完整发行日期标签，在年份详情页新增「发行日期 新→旧 / 旧→新」两个排序项。

## 2. 根因（调研结论）

完整日期在文件里存在，但读取链路三处丢失：

1. **MediaStore 只有年**：`SyncWorker.kt` 只查 `MediaStore.Audio.Media.YEAR`（int），MediaStore 音频表没有完整发行日期列，此路径无解。
2. **文件标签路径主动截断**：`AudioMetadataReader.kt` 两条路径都在读出日期后 `.take(4).toIntOrNull()` 只留年份：
   - TagLib propertyMap 路径：`propertyMap["DATE"]?.take(4)`；
   - JAudioTagger 路径：`tag.getFirst(FieldKey.YEAR)?.take(4)`。
   音频文件的日期标签（ID3v2.4 `TDRC` / Vorbis `DATE` / MP4 `©day`）通常是 `yyyy-MM-dd` 全格式。
3. **DB 无落点**：`SongEntity` 只有 `year: Int`，无发行日期列。

### 2.1 各格式的日期字段与工具支持

读取主路径是 kyant TagLib JNI 的通用 propertyMap（`TagLib.getMetadata`），TagLib 把各格式日期字段统一归一为键 **`DATE`**；兜底路径 JAudioTagger 用 `FieldKey.YEAR`（同一逻辑字段，命名不同）：

| 格式 | 标准字段 | 完整日期支持 | TagLib propertyMap | JAudioTagger |
|---|---|---|---|---|
| MP3 ID3v2.4 | `TDRC`（Recording Time，`yyyy-MM-ddTHH:mm:ss`） | ✅ 原生单一字段 | `DATE` | `FieldKey.YEAR` |
| MP3 ID3v2.3 | `TYER`（年）+ `TDAT`/`TIME`（月日/时间，拆帧） | ⚠️ 标准拆成三帧 | `DATE` | `FieldKey.YEAR` |
| FLAC / Ogg / Opus（Vorbis Comment） | `DATE` | ✅ | `DATE` | `FieldKey.YEAR` |
| MP4 / M4A | `©day` | ✅ | `DATE` | `FieldKey.YEAR` |
| WAV / AIFF | 内嵌 ID3 chunk，同 ID3 规则 | 同上 | `DATE` | `FieldKey.YEAR` |

**读写工具链均支持完整日期**：读取端（`AudioMetadataReader`）是先读出完整字符串再 `.take(4)` 截断的——值在 propertyMap 里，截断是应用自己的策略，不是库能力限制。写入端：TagLib 主路径 `TagLib.savePropertyMap` 按键透传，`DATE` 会写回各格式对应原生字段；JAudioTagger 自定义字段路径已有 `"DATE" -> FieldKey.YEAR` 映射（`SongMetadataEditor.applyCustomMetadataField`），`setField(FieldKey.YEAR, "1998-05-12")` 在 v2.4 写 `TDRC`、Vorbis 写 `DATE`。唯一限制是 ID3v2.3：标准本身没有单一完整日期字段（年/月日/时间拆三帧），JAudioTagger 写入时会拆分或降级为年——实际影响小，v2.3 文件大多也只标年份。

## 3. 数据模型

### 3.1 新列

`SongEntity` 新增列 `release_date TEXT`，`Song` 模型新增 `releaseDate: String?` 字段，DB version 11 → 12（`Migrations.kt` `addColumnIfMissing` + `di/AppModule.addMigrations` + `PiscesPlayerDatabase.version` +1，提交新生成的 `app/schemas/12.json`）。

### 3.2 四态语义

| 值 | 含义 | 排序行为 |
|---|---|---|
| `NULL` | **从未读过文件**（待回填） | 沉底 |
| `'0'` | **读过一次，无任何日期**（文件无 DATE 标签且 year ≤ 0） | 沉底 |
| `'yyyy-01-01'` | 只有年份（year 兜底，默认 1 月 1 日） | 正常参与排序 |
| `'yyyy-MM-dd'` | 完整发行日期 | 正常参与排序 |

- 读过文件必留痕：任何一次真正读了文件头的时机（augment / deepScan / 回填 / 标签编辑）一律写「日期或派生值或 `'0'`」，**不留 NULL**。这样每次同步的回填查询 `WHERE release_date IS NULL` 不会反复命中同一批文件（无日期的文件只读一次文件头）。
- `year` 列维持现状不动，`release_date` 与 `year` 各存各的；日期排序只用 `release_date`。

### 3.3 派生规则（SyncWorker / 回填共用）

```
candidate = 标签完整日期(yyyy-MM-dd)
          ?: year 兜底("$year-01-01"，year > 0 时，year 取自标签或 MediaStore)
          ?: '0'
```

已知取舍：真实日期恰好是 1 月 1 日的文件与 year 兜底值在 DB 中不可区分；后续若该文件的 year 被修改，派生重算会覆盖真实 Jan-1 值。属可接受边角案例，不做额外标记列。

### 3.4 远程源

Navidrome / Jellyfin 歌曲无本地文件，`release_date` 恒为 `NULL`（沉底）。回填查询限定本地源且文件存在。Jellyfin `PremiereDate` / MusicBrainz `mb_release_id` 作为数据源列为后续可选扩展，本期不做。

## 4. 导入与回填

### 4.1 读取层（AudioMetadataReader）

- `AudioMetadata` 新增 `releaseDate: String?`；
- 两条标签路径取**完整**日期字符串，解析出 `yyyy` + `MM` + `dd` 三段齐全才返回标准化 `yyyy-MM-dd`；解析不出完整日期返回 null（上层用 year 兜底）；
- `year` 现有逻辑不动（仍取前 4 位）。

### 4.2 增量同步（SyncWorker）

- augment 路径（`shouldAugmentMetadata` 为 true 时）：`meta.releaseDate` 走 §3.3 派生，结果写入实体；
- `needsUpdate` 比较项追加 `existing.releaseDate == candidate`，日期变化才触发更新（不额外扩大重写范围）；
- augment 未触发的行不动，交给回填。

### 4.3 回填（方案 A，用户已确认）

每次同步末尾：

1. 查询 `release_date IS NULL` 的行（本地源）；
2. 逐个读文件头（`AudioMetadataReader.read(readArtwork = false)`，只读标签不读封面，单文件毫秒级）；
3. 文件不存在或读取失败也写 `'0'`（否则每次同步无限重试）；文件后续变化会经 date_modified → `needsUpdate` → augment 正常覆盖。

全量标记完成后，每次同步只剩一条单列查询，零文件 IO。

## 5. 标签编辑同步

### 5.1 App 内编辑（SongMetadataEditor）

现状：`editSongMetadata` 不支持直接编辑 year，但**自定义字段编辑已支持 `DATE`**（`applyCustomMetadataField` 中 `"DATE" -> FieldKey.YEAR` 映射，写入文件真实日期标签）。

问题：编辑成功后走 `forceMediaRescan` + MediaStore update，但 MediaStore 只有 YEAR —— DATE 标签的月/日部分不会触发增量同步，DB 的 `release_date` 不会更新。

接线：`editSongMetadata` 成功后，重新用 `AudioMetadataReader.read(readArtwork = false)` 读一次文件头，按 §3.3 派生后经新 DAO 方法（如 `updateSongReleaseDate(songId, value)`）直接写 DB。覆盖「改 DATE」「删 DATE」「外部补 DATE」三种情形。

### 5.2 外部工具改标签

文件 date_modified 变化 → 增量同步 `needsUpdate` → augment 重读 → §3.3 派生 → 更新。现有机制自然覆盖，无需额外代码。

## 6. 排序接入

1. `SortOption` 新增 `YearSongReleaseDateAsc` / `YearSongReleaseDateDesc`（methodKey `year_song_release_date`），追加进 `YEAR_SONGS` 列表；`YearDetailViewModel` / 排序 sheet / `yearDetailSortOptionFlow` 走既有机制，无需额外接线。
2. `MusicDao.getSongsByYear` 的 ORDER BY 追加：

```sql
CASE WHEN :sortOrder = 'year_song_release_date_asc'  THEN (songs.release_date IS NULL OR songs.release_date = '0') END ASC,
CASE WHEN :sortOrder = 'year_song_release_date_desc' THEN (songs.release_date IS NULL OR songs.release_date = '0') END ASC,
CASE WHEN :sortOrder = 'year_song_release_date_asc'  THEN songs.release_date END ASC,
CASE WHEN :sortOrder = 'year_song_release_date_desc' THEN songs.release_date END DESC,
```

- 第一组把 NULL / `'0'` 沉底（两个方向都沉底）；不加这组时 `'0'` 的字典序会在 ASC 方向插队到日期之前；
- `yyyy-MM-dd` 与 `yyyy-01-01` 字典序即时间序；
- 末位 tie-break 沿用既有的 `title COLLATE NOCASE, id`。
3. `YearSongRelease`（专辑+碟+轨）保持原样。

## 7. i18n

核实结论：排序 sheet（`LibrarySortBottomSheet`）直接渲染 `SortOption.methodLabel` 硬编码英文，全库排序项均无 string-resource 本地化。新排序项 `Release Date` 与现有项保持同一模式，无需新增 strings。

## 8. 验证清单

- [x] `testDebugUnitTest`：`parseReleaseDateTag` / `deriveReleaseDateValue` 纯函数单测（`AudioMetadataReaderTest` 6 例）+ `SortOptionTest` 方向翻转 / `YEAR_SONGS` 归属（2 例新增）— 通过。
- [x] Room 编译校验：`MusicDao.getSongsByYear` 新 ORDER BY（含 `IN` 参数 CASE）与迁移 v11→v12 通过 KSP 编译，`app/schemas/12.json` 已生成并含 `release_date` 列。
- [x] **不写文件标签验证**：release_date 链路全部写点经代码审计只落 DB（`updateSongReleaseDate` = 纯 `UPDATE songs` SQL；`TagLib.savePropertyMap` 仅存在于用户主动编辑的既有路径）；新增 instrumented 测试 `AudioMetadataReaderReleaseDateTest`（3 例）以字节级对比证明：对完整日期 / 仅年份 / 无日期三种文件执行 `AudioMetadataReader.read` + `deriveReleaseDateValue` 后文件字节不变 — 模拟器实测通过。
- [ ] 模拟器实测：导入含完整日期 / 仅年份 / 无日期标签三种文件 → 年份详情页排序三档行为（完整日期正序、year 兜底排在其相对位置、无日期沉底）。
- [ ] 回填不重试：第二次同步后 `release_date IS NULL` 行数归零且不再读文件（日志确认）。
- [ ] App 内编辑 DATE 自定义字段 → DB `release_date` 立即更新。
- [ ] 外部改标签 → 下次同步后更新。

## 9. 决策记录

| # | 决策 | 说明 |
|---|---|---|
| 1 | 回填策略选 A | 同步时对 `release_date IS NULL` 的行补读文件头；配合「读过必留痕」防止无日期文件每次同步重复读 |
| 2 | 无完整日期时用 year 兜底为 1 月 1 日 | `yyyy-01-01` 正常参与排序，不沉底 |
| 3 | `year` 与 `release_date` 各存各的 | year 列不动，日期排序只用 release_date |
| 4 | 标签修改需同步 DB | App 内编辑走直接写 DB（MediaStore 只有 YEAR，月日不会回流）；外部修改走既有 date_modified → needsUpdate 链路 |
