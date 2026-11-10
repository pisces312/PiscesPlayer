# androidTest 设备矩阵失败记录

> 用途：记录 `connectedDebugAndroidTest` 在模拟器 / 真机上的失败分布、根因与后续动作。
> 最近一次实测：2026-09-20。
> 相关命令：`.\gradlew.bat :app:connectedDebugAndroidTest`（指定设备时设 `ANDROID_SERIAL`）。
> 报告路径：`app/build/reports/androidTests/connected/debug/index.html`；
> 原始结果：`app/build/outputs/androidTest-results/connected/debug/TEST-<device>.xml`。

## 1. 设备与总览

| 设备 | 形态 | ABI | API | 测试结果 |
|---|---|---|---|---|
| `pixel6` AVD / `emulator-5554` | 模拟器 | **x86_64**（跑 arm64 APK 靠 native bridge） | 34 | 29 tests / **5 failures**（82%） |
| `BKQ-AN80` / 真机 adb-wifi | 真机（荣耀） | **arm64-v8a** | 37 | 29 tests / **4 failures**（86%） |

**两端都没有全绿。** 失败集合不同，不能只看单端结果下结论。

### 套件级对比

| 套件 | 模拟器 x86_64 | 真机 arm64 |
|---|---|---|
| LocalPlaylistDaoTest (4) | PASS | PASS |
| MusicDaoTest (8) | PASS | PASS |
| PlaylistMigrationTest (2) | PASS | PASS |
| AudioMetadataReaderReleaseDateTest (3) | PASS | PASS |
| MusicServiceWorkflowTest (5) | PASS | PASS |
| WavySliderExpressiveTest (2) | PASS | **FAIL** |
| SyncWorkerTest (3) | **FAIL** | PASS |
| BaselineProfileGenerator (1) | 空失败 `name=null` | 空失败 `name=null` |
| StartupBenchmark (1) | 空失败 `name=null` | 空失败 `name=null` |

## 2. 问题 A：SyncWorkerTest 在 x86 模拟器上全挂（已定位）

### 现象

模拟器上 3 条用例全部在 `setUp` 阶段失败：

- `testSyncWorker_success_whenMediaStoreHasData`
- `testSyncWorker_success_whenMediaStoreIsEmpty`
- `incrementalSync_recoversAlbumTracksIndexedWithOldTimestamps`

### 报错

```
java.lang.ExceptionInInitializerError / NoClassDefFoundError: io.mockk.impl.JvmMockKGateway
Caused by: io.mockk.proxy.MockKAgentException:
  MockK could not self-attach a jvmti agent to the current VM.
Caused by: java.io.IOException: Native-bridge agents unsupported:
  /data/user/0/com.pisces312.piscesplayer.debug/cache/mockkjvmtiagent….so
```

### 根因

- 本仓库 ABI 只构建 `arm64-v8a`（见 `AGENTS.md`）。
- `pixel6` AVD 是 **x86_64**，装 arm64 APK 后经 **native bridge** 跑。
- MockK inline 需要把 JVMTI agent `.so` attach 到 ART；**native-bridge 进程不允许 attach agent**。
- 真机 arm64（BKQ-AN80）上 **同一套测试 3/3 通过**，可交叉验证：这是**模拟器 ABI/转译环境问题**，不是 SyncWorker 业务代码回归。

### 与 AGENTS.md 既有记录的关系

`AGENTS.md`「instrumented 测试」段落已写：

> 唯一跑不了的：`SyncWorkerTest` 需要 MockK inline（JVMTI agent），ART 拒绝从 app cache 加载，属既有测试设计问题。

本次在模拟器上看到的具体错误更精确：**`Native-bridge agents unsupported`**（而不只是 app cache 路径）。真机 arm64 上 agent 可以加载，说明「跑不了」的边界应改写为：

- **x86 模拟器 + arm64 APK（native bridge）→ 必挂**；
- **原生 arm64 设备 → MockK inline 可以跑**。

### 后续动作（可选）

