# PixelPlayerOSS 实现审查报告

- **审查范围**：全仓 Kotlin 源码（592 文件 / ~15.2 万行）、构建配置、Room migration、网络/凭据、测试覆盖
- **基线版本**：`0.4.2-pisces.1`（`APP_VERSION_CODE=400201`，Room v11）
- **审查日期**：2026-09-16
- **方法**：静态代码审查 + AGENTS.md 约定合规抽查 + 三路并行深查（架构/数据安全/工程质量）

---

## 总评

整体工程水准在同类 Android 音乐播放器中偏上：Navigation 传递链纪律完整、协程无 GlobalScope、Room migration 幂等护栏齐全、备份/歌词导入有真实防护、云凭据已用 EncryptedSharedPreferences。

主要债务集中在三处：

1. **状态所有权契约只修了 Search 一处**，其余 13 个 `@Singleton` StateHolder 仍是裸 `onCleared()`，历史 Search 回归的同型风险仍在。
2. **凭据落地不一致**：Navidrome/Jellyfin/ListenBrainz 用 ESP，AI API Key 直接明文 DataStore，且 ESP 失败可静默降级明文；网络配置全局放行明文 + 用户 CA。
3. **God Object / God File**：`PlayerViewModel` 5039 行、`LibraryScreen` 3429 行等，回归面过大，是传递链脆弱的根因。

优先修复顺序见文末「修复路线图」。

---

## 发现清单

### Critical

#### C1. `@Singleton` StateHolder 所有权校验几乎全部缺失

| 项 | 内容 |
|---|---|
| 位置 | `PlaybackStateHolder.kt:858`、`LibraryStateHolder.kt:257`、`LyricsStateHolder.kt:396`、`ThemeStateHolder.kt:316`、`SleepTimerStateHolder.kt:284`、`ConnectivityStateHolder.kt:511`、`DailyMixStateHolder.kt:152`、`QueueUndoStateHolder.kt:96`、`PlaylistDismissUndoStateHolder.kt:160` 等 |
| 现状 | 全仓仅 `SearchStateHolder` 实现了 `initialize(owner, scope)` / `onCleared(owner)` 首个 owner 胜出。其余均为 `initialize(scope)` + 无条件 `onCleared()`（cancel job / `scope=null` / 注销系统 callback）。 |
| 风险 | 一旦出现第二个 `PlayerViewModel` 实例（导航栈级 `hiltViewModel()` 残留、新增屏幕漏传），其 `onCleared` 会静默拆掉 activity 级实例的播放/队列/主题/连通性状态——与历史上 Search 被清空的回归同型，无崩溃、无日志。 |
| AGENTS.md | 明确要求「新增 `@Singleton` StateHolder 照抄 SearchStateHolder 所有权校验」，存量未回填。 |
| 修法 | 抽 `OwnedScopeHolder` 基类（或接口 + 默认实现），统一 `initialize(owner, scope)` / `onCleared(owner)`；逐个回填并补测试。 |

#### C2. AI API Key 明文存 DataStore

| 项 | 内容 |
|---|---|
| 位置 | `AiPreferencesRepository.kt:119,171-172` |
| 现状 | OpenAI 兼容 API Key 以普通 string 写入 `Preferences DataStore`，无加密。 |
| 对比 | Navidrome / Jellyfin / ListenBrainz 凭据已用 `EncryptedSharedPreferences` + `MasterKey AES256_GCM`。 |
| 风险 | root / 备份导出 / 侧载可直接读走第三方 API Key。 |
| 修法 | 迁移进 EncryptedSharedPreferences，与云凭据同路径；提供一次性迁移逻辑（读明文 → 写 ESP → 删明文）。 |

#### C3. 备份默认明文导出 API Key

| 项 | 内容 |
|---|---|
| 位置 | `AiProviderConfigModuleHandler.kt:22-23`、`AiPreferencesRepository.kt:297-299` |
| 现状 | 注释已承认 payload 含明文 key；加密是可选项（≥4 位密码）。用户不勾加密时 `.pxpl` 文件即含密钥。 |
| 修法 | 含 AI 模块时强制加密，或导出前单独确认「将包含 API Key」。 |

