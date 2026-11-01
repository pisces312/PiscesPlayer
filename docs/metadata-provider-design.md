# PixelPlayerOSS 自定义元数据 Provider 详细设计（Phase 3）

> 状态：**详细设计，未实施**（Phase 3）。本文不包含任何 PixelPlayerOSS 代码改动。
> 日期：2026-11-01
> 适用仓库：本仓库（GPL-3.0-or-later）
> 关联文档：
> - [`metadata-search-plan.md`](metadata-search-plan.md)：总体计划；本文展开其 §5.10，并把编辑器、校验、测试用例落到可实现粒度。
> - [`metadata-search-status.md`](metadata-search-status.md)：Phase 1 的实际落地情况与接口现状。
> - 《音乐标签》元数据源取证：本地调研文档，未随仓库发布（结论已并入总体计划 §3）。

---

## 0. 一页结论

| 项目 | 结论 |
|---|---|
| 功能定位 | 高级用户的 BYO-API（bring your own API）扩展，不是曲库覆盖方案 |
| 输入 | 用户自建或已获授权的 JSON API：baseUrl + 请求模板 + 响应字段映射 + 认证 + 限速 |
| 输出 | 统一的 `MetadataCandidate`，与 MusicBrainz/Discogs 同屏预览；用户勾选字段后选择“只更新数据库”或“文件 + 数据库” |
| 不内置 | 任何第三方源名称、地址、参数、密钥、签名、Cookie、HTML 抓取、JS 执行、反爬规避 |
| 默认网络策略 | 仅 HTTPS；默认禁止私网；明文 HTTP 与私网访问是两个独立开关，均需用户显式开启 |
| 密钥 | 只存 `EncryptedSharedPreferences`；导出、日志、崩溃信息中永不出现 |
| 调用范围 | 只做用户触发的单曲搜索/详情/封面下载；无后台批量、无持久缓存、无 Provider 市场 |
| 依赖 | Phase 1 的统一候选模型、字段选择、应用目标与 `MetadataEditStateHolder.applyOnlineMetadata` |
| 交付面 | 2 个新界面（Provider 列表、编辑器）+ 1 个测试面板 + 单曲搜索集成；不改 Room schema；新增 Gson DTO 需要 R8 keep 规则 |

### 0.1 关键硬限制

| 限制项 | 值 |
|---|---|
| 单个 Provider 查询参数 | ≤ 10 个 |
| 单个 Provider 自定义请求头 | ≤ 8 个 |
| `maxResults` | 1–50，默认 10 |
| 搜索响应体 | ≤ 1 MiB |
| 详情响应体 | ≤ 2 MiB |
| 封面文件 | ≤ 10 MB；JPEG/PNG/WebP；解码后 ≤ 4096×4096 |
| Provider 总数 | ≤ 50 |
| 导入文件 | ≤ 256 KB |
| 连接/读取/调用超时 | 10 s / 15 s / 20 s |
| 重定向 | ≤ 2 次，且仅同源 |
| 最小请求间隔 | ≥ 250 ms；已知主机有强制下限 |
| JSON 嵌套深度 | ≤ 64 层；token 数 ≤ 200,000 |
| 单个字段值 | ≤ 4,096 字符，超出截断并告警 |

---

## 1. 背景与现状

### 1.1 代码基线（已实测）

| 能力 | 现状 | 证据 |
|---|---|---|
| MusicBrainz 搜索 | 已有。仅 `recording` 搜索，`limit=8`，全局 1.1 s 间隔，Lucene 转义，结果按本地规则调分 | `data/musicbrainz/MusicBrainzApiService.kt:30`、`:48`、`:51`、`:91` |
| MusicBrainz 写入 | 已有，但**只写数据库**，且只填空字段（artist/album/year），同时写 `mb_*` ID | `data/musicbrainz/MusicBrainzRepository.kt:24`；`data/database/MusicDao.kt:1926` |
| 单曲入口 | 资料页弹出 `AlertDialog`，点候选即应用；无预览、无字段勾选、无“文件/数据库”选择 | `presentation/components/SongInfoBottomSheet.kt:922`；`presentation/viewmodel/SongInfoBottomSheetViewModel.kt:134`、`:151` |
| 数据库字段 | 有 `mb_recording_id` / `mb_release_id` / `mb_artist_id` / `release_date`；`title/artist/album/genre` 有 `*_user_edited` 标记；`year`、`albumArtist` 没有对应标记 | `data/database/SongEntity.kt:86`、`:95`、`:99` |
| 密钥存储 | `androidx.security:security-crypto` 已在依赖中，且已有 `EncryptedSharedPreferences` + `MasterKey` 的使用范例 | `app/build.gradle.kts:339`；`data/listenbrainz/ListenBrainzRepository.kt:79` |
| 设置/导航 | 已有 `SettingsCategory`、`SettingsRegistry`、`Screen`、`AppNavigation` 的成熟注册方式 | `presentation/model/SettingsCategory.kt:16`；`presentation/settings/search/SettingsRegistry.kt:636`；`presentation/navigation/Screen.kt:8` |
| 网络栈 | OkHttp 5.5.0、Gson、Retrofit 已在用；无 JsonPath 依赖 | `gradle/libs.versions.toml:80`；`app/build.gradle.kts:327` |
| 本地网络权限 | `INTERNET` 与 `ACCESS_LOCAL_NETWORK` 已声明；Navidrome/Jellyfin 登录已在 SDK ≥ 37 时请求本地网络权限 | `app/src/main/AndroidManifest.xml:5`、`:6`；`presentation/navidrome/auth/NavidromeLoginActivity.kt:189` |

**关键结论：当前 MusicBrainz 应用路径不能直接复用为 Phase 3 的写入路径。** `applyMusicBrainzMatch` 的 SQL 是“只填空”：

```sql
artist_name = CASE
    WHEN TRIM(artist_name) = '' OR LOWER(artist_name) = 'unknown artist' THEN :artist
    ELSE artist_name
END,
...
year = CASE WHEN year <= 0 THEN :year ELSE year END
```

因此“用户勾选后覆盖已有字段”必须依赖 Phase 1 的 `MetadataMergePolicy` + `applyOnlineMetadata`。Phase 3 不另造一套写入逻辑。

### 1.2 尚未存在的能力

- `MetadataCandidate`、`MetadataFieldSelection`、`MetadataApplyTarget`、`MetadataMergePolicy`（Phase 1 计划项，当前代码中没有）。
- Cover Art Archive 客户端、Discogs 适配器。
- 字段级预览、勾选、应用目标选择。
- 任何自定义 Provider 相关代码。

### 1.3 本文相对计划稿的细化

计划稿 §5.10 给出的是示意 schema。本文把它定为实现契约，主要调整：

1. `response` 从扁平字段改为 `items + fields + detail`，便于校验与扩展。
2. `query` 从对象改为有序数组，支持 `omitIfEmpty`，避免把空 `album=` 发给不接受的 API。
3. `capabilities` 不落 JSON，改由字段映射推导，避免“声明了封面但没配路径”的自相矛盾。
4. 增加 `enabled`、`network.allowPrivateNetwork`、`network.allowCleartext`、`search.maxResults`、`score.scale`、`duration.unit`。
5. `auth.secretRef` 改为由 Provider id 派生，导入时重新生成，避免跨设备引用不存在的密钥。
6. 路径语法在点/下标之外增加**单层 `[]` 映射**（如 `artists[].name`），否则大量真实 API 的 artist 数组无法映射；仍不引入完整 JsonPath。
7. 自定义 Provider 不自动与内置源并发搜索，改为用户显式选择，降低限速与条款风险。

---

## 2. 目标与非目标

### 2.1 目标

| 编号 | 目标 |
|---|---|
| G1 | 用户能创建、编辑、启用/禁用、删除多个 JSON API Provider |
| G2 | 用户能用真实单曲做“测试请求”，看到 HTTP 状态、耗时、候选数量、字段映射结果和截断后的原始响应 |
| G3 | 搜索结果进入与内置源一致的候选/预览/勾选/应用流程 |
| G4 | 支持“只更新数据库”和“文件 + 数据库”两种应用目标，行为与 Phase 1 一致 |
| G5 | Provider 配置可导出/导入，文件不含任何密钥，导入有完整校验与冲突处理 |
| G6 | 密钥加密存储；日志、导出、崩溃信息、UI 都不泄露密钥 |
| G7 | 所有网络行为有硬上限：超时、响应大小、JSON 深度、重定向、限速、并发 |

### 2.2 非目标（明确不做）

| 编号 | 非目标 | 原因 |
|---|---|---|
| N1 | HTML 抓取、DOM 解析 | 脆弱、易违反条款、易被反爬对抗 |
| N2 | JavaScript 执行、WebView 抓取 | 等于内置浏览器，风险不可控 |
| N3 | 签名/加密算法、Cookie 注入、证书固定绕过 | 属于规避手段，直接违背项目红线 |
| N4 | 反爬规避、代理轮换、UA 伪装 | 同上 |
| N5 | 内置任何第三方源的地址/参数/示例 | 把项目重新变成“提供规避工具” |
| N6 | Provider 市场、在线 gallery、分享链接索引 | 会形成事实上的预置源分发 |
| N7 | 后台全库爬取、定时同步、批量重试 | 限速与条款风险；也超出单曲工具定位 |
| N8 | 搜索结果持久缓存 | 数据库权利/著作权风险；只在内存中保留当前会话 |
| N9 | POST/PUT、OAuth2、GraphQL（v1） | 先做最小 GET + API key/bearer；后续按需求单独立项 |
| N10 | 歌词、音频流、下载、任意 HTTP 代理 | 职责外，且扩大攻击面 |
| N11 | 新增来源溯源数据库列 | 避免 Room 迁移；来源只在 UI 展示 |
| N12 | 把功能宣传成“解锁网易云/QQ 曲库” | 文案与合规红线 |

### 2.3 成功标准

1. 高级用户能在 10 分钟内配置一个自建 JSON API，并成功搜到一首歌。
2. 普通用户路径完全不受影响；没有 Provider 时，单曲资料页不出现任何新入口。
3. 任一 Provider 失败不会阻塞内置源，也不会让应用崩溃。
4. 导出文件可被另一台设备导入；密钥必须重新输入。
5. 发布版（R8）下 DTO 字段不被混淆，导入导出与持久化正常。

---

## 3. 用户与流程

### 3.1 角色

| 角色 | 描述 | 看到什么 |
|---|---|---|
| 普通用户 | 只用内置源 | 看不到 Provider 入口；单曲页也没有额外按钮 |
| 高级用户 | 有自己的 JSON API（自建服务、公司曲库、已授权商业 API） | Developer → Online metadata providers |
| API 运营方 | 提供 JSON API 的一方 | 只看到 `PixelPlayerOSS/<version>` 的 UA 与用户配置的限速 |

### 3.2 首次配置流程

1. 设置 → Developer → Online metadata providers。
2. 阅读一页说明：该功能只连接“你有权使用的 JSON API”，不内置任何源。
3. 点“Add provider”。
4. 填 Basics：名称、baseUrl、可选 attribution。
5. 选认证：None / API key（header 或 query）/ Bearer；输入密钥。
6. 填搜索请求：path、query 参数模板、可选 headers、maxResults。
7. 填响应映射：items 路径 + id/title/artist/album/... 路径；可选详情路径、封面路径、评分。
8. 点“Test”，输入样例查询，确认能映射出候选。
9. 首次保存时勾选授权承诺；保存并返回列表。
10. 列表卡片显示“Ready”或“Needs key”。

### 3.3 日常搜索流程

1. 打开某首歌的资料页。
2. 如果存在“可用”的自定义 Provider（启用 + 有密钥 + 配置有效），候选区出现来源筛选。
3. 默认只查内置源；用户切换到某个 Provider，或点“Search my providers”。
4. 每个 Provider 独立显示加载/失败/结果；一个失败不影响其他。
5. 用户点某个候选 → 预览（Phase 1 界面），显示字段、来源、封面。
6. 若配置了详情路径，此时才发起详情请求；失败则降级为搜索级字段并提示。
7. 用户勾选字段，选择“只更新数据库”或“文件 + 数据库”，应用。
8. 应用成功后刷新资料页；失败按错误类型提示，可回退到 DB-only。

### 3.4 导入/导出流程

- 列表页右上角 → Export：SAF 创建文件，默认文件名 `pixelplayer-metadata-providers-YYYYMMDD.json`；文件不含密钥。
- 列表页右上角 → Import：SAF 选择 JSON；解析后展示“新增 N / 冲突 M / 无效 K”；用户逐项选择保留/替换/复制；确认后一次性写入。
- 导入完成后，所有 `auth != none` 的 Provider 显示“Needs key”，搜索前必须补密钥。

### 3.5 失败与恢复

| 场景 | 行为 |
|---|---|
| 密钥丢失（恢复出厂/换机/Keystore 重置） | 自动清除无法解密的密文，Provider 标记“Needs key”，不崩溃 |
| DataStore 中 Provider JSON 损坏 | 保留原始字符串，列表页显示“Configuration damaged”，提供“Export raw”与“Reset” |
| 单个 Provider 配置无效 | 该条目标记“Invalid”，不参与搜索；其余正常 |
| 网络/HTTP 错误 | 只影响当前 Provider 的当前请求；可重试 |
| 应用写文件失败 | 提示原因；提供“只更新数据库”作为回退 |

---

## 4. 总体架构

### 4.1 分层

```text
┌─────────────────────────────────────────────────────────────┐
│ Presentation                                                │
│  MetadataProviderListScreen / MetadataProviderEditorScreen  │
│  ProviderTestPanel / MetadataCandidateSheet (Phase 1)       │
│  MetadataProviderListViewModel / MetadataProviderEditorVM   │
└──────────────────────────┬──────────────────────────────────┘
                           │
┌──────────────────────────▼──────────────────────────────────┐
│ Domain / Application                                        │
│  MetadataProviderRepository                                 │
│  ProviderSearchService                                      │
│  ProviderImportExport                                       │
│  MetadataSearchOrchestrator (Phase 1)                       │
│  MetadataEditStateHolder.applyOnlineMetadata (Phase 1)      │
└──────────────────────────┬──────────────────────────────────┘
                           │
┌──────────────────────────▼──────────────────────────────────┐
│ Provider Engine                                             │
│  ProviderValidator       ProviderTemplate                   │
│  JsonPathEvaluator       ProviderRequestFactory             │
│  ProviderUrlPolicy       ProviderHttpClientFactory          │
│  ProviderRateLimiter     ProviderResponseMapper             │
│  ProviderHttpTransport   ProviderErrorMapper                │
└──────────────────────────┬──────────────────────────────────┘
                           │
┌──────────────────────────▼──────────────────────────────────┐
│ Platform                                                    │
│  OkHttp (per-provider client)                               │
│  DataStore<Preferences> ("settings")                        │
│  EncryptedSharedPreferences ("metadata_provider_secrets")   │
└─────────────────────────────────────────────────────────────┘
```

### 4.2 组件职责

