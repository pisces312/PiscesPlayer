# Media3 1.10.1 → 1.11.0 升级（含运行时回归修复）

> 日期：2026-09-11
> 来源：历史参考实现（原私有派生仓库，已删除）
> 参考文档：`docs/media3-1.11.0-upgrade-plan.md`（历史参考实现，2026-09-06）
> 上游提交：`be37dc36`（版本号）、`d615e5f4`（onConnect 回归修复）

## 1. 结论摘要

1. **升级本身无编译阻塞**：1.11.0 移除的符号在 OSS 源码中零引用。
2. **但存在一处运行时静默回归**：`MediaSession.Callback.onConnect` 的默认实现语义变更，
   导致控制器拿到**空命令集**，表现为「点暂停无效、mini player 不显示、媒体按键无响应」。
   编译器无法发现，必须靠真机/模拟器点击验证。
3. **修复为 1 行**：改用 `AcceptedResultBuilder(session, controller)`（详见 §3）。

## 2. 改动清单

| 文件 | 改动 |
|---|---|
| `gradle/libs.versions.toml` | `media3Session` / `media3Transformer`：`1.10.1` → `1.11.0` |
| `app/src/main/java/com/pisces312/piscesplayer/data/service/MusicService.kt` | `onConnect` 中 `super.onConnect(session, controller)` → `MediaSession.ConnectionResult.AcceptedResultBuilder(session, controller).build()` |

版本号一经提升，`media3-exoplayer`、`media3-exoplayer-midi`、`media3-session`、`media3-ui`
（均引用 `media3Session`）与 `media3-transformer` 一并生效，并透传到 `media3-common` 等传递依赖。

## 3. 根因与修复

### 3.1 触发链路

OSS `MusicService.onConnect` 与上游同构：先取父类默认结果，再在其上追加自定义 session 命令：

```kotlin
val defaultResult = super.onConnect(session, controller)          // ①
...
return MediaSession.ConnectionResult.accept(
    sessionCommandsBuilder.build(),                               // ② 由 defaultResult 派生
    defaultResult.availablePlayerCommands                         // ③ 由 defaultResult 派生
)
```

### 3.2 默认实现在两版本的行为差异

| 版本 | 默认 `onConnect` 返回 |
|---|---|
| 1.10.1 | `AcceptedResultBuilder(session).build()` → 携带 `DEFAULT_PLAYER_COMMANDS` + `DEFAULT_SESSION_AND_LIBRARY_COMMANDS` |
| 1.11.0 | `session.getDeprecatedDefaultConnectionResult()` → `SessionCommands.EMPTY` + `Commands.EMPTY` + `BUNDLE_KEY_NOT_IMPLEMENTED=true` |

### 3.3 为何框架的兼容回退没有兜住

1.11.0 的 `MediaSessionImpl.onConnectOnHandler` 会检查 `BUNDLE_KEY_NOT_IMPLEMENTED` 标记，
命中则回退到 `onConnectAsync` 的默认实现（trusted → 全量命令）。

但应用的 `onConnect` 是用 `ConnectionResult.accept(...)` **重新构造**结果的，该新对象不带
`sessionExtras` 标记。框架据此判定「应用自行实现了 onConnect」，直接采纳 —— 于是
**空的 player 命令 + 空的 session 命令**被下发，`controller.pause()` 退化为 no-op。

> 注意：该问题与 `controller.isTrusted()` 无关。空命令集来自 `super.onConnect()` 的返回值本身。

### 3.4 修复

```kotlin
val defaultResult =
    MediaSession.ConnectionResult.AcceptedResultBuilder(session, controller).build()
```

`AcceptedResultBuilder(MediaSession, ControllerInfo)` 是 **1.11.0 新增**的构造器（已用 `javap`
在 `media3-session` AAR 上核实：1.10.1 只有单参构造），按 `isTrusted()` 返回对应默认命令集，
语义等价于 1.10.1 的 `super.onConnect()`，且不带「未实现」标记，框架会直接采纳。

**该修复不可回移 1.10.1**：2 参构造器在 1.10.1 不存在。

## 4. OSS 侧兼容性核查（逐条实测，非推断）

在本地 Gradle 缓存中对 `androidx.media3` 1.10.1 与 1.11.0 的 AAR 做 `javap` 对比：

