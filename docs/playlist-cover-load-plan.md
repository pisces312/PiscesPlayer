# 播放列表封面（PlaylistArtCollage）加载优化方案

> 结论先行：**"缓存播放列表图标"不是正确的抓手**——拼图合成本身极便宜（4 次 draw），
> 真正的成本在底层 4 张封面的**首次冷抽取**（MediaStore 查询 + 从音频文件解出内嵌图 + 解码 + JPEG 编码写盘）。
> 方案应打在这一层：**减少成本 + 把成本挪出 UI 关键路径（预热）**。
> 状态：**仅调研，未改任何代码**（2026-09-15）。

## 1. 现状链路（已逐行确认）

```
LibraryScreen（PLAYLISTS tab）
└── PlaylistContainer / PlaylistItems         presentation/components/PlaylistContainer.kt:292
    └── PlaylistItem                          PlaylistContainer.kt:344
        ├── playerViewModel.observeSongs(songIds.take(4))   ← 每个 item 一次 Room Flow
        │   初始值 null → PlaylistCover(playlistSongs = emptyList()) → 先画 QueueMusic 占位图标
        └── PlaylistCover                     components/PlaylistCover.kt:38
            仅当 coverImageUri == null && coverColorArgb == null 时走拼图
            └── PlaylistArtCollage            components/PlaylistArtCollage.kt:34
                └── SmartImage × 1~4          components/SmartImage.kt:46
                    └── Coil AsyncImage，model = song.albumArtUriString
                        = "pixelplayer_local_art://song/<songId>"（utils/LocalArtworkUri.kt:14）
                        └── LocalArtworkCoilFetcher   data/image/LocalArtworkCoilFetcher.kt:19
                            └── AlbumArtUtils.ensureAlbumArtCachedFile(ctx, songId)   ← 未传 filePath！
                                ├─ HIT  filesDir/album_art/song_art_<id>_v4.jpg → 直接返回（快）
                                └─ MISS （慢，逐首发生）
                                    ├─ resolveSongMediaStoreInfo → ContentResolver.query(MediaStore)  ← 每首 1 次
                                    ├─ MediaMetadataRetriever.setDataSource(音频文件).embeddedPicture
                                    │   失败再兜底 AudioMetadataReader.read()（TagLib/JAudioTagger 全文件解析）
                                    └─ boundArtworkForCache：BitmapFactory 全解码 → 缩放(≤1536px) → JPEG(90) 编码 → 写盘
```

`album_art/` 是按**"该歌的封面曾被显示过"**逐个惰性生成的。播放列表图标的特点是
**一次拉进 4N 首"从未在任何列表首屏出现过"的歌** → 全部冷抽取 → 用户看到的就是"封面一个个从音频文件里冒出来"。

## 2. 已确认的问题清单

| # | 问题 | 位置 | 性质 |
|---|---|---|---|
| 1 | 冷抽取在 UI 关键路径上串行发生 | `LocalArtworkCoilFetcher.fetch()` | **主因** |
| 2 | 每首冷抽取多做一次 `ContentResolver.query`（没传 `filePath`，虽然 `Song.path` 就在手边） | `LocalArtworkCoilFetcher.kt:21` | 可省的成本 |
| 3 | 每个可见 playlist 各自订阅一个 Room Flow，且初始值为 `null` → 先占位图标再出图（两段闪烁） | `PlaylistContainer.kt:362` | 可消除的延迟 |
| 4 | 同一首歌在不同视图产生多份不同尺寸的缓存条目：拼图内 1 首用 `Size(256,256)`、2/3/4 首用 `Size(128,128)`、`SmartImage` 默认 `300`、`OptimizedAlbumArt` 用 `2048` | `PlaylistArtCollage.kt` 各处 | 稀释 40MB 内存缓存 → 命中率下降 |
| 5 | `SmartImage` 不传 `memoryCacheKey`（`OptimizedAlbumArt` 传了）→ Coil 用含 size/parameters 的默认 key | `SmartImage.kt:108` | 同上 |
| 6 | `SmartImage` 默认 `allowHardware = false` → 封面全部是软件位图，绘制时每帧上传纹理 | `SmartImage.kt:57` | 渲染开销（非加载耗时） |
| 7 | ~~自建 loader 导致预取白做~~ **已更正，见 §2.1**：`PrefetchAlbumNeighborsImg` 是**死代码**（全仓 0 调用点）→ 运行时零影响 | `scoped/PrefetchAlbumNeighbors.kt:20-53` | 死代码（**不是** bug） |