| 方案 | 说明 |
|---|---|
| 排除模拟器误报 | 回归以真机 arm64 为准；模拟器结果忽略 SyncWorker |
| 去 MockK | 把 `UserPreferencesRepository` 等 mock 换成 fake / 手写 stub，彻底去掉 JVMTI 依赖 |
| 换 arm64 模拟器镜像 | 若需要「模拟器也绿」，新建 arm64 系统镜像的 AVD（仍可能受 cache agent 策略影响） |

## 3. 问题 B：WavySliderExpressiveTest 在真机上全挂（排查中）

### 现象

真机 BKQ-AN80 上 2 条用例失败，模拟器上相同用例 **通过**：

- `followsValueAfterBackingStateIsReplaced`（~7.0s）
- `reportsNonFiniteValueAsZero`（~6.4s）

### 报错

```
java.lang.IllegalStateException: No compose hierarchies found in the app.
Possible reasons include:
  (1) the Activity that calls setContent did not launch;
  (2) setContent was not called;
  (3) setContent was called before the ComposeTestRule ran.
If setContent is called by the Activity, make sure the Activity is launched
after the ComposeTestRule runs
  at androidx.compose.ui.test.TestOwnerKt.getAllSemanticsNodes
  at …WavySliderExpressiveTest.reportedProgress(WavySliderExpressiveTest.kt:30)
```

测试源码：`app/src/androidTest/java/com/pisces312/piscesplayer/presentation/components/WavySliderExpressiveTest.kt`
（`createComposeRule()` + `setContent { WavySliderExpressive(...) }`，通过 `semanticsLabel = "Playback position"` 找节点。）

### 已观察到的设备侧事实

| 项 | 观察 |
|---|---|
| 设备 | 荣耀 `BKQ-AN80`，API 37，arm64-v8a |
| 多用户 | `0:机主`、`100:平行空间`、`128:分身应用(running)` |
| 动画缩放 | `window/transition/animator` 均为 `1.0`（**未关闭**） |
| logcat | `ComponentActivity` 有 create / Surface / `ACTIVITY_RESUMED`；同时出现 test 包的 `InstrumentationActivityInvoker$EmptyActivity` |
| 耗时 | 两条用例各 ~7s，像在等 Compose hierarchy 超时 |
| 复现 | 模拟器 PASS / 真机 FAIL —— **设备相关**，不是纯逻辑断言失败 |

### 初步判断

更像是 **真机 Compose 测试宿主 Activity 未完成 `setContent` / 层级未注册到 TestOwner**，而不是 slider 算法错。荣耀 HarmonyOS 对测试 Activity、多用户、前台管控较严，常见诱因：

1. 动画/窗口缩放未关，Compose 测试同步超时；
2. `ComponentActivity` 被系统调度/遮挡（平行空间/分身应用用户并存）；
3. 测试 APK 的 `ComponentActivity` 与 app 进程协作异常（instrumentation 目标包 vs test 包）；
4. `createComposeRule()` 默认 Activity 在该 ROM 上首次冷启动过慢。

### 排查清单与阶段结论（2026-09-20）

- [x] 仅重跑 `WavySliderExpressiveTest`（重装 debug + androidTest APK 后 `am instrument`）
- [x] 真机关闭动画缩放后再跑
- [ ] 抓测试时段完整 logcat（全量套件复跑被中断，尚未拿到失败时刻的完整 logcat）
- [ ] 确认 instrumentation 安装到 user 0，且未被平行空间/分身用户干扰
- [ ] 若仍失败，尝试 `createAndroidComposeRule<ComponentActivity>()` 或延长 compose 超时
- [ ] 必要时在另一台更接近 AOSP 的真机上交叉验证

**阶段结论：**

| 条件 | 结果 |
|---|---|
| 全量套件首次跑（动画 1.0，Gradle） | FAIL：`No compose hierarchies found`，两条各 ~7s |
| 仅 WavySlider + 动画 0 + `am instrument` | **OK (2 tests)**，~0.7–32s（冷启动变慢） |
| 仅 WavySlider + 动画 1.0 + `am instrument` | **OK (2 tests)**，~1.2s |
| 仅 WavySlider + 动画 0 + Gradle `connectedDebugAndroidTest` | **BUILD SUCCESSFUL** |

