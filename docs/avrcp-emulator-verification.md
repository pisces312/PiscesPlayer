# 在模拟器上验证 AVRCP / 车机歌词标题

> 用途：不连真车就能端到端验证「车机标题」链路。
> 姊妹文档：`docs/car-lyrics-avrcp-gate.md`（闸门原理与绕过方案）、`docs/car-lyrics-title-plan.md`（功能本体）。
> 最近一次实测：2026-09-18（同日两次：驻留上限与缓冲态见 `car-lyrics-title-plan.md` §9.6；空行标记阈值见 `car-lyrics-blank-markers.md` §6.2，用设备上的真实 mp3 播完整曲）；此前 2026-09-17，pixel6 AVD（API 34）+ 本仓库 debug `0.4.2-pisces.1`。

## 0. 为什么模拟器够用

**Android 模拟器自带完整蓝牙协议栈，AVRCP target 是真实运行的**，不是桩：

| 证据 | 观察 |
|---|---|
| 蓝牙进程在跑 | `adb shell ps -A \| grep bluetooth` → `com.google.android.bluetooth`（另含 `android.hardware.bluetooth-service.default`、`bt_vhci_forwarder`） |
| Java 层 AVRCP | logcat 里有 `AvrcpTargetService` / `AvrcpTargetJni` / `AudioMediaPlayerWrapper` / `MediaPlayerList` / `MediaDataManager` |
| Native 层 AVRCP | logcat tag `bt_stack`：`avrcp_service.cc(515) AvrcpService::SendMediaUpdate(bool, bool, bool) track_changed=…` —— 这就是最终发给车机的那一跳 |
| 元数据变更被消费 | `AudioMediaPlayerWrapper: trySendMediaUpdate(): Metadata has been updated for <pkgname>` |

也就是说：**只要我们的 App 往 MediaSession 写标题，模拟器上的蓝牙进程会像真车一样把它走完 AVRCP 全流程并打日志。** 唯一缺的是「真实车机屏幕的渲染与截断宽度」，那部分只能靠真车。

---

## 1. 环境准备

### 1.1 设备

| 项 | 值 | 说明 |
|---|---|---|
| AVD | `pixel6`（系统镜像 `sdk_gphone64_x86_64-userdebug 14 UE1A.230829.050`） | **ABI 是 x86_64**，但本仓库的 **arm64-v8a 产物可直接装**（模拟器有 ARM 转译），不必为此打 universal |
| API | 34 | |
| 屏幕 | `1080x2400`（`adb shell wm size`） | 下文坐标基于此；布局变了坐标要重取 |

启动后轮询到 `adb shell getprop sys.boot_completed` = `1` 再用。

### 1.2 装包

```bash
# debug 不跑 R8，日志与 tag 完整；验证一律用 debug
./gradlew :app:assembleDebug > build_debug.log 2>&1
adb install -r -d app/build/outputs/apk/debug/pixelplayeross-arm64-v8a-0.4.2-pisces.1-debug.apk
```

> 版本号日常固定 `pisces.1` 不 bump，若 adb 报降级，记住加 `-d`。

### 1.3 造数据：注入一段可控歌词

真曲库的歌词不可控，直接改数据库最省事。找一首歌（下面用 `id=1000001891`），写三行测试歌词 —— **纯英文长行 / 纯中文长行 / 短行**，正好覆盖「列宽切段」的三类输入：

```bash
adb shell "run-as com.pisces312.piscesplayer.debug sqlite3 databases/pixelplayer_database \
  \"update songs set lyrics =
     '[00:00.00]abcdefghijklmnopqrstuvwxyzabcdefghijklmnop' || char(10) ||
     '[00:06.00]一二三四五六七八九十一二三四五六七八九十一' || char(10) ||
     '[00:12.00]short line'
   where id = 1000001891\""

# 回读确认
adb shell "run-as com.pisces312.piscesplayer.debug sqlite3 databases/pixelplayer_database \
  \"select lyrics, duration from songs where id = 1000001891\""
```

期望切段（30 列/段，汉字 3 列）：40 字母 → 2 段 ×20；21 汉字 → 3 段 ×7；短行 → 1 段。

---

## 2. 打开 A2DP 门控（关键开关）

车机歌词默认只在「音频路由到蓝牙 A2DP」时生效。模拟器没有真实 A2DP 设备，用一个全局标志强制打开：

