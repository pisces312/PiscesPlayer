# 播放历史 / 听歌统计与历史参考实现完全对齐方案

> 状态：**已实施并在 emulator-5554 验证通过（待提交）**
> 更新：2026-09-10
> 分支：`pisces/port`（`pisces312/PixelPlayerOSS`，GPL-3.0-or-later fork）
> 参照：历史参考实现的 `PowerampBackupImporter` + `PlaybackStatsRepository`
>
> 用户决策：① 接受时长膨胀与时间线尖峰；②③ **播放历史条数不设上限**（不引入历史参考实现的 `MAX_HISTORY_EVENT_COUNT=20_000`），与「永久保留历史」一致。

---

## 0. 背景与问题

真机现象：首页「最近播放」能看到全部 Poweramp 导入记录，但听歌统计页即使切到「全部」也只显示一天。

根因（已定位）：

- 两侧**数据源相同**，都是 `filesDir/playback_history.json`。
- `PowerampBackupImporter` 写历史时按设计令 `durationMs = 0L`（Poweramp 只有 `played_times` + `played_at`，无逐次时长），
  压缩成 1 条 `playCount = N` 的带权重事件。
- `PlaybackStatsRepository.buildSummaryFromEvents()` 有一道
  `if (clippedDuration <= 0L) return@mapNotNull null` —— **零时长事件整条被丢弃**。
- 首页 `mapRecentlyPlayedSongs()` 只看 `timestamp`、不看时长，所以导入记录照常显示 → 两侧落差由此产生。

---

## 1. 权威口径：历史参考实现「方案 A v2（时长也 ×N）」

⚠️ **文档与代码不一致，以代码为准**：

- `docs/poweramp-import-feature-plan.md` §9 **D8** 结论是「**不做**时长估算，`durationMs` 恒 0（规则 N2）」。
- 但代码后续演进推翻了该决策，最终实现见 `.workbuddy/memory/2026-09-04.md` §「方案 A 已实施（时长也 ×N）」
  （commit `c4e76153`）。本方案对齐的是**代码现状**。

### 1.1 语义约定

| 项 | 口径 |
|---|---|
| 导入事件 | 每首歌最多 1 条，`timestamp = played_at`，`durationMs = 0`，`playCount = played_times` |
| 时长还原 | 统计时按 `N × 歌曲时长` **回溯**成 `[t − N×d, t]` |
| 一致性理由 | 与本地「播 N 次累加 N 次时长」口径一致；否则零时长事件会被过滤，统计页看不到导入数据 |

### 1.2 完整清单（历史参考实现现状）

1. `PlaybackEvent` / `PlaybackSegment` / `PlaybackSpan` 均带 `playCount`；`PlaybackEvent.weight` 对非法值兜底为 1。
2. `recordPlayback(songId, durationMs, timestamp, playCount = 1)`，`playCount.coerceAtLeast(1)`。
3. `sanitizeEvent()` 保留并归一化权重（旧 JSON 无该字段时退化为 1）。
4. **`expandImportedSpan()`**：零时长事件按 `歌曲时长 × weight` 展开，**且必须在 `resolveBounds` 之前执行**。
5. **导入去重保留权重最大**：`groupBy(去重键).values.map { it.maxBy { playCount } }`，而非 `distinctBy`（后者保留先出现的旧事件，重导入永远不生效）。
6. **聚合全加权**：`totalPlays` / 歌曲 / 艺人 / 专辑 / 流派 / 时间线桶（按区间落在桶内的**时长占比 × 权重**折算）/ 会话。
7. **D13 条数上限**：`MAX_HISTORY_EVENT_COUNT = 20_000`，替代原 730 天时间裁剪；`recordPlayback` 与 `importEventsFromBackup` 各调一次 `enforceHistoryCountCap()`。
8. 备份 DTO 带 `playCount`：导出 / 还原不丢次数。

---

## 2. OSS 现状差集

