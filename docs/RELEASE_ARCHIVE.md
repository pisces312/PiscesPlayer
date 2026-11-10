# 旧 Release 归档（1.0.0 更名前）
PiscesPlayer 1.0.0 换名换包名后，以下 Release 已从 GitHub 删除；正文与资产清单在此留底，便于后续写总结或复盘。git tag 仍保留，需要时可从源码重建 APK。
删除日期：2026-11-10（版本 1.0.0 发布时）
> 注意：`dictionary-kuromoji-0.9.0`（日语词典资产，Pre-release）**不在删除范围**，应用内罗马音功能依赖它。

## 归档索引
| Tag | 名称 | 发布时间 | 资产 || --- | --- | --- | --- || `v0.4.3-pisces.1` | PixelPlayerOSS 0.4.3-pisces.1 | 2026-09-19 | `pixelplayeross-arm64-v8a-0.4.3-pisces.1-release.apk` || `v0.4.2-pisces.1` | PixelPlayerOSS 0.4.2-pisces.1 | 2026-09-16 | `pixelplayeross-arm64-v8a-0.4.2-pisces.1-release.apk`, `release-0.4.2-ai-thinking-details.jpg`, `release-0.4.2-ai-thinking.jpg` || `v0.4.1-pisces.1` | PixelPlayerOSS 0.4.1-pisces.1 | 2026-09-12 | `pixelplayeross-arm64-v8a-0.4.1-pisces.1-release.apk`, `screenshot-ai-playlist.jpg`, `screenshot-serendipity.jpg` || `v0.4.0-pisces.1` | PixelPlayerOSS 0.4.0-pisces.1 | 2026-09-11 | `pixelplayeross-arm64-v8a-0.4.0-pisces.1-release.apk`, `screenshot-ai-playlist.jpg`, `screenshot-listening-stats.jpg` |
---

## PixelPlayerOSS 0.4.3-pisces.1（`v0.4.3-pisces.1`）
发布：2026-09-19 · 预发布：否

资产：
- `pixelplayeross-arm64-v8a-0.4.3-pisces.1-release.apk` — 35.9 MB

### 正文（原文）

# PixelPlayerOSS 0.4.3-pisces.1

上一次发布之后落了 30 个提交，头条是**发行日期**：曲库里的歌现在会从文件自己的标签里读出真正的发行日期，年份列表可以按它排序，歌曲详情里也能看到；车机歌词那批收尾也一并进来了。

Thirty commits since the last release. The headline is **release dates**: songs now carry the date their own tags declare, the year list can be sorted by it, and the song sheet shows it. The car lyric title work is finished off here too.

> 本版本基于 `pisces312/PixelPlayerOSS` fork 构建，**签名密钥与上游 CI 不同**。
> 用个人密钥（`pisces312`）签名；若你此前装的是上游 / 官方构建，需先卸载再安装。
> 从 0.4.2 升级可直接覆盖安装，数据库会自动迁移到 v12。
>
> Built from the `pisces312/PixelPlayerOSS` fork and signed with a **different key than the upstream CI builds**.
> If you have an official/upstream build installed, uninstall it first.
> Upgrading from 0.4.2 installs straight over; the database migrates to v12 on its own.

---

## 中文

### 1. 发行日期：从文件标签里读出来

在此之前，"年份"这一列读的是**年份**标签，很多文件只写了年份，详情里也就只能显示个四位数字。现在多了一列真正的**发行日期**（数据库 v12），三种显示口径分得很清楚：

| 文件里有什么 | 显示什么 |
|---|---|
| 完整的 `yyyy-MM-dd` | 歌曲详情里出现 **发行日期** 一行，就是这个日期 |
| 只有年份（如 `2019`） | 不显示发行日期行——年份那一行已经有了，重复没意义 |
| 完全没有日期信息 | 不显示 |

**年份详情页**的排序里新增了 **Release Date (Oldest First)** 与 **Release Date (Newest First)**，这是这次唯一新增的排序项。

不需要手动全库重扫：**下一次同步结束时**会自动在后台把库里已有的歌读一遍（只读标签、不读封面），已经是这个版本的库不用管。云端（Navidrome / Jellyfin）曲目不动——不下载就拿不到它们的日期。

### 2. 音乐库歌单按来源筛选

曲库的歌单页现在能在 **全部 / AI 歌单 / 普通歌单** 之间筛，筛选结果会被记住，重启还在。两种"空"也分开了：没有 AI 歌单和没有普通歌单各自有说明，不再是同一句干巴巴的空列表。

### 3. 车机歌词：空白行不再把标题清掉

上一版把歌词行送进标题之后，实际用下来有个明显毛病：**标题会时不时跳回真实曲名**。

原因是 LRC 里的**裸时间戳空行**——那些行没有文字，但被无条件当成了"这里没有歌词"，于是把标题复位了。而绝大多数空白行只是**一句唱完、下一句还没开始的换气**，不是真的没内容。

现在的判据是：**只有这个空行到下一个有文字的行之间隔了 6 秒以上（或者后面再没有文字了），才算真正的间隙**，才把标题让回曲名。所以现在只有**前奏 / 间奏 / 尾奏**会短暂显示曲名，换气不会。用一首真实歌词实测：复位次数从 44 次降到 3 次。

同一批还修了两个：

- **分段驻留上限 4 秒**。长歌词行会被切成几段轮流显示，之前没给单段设上限，某一段可能一直挂在屏幕上；现在每段最多停 4 秒。
- **行中间拖动进度条不再卡住标题**。之前的调度唤醒条件漏了一个输入，导致在行中间拖滑杆后，标题要等到下一行边界才更新。

新增一个开关：**设置 → 播放 → 车载蓝牙 → 分段显示长行**（默认开）。如果你的车机会自己滚动长标题，关掉它，整行原样送过去。

### 4. 快捷播放不再污染收听统计

从"快捷播放"启动的歌本来就不属于曲库（只是临时试听），但它们之前照样进了**收听统计 / 最近播放 / AI 选歌的采样池**。现在这条路径统一在唯一入口处被拦掉，上面三处都不再被影响。音乐通知和它上面的控制按钮不受影响。

### 5. 其它修复