```bash
adb shell settings put global pixelplayer_car_lyric_title_force_a2dp 1   # 打开（模拟在车上）
adb shell settings get  global pixelplayer_car_lyric_title_force_a2dp     # 读回
adb shell settings delete global pixelplayer_car_lyric_title_force_a2dp   # 关闭（回到常态）
```

**改动即时生效，不需要重启进程** —— 控制器订阅了这个标志。这让「同一播放位置切换开关」成为最省事的对照实验（见 §3.3）。

> 前提：设置 → 播放 → 车载蓝牙 → **「标题显示歌词」** 必须打开（默认关闭）。

---

## 3. 观察面

### 3.1 `dumpsys media_session` —— 蓝牙进程读到的就是这份数据

```bash
adb shell dumpsys media_session | grep -E "package=com.pisces312.piscesplayer.debug" -A 12 \
  | grep -oE "active item id=[^,]*|queueTitle=[^,]*, size=[0-9]*|description=[^,]*"
```

| 字段 | 含义 | 闸门短路时 |
|---|---|---|
| `description=` | Title（车机看到的那行字） | 逐段变成歌词 |
| `active item id=` | `PlaybackState.activeQueueItemId`，媒体3 由 timeline 算出 | **`-1`**（短路的决定性证据） |
| `queueTitle=…, size=` | 框架会话的队列 | 窗口内仅剩当前曲目（本例队列本来就 1 项，看不出变化；多首队列时是 60 → 1） |

### 3.2 logcat —— 我方发布与蓝牙发出的时间对齐

| tag | 内容 |
|---|---|
| `MusicService_PixelPlayer` | 我方：`car lyric title: …`（`bluetooth output active` / `switching to song` / `loaded N synced lines as M cues` / 每段文本 / `next wake`） |
| `AudioMediaPlayerWrapper` | 蓝牙 Java 层：`trySendMediaUpdate(): Metadata has been updated for …` |
| `bt_stack` | 蓝牙 native：`avrcp_service.cc(515) SendMediaUpdate(bool,bool,bool) track_changed=1 : play_state=1 : queue=0` |
| `AvrcpTargetService` / `AvrcpTargetJni` | AVRCP target 其他行为 |
| `MediaPlayerList` / `MediaDataManager` | 会话发现与封面加载（`Unable to load bitmap` / `Artwork URI has not been granted` 常来自 SystemUI 取封面，与本功能无关） |

对齐命令（把两条时间线并排打出来）：

```bash
adb logcat -d | grep -E "MusicService_PixelPlayer: car lyric title: (abc|vwxy|一二三|五六七|short)|SendMediaUpdate\(bool" \
  | grep -vE "loaded|active:|bluetooth output|switching" \
  | sed 's/^\(09-17 [0-9:.]*\).*car lyric title: \(.*\)/\1  APP-PUBLISH \2/
         s/^\(09-17 [0-9:.]*\).*SendMediaUpdate/\1  BT-SEND/'
```

**闸门是否还在 2 秒延迟路径上**（改前会大量出现，改后应为 0）：

```bash
adb logcat -d | grep -ciE "media update timeout"
```

### 3.3 标准流程（一次跑完全部断言）

```bash
# 1) 基线：关掉门控
adb shell settings delete global pixelplayer_car_lyric_title_force_a2dp
adb shell am force-stop com.pisces312.piscesplayer.debug
adb shell monkey -p com.pisces312.piscesplayer.debug -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
sleep 6
adb shell input tap 420 2203      # 搜索框
sleep 3; adb shell input text "Night"; sleep 3
adb logcat -c
adb shell input tap 300 800       # 搜索结果行
sleep 2
for i in 1 2 3; do adb shell dumpsys media_session | grep -E "package=com.pisces312.piscesplayer.debug" -A 12 \
  | grep -oE "active item id=[^,]*|description=[^,]*" | head -2 | tr '\n' '|'; echo; sleep 2; done
# 期望：active item id=0 | description=Night Tone   ← 真实曲名

# 2) 窗口内：打开门控（不用重启进程）
adb shell settings put global pixelplayer_car_lyric_title_force_a2dp 1
sleep 4
for i in 1 2 3 4 5 6; do adb shell dumpsys media_session | grep -E "package=com.pisces312.piscesplayer.debug" -A 12 \
  | grep -oE "active item id=[^,]*|description=[^,]*" | head -2 | tr '\n' '|'; echo; sleep 2; done
```

