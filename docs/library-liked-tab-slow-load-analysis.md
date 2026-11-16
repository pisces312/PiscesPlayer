# 曲库「我喜欢」页显示为空 —— 加载链路分析与修复方案

> 调研日期：2026-11-17
> 状态：**已落地（2026-11-17）**。采用 A′ + §6 修法；B 经 bytecode 验证前提不成立已否决；C 暂缓。
> 改动：`LibraryScreen` 四个分页订阅移入 HorizontalPager 页面分支（利用已有 `cachedIn`）；
> 「全选」改用 `PlayerViewModel.getFavoriteSongsForSelection()`（suspend 全量快照，与 SONGS 分支同模式）；
> SONGS/ALBUMS/ARTISTS/LIKED 空状态改传 `LibraryViewModel.effectiveStorageFilter`。
> 结论先行：**收藏没丢，是首屏渲染被拖慢/被打断**；SQL 与索引不是瓶颈（§2 有实测），
> 问题在分页器的订阅时机、placeholder 配置与重建频率（§3）。

## 1. 现象与澄清

「曲库 → 我喜欢」页打开后一段时间内显示空状态（「还没有喜欢的歌曲」），过一会儿又一次性全部出现；
播放页的心形仍是实心、收藏计数正常。

**这个澄清很关键**：心形实心 ⇒ `favorites` 表里有行且 `isFavorite` 读取正确 ⇒
不是「收藏被同步删掉」「写入被静默丢弃」「换包名后重来」这类数据丢失问题。
排除了数据层之后，嫌疑集中在 **`favoritesPagingFlow` 拿到第一页的耗时** 上。

「一次性全部出现」而不是「一点点刷出来」，也是判据：说明卡住的是**首页提交**（count + 第一页两趟往返），
而不是逐页 load 慢。

## 2. 已排除：SQL 与索引不是瓶颈（实测）

用本地 SQLite 复刻 `MusicDao.getFavoriteSongsPaginated` 的同款语句
（2 万首歌、1500 首收藏、80 个目录、10 条 `allowedParentDirs` 参数，`ORDER BY` 为 10 段 `CASE WHEN`）：

| 操作 | 耗时 |
|---|---|
| `COUNT(*) FROM (… ORDER BY 10 段 CASE WHEN)` | 2.2 ms |
| `COUNT(*)`（去掉 `ORDER BY`） | 0.6 ms |
| 第一页 `LIMIT 50 OFFSET 0` | 0.9 ms |
| 加 `favorites(is_favorite, timestamp)` 索引后 | COUNT 3.3 ms / 首页 1.1 ms（**更差**） |

加索引反而变差，执行计划仍是 `USE TEMP B-TREE`（文件排序），即索引对这条形态的语句没被用上。

**结论：不要改这段 SQL，也不要加这个索引。** 毫秒级的查询解释不了数十秒的空窗。

## 3. 真正的嫌疑（按可能性排序）

### 3.1 进曲库就并发预取 4 个分页器

`LibraryScreen.kt:996-999` 在曲库页**无条件**订阅四个分页流：

```kotlin
val allSongsLazyPagingItems = libraryViewModel.songsPagingFlow.collectAsLazyPagingItems()
val albumsLazyPagingItems    = libraryViewModel.albumsPagingFlow.collectAsLazyPagingItems()
val artistsLazyPagingItems   = libraryViewModel.artistsPagingFlow.collectAsLazyPagingItems()
val favoritePagingItems      = libraryViewModel.favoritesPagingFlow.collectAsLazyPagingItems()
```

「我喜欢」不是首屏必需，却和另外三个抢同一个 Room 查询执行器（默认 4 线程），
且每个 Pager 各要跑一次 COUNT + 第一页（placeholder 模式下见 3.2），
四个 Pager 一起进场 ≈ 八趟查询排队。

### 3.2 `enablePlaceholders = true`

`MediaStoreSongRepository.kt:446-450` 的 `defaultPagingConfig`：

```kotlin
private val defaultPagingConfig = androidx.paging.PagingConfig(
    pageSize = 50,
    enablePlaceholders = true,
    maxSize = 250
)
```

Room 的 `LimitOffsetPagingSource` 在 placeholder 模式下**必须先拿到 count 才能发出第一页**，
首屏天然多一趟 count 往返；count 没回来之前 UI 拿不到任何 item。

### 3.3 Pager 被反复重建（与「等一会儿一次性全出来」最吻合）

收藏流是两层 `flatMapLatest` 串联（`MediaStoreSongRepository.kt:482-513`）：

```
combine(allowedDirectories, blockedDirectories)      // 第 1 层：目录偏好
  .flatMapLatest { flow { emit(Pager(...).flow) } }  // 第 2 层：表失效
```

上游 `LibraryStateHolder.kt:147-153` 还有一层 `combine(sortOption, effectiveStorageFilter).flatMapLatest`。
任一处变化都会丢弃正在进行的首页加载重来：

- 目录偏好（允许/阻止目录）任何一次写入；
- 排序项、存储筛选、「仅云端」开关（`effectiveStorageFilter`，`LibraryStateHolder.kt:85-91`）变化；
- **同步期间批量写 `songs` 表触发的表失效** —— 冷启动刚进曲库时正好撞上启动同步。

每次重建 = count + 首页重新排队；几次叠加就能吃掉几十秒，最后数据齐了才一次性发出。

### 3.4 组合期 O(n) 快照遍历（次要，但量级随收藏数涨）

`LibrarySongsAndFavoritesTabs.kt:120-126`：

