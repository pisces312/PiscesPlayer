# AI 流式输出 + 生成界面展示思考过程 方案

> 状态：**已实施（2026-09-16）** —— 构建与单测通过，实现记录与偏离见 §10。
> 真机双 provider（MiMo / 火山方舟）端到端验证待接设备后补（§8）。
> 涉及代码：`data/ai/OpenAiCompatibleClient.kt`、`data/ai/AiHandler.kt`、
> `data/ai/AiPlaylistGenerator.kt`（+ 新增 `data/ai/AiProgressEvent.kt`）、
> `presentation/viewmodel/PlaylistViewModel.kt`、
> `presentation/components/AiMixSheet.kt`、`res/values*/strings_ai.xml`、
> 新增单测 `app/src/test/.../data/ai/OpenAiCompatibleClientSseTest.kt`。
> 关联问题：开启「思考」后用「不期而遇」/ AI 混音必现「网络不可达」，根因见第 1 节。

## 1. 背景与根因

`OpenAiCompatibleClient` 注入的是全局共享 `OkHttpClient`（`AppModule.provideOkHttpClient()`），
其 `connect/read/writeTimeout` 全为 **8 秒**；AI client 只在 `newBuilder()` 上补了
`callTimeout(60s)`，**readTimeout 仍继承 8 秒**。

请求固定 `stream=false`（`OpenAiCompatibleClient.kt:83`）：非流式下，模型在
「思考 + 生成」全部完成前一个字节都不返回，而 OkHttp 的 readTimeout 卡的是两次数据包
之间的间隔。思考沉默超过 8 秒 → `SocketTimeoutException`（IOException 子类）→
被 `execute()` 的 `catch (e: IOException)` 归类为 `AiErrorKind.NETWORK`
（`OpenAiCompatibleClient.kt:167-174`）→ UI 显示「网络不可达」
（`PlaylistViewModel.kt:771`）。与网络是否可达无关。

本方案改 SSE 流式后，思考 token 逐块下发，socket 持续有数据，readTimeout 不再误杀；
同时把思考内容实时显示在生成界面，并顺手修掉超时配置。

## 2. 厂商协议核实（2026-09-15 查文档）

两家内置 provider 均为 OpenAI 兼容 Chat Completions，SSE 格式一致：

- 请求：`"stream": true`，SSE 逐块返回，以 `data: [DONE]` 结束；
  支持 `"stream_options": {"include_usage": true}`，最后一包带 `usage`（该包 `choices` 为空）。
- 思考增量：`choices[0].delta.reasoning_content`；正文增量：`choices[0].delta.content`。
  思考阶段 content 为空串，回答阶段不再有 reasoning_content。
- 非流式消息体对应字段为 `choices[0].message.reasoning_content`。
- token 用量：`usage.completion_tokens_details.reasoning_tokens`（现有代码已读取）。
- 现有请求体里的 `"thinking": {"type": "enabled"|"disabled"}` 两家都支持，流式下不变。

文档：

- 火山方舟《流式输出》`https://docs.volcengine.com/docs/82379/2123275`
  （Chat API SSE 示例明确含 `delta.reasoning_content`、`data: [DONE]`）；
  《对话(Chat)API》`https://docs.volcengine.com/docs/6492/2192011`（stream / stream_options / thinking）。
- 小米 MiMo《OpenAI Chat Completions API 兼容》
  `https://mimo.mi.com/docs/zh-CN/api/chat/openai-api`
  （流式 chunk 的 `choices.delta.reasoning_content`、usage 结构、thinking 参数；
  模型 mimo-v2.5 / mimo-v2.5-pro）。
- CUSTOM provider 走标准 OpenAI SSE；另兼容 `delta.reasoning`（OpenRouter 等生态的字段名）。

## 3. 现状链路（源码证据）

```
AiMixSheet「生成」Button (AiMixSheet.kt:308)
  → HomeScreen onGenerate (HomeScreen.kt:654-660)
     → PlaylistViewModel.generateAiPlaylistPreview / generateSerendipityPreview (L487/L504)
        → startPreview() (L514-549)：置 isGenerating=true，runCatching { generate() }
           → AiPlaylistGenerator.generate / generateSerendipity
              → AiHandler.generate (AiHandler.kt:59)：缓存 → client.chat → 缓存/usage/请求日志
                 → OpenAiCompatibleClient.chat：stream=false，一次性 ChatResult
  UI：AnimatedContent 按 isGenerating/hasResult 切 Input/Generating/Result
      GeneratingPhase (AiMixSheet.kt:409-430) 目前只有大转圈 + ai_mix_generating
```

