# UX / 交互设计审阅与修复跟踪

> 审阅日期：2026-02-15  
> 范围：播放器手势、导航与列表、多选与破坏性操作、设置、无障碍、权限  
> 用途：记录问题、修复进度、有意忽略项。**修改状态时只改表格 Status 列**（`open` / `fixing` / `fixed` / `ignored`），忽略时在备注写原因。

## 总评

| 维度 | 评价 |
|---|---|
| 视觉一致性 | 好。主题、动效、触觉反馈铺得很满 |
| 手势可发现性 | 差。大量能力藏在手势里，无 onboarding / 无提示 |
| 破坏性操作安全 | 不一致。歌曲删除有保护，播放列表批量删除几乎裸奔 |
| 状态同步 | 有硬伤。删除路径会误关播放器 sheet、callback 可能不调 |
| 无障碍 | 中等。contentDescription 覆盖面大，但有硬编码英文与 TalkBack 盲区 |
| 代码/组件复用 | 差。多套 list item、多套 toast、多套 multi-select 模型并行 |

**Status 图例**：`open` 待处理 · `fixing` 处理中 · `fixed` 已修 · `ignored` 有意不做

---

## P0 — 数据损失 / 流程卡死 / 强制回流

| ID | 问题 | 位置 | Status | 备注 |
|---|---|---|---|---|
| P0-1 | 删除任意歌曲会强制收起 full player sheet；并静默取消该曲收藏 | `PlayerViewModel.removeSong` `app/src/main/java/com/pisces312/piscesplayer/presentation/viewmodel/PlayerViewModel.kt:3803-3820` | fixed | 仅 currentSong 时 collapse；收藏清理保留（删文件合理） |
| P0-2 | 播放列表批量删除：无确认、无 toast、无 undo（**只删列表元数据，不删音频文件**；删除保护不覆盖此路径） | `LibraryScreen.kt` 批量删除确认对话框 + toast | fixed | 已加确认（error 色）+ 成功 toast；undo 未做，可作后续项 |
| P0-3 | 批量删歌 early-out 不调 `onComplete` → 多选 sheet 卡死 | `PlayerViewModel.deleteSelectedFromDevice` `:3569-3574` | fixed | early-out 已补 clearSelection + onComplete |
| P0-4 | Setup 完成后拒绝通知权限会强制重进 Setup | `MainActivity.kt` / `SetupViewModel.requiredPermissionsGranted` / `SetupScreen` 门控 | fixed | 仅媒体权限挡主流程；通知页可跳过 |

---

## P1 — 一致性 / 可发现性 / 手势冲突

| ID | 问题 | 位置 | Status | 备注 |
|---|---|---|---|---|
| P1-1 | Mini 手势：死区、dismiss 40% 过远、竖向偏移被当成横滑切歌 | `MiniPlayerDismissGestureHandler.kt` | fixed | 去死区；dismiss 30%；tension 48dp；竖向占优时放弃切歌 |
| P1-2 | 进度条区域空 `detectVerticalDragGestures` 吞竖滑 | `FullPlayerContent.kt:1680-1682` | ignored | 有意隔离 seek 与 sheet 收起；用户确认 seek 竖滑是预期，暂不改 |
| P1-3 | 歌词页横滑切歌与纵向滚动轴向未锁；`hasTriggeredAction` 死代码 | `LyricsSheet.kt` | fixed | 改 `detectHorizontalDragGestures`，竖滑交还 LazyColumn；删死代码 |
| P1-4 | 「关闭播放列表」语义三套：mini 双向 / queue 仅左滑 / full 无 | Mini vs Queue dismiss | ignored | 使用中未觉不适，暂不改 |
| P1-5 | 多选只能长按进入；三套 selection 模型；离开 Library 不清理 | `LibraryScreen.kt` | ignored | 使用中未觉不适，暂不改 |
| P1-6 | 底部导航再点当前 tab 无响应（无 pop-to-root / scroll-top） | `PlayerInternalNavigationBar.kt` / `PlayerNavigationRail.kt` | fixed | 双击当前 tab 回顶；Search 双击回顶+聚焦搜索框。单击切换不变 |
| P1-7 | 云/本地同一按钮语义切换（下载 vs 删除） | `SongInfoBottomSheet.kt:624-640` | ignored | 使用中未觉不适，暂不改 |
| P1-8 | 空状态无 CTA；存储过滤器纯图标 | `LibraryEmptyState.kt` | ignored | 使用中未觉不适，暂不改 |
| P1-9 | Queue 开启手势藏在 expansion≥0.99 | `FullPlayerContent.kt:629-678` | ignored | 使用中未觉不适，暂不改 |

---

## P2 — 设置 / 无障碍 / 反馈 / 实现气味