| 组件 | 职责 | 不做什么 |
|---|---|---|
| `MetadataProviderRepository` | Provider 定义的读写、启用/禁用、损坏状态、导入导出的事务入口 | 不发网络请求 |
| `ProviderSecretStore` | 密钥的加密读写、清除、可用性检查 | 不把密钥交给导出 |
| `ProviderValidator` | 语法 + 语义 + 安全规则校验，输出 `ValidationIssue` | 不访问网络 |
| `ProviderTemplate` | 变量替换、转义、长度检查 | 不拼接完整 URL |
| `JsonPathEvaluator` | 点/下标/单层 `[]` 的只读取值 | 不执行脚本、不支持过滤表达式 |
| `ProviderRequestFactory` | baseUrl 拼接、path 分段编码、query/header 组装、认证注入 | 不读取响应 |
| `ProviderUrlPolicy` | scheme/主机/端口/重定向/私网/明文检查 | 不负责限速 |
| `ProviderHttpClientFactory` | 每个 Provider 一个 OkHttpClient，DNS 固定、无全局日志拦截器 | 不缓存响应 |
| `ProviderRateLimiter` | 最小间隔、并发上限、429 冷却 | 不做后台重试 |
| `ProviderHttpTransport` | 发起请求、读取有上限的响应、取消 | 不解析业务字段 |
| `ProviderResponseMapper` | JSON → `MetadataCandidate`，类型转换、缺失字段告警 | 不写数据库 |
| `ProviderSearchService` | 串起上述组件，输出 `ProviderSearchOutcome` | 不直接改 UI 状态 |
| `MetadataSearchOrchestrator` | 合并内置/自定义结果、去重、排序、每源状态 | 不并发调用未启用的源 |

### 4.3 数据流

**搜索**

```text
Song/查询表单
  → MetadataQuery
  → ProviderSearchService.search(provider, query)
  → 校验配置 + 检查密钥
  → ProviderRateLimiter.acquire()
  → ProviderRequestFactory.build()
  → ProviderUrlPolicy.validate()
  → ProviderHttpTransport.execute()
  → 响应大小/类型/JSON 深度检查
  → JsonPathEvaluator + ProviderResponseMapper
  → List<MetadataCandidate> + warnings
  → MetadataSearchOrchestrator 合并/去重/排序
  → UI
```

**详情**

```text
用户选中候选
  → 若 provider.response.detail == null：直接用搜索字段
  → 否则 ProviderSearchService.detail(provider, candidate)
  → 详情请求（同一认证/限速/主机策略）
  → 详情字段覆盖搜索字段，缺失回退
  → 预览
```

**应用**

```text
用户勾选字段 + 选择目标
  → MetadataFieldSelection + MetadataApplyTarget
  → MetadataEditStateHolder.applyOnlineMetadata(...)
  → DB_ONLY: Room + *_user_edited
  → FILE_AND_DB: SongMetadataEditor → MediaStore/Room → 封面
```

### 4.4 与 Phase 1/2 的接口契约

Phase 3 依赖以下 Phase 1 交付物；如果 Phase 1 尚未合并，**先落这两个纯 Kotlin 模型**，再接 Provider 引擎：

```kotlin
// data/metadata/MetadataQuery.kt
data class MetadataQuery(
    val title: String,
    val artist: String,
    val album: String? = null,
    val albumArtist: String? = null,
    val isrc: String? = null,
    val durationMs: Long? = null,
    val filename: String? = null,
    val rawQuery: String? = null,
    val limit: Int = 10
)

// data/metadata/MetadataCandidate.kt
enum class MetadataSourceKind { MUSICBRAINZ, DISCOGS, CUSTOM }

data class MetadataSource(
    val kind: MetadataSourceKind,
    val displayName: String,
    val sourceUrl: String? = null
)

data class MetadataCandidateFields(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val albumArtist: String? = null,
    val year: Int? = null,
    val releaseDate: String? = null,
    val trackNumber: Int? = null,
    val discNumber: Int? = null,
    val durationMs: Long? = null,
    val isrc: String? = null
)

data class MetadataCandidate(
    val source: MetadataSource,
    val sourceItemId: String,
    val providerId: String? = null,
    val fields: MetadataCandidateFields,
    val coverUrl: String? = null,
    val rawScore: Int? = null,
    val adjustedScore: Int = 0,
    val disambiguation: String? = null
)

enum class MetadataApplyTarget { DB_ONLY, FILE_AND_DB }
```

Phase 1 的 `MetadataEditStateHolder` 需要提供：

```kotlin
suspend fun applyOnlineMetadata(
    song: Song,
    selection: MetadataFieldSelection,
    target: MetadataApplyTarget,
    coverArtUpdate: CoverArtUpdate? = null
): MetadataEditResult
```

Phase 3 不新增 Room 列，不改 `applyMusicBrainzMatch`；自定义来源**不写** `mb_recording_id` / `mb_release_id` / `mb_artist_id`，避免污染 MusicBrainz 身份字段。

---

## 5. 数据模型

### 5.1 两层模型

| 层 | 类型 | 用途 |
|---|---|---|
| Wire/DTO | `ProviderDefinitionDto` 等 Gson 类 | 持久化、导入导出、编辑器草稿 |
| Domain | `MetadataProvider` | 已校验的运行时对象：`HttpUrl`、编译后的模板、解析后的路径、有效限速 |
| Runtime | `MetadataProviderRuntime` | `hasSecret`、`lastTest`（会话内）、`usable` 派生状态 |

**为什么要两层**：DTO 允许字段缺失/非法（用户手编、旧版本、导入文件），Domain 只接受通过校验的值，网络层永远看不到未校验的字符串。导入校验和运行时校验共用同一个 `ProviderValidator`。

### 5.2 Wire DTO（Gson）

所有字段用 `@SerializedName` 显式命名，并在 `proguard-rules.pro` 中 keep 整个 schema 包；仓库已有因字段被 R8 重命名导致 Gson 全空值的先例（`app/proguard-rules.pro:35`）。

> **Gson + Kotlin 的关键注意**：Gson 通过 Unsafe 或无参构造创建对象，**缺字段时不保证使用 Kotlin 默认值**；即使属性声明为非空 `String`/`Int`，反序列化后也可能是 `null`/`0`。因此 DTO 只能当作“不可信传输形状”：校验器必须逐项做 null/类型检查，任何缺失都转成 `ValidationIssue`。实现时推荐把 DTO 字段全部声明为可空（或用 `JsonObject` 手动读取）；下面代码块里的 `= ""`/`= 1` 只是文档可读性，不是运行时保证。

```kotlin
package com.lostf1sh.pixelplayeross.data.metadata.provider.schema

data class ProviderDefinitionDto(
    @SerializedName("schemaVersion") val schemaVersion: Int = 1,
    @SerializedName("id") val id: String = "",
    @SerializedName("name") val name: String = "",
    @SerializedName("enabled") val enabled: Boolean = true,
    @SerializedName("baseUrl") val baseUrl: String = "",
    @SerializedName("auth") val auth: AuthDto = AuthDto(),
    @SerializedName("search") val search: SearchDto = SearchDto(),
    @SerializedName("response") val response: ResponseDto = ResponseDto(),
    @SerializedName("rateLimit") val rateLimit: RateLimitDto = RateLimitDto(),
    @SerializedName("network") val network: NetworkDto = NetworkDto(),
    @SerializedName("attribution") val attribution: String? = null
)

data class AuthDto(
    @SerializedName("type") val type: String = "none",   // none | apiKey | bearer
    @SerializedName("in") val location: String? = null,  // header | query（仅 apiKey）
    @SerializedName("name") val name: String? = null
)

data class SearchDto(
    @SerializedName("method") val method: String = "GET",
    @SerializedName("path") val path: String = "",
    @SerializedName("query") val query: List<QueryParamDto> = emptyList(),
    @SerializedName("headers") val headers: Map<String, String> = emptyMap(),
    @SerializedName("maxResults") val maxResults: Int = 10
)

data class QueryParamDto(
    @SerializedName("name") val name: String = "",
    @SerializedName("template") val template: String = "",
    @SerializedName("omitIfEmpty") val omitIfEmpty: Boolean = false
)

data class ResponseDto(
    @SerializedName("items") val items: String = "",
    @SerializedName("fields") val fields: FieldMappingDto = FieldMappingDto(),
    @SerializedName("detail") val detail: DetailDto? = null
)

data class FieldMappingDto(
    @SerializedName("id") val id: String? = null,
    @SerializedName("title") val title: String? = null,
    @SerializedName("artist") val artist: String? = null,
    @SerializedName("album") val album: String? = null,
    @SerializedName("albumArtist") val albumArtist: String? = null,
    @SerializedName("year") val year: String? = null,
    @SerializedName("trackNumber") val trackNumber: String? = null,
    @SerializedName("discNumber") val discNumber: String? = null,
    @SerializedName("duration") val duration: DurationDto? = null,
    @SerializedName("isrc") val isrc: String? = null,
    @SerializedName("coverUrl") val coverUrl: String? = null,
    @SerializedName("score") val score: ScoreDto? = null
)

data class DurationDto(
    @SerializedName("path") val path: String = "",
    @SerializedName("unit") val unit: String = "ms"      // ms | s | mm:ss
)

data class ScoreDto(
    @SerializedName("path") val path: String = "",
    @SerializedName("scale") val scale: String = "0-100" // 0-100 | 0-1
)

data class DetailDto(
    @SerializedName("path") val path: String = "",       // 必须含 {id}
    @SerializedName("fields") val fields: FieldMappingDto = FieldMappingDto()
)

data class RateLimitDto(
    @SerializedName("minIntervalMs") val minIntervalMs: Int = 1100,
    @SerializedName("maxConcurrent") val maxConcurrent: Int = 1
)

data class NetworkDto(
    @SerializedName("allowPrivateNetwork") val allowPrivateNetwork: Boolean = false,
    @SerializedName("allowCleartext") val allowCleartext: Boolean = false
)
```

**持久化信封**

```kotlin
data class ProviderStoreEnvelope(
    @SerializedName("schemaVersion") val schemaVersion: Int = 1,
    @SerializedName("providers") val providers: List<ProviderDefinitionDto> = emptyList()
)
```

### 5.3 Domain 模型

```kotlin
data class MetadataProvider(
    val definition: ProviderDefinitionDto,
    val baseUrl: HttpUrl,
    val auth: AuthSpec,
    val search: CompiledSearch,
    val response: CompiledResponse,
    val rateLimit: EffectiveRateLimit,
    val network: NetworkSpec
) {
    val id: String get() = definition.id
    val name: String get() = definition.name.trim()
    val enabled: Boolean get() = definition.enabled
    val hasDetail: Boolean get() = response.detail != null
    val hasCover: Boolean get() = response.fields.coverUrl != null
}

sealed interface AuthSpec {
    data object None : AuthSpec
    data class ApiKeyHeader(val headerName: String, val secretRef: String) : AuthSpec
    data class ApiKeyQuery(val paramName: String, val secretRef: String) : AuthSpec
    data class Bearer(val secretRef: String) : AuthSpec
}

data class CompiledSearch(
    // 每个 path segment 编译成一个模板；纯静态段就是没有变量的模板。
    // 这样既支持 /search，也支持 /{title}-{artist}。
    val pathSegments: List<CompiledTemplate>,
    val query: List<CompiledQueryParam>,
    val headers: Map<String, String>,
    val maxResults: Int
)

data class CompiledQueryParam(
    val name: String,
    val template: CompiledTemplate,
    val omitIfEmpty: Boolean
)

data class EffectiveRateLimit(
    val minIntervalMs: Int,
    val maxConcurrent: Int,
    val enforcedByHost: Boolean,
    val reason: String?
)
```

`secretRef` 由 id 派生：`"mp_" + id`。DTO 中不存它；导出文件也不含它。这样同一份配置导入新设备后不会指向旧密文。

### 5.4 枚举

| 枚举 | 值 | 默认 |
|---|---|---|
| `AuthType` | `NONE`, `API_KEY`, `BEARER` | `NONE` |
| `AuthLocation` | `HEADER`, `QUERY` | `HEADER`（apiKey 时必填） |
| `DurationUnit` | `MS`, `SECONDS`, `MM_SS` | `MS` |
| `ScoreScale` | `ZERO_TO_HUNDRED`, `ZERO_TO_ONE` | `ZERO_TO_HUNDRED` |
| `TemplateVariable` | `title`, `artist`, `album`, `albumArtist`, `isrc`, `duration`, `filename`, `rawQuery`, `limit`, `id` | — |
| `ValidationSeverity` | `ERROR`, `WARNING` | — |

### 5.5 字段约束总表

| 字段 | 类型 | 必填 | 约束 |
|---|---|---|---|
| `schemaVersion` | int | 是 | 必须为 1 |
| `id` | string | 是 | `^usr_[0-9a-f]{32}$`；新建时生成 |
| `name` | string | 是 | trim 后 1–60 字符；无控制字符 |
| `enabled` | bool | 否 | 默认 true |
| `baseUrl` | string | 是 | `https`；无 userinfo/query/fragment；≤ 300 字符；末尾 `/` 归一化去掉 |
| `auth.type` | enum | 是 | none/apiKey/bearer |
| `auth.in` | enum | apiKey 时必填 | header/query |
| `auth.name` | string | apiKey 时必填 | header: `^[A-Za-z0-9-]{1,64}$`；query: `^[A-Za-z0-9_.-]{1,64}$`；拒绝危险头 |
| `search.method` | string | 否 | 仅 `GET` |
| `search.path` | string | 是 | 以 `/` 开头；≤ 300 字符；无 `?`/`#`/`://`/`..`；段数 1–16 |
| `search.query` | array | 否 | ≤ 10 项；name 合法；template ≤ 200 字符；变量必须已知 |
| `search.headers` | map | 否 | ≤ 8 项；name 合法且不在拒绝表；value ≤ 500 字符；无 CR/LF；不支持变量 |
| `search.maxResults` | int | 否 | 1–50，默认 10 |
| `response.items` | string | 是 | 合法路径；≤ 120 字符；深度 ≤ 8 |
| `response.fields.id` | string | 是 | 合法路径 |
| `response.fields.title` | string | 是 | 合法路径 |
| `response.fields.artist` | string | 否 | 合法路径；支持 `[]` |
| 其他字段路径 | string | 否 | 合法路径；≤ 120 字符 |
| `duration.path` | string | 与 unit 同时 | 合法路径 |
| `duration.unit` | enum | 与 path 同时 | ms/s/mm:ss |
| `score.path` | string | 否 | 合法路径 |
| `score.scale` | enum | 默认 0-100 | 0-100/0-1 |
| `detail.path` | string | 可选 | 必须含 `{id}`；≤ 300 字符 |
| `detail.fields` | object | 可选 | 至少一个字段；不能覆盖 `id` |
| `rateLimit.minIntervalMs` | int | 否 | 250–60000，默认 1100 |
| `rateLimit.maxConcurrent` | int | 否 | 1–2，默认 1 |
| `network.allowPrivateNetwork` | bool | 否 | 默认 false |
| `network.allowCleartext` | bool | 否 | 默认 false；为 true 时必须同时 allowPrivateNetwork=true |
| `attribution` | string | 否 | ≤ 200 字符；纯文本 |

---

## 6. Provider JSON Schema v1

### 6.1 完整示例