- **曲库排序弹窗可以滚动了**。之前选项多的时候底部几项点不到。
- **播放器不再显示上一首歌的信息**。切歌后的一小段时间里，界面读的是应用自己维护的一份拷贝，而不是播放引擎的真实状态；现在统一读引擎。
- **备份导出补回 AI 与「不期而遇」歌单**。之前导出的备份里没有它们，恢复时静默丢失。
- **播放器点击标题直接进「详情」页**，不再落在第一个标签上。
- **搜索历史行高收到 48dp**，符合最小触摸目标。
- 评级控件改为**浮层胶囊**展开，不再撑动周围那一行。

---

## English

### 1. Release dates, read from the file's own tags

Until now the Year column read the year tag, and plenty of files only carry a year, so the sheet could do no better than four digits. There is now a real release date (database v12) with three distinct states:

| What the file carries | What the app shows |
|---|---|
| A full `yyyy-MM-dd` | A **Release date** row in the song sheet, showing that date |
| A year only (e.g. `2019`) | No release date row — the Year row already carries it |
| Nothing at all | No release date row |

The year screen's sort options gained **Release Date (Oldest First)** and **Release Date (Newest First)**; they are the only sort options added by this release.

No manual rescan is needed: at the end of the **next sync** every song already in the library is read once in the background (tags only, no artwork). Cloud catalogue entries (Navidrome, Jellyfin) are left alone — their date cannot be read without downloading them.

### 2. A source filter for playlists

The library's playlist tab can now list **all / AI playlists / regular playlists**, and the choice survives a restart. The two empty states are told apart as well, so each says which kind of playlist is missing instead of showing one blank list.

### 3. Car lyric title: blank lines no longer clear it

Shipping the lyric line as the media title exposed a clear fault in real use: **the title kept falling back to the real track name**.

The cause was an untimed blank line in the LRC file. Those lines carry a timestamp but no text, and they cleared the override unconditionally — yet almost all of them are just one line ending before the next begins, not a stretch with nothing to show.

The rule now is: a blank marker only counts as a real gap when **the next line with text is at least 6 seconds later, or there is no further text at all**. Only then does the title go back to the song name, so now it is intro, interlude and outro that briefly show the track title, and a breath does not. Measured against a real lyric file, fallbacks dropped from 44 to 3.

Two related fixes came with it:

- **A segment now stays on screen at most 4 seconds.** Long lines are cut into segments shown in turn, and nothing used to bound how long one segment lasts.
- **Dragging the position slider inside a line no longer wedges the title.** The scheduler's wake-up key was missing one of the inputs that decide when the next cue is due, so the title waited for the following line.

New setting under **Settings > Playback > Bluetooth car > Split long lines** (on by default). Turn it off if your head unit scrolls a long title by itself.

### 4. Quick play no longer counts as listening

Songs started from Quick play deliberately stay outside the library, but they were still reaching **listening statistics, recently played, and the pool the AI samples from**. That path is now blocked at its single choke point, and all three are unaffected. The media notification and its controls are unchanged.

### 5. Other fixes

- **The library sort sheet scrolls**, so its last options are reachable again.
- **The player no longer shows the previous song** right after a track change: the displayed state was derived from the app's own copy of the playback position instead of the engine. It is now read from the engine.
- **Backup export includes AI and Serendipity playlists** again; they were missing from the archive, and restoring silently lost them.
- **Tapping the title in the full player opens the Details tab** rather than the first tab.
- **Search history rows are 48 dp tall**, matching the minimum touch target.
- The **rating control opens as an overlay pill** instead of expanding in place.

---

## 安装说明 / Install notes

| | |
|---|---|
| 版本 / Version | `0.4.3-pisces.1` |
| 版本码 / Version code | `400301` |
| ABI | `arm64-v8a` |
| minSdk / targetSdk | 30 / 37 |
| 签名 / Signing | 个人密钥（alias `pisces312`）/ personal key |
| SHA-256 | `d6f5f3ff641a26cab706fde63d7bdae1e4a942eb0c304a9e203ef703f4fba355` |

**签名不同，无法覆盖安装上游构建。** 若设备上已有官方构建，请先卸载（会清除应用数据，请先用内置备份导出）。

**The signature differs from upstream builds, so this APK cannot install over them.**
Uninstall the existing official build first — that wipes app data, so export a backup from inside the app beforehand.

## 许可证 / License