> 坐标依赖当前布局，取法：`adb shell uiautomator dump /sdcard/ui.xml` 后
> `adb shell cat /sdcard/ui.xml | tr '<' '\n<' | grep -oE 'text="Night[^"]*"[^/]*bounds="[^"]+"'`。

---

## 4. 最近一次实测结果（2026-09-17，闸门短路方案落地后）

**窗口内**（`force_a2dp=1`，播放注入歌词的 `Night Tone`）：

```
active item id=-1 | description=abcdefghijklmnopqrstu
active item id=-1 | description=vwxyzabcdefghijklmnop
active item id=-1 | description=一二三四五六七
active item id=-1 | description=五六七八九十一
active item id=-1 | description=short line
```

**我方发布 → 蓝牙栈发出的延迟**：

| APP-PUBLISH | BT-SEND (`track_changed=1`) | 延迟 |
|---|---|---|
| 04:35:18.721 `abcdefghijklmnopqrstu` | 04:35:18.720 | ~0 ms |
| 04:35:24.496 `vwxyzabcdefghijklmnop` | 04:35:24.518 | 22 ms |
| 04:35:27.424 `一二三四五六七` | 04:35:27.427 | 3 ms |
| 04:35:31.555 `五六七八九十一` | 04:35:31.556 | 1 ms |
| 04:35:33.491 `short line` | 04:35:33.491 | 0 ms |

- 2s 延迟路径触发次数：**0**（改前每段都会踩）
- 这几段 cue 的间隔恰好约 2 秒 —— 正是改动前会被永久饿死的临界情形
- `track_changed=0` 的每 3 秒一次是 position/play_state 心跳，与 metadata 无关

**反向验证**（门控关掉后）：`active item id=0`、`description=Night Tone` —— 队列与真实曲名完整恢复。

---

## 5. 已知坑

| 坑 | 现象 | 处理 |
|---|---|---|
| **UI 交互偶发无响应** | pixel6 AVD 有时点了没反应 | `am force-stop` 后重开；UI 自动化测试（instrumented）基本点不动，**只做数据库/会话类验证** |
| **音频管线停在 BUFFERING** | `position` 不推进、`dumpsys` 状态停在 BUFFERING | 不影响 metadata 推送验证；反而可利用 position 固定在 0 做「同位置切开关」对照实验 |
| **约 2 分钟日志断流** | 控制器一条日志都不出，进程存活、无 FATAL、MediaSession 仍在更新 | 未定位根因（疑似模拟器调度节流）。`force-stop` 后恢复。**2026-09-18 增补**：本功能侧已消掉一条「断流后永不恢复」的机理——旧代码在 `!player.isPlaying`（缓冲 / 抑制，`playWhenReady` 仍为 true，位置会自己继续走）时直接 `stopScheduling()`，此后只能等播放事件；现已改为按看门狗兜底（判据与实测见 `car-lyrics-title-plan.md` §9.6 的 ③，`next wake in 5000 ms` 会连续出现）。真机若出现「标题停住」，先按该判据区分是哪一侧 |
| **歌曲播完就恢复真实曲名** | 采样太晚只看到 `Night Tone` | 采样要在播放开始后 2 秒内起步；45 s 的曲子最后一行是 `short line` |
| **封面报错噪音** | `Unable to load bitmap` / `Artwork URI has not been granted` | 来自 SystemUI 取封面，与本功能无关，别误判 |
| **debug 才有的东西** | release 跑 R8，tag 名字不变但 ProGuard 会改类名 | 验证一律用 debug；**别给 debug 开 minify** |

---

## 6. 模拟器覆盖不到的部分（必须真车）

1. **车机标题栏的实际渲染宽度** —— 30 列/段是按观测推的（10 汉字 ≈ 30 字母），真车要复核是否正好填满、有没有少一个字母。
2. **车机是否自带跑马灯滚动** —— 若会滚，设置里关掉「拆分长行」更好。
3. **真实 A2DP 路由判定的时机** —— 模拟器用标志强制，真车是路由回调。
4. **蓝牙进程 CPU / 封面是否闪** —— 短路后每条 cue 都真发，蓝牙侧每次要重建 `Metadata`；真车上留意。
5. **btsnoop** —— 要看空中报文（`EVENT_TRACK_CHANGED` / `GetElementAttributes` 往返）必须真车抓。