关键事实：

- AI 与不期而遇共用同一个 `AiMixSheet`、同一个 `_aiPlaylistPreviewState`
  （`NlpPlaylistPreviewState`）；该 state 也被离线 NLP 的 `DescribePlaylistDialog`
  （LibraryScreen）共用，但 NLP 走独立的 `generateNlpPlaylistPreview()`，不经过
  `startPreview()`，也不发网络请求 → 给 state 加 AI 专属字段不影响 NLP 对话框。
- `AiHandler.generateText()`（不期而遇「换个说法」）与 `testConnection()`（设置页测试连接）
  也都调 `client.chat()`，同样受 8 秒 readTimeout 影响，本次一并换成流式实现。
- `listModels()` 是 GET，保持非流式，但要脱离 8 秒默认超时。
- 关 sheet 时 `resetAiPlaylistPreview()`（L721）只清 state，**不会取消在途请求**；
  重新生成也不会。流式化后应让取消真正断开连接（省 token）。
- `AiRequestLogStore` 是 cacheDir 文件日志（非 Room），改字段不涉及 schema migration；
  但本期决定思考内容**不写日志、不进缓存、不入库**，只存在于内存 UI state。

## 4. 方案总览

```
SSE chunk(data: {...})
  → OpenAiCompatibleClient 读循环：纯函数解析 delta
       ├─ reasoning_content/reasoning → 累积 thinkingText → listener.onProgress(Thinking)
       ├─ content                     → 累积 answerText   → listener.onProgress(Answer)
       └─ 末包 usage                  → ChatResult(token 统计)
  → AiHandler（缓存命中无回调；失败/成功日志、usage 落库逻辑不变）
  → AiPlaylistGenerator（透传 listener）
  → PlaylistViewModel.startPreview（update _aiPlaylistPreviewState.stage/thinkingText）
  → AiMixSheet.GeneratingPhase：状态行 + 可折叠「思考过程」卡片实时滚动
```

## 5. 详细改动

### 5.1 新增 `data/ai/AiProgressEvent.kt`

```kotlin
package com.pisces312.piscesplayer.data.ai

/** SSE 期间向 UI 报告的中间进度。[full] 是截至当前的累积全文。 */
sealed interface AiProgressEvent {
    data class Thinking(val delta: String, val full: String) : AiProgressEvent
    data class Answer(val delta: String, val full: String) : AiProgressEvent
}

fun interface AiProgressListener {
    fun onProgress(event: AiProgressEvent)
}
```

### 5.2 `OpenAiCompatibleClient` 流式化

**请求体**：`stream` 改为 `true`；追加 `stream_options = { include_usage = true }`；
`thinking` 字段逻辑保持不变。

**两个 OkHttpClient（都从 baseClient.newBuilder() 覆盖，不再吃全局 8 秒）**：

| client | connect | read | write | callTimeout | 用途 |
|---|---|---|---|---|---|
| streamingClient | 15s | **120s** | 30s | **不设** | chat 全部走 SSE |
| simpleClient | 15s | 60s | 30s | 60s | listModels（GET） |

- 流式下 readTimeout 是「两个 chunk 之间的最大沉默」，思考 token 逐块下发，正常远小于
  120s；该值只用于兜底死连接。不设 callTimeout，避免长思考（复杂 prompt 可能数分钟）
  被总时长砍断。
- 8 秒→15 秒 connectTimeout：弱网/握手慢时避免误杀。

**SSE 读取与解析**（解析部分抽成 internal 纯函数/内部类，不绑网络，供 JVM 单测）：

- 执行：`call.execute()` 抛 IOException 仍映射 `NETWORK`；HTTP 非 2xx 时错误体是普通
  JSON，沿用现有 `aiErrorKindFor(code)` + `errorMessage()` 映射，行为不变。
