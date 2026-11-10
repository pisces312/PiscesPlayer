# 车机歌词标题：空行标记（blank marker）的语义与真实歌词验证方法

> 专题文档，配合 `docs/car-lyrics-title-plan.md`（功能本体，§6.3 规则表 / §9.7 记录）阅读。
> 本文只讨论**一件事**：LRC 里的裸时间戳行（空行）该怎么处理，以及**怎么用真实歌词把这件事验明白**。
> 源码版本：本仓库当前 `main`；规则改动落在 `utils/LyricsTimelineUtils.kt`（`buildLyricCues` / `standsForAGap`）。

## 0. 一句话结论

LRC 里的空行是**「上一句唱到这里结束」的记号**，不是「该回到真实曲名了」的指令。旧实现把每一句之间的换气都当成一次清屏，于是车机标题在歌词与曲名之间来回闪（用户报「一直跳回标题」）。现在只有**空行到下一条有文字的行间隔 ≥ 6 s（或后面再无文字）**时才回退曲名——那才是真正的前奏 / 间奏 / 尾奏。

`如愿-王菲`（105 行 = 44 空行 + 61 有文字行）：回退次数 **44 → 3**，整曲 cue 数 130 → 89。

## 1. 现象

车机标题在歌词与真实曲名之间来回跳，间隔不到 1 秒，整曲都在发生（不是偶发）。手机侧一切正常。

## 2. 根因

LRC 在几乎每一句唱完处都写一个**裸时间戳行**（形如 `[00:34.34]`，无文字），解析后是一条 `SyncedLine(line = "")`。旧实现把它当成一次「清屏」：

```
空行 → 一个空文本 cue → publish(null) → player 的 metadataOverride 撤下 → 车机读回真实 metadata（曲名）
```

于是**每一句之间都闪一次曲名**。该文件的 44 个空行间隔分布：

| 间隔 | 个数 | 含义 |
|---|---|---|
| < 1 s | 36 | 换气 |
| 1–2 s | 3 | 换气 |
| 2–4 s | 2 | 换气（慢句尾） |
| 4–8 s | 1 | 半句空档 |
| ≥ 8 s | 2 | 真·空档 |

绝大多数（41/44）是**换气**，间隔中位数 0.7 s。这正是「闪」的来源。

## 3. 规则（本次改动）

| 情形 | 结果 | 依据 |
|---|---|---|
| 空行后 **< 6 s** 就有下一条有文字的行 | **整条跳过**，屏幕停在上一句 | 换气 |
| 空行后 **≥ 6 s**，或后面再无文字 | 产一个空文本 cue ⇒ 撤销覆盖 ⇒ 恢复真实曲名 | 前奏 / 间奏 / 尾奏 |
| 空行本身（无论是否被跳过） | **仍然是它前一行的时间跨度终点** | `lineEndMs` 读的是原始 `lines` |

实现：`buildLyricCues` 里 `if (text.isEmpty() && !standsForAGap(lines, index)) return@forEachIndexed`；判据函数 `standsForAGap`；阈值常量 `BLANK_GAP_LIMIT_MS = 6_000L`。

三条边界与取值依据：

1. **跨度不变式（最容易写错的一处）**：`lineEndMs` 仍用原始 `lines[index + 1]`，不能因为空行被跳过就改读「下一条被显示的行」。否则被空行切细的长行会被拉长到下一句歌词，拆段时刻整体后移。单测 `buildLyricCues_stillEndsALineAtTheBlankMarkerItSkips`（该用例的设计是：按空行算跨度 ⇒ 段间隔 2 s；误按下一条显示的行算 ⇒ 3 s，能直接暴露回归）。
2. **阈值 6 s 的来处**：该文件最大换气间隔 2.38 s，留 2.5 倍余量；最小的真空档 6.20 s。4 s 与 6 s 在本曲结果完全相同（那三条真空档分别是 12.01 / 30.56 / 6.20 s，都远在其上）。
3. **比对对象是「下一条有文字的行」，制作信息行也算**：判据问的是「这期间有没有东西可以显示」，不是「是不是歌词」。附带效果：本曲 `16.53 s` 的版权行把 `12.01 s` 那条空行的间隔压到 4.52 s，于是它也被跳过。

## 4. 为什么不过滤制作信息行