```kotlin
val currentSongListIndex = remember(favoriteSongs.itemCount, currentSongId) {
    val items = favoriteSongs.itemSnapshotList
    items.indexOfFirst { it?.id == currentSongId }
}
```

`itemCount` 一到就物化整张快照并在**主线程**线性扫一遍。收藏上千时是一次主线程长任务，
会放大前面几项的卡顿观感（但不解释「空着几十秒」）。

## 4. 30 秒自测：区分 3.3 / 3.2 与 3.4

| 操作 | 观察 | 结论 |
|---|---|---|
| 冷启动后**立刻**进曲库 → 切「我喜欢」 | 慢 | 嫌疑 3.3（被启动同步打断）或 3.2 |
| 等首页完全稳定后**退出重进**曲库 → 切「我喜欢」 | 快 | 确认是**启动竞争**，非结构性问题 |
| 同上 | 照旧慢 | 看收藏总数是否上千 ⇒ 3.4；否则 3.2 |
| 关掉「仅云端」开关后再试 | 变快 | `effectiveStorageFilter` 链路在参与重建 |

补充：若能在真机抓一次 trace，`favoritesPagingFlow` 的 emit 次数（每重建一次 emit 一次）
是最直接的证据 —— emit 次数 > 1 就坐实 3.3。

## 5. 建议修法（均不动 SQL）与落地结论

### A. 只在页面可见时订阅「我喜欢」分页流（首选）→ **已落地为 A′，推广到四个 tab**

把 `LibraryScreen.kt:996-999` 的四个无条件订阅**各自挪进 HorizontalPager 的页面分支**。
注意：tab 内容在 `HorizontalPager(beyondViewportPageCount = 1)` 里，页面组合 = 当前页 ± 1，
所以不能按 `currentTabId` 门控（相邻页会被置空），必须把 `collectAsLazyPagingItems` 放进页面 lambda。

- 收益：首屏只有当前页 ± 1 的 Pager 在跑，LIKED 彻底退出冷启动竞争。
- 风险（已排除）：四条流在 `LibraryViewModel` 里**早已 `cachedIn(viewModelScope)`**
  （`LibraryViewModel.kt:14-20`，调研时遗漏），页面重进组合直接回放缓存，不重查。
- 连带改动：顶栏「全选」原引用 hoisted 的 `favoritePagingItems.itemSnapshotList`，移入分支后不可见，
  改为 `scope.launch { selectAll(playerViewModel.getFavoriteSongsForSelection()) }` —
  suspend 全量快照（`getFavoriteSongsOnce` + effective filter），与 SONGS 分支的
  `getSongsForCurrentLibrarySelection()` 同模式，还顺带修掉了「只选已加载页」的旧瑕疵。

### B. 收藏 Pager 关掉 `enablePlaceholders` → **已否决（前提为假）**

反编译 room-paging 2.8.5（本机 Gradle 缓存）：`CommonLimitOffsetImpl.initialLoad` **无条件先调
`queryItemCount`**，全链无 `placeholdersEnabled` 分支 —— count 往返省不掉。
且按 §2 实测 count 仅 2.2 ms，即使省掉对数十秒空窗也无意义。**不做**，还避免 `itemCount` 语义变化的风险。

### C. `indexOfFirst` 的 remember key 换成「当前歌 id + 已加载项」→ **暂缓**

千级收藏下是亚毫秒级，不值得动；收藏数上十万再说。

落地顺序：**A′ + §6 同一次提交；B 否决；C 暂缓。**

## 6. 顺带发现的独立缺陷：空状态用错筛选源

空状态传的是**原始** `currentStorageFilter`（`LibraryScreen.kt:1491`），
而列表数据走的是 **`effectiveStorageFilter`**（`LibraryStateHolder.kt:85-91`，
「隐藏本地媒体」开着时强制 `ONLINE`）。

后果：一旦 `hideLocalMedia` 打开，列表实际只有云端收藏，空状态却按用户手选的筛选去解释，
会显示「还没有喜欢的歌曲」之类的错误文案，**掩盖真实原因**。
这是与本次卡顿无关的独立 bug，建议一并修（让空状态也用 `effectiveStorageFilter`）。

## 7. 改动范围

- 新增：本文件。
- 已落地（2026-11-17）：
  - `presentation/screens/LibraryScreen.kt` — 四个 `collectAsLazyPagingItems()` 移入 pager 页面分支；
    「全选」LIKED 分支改走 `getFavoriteSongsForSelection()`；
    新增 `effectiveStorageFilter` 状态收集；SONGS/ALBUMS/ARTISTS/LIKED 空状态传参改用它。
  - `presentation/viewmodel/LibraryViewModel.kt` — 暴露 `effectiveStorageFilter: StateFlow<StorageFilter>`。
  - `presentation/viewmodel/LibraryStateHolder.kt` — `effectiveStorageFilter` 由 private 改 public。
  - `presentation/viewmodel/PlayerViewModel.kt` — 新增 `getFavoriteSongsForSelection()`。
- 未做：修法 B（bytecode 证伪）、修法 C（量级不足）、YEARS 空状态（其数据本就不过滤，
  传任何 filter 都误导，超出本次范围）。
- 顺带发现未修：`PlayerViewModel.shuffleFavoriteSongs()` 用**原始** `currentStorageFilter`
  调 `getFavoriteSongsOnce`（`PlayerViewModel.kt:1407`），与 LIKED 列表的 effective filter 不一致
  —— hideLocalMedia 开启时 shuffle 池会混入本地收藏。属播放行为问题，单独评估，不在本次改动内。