#### C4. 全局允许明文 + 信任用户 CA

| 项 | 内容 |
|---|---|
| 位置 | `app/src/main/res/xml/network_security_config.xml:3-7`、`AndroidManifest.xml:45` |
| 现状 | `cleartextTrafficPermitted="true"` 覆盖所有域名；信任 `user` 证书锚。 |
| 风险 | 任意域名可走 HTTP；恶意用户 CA 可 MITM 自建云服务（Navidrome/Jellyfin 常部署在 LAN）。 |
| 修法 | base-config 禁明文；仅对 LAN host（复用 `CloudStreamSecurity.isLocalServerHost`）开 domain-config；user 锚仅 debug 变体保留。 |

#### C5. `android.dependency.useConstraints=false` 可能使安全约束失效

| 项 | 内容 |
|---|---|
| 位置 | `gradle.properties:25`；约束声明在 `app/build.gradle.kts:401-412` |
| 现状 | 该 property 关闭 constraints，而 build 脚本用 constraints 钉死 netty 4.2.16 / bouncycastle 1.85 等 CVE 修复版本。 |
| 风险 | 若该开关实际生效，安全钉版本形同虚设。 |
| 修法 | 跑 `./gradlew :app:dependencies --configuration releaseRuntimeClasspath` 验证解析结果；不生效则删 property 或改用 `resolutionStrategy.force`。**验证前不要盲目删。** |

#### C6. `LyricsStateHolder` 启动的 collector 不落 Job

| 项 | 内容 |
|---|---|
| 位置 | `LyricsStateHolder.kt:87-96` |
| 现状 | `initialize` 内 `coroutineScope.launch { stablePlayerState.collect {...} }` 未保存 Job；`onCleared` 只 cancel `loadingJob`。 |
| 风险 | 二次 initialize 会叠加 collector，且无法单独取消。 |
| 修法 | 存 `songObserveJob`，initialize 前 cancel，onCleared 一并 cancel。 |

---

### High

#### H1. EncryptedSharedPreferences 失败回退明文

- **位置**：`NavidromeRepository.kt:104-115`、`JellyfinRepository.kt:86-97`、`ListenBrainzRepository.kt:92-103`
- **现状**：ESP 异常时删库重建，再失败则写 `*_plain` 明文 prefs（含密码/token）。
- **修法**：二次失败直接清空凭据并要求重登，禁止 plain 回退。

#### H2. Navidrome 密码始终落盘

- **位置**：`NavidromeRepository.kt:212` + `NavidromeApiService.kt:631-633`
- **现状**：即使走 TOKEN 模式也保存明文密码；PASSWORD 回退时 `p=enc:<hex>` 可逆编码进 URL query。
- **修法**：TOKEN 成功后不存密码，回退时再要；强制 TOKEN 服务器禁用 PASSWORD 模式。

#### H3. FileProvider 暴露整个 cache/files 根

- **位置**：`app/src/main/res/xml/file_paths.xml:3-8`（`path="."`）
- **现状**：任何写进 `cacheDir`/`filesDir` 的文件，一旦拿到 URI grant 即可被读。
- **修法**：收窄到 `shared_zips/`、`log_export/` 等子目录。

#### H4. `PlayerViewModel` 5039 行 God Object

- **位置**：`presentation/viewmodel/PlayerViewModel.kt`
- **现状**：同时持有 MediaController 生命周期、删除/分享/队列/元数据/UI toast，`onCleared` 手工清理 12 个 Holder。
- **影响**：是传递链脆弱与回归面过大的根因。
- **修法**：Controller 接入与文件操作再拆出 collaborator，VM 只做编排。不必一次拆完，可随功能改动渐进。

#### H5. 存量 Holder 的 `onCleared` 与 `isInitialized` 语义冲突

- **位置**：`ConnectivityStateHolder.kt:511-529`
- **现状**：`onCleared` 无条件 `isInitialized=false` 并注销系统 callback。
- **风险**：非 owner 触发时，owner 侧连通性监控静默失效。
- **修法**：并入 C1 的 owner 协议。

#### H6. R8 keep 过宽