LRC 里的 `作词 : …` / `作曲 : …` / `曲版权管理方：…` / 尾部名单等行**有文字**，与歌词行同等对待：照常上屏、照常参与拆分与驻留，**永不触发回退**。

这是明确的产品选择（保留歌词文件里的署名信息）。它带来两个好处：规则只有「空行怎么处理」一处分叉；`publish` 的空文本路径依旧是**唯一**会把标题交还给真实曲名的地方（连同前奏 / 换歌 / 门控三条）。

代价（已知并接受）：前奏不再清屏 ⇒ **0:00–19.5 s 显示的是版权行**（本曲 `曲版权管理方：…` 的最后一段从 18.5 s 一直停到 31.5 s 首句歌词，约 13 s）。若哪天决定改为「前奏一律显示曲名」，做法是给 `buildLyricCues` 加「过滤署名行」，见 §7 备选。

## 5. 用真实歌词验证的方法

这次问题的证据**只能从真实歌词文件里拿到**：手工夹具不会带上 44 个换气空行的间隔谱，也就复现不出「一直跳」。所以证据必须来自真实文件，而验证分三层，一层比一层贵——**先跑最便宜的**：

| 层 | 跑的是什么 | 成本 | 能证明 |
|---|---|---|---|
| **5.1 单测** | 应用自己的解析器 + 切段函数，喂真实文件的**结构** | 秒级，无需设备 | 规则本身，且每次改动都可回归 |
| **5.2 离线复刻** | 真实文件的**完整歌词**，用 Python 复刻同一套算式 | 分钟级，需那首歌在本机 | 算式在完整文件上的前后对照（数字表） |
| **5.3 在线管线** | 模拟器 / 真机 + debug 包 + 真实 mp3 | 约 5 分钟，需设备 | 加载 → 建 cue → 推送 → 回退 → 车机重读整条链路 |
| **5.4 真车** | 耳朵 + 车机 | — | 观感（其余各层都替代不了） |

### 5.1 单测：把真实文件的结构固化成夹具（**推荐起点**）

真歌词**不入库**（版权），而这条规则**从来不读歌词文字**——决定一个 cue 时刻的只有三样：行的时间戳、该行的列宽（决定切成几段）、下一条有文字的行离多远。所以把「结构」提取出来、文字换成等宽填充，就得到一份既无版权风险、又与真曲逐位同构的夹具：

- **夹具** `app/src/test/resources/lyrics/blank-marker-shape.lrc`：105 行、44 个空行，时间戳与**逐行列宽**都取自那首歌，文字是 `示例文本用于测试…` 一类填充。
- **测试** `LyricsTimelineUtilsRealFileTest`（8 例）：夹具文本交给**应用自己的** `LyricsUtils.parseLyrics`（而不是直接构造 `SyncedLine`），所以「解析器保留空行」这个前提本身也在断言范围内。
- **生成器** `gen_lyric_fixture.py`（根目录，与 `gen_debug_icons.py` 同类的一次性脚本），可指向任意一首歌或 `.lrc`：

```bash
# 生成夹具；--check 顺手证明夹具与真曲的 cue 时刻逐位相同（不一致则退出码 1）
python gen_lyric_fixture.py --source "E:\resources\music\如愿-王菲.mp3" --check

./gradlew :app:testDebugUnitTest --tests "com.pisces312.piscesplayer.utils.LyricsTimelineUtilsRealFileTest"
```

`--check` 是这套做法的可靠性来源：同一套算式分别跑在真歌词与夹具上，逐条比对 cue 时刻（本曲 89 个全部相同）。「夹具等价于真曲」因此不是声称，而是可复核的。换歌时同理——**先 `--check` 通过，夹具才可信**。

**这一层是有牙齿的**：把 `BLANK_GAP_LIMIT_MS` 临时改成 `0L`（＝改前的行为），8 例中有 3 例立刻失败，报出的正是改前的数字——`expected:<89> but was:<130>`，空 cue 索引从 `[9, 28, 51]` 变成 `[9, 11, 13, 17, 19, 24, 30, …]`。以前要四分钟 + debug 包 + 一台设备才能发现的回归，现在一条断言就挡住了。

### 5.2 离线复刻：抽出真实歌词，用应用的规则做前后对照（不改应用、不入库）