- 2xx 后按行读 `response.body.source().readUtf8Line()`：
  - 空行、`: ` 开头的 SSE 注释/心跳行、`event:`/`id:` 行 → 忽略；
  - `data: [DONE]` → 正常结束；
  - 其余 `data: ` 后 JSON 解析为 chunk：
    - `choices[0].delta.reasoning_content`，取不到再试 `delta.reasoning` → Thinking 累积；
    - `choices[0].delta.content` → Answer 累积；
    - 顶层 `usage` 非空（末包，choices 可能为空数组）→ 记录 token；
    - 顶层 `error.message`（个别网关中途报错）→ 抛 `AiProviderException(SERVER)`。
- 纯函数形状建议：`internal fun parseSseLine(line: String): SseResult`
  （`Data(JsonObject)` / `Done` / `Ignore` 三态）+
  `internal fun JsonObject?.deltaField(name: String): String?`，
  读循环只负责 IO 与累积，便于用构造的 chunk 字符串直接单测。
- **非 SSE 兜底**：若首行 trim 后以 `{` 开头（某些代理/网关会缓冲 SSE，最终整包返回
  一个普通 chat.completion JSON），则按非流式解析一次
  （`choices[0].message.content` / `message.reasoning_content` / 顶层 usage），
  listener 各回调一次。最坏情况退化为「等完再显示」，与现状一致，不会更差。
- 结束时 content 仍为空 → 维持现语义抛 `RESPONSE_PARSE / "Empty response from model"`。
- usage 缺失（端点不认识 stream_options）→ token 全部记 0（现有 `intField()` 已容错）。

**协程取消**：

```kotlin
suspend fun chat(..., listener: AiProgressListener? = null): ChatResult =
    withContext(Dispatchers.IO) {
        val call = streamingClient.newCall(request)
        coroutineContext.job.invokeOnCompletion { if (coroutineContext.job.isCancelled) call.cancel() }
        // execute() / 读循环 catch IOException 时：若当前 job 已取消，
        // 改抛 CancellationException（不映射 NETWORK、不写失败日志）
        // 读循环每个 chunk 前 ensureActive()
    }
```

`chat()` 签名新增带默认值的 `listener` 参数；`listModels()` 不变。

### 5.3 `AiHandler`

- `generate(..., listener: AiProgressListener? = null)`：缓存命中直接返回（无进度）；
  网络路径把 listener 透传给 `client.chat()`；缓存写入、usage 落库、请求日志逻辑不变。
- `generateText()`（rephrase，思考强制关）与 `testConnection()` 改走同一个流式 `chat()`，
  listener 传 null——全应用只有一条 chat 代码路径。
- 两处 `catch (t: Throwable)` 写 FAILED 日志前先判断
  `if (t is CancellationException) throw t`：用户主动取消不算失败，不写日志、不记错误。

### 5.4 `AiPlaylistGenerator`

- `generate(...)` / `generateSerendipity(...)` 增加 `listener: AiProgressListener? = null`
  并透传给 `handler.generate()`；parse/resolve 等本地逻辑不动。

### 5.5 `PlaylistViewModel`

**state 扩字段**（`NlpPlaylistPreviewState`，默认值保证 NLP 对话框零影响）：

```kotlin
val stage: AiGenerationStage = AiGenerationStage.IDLE,
val thinkingText: String = "",

enum class AiGenerationStage { IDLE, THINKING, ANSWERING }
```

**startPreview**：

- 持有 `private var aiGenerationJob: Job?`；每次进入先 `aiGenerationJob?.cancel()` 再启动
  新 job；`resetAiPlaylistPreview()` 也 cancel 并清空 state（含 thinkingText）。
  这样关 sheet、再生成都会真正 `call.cancel()` 断开 SSE。
- 透传 listener：
  - Thinking → `update { it.copy(stage = THINKING, thinkingText = event.full) }`；
  - Answer → `update { it.copy(stage = ANSWERING) }`（正文流本期不在 UI 展示，
    只用来切阶段）。
- 现有 `runCatching { generate() }` 会吞掉 CancellationException 写成错误文案，改为
  `try { ... } catch (e: CancellationException) { throw e } catch (t) { …错误态… }`。
- StateFlow 自带 conflate，chunk 高频更新不会压垮 UI；思考全文最长也就几千字。

### 5.6 `AiMixSheet.GeneratingPhase` UI

替换现有「200dp 居中大转圈」，新结构（Column，间距 12dp）：

1. **状态行**：小号 LoadingIndicator(24dp) + 状态文案
   - THINKING：`ai_thinking_status`（「AI 正在思考…」）
   - ANSWERING / IDLE：复用现有 `ai_mix_generating`（「正在挑选歌曲……」）
