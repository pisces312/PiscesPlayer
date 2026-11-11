# 备份恢复后播放列表为空 —— 根因与修复方案

> 状态：**方案 A 已实现（2026-09-11）**，实现说明见文末第 9 节。
> 分支：`pisces/port`（`pisces312/PixelPlayerOSS`，GPL-3.0-or-later fork）
> 发现：2026-09-11，清应用数据后走初始设置导入备份，播放列表名称恢复、列表内歌曲全空。

## 1. 现象

- 清除应用数据 → 重新进入应用 → 在初始设置（Setup）流程里导入 `.pxpl` 备份。
- 播放列表（名称、封面、排序偏好）恢复成功；进入每个列表，**歌曲全部为空**。
- 等本地媒体库扫描完成后，列表依然是空的 —— 丢失不可逆，只能重新导入备份。

## 2. 根因（证据链）

### 2.1 本地歌曲 ID 是稳定的，问题不在 ID

- 本地歌曲的 `id` 直接取 `MediaStore.Audio.Media._ID`
  （`MediaStoreSongRepository.fetchSongsFromMediaStore`，`SyncWorker` 写入 Room `songs` 表）。
- 清应用数据只清应用私有 Room，**不清系统 MediaStore**，所以同一台设备上扫描前后 ID 不变。
- 播放列表详情按 ID 回表取歌：`PlaylistViewModel.loadPlaylistDetails`
  → `musicRepository.getSongsByIds(songIds)` → Room `WHERE id IN (...)`，
  并在 `MusicRepositoryImpl.getSongsByIds` 里按列表顺序重排。**引用只要还在，扫描完成后自然能显示。**
- `SyncWorker` 全程不碰播放列表表（无任何 playlist 引用），
  全局只有用户主动删歌时才会 `removeSongFromAllPlaylists` —— 悬挂引用不会被同步流程清理。

### 2.2 恢复播放列表时，对空库做了"解析不到就丢弃"

`data/backup/module/PlaylistsModuleHandler.kt`：

1. `restore()` → `resolvePlaylists(backupPlaylists, songMetadata)`（L116）。
2. `resolvePlaylists` 查 `musicDao.getAllLocalSongSummaries()`（L183）作为解析基准：
   - 直接 ID 命中 + 元数据校验一致 → 保留；
   - 直接 ID 不命中 → 按 title+artist（+album/duration 消歧）做跨设备元数据匹配；
   - **匹配不到 → `resolveSongId` 返回 null**。
3. L212-217：`playlist.songIds.mapNotNull { resolutionCache[songId] }` ——
   **null 的 ID 被直接丢弃**，随后 `replaceAllPlaylists(finalPlaylists)`（L128）
   事务性落库（`LocalPlaylistDao.replaceAllPlaylistsTransactional`）。

清数据后 Room `songs` 表是空的：所有歌既无直接命中、元数据索引也为空 →
全部 unresolved → 全部丢弃 → 落库的就是空 `songIds`。之后扫描再补齐 `songs` 表，
播放列表里的引用已经被物理删除，无法自愈。日志里只留一行 warning：
`Playlist restore: 0/N songs resolved, N unresolved`（L209）。

### 2.3 首次启动的真实扫描时序：向导期间根本不会扫描

**结论：App 启动不会自动开始扫描；首次扫描最早在向标点"完成"的瞬间才 enqueue。
向导里点导入备份时，Room `songs` 表必然为空，本路径是必现而非竞态。**

逐段证据：

1. `PiscesPlayerApplication.onCreate` 调 `SyncManager.start()`，但 `start()` 只做三件事：
   注册媒体变更观察者、注册回前台追赶同步、排 24h 周期维护 —— **本身不发起扫描**。
2. 两条"自动同步"路径在向导期间都被 `initialSetupDone=false` 显式拦截：
   - 存储变更自动同步：`SyncManager.runLocalAutoSyncAfterDebounce`（L345 附近）直接 return；
   - 回前台追赶同步：`maybeRunForegroundCatchUpSync`（L378）直接 return。
3. `MainActivity` 里唯一的启动扫描入口 `mainViewModel.startSync()`：
   - 只在 `LaunchedEffect(showSetupScreen)` 且 `showSetupScreen == false` 时调用（L327-332）；
   - 而 `showSetupScreen = !isSetupComplete || !permissionsValid`（L319-325），向导显示期间恒为 true；
   - `MainViewModel.startSync()` 内部还再判一次 `isSetupComplete.value == true`（L86-93）。