- **数据来源**：本机 mp3 的**内嵌**歌词标签。`getLyrics` 默认 `EMBEDDED_FIRST`，车机上看到的就是这一份（`.lrc` 同名文件是另一条路径）。
- **抽取方式**：自解析 ID3v2 帧，不依赖外部库（`mutagen` 非必需）。要点：
  - 帧头 = `4 字节 ID` + `4 字节大端长度` + `2 字节 flags`，帧从 `10 + size` 之后开始；
  - `USLT` 帧自带**编码字节**（`0`=latin-1 / `1`=UTF-16 / `2`=UTF-16BE / `3`=UTF-8）、`3 字节语言码`，其后是描述字段与正文，两者之间是**同编码的终止符**（UTF-16 要按偶数字节找 `00 00`）。
  - 时间戳形态：`[mm:ss]` / `[mm:ss.xx]` / `[mm:ss.xxx]`，两位小数是**厘秒**、三位是**毫秒**，解析时必须区分（否则整曲时间轴差 10 倍）。
- **复刻规则**：3 列 / 汉字、30 列上限、`dwell = min(span ÷ 段数, 4 s)`、**跳过空行但不改 span**——与应用同一套算式逐条对齐。
- **产出**：行数 / 空行数 / 间隔分布 / 改动前后 cue 数与回退次数的对照（§6 的表就是它的输出）。
- **版权**：真歌词**不入库**。临时脚本与导出的文本都留在 `.gitignore` 的 `build/` 下；进仓库的只有 §5.1 那份**结构夹具**（时间戳 + 等宽填充，不含歌词文字），以及本文引用的**时间戳与计数**。

### 5.3 在线：在模拟器 / 真机上跑真实管线

5.1 与 5.2 都只证明算式，管线（加载 → 建 cue → 推送 → 回退 → 车机重读）要靠这一层。

- **必须装 debug 包**：`car lyric title:` 全系列日志都是 `Timber.d/v`，而 `ReleaseTree` 只放行 `WARN` 及以上（`ReleaseTree.kt:13`），release 里一条都不出；应用内日志导出在 release 默认也只收 WARN+。debug 与 release 的 `applicationId` 不同（`.debug` 后缀），可共存。
- **让歌词进到应用里，两条路径都可用**：
  1. **真实文件**：`adb push` mp3 到 `/sdcard/Music/` + 触发媒体扫描，等曲库索引到它再播放。走的是内嵌标签 + 真实时长，最保真。
  2. **注入 Room**（更快、可复现过）：把歌词文本写进 `songs.lyrics` 再播放该曲。**注意曲长要对得上**——末行的跨度取自 `player.duration`（真实文件），歌词比音频长时后面的行永远轮不到。
- **抓日志的正确姿势**：**先起 `adb logcat -v time > file 2>&1 &`，再开始播放**。`logcat -c` 之后隔一段时间再用 `-d` 抓，可能整个 tag 一行都拿不到（2 MiB 环形缓冲已被刷掉）。
- **模拟器的 A2DP 门控**：`adb shell settings put global pixelplayer_car_lyric_title_force_a2dp 1`（仅 debug 构建识别）。
- **判据**（全部是可 grep 的字符串）：

| 判据 | 期望 |
|---|---|
| `loaded N synced lines as M cues` | N / M = §5.1 单测断言的两个数（本曲 105 / 89） |
| `back to the track title (…)` 的**条数**与**时刻** | 只出现在真空档；括号里写明是哪条路径 |
| 换气处（间隔 0.5–2.4 s） | 一条 `back to the track title` 都不应有 |
| `media update timeout` 计数 | 0（顺带确认 AVRCP 闸门短路未被带坏） |

`publish` 现在会为回退打一行带原因的日志（`toggle off` / `output is not bluetooth` / `nothing playing` / `no synced lyrics` / `song changed, lyrics not loaded yet` / `before the first line` / `instrumental stretch`）——**这行日志是本节所有在线判据的前提**，加它之前的版本只能靠采样 `dumpsys media_session` 的标题去猜是哪条路径。

一次完整跑法的命令（2026-09-18 实际用的就是这套）：