2. **思考卡片**（`thinkingText` 非空才出现）：
   - `Surface(secondaryContainer, 圆角 12dp)`，内边距 12dp；
   - header：`ai_thinking_section`（「思考过程」），思考中时右侧微型 LoadingIndicator；
     右侧一个 ExpandMore/ChevronRight IconButton 手动展开/收起；
   - 可折叠区域：展开时为 `Modifier.heightIn(max = 220dp).verticalScroll(...)` 的 Text，
     `bodySmall`、`onSurfaceVariant`；`LaunchedEffect(thinkingText, expanded)` 自动滚到底部；
   - 自动行为：思考中默认展开跟随；收到首个 Answer（stage=ANSWERING）后自动折叠，
     用户手动展开过则尊重手动状态（本地 `userToggled` 标记）；
   - `animateContentSize()` 平滑过渡。
3. **无思考内容**（未开思考 / provider 不返回 / 思考字段为空）：退化为现状的居中
   大转圈 + 文案，视觉不回归。
4. 正文（Title - Artist 候选原始文本）本期**不做流式展示**：带 code fence/格式噪声，
   且本地匹配后的结果才是权威，展示价值低；listener 已保留 Answer 全文，后续要做随时可加。

### 5.7 字符串（只写英文 `values/` 与简体中文 `values-zh-rCN/`）

`strings_ai.xml` 新增：

| key | English | 简体中文 |
|---|---|---|
| `ai_thinking_status` | Thinking… | AI 正在思考… |
| `ai_thinking_section` | Thought process | 思考过程 |
| `ai_thinking_toggle` | Expand or collapse the thought process | 展开或收起思考过程 |

ANSWERING 阶段复用 `ai_mix_generating`，不新增。不新增设置开关（思考展示是默认体验，
无需注册 SettingsRegistry）。

## 6. 明确不做（边界）

- 不把思考内容写入 AiRequestLog 文件、Room、响应缓存；不新增数据库字段/migration。
- 不改 `DescribePlaylistDialog`（离线 NLP，无网络）。
- 不展示正文候选流、不做「停止生成」按钮（关 sheet/再生成即取消，已覆盖主要场景；
  显式停止按钮后续可加）。
- 不改 thinking 开关本身与设置页布局。
- 不为 stream_options 做按 provider 的开关分支：两家内置均支持，OpenAI 生态对未知
  字段普遍忽略；若 CUSTOM 端点返回 4xx，错误文案里会带服务端 message，再按反馈处理。

## 7. 测试

新增 `app/src/test/java/.../data/ai/OpenAiCompatibleClientSseTest.kt`（JUnit5，纯 JVM，
不引 MockWebServer 新依赖；解析逻辑做成吃字符串行序列的纯函数）：

1. `data: ` 前缀解析、空行/`: ping` 心跳/`event:` 行忽略、`data: [DONE]` 正常收尾；
2. reasoning_content 多分片拼接为思考全文，content 多分片拼接为正文；
3. `delta.reasoning` 回退字段可识别；
4. 末包 choices 为空但带 usage → prompt/output/thought token 正确；全程无 usage → 记 0；
5. 非 SSE 整包 JSON 兜底（message.content / message.reasoning_content）；
6. chunk 顶层 error → 抛 AiProviderException；
7. content 全程为空 → RESPONSE_PARSE。

可选（时间盒内）：PlaylistViewModel 用 turbine 验证「生成中 reset → job 取消、
state 无 errorMessage」。

## 8. 验证清单（实现后）

- `.\gradlew.bat :app:testDebugUnitTest`
- `.\gradlew.bat :app:lintDebug`
- `.\gradlew.bat :app:assembleRelease`，装真机/模拟器（arm64-v8a 产物即可）：
  1. 小米 MiMo + 火山方舟各测一次：开思考 → AI 混音与不期而遇均能看到「思考过程」
     实时滚动，随后正常出歌单；不再出现「网络不可达」；
  2. 关思考：界面退化为转圈，生成正常；
  3. 不期而遇「换个说法」正常；
  4. 设置页「测试连接」正常；
  5. 思考中关闭 sheet / 再生成：logcat 确认请求被 cancel（无 FAILED 请求日志、无错误
     toast），再生成能正常完成；
  6. AI 请求日志页：成功记录正文/usage 正常，思考内容不出现；
  7. 断网/填错 key：错误分类与文案与现状一致（网络不可达 / 未授权等）。