GPL-3.0-or-later。本 fork 基于 [PixelPlayerHQ/PixelPlayerOSS](https://github.com/PixelPlayerHQ/PixelPlayerOSS) 构建。

Built on [PixelPlayerHQ/PixelPlayerOSS](https://github.com/PixelPlayerHQ/PixelPlayerOSS).

---

## PixelPlayerOSS 0.4.2-pisces.1（`v0.4.2-pisces.1`）
发布：2026-09-16 · 预发布：否

资产：
- `pixelplayeross-arm64-v8a-0.4.2-pisces.1-release.apk` — 35.9 MB
- `release-0.4.2-ai-thinking-details.jpg` — 0.2 MB
- `release-0.4.2-ai-thinking.jpg` — 0.2 MB

### 正文（原文）

# PixelPlayerOSS 0.4.2-pisces.1

上一次发布之后落了 45 个提交，两个头条：蓝牙车机那行标题现在可以跟着歌词走；AI 回答改为流式，模型边想你就能边看，而且这份思考过程会随歌单存下来，之后随时能翻回去。

Forty-five commits since the last release, with two headlines: the media title can now follow the lyrics on a Bluetooth car head unit, and the AI answer is streamed, so the thought process is visible while it thinks and is kept with the mix for later.

> 本版本基于 `pisces312/PixelPlayerOSS` fork 构建，**签名密钥与上游 CI 不同**。
> 用个人密钥（`pisces312`）签名；若你此前装的是上游/官方构建，需先卸载再安装。
> 从 0.4.0 / 0.4.1 升级可直接覆盖安装，数据库会自动迁移到 v11。
>
> Built from the `pisces312/PixelPlayerOSS` fork and signed with a **different key than the upstream CI builds**.
> If you have an official/upstream build installed, uninstall it first.
> Upgrading from 0.4.0 or 0.4.1 installs straight over; the database migrates to v11 on its own.

---

## 中文

### 1. 车载歌词标题：车机那行标题跟着歌词滚

蓝牙车机只能拿到 Title / Artist / Album / Genre 四个字段，AVRCP 协议里**根本没有歌词字段**——所以所有"车机歌词"的实现（汽水音乐也是这么做的）都是把**当前歌词行塞进 Title**。这个功能就是同一思路的独立实现。

位置：**设置 → 播放 → 车载蓝牙 → 标题显示歌词**，**默认关闭**（这个是每个已配对设备都看得见的字段，得你主动开）。

它只在下面三条**同时**满足时才改写标题：

| 条件 | 为什么 |
|---|---|
| 开关打开 | 默认关 |
| 当前输出是蓝牙 A2DP 设备 | 覆盖值对**所有** MediaSession 消费者可见。没有这条，插着有线耳机看手机时，标题位置也会变成歌词 |
| 当前歌曲有同步歌词 | 没有时间轴就定位不到当前行 |

任何一条不满足就保持真实曲名。所以通知栏、锁屏、有线耳机、手机外放全都不受影响。**车机是否支持不用管**：不支持元数据的车机根本不会来要这个字段，改了也没影响。

顺带一提：验证不必等车机——任何蓝牙音频设备（耳机、音箱）走同一条 A2DP 路径，标题同样会被改写。

### 2. 歌词提前量：0 – 1 秒

就在那个开关正下方。蓝牙链路加上车机重绘是有延迟的，"歌词总是慢半拍"就把这个值调大。

- 0 – 1 秒，每 0.1 秒一档，默认 0.5 秒；
- 开关关闭时**置灰**（不隐藏，方便你看到有这个调节项）；
- 拖动后立刻按新值重算当前行，不用等下一行；
- 实现上是把这句歌词**提前 J 毫秒发布**，相邻两行之间的间隔不变，所以误差不会累计。

### 3. 长行拆分：车机标题栏一次只装得下十个字左右

一行歌词太长，车机那行标题会显示不全。所以超过约十个字的行会**按时间等分成几段**，一段一段发下去，跟着唱走，而不是把尾巴截掉。

调度也一并改了：原来是固定每 500 毫秒轮询一次（开关关着也在空转，而且无法判断内容有没有变），现在**睡到下一句歌词的边界**再醒，醒来后重新按当前播放位置计算，所以不会累积误差；暂停和播完之后**不排任何定时器**。关掉开关约 0.1 秒恢复真实标题，蓝牙断开约 1.9 秒恢复。

只改 metadata，不动播放队列，所以**不会触发车机的换曲事件**（AVRCP `Track Changed`），进度条不会被重置。

### 4. AI 思考过程：边想边看

先说修的那个 bug：开着「思考」时，AI 混音和不期而遇**必现**"网络不可达"。

根因不在网络——AI 客户端是从全局 HTTP 客户端建的，只调大了总时长，**读超时仍是 8 秒**；而模型思考期间一个字节都不发，8 秒静默被判成连接超时，又被归类成"网络不可达"。请求其实一直好好地到了服务商那边。

现在请求改为**流式**（SSE）：思考 token 边生成边到达，连接始终有数据。生成面板里能看到状态行和一张可展开的思考卡片——自动跟到最新一句，等回答一开始自动收起（收起了也能再点开）。

### 5. 思考过程留档：保存后还能翻回去

- 结果页（歌单列表那一屏）现在也能看思考过程，并补上了这一屏此前一直没有的**垂直滚动条**；思考卡片默认收起，点标题行展开。
- 保存混音时，思考过程一起写进歌单（数据库升级到 **v11**）。
- 曲库 → 点开这个歌单 → 点标题下方的 AI 描述横幅 → 弹窗里就能看到**完整提示词（可选中复制）、本次采样用的是哪一档、原始生成的歌曲列表**（标注已移除 / 已不可用），以及**思考过程**。

思考内容只落在歌单这一列，不写请求日志、不写缓存。

### 6. AI 歌单的其它改进

- **不期而遇有了自己的采样设置**：默认**随机取 200 首**（不是"最常听"），面板里能直接改——它的目的就是撞到你不怎么放的歌。
- **长按「不期而遇」**直接用默认值生成一张，一个手势省掉整屏操作。
- **默认生成数量 25 → 15**，两个入口（描述生成 / 不期而遇）共享。
- 面板**打开即全展开**，生成按钮不用再上拉；输入阶段可滚动，软键盘弹出时也能滚到按钮。
- 生成中关掉面板或点「重新生成」，**会真的断开请求**（省 token），也不会再往请求日志里记一条失败。

### 7. 其它新增

- **曲库新增「流派」tab**，可按名称正序 / 倒序排。流派此前只藏在搜索页的空闲态里，现在有正经位置。
- **搜索页空闲态显示搜索历史**，点一条即可重新搜；清空需要确认。
- **双击当前所在的底部导航项**回到顶部（首页、曲库各子列表、统计）。搜索页保持"聚焦搜索框"的行为。
- 播放器控制行的五星评分可以**点箭头收起**了。
- **中文界面补全**：此前有几个位置在中文设备上回退成英文，包括「导入」和「日志」两个**完全缺失**的翻译文件。

### 8. 改进

- **听歌统计改为按需计算 + 缓存**。以前一进统计页就重写并 fsync 整个 `playback_history.json`、再跑三次全量聚合——这就是进统计页时**蓝牙音频卡顿**的原因。现在按日历周期缓存，重进复用；落盘只剩"结束一次播放"这一个入口。
- 迷你播放器手势：去掉跳过死区、下滑关闭阈值改为屏宽 30%、**纵向为主的滑动不再切歌**；歌词覆盖层只认水平拖动，纵向滚动交还给列表。

### 9. 修复

- **搜索永久失效**：进一次 AI 混音页再返回，搜索从此永远返回空列表，只能重启应用。原因是该路由里创建了第二个 `PlayerViewModel`，它销毁时把全局的搜索状态一起拆掉了。这个坑已加回归测试。
- 删除 AI 播放列表**崩溃**（确认弹窗的标题把复数资源 id 喂给了普通字符串查询，必然抛异常）。
- 删除播放列表不再留下**孤立歌曲行**（那张表没有外键，不会自动级联）；两行现在一个事务删完，歌曲文件本身不动。
- 删除**非当前播放**的歌曲不再把全屏播放器关掉；多选删除的回调一定会走完；批量删除播放列表会二次确认；只撤销通知权限不再被强制重走一遍设置向导。

---

## English

### 1. Car lyric title: the head unit's title line follows the lyrics

A Bluetooth head unit only gets Title, Artist, Album and Genre, and AVRCP has no lyrics field at all, so every app that scrolls lyrics there is putting the current lyric line into Title. This is an independent implementation of the same idea.

Settings > Playback > Bluetooth car > Show lyrics as media title. Off by default, since the title is a field every paired device can see.

The title is rewritten only while all three hold: the switch is on, the current output is a Bluetooth A2DP device, and the song has synced lyrics. Otherwise the real track title stays. That is what keeps the override away from the notification, the lock screen, wired headphones and the phone's own speakers. Whether your head unit supports it needs no checking: one that does not read metadata never asks for the field.

You do not need a car to test it either. Any Bluetooth audio device shares the same A2DP path, and its title gets rewritten too.

### 2. Title lead: 0 to 1 second

Directly under that switch. The Bluetooth link and the head unit's redraw both add delay, so if the lyrics are always a beat behind, raise it.

0 to 1 s in 0.1 s steps, 0.5 s by default; greyed out while the switch is off; a change re-resolves the current line straight away. Each line is published that much early, so the gap between lines is unchanged and the error never accumulates.

### 3. Long lines are split, not truncated

A line wider than the head unit's roughly ten character title field is split into evenly timed slices and published one after another, in step with the vocals, rather than losing its tail.

Scheduling changed with it: instead of polling every 500 ms, the feature sleeps until the next lyric boundary and recomputes from the live position when it wakes, so nothing accumulates; a paused or finished track arms no timer at all. Turning the switch off restores the real title in about 0.1 s, losing the Bluetooth route in about 1.9 s.

Only the metadata is rewritten, never the queue, so the head unit does not see a track change and its progress bar is not reset.

### 4. The thought process, while the model is thinking

First the bug this fixes. With thinking enabled, AI generation always failed with network unreachable. The cause was not the network: the AI client was built from the app-wide HTTP client and only had its call timeout raised, so the 8 second read timeout survived, and a model that sends nothing while thinking was cut off by it and classified as a network failure. The request had been reaching the provider all along.

Requests are streamed now, so thinking tokens arrive as they are produced and the connection never goes quiet. The generating panel shows a status line and a collapsible card that follows the stream to the latest sentence and folds itself once the answer starts.

### 5. The thought process is kept

- The result view shows it too, and gained the vertical scrollbar that view never had. The card starts collapsed there; one tap on its header expands it.
- Saving a mix stores the thought process with the playlist (schema v11).
- Library > open the mix > tap the AI prompt banner under the title: the dialog shows the complete prompt, selectable for copying, the sampling that generation used, the originally generated songs with any since removed or unavailable ones marked, and the thought process.

The text only lands in that playlist column. It is never written to the request log or the response cache.

### 6. Other AI work

- Serendipity has its own sampling settings, defaulting to a random slice of 200 songs rather than your most played, editable from the sheet.
- Long-pressing Serendipity generates a mix from the defaults in one gesture.
- The default mix length is 15 instead of 25, shared by both entries.
- The sheet opens fully expanded, and its input phase scrolls so the generate button is reachable with the keyboard up.
- Closing the sheet mid-generation, or tapping regenerate, now really cancels the request instead of spending the tokens, and no failed row is logged.

### 7. Other additions

- A Genres tab in the library with name sorting; genres used to be reachable only from the search screen's idle state.
- Search history on the search screen; tapping an entry searches again, and clearing asks first.
- Double-tapping the selected bottom navigation entry scrolls Home, the library sublists and Stats to the top. Search keeps focusing its search bar.
- The five-star row in the player collapses with its own chevron.
- Simplified Chinese is complete, including the import and logs resources, which were missing entirely.

### 8. Improvements

- Listening stats are computed on demand and cached per calendar period. Entering the page used to rewrite and fsync the whole playback history and run three full aggregations, which is what made audio stutter over Bluetooth while opening it. Finalizing a session is now the only place the file is written.
- Mini-player gestures: no skip dead zone, dismissing the playlist takes 30% of the screen width, and a vertically dominant drag no longer skips. The lyrics overlay claims horizontal drags only, so vertical scrolling goes back to the list.

### 9. Fixed

- Search could go permanently dead: visiting the AI mix screen once left every search returning an empty list until the app restarted, because a second PlayerViewModel created inside that route tore down the app-wide search state when it was destroyed. A regression test covers it.
- Deleting an AI playlist crashed its confirmation dialog, which passed a plurals id to a plain string lookup and was guaranteed to throw.
- Deleting a playlist left orphaned song rows behind, since that table has no foreign key to cascade them. Both rows are now removed in one transaction and the songs themselves are untouched.
- Deleting a song that is not the current one no longer closes the full player; a multi-select delete always completes its callback; batch playlist deletion asks for confirmation; and revoking only the notification permission no longer forces the setup wizard again.

---

## 已知限制 / Known limitations

车机歌词标题：

- **需要同步歌词**。只有整段纯文本歌词的歌不会启用（没有时间轴就定位不到当前行）。歌词来源是本机标签、本地 `.lrc` / `.ttml` 与 LRCLIB。
- **云端曲目基本没有歌词**：Navidrome 有歌词接口但尚未接进来，Jellyfin 没有该接口，所以云端歌会走"保持原标题"的降级路径。
- **歌曲播完后标题停在最后一行歌词**，不会自动恢复成曲名（下一次播放事件才重算）。
- **车机端刷新时机只能实测**：手机发出更新 → 车机重绘这段延迟是「歌词提前量」要补偿的对象，耳朵 + 车机是唯一判据。默认 0.5 秒是链路量级的估值，真机觉得不合适就直接调滑杆。
- 约十个字的切分长度也是**实机观测值**（车机 title 字段宽度），换车可能需要复核。

For the car lyric title:

- Synced lyrics are required. A song with only plain, un-timed lyrics cannot drive it. Lyrics come from local tags, local `.lrc` / `.ttml` files and LRCLIB.
- Cloud tracks rarely have any: Navidrome exposes lyrics but is not wired into the lyrics repository yet, and Jellyfin has no such API, so those songs keep the real title.
- After a song finishes, the title stays on the last lyric line instead of reverting to the track name; the next playback event recomputes it.
- How late the head unit redraws can only be measured on real hardware, which is what the title lead compensates. 0.5 s is an estimate of the link's magnitude; move the slider if it is off for your car.
- The roughly ten character split width is also an observation from one head unit and may need revisiting on another.

---

## 截图 / Screenshots

生成中：状态行下面那张思考卡片正在跟着模型往下滚。

While generating: the card under the status line follows the model as it writes.

![AI thinking while generating](https://raw.githubusercontent.com/pisces312/PixelPlayerOSS/main/docs/images/release-0.4.2-ai-thinking.jpg)

保存之后：曲库 → 打开这个歌单 → 点 AI 描述横幅，弹窗里是完整提示词、采样档位、原始生成列表和思考过程。

After saving: Library > open the mix > tap the AI prompt banner. The dialog holds the full prompt, the sampling used, the original song list and the thought process.

![The thought process in the prompt details dialog](https://raw.githubusercontent.com/pisces312/PixelPlayerOSS/main/docs/images/release-0.4.2-ai-thinking-details.jpg)

车机侧的标题歌词要真车实拍，截图后续补上。
The car lyric title needs a photo taken at a real head unit; that screenshot will be added later.

---

## 安装说明 / Install notes

| | |
|---|---|
| 版本 / Version | `0.4.2-pisces.1` |
| 版本码 / Version code | `400201` |
| ABI | `arm64-v8a` |
| minSdk / targetSdk | 30 / 37 |
| 签名 / Signing | 个人密钥（alias `pisces312`）/ personal key |
| SHA-256 | `923bc12c62f6268fd940b51acb0a34eaece4a04154530c521b22fd8ffaf45f3e` |

**签名不同，无法覆盖安装上游构建。** 若设备上已有官方构建，请先卸载（会清除应用数据，请先用内置备份导出）。

**The signature differs from upstream builds, so this APK cannot install over them.**
Uninstall the existing official build first — that wipes app data, so export a backup from inside the app beforehand.

## 许可证 / License

GPL-3.0-or-later。本 fork 基于 [PixelPlayerHQ/PixelPlayerOSS](https://github.com/PixelPlayerHQ/PixelPlayerOSS) 构建。

Built on [PixelPlayerHQ/PixelPlayerOSS](https://github.com/PixelPlayerHQ/PixelPlayerOSS).

---

## PixelPlayerOSS 0.4.1-pisces.1（`v0.4.1-pisces.1`）
发布：2026-09-12 · 预发布：否

资产：
- `pixelplayeross-arm64-v8a-0.4.1-pisces.1-release.apk` — 35.8 MB
- `screenshot-ai-playlist.jpg` — 0.1 MB
- `screenshot-serendipity.jpg` — 0.1 MB

### 正文（原文）

# PixelPlayerOSS 0.4.1-pisces.1

上一次发布之后落了 16 个提交：AI 歌单多了第二个入口「不期而遇」，歌单开始记住自己的 AI 描述，天气城市改为在设备上离线反查。
Sixteen commits since the last release: the AI playlist gains a second entry point, Serendipity; a mix remembers the prompt it came from; and the weather city now resolves on the device.

> 本版本基于 `pisces312/PixelPlayerOSS` fork 构建，**签名密钥与上游 CI 不同**。
> 用个人密钥（`pisces312`）签名；若你此前装的是上游/官方构建，需先卸载再安装。
>
> Built from the `pisces312/PixelPlayerOSS` fork and signed with a **different key than the upstream CI builds**.
> If you have an official/upstream build installed, uninstall it first.

---

## 中文

### 1. 不期而遇（Serendipity）：零输入，按此刻生成

首页 AI 横幅上多了一颗魔法棒胶囊，就在「说一句」左侧。点开后，弹窗直接给你三样东西：

- **此刻**：星期与时段（`星期日 01:12 · 深夜`）、城市天气（`晴 24°C`，同时显示 `宝山区 · 上海` 这样的区级名）、今天步数。读不到的信号**直接不出现**，不会拿假数据凑一句。
- **这张歌单的依据**：一句在设备上本地合成的话，可以自己改。例如
  `It is Sunday late night, 01:12. Clear skies and 24°C in Baoshan, Shanghai. Around 0 steps so far: a quiet day. Choose tracks that belong to right now.`
- **两种换词方式**：「换一句」只重排本地句式，不重新采集信号、不联网；「让 AI 换个说法」是可选的一次小请求，供应商不可达时自动保留本地措辞。

默认路径不花 AI 去写这句词：文案本地合成，只有最后生成歌单才发请求。

![不期而遇](https://raw.githubusercontent.com/pisces312/PixelPlayerOSS/main/docs/images/release-0.4.1-serendipity.jpg)

### 2. 说一句：AI 歌单生成入口（原 Start）

- 按钮改名为「说一句」并与「不期而遇」并排放在横幅第二行，标题不再被挤到折行。
- 输入框、灵感标签（深夜码字 / 通勤提神 / 健身训练）、长度 15 / 25 / 40、高级里的曲库切片与「发给模型的歌曲数」——这些界面此前在中文设备上会回退成英文，本次全部补齐。
- 结果可以播放、逐首移除或仅保存；歌单按你点的灵感词命名，「最近生成」改为紧凑卡片并新增一个列出全部生成歌单的页面。

![AI 歌单](https://raw.githubusercontent.com/pisces312/PixelPlayerOSS/main/docs/images/release-0.4.1-ai-playlist.jpg)

### 3. 天气城市：完全离线反查

设置 → AI → 天气来源，三选一：

| 选项 | 行为 |
|---|---|
| 设备定位 | 读取大致位置（首次使用时申请权限），适合经常移动 |
| 指定城市 | 不申请定位权限、不读取手机位置，从内置城市库里搜索 |
| 关闭 | 不使用天气，只用时间生成 |

- 「经纬度 → 城市或区县」的换算**完全离线**，内置约 8,900 个地点（全国省市与区县 + 全球 10 万人口以上城市）。
- 只有最后查天气那一步联网，用的是 Open-Meteo，不需要 API key。
- 优先给到区县精度（如「宝山区 · 上海」）；偏远地区回退到最近的城市。

### 4. 其它新增

- AI 歌单保存生成它的那句描述，在歌单页标题下方显示（数据库升级到 v9；手动建的歌单不受影响）。
- 播放器控制行新增评分段：点一下星标就地展开五星评分。
- 歌曲详情新增年份行，可直接跳到该年份的详情页。
- 收听统计的热歌榜可以整队播放。
- 设置新增「统计」分区：「不限榜单数量」，歌手 / 专辑 / 歌曲榜不再停在 100 条。
- 全屏播放器点标题即可打开歌曲详情。

### 5. 修复

- 列表底部不再被迷你播放条遮挡：重复歌曲页、年份详情、以及三个统计榜单页都补上了底部留白。

---

## English

### 1. Serendipity: a zero-input mix drawn from right now

A magic-wand pill now sits on the AI banner, left of Describe. Tapping it opens a sheet with three things:

- **Right now**: weekday and time of day (`Sunday 01:12 · late night`), city weather (`Clear 24°C`, with the district name such as `Baoshan, Shanghai`), and today's step count. A signal that cannot be read is simply absent instead of being faked.
- **What this mix is for**: one sentence composed on the device, editable before generating, for example
  `It is Sunday late night, 01:12. Clear skies and 24°C in Baoshan, Shanghai. Around 0 steps so far: a quiet day. Choose tracks that belong to right now.`
- **Two ways to reword it**: Another line reshuffles the local sentence without re-reading any signal, and Let the AI rephrase it is an optional single small request that falls back to the local wording when the provider is unreachable.

The default path spends no AI call on that sentence: it is composed locally, and only the playlist request itself goes out.

![Serendipity](https://raw.githubusercontent.com/pisces312/PixelPlayerOSS/main/docs/images/release-0.4.1-serendipity.jpg)

### 2. Describe: the AI playlist entry (was Start)

- The pill is renamed Describe and moved next to Serendipity on the banner's second line, so the banner title no longer has to wrap.
- The prompt field, the idea chips (late-night coding / commute pick-me-up / workout), the 15 / 25 / 40 length row and the advanced sampling controls used to fall back to English on Simplified Chinese devices; they are all translated now.
- Results can be played, trimmed song by song or saved only; a mix is named after the idea word that was tapped, the recent-mixes row became a compact card row, and a new screen lists every generated mix.

![AI playlist](https://raw.githubusercontent.com/pisces312/PixelPlayerOSS/main/docs/images/release-0.4.1-ai-playlist.jpg)

### 3. Weather city: fully offline lookup

Settings → AI → Weather source offers three options:

| Option | Behaviour |
|---|---|
| Device location | Reads the approximate position (permission requested on first use); follows you when you travel |
| Chosen city | Never requests the location permission and never reads the phone's position; search the bundled city list |
| Off | No weather; the mix is built from the time of day alone |

- Coordinates to city or district resolve **entirely offline** against roughly 8,900 bundled places (every Chinese province, city and district, plus world cities above 100,000 residents).
- Only the forecast call goes online, against Open-Meteo, which needs no API key.
- District precision is preferred (`Baoshan, Shanghai`); remote areas fall back to the nearest city.

### 4. Other additions

- An AI playlist remembers the prompt it was generated from and shows it under the title on the playlist screen (schema v9; manually built playlists are unaffected).
- A rating segment in the player toggle row: tap the star to expand the five-star row and rate in place.
- A Year row in the song info sheet that opens that year's detail screen.
- The whole top-songs list can be played as a queue from listening stats.
- A Statistics section in settings with No limit on stats rankings, so song, artist and album rankings no longer stop at 100.
- The song info sheet can also be opened by tapping the title in the full player.

### 5. Fixed

- Lists no longer end underneath the mini player: the missing bottom space was added on the duplicate-songs screen, the year detail song list and the three stats ranking screens.

---

> 两张截图同时作为附件提供下载，见下方 Assets。
> Both screenshots are also attached to this release for download — see Assets below.

## 安装说明 / Install notes

| | |
|---|---|
| 版本 / Version | `0.4.1-pisces.1` |
| 版本码 / Version code | `400101` |
| ABI | `arm64-v8a` |
| minSdk / targetSdk | 30 / 37 |
| 签名 / Signing | 个人密钥（alias `pisces312`）/ personal key |
| SHA-256 | `29069fd0558173ae11fac747eeef7293bfc63df48a8dda5525665962c9e970f2` |

**签名不同，无法覆盖安装上游构建。** 若设备上已有官方构建，请先卸载（会清除应用数据，请先用内置备份导出）。

**The signature differs from upstream builds, so this APK cannot install over them.**
Uninstall the existing official build first — that wipes app data, so export a backup from inside the app beforehand.

## 许可证 / License

GPL-3.0-or-later。本 fork 基于 [PixelPlayerHQ/PixelPlayerOSS](https://github.com/PixelPlayerHQ/PixelPlayerOSS) 构建。
内置城市数据：中国行政区划来自 [AreaCity](https://github.com/xiangyuecn/AreaCity-JsSpider-StatsGov)（MIT），境外城市来自 GeoNames（CC-BY 4.0）。

Built on [PixelPlayerHQ/PixelPlayerOSS](https://github.com/PixelPlayerHQ/PixelPlayerOSS).
Bundled place data: Chinese administrative divisions from [AreaCity](https://github.com/xiangyuecn/AreaCity-JsSpider-StatsGov) (MIT); non-Chinese places from GeoNames (CC-BY 4.0).

---

## PixelPlayerOSS 0.4.0-pisces.1（`v0.4.0-pisces.1`）
发布：2026-09-11 · 预发布：否

资产：
- `pixelplayeross-arm64-v8a-0.4.0-pisces.1-release.apk` — 35.6 MB
- `screenshot-ai-playlist.jpg` — 0.1 MB
- `screenshot-listening-stats.jpg` — 0.2 MB

### 正文（原文）

# PixelPlayerOSS 0.4.0-pisces.1

自用 fork 的首个稳定版，整合了 `pisces/port` 上 48 个提交的全部新功能，外加此后落在 `main` 上的应用内更新日志与备份恢复修复。
The first stable release of this personal fork, rolling up all 48 commits from `pisces/port`, plus the in-app changelog and backup-restore fixes that landed on `main` afterwards.

> 本版本基于 `pisces312/PixelPlayerOSS` fork 构建，**签名密钥与上游 CI 不同**。
> 用个人密钥（`pisces312`）签名；若你此前装的是上游/官方构建，需先卸载再安装。
>
> Built from the `pisces312/PixelPlayerOSS` fork and signed with a **different key than the upstream CI builds**.
> If you have an official/upstream build installed, uninstall it first.

---

## 中文

### 1. AI 歌单生成

用自然语言描述心情或场景，从自己的曲库里挑歌生成歌单。

- **任意 OpenAI 兼容接口**：设置里新增独立的 **AI** 分类，内置 **Xiaomi MiMo**、**Volcano Engine (Ark)** 与 **Custom Provider**（自填 Base URL）三种，可配置 API Key 与模型 ID（支持一键拉取模型列表并挑选）。密钥按供应商分别保存；小米 MiMo 的 Token Plan 密钥（`tp-`）与按量付费密钥（`sk-`）会自动解析到各自的端点。
- **首页 AI Mix 入口**：顶部 AI Playlist 卡片直接唤起底部弹窗，输入框可写"深夜码字""通勤提神"这类描述，也可点选内置灵感标签。
- **库切片可选**：把这批歌送进模型前，可选择 **Most played**（结果稳定，重复提问只花一次请求）或 **Random**（每次全新洗牌，能挖到你从不播放的歌），数量可选 15 / 25 / 40 首。
- **结果可编辑**：生成后能直接播放、逐首移除、或仅保存为歌单。
- **命名与记录**：歌单按 `yyyyMMdd-HHmm` 命名；首页新增"最近生成"横向列表。
- **诊断能力**：设置里有连接测试、缓存清理、请求日志（完整请求/响应 + token 用量，可复制/分享）。落盘前会对 URL 里的 `key` / `token` / `secret` 参数做脱敏，请求头中的密钥不写入日志。

### 2. 第三方播放器数据导入（Poweramp）

把 Poweramp 的 `.poweramp-backup` 备份迁移过来。

- **来源选择页**：第三方导入独立入口，后续可扩展更多来源。
- **备份预览**：解析后先展示歌曲数、歌单数、匹配数、匹配率，以及不匹配的示例，让你决定是否继续。
- **歌曲匹配**：按标题/艺人/专辑/时长与文件路径双重匹配，路径归一化可处理外置 SD 卡的卷序列号形式（`9C33-6BBD/...`）与多种存储根。
- **分项开关**：歌单、播放历史、播放次数、收藏与评分可分别勾选导入；评分可设阈值自动标记为收藏。
- **两种模式**：Merge（推荐，评分与收藏只升不降）/ Replace（用备份覆盖播放历史与播放次数）。
- **结果统计**：导入完成后列出匹配歌曲、未匹配、新建/合并歌单数、历史事件数、播放次数、收藏与评分写入数、跳过的空歌单。

### 3. 收听统计重做

统计页从首页卡片升级为**底部一级 Tab**，整体重新设计。

- **最近播放**：按天分组、重复歌曲合并计数，顶部副标题说明"你最近在听什么"。
- **区间浏览**：今日 / 本周 / 本月 / 本年 / 全部五个区间，支持"上一周期""下一周期"和"回到当前周期"。
- **概览卡**：收听时长、播放次数、歌曲数、艺人数四项指标。
- **完整榜单**：Top 歌曲 / 艺人 / 专辑各有独立详情页，可"显示全部 N 首/位/张"，支持按**播放次数**或**时长**排序。
- **数据准确性**：播放事件带播放次数权重；打开统计页时会把进行中的播放会话落盘，正在播放的歌立刻出现在统计里；从 Poweramp 导入的零时长事件按播放次数展开，历史播放不再丢失。

### 4. 五星评分改为写入数据库

- 设置信息页新增可点击的五星评分行。
- 评分改为存数据库字段，不再写音频文件内嵌标签（云端曲目也能评分）。
- 取消收藏时保留评分；批量编辑元数据不再误清其他标签。

### 5. 年份智能分类

曲库新增 **Years** 标签页，按发行年份分桶，点进年份详情可播放 / 随机 / 排序。

### 6. 删除保护模式

新增开关，**默认关闭"允许删除歌曲"**——关掉后歌曲永远无法从设备删除，误触不会毁掉文件。

### 7. 应用内诊断日志

- 可调日志级别（Verbose → Error），release 版默认 WARN。
- 一键导出并分享诊断日志（内存环形缓冲 + 滚动文件 + logcat 兜底）。
- 崩溃报告自动附带诊断日志；超大日志会被截断保护，避免复制/分享卡死。

### 8. 备份与恢复增强

- 备份内容包含 AI 供应商配置。
- 修复收藏备份因 Gson 字段重名导致失败的问题。
- 修复 release 版歌单恢复崩溃：R8 混淆规则未覆盖 `data.backup.restore.PendingSongRef`，字段被重命名后备份载荷里的字符串全部读成 null。无法识别的载荷现在降级为无元数据，仍可命中直接 ID 匹配，而不是抛异常。
- 歌单恢复新增独立的歌曲匹配解析器。
- 导出/恢复播放历史时保留播放次数。

### 9. 依赖与构建

- **Media3 升级到 1.11.0**，并修复 `onConnect` 命令集回归（升级后出现的播放/暂停状态自动跳回问题）。
- debug / release 可共存：debug 有独立包名后缀与"PixelPlayerOSS [D]"名称、红底图标。
- 仅构建 `arm64-v8a`，产物统一命名为 `pixelplayeross-<abi>-<版本>-<buildtype>.apk`。
- 新增 10 份设计文档（AI 移植、收听统计、Poweramp 历史对齐、备份歌单恢复、音频输出选型、Media3 升级路径等）。

### 10. 应用内更新日志

- 更新日志弹窗改为在运行时读取仓库的 `CHANGELOG.md`，不再是硬编码的本地化条目。章节标题仍走本地化，条目原文照录，因此弹窗始终与仓库文件同步，发版不必再手改两份。

---

## English

### 1. AI playlist generation

Describe a mood or a moment in plain language and get a playlist picked from your own library.

- **Any OpenAI-compatible endpoint.** A dedicated **AI** settings category ships three built-in providers — **Xiaomi MiMo**, **Volcano Engine (Ark)** and a **Custom Provider** with a user-supplied base URL — and lets you set the API key and model id, with a one-tap model list fetch and picker. Keys are stored per provider; Xiaomi MiMo Token Plan keys (`tp-`) and pay-as-you-go keys (`sk-`) resolve to their own endpoints automatically.
- **Home AI Mix entry.** The AI Playlist card at the top of the home feed opens a bottom sheet. Type a description such as "late night coding" or "commute energy", or tap one of the built-in idea chips.
- **Configurable library slice.** Choose **Most played** (stable list, so asking twice costs one request) or **Random** (a fresh shuffle that reaches songs you never play), with a sample size of 15, 25 or 40.
- **Editable results.** Play immediately, remove individual songs, or save the mix as a playlist.
- **Naming and history.** Playlists are named `yyyyMMdd-HHmm`, and a "Recently generated" row was added to the home screen.
- **Diagnostics.** Connection test, cache clearing and a request log with full request/response payloads and token usage that can be copied or shared. Credentials riding along in a URL (`key` / `token` / `secret` query parameters) are redacted before anything is written to disk, and the API key itself never enters a log entry.

### 2. Third-party player import (Poweramp)

Migrate a Poweramp `.poweramp-backup` file.

- **Source picker.** A dedicated third-party import entry, built to accept more sources later.
- **Backup preview.** Song, playlist and matched counts, the match rate, and examples of unmatched songs — shown before you commit to the import.
- **Song matching.** Title/artist/album/duration plus file-path matching, with path normalisation that handles external SD card volume-serial paths (`9C33-6BBD/...`) and multiple storage roots.
- **Per-section toggles.** Playlists, playback history, play counts, and favorites & ratings can be imported independently; a rating threshold can mark highly rated songs as favorites.
- **Two modes.** Merge (recommended — ratings and favorites are never downgraded) or Replace (overwrite playback history and play counts from the backup).
- **Result summary.** Matched songs, unmatched songs, playlists created/merged, history events, play counts, favorites, ratings saved, and skipped empty playlists.

### 3. Listening stats rebuilt

Stats were promoted from a home card to a **top-level bottom-bar tab** and redesigned end to end.

- **Recently played.** Grouped by day with repeated songs merged and counted; a subtitle explains what the list shows.
- **Period browsing.** Today / Week / Month / Year / All, plus previous period, next period, and reset to the current period.
- **Overview card.** Listening duration, play count, song count and artist count.
- **Full rankings.** Top songs, artists and albums each have a dedicated detail screen with "show all N" and sorting by **plays** or **duration**.
- **Data accuracy.** Playback events carry a play-count weight; opening the stats screen flushes the in-progress listening session so the song currently playing shows up immediately; zero-duration events imported from Poweramp are expanded by play count so imported history is not lost.

### 4. Five-star ratings now live in the database

- An interactive star row was added to the song info sheet.
- Ratings are stored as a database field instead of being written into embedded file tags, so cloud tracks can be rated too.
- Un-favoriting a song keeps its rating, and batch metadata edits no longer wipe unrelated tags.

### 5. Years smart category

A **Years** tab in the library buckets songs by release year, with a year detail screen supporting play, shuffle and sort.

### 6. Deletion protection mode

A new switch that **turns "Allow Song Deletion" off by default** — with it off, songs can never be deleted from the device, so a mis-tap cannot destroy files.

### 7. In-app diagnostic logs

- Adjustable log level (Verbose → Error); release builds default to WARN.
- One-tap export and share of diagnostic logs (in-memory ring buffer, rolling file, logcat fallback).
- Crash reports automatically carry the diagnostic log; oversized logs are truncated so copy/share cannot hang.

### 8. Backup and restore improvements

- Backups now include the AI provider configuration.
- Fixed favorites backups failing because of a duplicate Gson field name.
- Fixed playlist restore crashing on release builds: the R8 keep rules did not cover `data.backup.restore.PendingSongRef`, so obfuscation renamed its fields and every string in the backup payload arrived null. A payload this build does not recognise now degrades to no metadata, which still accepts a direct-ID hit, instead of throwing.
- A dedicated song-matching resolver for playlist restore.
- Play counts are preserved when exporting and restoring playback history.

### 9. Dependencies and build

- **Media3 upgraded to 1.11.0**, with a fix for the `onConnect` command-set regression that made playback state jump back after the upgrade.
- Debug and release builds can coexist: the debug build has its own application id suffix, the name "PixelPlayerOSS [D]" and a red launcher icon.
- `arm64-v8a` only, with a uniform artifact name: `pixelplayeross-<abi>-<version>-<buildtype>.apk`.
- 10 new design documents (AI port plan, listening stats design, Poweramp history alignment, playlist restore, audio output options, Media3 upgrade path, and more).

### 10. In-app changelog

- The changelog sheet now reads the repository `CHANGELOG.md` at runtime instead of shipping a hardcoded list of localized entries. Section titles stay localized and the entries are shown verbatim, so the sheet tracks the file and releases no longer need hand-editing in two languages.

---

## 截图 / Screenshots

### AI 歌单 / AI Playlist

![AI Playlist](https://raw.githubusercontent.com/pisces312/PixelPlayerOSS/main/docs/images/release-0.4.0-ai-playlist.jpg)

### 收听统计 / Listening stats

![Listening stats](https://raw.githubusercontent.com/pisces312/PixelPlayerOSS/main/docs/images/release-0.4.0-listening-stats.jpg)

> 两张截图同时作为附件提供下载，见下方 Assets。
> Both screenshots are also attached to this release for download — see Assets below.

---

## 安装说明 / Install notes

| | |
|---|---|
| 版本 / Version | `0.4.0-pisces.1` |
| 版本码 / Version code | `400001` |
| ABI | `arm64-v8a` |
| minSdk / targetSdk | 30 / 37 |
| 签名 / Signing | 个人密钥（alias `pisces312`）/ personal key |
| SHA-256 | `268388ae3e2261bd569babd754fe144e8fc5d16259dafc689cca962ce63099a3` |

**签名不同，无法覆盖安装上游构建。** 这是本 fork 的独立签名，与上游 CI 使用的密钥不一致；
若设备上已有官方构建，请先卸载（会清除应用数据，请先用内置备份导出）。

**The signature differs from upstream builds, so this APK cannot install over them.**
Uninstall the existing official build first — that wipes app data, so export a backup from inside the app beforehand.

## 许可证 / License

GPL-3.0-or-later。本 fork 基于 [PixelPlayerHQ/PixelPlayerOSS](https://github.com/PixelPlayerHQ/PixelPlayerOSS)，
部分功能由仓库作者从自有专有分支移植而来，原创算法与解析层标注 `Original work, GPL-3.0-or-later`。