4. 向导过程中唯一能触发扫描的用户操作是"修改文件夹过滤规则后应用"
   （`SetupViewModel.applyPendingDirectoryRuleChanges → forceRefresh`，L212-219）；
   **单纯授予权限不会触发扫描。**
5. 备份恢复入口 `SetupViewModel.restoreFromPlan`（L359）在向导页内直接执行，此时：
   初始设置未完成 → 上述 1-4 全部不产生扫描 → `songs` 表保证为空 → 所有歌曲引用必被丢弃。
6. 恢复成功后 UI 只是翻到向导完成页（`SetupEvent.RestoreCompleted`，SetupScreen L234-243）；
   首次扫描要等用户点完成、`completeSetup(syncAfter=true) → syncManager.fullSync()`
   （SetupViewModel L419-424）才排队 —— 此时丢弃已经落库，扫描再完成也找不回引用。

设置页路径（`SettingsViewModel.restoreFromPlan` L1053）则是**竞态**：该路径在恢复成功后
确实会调 `syncManager.sync()`（L1071/L1079），但它发生在破坏性丢弃**之后**，
无法挽回被删的引用；若恢复时扫描只完成了一半，已扫到的留下、其余永久丢失。

### 2.4 对照：收藏模块没有这个问题

`FavoritesModuleHandler.restore` 原样 `favoritesDao.replaceAll(favorites)`，
不依赖当前曲库、不做解析、不丢 ID —— 所以同样清数据恢复，收藏在扫描完成后会自动亮起。
播放列表是唯一做"恢复时破坏性解析"的模块。

### 2.5 影响范围

| 场景 | 结果 |
|---|---|
| **向导中恢复（清数据后的主路径，本次复现路径）** | **必现：向导期间从不扫描，曲库保证为空，歌曲全空** |
| 设置页恢复，扫描未完成/进行中 | 竞态：已扫到的留下，其余永久丢弃（恢复后补的 `sync()` 救不回来） |
| 恢复后才把歌拷进手机 | 后续增量同步也找不回已删除的引用 |
| 跨设备恢复且新机曲库已扫完 | 现有元数据匹配逻辑正常，不受影响 |
| 智能歌单（smart playlist） | 不受影响（songIds 动态生成，且备份了生成源） |
| 云源（Navidrome/Jellyfin）歌曲 | 本就不导出（`buildCloudSongIdSet` 过滤），不在本次范围 |

## 3. 修复原则

**恢复时永远不允许因为"当前曲库没准备好"而永久删除歌曲引用。**
区分两种"匹配不到"：

- **库未就绪**（空库 / 扫描中）→ 保留原始引用，延后再解析；
- **库已就绪但确实没有这首歌**（跨设备且文件不存在）→ 才允许丢弃，等价于今天的行为。

同设备恢复时本地 ID 即 MediaStore ID，保留原 ID 后扫描一完成直接命中，
连元数据匹配都不需要。

## 4. 方案对比

### 方案 0：恢复前强制等扫描完成（不单独使用）

向导里先 `fullSync` 并挂起等待 WorkInfo SUCCEEDED，再跑恢复模块
（现状是向导期间完全不扫描，所以这步必须自己发起）。

- 优点：不改解析逻辑，恢复当时库就是全的。
- 缺点：大库扫描数分钟，恢复流程被卡死；设置页路径的竞态依然存在；
  用户没授权/没拷歌时会永久等待；跨设备匹配仍需要现有元数据逻辑。
- 结论：**不作为正确性保障；只吸收其中"向导恢复后立刻发起扫描"这一点并入方案 A**
  （见改动清单），不阻塞恢复流程。

### 方案 A（推荐）：空库不解析、保留原 ID、同步后补解析（不新增 Room 表）

1. **恢复时（`PlaylistsModuleHandler.restore`）按当前库状态分流：**
   - `getAllLocalSongSummaries()` 为空：**跳过整个 resolvePlaylists**，
     backup 的 `songIds` 原样落库；`songMetadata` 整体写入延后解析存储。
   - 库非空：沿用现有匹配规则，但"仍未匹配"的条目不立即丢弃，
     连同 metadata 进入延后解析存储；只有连 metadata 都没有的才按今天丢弃。