| ID | 问题 | 位置 | Status | 备注 |
|---|---|---|---|---|
| P2-1 | Crossfade on/off 用 true/false 选项而非 Switch | `SettingsCategoryScreen.kt:958-967` | open | |
| P2-2 | 滑块写入策略分裂：库相关 draft+finish；crossfade/速度拖动即写 | `SettingsCategoryScreen.kt:448-491` vs `:969-993` | open | |
| P2-3 | 设置搜索缺 highlight：palette_style、navbar_corner、experimental、crossfade 时长 | `SettingsRegistry.kt:272,335,586`；`SettingsCategoryScreen.kt:969` | open | 与 AGENTS.md 注册五步对齐 |
| P2-4 | 「重置 Setup」「测试崩溃」无确认；「重置歌词」确认按钮非 error 色 | `SettingsCategoryScreen.kt:1190-1194,1211-1214,1522-1529` | open | 危险确认统一 error 色 |
| P2-5 | 硬编码英文：Playback position / Connecting… / ListenBrainz 副标题等 | `PlayerSeekBar.kt:123`；`UnifiedPlayerSheetShared.kt:134-143`；`SettingsRegistry.kt:194` | open | 走 strings + zh-rCN |
| P2-6 | 搜索历史行 pointerInput 不可见于 TalkBack | `SearchScreen.kt:615-618` | fixed | 改 `clickable`，随搜索历史接线落地 |
| P2-7 | Mini 传输按钮 36dp < 48dp；无全局最小触控尺寸 | `UnifiedPlayerSheetShared.kt:152,182,206` | open | |
| P2-8 | 双 toast 通道：`toastEvents` vs 裸 `Toast.makeText` | `LibraryScreen.kt:692` 等 | open | 统一 toastEvents |
| P2-9 | `DismissUndoBar` 仅用于关队列；文件删除/清空队列无 undo | `MainActivity.kt:986-1019`；`QueueBottomSheet.kt:1128-1150` | open | |
| P2-10 | 播放列表 merge 失败静默；空白名 no-op | `PlaylistViewModel.kt:1211,1225-1227` | open | |
| P2-11 | `clearSearchHistory()` 零调用方（死 API） | `PlayerViewModel.kt:4028` | fixed | 搜索页空态接 `SearchHistoryList`，清空带二次确认 |
| P2-12 | Seek 后 5s 忽略 position（差<0.04 除外）无 seeking 态 | `PlayerSeekBar.kt:67-76` | open | 缩短窗口 + pending chrome |
| P2-13 | Full player 首次展开后永不 uncompose | `FullPlayerCompositionPolicy.kt:44-49` | open | 性能/内存 |
| P2-14 | Mini free-drag 每帧 launch 新 animateTo | `MiniPlayerDismissGestureHandler.kt:159-169` | open | jank 源 |
| P2-15 | 死组件：`PlayerProgressBarSection` stub；`ExpressiveSongListItem` / `SelectionHeader` 零调用 | `presentation/components/` | open | 合并或删除，避免双轨设计 |
| P2-16 | `AllFilesAccessDialog` 存在但 MANAGE_EXTERNAL_STORAGE 不在 manifest；launcher 回调为空 | `MainActivity.kt:190-191` | open | 删死路径或真正接入 |
| P2-17 | Batch song delete 在 API30+ 仅靠系统 sheet，与 pre-R 自建确认不一致 | `PlayerViewModel.kt:3576-3609` | open | 统一风险文案（系统 sheet 可接受但文案要清楚） |
| P2-18 | 歌曲删除成功无 undo（文件不可逆可接受，但应明确） | `PlayerViewModel.kt:3736` | open | 至少区分「已删 N 跳过 M」；undo 仅适用于「移出库」 |
| P2-19 | 深链接缺失：无 `navDeepLink` | `AppNavigation.kt` / `Screen.kt` | open | 通知/外部打开 album/artist |
| P2-20 | 相关专辑 replace 导航与其他详情页 navigateSafely 不一致 | `LibraryScreen.kt:1346-1384` | open | |

---

## P0 详细说明与修复建议

### P0-1 删除歌曲误关播放器 + 误清收藏

**现象**：在曲库删任意一首（含非当前播放）歌，full player 被收起；该曲收藏被取消。

**根因**：

```kotlin
// PlayerViewModel.removeSong
toggleFavoriteSpecificSong(song, true)  // 静默取消收藏
_isSheetVisible.value = false           // 无条件 collapse
```

**建议**：

1. 仅当 `song.id == currentSong?.id` 时才 `_isSheetVisible.value = false`。
2. 删除路径不调用 `toggleFavoriteSpecificSong`（收藏表清理可在 `removeSongFromLibrary` 内部做，且不必「取消收藏」语义）。
3. 若删的是当前曲：collapse 后 toast 说明已跳过/停止，避免用户困惑。