```bash
./gradlew :app:assembleDebug
adb install -r -d app/build/outputs/apk/debug/pixelplayeross-arm64-v8a-*-debug.apk
adb shell settings put global pixelplayer_car_lyric_title_force_a2dp 1        # 仅 debug 识别

# 真实文件已在设备上时：Library → 文件夹 → Internal Storage → Music → 点那首歌
# （无头环境靠 uiautomator dump 拿 bounds，再 input tap 中心点）
adb shell am force-stop com.pisces312.piscesplayer.debug
adb shell monkey -p com.pisces312.piscesplayer.debug -c android.intent.category.LAUNCHER 1
adb shell uiautomator dump /sdcard/ui.xml
adb shell cat /sdcard/ui.xml | tr '<' '\n<' | grep -oE 'text="[^"]+"[^/]*bounds="[^"]+"'

(adb logcat -v time > build/logcat_blank.txt 2>&1 &) ; sleep 2               # 先起日志
adb shell input tap <x> <y>                                                  # 再播放
sleep 215 ; pkill -f "adb logcat"                                            # 播完整曲后收工

grep "car lyric title" build/logcat_blank.txt | grep -E "loaded [0-9]+ synced|back to the track title"
adb shell settings delete global pixelplayer_car_lyric_title_force_a2dp      # 还原
```


### 5.4 真车

唯一能验收「换气时标题不闪」的地方：装 debug 包，用耳朵 + 车机。logcat 只能确认路径与时刻，确认不了观感。

## 6. 实测记录（2026-09-18）

样本：本机 `如愿-王菲` 的内嵌 USLT，时长 265.38 s（`ffprobe`）。**105 行 = 44 空行 + 19 制作信息行 + 42 歌词行**。

| 指标 | 改前 | 改后（6 s 阈值） |
|---|---|---|
| cue 总数 | 130 | **89** |
| 回退真实曲名次数 | **44** | **3** |
| 换气空行（41 个，0.5–2.4 s） | 每次都闪一次曲名 | **0** |
| 真空档（`19.51` / `1:29.82` / `3:12.93`，间隔 12.01 / 30.56 / 6.20 s） | 3 次 | 3 次（保留，设计如此） |
| 4:20 之后的署名名单区（16 个 0.15 s 空行夹在名单行之间） | 每 0.15 s 闪一次曲名，共 **16 次** | **0** |

30–40 s 段（该处空行密集）：

| | cue 序列 |
|---|---|
| 改前 | 首句 → 空 → 次句 → 空 → 第三句（5 个 cue，含 **2 次回退**） |
| 改后 | 首句 → 次句 → 第三句（3 个 cue，**0 次回退**） |

### 6.1 单测（`LyricsTimelineUtilsRealFileTest`）：通过

夹具生成时 `--check` 已逐条比对过真曲的 89 个 cue 时刻（全部相同），所以下表同时就是**真曲**的数字。

| 断言 | 期望 | 观察 |
|---|---|---|
| 解析后行数 / 空行数 | 105 / 44 | 105 / 44 |
| cue 总数 | 89 | 89 |
| 空文本 cue 的索引与时刻 | `[9, 28, 51]` / 19.51 / 89.82 / 192.93 s | 完全一致 |
| 41 个换气空行处的屏幕内容 | 非空（＝上一句） | 41 处全部非空 |
| 三个真空档处的屏幕内容 | 空（＝真实曲名） | 全部为空 |
| cue 时刻严格递增、序号 `0..88` 无缺口 | 成立 | 成立 |
| 首句之前（0–16.53 s） | 署名行照常上屏，共 6 个 cue | 6 个 cue |
| 末行（起点距曲尾仅 144 ms） | 265240 / 265312 ms | 一致 |

反向验证：阈值临时改 `0L`（＝改前行为）⇒ 8 例中 3 例失败，`expected:<89> but was:<130>`，空 cue 索引变成 `[9, 11, 13, 17, 19, 24, 30, …]`，与改前的真实数字一致。

### 6.2 在线（模拟器 pixel6 AVD + debug 包）：通过

环境：pixel6 AVD（API 34）、本仓库 debug `0.4.2-pisces.1`、`pixelplayer_car_lyric_title_force_a2dp=1`；主开关与「拆分长行」均为开、`lead` 默认 500 ms。

歌词来源走的是**真实文件**：`如愿-王菲.mp3` 本来就在设备 `/sdcard/Music/`，曲库内 id `1000001867`，其 `songs.lyrics` 为空 ⇒ `getLyrics` 落到内嵌 USLT（与真车同一条路径）。播放方式为 `adb shell input tap` 点该行；日志**先起 `adb logcat -v time > file`** 再点，持续覆盖到歌曲自动切下一首。