```json
{
  "schemaVersion": 1,
  "id": "usr_9f2c4a1b7d3e4f608a5b1c2d3e4f5a6b",
  "name": "My Music API",
  "enabled": true,
  "baseUrl": "https://api.example.com/v1",
  "auth": {
    "type": "apiKey",
    "in": "header",
    "name": "X-API-Key"
  },
  "search": {
    "method": "GET",
    "path": "/search",
    "query": [
      { "name": "q", "template": "{title} {artist}", "omitIfEmpty": false },
      { "name": "album", "template": "{album}", "omitIfEmpty": true },
      { "name": "limit", "template": "{limit}", "omitIfEmpty": false }
    ],
    "headers": {
      "Accept": "application/json",
      "Accept-Language": "en"
    },
    "maxResults": 10
  },
  "response": {
    "items": "data.results",
    "fields": {
      "id": "id",
      "title": "title",
      "artist": "artists[].name",
      "album": "album.title",
      "albumArtist": "album.artist.name",
      "year": "releaseDate",
      "trackNumber": "track.number",
      "discNumber": "disc.number",
      "duration": { "path": "duration", "unit": "ms" },
      "isrc": "isrc",
      "coverUrl": "cover.url",
      "score": { "path": "score", "scale": "0-100" }
    },
    "detail": {
      "path": "/tracks/{id}",
      "fields": {
        "album": "album.title",
        "year": "album.releaseDate",
        "coverUrl": "album.cover.url"
      }
    }
  },
  "rateLimit": {
    "minIntervalMs": 1100,
    "maxConcurrent": 1
  },
  "network": {
    "allowPrivateNetwork": false,
    "allowCleartext": false
  },
  "attribution": "My Music API"
}
```

最终搜索 URL：

```text
https://api.example.com/v1/search?q=晴天%20周杰伦&limit=10
```

最终详情 URL：

```text
https://api.example.com/v1/tracks/<id>
```

### 6.2 语义

**baseUrl 与 path 的拼接**

- `baseUrl` 可以带路径前缀（如 `/v1`、`/api`），但不能带 query 或 fragment。
- `search.path` 与 `detail.path` 都追加在 baseUrl 路径之后。
- 静态段与变量段分开处理：变量值用 `addPathSegment` 编码，值中的 `/` 会变成 `%2F`，不会被当作路径分隔符。
- 禁止 `.` / `..` 段；禁止双斜杠。

**认证**

| type | in | 结果 |
|---|---|---|
| none | — | 不加任何认证 |
| apiKey | header | `name: <secret>` |
| apiKey | query | 查询串追加 `name=<secret>` |
| bearer | — | `Authorization: Bearer <secret>` |

- 密钥为空时，Provider 不可用于搜索，返回 `MissingSecret`。
- 认证 query 参数追加在用户参数之后；如果同名，校验阶段报错。
- 封面请求**不携带任何认证**，避免把密钥发给 CDN。

**请求头**

- 应用固定发送：
  - `User-Agent: PixelPlayerOSS/<version> (custom metadata provider)`
  - `Accept: application/json`（用户可用自定义 `Accept` 覆盖）
- 用户自定义头只允许静态值（v1），不支持变量。
- 拒绝表（大小写不敏感）：`Host`、`Content-Length`、`Transfer-Encoding`、`Connection`、`Keep-Alive`、`Proxy-*`、`Cookie`、`Cookie2`、`Set-Cookie`、`Authorization`、`Proxy-Authorization`、`WWW-Authenticate`、`TE`、`Trailer`、`Upgrade`、`Expect`、`User-Agent`。
- 认证头由 `auth` 统一注入，不允许用自定义头再写一份。

**响应**

- `items` 必须解析为数组；每个元素是一个对象。
- `fields` 中 `id` 与 `title` 必填；`artist` 强烈建议填（缺失时 UI 显示 Unknown Artist 并告警）。
- `detail.fields` 只允许覆盖搜索映射中已有的字段；不允许改 `id`。
- `detail` 请求只在用户选中候选后发起；失败不影响使用搜索级字段。

### 6.3 版本策略

- 当前只有 `schemaVersion: 1`。
- 读取时：
  - `1`：正常。
  - 缺失：按 1 处理，但给出 WARNING。
  - `>1`：拒绝该文件/条目，提示“此配置由更新版本创建”。
  - `0` 或负数：拒绝。
- 未来新增字段：小版本内允许忽略未知字段并给出 WARNING；不改变已有字段语义。
- 不提供“自动升级并覆盖用户文件”；迁移只在内存中做，保存时写当前版本。
- 导出时始终写 `schemaVersion: 1` 与 `format: "pixelplayer.metadata-providers"`。

---

## 7. 持久化与密钥

### 7.1 DataStore 布局

复用现有单例 DataStore（`Context.dataStore`，文件名 `settings`，见 `data/preferences/UserPreferencesRepository.kt:39` 与 `di/AppModule.kt:115`），新增一个键：

```kotlin
val METADATA_PROVIDERS_V1 = stringPreferencesKey("metadata_providers_v1")
```

值为 `ProviderStoreEnvelope` 的 JSON 字符串。

**为什么一个键，而不是每个 Provider 一个键：**

- Provider 数量少（≤ 50），整包只有几 KB 到几十 KB。
- 导入/导出/批量删除需要一次性原子写入；一个 `dataStore.edit` 即可。
- 顺序稳定，列表无需额外排序键。
- 避免键名拼接与散落清理。

**写入策略**

- 所有写操作走 `dataStore.edit { }`，DataStore 自身串行化。
- 读取时先解析信封，再逐条校验；无效条目单独暴露，不静默丢弃。
- 单次写入前检查序列化后大小 ≤ 256 KB；超限拒绝保存并提示。

### 7.2 Repository API

```kotlin
@Singleton
class MetadataProviderRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    private val secretStore: ProviderSecretStore,
    private val validator: ProviderValidator,
    private val gson: Gson
) {
    data class ProviderListItem(
        val provider: MetadataProvider,
        val hasSecret: Boolean,
        val issues: List<ValidationIssue>
    )

    data class StoreState(
        val providers: List<ProviderListItem> = emptyList(),
        val invalid: List<InvalidProvider> = emptyList(),
        val corruptRaw: String? = null
    )

    val state: Flow<StoreState>

    suspend fun snapshot(): StoreState

    suspend fun save(
        definition: ProviderDefinitionDto,
        secret: SecretUpdate = SecretUpdate.Keep
    ): SaveResult

    suspend fun setEnabled(id: String, enabled: Boolean)
    suspend fun delete(id: String)
    suspend fun deleteAll()

    suspend fun exportDocument(): String
    suspend fun importDocument(
        json: String,
        conflictPolicy: (ProviderDefinitionDto) -> ImportConflictChoice
    ): ImportResult
}

sealed interface SecretUpdate {
    data object Keep : SecretUpdate
    data class Replace(val value: String) : SecretUpdate
    data object Clear : SecretUpdate
}
```

- `save` 的 `SecretUpdate` 语义：
  - `Keep`：不动已有密钥；新建时若 `auth != none` 且没有密钥，允许保存但标记 “Needs key”。
  - `Replace`：先写密文，再写定义；任何一步失败则整体失败，不留下“定义已更新但密钥是旧的”状态。
  - `Clear`：删除密文；`auth != none` 时标记 “Needs key”。
- 删除 Provider 时先删定义再删密文；即使密文删除失败，也不会造成密钥被其他 Provider 使用（`secretRef` 含 id）。
- `state` 用 `distinctUntilChanged()`；解析失败保留 `corruptRaw`。

### 7.3 密钥存储

复用仓库已有模式（`data/listenbrainz/ListenBrainzRepository.kt:79`）：

```kotlin
private fun createEncryptedPrefs(): SharedPreferences {
    val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()
    return EncryptedSharedPreferences.create(
        context,
        "metadata_provider_secrets",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )
}
```

| 项目 | 规则 |
|---|---|
| 文件名 | `metadata_provider_secrets` |
| 键 | `mp_<providerId>` |
| 值 | 原始密钥字符串 |
| 读取失败 | 捕获 `GeneralSecurityException`/`IOException`；删除整个文件；标记所有 Provider “Needs key” |
| 降级 | **不**回退到明文 `SharedPreferences`（与 ListenBrainz 的明文兜底不同，这里宁可不可用） |
| 备份 | 不参与应用内备份；Android 自动备份可能带走 DataStore 定义，但 Keystore 主密钥不会，恢复后密文不可解，按“丢失密钥”处理 |
| 日志 | 永不打印密钥、含认证参数的完整 URL、请求头 |

**为什么不把密钥直接写进 DataStore：** DataStore 是明文 XML/Preferences，且会被应用内备份与调试工具读取；密钥必须单独加密。

### 7.4 损坏恢复

| 状态 | UI | 操作 |
|---|---|---|
| 信封 JSON 解析失败 | 顶部横幅 “Provider configuration is damaged” | “Export raw” 复制原始字符串；“Reset providers” 二次确认后清空（同时清密钥） |
| 单条 DTO 校验失败 | 卡片标 “Invalid”，展开显示 issues | 编辑修复；或删除 |
| 单条 DTO JSON 结构缺失 | 同上 | 同上 |
| 密钥不可读 | 卡片标 “Needs key” | 重新输入密钥 |

**不自动删除任何用户数据**；损坏时保留原文，便于用户手工恢复。

### 7.5 备份/恢复行为

- Phase 3 **不**把 Provider 定义接入 `BackupSection`（应用内全局备份）。理由：
  - 密钥不随备份走，恢复后仍要重新输入；
  - Provider 是高级设置，避免扩大全局备份的兼容面；
  - 导入/导出已经提供 SAF 路径。
- 单曲元数据、AI 设置等现有备份逻辑不受影响。
- 若后续要接入，只备份定义，不备份密钥，并复用现有的 `PreferenceBackupEntry` 类型化模式（`data/preferences/AiPreferencesRepository.kt:301`、`:321`）。

### 7.6 并发与原子性

- 读取：`dataStore.data.first()` 或 `state` Flow。
- 写入：每次操作一个 `edit`；导入先构建完整新列表并校验，再一次性写入。
- 编辑页保存：先校验 → 写密钥（如需要）→ 写定义；密钥写成功但定义写失败时，回滚删除刚写的新密钥（仅当之前没有旧密钥）。
- 同一 Provider 的编辑与列表开关可能并发：以 DataStore 中的最新信封为准，保存时按 id 合并，不覆盖其他条目。

---

## 8. 网络执行管线

### 8.1 阶段

```text
1. 配置校验（从 Domain 对象读取，理论上已通过）
2. 密钥检查
3. 限速/并发获取
4. URL 构建
5. 主机策略校验 + DNS 解析与固定
6. 发请求（手动重定向）
7. 状态码与响应头检查
8. 有上限读取响应体
9. Content-Type 检查
10. JSON 深度/Token 检查
11. 字段映射
12. 释放限速许可
```

任何阶段失败都转换为 `ProviderError`，不把原始异常直接抛给 UI。

### 8.2 URL 构建

```kotlin
fun buildSearchUrl(provider: MetadataProvider, query: MetadataQuery): HttpUrl
```

规则：

1. 以 `provider.baseUrl` 为起点；baseUrl 的路径段保留。
2. 将 `search.path` 按 `/` 拆段；空段忽略；`.`/`..` 在校验期已拒绝。
3. 每一段：
   - 静态段 → `addPathSegment(decodedStaticValue)`；
   - 变量段 → 渲染变量 → `addPathSegment(renderedValue)`。
4. 查询参数按用户顺序追加；`omitIfEmpty=true` 且渲染结果为空时跳过。
5. 认证 query 参数最后追加。
6. 不自动添加 `limit` 参数；只有模板里写了 `{limit}` 才出现。
7. 最终 URL 长度 > 4096 字符 → `UrlTooLong`。

**示例**

| baseUrl | path | 结果 |
|---|---|---|
| `https://api.example.com` | `/search` | `https://api.example.com/search` |
| `https://api.example.com/v1` | `/search` | `https://api.example.com/v1/search` |
| `https://api.example.com/v1/` | `/tracks/{id}` | `https://api.example.com/v1/tracks/<id>` |

### 8.3 认证

| 类型 | 注入位置 | 重定向 | 封面 |
|---|---|---|---|
| apiKey header | 请求头 | 同源保留 | 不携带 |
| apiKey query | 查询串 | 同源保留 | 不携带 |
| bearer | `Authorization` 头 | 同源保留 | 不携带 |

- 认证头/参数在日志中按名称脱敏；含 query 认证的 URL 在错误信息里只显示 host + path。
- `401`/`403` 统一提示“Authentication failed”，不区分是密钥错误还是权限不足，避免信息泄露。
- 认证只由 `ProviderRequestFactory` 注入，**不**放 OkHttp interceptor。封面请求复用同一个 per-provider client（共享 DNS 固定与主机策略），但由工厂构造一个不注入认证的请求，因此不会把密钥发给 CDN。

### 8.4 请求头与查询

- 默认头：`User-Agent`、`Accept: application/json`、`Accept-Encoding: gzip`（OkHttp 自动）。
- 用户头覆盖 `Accept` 时允许；其余危险头拒绝。
- 查询参数名不允许重复（含认证参数）。
- 参数值最大 2,048 字符；整个查询串编码后最大 8,192 字符。

### 8.5 重定向策略

- `followRedirects(false)`、`followSslRedirects(false)`。
- 手动最多 2 跳。
- 只允许同 scheme + host + port；跨源重定向一律 `RedirectBlocked`。
- 每一跳重新执行 `ProviderUrlPolicy`（防止通过 DNS 或 URL 变形绕过）。
- 不自动把 POST 改 GET（v1 只有 GET）。
- 301/302/303/307/308 都按上述规则处理；没有 `Location` 头则按错误处理。

**为什么同源限制：** 跨源重定向会把 API key 带到攻击者主机，也会被用作 SSRF 跳板。大多数合法 API 不会跨源重定向。

### 8.6 主机与 SSRF 策略

`ProviderUrlPolicy` 对**每一次**请求（搜索、详情、封面）执行：

| 地址类别 | 默认 | 开启 `allowPrivateNetwork` 后 |
|---|---|---|
| 公网单播（IPv4/IPv6） | 允许 | 允许 |
| 回环 `127.0.0.0/8`、`::1` | 拒绝 | 允许 |
| RFC1918 `10/8`、`172.16/12`、`192.168/16` | 拒绝 | 允许 |
| IPv6 ULA `fc00::/7` | 拒绝 | 允许 |
| 链路本地 `169.254.0.0/16`、`fe80::/10` | 拒绝 | 拒绝 |
| 云元数据 `169.254.169.254` | 拒绝 | 拒绝 |
| 组播 `224.0.0.0/4`、`ff00::/8` | 拒绝 | 拒绝 |
| 未指定 `0.0.0.0`、`::` | 拒绝 | 拒绝 |
| IPv4-mapped IPv6 的上述地址 | 按映射后的地址判断 | 同左 |

补充规则：

- 禁止 URL 中带 userinfo（`https://user:pass@host`）。
- `http` 仅在 `allowPrivateNetwork=true && allowCleartext=true` 时允许；公网 `http` 永远拒绝。
- 域名解析返回多个地址时，**任何一个**命中拒绝表就整体拒绝（防止 split-horizon/DNS rebinding 挑好地址）。
- 端口不限（自建服务可能用 8080/8443）。
- 封面 URL 可以是相对路径；相对路径按触发它的 API 响应 URL 解析，然后同样过策略。
- 不允许从响应里读取任意 URL 并直接请求；**唯一**例外是封面字段（经过策略）和详情模板（模板由用户配置，不是响应提供）。

**Android 本地网络权限：**

- 目标 SDK 37。`allowPrivateNetwork=true` 时，在测试/保存/搜索前检查 `ACCESS_LOCAL_NETWORK`：
  - `Build.VERSION.SDK_INT >= 37` 且未授权 → 显示理由并请求权限（复用 `NavidromeLoginActivity.kt:189` 的模式）。
  - 拒绝授权 → 私网 Provider 不可用，提示“需要本地网络权限”；公网 Provider 不受影响。