**验收**：打开 full player → 在 Library 删一首非当前曲 → sheet 保持展开，收藏状态不变。

---

### P0-2 播放列表批量删除无确认/无反馈

**现象**：多选播放列表 → 删除 → 立刻消失，无对话框、无 toast、不可恢复。

**范围澄清**：`deletePlaylistsInBatch` → `localPlaylistDao.deletePlaylist` 只删列表实体与曲目关联（Room），**不删除磁盘音频**。删除保护（`songDeletionEnabled`）只拦「从设备删除歌曲」文件路径。风险是丢歌单元数据，不是丢文件。

**建议**：

1. 确认对话框：标题 `删除 N 个播放列表？`，正文说明仅删除列表、曲目文件保留；确认按钮 error 色。
2. 成功后 toast：`已删除 N 个播放列表`。
3. （可选后续）短时 undo：内存缓存 playlist 对象，复用 `DismissUndoBar`。

**验收**：删除必须经过确认；取消不删；确认后有 toast；失败路径也有 toast。

---

### P0-3 批量删歌 early-out 不调 onComplete

**现象**：选中曲全是「当前正在播放」时，多选 sheet 不关闭，无后续 UI 反馈。

**根因**：

```kotlin
if (deletableSongs.isEmpty()) {
    _toastEvents.emit(...)
    return@launch  // 缺 onComplete()
}
```

**建议**：该分支补 `onComplete()`；审计 `deleteSelectedFromDevice` / `deleteFromDevice` 所有 return 路径。

**验收**：全选当前播放曲并删除 → toast 出现，sheet 关闭，选择态清理。

---

### P0-4 通知权限导致强制重进 Setup

**现象**：Setup 已完成 → 系统里关闭通知权限 → 冷启动再次进入完整 Setup。

**根因**：

```kotlin
showSetupScreen = !isSetupComplete!! || !permissionsValid
// permissions 含 POST_NOTIFICATIONS
```

**建议**：

1. `permissionsValid` 只校验媒体权限（`READ_MEDIA_AUDIO` / `READ_EXTERNAL_STORAGE`）。
2. 通知权限：首次需要时单独请求；拒绝后在通知相关功能处引导，不再挡主流程。
3. 注意 `isSetupComplete == null` 的加载态不要闪进 Setup。

**验收**：完成 Setup 后关闭通知权限 → 直接进入主界面；媒体权限被拒仍应引导。

---

## 设计原则（防继续漂移）

1. **一个手势一个 owner**：sheet / queue / lyrics / list 用 nestedScroll 显式交接，禁止空 handler 吞事件。  
2. **破坏性操作三级梯度**：可逆（undo）> 可确认（dialog）> 系统级（MediaStore）——禁止静默成功。  
3. **手势必须有非手势路径**：长按选择要有「选择」按钮；滑删要有菜单；双击要有可见入口。  
4. **状态 scope 对齐 UI 生命周期**：selection、pending delete callback 在离开页面时清理。  
5. **组件只留一套**：list item / multi-select header / toast 通道分叉要么合并，要么在文档写明场景。

---

## 修复进度

| 日期 | 动作 | 相关 ID |
|---|---|---|
| 2026-02-15 | 完成全量交互审阅，建本文档 | — |
| 2026-02-15 | 修复 P0-1/3（PlayerViewModel）；P0-2 确认+toast；P0-4 通知权限降级；compileDebugKotlin 通过 | P0-1..4 |
| 2026-02-15 | 补 UT：removeSong sheet 可见性、deleteSelected early-out、SetupUiState.requiredPermissionsGranted；testDebugUnitTest 通过 | P0-1,3,4 |
| 2026-02-15 | 修复 P1-1 Mini 手势（死区/阈值/竖滑锁轴）与 P1-3 歌词横滑专用检测；P1-2/4/5/6/7/8/9 按用户反馈 ignored | P1-1,3 |
| 2026-02-15 | P1-6：双击当前 root tab 回顶；Search 双击聚焦搜索框。覆盖 Home/Library 子页/Stats | P1-6 |
| 2026-02-15 | 提交 P1-1/P1-3；开始排查双击回顶模拟器无效 | P1-6 |
| 2026-09-13 | 搜索页空态改为搜索历史（清空二次确认 + 单项删除 + TalkBack 可点）；流派从搜索页移入音乐库独立 tab（名称排序） | P2-6, P2-11 |

> 更新规则：开始改某项把 Status 改为 `fixing`；合入后改 `fixed` 并在上表补一行；有意不做改 `ignored` 并在备注写原因。