> 说明：第 6 项不是"加载慢"，但 48dp 图标里塞 4 张软件位图，滚动时确有开销。

### 2.1 复核更正：第 7 项是死代码，不是 bug（2026-09-15 复查）

初稿把 `PrefetchAlbumNeighborsImg` 记为"确凿 bug / 预取等于白做"，**这是过度断言**。复查结论：

1. **它是死代码**。全仓 `grep PrefetchAlbumNeighborsImg` 只命中定义处（`PrefetchAlbumNeighbors.kt:20`），**0 个调用点**。
   同一文件里**活的那个**是 `PrefetchAlbumNeighbors`（`:57`），由 `AlbumCarouselSelection.kt:82` 调用，
   且它用的是 `coil.Coil.imageLoader(context)` **全局实例**（`PrefetchAlbumNeighbors.kt:67`）——写法本来就是对的。
2. **"无 diskCache"这句是错的**。核对 Coil 2.7.0 源码 `ImageLoader.Builder.build()`：

   ```kotlin
   memoryCacheLazy = memoryCache ?: lazy { MemoryCache.Builder(applicationContext).build() },
   diskCacheLazy   = diskCache   ?: lazy { SingletonDiskCache.get(applicationContext) },
   callFactoryLazy = callFactory ?: lazy { OkHttpClient() },
   ```

   → 自建 `ImageLoader(context)` 的**磁盘缓存在全局是共享的**（`SingletonDiskCache` 单例）。
   源码 KDoc 也写明："By default, [ImageLoader]s share the same disk cache instance."
3. **"内存缓存独立"这句是对的**：内存缓存是**每实例独立**新建的（与上面对照）。
   叠加它对本地的 `pixelplayer_local_art://` 设了 `diskCachePolicy(DISABLED)`（`:35`），
   所以它预取的结果在两处缓存里都留不下 —— **假如**被调用，确实等于白做。
4. **但 0 调用点 ⇒ 运行时影响为零。** 真正值得处理的是"防呆"：函数名与活的
   `PrefetchAlbumNeighbors` 只差一个 `Img`、参数形状也相似，下一个改这块的人很容易把错误写法抄进活路径。

> **处理结果（2026-09-15）**：已按方案 A 删除 `PrefetchAlbumNeighborsImg`（`PrefetchAlbumNeighbors.kt` 净 -38 行），
> `assembleDebug` 通过。`PrefetchAlbumNeighbors` 与所有缓存行为未变。

### 2.2 复核中确认**没有**问题的地方（防止后续误改）

- `AlbumArtCacheManager.lastCleanupTime` 初始值 `0L` 是**正确**的哨兵写法（`:54`）：`now - 0L` 远大于
  `MIN_CLEANUP_INTERVAL_MS`，首次调用能正常执行清理。**这里若写成 `Long.MIN_VALUE` 才会溢出**成"永远跳过清理"。
- **活的** `PrefetchAlbumNeighbors` 传的是调用方的 `targetSize`（`AlbumCarouselSelection.kt:87`），
  与渲染尺寸一致 → 不存在"预取尺寸 ≠ 显示尺寸导致 miss"的问题。（死掉那个用的是默认 `Size.ORIGINAL`，是另一个坑。）