## 9. 风险与回退

- **代理缓冲 SSE**：部分梯子/HTTP 代理缓冲流式响应，表现为首包变慢但最终成功；非 SSE
  兜底解析覆盖整包返回场景。
- **长思考超 120s 沉默**：正常逐 token 输出不会沉默 120s；若服务商思考阶段确实长时间
  不推任何 chunk，再调大 readTimeout，不回退非流式。
- **取消即损失已计费 token**：流式下服务商按已生成量计费，无法避免；关 sheet 断开
  符合用户预期，且比现状（后台跑完、用户无感知）更省。
- 回退：单 commit 改动，必要时直接 revert（commit message 拟：
  "Stream AI chat completions and surface thinking in the mix sheet"）。

## 10. 实现记录（2026-09-16）

按 §4/§5 落地，文件与计划一致：

| 文件 | 改动 |
|---|---|
| `data/ai/AiProgressEvent.kt` | 新增：`AiProgressEvent.Thinking/Answer`（`delta` + 累积 `full`）、`AiProgressListener` |
| `data/ai/OpenAiCompatibleClient.kt` | `chat()` 改 SSE；新增 `streamingClient`(read 120s / connect 15s / **不设** callTimeout) 与 `simpleClient`(read 60s / call 60s)；新增 `SseLine` + `parseSseLine` + `ChatStreamAccumulator`；`listModels()` 走 `simpleClient` |
| `data/ai/AiHandler.kt` | `generate(..., listener)` 透传；两处 `catch (Throwable)` 前置 `if (t is CancellationException) throw t` |
| `data/ai/AiPlaylistGenerator.kt` | `generate` / `generateSerendipity` 加 `listener` 透传 |
| `presentation/viewmodel/PlaylistViewModel.kt` | `AiGenerationStage` 枚举 + `stage`/`thinkingText` 字段；`aiGenerationJob` 在再生成/`resetAiPlaylistPreview()` 时 cancel；listener 只写 stage 与思考全文 |
| `presentation/components/AiMixSheet.kt` | `GeneratingPhase(state)`：状态行 + 可折叠思考卡片；无思考内容时退化为原 200dp 大转圈 |
| `strings_ai.xml`（en + zh-rCN） | `ai_thinking_status` / `ai_thinking_section` / `ai_thinking_toggle` |

与计划的偏离（都不改变对外行为）：

1. 解析逻辑做成 `ChatStreamAccumulator`（吃整行、维护累积与 usage），而不是只暴露 `parseSseLine`：
   单测因此能覆盖「多分片拼接 / usage 末包 / 非 SSE 整包兜底 / chunk error / 空正文」整条链路，
   `SseLine` 与 `parseSseLine` 仍作为 `internal` 一并暴露，两类断言都在。
2. `AiProgressEvent.Answer.full` 未接任何 UI（与 §5.6.4 一致），只用于把 stage 切到 ANSWERING。
3. listener 绑在发起请求的 `CoroutineScope` 上并加 `isActive` 守卫：已取消请求的尾包不会把
   `resetAiPlaylistPreview()` 刚清掉的 state 写回（计划未细化，属竞态兜底）。
4. 单测用 JUnit 5 + Truth（仓库主流：82 个文件用 jupiter，38 个用 junit4），共 9 个用例。

验证结果（本机，2026-09-16）：

- `:app:testDebugUnitTest` —— **753 个用例全通过**，其中新增 `OpenAiCompatibleClientSseTest` 9/9。
- `:app:assembleRelease` —— 成功，`pixelplayeross-arm64-v8a-0.4.1-pisces.1-release.apk`（37.6 MB），
  `apksigner verify` → v2 scheme、`CN=pisces312`。
- `:app:lintDebug` —— 仍有 **344 errors**，**全部为存量**（11 个非 en/zh 语种的 `MissingTranslation`
  等，另有 2 条在未改动的 `CarLyricTitleController.kt`）。本次按 §5.7 约定只加 en + zh-rCN，
  新增 3 条 `MissingTranslation` 与既有 122 条同类；改动文件内**零新增** lint 问题。
- 真机项（§8.1–8.7）待接设备后补：思考过程实时滚动、关思考退化、换个说法、测试连接、
  取消后无 FAILED 日志、请求日志不含思考内容。