2. **延后解析存储（避开 Room 迁移）：** 用一个偏好字符串
   `playlist_restore_pending_json_v1` 存 `Map<backupSongId, SongMetadataEntry>`
   （与 legacy playlist 偏好同款做法，Room schema 停在 v6，不新增实体、不需要 migration）。
   悬挂的歌曲引用本来就留在 `playlist_songs` 表里，`getSongsByIds` 对缺失行天然过滤，
   显示层零改动。
3. **新增 `PlaylistRestoreResolver`（@Singleton）：**
   - 在 `PiscesPlayerApplication.onCreate` 紧挨 `syncManager.start()` 启动；
   - 监听 `SyncManager` 的 unique work 状态，**每次前台同步 SUCCEEDED 后跑一次**；
     启动时也跑一次（覆盖 app 没运行时同步已完成的情况）；
   - 现有两个"恢复后触发同步"的点直接被它接住：向导完成时的 `completeSetup → fullSync`、
     设置页恢复成功后的 `syncManager.sync()`（SettingsViewModel L1071/L1079，已存在，无需新增）；
   - pending 为空直接返回，开销可忽略。
4. **补解析规则（复用现有 `resolveSongId` 逻辑，抽成共享内部函数）：**
   - 直接 ID 命中且元数据一致（或无 metadata）→ 确认，移出 pending；
   - 元数据匹配到别的 ID → 把该播放列表里的旧 ID 重映射为新 ID，保持 `sort_order`；
   - 仍未命中 → 保留，等下一次同步。
5. **终态清理：** 仅当一次**全量**同步（fullSync/rebuild）成功、且库非空之后，
   仍未解析的 pending 条目才从播放列表移除并清空 pending（这才是今天"丢弃"语义的正确时机）。
   增量同步不触发清理（增量不保证扫全量文件）。
6. **回滚/重复恢复一致性：** `restore()` 每次先覆盖写 pending；
   `snapshot()` 产物没有 songMetadata，rollback 走 restore 时同步清空 pending，
   避免回滚后残留重映射。

### 方案 B（更重，暂不做）：新增 Room pending 表 + 按播放列表粒度记录状态

可记录"每个 pending ID 属于哪些列表、重试次数、最后一次尝试时间"，
支持更细的 UI 提示（如"3 首歌未在本机找到"）。代价是 Room v6→v7 实体变更 + migration，
当前需求用方案 A 的单个 JSON 偏好即可覆盖，B 留作以后真要做恢复报告时再升级。

## 5. 推荐改动清单（方案 A，文件级）

| 文件 | 改动 |
|---|---|
| `data/backup/module/PlaylistsModuleHandler.kt` | restore 空库短路；未匹配条目入 pending 而非丢弃；抽出可复用的解析函数；restore/rollback 时覆盖/清空 pending |
| `data/preferences/UserPreferencesRepository.kt` | 新增 pending JSON 的 get/set/clear（按既有偏好键模式，英文 strings 无需新增） |
| 新增 `data/backup/restore/PlaylistRestoreResolver.kt` | 同步完成/启动时补解析 + 全量同步后的终态清理；重映射走 `PlaylistPreferencesRepository`（按列表 `replacePlaylistSongs` 保序） |
| `PiscesPlayerApplication.kt` | 启动 resolver（与 `syncManager.start()` 同位置，显式可 grep） |
| `data/worker/SyncManager.kt` | 如需区分全量/增量完成事件，补一个暴露 WorkInfo/同步模式（`SyncMode` 走 worker inputData）的 Flow；先读现有 `syncProgress`，能复用就不加 |
| `SetupViewModel.kt` | 向导内恢复**成功后立即** enqueue 一次 `fullSync(deepScan=false)`（现状要等用户点完成才首扫；不等结果，resolver 兜底），让用户停在完成页时扫描就已经在跑 |
| `SettingsViewModel.kt` | 无需改动（恢复成功后已有 `syncManager.sync()`，resolver 会接住） |

约束：
- glue 层全部在本仓库重写；不搬专有 PixelPlayer 实现。
- 不新增用户可见文案则不动 strings；若加"等待本地扫描"提示，只加英文 `values/`。
- 不改 Room schema、不加 migration。