| 判据 | 期望 | 观察 | 结论 |
|---|---|---|---|
| `loaded N synced lines as M cues` | 105 / 89 | `loaded 105 synced lines as 89 cues for 1000001867 (offset 0 ms, lead 500 ms, split true)` | **与 §5.2 离线算式逐位一致**（同一份歌词、两套独立实现） |
| 回退总次数 | 1（换歌）+ 3（真空档） | 4 条：`song changed, lyrics not loaded yet` ×1、`instrumental stretch` ×3 | 无多余回退 |
| 三次空档回退落在哪个 cue | 9 / 28 / 51 | `next wake … (cue 9)`、`(cue 28)`、`(cue 51)` | 与离线算出的空 cue 索引 `[9, 28, 51]`（19.51 / 89.82 / 192.93 s）**完全吻合** |
| 换气处（间隔 0.5–2.4 s，41 处） | 0 次回退 | 整曲 42 句歌词之间一条 `back to the track title` 都没有 | 被报的现象消失 |
| 真空档期间屏幕内容 | 真实曲名 | `cue 28` 之后连续 6 条 `next wake in 5000 ms (cue 28)`（31 s）＝真实曲名停留到下一句 | 「间奏显示曲名」的设计行为保留 |
| 曲尾署名区（4:20–4:25） | 0 次回退 | 0：署名行逐段连续推送（每 ≈0.4 s 一段），中间无回退；改前此处每 0.15 s 闪一次曲名 | 16 → 0 |
| AVRCP 闸门短路未被带坏 | `media update timeout` = 0 | 0 | 与 `car-lyrics-avrcp-gate.md` 结论一致 |

关键日志（歌词行以占位符代替，只保留可 grep 的形态）：

```
D/MusicService_PixelPlayer: car lyric title: loaded 105 synced lines as 89 cues for 1000001867 (offset 0 ms, lead 500 ms, split true)
D/MusicService_PixelPlayer: car lyric title: 作词 : 唐恬 作曲 :        ← 署名行照常上屏（第 1 段）
D/MusicService_PixelPlayer: car lyric title: back to the track title (instrumental stretch)
V/MusicService_PixelPlayer: car lyric title: next wake in 5000 ms (cue 9, lead 500 ms)
…（31 s 的真实曲名）…
D/MusicService_PixelPlayer: car lyric title: ⟨第 1 句歌词，段 1/2⟩
```

**两点提醒**：

1. 模拟器的音频管线会停顿，**墙钟间隔会漂**（本曲累计漂了约 3 s）。所有判据都以 **cue 序号**为准，不要拿墙钟算对齐——这与 `avrcp-emulator-verification.md` §5 的注意事项同源。
2. 歌曲放完自动切下一首时会有一条 `song changed, lyrics not loaded yet` 回退，**这是设计内的换歌路径**，不是空行规则的问题；统计回退次数时先把它减掉。

### 6.3 未验证 / 边界

- **真车未复测**：换气处是否还在闪，只能耳朵 + 车机。
- 本文的数字来自**一首歌**。阈值 6 s 的普适性依赖「换气 < 6 s」这一经验：慢歌长句可能更长（本曲最长 2.38 s）。若真车在别的歌上仍见闪回，先看那首歌的空行间隔谱（§5.2 的脚本即可量），再决定是否调 `BLANK_GAP_LIMIT_MS`。

## 7. 备选与被否决的做法

| 做法 | 结果 | 为什么没采用 |
|---|---|---|
| **保持现状**（空行一律清屏） | 每句之间闪曲名 | 就是被报的那个 bug |
| **空行一律不清屏**（无阈值） | 本曲回退 0 次 | 前奏 18.5–31.5 s 停在版权行；`1:29.82` 起的 30 s 间奏停在上一句。且它丢掉了「间奏显示曲名」这个原本就在设计里的行为 |
| **阈值 + 过滤署名行** | 本曲回退 3 次，前奏 0–31.5 s 显示曲名 | 需要为「什么算署名行」维护一张关键字表（本曲可全中且零误伤，但换首歌就得重新核），且与「署名信息保留显示」的产品选择相悖。**若将来产品决定改口，这条就是落地方式**（纯函数 + 单测即可） |
| **阈值 + 显示占位符**（如 `♪`） | 真空档显示符号而非曲名 | 多一套文案与译文；标题栏出现符号未见得比曲名好 |