- LRU 有触发点：`cacheAlbumArtBytes` 每次写盘后 fire-and-forget 调 `cleanCacheIfNeeded`（`AlbumArtUtils.kt:382`）。
- `CreatePlaylistScreen.kt:329` / `:760` 也在 `LaunchedEffect` 里 `ImageLoader(context)`，但**不是缺陷**：
  一次性加载用户刚选的封面图，用完即解引用（Coil KDoc：`shutdown()` 可选，失去引用会自动回收）；
  `callFactory` 是 `lazy`，content:// 不走网络 → 连 OkHttpClient 都不会创建。

### 2.3 半落地（不是 bug，是功能缺口）

`AlbumArtCacheManager` 有 **4 个 public API 全仓无调用点**：
`getCacheSizeBytes` / `getCacheSizeFormatted` / `getCachedFileCount` / `clearAllCache`。

即：设置页只有"专辑封面缓存上限"滑杆（`SettingsCategoryScreen.kt:479`），
**既没有"当前占用 XX MB"的显示，也没有"立即清理"按钮**。
叠加"`filesDir` 普通文件管理器看不到"这条，封面缓存当前对用户是**完全不可见、不可管理**的
——能力已经写好了，只是没接线。

## 3. 方案

### P0 — 低风险、直接收益（建议先做这一批）

**P0-1 复用 `Song.path`，砍掉每首一次的 MediaStore 查询**
- `PlaylistArtCollage` 构造 `ImageRequest` 时把 `song.path` 塞进 `parameters`
  （Coil 2：`ImageRequest.Builder.parameters(...)`），`SmartImage` 已支持 `model is ImageRequest` 分支（`SmartImage.kt:103`）。
- `LocalArtworkCoilFetcher` 从 `options.parameters` 取出，传给 `ensureAlbumArtCachedFile(filePath = ...)`。
- 收益：每首冷抽取省一次 binder 查询（量级 10–50ms）。`Song.path` 不变 → 缓存 key 稳定，无害。
- 风险：低。注意 `parameters` 参与 Coil 默认 memoryCacheKey 生成。

**P0-2 批量查歌，替代 N 个 Room Flow + 消除占位图标闪烁**
- 在 `PlaylistItems` 层一次性收集 `filteredPlaylists.flatMap { it.songIds.take(4) }.distinct()`，
  一次 `getSongsByIds` → `Map<String, Song>` 下发到 `PlaylistItem`。
- 收益：N 次查询 → 1 次；`PlaylistCover` 首帧就有 `songs`，不再先显示 QueueMusic 占位图标。
- 风险：中低。**实施前需确认 `getSongsByIds` 是否保序 / 重复 id 的处理**（`IN (...)` 结果顺序不保证）。
- 参数上限：可见行 ≤12 × 4 = 48，远低于 SQLite 上限，安全。

**P0-3 进列表即预热首屏**
- 拿到 `Map<songId, Song>` 后，对可见 + 上下各 2 行的 playlist 封面做
  `Coil.imageLoader(context).enqueue(...)`（`size(128,128)`、`memoryCachePolicy(ENABLED)`、低优先级）。
- 收益有限（只提前几十毫秒）。**真正的收益在 P1**，此项可选。

### P1 — 治本：把冷抽取挪出 UI 关键路径

**P1-1 曲库扫描/同步完成后，后台批量预热"播放列表封面用到的歌"**
1. 触发点：扫描完成信号（`data/observer/MediaStoreObserver` 或同步 Flow 完成）。
2. 读全部 playlist 的 `songIds.take(4)` → 去重 → 剔除已有 `album_art/song_art_<id>_v4.jpg` 的。
3. 首批限量（如 200 首）、限并发（`Semaphore(2~4)`）、`Dispatchers.IO`。
4. 直接调 `AlbumArtUtils.ensureAlbumArtCachedFile(ctx, songId, filePath)`（**必须带 path**），
   写完磁盘即可，不必 enqueue Coil。
5. 可考虑 WorkManager + `Constraints`（充电/空闲），避免影响前台。