| # | 能力 | OSS 现状 | 动作 |
|---|---|---|---|
| 1 | `PlaybackEvent.playCount` / `weight` | ✅ 已有（`PlaybackStatsRepository.kt:54-65`） | — |
| 2 | `PlaybackSegment` / `PlaybackSpan` 带 `playCount` | ✅ 已有（`:111-128`） | — |
| 3 | `sanitizeEvent` 保留权重 | ✅ 已有（`:606`） | — |
| 4 | 加权聚合（含时间线桶按占比折算） | ✅ 已有（`:283`、`:921-945`） | — |
| 5 | 导入端已写 `playCount = played_times` | ✅ 已有（`PowerampBackupImporter.kt:172-186`） | — |
| 6 | **`expandImportedSpan()`** | ❌ **缺失**（`grep expandImportedSpan` 无结果） | **新增** |
| 7 | **导入去重保留权重最大** | ❌ 仍是 `distinctBy`（`:523-525`，保留第一条） | **改造** |
| 8 | **`recordPlayback(..., playCount)`** | ❌ 无该参数（`:175-179`） | **新增**（默认 1，调用方不变） |
| 9 | **条数上限** | ❌ 上次已删除 730 天裁剪，**当前完全无上限** | **不新增（用户选择无上限）** |
| 10 | **备份 DTO 带 `playCount`** | ❌ 三处均无 | **新增** |

> 第 5 项很关键：**OSS 导入端早已写入 `playCount` 权重**，数据本身是完整的。
> 补齐第 6 项后**无需重新导入 Poweramp 备份**。

---

## 3. 实施方案

### 3.1 `PlaybackStatsRepository.kt`

**A. 条数上限：不做（用户选择无上限）**

历史参考实现有 `MAX_HISTORY_EVENT_COUNT = 20_000` 与 `enforceHistoryCountCap()`。本实现选择无上限，
与「永久保留历史」一致。`recordPlayback` / `importEventsFromBackup` 均不做裁剪。

**B. 新增 `expandImportedSpan()`**（自写实现，不复制专有代码；属可搬的原创聚合算法，注明 `Original work, GPL-3.0-or-later`）

```kotlin
private fun expandImportedSpan(event: PlaybackEvent, songMap: Map<String, Song>): PlaybackEvent {
    val start = event.startMillis()
    val end = event.endMillis()
    if (end > start) return event                      // 本地真实播放：原样返回
    val songDuration = songMap[event.songId]?.duration?.takeIf { it > 0L } ?: return event
    val expanded = songDuration * event.weight
    return event.copy(
        durationMs = expanded,
        startTimestamp = (end - expanded).coerceAtLeast(0L),
        endTimestamp = end
    )
}
```

**C. `buildSummaryFromEvents()` 接线**——**必须先用 `expandedEvents` 算边界，再过滤**：

```kotlin
val songMap = songs.associateBy { it.id }
val expandedEvents = allEvents.map { expandImportedSpan(it, songMap) }
val (startBound, endBound) = period.resolveBounds(expandedEvents, nowMillis, zoneId)
val filteredEvents = expandedEvents.mapNotNull { ... }   // 后续逻辑不变
```

> 坑（历史参考实现实测踩过）：若先 `resolveBounds` 再展开，`StatsTimeRange.ALL` 的起点会取原始 `played_at`，
> 回溯出的 `[t − N×d, t]` 整条落在起点之前被裁掉，展开等于白做。

**D. `importEventsFromBackup()` 去重改为保留权重最大**

**E. `recordPlayback()` 加 `playCount: Int = 1`**（`coerceAtLeast(1)`）。
`ListeningStatsTracker` 调用点保持 3 参数不变。

### 3.2 备份 DTO 三处加 `playCount`

| 文件 | 改动 |
|---|---|
| `data/backup/AppDataBackupManager.kt` | `PlaybackHistoryBackupEntry` 加 `val playCount: Int = 1`；导出 / 还原两端透传 |
| `data/backup/model/BackupModels.kt` | 同名 DTO 加同字段 |
| `data/backup/module/PlaybackHistoryModuleHandler.kt` | `export()` 写入 `event.playCount`；`restore()` 还原到 `PlaybackEvent(playCount = ...)` |

> 旧备份无该字段 → Gson 走默认值 1，向后兼容。