## 6. 验证计划

1. 单元测试（JUnit5，`:app:testDebugUnitTest`）：
   - 空库 restore：songIds 原样保留、pending 全量写入；
   - 非空库：直接命中保留 / 元数据重映射 / 真缺失入 pending 三类；
   - 补解析：同步后直接命中、元数据改映射且顺序不变；
   - 全量同步后仍缺失才清理；增量同步不清理；
   - rollback 清空 pending。
2. 真机手测（debug 包）：
   - 复现路径：清数据 → 向导中授予权限后**立刻**恢复（此时确认 logcat 无任何 SyncWorker 启动）
     → 恢复成功后扫描自动开始 → 扫描完成后列表歌曲自动出现且顺序不变；
   - 设置页在扫描进行到一半时恢复 → 剩余歌曲在同步结束后补齐；
   - 跨设备/删除部分文件后恢复：存在的歌保留，缺失的歌在一次全量同步后移除；
   - 加密备份路径同样验证一遍。
3. `:app:assembleDebug` + `:app:lintDebug` 通过。

## 7. 对当前已损坏数据的说明

本次 bug 已经把空 `songIds` 落库的安装，**装上修复包后不会自动找回歌曲**
（引用信息在恢复当时已被删除）。需要在曲库扫描完成后**重新导入一次同一个备份文件**，
方案 A 生效后即可完整恢复。

## 8. 不做的事

- 不改备份文件格式（`songMetadata` 字段现有备份里已具备，老备份直接受益）。
- 不处理云源歌曲的播放列表恢复（维持导出时过滤的现状）。
- 不做恢复结果 UI 报告（方案 B 范畴）。

## 9. 实现记录（方案 A 落地）

实际落地与上文方案的差异/细化：

1. **共享匹配器**：新建纯逻辑类 `data/backup/restore/PlaylistSongMatcher.kt`
   （无 Android 依赖、构造时传入 `List<SongSummary>`），原 handler 内的
   直接 ID 校验 / title+artist 匹配 / album、duration 消歧规则整体迁入，handler 与 resolver 共用；
   `PendingSongRef` 也定义在此（替代原 handler 内嵌的 `SongMetadataEntry`，Gson 字段名不变）。
2. **恢复分流**（`PlaylistsModuleHandler.restore`）：空库 → 全部 songId 原样保留、
   有 metadata 的入 pending；非空库未匹配但有 metadata → 保留 + pending；
   非空库且无 metadata → 才丢弃。legacy JSON 数组路径与 rollback 会清空 pending。
3. **pending 存储**：`UserPreferencesRepository` 新增
   `playlist_restore_pending_json_v1`（裸 JSON 字符串，Gson 由调用方持有），未动 Room schema。
4. **补解析器**：新建 `data/backup/restore/PlaylistRestoreResolver.kt`（@Singleton），
   在 `PiscesPlayerApplication.onCreate` 启动；启动时跑一次，并监听
   `getWorkInfosForUniqueWorkFlow(SyncWorker.WORK_NAME)` 的 SUCCEEDED。
   全量/增量的区分没有加新 Flow，而是给 SyncWorker 各 WorkRequest 加了
   `TAG_SYNC_MODE_FULL/INCREMENTAL/REBUILD` tag（WorkInfo 不暴露 inputData，tags 可读）；
   只有 FULL/REBUILD 同步成功后才清理确实找不到的 pending 引用。
5. **向导即扫**：`SetupViewModel.restoreFromPlan` 在成功/可完成的部分失败分支
   立即 `syncManager.fullSync(deepScan=false)`，不必等用户点完成。
6. **测试**：新增 `PlaylistSongMatcherTest`（10 例，纯逻辑）与
   `PlaylistsModuleHandlerTest`（6 例，MockK，覆盖空库保留/直接命中/重映射/
   非空库带 metadata 保留/无 metadata 丢弃/rollback 清 pending），全部通过；
   `:app:assembleDebug` 通过。仓库既有的 PlayerViewModelTest（25）与
   LibraryScreenFolderNavigationAnimationTest（1）失败与全项目 284 个 lint error
   均为改动前已存在的基线问题，本次新增/修改文件无 lint 新增问题。
7. **真机手测仍待执行**（第 6 节清单），UI 类改动按约定装真机验证后再视为闭环。