- 收益：切到播放列表 tab 时磁盘已就绪，Coil 直接出图 → 冷抽取从用户感知中消失。
- 代价/风险：
  - 一次批量媒体解析（CPU + IO），必须限流；
  - **`ensureAlbumArtCachedFile` 的兜底路径会走 `AudioMetadataReader`（JAudioTagger）解析整个文件，慢一个数量级**
    → 批量预热建议新增一个"仅 MMR、不兜底"的开关，否则预热耗时会失控；
  - 损坏文件 / 已卸载存储需 try-catch 兜住（现有代码已有异常处理，但要覆盖）。

**P1-2 统一 targetSize + 统一 memoryCacheKey（提高命中率）**
- `PlaylistArtCollage` 单首场景也用 `Size(128,128)`（现为 256），与多首场景共享同一缓存条目。
- 给 `SmartImage` 传 `memoryCacheKey = albumArtMemoryCacheKey(model, size)`，与 `OptimizedAlbumArt` 对齐。
- `allowHardware = true` 仅限拼图小图；**必须实测**：`Modifier.clip(CircleShape)` +
  `graphicsLayer(rotationZ/scale)`（`PlaylistCover.kt:73-78`）与 HardwareBitmap 的兼容性，
  若有问题则只对无 `graphicsLayer` 的分支开启。
- 内存缓存 40MB（`AppModule.kt:264`）是否上调到 64/80MB：**先测命中率再决定**，别凭感觉调。

### P2 — "缓存播放列表图标"（用户字面诉求）：评估后**不建议**作为主方案

若坚持实现：`@Singleton PlaylistCollageCache`，key = `playlistId + songIds.take(4) + coverShape 参数`，
value = `ImageBitmap(96×96)`，LRU ~64 项。

- 收益：把 4 次解码 + 4 次 draw 降为 1 次 draw。**在稳态（内存/磁盘全命中）下单行省约 0.5–2ms**。
- 对首屏"冷抽取"**零帮助**——这是用户实际感知的问题。
- 代价：失效管理复杂（songIds 变 / shape 变 / `clearAllCache` / `AlbumArtCacheManager` LRU 淘汰封面）、
  内存常驻、与 Coil 缓存重复。
- **结论：投入产出比差。** 除非 Macrobenchmark 实测证明稳态下 4 张解码确实是瓶颈，否则不做。

## 4. 验证方法（不要用推断代替实测）

1. **基线**：`adb shell pm clear com.pisces312.piscesplayer.debug` 清掉 `filesDir/album_art` →
   进播放列表 tab → 录屏逐帧统计"最后一个封面出现"的时刻。
2. **打点**：在 `ensureAlbumArtCachedFile` 临时加日志（`hit/miss` + `songId` + 耗时 + 是否走了 JAudioTagger 兜底）。
3. **抽查**：`filesDir/album_art` 的文件数与 mtime 变化，确认冷抽取规模。
4. **定阈值**：先用实测分布确定"HIT 应 < X ms"，避免把正常开销误判为瓶颈。
5. **回归**：`baselineprofile` 模块（已存在）加 `PlaylistTabScroll` 场景做 Macrobenchmark。

## 5. 建议执行顺序

1. P0-2 → P0-1（可合并一次提交，改动集中在 `PlaylistContainer.kt` / `PlaylistArtCollage.kt` / `LocalArtworkCoilFetcher.kt`）。
2. 实测基线，判断首屏冷抽取是否仍是主因。
3. 若仍是 → 做 P1-1（批量预热，注意"不兜底 JAudioTagger"这个前提）。
4. P1-2 与 P2 都**先测再动**。
5. ~~**可选清理**：删除死代码 `PrefetchAlbumNeighborsImg`~~ —— **已于 2026-09-15 删除**（用户选方案 A）。
   连带的 `import coil.request.CachePolicy` 一并删除（全文件仅该函数用非限定名引用）；活的
   `PrefetchAlbumNeighbors` 一字未改。**注意这是防呆，不是修 bug。**
6. 封面缓存"可见/可管理"缺口（§2.3）：把已有的 `getCacheSizeFormatted` / `clearAllCache` 接进设置页。
   不改缓存机制，只加 UI 入口。**未实施。**