- 权限只影响私网访问；公网 HTTPS 不需要它。

### 8.7 DNS 固定

- 不为全局 OkHttpClient 改 DNS；为每个 Provider 构建独立 client。
- client 的 `Dns` 闭包：
  1. `Dns.SYSTEM.lookup(host)`；
  2. 对所有地址执行 `ProviderUrlPolicy.validateAddress`；
  3. 返回已校验的地址列表。
- OkHttp 使用该列表连接，避免“校验时解析到公网、连接时解析到内网”的 TOCTOU。
- client 缓存键：`provider.id + hash(baseUrl, allowPrivateNetwork, allowCleartext)`；配置变化后重建。认证不参与 client 构建，见 §8.3。
- 不实现自定义 DNS-over-HTTPS；不绕过系统 DNS。

### 8.8 响应读取与大小限制

| 项目 | 搜索 | 详情 |
|---|---|---|
| 最大字节 | 1 MiB | 2 MiB |
| `Content-Length` 预检 | 超过上限直接失败 | 同左 |
| 流式读取 | 最多读 `limit + 1` 字节 | 同左 |
| 原始预览 | 20 KB | 20 KB |
| 内容类型 | `application/json` 或 `*+json` | 同左 |
| 缺失 Content-Type | 若首字符是 `{`/`[` 则尝试解析并告警 | 同左 |
| `text/html` | 直接拒绝 | 同左 |
| 字符集 | 按 Content-Type，缺省 UTF-8 | 同左 |

- 不跟随 `Content-Encoding` 之外的任何自定义压缩；OkHttp 自动处理 gzip。
- 响应体读取完成后关闭。
- 不使用 `response.body.string()` 直接读无上限内容；统一走 `CappedBodyReader`。

### 8.9 JSON 深度与 Token 上限

`SafeJsonParser`：

1. 用 Gson `JsonReader` 流式走一遍，统计深度与 token 数；
2. 深度 > 64 或 token > 200,000 → `JsonTooComplex`；
3. 再解析为 `JsonElement`。

原因：Gson 的树解析对超深嵌套可能栈溢出；仅靠 1 MiB 体积上限不足以防护。

### 8.10 限速与 429

每个 Provider 一个 `ProviderRateLimiter`：

```kotlin
class ProviderRateLimiter(
    private val minIntervalMs: Long,
    private val maxConcurrent: Int
) {
    suspend fun <T> withPermit(block: suspend () -> T): T
    fun onHttp429(retryAfterSeconds: Long?)
    fun cooldownRemainingMs(): Long
}
```

- `withPermit`：先等最小间隔，再拿 `Semaphore(maxConcurrent)` 许可。
- 429：
  - 解析 `Retry-After`（秒数或 HTTP 日期），上限 60 秒用于自动冷却；
  - 超过 60 秒也记录，但直接提示“请稍后再试”，不长时间挂起 UI；
  - 冷却期内新请求立即失败 `RateLimited(retryAt)`，不排队。
- 5xx 不自动重试；网络错误不自动重试。
- 用户手动重试时重新走完整管线。
- 已知主机强制下限：

| host | 强制规则 |
|---|---|
| `musicbrainz.org` | `minIntervalMs >= 1100`，`maxConcurrent = 1` |
| `coverartarchive.org` | `minIntervalMs >= 1100`，`maxConcurrent = 1` |
| `api.discogs.com` | 无密钥：`minIntervalMs >= 2400`；有密钥：`>= 1000`；`maxConcurrent = 1` |

- 强制值只上调不下调；UI 显示“Effective: 1100 ms (enforced by host)”。
- 内置 MusicBrainz 客户端继续使用自己的 1.1 s 逻辑，不经过自定义 Provider 管线。

### 8.11 超时与取消

| 项目 | 值 |
|---|---|
| connectTimeout | 10 s |
| readTimeout | 15 s |
| writeTimeout | 10 s |
| callTimeout | 20 s |
| 取消 | 协程取消时 `Call.cancel()`；UI 状态回到 Idle/Error(Cancelled) |

### 8.12 日志与脱敏

- Tag：`MetadataProvider`。
- 允许记录：provider id、host、method、path（不含 query）、HTTP 状态、耗时、响应字节数、错误码。
- 禁止记录：完整 URL（query 认证可能含密钥）、请求头、响应体、候选字段、密钥。
- 错误信息中只拼 host + path；不拼 query。
- Release 构建的 Timber debug 日志会被 R8 移除（`app/proguard-rules.pro:63`），但仍按上述规则写，避免 debug 包泄露。
- 测试面板的“原始响应”只显示在 UI，不写日志；复制按钮复制的是脱敏后的文本。

---

## 9. 模板与 JSON 路径引擎

### 9.1 模板语法

```text
template  := ( literal | escape | variable )*
escape    := "{{" | "}}"
variable  := "{" name "}"
name      := "title" | "artist" | "album" | "albumArtist" | "isrc"
           | "duration" | "filename" | "rawQuery" | "limit" | "id"
```

规则：

- 变量名区分大小写，仅允许上述小写/驼峰形式。
- `{{` 表示字面量 `{`，`}}` 表示字面量 `}`。
- 任何未闭合的 `{`、未知变量、嵌套 `{...{...}...}` → 校验错误。
- 变量两侧允许空格：`{ title }` 等价 `{title}`。
- 不支持函数、条件、默认值、格式化。

### 9.2 变量表

| 变量 | 来源 | 搜索 | 详情 | 空值行为 |
|---|---|---|---|---|
| `{title}` | `MetadataQuery.title` | 是 | 否 | 空串 |
| `{artist}` | `MetadataQuery.artist` | 是 | 否 | 空串 |
| `{album}` | `MetadataQuery.album` | 是 | 否 | 空串 |
| `{albumArtist}` | `MetadataQuery.albumArtist` | 是 | 否 | 空串 |
| `{isrc}` | `MetadataQuery.isrc` | 是 | 否 | 空串 |
| `{duration}` | `MetadataQuery.durationMs` | 是 | 否 | 空串 |
| `{filename}` | `MetadataQuery.filename` | 是 | 否 | 空串 |
| `{rawQuery}` | `MetadataQuery.rawQuery` | 是 | 否 | 空串 |
| `{limit}` | `search.maxResults` | 是 | 否 | 不会为空 |
| `{id}` | 候选的 `sourceItemId` | 否 | 是 | 详情请求不会在没有 id 时发起 |

- `{duration}` 输出毫秒整数（无单位）。
- `{limit}` 只能用于 query 参数，不能用于 path；校验期拒绝。
- `{id}` 只能用于 `detail.path`；搜索模板里出现 → 校验错误。

### 9.3 编码规则

- Query 值：先渲染为原始字符串，再交给 `HttpUrl.Builder.addQueryParameter` 统一百分号编码；不要手工 `URLEncoder` 两次。
- Path 变量：`addPathSegment` 编码，`/`、`?`、`#`、空格都会转义。
- Header 值：不做 URL 编码；拒绝 CR/LF。
- 渲染结果长度 > 2,048 字符 → `TemplateValueTooLong`。

### 9.4 JSON 路径语法

```text
path  := head ( "[]" tail )?
head  := segment ( "." segment | "[" index "]" )*
tail  := ( "." segment | "[" index "]" )*
segment := [A-Za-z0-9_-]+
index := 0 | [1-9][0-9]*
```

- 只读取值；不支持 `*`、`..`、过滤、递归下降、脚本。
- 最多 8 个段（`[]` 不计入）。
- 路径总长 ≤ 120 字符。
- `[]` 最多出现一次，表示“对数组每个元素应用后面的路径，并用 `, ` 连接”。
- 数组下标越界 → `null`，不抛异常。
- 对象键含点号或方括号时暂不支持（v1 明确限制）。

**示例**

| 路径 | JSON | 结果 |
|---|---|---|
| `data.results` | `{"data":{"results":[...]}}` | 数组 |
| `artists[0].name` | `{"artists":[{"name":"A"}]}` | `"A"` |
| `artists[].name` | `{"artists":[{"name":"A"},{"name":"B"}]}` | `"A, B"` |
| `tags[]` | `{"tags":["rock","pop"]}` | `"rock, pop"` |
| `a.b[2].c` | 越界 | `null` |

### 9.5 类型转换

| JSON 类型 | 转为字符串 | 备注 |
|---|---|---|
| string | 原样（trim） | 控制字符替换为空格 |
| number | 整数去掉 `.0`；小数用 `toPlainString` | 不做四舍五入 |
| boolean | `"true"` / `"false"` | |
| null | `null` | |
| array | 元素为标量时用 `", "` 连接 | 元素为对象且没有 `[]` → `null` + 告警 |
| object | `null` | + 告警 |

字段级转换：

- **year**：从值中提取第一个四位年份（1000–2999）；`2004-06-15` → 2004；`"2004"` → 2004；失败 → null + 告警。
- **trackNumber / discNumber**：数字直接取；字符串取 `/` 前的前导数字；`"1/12"` → 1；失败 → null + 告警。
- **duration**：按 `unit` 解析：
  - `ms`：数字或数字字符串；
  - `s`：秒 → ×1000；
  - `mm:ss`：`^(\d{1,3}):([0-5]\d)$` → 分×60000 + 秒×1000。
  - 失败 → null + 告警。
- **isrc**：trim、去空格、转大写；长度 > 32 → 截断 + 告警；不做严格 12 位校验。
- **score**：按 `scale` 归一化到 0–100，clamp；非数字 → null + 告警。
- **coverUrl**：字符串；相对路径按响应 URL 解析；非 http(s) → null + 告警。
- **artist**：如果映射路径返回数组，按 `, ` 连接；如果是 `[]` 映射，天然得到连接结果。

### 9.6 限制

| 项目 | 限制 |
|---|---|
| 模板长度 | ≤ 200 字符 |
| 渲染值长度 | ≤ 2,048 字符 |
| 路径长度 | ≤ 120 字符 |
| 路径深度 | ≤ 8 段 |
| `[]` 数量 | ≤ 1 |
| 单字段结果 | ≤ 4,096 字符，超出截断 + 告警 |

### 9.7 映射示例

**扁平响应**

```json
{ "results": [ { "trackId": 1, "name": "晴天", "artistName": "周杰伦", "albumName": "叶惠美", "year": 2003 } ] }
```

```json
{
  "items": "results",
  "fields": {
    "id": "trackId",
    "title": "name",
    "artist": "artistName",
    "album": "albumName",
    "year": "year"
  }
}
```

**数组 artist + 嵌套封面**

```json
{
  "items": "data",
  "fields": {
    "id": "id",
    "title": "title",
    "artist": "artists[].name",
    "coverUrl": "images[0].url"
  }
}
```

---

## 10. 响应映射与候选生成

### 10.1 映射流程

```text
items 路径 → JsonArray
  for each element (最多 maxResults 个):
    id = 必填路径
    title = 必填路径
    artist/album/albumArtist/year/track/disc/duration/isrc/cover/score = 可选
    → 必填缺失或为空的元素丢弃，并累计 warning
    → 生成 MetadataCandidate
  → 本地调分
  → 按分数排序
```

- 解析失败的单条候选不影响其他条。
- 所有条目都失败 → `MappingError`，测试面板显示第一个失败原因和路径。
- `maxResults` 同时限制“解析条数”和“展示条数”；不会因为 API 返回 1000 条就全部映射。

### 10.2 本地评分

与现有 MusicBrainz 的调分逻辑保持一致（`MusicBrainzApiService.kt:193`）：

| 条件 | 加分 |
|---|---|
| 标题规范化后相等 | +8 |
| artist 规范化后相等 | +8 |
| album 双方非空且相等 | +6 |
| 时长差 ≤ 2 s | +8 |
| 时长差 ≤ 5 s | +4 |
| 时长差 ≥ 30 s | -12 |

基础分：

- 映射了 `score`：基础分 = 归一化到 0–100 的 provider score。
- 未映射 `score`：基础分 = 50。
- 最终 `adjustedScore = clamp(base + 调整, 0, 100)`。

规范化：`lowercase()` 后只保留 Unicode 字母与数字。中文不做简繁转换；简繁问题由 Phase 1 的查询变体解决。

### 10.3 排序与去重

- 排序：`adjustedScore` 降序 → provider score 降序 → title 升序。
- 去重键：`normalize(title)|normalize(artist)|normalize(album)`。
- 重复时保留分数最高的一条；同分时优先内置源，其次按 `displayName` 字典序。
- v1 不展示“其他来源也有”，避免 UI 复杂化；去重丢弃的条目只在 debug 日志中记录数量（不记录内容）。

### 10.4 详情补全

- 只有 `response.detail != null` 且候选有 `sourceItemId` 时才请求。
- 详情请求使用同一 Provider 的认证、限速、主机策略、请求头；**不**带搜索 query 参数。
- 详情字段覆盖搜索字段；详情缺失的字段回退到搜索值。
- 详情失败：
  - 预览仍显示搜索级字段；
  - 顶部提示“详细信息加载失败，可能缺少专辑/封面”；
  - 不阻止应用。
- 详情不会自动触发第二次网络请求重试。

### 10.5 封面处理

| 阶段 | 行为 |
|---|---|
| 搜索/候选列表 | 不下载封面；只保存 URL；显示占位图 |
| 预览 | 用户选中候选后，可下载一次封面用于预览；失败显示占位图 |
| 应用（DB_ONLY） | **不写封面**（见 10.6） |
| 应用（FILE_AND_DB） | 下载封面 → 校验 → 交给 `SongMetadataEditor` |

封面校验：

- URL：http(s)；相对路径按响应 URL 解析；过主机策略；不携带认证。
- 大小：`Content-Length` 预检 + 流式读取上限 10 MB。
- 类型：`image/jpeg`、`image/png`、`image/webp`；缺失时用魔数嗅探。
- 拒绝 `image/svg+xml`（可含脚本，且标签写入不支持）。
- 解码边界：`BitmapFactory.Options.inJustDecodeBounds` 检查宽高，> 4096 拒绝。
- 下载失败不阻止文本字段应用；提示“封面下载失败”。

### 10.6 为什么 DB-only 不写封面

“只更新数据库”意味着不改文件；而当前应用的封面 URI 指向文件内嵌图或本地缓存。若为了 DB-only 把网络封面落盘，就会引入：

- 持久缓存（与 N8 冲突）；
- 缓存清理、版权、存储配额问题；
- 与“文件未变”的语义冲突。

因此 Phase 3 明确：**DB-only 只写文本字段；封面只在 FILE_AND_DB 写。** 如果用户只想要封面而不改文件，作为未来独立功能评估。

### 10.7 错误与降级矩阵

| 情况 | 结果 |
|---|---|
| 无结果 | `Empty`，UI 显示“No matches”，不视为错误 |
| 部分候选字段缺失 | 保留候选，标警告 |
| 全部候选缺 id/title | `MappingError` |
| 详情失败 | 降级为搜索字段 |
| 封面失败 | 文本仍可应用 |
| JSON 解析失败 | `ParseError`，显示截断的响应预览 |
| 429 | `RateLimited`，显示冷却时间 |
| 401/403 | `AuthError` |
| 404 | `EndpointNotFound` |
| 5xx | `ServerError` |
| 超时/断网 | `NetworkError` |

---

## 11. UI 设计

### 11.1 入口与导航

**入口位置：Settings → Developer → Online metadata providers。**

理由：