### 3.3 单元测试（`PlaybackStatsRepositoryTest.kt`）

新增 4 个用例，与历史参考实现对齐：

1. **权重展开**：零时长 + `playCount = N` → ALL 范围下 `totalDuration == N × 歌曲时长`、`totalPlayCount == N`。
2. **展开先于边界**：构造 `played_at` 远早于 `N × d` 的事件，断言 ALL 范围仍计入（防回归）。
3. **权重兜底**：`playCount = 0` / 负数 → 退化为 1。
4. **重导入顶替**：先导入无权重事件，再导入同键带权重事件 → 保留权重更大的一条。

### 3.4 验证

- `./gradlew.bat :app:testDebugUnitTest --tests "*PlaybackStatsRepositoryTest*" --tests "*Backup*"`
- `./gradlew.bat :app:assembleDebug`
- 装模拟器：播放若干首 → 开统计页 → 断言数字与 `playback_history.json` 事件吻合。

---

## 4. 影响与风险

| 项 | 说明 |
|---|---|
| **总时长膨胀** | 「全部」范围总时长约放大 4.76 倍（历史参考实现实测口径）。这是**预期结果**，为与本地「播 N 次累加 N 次时长」口径一致而刻意为之，不是 bug。 |
| **时间线尖峰** | 单曲高播放次数（实测最高 76 次）会回溯出 5 小时级别的区间，在日/周视图形成尖峰。历史参考实现已接受此代价。 |
| **无界增长** | 补齐 D13 条数上限后才安全。当前 OSS 无上限，重度使用会导致 `playback_history.json` 持续膨胀，且每次 `recordPlayback` 全量读写 `AtomicFile`。 |
| **`songMap` 未命中** | 歌曲已从曲库移除、或 `Song.duration <= 0` → 事件仍被丢弃。无时长参照无法还原，属预期边界。 |
| **版权** | `expandImportedSpan` 属聚合 / 分桶算法层（可搬的原创算法），在 OSS 侧自写实现 + 注明 `Original work, GPL-3.0-or-later`；不复制专有 `PowerampBackupImporter` / UI / DI / strings。 |

---

## 5. 用户决策（已定）

1. ✅ **接受**「总时长膨胀 + 时间线尖峰」这一历史参考实现口径（自写实现，不复制专有代码）。
2. ✅ 条数上限：**不设上限**（不引入 `enforceHistoryCountCap`），与「永久保留历史」一致。
3. ✅ `MAX_PLAYBACK_HISTORY_LIMIT`（最近播放单次读取上限，当前 5,000）：**保持不动**。
   该上限仅约束「最近播放」列表一次反序列化/渲染的条数，与统计聚合无关；统计 `loadSummary` 走全量 `readEvents()`，不受其限。

---

## 6. 实施记录（2026-09-10）

改动文件：

| 文件 | 改动 |
|---|---|
| `PlaybackStatsRepository.kt` | 新增 `expandImportedSpan()`；`buildSummaryFromEvents()` 先展开再算边界；`recordPlayback()` 加 `playCount` 参数（默认 1）；抽出 `mergeImportedEvents()`（`groupBy + maxBy(playCount)`）替代 `distinctBy` |
| `AppDataBackupManager.kt` / `model/BackupModels.kt` / `module/PlaybackHistoryModuleHandler.kt` | 备份 DTO 三处加 `playCount`，导出/还原透传 |
| `PlaybackStatsRepositoryTest.kt` | 新增 4 个用例：权重展开 / 展开先于边界 / 去重保留高权重 / 不同歌不裁剪 |

验证：

- `:app:assembleDebug` BUILD SUCCESSFUL
- `:app:testDebugUnitTest --tests "*PlaybackStatsRepositoryTest*"` 全部通过
- emulator-5554 端到端：注入 3 条零时长带权重事件（playCount 30/15/5）→ All 范围显示
  **50 Plays / 1h 41m / 3 Songs / 3 Artists**，Top songs 首行 `×30 · 1h 29m`，与权重展开口径完全一致。

无需重新导入 Poweramp：导入端早已写入 `playCount`，补齐统计侧展开后旧数据立即生效。