| 核查项 | 结论 |
|---|---|
| `AudioProcessor` 接口 | 两版本**完全一致**（含 `flush()` / `flush(StreamMetadata)`）。自定义 `HiResSampleRateCapAudioProcessor`、`SurroundDownmixProcessor` 无需改动 |
| `DefaultRenderersFactory.buildAudioSink(Context, boolean, boolean)` | 签名两版本一致，`DualPlayerEngine` 的 override 不受影响 |
| `DefaultAudioSink.DefaultAudioProcessorChain(AudioProcessor...)` | 两版本一致，`:1023` 的可变参构造照旧可用 |
| `DefaultAudioSink.Builder.setEnableFloatOutput / setEnableAudioOutputPlaybackParameters / setAudioProcessorChain` | 两版本一致 |
| `AudioSink.configure` | 1.10.1 为 `abstract`，1.11.0 变为 `default` 并新增 `configure(AudioSinkConfig)`。项目**未直接实现** `AudioSink`，仅作为 `buildAudioSink()` 返回类型，无破坏 |
| 1.11.0 移除的符号 | `MediaExtractorCompat` / `MotionPhotoMetadata` / `DummyTrackOutput` / `DummyExtractorOutput` / `C.generateAudioSessionIdV21` / `DecoderAudioRenderer.getChannelMapping` / `Mp4Extractor.FLAG_READ_MOTION_PHOTO_METADATA`：源码**零引用**（仅出现在生成的 baseline profile 文本中，非源码） |
| `androidx.media3.exoplayer.MetadataRetriever` | 项目使用的是框架类 `android.media.MediaMetadataRetriever`，不受影响 |
| MediaSession 线程约束收紧 | `MusicService` 中 8 处 `Dispatchers.IO` 块（:996/:1088/:1135/:1646/:1771/:1914/:1991/:2409）逐一核对：仅做仓库查询、scrobble 上报与 URI 授权，**均未触碰 MediaSession getter**，无 `IllegalStateException` 风险 |

**传递依赖提示**：`org.jellyfin.media3:media3-ffmpeg-decoder:1.9.0+1` 自身声明依赖
`media3-exoplayer:1.9.0`，被版本约束提升到 1.11.0。该 AAR 含 arm64-v8a / armeabi-v7a / x86 / x86_64
四个 ABI 的 so。历史参考实现在相同组合下已验证可正常播放。

## 5. 验证清单

- [x] 编译：`:app:assembleDebug`（含 R8 minify + shrinkResources）通过
- [x] 依赖解析：`media3-session` 全部解析到 1.11.0
- [ ] 暂停/播放：点暂停后 AudioTrack 保持 stopped（修复前会 ~84ms 自动恢复）
- [ ] media key：`adb shell input keyevent 127` 暂停 / `126` 播放
- [ ] mini player：底部播放条正常显示，标题/艺人/按钮态同步
- [ ] 启动自动播放：确认仍为既有快照恢复行为
- [ ] 多声道/DSD：`SurroundDownmixProcessor` 输出声道数与解码器事件归属正常
- [ ] 外部控制器：Android Auto / 蓝牙 AVRCP 连接与命令不回归（看 `onConnect` 日志的 `trusted` 与命令位掩码）

## 6. 技术债：后续可迁移到 `onConnectAsync`

### 6.1 两个回调的关系

`onConnectAsync` 是 1.11.0 **新增**的回调（1.10.1 不存在，`javap` 已核实），语义与 `onConnect`
完全相同，只有两点差别：

| 维度 | `onConnect` | `onConnectAsync` |
|---|---|---|
| 返回类型 | `ConnectionResult`（同步） | `ListenableFuture<ConnectionResult>`（可异步） |
| 默认实现 | 返回空命令集 + `BUNDLE_KEY_NOT_IMPLEMENTED` 标记 | `immediateFuture(AcceptedResultBuilder(session, controller).build())`，即按 `isTrusted()` 给默认命令集 |
| 框架处理 | 需自行保证返回非空命令集 | 若 `onConnect` 走的是默认实现，框架自动回退到它 |
| 状态 | `@UnstableApi`，官方声明「移除 `@UnstableApi` 后即废弃」 | 官方**推荐**的实现方式 |

注意 `onConnectAsync` 的默认实现已经内置了 `AcceptedResultBuilder(session, controller)`——
也就是说，**什么都不覆盖**时 1.11.0 的行为是正确的；问题只出在应用覆盖了 `onConnect`
却没有自己补齐默认命令集。

### 6.2 迁移时的两个坑

1. **不能同时覆盖**。官方明确：若两者都覆盖，deprecated 的 `onConnect` **优先**，
   `onConnectAsync` 会被静默跳过。迁移必须**删除** `onConnect` 而不是并存。
2. **`onConnectAsync` 里控制器尚未连接**，`sendCustomCommand` / `setMediaButtonPreferences`
   之类调用会被忽略，需要这类初始化动作要用 `onPostConnect`。

OSS 的 `onConnect` 目前承担三件事：拒绝非授权控制器、给 privileged 控制器追加自定义 session 命令、
`grantArtworkUriPermissions`。迁到 `onConnectAsync` 时这三项都要搬进返回的 future 里
（可以直接 `immediateFuture` 包装保持同步语义）。在 1.10.1 上无法兼容编译，**必须等版本线整体前进后再做**。