- 面向高级用户，放在 Developer 最不打扰普通用户；
- 不新增 `SettingsCategory`，避免改动设置首页的枚举、图标、排序与所有语言文案；
- 后续若用户量足够，再提升为独立的 “Metadata” 分类。

**导航改动**

`presentation/navigation/Screen.kt`：

```kotlin
// 必须声明 highlightKey：SettingSpec.createNavigationRoute() 会无条件追加
// ?highlightKey=<itemKey>（见 SettingSpec.kt:39），路由不声明会被忽略或报参数错误。
object MetadataProviders : Screen("metadata_providers?highlightKey={highlightKey}") {
    fun createRoute(highlightKey: String? = null): String =
        if (highlightKey != null) {
            "metadata_providers?highlightKey=$highlightKey"
        } else {
            "metadata_providers"
        }
}

object MetadataProviderEditor : Screen("metadata_providers/editor?providerId={providerId}") {
    fun createRoute(providerId: String? = null): String =
        if (providerId != null) {
            "metadata_providers/editor?providerId=${Uri.encode(providerId)}"
        } else {
            "metadata_providers/editor"
        }
}
```

`presentation/navigation/AppNavigation.kt`：

- 在 `Screen.Experimental` 附近新增两个 `composable(...)`，使用与设置页一致的 `enterTransition/exitTransition` 与 `ScreenWrapper`。
- 列表页声明 `navArgument("highlightKey") { type = NavType.StringType; nullable = true; defaultValue = null }`，用于从设置搜索进入时高亮。
- 编辑器参数 `providerId` 为 `StringType?`、默认 `null`；`null` 表示新建。
- 两个页面都通过 `navController.popBackStack()` 返回。

`presentation/screens/SettingsCategoryScreen.kt` 的 `SettingsCategory.DEVELOPER` 分支：

- 在 “Experiments” 子区块之后新增：

```kotlin
SettingsSubsection(title = stringResource(R.string.setcat_online_metadata)) {
    SettingsItem(
        title = stringResource(R.string.metadata_providers_title),
        subtitle = stringResource(R.string.metadata_providers_subtitle),
        leadingIcon = {
            Icon(Icons.Outlined.Api, null, tint = MaterialTheme.colorScheme.secondary)
        },
        trailingIcon = {
            Icon(Icons.Rounded.ChevronRight, stringResource(R.string.cd_open))
        },
        modifier = Modifier.settingHighlight("item_developer_metadata_providers", highlightKey),
        onClick = { navController.navigateSafely(Screen.MetadataProviders.createRoute()) }
    )
}
```

`presentation/settings/search/SettingsRegistry.kt` 的 DEVELOPER 段新增：

```kotlin
SettingSpec(
    id = "developer_metadata_providers",
    itemKey = "item_developer_metadata_providers",
    titleRes = R.string.metadata_providers_title,
    subtitleRes = R.string.metadata_providers_subtitle,
    category = SettingsCategory.DEVELOPER,
    subscreenRoute = Screen.MetadataProviders.createRoute(),
    type = SettingType.NAVIGABLE_CARD,
    keywordsStatic = listOf(
        "metadata", "provider", "api", "json", "online", "search",
        "元数据", "标签", "接口"
    )
)
```

这样设置搜索能直接命中；`settingHighlight` 让从搜索结果进入时定位到该行。

### 11.2 Provider 列表页

**布局**

```text
┌──────────────────────────────────────────────┐
│ ← Online metadata providers          ⋮       │
├──────────────────────────────────────────────┤
│ 连接你有权使用的 JSON API。本功能不内置任何   │
│ 第三方服务，也不提供签名/绕过能力。            │
├──────────────────────────────────────────────┤
│ [My Music API]                    [Enabled]  │
│ api.example.com · Search · Cover · Ready     │
│ Last test: OK, 312 ms                        │
│                                      Edit ⋮  │
├──────────────────────────────────────────────┤
│ [Company Catalog]                 [Enabled]  │
│ 192.168.1.20 · Search · Needs key            │
│                                      Edit ⋮  │
├──────────────────────────────────────────────┤
│                                              │
│              No more providers               │
│                                              │
│                    (+ Add provider)          │
└──────────────────────────────────────────────┘
```

**元素**

| 元素 | 行为 |
|---|---|
| 顶部说明卡 | 简述定位与合规边界；点“Learn more”打开说明对话框 |
| Provider 卡片 | 名称、host（不显示完整 URL）、能力徽章、状态徽章、开关、最后测试结果 |
| 开关 | 只改 `enabled`；无密钥/无效时仍可开，但搜索跳过并提示 |
| 徽章 | `Search`、`Cover`、`Detail`、`Needs key`、`Invalid` |
| 卡片菜单 | Edit / Duplicate / Delete |
| FAB | Add provider |
| 顶部菜单 | Import / Export / Help |
| 空态 | 说明 + Add + Import |
| 损坏横幅 | Export raw / Reset providers |

**删除确认**

- 标题：“Delete provider?”
- 正文：“This removes the configuration and its saved API key. Existing song metadata is not affected.”
- 按钮：Cancel / Delete（错误色）。

**列表排序**

- 按名称升序；禁用项不沉底，避免用户找不到。
- 新建/导入后定位到新条目并高亮 1 秒。

### 11.3 编辑器页

**区块顺序**

```text
Basics
  Name
  Base URL
  Attribution (optional)

Authentication
  Type [None | API key | Bearer]
  Location [Header | Query]        (apiKey only)
  Header/Parameter name            (apiKey only)
  API key [••••••••] [Replace] [Clear]
  Status: Key saved / No key

Search request
  Path
  Query parameters
    [name] [template] [omit if empty] [remove]
    [+ Add parameter]
  Headers
    [name] [value] [remove]
    [+ Add header]
  Max results

Response mapping
  Items path
  ID path
  Title path
  Artist path
  Album path
  Album artist path
  Year path
  Track number path
  Disc number path
  Duration path + unit
  ISRC path
  Cover URL path
  Score path + scale
  [Advanced] Detail path + detail field overrides

Network
  Allow private network [switch]
  Allow cleartext HTTP [switch, disabled unless private]
  Effective rate limit (read-only note)

Rate limit
  Min interval (ms)
  Max concurrent

[Test request]                    [Save]
```

**交互规则**

- 所有字段用 Compose `OutlinedTextField` / `ExposedDropdownMenuBox`，与现有 AI 设置页保持一致（参考 `presentation/screens/AiSettingsScreen.kt:57`）。
- 字段错误就地显示在 `supportingText`，颜色用 `MaterialTheme.colorScheme.error`。
- 保存按钮显示错误数量：`Save (2 errors)`；有 ERROR 时禁用。
- WARNING 不阻止保存，但在字段下方显示黄色提示。
- 密钥字段：
  - 已有密钥时显示占位 `••••••••`，不显示真实值；
  - “Replace” 打开输入态；“Clear” 二次确认；
  - 新输入的值在保存前只保存在 ViewModel 内存，不写日志、不写草稿持久化。
- 修改 `baseUrl` 不清空密钥，但提示“Key will be sent to the new host”并二次确认。
- 修改 `auth.name` 不清空密钥，但提示。
- 返回时若有未保存修改 → 确认对话框。
- 测试按钮始终可用（除非有 ERROR）；测试使用当前草稿 + 内存中的新密钥，不要求先保存。
- 保存成功后返回列表并显示 Snackbar。

**首次保存的授权承诺**

对话框：

> “I have the right to use this API and will comply with its terms.”
> 中文：“我有权使用该 API，并会遵守其条款。”

- 必须勾选才能保存。
- 本地记录 `attestedAt` 与 `attestationVersion`（仅本地，不导出、不上报）。
- 这不是法律豁免，只是用户确认。

### 11.4 测试面板

**入口**

- 编辑器底部 “Test request”。
- 列表卡片菜单 “Test”（可选；复用已保存配置）。

**输入**

| 字段 | 默认 |
|---|---|
| Title | 空，占位 “晴天” |
| Artist | 空，占位 “周杰伦” |
| Album | 空 |
| ISRC | 空 |
| Duration (ms) | 空 |
| Filename | 空 |
| Raw query | 空（仅当模板使用 `{rawQuery}` 时显示提示） |

如果从单曲资料页的 Provider 菜单进入，测试面板用该歌曲预填。

**输出**

```text
Result
  HTTP 200 · 312 ms · 4.8 KB
  Candidates: 3
  Warnings: 1

  [1] 晴天 · 周杰伦 · 叶惠美 · 2003 · score 92
      id=... cover=yes
  [2] ...
  [3] ...

  [Mapped fields] [Raw response (20 KB max)] [Copy]
```

- 原始响应对话框：
  - 截断到 20 KB；
  - 如果是 query 认证，URL 中该参数显示为 `***`；
  - 不显示请求头；
  - 提供 “Copy” 与 “Close”。
- 诊断信息（可折叠）：
  - 最终请求：`GET https://host/path`（不含 query）；
  - 参数数量、是否使用 limit、是否省略空参数；
  - 每个候选的缺失字段；
  - 有效限速及其来源（用户配置/主机强制）。
- 测试不会写入 DataStore、不会写数据库、不会缓存响应。

### 11.5 导入对话框

**流程**

1. SAF 选择文件（`application/json`，兼容 `text/plain`）。
2. 读取上限 256 KB。
3. 解析 + 校验。
4. 展示摘要：
   - `3 new · 1 conflict · 1 invalid`
   - 冲突项：现有名称/host vs 导入名称/host。
   - 无效项：字段路径 + 原因。
5. 冲突逐项选择：Keep existing / Replace / Import as copy。
6. 底部按钮：Cancel / Import N providers。
7. 导入成功后返回列表，Snackbar “Imported 3 providers. API keys must be entered manually.”

**规则**

- 有无效项时：允许导入有效项，但必须显示“K invalid entries will be skipped”；用户确认。
- 全部无效：禁用 Import 按钮。
- 导入过程中不发起任何网络请求。
- 导入后不自动启用没有密钥的 Provider（保持 `enabled` 原值，但搜索时标 Needs key）。

### 11.6 导出流程

- SAF `CreateDocument("application/json")`。
- 默认文件名：`pixelplayer-metadata-providers-YYYYMMDD.json`。
- 内容：`format` + `schemaVersion` + `exportedAt` + `appVersion` + `secretsIncluded: false` + `providers`。
- 导出前提示：“API keys are never exported. You will need to enter them again after importing.”
- 写入失败：Snackbar + 错误原因；不留下半截文件（先写临时流，成功后关闭；SAF 写入失败由系统处理）。

### 11.7 单曲搜索集成

**候选区顶部来源栏**

```text
[MusicBrainz] [My Music API] [Company Catalog]   [Search providers]
```

- 只有至少一个“可用”的自定义 Provider 时显示。
- 默认选中内置源；自定义源不自动并发查询。
- 点某个 Provider 芯片 → 单独查询该源；显示该源自己的加载/错误行。
- 点 “Search providers” → 依次查询所有可用自定义源（仍受各自限速；不是并发风暴）。
- 每个源的结果分组显示，组头是 Provider 名称；同组内按分数排序。
- 跨源合并/去重只在用户点“Show all”时进行；默认分组更利于判断来源可信度。

**候选卡片**

- 标题、artist、album、year、score。
- 来源徽章：`MusicBrainz` / `Discogs` / Provider 名称。
- 自定义来源额外显示 `API` 小徽章。
- 封面：候选列表不加载网络封面；预览时才加载。
- 详情可用时，卡片显示 “Details” 提示；选中后自动拉取。

**预览与应用**

- 复用 Phase 1 的 `MetadataCandidateSheet`：
  - 字段列表 + 勾选框；
  - “Only fill blanks” 开关；
  - 应用目标单选：DB only / File + DB；
  - 目标不可用时给出原因（云歌曲、只读文件）。
- 自定义来源的候选不显示 MBID；也不写 MBID。
- 应用成功后：
  - DB-only：资料页字段更新；文件 SHA-256 不变；
  - File+DB：文件标签、MediaStore、Room 一致；封面（若勾选）写入。

**失败回退**

- FILE_AND_DB 因权限/只读失败 → 对话框：“This file cannot be written. Update the database only?” → 用户确认后走 DB_ONLY。
- 云歌曲 → 只允许 DB_ONLY，File+DB 置灰并说明。
- 封面失败 → 文本应用成功，Snackbar 提示封面失败。

### 11.8 空态、禁用态、错误态

| 场景 | 展示 |
|---|---|
| 无 Provider | 单曲页无来源栏；列表页空态 |
| 有 Provider 但全部禁用 | 单曲页无来源栏；列表页正常 |
| 有 Provider 但全部缺密钥 | 单曲页无来源栏；列表页 “Needs key” 徽章 |
| Provider 无效 | 不参与搜索；列表页 “Invalid” + 展开原因 |
| 单源失败 | 该源组显示错误 + Retry；其他源不受影响 |
| 限速中 | 该源显示倒计时；Retry 在倒计时结束后可用 |
| 详情失败 | 预览可用，顶部警告 |
| 封面失败 | 预览占位图，应用仍可继续 |
| 无结果 | “No matches from <provider>” |

### 11.9 文案与合规提示

**必须出现的文案**

- 列表页说明：
  - EN: “Connect a JSON API you are authorized to use. This feature does not include any third-party service, signing, scraping, or bypass capability.”
  - zh-rCN: “连接你有权使用的 JSON API。本功能不内置任何第三方服务，也不提供签名、抓取或绕过能力。”
- 首次保存承诺：
  - EN: “I have the right to use this API and will comply with its terms.”
  - zh-rCN: “我有权使用该 API，并会遵守其条款。”
- 导出提示：
  - EN: “API keys are never exported. Re-enter them after importing.”
  - zh-rCN: “导出不包含 API 密钥，导入后需要重新输入。”
- 私网开关：
  - EN: “Only enable this for a server you control. It allows requests to private addresses.”
  - zh-rCN: “仅为你自己控制的服务器开启。开启后允许访问私有地址。”
- 明文开关：
  - EN: “Plain HTTP sends data unencrypted. Use only on a trusted local network.”
  - zh-rCN: “明文 HTTP 不加密传输，仅在可信本地网络使用。”

**禁止出现的文案**

- “解锁/破解/白嫖/免费听”
- “网易云/QQ 音乐/酷狗/酷我”
- “全网最全/100% 覆盖”
- “自动绕过验证/签名”
- 任何具体逆向源的名称、URL、参数示例

### 11.10 无障碍与国际化

- 所有图标按钮有 `contentDescription`；纯装饰图标传 `null`。
- 触控目标 ≥ 48 dp。
- 错误文本与字段通过 `supportingText` 关联；屏幕阅读器能读到错误。
- 徽章不只靠颜色区分，带文字。
- 新文案只写 `values/`（英文）与 `values-zh-rCN/`；其他语言回退英文。
- 文案放新文件 `strings_metadata_providers.xml`，中英文各一份，避免把 `strings.xml` 继续膨胀。
- 数字与日期用 `stringResource` 占位符，不手工拼接。

---

## 12. 校验与错误处理

### 12.1 校验层级

| 层级 | 时机 | 内容 |
|---|---|---|
| L1 语法 | 导入/保存 | JSON 可解析、字段类型正确、枚举值合法 |
| L2 结构 | 导入/保存 | 必填字段、长度、数量、路径语法、模板变量 |
| L3 语义 | 导入/保存 | baseUrl 与 path 组合、auth 完整性、detail 完整性、duration/score 成对 |
| L4 安全 | 导入/保存/每次请求 | scheme、userinfo、危险头、私网/明文、`..` |
| L5 运行时 | 每次请求 | 密钥存在、DNS 地址、响应大小/类型/深度、429 |