- **位置**：`proguard-rules.pro:27`（`data.model.** { *; }`）、整包 keep `kuromoji` / `pinyin4j` / `io.ktor.server`
- **现状**：model 层全量保留，体积与混淆收益受损；无 `-keep class **` 级灾难。
- **修法**：model 层改 `@Keep` 或 Gson `@SerializedName` 定向 keep。Timber v/d/i 剥离（63-78 行）配置正确，勿动。

#### H7. 依赖版本风险

| 项 | 现状 | 问题 |
|---|---|---|
| Media3 | 1.11.0 | AGENTS.md 仍写 1.10.1，文档漂移 |
| jellyfin media3-ffmpeg | 1.9.0+1 | 落后 2 个 minor，API 兼容风险 |
| material3 | 1.5.0-alpha25 | alpha 进 release |
| glance | 1.3.0-alpha02 | 同上 |
| baselineprofile | 1.5.0-beta01 | beta，可接受 |

- **修法**：更新 AGENTS.md Media3 版本；评估 ffmpeg extension 升级；material3/glance 保持关注 release 通道。

#### H8. `CloudTrackDownloadWorker` 无前台服务

- **位置**：`data/worker/CloudTrackDownloadWorker.kt`
- **现状**：全程无 `setForeground`；大文件下载在后台可能被系统杀掉，无进度通知。
- **修法**：改 `ForegroundInfo` + 进度通知，或约束为充电/前台触发。

---

### Medium

#### M1. `DailyMixScreen` 未使用的 `MainViewModel = hiltViewModel()`

- **位置**：`DailyMixScreen.kt:98`
- **现状**：参数声明后函数体内零引用，却会在 DailyMix 路由上多建一个 Nav 级 MainViewModel。
- **修法**：直接删参数。

#### M2. God file 集群

| 文件 | 行数 |
|---|---|
| `PlayerViewModel.kt` | 5039 |
| `LibraryScreen.kt` | 3429 |
| `SettingsCategoryScreen.kt` | 2932 |
| `SetupScreen.kt` | 2348 |
| `FullPlayerContent.kt` | 2290 |
| `MusicService.kt` | 2250 |
| `AudioBookmarksScreen.kt` | 1977 |
| `QueueBottomSheet.kt` | 1974 |
| `MusicDao.kt` | 1949 |
| `LyricsSheet.kt` | 1900 |

- **修法**：按 tab / 设置 category / AI 生成路径拆 composable 与 VM 方法组。不必一次拆完。

#### M3. 空 catch（5 处）

- `DualPlayerEngine.kt:674,676,1339`（player release 静默吞异常）
- `PlaylistViewModel.kt:1061,1069`（文件删除失败无日志）
- **修法**：至少 `Timber.w`。

#### M4. 强制解包 `!!`（35 处）

- 例：`PlayerViewModel.kt` `sdPath!!`、`ExternalMediaStateHolder.kt:68`、`ConnectivityStateHolder.kt:162`
- **修法**：改 `?: return` / `requireNotNull`。

#### M5. 测试覆盖缺口

- 单测 **123** 文件 / instrumented **7** 文件（数量尚可）。
- **有覆盖**：PlayerViewModel（29 tests）、SearchStateHolder（4）、PlaybackStateHolder（7）、QueueUtils、备份模块、歌词安全。
- **缺口**：
  - 10 个 Room migration 中 **8 个无测试**（仅 5→6 与 10→11 有）
  - `MusicServiceWorkflowTest` 用 `fallbackToDestructiveMigration`，测不到真实 migration
  - 无 `QueueStateHolder` 专项测试（播下一首/删除/undo）
  - `MusicService`（2250 行）无单测
- **修法**：优先补 8 个 migration 测试 + QueueStateHolder 核心路径。

#### M6. `lint.checkReleaseBuilds = false`

- **位置**：`app/build.gradle.kts:206`
- **修法**：至少保留 error 级检查。

#### M7. 签名静默失败

- 无 env / keystore 时 `hasReleaseSigningConfig=false` → 产出未签名 APK 且 BUILD SUCCESSFUL。
- **修法**：构建结束打印醒目 WARNING（AGENTS.md 已文档化，仍建议代码层提醒）。

#### M8. `allowBackup="true"`

