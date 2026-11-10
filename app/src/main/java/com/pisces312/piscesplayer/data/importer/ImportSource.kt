/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Original work: ported from the author's china-only PixelPlayer fork.
 */
package com.pisces312.piscesplayer.data.importer

import android.net.Uri

/**
 * 第三方导入来源接口（预留扩展点）。
 * 当前实现：Poweramp（`.poweramp-backup`）。
 */
interface ImportSource {
    val id: String                              // "poweramp"
    val displayName: String
    val supportedFileExtensions: List<String>   // [".poweramp-backup"]

    /** 解出统一中间数据；损坏/非预期文件应抛出 [ImportParseException]。 */
    suspend fun parse(uri: Uri): ImportSourceData
}

/** 备份文件无法识别时的可读错误（"不是有效的 Poweramp 备份"）。 */
class ImportParseException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** 统一中间数据（跨来源通用）。 */
data class ImportSourceData(
    val playlists: List<ImportPlaylist>,            // 播放列表
    val songRecords: Map<String, ImportSongRecord>  // key = 去重键（Poweramp = path，CUE 场景为 path#offset）
)

/** songKeys 顺序即来源内顺序（导入时保序）。 */
data class ImportPlaylist(val name: String, val songKeys: List<String>)

data class ImportSongRecord(
    val path: String,                   // 来源相对路径（Poweramp: primary/... 或 SD 卷前缀，或裸文件名）
    val titleHint: String?,             // 首选 readable_name（实测 96%+ 为纯标题）
    val artistHint: String?,            // 唯一来源是文件名解析（readable_name 不含歌手）
    val albumHint: String?,             // 来自目录名，仅少数歌曲可获得；作消歧加分项
    val rating: Int,                    // 0–5（NULL → 0）
    val playCount: Int,                 // played_times 归一化（played_at>0 且次数为 0 → 至少 1 次）
    val lastPlayedAt: Long?,            // played_at，单位毫秒
    val playedFullyAt: Long?,           // played_fully_at；0 = 从未完整播放（预留，暂不落地）
    val totalPlayDurationMs: Long?      // Poweramp 无此数据，恒为 null（不捏造时长）
)

/** 导入选项（对应向导 Options 步骤）。 */
data class ImportOptions(
    val replaceMode: Boolean = false,       // false = 合并（默认）；true = 替换（仅历史/统计生效）
    val importPlaylists: Boolean = true,
    val importHistory: Boolean = true,
    val importEngagement: Boolean = true,
    val importFavorites: Boolean = true,    // 含评分（rating 一并写入 favorites 表）
    val favoriteRatingThreshold: Int = 4    // rating >= 阈值 → isFavorite = true
)

/** 导入执行进度（分步骤上报，供 UI 进度条）。 */
data class ImportProgress(
    val step: Step,
    val current: Int,
    val total: Int
) {
    enum class Step { PARSING, MATCHING, PLAYLISTS, HISTORY, ENGAGEMENT, FAVORITES }
}

/** 结果报告模型。 */
data class ImportResult(
    val matchedSongs: Int,
    val unresolvedSongs: Int,
    val unresolvedExamples: List<String>,
    val playlistsCreated: Int,
    val playlistsMerged: Int,
    val historyEventsImported: Int,
    val engagementImported: Int,
    val favoritesImported: Int,
    val ratingsSaved: Int,
    val skippedEmptyPlaylists: Int
)

/** 预览模型（解析后、执行前展示给用户）。 */
data class ImportPreview(
    val sourceId: String,
    val playlistCount: Int,
    val songCount: Int,                     // 去重后歌曲数
    val ratedCount: Int,                    // rating > 0 的歌曲数
    val ratingDistribution: Map<Int, Int>,  // 星级 → 数量
    val playedCount: Int,                   // lastPlayedAt > 0 的歌曲数
    val matchedEstimate: Int,               // 预估可匹配歌曲数
    val matchRate: Float                    // matchedEstimate / songCount
)