L1–L4 在保存前完成；L5 在请求时完成。测试面板会同时触发 L1–L5。

### 12.2 校验规则总表

| 编号 | 字段 | 规则 | 级别 | 消息 key |
|---|---|---|---|---|
| V001 | root | `schemaVersion` 必须为 1 | ERROR | `metadata_provider_error_schema_version` |
| V002 | id | 匹配 `^usr_[0-9a-f]{32}$` | ERROR | `metadata_provider_error_id` |
| V003 | name | 1–60 字符，无控制字符 | ERROR | `metadata_provider_error_name` |
| V004 | baseUrl | 可解析、https、无 userinfo/query/fragment、≤300 | ERROR | `metadata_provider_error_base_url` |
| V005 | baseUrl | 末尾 `/` 自动去掉 | — | — |
| V006 | auth.type | 枚举合法 | ERROR | `metadata_provider_error_auth_type` |
| V007 | auth.in | apiKey 时必须 header/query | ERROR | `metadata_provider_error_auth_location` |
| V008 | auth.name | apiKey 时必填，匹配名称正则 | ERROR | `metadata_provider_error_auth_name` |
| V009 | auth.name | 不在危险头拒绝表 | ERROR | `metadata_provider_error_auth_header_reserved` |
| V010 | search.method | 必须 `GET` | ERROR | `metadata_provider_error_method` |
| V011 | search.path | 以 `/` 开头，≤300，无 `?`/`#`/`://`/`..` | ERROR | `metadata_provider_error_path` |
| V012 | search.path | 段数 1–16，静态段非空 | ERROR | `metadata_provider_error_path_segments` |
| V013 | search.query | ≤10 项，name 不重复（含认证参数） | ERROR | `metadata_provider_error_query_count` |
| V014 | query.template | 语法合法、变量已知、长度 ≤200 | ERROR | `metadata_provider_error_template` |
| V015 | query.template | 搜索模板不得使用 `{id}` | ERROR | `metadata_provider_error_template_id` |
| V016 | headers | ≤8 项，名称合法，值 ≤500，无 CR/LF | ERROR | `metadata_provider_error_headers` |
| V017 | headers | 名称不在拒绝表 | ERROR | `metadata_provider_error_header_reserved` |
| V018 | maxResults | 1–50 | ERROR | `metadata_provider_error_max_results` |
| V019 | response.items | 非空、路径合法、≤120 | ERROR | `metadata_provider_error_items_path` |
| V020 | fields.id | 非空、路径合法 | ERROR | `metadata_provider_error_id_path` |
| V021 | fields.title | 非空、路径合法 | ERROR | `metadata_provider_error_title_path` |
| V022 | fields.artist | 建议填写；缺失为 WARNING | WARNING | `metadata_provider_warning_artist_path` |
| V023 | 所有路径 | 深度 ≤8、`[]` ≤1 | ERROR | `metadata_provider_error_path_depth` |
| V024 | duration | path 与 unit 必须同时存在或同时缺失 | ERROR | `metadata_provider_error_duration` |
| V025 | score | path 与 scale 成对；scale 合法 | ERROR | `metadata_provider_error_score` |
| V026 | detail.path | 非空、含 `{id}`、≤300 | ERROR | `metadata_provider_error_detail_path` |
| V027 | detail.fields | 至少一个字段；不得覆盖 id | ERROR | `metadata_provider_error_detail_fields` |
| V028 | detail | 有 detail.fields 必须有 detail.path，反之亦然 | ERROR | `metadata_provider_error_detail_pair` |
| V029 | minIntervalMs | 250–60000 | ERROR | `metadata_provider_error_rate_min` |
| V030 | maxConcurrent | 1–2 | ERROR | `metadata_provider_error_rate_concurrency` |
| V031 | allowCleartext | true 时必须 allowPrivateNetwork=true | ERROR | `metadata_provider_error_cleartext_private` |
| V032 | allowCleartext | 公网明文永远拒绝（运行时） | ERROR | `metadata_provider_error_cleartext_public` |
| V033 | attribution | ≤200 字符 | ERROR | `metadata_provider_error_attribution` |
| V034 | 全部 | 序列化后 ≤256 KB，Provider ≤50 | ERROR | `metadata_provider_error_store_size` |
| V035 | 未知字段 | 导入时忽略并 WARNING | WARNING | `metadata_provider_warning_unknown_field` |
| V036 | baseUrl 主机 | 已知主机限速强制上调 | WARNING | `metadata_provider_warning_enforced_rate` |
| V037 | query 参数 | `omitIfEmpty=false` 且模板只含一个可能为空的变量 | WARNING | `metadata_provider_warning_empty_param` |
| V038 | 封面 | coverUrl 路径存在但可能返回相对 URL | INFO | — |

### 12.3 错误分类

```kotlin
sealed interface ProviderError {
    data class InvalidConfiguration(val issues: List<ValidationIssue>) : ProviderError
    data object MissingSecret : ProviderError
    data object LocalNetworkPermissionMissing : ProviderError
    data class BlockedHost(val host: String, val reason: BlockReason) : ProviderError
    data object CleartextBlocked : ProviderError
    data object RedirectBlocked : ProviderError
    data object UrlTooLong : ProviderError
    data class Network(val cause: IOException) : ProviderError
    data class Timeout(val phase: TimeoutPhase) : ProviderError
    data class Http(
        val code: Int,
        val retryAfterSeconds: Long? = null
    ) : ProviderError
    data class RateLimited(val retryAtEpochMs: Long) : ProviderError
    data class ResponseTooLarge(val limitBytes: Long) : ProviderError
    data class UnsupportedContentType(val contentType: String?) : ProviderError
    data object JsonTooComplex : ProviderError
    data class Parse(val message: String) : ProviderError
    data class Mapping(val missingFields: List<String>) : ProviderError
    data object Cancelled : ProviderError
}
```

### 12.4 用户提示映射

| 错误 | 用户提示（EN） | 重试 |
|---|---|---|
| MissingSecret | “Add an API key for this provider.” | 否，去编辑 |
| LocalNetworkPermissionMissing | “Local network permission is required for this provider.” | 授权后 |
| BlockedHost | “This host is not allowed. Enable private network access only for a server you control.” | 否 |
| CleartextBlocked | “Plain HTTP is disabled. Use HTTPS or enable it for a private server.” | 否 |
| RedirectBlocked | “The server redirected to a different host. Cross-host redirects are blocked.” | 否 |
| UrlTooLong | “The request URL is too long. Shorten the query template.” | 否 |
| Network | “Network error. Check your connection.” | 是 |
| Timeout | “The request timed out.” | 是 |
| Http 401/403 | “Authentication failed. Check the API key.” | 否 |
| Http 404 | “Endpoint not found. Check the base URL and path.” | 否 |
| Http 429 | “Rate limited. Try again in N seconds.” | 倒计时后 |
| Http 5xx | “The server returned an error (HTTP N).” | 是 |
| ResponseTooLarge | “The response is too large (limit 1 MB).” | 否 |
| UnsupportedContentType | “The server did not return JSON.” | 否 |
| JsonTooComplex | “The response JSON is too complex.” | 否 |
| Parse | “The response is not valid JSON.” | 否 |
| Mapping | “No results matched the configured field paths.” | 否 |
| Cancelled | 无提示 | — |

原始异常只放在“Details”折叠区，且经过脱敏；默认不展示堆栈。

### 12.5 测试面板诊断

测试面板额外显示：

- 最终请求方法 + `scheme://host/path`（query 脱敏）。
- 查询参数数量、跳过的空参数、是否使用 `{limit}`。
- 有效限速与来源（用户设置/主机强制）。
- 响应 Content-Type、字节数、JSON 深度（若可测）。
- 每个候选的缺失字段（例如 `missing: album, cover`）。
- 所有 WARNING。

这些信息只用于调试，不写入日志。

---

## 13. 安全、隐私与合规

### 13.1 威胁模型

| 威胁 | 载体 | 缓解 | 测试 |
|---|---|---|---|
| SSRF 访问内网/云元数据 | 恶意 baseUrl、重定向、封面 URL | 主机策略 + 地址校验 + DNS 固定 + 同源重定向 | `ProviderUrlPolicyTest` |
| DNS rebinding | 域名先解析公网后解析内网 | 每次 lookup 校验并返回固定地址 | 集成测试（自定义 Dns） |
| 密钥泄露到日志 | 完整 URL、请求头 | 只记 host/path；不记头；测试面板脱敏 | 日志捕获测试 |
| 密钥泄露到导出/备份 | 导出文件、DataStore | 导出永不包含；密钥单独加密；备份不含 | `ProviderImportExportTest` |
| 密钥被发给第三方 CDN | 封面请求、跨源重定向 | 封面不带认证；跨源重定向拒绝 | 集成测试 |
| 请求头注入 | 自定义 header 值含 CRLF | 校验拒绝 | `ProviderValidatorTest` |
| 超大响应/内存耗尽 | 恶意服务器 | 1/2 MiB 上限 + 流式读取 | 集成测试 |
| 超深 JSON 栈溢出 | 恶意服务器 | 深度 ≤64 + token ≤200k | `SafeJsonParserTest` |
| UI 注入 | 响应里含 HTML/脚本 | Compose `Text` 纯文本；无 WebView；不解析 HTML | UI 检查 |
| 封面解码炸弹 | 超大图片 | 10 MB + 尺寸上限 + 类型白名单 | 集成测试 |
| 资源滥用 | 高频搜索/后台爬取 | 限速 + 无后台任务 + 用户触发 | 代码审查 |
| Provider 被当代理 | 响应返回任意 URL | 只允许模板生成的详情 URL 与封面 URL；封面过策略 | 代码审查 |
| 配置注入/覆盖 | 导入恶意 JSON | 严格校验 + 原子写入 + 不执行任何逻辑 | `ProviderImportExportTest` |

### 13.2 关键缓解措施

1. **HTTPS 默认**：公网明文永久拒绝。
2. **私网默认关闭**：开启需独立开关 + 本地网络权限（SDK ≥37）。
3. **无脚本**：模板只有变量替换；路径只有取值；没有表达式、没有函数。
4. **无 Cookie**：OkHttp 不配置 `CookieJar`；拒绝 `Cookie` 头。
5. **无 WebView**：响应只进入 Gson。
6. **无证书绕过**：使用系统 TLS 校验；自签名证书在私网也不接受（用户可改用 HTTP + 私网开关，风险自担）。
7. **认证最小化**：只支持 none/apiKey/bearer；不支持 OAuth、客户端证书、自定义签名。
8. **固定 UA**：不让用户伪装浏览器；便于 API 运营方识别与限流。
9. **请求范围固定**：只请求 baseUrl + 用户配置的 path；不从响应里发现任意端点。
10. **失败安全**：任何校验/策略失败都拒绝请求，不降级到明文或私网。

### 13.3 密钥生命周期

```text
用户输入
  → ViewModel 内存
  → 保存时写入 EncryptedSharedPreferences
  → 请求时读取到内存，注入 header/query
  → 请求结束，释放引用
  → 删除 Provider 时删除密文
  → 导出/备份/日志/崩溃信息：永不出现
```

- 不在草稿持久化、SavedStateHandle、剪贴板历史中保存密钥。
- 不使用 `rememberSaveable` 保存密钥输入框。
- 页面销毁时清空 ViewModel 中的新密钥。
- 不提供“显示密钥”按钮；只提供 Replace/Clear。
- 密钥长度上限 4,096 字符；拒绝控制字符。

### 13.4 隐私

- 无遥测、无分析 SDK、无远程配置。
- 除了用户触发的搜索/详情/测试/封面，不发任何网络请求。
- 不上传 Provider 配置、搜索词、曲库信息。
- 不建立 Provider 使用统计。
- 测试面板的原始响应只存在内存。
- 不在日志中记录歌曲标题/artist 等查询内容（避免把用户曲库写入日志）。

### 13.5 法律与条款护栏

**实现层必须遵守：**

1. **零预置**：不内置任何第三方服务的名称、地址、参数、示例、截图、教程。
2. **零逆向**：不实现签名、加密、Cookie 注入、WebView 抓取、JS eval、证书固定绕过、反爬规避。
3. **用户承诺**：首次保存必须勾选授权与条款承诺；本地记录时间。
4. **来源标注**：候选显示 Provider 名称；导出保留 `attribution`。
5. **密钥最小化**：加密存储、导出剔除、日志脱敏。
6. **限速**：每 Provider 独立限速；已知主机强制下限；尊重 429。
7. **不批量**：只做用户触发的单曲/显式多源查询；无后台任务。
8. **不持久缓存**：响应只在内存；封面只在应用时下载。
9. **不建市场**：只支持本地文件导入；不托管、不索引、不分享配置。
10. **窄职责**：只取元数据字段；不做歌词、音频流、下载、代理。
11. **文案中性**：只描述“连接你有权使用的 JSON API”，不描述为规避手段。

**法律边界（必须在文档与 UI 中承认）：**

- 通用适配器降低的是**项目自身**内置侵权/规避接口的风险，不免除用户违反 API 条款的责任。
- 元数据与封面本身的著作权、数据库权利仍然存在；不得批量缓存或再分发。
- GPL-3.0-or-later 只覆盖本项目代码，不覆盖第三方 API 条款。
- 用户勾选承诺不是法律豁免，只是产品层面的确认与提示。

### 13.6 GPL 边界

- 本功能代码以 GPL-3.0-or-later 发布。
- 用户创建的 Provider 配置是用户数据，不是项目分发的一部分。
- 项目仓库不包含任何第三方 API 的配置样例（包括测试用的真实服务地址）。
- 测试使用本地 mock 服务或用户自己的服务；仓库中的测试 fixture 使用 `example.com` / `localhost` 等中性地址。

### 13.7 明确不做（安全相关）

- 不实现“从浏览器复制 cURL 自动导入”的功能（容易引导用户复制 Cookie/签名头）。
- 不实现“自动探测 API”的功能。
- 不实现“从网页提取接口”的功能。
- 不实现“Provider 分享码/短链”。
- 不实现“自动更新 Provider 配置”。
- 不实现“远程 Provider 列表”。

---

## 14. 导入/导出

### 14.1 文件格式

```json
{
  "format": "pixelplayer.metadata-providers",
  "schemaVersion": 1,
  "exportedAt": "2026-11-01T12:34:56Z",
  "appVersion": "0.4.1",
  "secretsIncluded": false,
  "providers": [
    {
      "schemaVersion": 1,
      "id": "usr_9f2c4a1b7d3e4f608a5b1c2d3e4f5a6b",
      "name": "My Music API",
      "enabled": true,
      "baseUrl": "https://api.example.com/v1",
      "auth": { "type": "apiKey", "in": "header", "name": "X-API-Key" },
      "search": {
        "method": "GET",
        "path": "/search",
        "query": [
          { "name": "q", "template": "{title} {artist}", "omitIfEmpty": false }
        ],
        "headers": { "Accept": "application/json" },
        "maxResults": 10
      },
      "response": {
        "items": "data.results",
        "fields": {
          "id": "id",
          "title": "title",
          "artist": "artists[].name"
        }
      },
      "rateLimit": { "minIntervalMs": 1100, "maxConcurrent": 1 },
      "network": { "allowPrivateNetwork": false, "allowCleartext": false },
      "attribution": "My Music API"
    }
  ]
}
```