- 凭据/DataStore/cloud_downloads 已在 `data_extraction_rules.xml` 排除，但 Room 库、收藏、互动统计仍会进云备份。
- **结论**：符合音乐播放器预期，知悉即可，无需改。

#### M9. AppLogCollector 导出含歌名/路径

- 用户主动导出；release 默认 WARN。可接受。若用户把 priority 调到 VERBOSE，分享 zip 会带 PII——建议导出前提示。

#### M10. `PlaylistViewModel` 多路由默认 `hiltViewModel()`

- 约 15 处屏幕各自拿 Nav 级实例。数据层在 Repository 故非 Critical，但 AI 生成进度/preview state 跨屏共享会不一致。
- **修法**：明确文档「每屏独立」或像 Stats 子屏那样 `getBackStackEntry` 共享。

#### M11. 播放路径 `runBlocking`（降级为 Medium）

- **位置**：`DualPlayerEngine.kt:1076,1100`
- **说明**：代码注释明确「resolveDataSpec runs on ExoPlayer's loading thread, where blocking I/O is allowed」。有预解析缓存（`resolvedUriCache`），仅 cache miss 时 inline resolve。
- **结论**：当前可接受；若未来发现预取卡顿，再改 suspend + 更强预热。

---

### Low

| # | 项 | 结论 |
|---|---|---|
| L1 | TODO/FIXME | **0** 处 |
| L2 | 裸 `Thread(` | **0** 处（仅 Thread.currentThread 取名/handler） |
| L3 | `GlobalScope` | **0** 处 |
| L4 | `@Suppress` | 53 处，多为 DEPRECATION / UNCHECKED_CAST / MissingPermission，可接受 |
| L5 | 生产 `allowMainThreadQueries` | 无（仅 androidTest） |
| L6 | TLS TrustManager bypass | 无 |
| L7 | 硬编码 API Key | 无；MusicBrainz/Deezer/LrcLib 无 key |
| L8 | DEBUG OkHttp 打完整 URL | `AppModule.kt:383-396` Level.HEADERS；Authorization 已 redact，但 Subsonic `t=`/`p=enc:` query 仍在 URL——建议自定义 interceptor 脱敏 |
| L9 | `WidgetUpdateReceiver` scope 未 cancel | 影响极小 |

---

## 正面确认（做得好的部分）

| 项 | 说明 |
|---|---|
| **PlayerViewModel 传递链** | 全仓 `PlayerViewModel = hiltViewModel()` 命中 **0**；`AppNavigation` → `ScreenWrapper` → 各屏均显式传入；Stats 子屏用 parent entry 共享的写法可作范本。 |
| **协程纪律** | 无 GlobalScope；`MusicService` 正确 cancel `serviceScope`；repository/单例 scope 均有 `SupervisorJob`；IO 隔离充分。 |
| **Room migration** | v11，`MIGRATION_1_2`…`10_11` 全注册；`hasColumn` 幂等护栏应对 Auto Backup 列漂移；`exportSchema=true`。 |
| **备份 restore 链** | 文件→manifest→checksum→schema 链式校验 + 快照回滚 + zip slip/zip bomb 防护。 |
| **歌词导入安全** | `LyricsImportSecurity` 拦扩展名/MIME/大小/编码/控制字符/DOCTYPE XXE。 |
| **MediaStore 删除** | API 30+ 走 `contentResolver.delete` + 系统确认 IntentSender。 |
| **Worker 并发** | Sync/下载/Scrobble 均 `enqueueUniqueWork` + attemptId 乐观锁；Scrobble 有指数退避。 |
| **Release 日志** | `ReleaseTree` 仅 WARN+；OkHttp release `Level.NONE` + redact 敏感 header；`AiRequestLogStore.redactUrl` 擦除 key/token/secret。 |
| **设置搜索注册** | SettingsRegistry 64 项，符合 AGENTS.md 约定。 |
| **DEBUG fallbackToDestructiveMigration** | 仅 DEBUG 开启（`AppModule.kt:157-159`），正确。 |

---

## AGENTS.md 约定合规抽查