**推断：**

1. **单跑能过** → slider 逻辑本身在真机上可测，不是稳定的产品代码回归。
2. **动画缩放不是充分条件**：关掉动画能过，但恢复 1.0 后单跑也过。
3. 更可疑的是 **全量套件前序用例干扰 / 无线 adb 与首装状态 / 测试宿主 Activity 调度**（荣耀多用户 `128:分身应用` 在跑）。首次全量失败发生在 `MusicServiceWorkflowTest` 等会起 Service/Session 的用例之后。
4. 下次复现应固定：单 adb 连接、user 0、先关动画、**全量套件**抓 logcat，对比 WavySlider 前后进程是否只剩 `*.debug.test` 而目标 app 进程未拉起。

**运维提示：**

- Gradle `connectedDebugAndroidTest` 默认 **跑完卸载** debug/test 包；`am instrument` 前需重新 `adb install`。
- 排查时先 `settings put global *animation_scale 0` 更稳；用户手工测正式版时应恢复为 `1.0`。

## 4. 问题 C：Benchmark 占位类空失败（建议清理）

### 现象

两端均报 2 条失败，XML 形态为：

```xml
<testcase name="null" classname="com.pisces312.piscesplayer.benchmark.StartupBenchmark" time="0.000">
  <failure></failure>
</testcase>
```

无堆栈、无 message、耗时 0。

### 来源

`app/src/androidTest/java/com/pisces312/piscesplayer/benchmark/StartupBenchmark.kt`：

```kotlin
@Ignore("Macrobenchmark scenarios should run from the dedicated benchmark setup, not app androidTest.")
class StartupBenchmark { @Test fun placeholder() = Unit }

@Ignore("Macrobenchmark profile generation should run from the dedicated benchmark setup, not app androidTest.")
class BaselineProfileGenerator { @Test fun placeholder() = Unit }
```

注释已说明：本意是占位，避免 macrobenchmark 混进 app androidTest。但 AGP/runner 仍把 **类级 `@Ignore` 的占位** 记成 `name=null` 的失败，污染成功率。

### 建议

- 优先：**从 `app/src/androidTest` 删除这两个类**（或迁到 `:baselineprofile` / `:benchmark` 模块），让 `connectedDebugAndroidTest` 只反映真实 instrumented 覆盖；
- 若必须保留源文件，至少不要放在会被 `connectedDebugAndroidTest` 扫描的 source set。

## 5. 结论与验收建议

1. **业务回归验收以真机 arm64 为准**（MockK 可用）；x86 模拟器结果不能作为 SyncWorker 的否决依据。
2. **Compose UI 测试目前不能假设「所有真机都能跑」**——WavySlider 在荣耀真机上宿主 hierarchy 起不来，需先修环境或测试写法，再谈断言。
3. **Benchmark 占位类应移出 app androidTest**，否则报表永远带 2 条假失败。
4. 在问题 B/C 未处理前，`connectedDebugAndroidTest` 的绿/红 **不等于** 产品质量绿/红，解读时必须带设备维度。

## 6. 报告与原始产物位置

| 产物 | 路径 |
|---|---|
| HTML 报告 | `app/build/reports/androidTests/connected/debug/` |
| XML（模拟器） | `app/build/outputs/androidTest-results/connected/debug/TEST-pixel6(AVD) - 14.xml` |
| XML（真机） | `app/build/outputs/androidTest-results/connected/debug/TEST-BKQ-AN80 - 17.xml` |
| 逐用例 logcat | `app/build/outputs/androidTest-results/connected/debug/<device>/logcat-*.txt` |
| 运行日志 | 仓库根 `androidTest-run.log`（模拟器）、`androidTest-realdevice.log`（真机） |

> 运行日志与 `build/outputs` 为本地产物，不入库；以本文记录的现象与根因说明为准。