- `format` 必须匹配；缺失时按旧版/裸数组尝试并警告。
- `secretsIncluded` 只作说明，导入时**永远**忽略任何密钥字段。
- 导出按名称排序，字段顺序固定（Gson DTO 顺序），便于 diff。
- 不导出 `lastTest`、`createdAt`、`updatedAt`、`secretRef`、`attestedAt`。

### 14.2 导入校验

| 检查 | 失败处理 |
|---|---|
| 文件 ≤256 KB | 拒绝，提示大小 |
| JSON 可解析 | 拒绝，显示行/列（不含文件内容） |
| `format` 正确 | 缺失时警告；错误值拒绝 |
| `schemaVersion` = 1 | 拒绝其他值 |
| `providers` 为数组 | 拒绝 |
| Provider 数量 ≤50 | 拒绝超出部分，提示 |
| 每个 Provider L1–L4 校验 | 无效项跳过，列出原因 |
| id 冲突 | 按冲突策略处理 |
| 名称+baseUrl 重复 | 警告，按冲突策略处理 |
| 未知字段 | 忽略 + 警告 |
| 任何 secret/apiKey/token 字段 | 忽略 + 警告“Secrets are not imported” |

### 14.3 冲突处理

| 情况 | 默认 | 用户可选 |
|---|---|---|
| 相同 id | Keep existing | Replace / Import as copy |
| 不同 id，但 name + baseUrl 相同 | Keep existing | Replace / Import as copy |
| 不同 id，name 相同但 baseUrl 不同 | Import | — |
| 不同 id，baseUrl 相同但 name 不同 | Import | — |

- `Import as copy`：生成新 id，名称追加 ` (imported)`；如果仍然重名，追加序号。
- `Replace`：保留现有 id？如果 id 不同但 name+baseUrl 相同，替换时使用导入的 id 还是现有 id？
  - 规则：Replace 保留**现有 id**，覆盖其定义与密钥引用；这样密钥仍然有效。导入文件中的 id 被丢弃。
  - 如果导入文件与现有 id 相同，直接覆盖定义，保留密钥。
- 选择结果以 `Map<providerId, Choice>` 传入，导入一次性应用。

### 14.4 密钥

- 导出：永不包含。
- 导入：即使文件包含 `secret`、`apiKey`、`token`、`apiKeyValue` 等字段，也一律忽略并警告。
- 导入后 `auth != none` 的 Provider 显示 “Needs key”；用户必须重新输入。
- 不提供“导出时包含密钥”的选项（计划稿允许强制包含；本设计改为**永不**，减少误操作与泄露面）。

### 14.5 原子性

1. 解析 + 校验全部在内存完成。
2. 用户确认冲突策略。
3. 构建新的完整 Provider 列表。
4. 校验总数 ≤50、序列化 ≤256 KB。
5. 单个 `dataStore.edit` 写入。
6. 写入成功后刷新 UI；失败则保持原状态，提示错误。

不出现“导入到一半”的状态。密钥不参与导入，因此不存在跨存储的部分提交。

### 14.6 示例：导出后导入到新设备

1. 旧设备 Export → 文件不含密钥。
2. 新设备 Import → 3 个 Provider 显示 Needs key。
3. 用户逐个 Edit → 输入密钥 → Save。
4. 列表显示 Ready；测试通过后可搜索。
5. 旧设备的密钥不受影响。

---

## 15. 测试计划

### 15.1 单元测试

| 测试类 | 覆盖 |
|---|---|
| `ProviderTemplateTest` | 变量替换、`{{`/`}}` 转义、未知变量、未闭合、空值、超长、`{limit}` 只在 query |
| `JsonPathEvaluatorTest` | 点/下标、`[]`、越界、类型错误、深度、对象/数组、数字格式 |
| `SafeJsonParserTest` | 正常、深度 65 拒绝、token 超限、非法 JSON |
| `ProviderValidatorTest` | V001–V038 每条规则的正反例；危险头；私网/明文组合；detail 成对 |
| `ProviderUrlPolicyTest` | http/https、userinfo、公网/私网/回环/链路本地/组播/未指定、IPv4-mapped、多地址拒绝 |
| `ProviderRequestFactoryTest` | baseUrl 路径拼接、变量段编码、query 顺序、omitIfEmpty、认证 header/query/bearer、无重复参数 |
| `ProviderResponseMapperTest` | 必填缺失、字段转换、year/track/duration/score、数组 artist、相对封面、单条失败不影响其他 |
| `ProviderRateLimiterTest` | 最小间隔、并发 1/2、429 冷却、冷却期立即失败、主机强制下限 |
| `ProviderImportExportTest` | 往返、冲突策略、未知字段、版本拒绝、密钥剔除、大小/数量上限、原子性（伪 DataStore） |
| `ProviderSecretStoreTest` | 保存/读取/清除、密文不可读时清理、无明文回退 |
| `MetadataCandidateScoringTest` | 调分、排序、去重、同分优先级 |
| `ProviderErrorMapperTest` | 401/403/404/429/5xx/超时/解析错误的用户消息 |
| `ProviderRedactionTest` | 日志不含密钥/query；错误信息只含 host+path |

### 15.2 集成测试（无新依赖）

用 JDK 自带 `com.sun.net.httpserver.HttpServer` 在 JVM 单元测试里起本地服务，不引入 MockWebServer：

| 用例 | 期望 |
|---|---|
| 200 + 合法 JSON | 解析出候选 |
| 200 + 缺 Content-Type，body 以 `{` 开头 | 尝试解析 + WARNING |
| 200 + `text/html` | `UnsupportedContentType` |
| 401 / 403 | `AuthError` 对应提示 |
| 404 | `EndpointNotFound` |
| 429 + `Retry-After: 2` | 冷却 2 s；期间立即 `RateLimited` |
| 500 | `ServerError`，不自动重试 |
| `Content-Length` 超 1 MiB | 预检失败，不读 body |
| chunked 超 1 MiB | 流式读取失败 `ResponseTooLarge` |
| 跨源 302 | `RedirectBlocked`，且第二台服务器收不到请求 |
| 同源 302 两次 | 跟随成功；第三次失败 |
| 深度 65 的 JSON | `JsonTooComplex` |
| 慢响应 >15 s | `Timeout`，连接被取消 |
| 相对封面 URL | 解析为同源绝对 URL |
| 封面 URL 指向私网（未开私网） | `BlockedHost`，且不下载 |
| 封面请求 | 不携带 API key 头/参数 |

### 15.3 手工/真机测试

前置：在 PC 上跑一个最小 JSON mock（监听 LAN），或在模拟器用 `10.0.2.2` 访问宿主机。

| 编号 | 步骤 | 通过标准 |
|---|---|---|
| M01 | 设置搜索 “metadata provider” | 命中 Developer 条目；点击进入列表 |
| M02 | 新建 Provider，baseUrl 填公网 https，不填密钥 | 保存成功但标 Needs key；搜索跳过并提示 |
| M03 | 填 mock 地址 + 私网/明文开关 | 首次要求本地网络权限（SDK ≥37）；授权后 Test 成功 |
| M04 | Test 返回 3 条候选 | 面板显示状态、耗时、字段、原始响应（截断） |
| M05 | 从单曲资料页选择该 Provider 搜索 | 只查该源；加载/失败独立 |
| M06 | 应用 DB-only | Room 字段更新；文件 SHA-256 不变；`*_user_edited` 正确 |
| M07 | 应用 File+DB | 标签、MediaStore、Room、封面一致；用 `ffprobe` 验证 |
| M08 | 云曲目应用 | 仅 DB-only 可用；File+DB 置灰并说明 |
| M09 | 只读文件应用 File+DB | 失败提示；可回退 DB-only |
| M10 | 429 响应 | 显示倒计时；倒计时结束前 Retry 不可用 |
| M11 | 跨源重定向 | 明确提示被阻止；无密钥泄露 |
| M12 | 导出 → 删除 → 导入 | 定义恢复；密钥为空；重新输入后可用 |
| M13 | 导入冲突 | 选择 Replace/Copy 后结果符合预期；原密钥保留/新条目无密钥 |
| M14 | 导入损坏 JSON | 不改变现有配置；错误可读 |
| M15 | 恢复出厂/清除数据后导入 | Provider 定义在；密钥需重输；不崩溃 |
| M16 | Release 包（R8） | 保存、重启、导入导出、搜索均正常 |
| M17 | 英文与 zh-rCN | 所有新文案有翻译；无硬编码 |
| M18 | 无 Provider | 单曲页无来源栏；设置页空态正常 |

### 15.4 安全用例

- 恶意响应正文含 `<script>`：UI 只显示文本，不执行。
- 自定义 header 值含 `\r\nX-Evil: 1`：保存被拒绝。
- baseUrl `https://user:pass@host`：拒绝。
- baseUrl `http://8.8.8.8`：拒绝（公网明文）。
- baseUrl `http://192.168.1.10` 未开私网：拒绝；开私网+明文：允许。
- baseUrl `http://169.254.169.254`：即使开私网也拒绝。
- 封面 URL 指向 `http://127.0.0.1` 未开私网：拒绝。
- 导入文件包含 `"apiKey": "secret"`：忽略并警告；日志无该值。
- 日志捕获：搜索一个使用 query 认证的 Provider，日志中无密钥/完整 query。
- 导出文件全文搜索原始密钥：0 命中。

### 15.5 回归

- 现有 MusicBrainz 搜索/应用行为不变。
- 设置搜索的其他条目不受影响。
- AI 设置、备份/恢复、Navidrome/Jellyfin 登录不受影响。
- `:app:assembleRelease` 通过；R8 后 Provider DTO 正常。
- 单曲页在没有 Provider 时布局不变。

### 15.6 测试数据

- 仓库内 fixture 只使用 `https://api.example.com`、`http://10.0.2.2:8080`、`localhost` 等中性地址。
- 示例歌曲用公有领域/自建音频；不提交受版权保护的封面。
- Mock 响应覆盖：扁平、嵌套、数组 artist、缺失字段、429、超时、深 JSON、大响应。

---

## 16. 文件改动清单

### 16.1 新增

| 文件 | 内容 |
|---|---|
| `app/src/main/java/.../data/metadata/MetadataQuery.kt` | 共享查询模型（若 Phase 1 未落） |
| `app/src/main/java/.../data/metadata/MetadataCandidate.kt` | 共享候选/来源/应用目标模型（若 Phase 1 未落） |
| `app/src/main/java/.../data/metadata/provider/schema/ProviderDefinitionDto.kt` | 全部 Gson DTO |
| `app/src/main/java/.../data/metadata/provider/MetadataProvider.kt` | Domain 模型 + 编译后的模板/路径 |
| `app/src/main/java/.../data/metadata/provider/ProviderValidator.kt` | L1–L4 校验 |
| `app/src/main/java/.../data/metadata/provider/ProviderTemplate.kt` | 模板解析/渲染 |
| `app/src/main/java/.../data/metadata/provider/JsonPathEvaluator.kt` | 路径解析/取值 |
| `app/src/main/java/.../data/metadata/provider/SafeJsonParser.kt` | 深度/token 防护 |
| `app/src/main/java/.../data/metadata/provider/ProviderRequestFactory.kt` | URL/query/header/auth 构建 |
| `app/src/main/java/.../data/metadata/provider/ProviderUrlPolicy.kt` | scheme/私网/明文/地址检查 |
| `app/src/main/java/.../data/metadata/provider/ProviderHttpClientFactory.kt` | per-provider OkHttpClient + DNS 固定 |
| `app/src/main/java/.../data/metadata/provider/ProviderHttpTransport.kt` | 请求执行 + 有上限读取 |
| `app/src/main/java/.../data/metadata/provider/ProviderRateLimiter.kt` | 间隔/并发/429 |
| `app/src/main/java/.../data/metadata/provider/ProviderResponseMapper.kt` | JSON → Candidate |
| `app/src/main/java/.../data/metadata/provider/ProviderSearchService.kt` | 编排搜索/详情/测试 |
| `app/src/main/java/.../data/metadata/provider/MetadataProviderRepository.kt` | DataStore 持久化 |
| `app/src/main/java/.../data/metadata/provider/ProviderSecretStore.kt` | 加密密钥 |
| `app/src/main/java/.../data/metadata/provider/ProviderImportExport.kt` | 导入/导出/冲突 |
| `app/src/main/java/.../presentation/screens/settings/MetadataProviderListScreen.kt` | 列表页 |
| `app/src/main/java/.../presentation/screens/settings/MetadataProviderEditorScreen.kt` | 编辑器 |
| `app/src/main/java/.../presentation/viewmodel/MetadataProviderListViewModel.kt` | 列表状态/动作 |
| `app/src/main/java/.../presentation/viewmodel/MetadataProviderEditorViewModel.kt` | 草稿/校验/测试/保存 |
| `app/src/main/java/.../presentation/components/ProviderTestPanel.kt` | 测试结果/原始响应 |
| `app/src/main/java/.../presentation/components/ProviderImportSheet.kt` | 导入预览/冲突 |
| `app/src/main/res/values/strings_metadata_providers.xml` | 英文文案 |
| `app/src/main/res/values-zh-rCN/strings_metadata_providers.xml` | 中文文案 |
| `app/src/test/java/.../data/metadata/provider/*Test.kt` | 15.1 的测试类 |

### 16.2 修改

| 文件 | 改动 |
|---|---|
| `presentation/navigation/Screen.kt` | 新增两个 route + `createRoute` |
| `presentation/navigation/AppNavigation.kt` | 注册两个 composable |
| `presentation/screens/SettingsCategoryScreen.kt` | DEVELOPER 分支新增入口 |
| `presentation/settings/search/SettingsRegistry.kt` | 新增 `SettingSpec` |
| `presentation/components/SongInfoBottomSheet.kt` | 来源筛选、Provider 结果组、详情/错误状态 |
| `presentation/viewmodel/SongInfoBottomSheetViewModel.kt` | 接入 `MetadataSearchOrchestrator`/Provider 搜索状态 |
| `app/proguard-rules.pro` | keep `data.metadata.provider.schema.**`（Gson 字段） |
| `presentation/viewmodel/MetadataEditStateHolder.kt`（Phase 1） | 提供 `applyOnlineMetadata` 契约 |
| `data/database/MusicDao.kt`（Phase 1） | 如 Phase 1 需要通用字段更新；Phase 3 不新增列 |
| `res/values/strings.xml` / `values-zh-rCN/strings.xml`（仅当复用现有 key） | 尽量放新文件，避免冲突 |

### 16.3 明确不改

| 文件/区域 | 原因 |
|---|---|
| `AndroidManifest.xml` | `INTERNET`、`ACCESS_LOCAL_NETWORK` 已声明；不新增权限 |
| Room schema / `Migrations.kt` | 不新增溯源列 |
| `app/build.gradle.kts` 依赖 | Gson/OkHttp/security-crypto 已有 |
| `BackupSection` / 备份模块 | Phase 3 不接入全局备份 |
| 现有 MusicBrainz 客户端 | 保持内置 1.1 s 限速与行为 |

### 16.4 R8 / Gson

在 `app/proguard-rules.pro` 增加：

```proguard
# Custom metadata provider configuration is persisted and exported as Gson JSON.
# Renamed fields would make a stored/exported file unreadable (same failure mode as
# the backup payloads documented above).
-keep class com.lostf1sh.pixelplayeross.data.metadata.provider.schema.** { *; }
```