| 约定 | 实际 | 结论 |
|---|---|---|
| 不写 `PlayerViewModel = hiltViewModel()` 默认值 | 0 命中 | ✅ |
| 新增 StateHolder 照抄 SearchStateHolder owner 校验 | 仅 Search 有，其余 13 个无 | ❌ C1 |
| SettingsRegistry 注册开关 | 64 项 | ✅ |
| DB version 以 `PiscesPlayerDatabase.kt` 为准 | v11，migration 齐全 | ✅ |
| Media3 版本 | 文档 1.10.1 / 实际 1.11.0 | ❌ H7 文档漂移 |
| 目标设备仅主流直板竖屏 | 布局未发现小屏兜底回退 | ✅ |
| 新功能字符串中英双语 | 抽查通过 | ✅ |

---

## 修复路线图

按「风险 × 改动成本」排序。建议每项独立 commit，英文 message。

### 第一梯队（本迭代，风险最高 / 成本可控）

| 序 | 项 | 预估工作量 |
|---|---|---|
| 1 | **C1** 回填 13 个 StateHolder owner 协议（可先抽接口，逐个替换） | 1–2 天 |
| 2 | **C6** LyricsStateHolder 存 observe Job | 0.5 小时 |
| 3 | **M1** 删 DailyMixScreen 死参数 | 10 分钟 |
| 4 | **C5** 验证 constraints 是否生效，失效则改 `resolutionStrategy.force` | 0.5 小时 |
| 5 | **H1** 去掉 ESP 的 plain 回退，失败即清凭据 | 1 小时 |
| 6 | **C4** 收紧 network_security_config（禁明文 + 去 user 锚） | 1–2 小时（需真机回归自建云） |

### 第二梯队（下迭代）

| 序 | 项 | 预估工作量 |
|---|---|---|
| 7 | **C2 + C3** AI API Key 迁移 ESP + 备份强制/确认加密 | 1 天 |
| 8 | **H3** 收窄 FileProvider 路径 | 1 小时 |
| 9 | **H2** Navidrome TOKEN 模式不落盘密码 | 半天 |
| 10 | **M5** 补 8 个 migration 测试 + QueueStateHolder 核心路径 | 1–2 天 |
| 11 | **M3 + M4** 空 catch 加日志、消除高风险 `!!` | 半天 |
| 12 | **H7** 更新 AGENTS.md Media3 版本；评估 ffmpeg 升级 | 1 小时 |

### 第三梯队（长期 / 随功能改动渐进）

| 序 | 项 |
|---|---|
| 13 | **H4 + M2** PlayerViewModel / LibraryScreen / SettingsCategoryScreen 拆分 |
| 14 | **H6** R8 model keep 定向化 |
| 15 | **H8** CloudTrackDownloadWorker 前台服务 |
| 16 | **M6** lint.checkReleaseBuilds 打开 error 级 |
| 17 | **M7** 未签名 APK 构建结束 WARNING |
| 18 | **M10** PlaylistViewModel 跨屏共享语义文档化 |
| 19 | **L8** DEBUG OkHttp URL 中 auth query 脱敏 |

---

## 回归测试建议

修复第一梯队后，至少手工回归：

1. **冷启动 → 播放本地歌曲 → 锁屏 → 解锁**（验证 PlaybackStateHolder 未被误清）
2. **进 AI 混音页 → 返回 → 搜索**（历史 Search 回归场景，确认仍正常）
3. **Navidrome 登录 → 杀进程 → 重开**（验证凭据未降级明文）
4. **断网播放云端已下载曲目**（DualPlayerEngine offline resolve 路径）
5. **`.\gradlew.bat :app:assembleRelease` + `apksigner verify`**（确认签名仍正常）

---

## 附录：审查方法说明

- 静态扫描：Grep `!!` / `@Suppress` / `TODO` / `GlobalScope` / `runBlocking` / `Thread(` / `allowMainThreadQueries` / `hiltViewModel` / `password` / `token` 等
- 文件体量：按行数排序 Top 15
- 测试清单：`app/src/test` 123 文件、`app/src/androidTest` 7 文件
- 架构合规：对照 AGENTS.md 逐条抽查
- 三路并行子审查：架构与状态管理 / 数据层与网络与安全 / 构建测试与代码质量