- DTO 字段仍写 `@SerializedName`，双保险。
- Release 验收必须包含“保存 → 杀进程 → 重启 → 读取”与“导出 → 导入”。

### 16.5 依赖

- 不新增运行时依赖。
- 不新增测试依赖：本地 HTTP 用 JDK `com.sun.net.httpserver.HttpServer`；协程测试用现有 `kotlinx-coroutines-test`。

---

## 17. 实施步骤与工作量

### 17.1 步骤

| 步骤 | 内容 | 依赖 |
|---|---|---|
| S1 | 落 `MetadataQuery` / `MetadataCandidate` / `MetadataApplyTarget` 契约与单元测试 | Phase 1 或本阶段先落 |
| S2 | DTO + Validator + 模板 + 路径引擎 + `SafeJsonParser` | S1 |
| S3 | URL 策略 + HTTP client 工厂 + transport + 限速器 + 错误模型 | S2 |
| S4 | 密钥存储 + Repository + 导入导出 | S2/S3 |
| S5 | 响应映射 + ProviderSearchService + 调分/去重 | S3/S4 |
| S6 | 列表页 + 编辑器 + 测试面板 + 导航/设置入口/文案 | S4/S5 |
| S7 | 单曲资料页集成 + 详情 + 封面 + 应用目标 | S6 + Phase 1 写入 |
| S8 | 安全/集成/真机测试 + R8 验收 + 文案复核 | S7 |

### 17.2 依赖关系

- S7 的应用目标依赖 Phase 1 的 `applyOnlineMetadata`；如果 Phase 1 未完成，S1–S6 可以先做，S7 等 Phase 1。
- S6 的 UI 可以先用假数据完成，不阻塞引擎。
- S8 的 R8 验收必须在 release 配置下做，不能只跑 debug。

### 17.3 工作量

| 部分 | 规模 |
|---|---|
| 引擎（校验/模板/路径/网络/映射） | 大；约 15 个新文件 |
| UI（列表/编辑器/测试/导入） | 中到大；约 8 个新文件 |
| 集成（单曲页/应用目标） | 中；依赖 Phase 1 |
| 测试 | 大；15+ 测试类 |
| 总计 | 大于 Phase 1，小于“再接两个内置源”的长期维护成本 |

不给虚假的“人日”数字；按文件与测试面评估，这是一个完整的 Phase，而不是一次小改。

### 17.4 回滚策略

- 功能完全在 Developer 入口之后；移除入口即可对普通用户隐藏。
- 数据层新增一个 DataStore key，不影响旧版本读取。
- 若需彻底移除：删除入口/路由/新文件，保留 DataStore key（旧版本忽略），不触碰 Room。
- 不修改现有 MusicBrainz/备份路径，回滚风险低。

---

## 18. 验收标准

### 18.1 功能

- [ ] 无 Provider 时，单曲资料页与设置页行为与改动前一致。
- [ ] 能新建 Provider，保存后重启仍存在。
- [ ] Test 能显示 HTTP 状态、耗时、候选、字段映射、截断原始响应。
- [ ] 能从单曲页搜索自定义 Provider，结果与内置源分组显示。
- [ ] 能预览、勾选字段、选择 DB-only / File+DB 并应用。
- [ ] DB-only 后文件 SHA-256 不变；Room 字段更新；`*_user_edited` 正确。
- [ ] File+DB 后标签、MediaStore、Room、封面一致。
- [ ] 云曲目只允许 DB-only；只读文件可回退 DB-only。
- [ ] 详情失败/封面失败不阻止文本字段应用。
- [ ] 429 显示冷却；不在冷却期发请求。
- [ ] 导出不含任何密钥；导入后需重新输入。
- [ ] 导入冲突的 Keep/Replace/Copy 行为符合 14.3。
- [ ] 导入无效文件不改变现有配置。

### 18.2 安全

- [ ] 公网 `http://` 被拒绝。
- [ ] 私网/回环默认被拒绝；显式开启并授权本地网络权限后才可用。
- [ ] `169.254.169.254`、链路本地、组播在任何模式下都被拒绝。
- [ ] 跨源重定向被拒绝；同源重定向最多 2 次。
- [ ] 超大响应、超深 JSON、错误 Content-Type 被拒绝。
- [ ] 封面请求不携带认证。
- [ ] 自定义 header CRLF 注入被拒绝。
- [ ] 日志、错误信息、导出文件中无密钥。
- [ ] 密钥不可解密时不回退明文、不崩溃。

### 18.3 工程

- [ ] 所有新文案在 `values/` 与 `values-zh-rCN/` 中齐全，无硬编码。
- [ ] 设置搜索能命中新入口。
- [ ] `:app:assembleRelease` 通过；R8 后 DTO 持久化/导入导出正常。
- [ ] 单元测试、集成测试通过。
- [ ] 真机完成 15.3 的全部 M 用例。
- [ ] 无新增权限、无新增 Room 迁移、无新增运行时依赖。

---

## 19. 风险与待确认问题

### 19.1 风险

| 风险 | 影响 | 缓解 |
|---|---|---|
| 用户用该功能接入违反条款的服务 | 用户违约；项目声誉风险 | 零预置、用户承诺、中性文案、无规避能力；不宣传为“解锁” |
| 字段映射复杂度导致配置门槛高 | 普通高级用户放弃 | 测试面板、字段级告警、示例路径、编辑器内联提示 |
| 详情请求翻倍 | 触发限速/延迟 | 详情可选；仅在选中时请求；与搜索共享限速 |
| Provider 配置损坏/密钥丢失 | 用户需重建 | 保留原文、可导出 raw、明确 Needs key、不自动删除 |
| R8 混淆 DTO | 发布版读写失败 | keep 规则 + `@SerializedName` + release 验收 |
| 私网/明文开关被滥用 | 扫描内网/明文泄露 | 默认关闭、独立开关、本地网络权限、链路本地永远拒绝 |
| 封面 URL 指向内网 | SSRF | 封面同样过主机策略；不带认证 |
| 无来源溯源列 | 用户事后不知道字段来自哪个源 | 应用前明确显示来源；不新增列，避免迁移 |
| 其他语言缺翻译 | 回退英文 | 只维护 EN + zh-rCN，其他语言明确回退 |

### 19.2 待确认（附推荐默认值）

| 编号 | 问题 | 推荐 |
|---|---|---|
| Q1 | 入口放 Developer 还是新建设置分类？ | **Developer**；等使用量证明后再提升 |
| Q2 | DB-only 是否写封面？ | **不写**；避免持久缓存与版权问题 |
| Q3 | 是否接入应用内全局备份？ | **不接入**；只用 SAF 导入导出 |
| Q4 | 自定义源是否与内置源自动并发搜索？ | **不并发**；用户显式选择，降低限速/条款风险 |
| Q5 | 是否支持 POST/OAuth？ | **v1 不支持**；按真实需求再立项 |
| Q6 | 是否支持 HTML 抓取？ | **永久不支持** |
| Q7 | 是否允许导出包含密钥？ | **永不**；即使二次确认也不提供 |
| Q8 | 是否在候选列表加载网络封面？ | **不加载**；预览时再加载 |
| Q9 | 跨源合并去重是否默认开启？ | **默认分组**；用户主动“Show all”再合并 |
| Q10 | 已知主机的限速是否强制？ | **强制上调**；UI 显示 effective 值 |

---

## 20. 附录

### 20.1 字符串 key 清单（首批）

```
metadata_providers_title
metadata_providers_subtitle
metadata_providers_intro
metadata_providers_add
metadata_providers_empty_title
metadata_providers_empty_body
metadata_providers_import
metadata_providers_export
metadata_providers_help
metadata_providers_delete_title
metadata_providers_delete_body
metadata_providers_delete_confirm
metadata_providers_duplicate
metadata_providers_enabled
metadata_providers_status_ready
metadata_providers_status_needs_key
metadata_providers_status_invalid
metadata_providers_status_disabled
metadata_providers_badge_search
metadata_providers_badge_cover
metadata_providers_badge_detail
metadata_providers_last_test_never
metadata_providers_last_test_ok
metadata_providers_last_test_failed
metadata_providers_damaged_title
metadata_providers_damaged_body
metadata_providers_export_raw
metadata_providers_reset
metadata_provider_editor_title_new
metadata_provider_editor_title_edit
metadata_provider_section_basics
metadata_provider_section_auth
metadata_provider_section_search
metadata_provider_section_response
metadata_provider_section_network
metadata_provider_section_rate
metadata_provider_name
metadata_provider_base_url
metadata_provider_attribution
metadata_provider_auth_type
metadata_provider_auth_location
metadata_provider_auth_name
metadata_provider_secret
metadata_provider_secret_saved
metadata_provider_secret_replace
metadata_provider_secret_clear
metadata_provider_path
metadata_provider_query_params
metadata_provider_query_name
metadata_provider_query_template
metadata_provider_query_omit_empty
metadata_provider_add_param
metadata_provider_headers
metadata_provider_header_name
metadata_provider_header_value
metadata_provider_add_header
metadata_provider_max_results
metadata_provider_items_path
metadata_provider_field_id
metadata_provider_field_title
metadata_provider_field_artist
metadata_provider_field_album
metadata_provider_field_album_artist
metadata_provider_field_year
metadata_provider_field_track
metadata_provider_field_disc
metadata_provider_field_duration
metadata_provider_field_duration_unit
metadata_provider_field_isrc
metadata_provider_field_cover
metadata_provider_field_score
metadata_provider_field_score_scale
metadata_provider_detail_path
metadata_provider_allow_private
metadata_provider_allow_cleartext
metadata_provider_effective_rate
metadata_provider_min_interval
metadata_provider_max_concurrent
metadata_provider_test
metadata_provider_save
metadata_provider_save_errors
metadata_provider_attest_title
metadata_provider_attest_body
metadata_provider_attest_checkbox
metadata_provider_unsaved_title
metadata_provider_unsaved_body
metadata_provider_test_title
metadata_provider_test_artist
metadata_provider_test_album
metadata_provider_test_run
metadata_provider_test_running
metadata_provider_test_http
metadata_provider_test_latency
metadata_provider_test_candidates
metadata_provider_test_raw
metadata_provider_test_copy
metadata_provider_test_truncated
metadata_provider_import_title
metadata_provider_import_summary
metadata_provider_import_conflict
metadata_provider_import_keep
metadata_provider_import_replace
metadata_provider_import_copy
metadata_provider_import_skip_invalid
metadata_provider_export_note
metadata_provider_error_* (见 V001–V038)
metadata_provider_warning_*
```

### 20.2 错误码与 HTTP 映射

| HTTP | `ProviderError` | 可重试 |
|---|---|---|
| 200–299 | 进入解析 | — |
| 301/302/303/307/308 | 重定向处理；跨源 → `RedirectBlocked` | 否 |
| 400 | `Http(400)` | 否 |
| 401/403 | `Http(401/403)` → AuthError | 否 |
| 404 | `Http(404)` → EndpointNotFound | 否 |
| 408 | `Timeout`/`Http(408)` | 是 |
| 429 | `RateLimited` | 冷却后 |
| 500–599 | `Http(code)` | 是 |
| 其他 | `Http(code)` | 否 |

### 20.3 代码证据（仓库相对路径）

| 事实 | 位置 |
|---|---|
| 设置分类枚举 | `app/src/main/java/com/lostf1sh/pixelplayeross/presentation/model/SettingsCategory.kt:16` |
| Developer/AI 子区块 | `app/src/main/java/com/lostf1sh/pixelplayeross/presentation/screens/SettingsCategoryScreen.kt:1292`、`:1382` |
| 设置搜索注册 | `app/src/main/java/com/lostf1sh/pixelplayeross/presentation/settings/search/SettingsRegistry.kt:636` |
| 导航 route | `app/src/main/java/com/lostf1sh/pixelplayeross/presentation/navigation/Screen.kt:8`、`:19`、`:27` |
| 导航注册 | `app/src/main/java/com/lostf1sh/pixelplayeross/presentation/navigation/AppNavigation.kt:254`、`:305` |
| 现有 MusicBrainz 搜索/限速/调分 | `app/src/main/java/com/lostf1sh/pixelplayeross/data/musicbrainz/MusicBrainzApiService.kt:30`、`:48`、`:51`、`:193` |
| 现有 MusicBrainz 应用 | `app/src/main/java/com/lostf1sh/pixelplayeross/data/musicbrainz/MusicBrainzRepository.kt:24` |
| 只填空的 DAO | `app/src/main/java/com/lostf1sh/pixelplayeross/data/database/MusicDao.kt:1905`、`:1926` |
| 单曲资料页对话框 | `app/src/main/java/com/lostf1sh/pixelplayeross/presentation/components/SongInfoBottomSheet.kt:922` |
| ViewModel 搜索/应用状态 | `app/src/main/java/com/lostf1sh/pixelplayeross/presentation/viewmodel/SongInfoBottomSheetViewModel.kt:134`、`:151` |
| DB 字段 | `app/src/main/java/com/lostf1sh/pixelplayeross/data/database/SongEntity.kt:86`、`:95`、`:99` |
| DataStore 单例 | `app/src/main/java/com/lostf1sh/pixelplayeross/data/preferences/UserPreferencesRepository.kt:39`；`app/src/main/java/com/lostf1sh/pixelplayeross/di/AppModule.kt:115` |
| 加密 Prefs 范例 | `app/src/main/java/com/lostf1sh/pixelplayeross/data/listenbrainz/ListenBrainzRepository.kt:79` |
| 本地网络权限范例 | `app/src/main/java/com/lostf1sh/pixelplayeross/presentation/navidrome/auth/NavidromeLoginActivity.kt:189` |
| security-crypto 依赖 | `app/build.gradle.kts:339` |
| OkHttp 版本 | `gradle/libs.versions.toml:80` |
| Manifest 明文开关 | `app/src/main/AndroidManifest.xml:52` |
| R8 Gson 先例 | `app/proguard-rules.pro:27`–`43` |
| 现有 AI 设置 UI | `app/src/main/java/com/lostf1sh/pixelplayeross/presentation/screens/AiSettingsScreen.kt:57` |
| 现有 AI 偏好备份 | `app/src/main/java/com/lostf1sh/pixelplayeross/data/preferences/AiPreferencesRepository.kt:301`、`:321` |

### 20.4 参考文档

- [`metadata-search-plan.md`](metadata-search-plan.md) §5.8–§5.10（覆盖率、其他源、自定义 Provider）。
- 《音乐标签》取证（本地调研文档，未随仓库发布）。
- MusicBrainz API：https://musicbrainz.org/doc/MusicBrainz_API
- Cover Art Archive：https://coverartarchive.org/
- Discogs API：https://www.discogs.com/developers
- Android `EncryptedSharedPreferences`：https://developer.android.com/reference/androidx/security/crypto/EncryptedSharedPreferences
- OkHttp `Dns`：https://square.github.io/okhttp/5.x/okhttp/okhttp3/-dns/

---

## 结论

自定义 Provider 是一个**通用 JSON 适配器**，适合高级用户接入自建或已授权的 API；它降低项目内置逆向接口的风险，但不提供曲库覆盖，也不免除用户的条款与版权责任。

实施顺序不变：

1. Phase 1：MusicBrainz + CAA，单曲搜索/预览/字段选择/DB-only 与 File+DB。
2. Phase 2：Discogs 作为第二内置源。
3. Phase 3：本文的自定义 Provider。

在任何阶段，都不要用“可接网易云/QQ”做宣传；文案只说“连接你有权使用的 JSON API”。
