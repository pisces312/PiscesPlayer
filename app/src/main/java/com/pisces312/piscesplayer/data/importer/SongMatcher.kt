/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Original work: ported from the author's china-only PixelPlayer fork.
 */
package com.pisces312.piscesplayer.data.importer

import com.pisces312.piscesplayer.data.database.ImportSongProjection
import com.pisces312.piscesplayer.data.importer.poweramp.PowerampPathNormalizer

/**
 * 歌曲匹配器——导入的关键模块。
 *
 * 三级匹配（从快到准、从准到兜底，宁缺勿错）：
 * 1. 绝对路径精确匹配（同设备命中率预期 99.9%）
 * 2. 文件名匹配（兜住存储根差异 / SD 卡卷变化 / 裸文件名）
 * 3. 元数据匹配（title|artist 归一化索引 + 目录名消歧加分项）
 *
 * artist/title 顺序不固定（以「歌名 - 歌手」为主），必须双向试探。
 *
 * 索引在构造时一次性建好（调用方先用 `MusicDao.getAllLocalSongsForImport()` 载入）。
 */
class SongMatcher(
    private val normalizer: PowerampPathNormalizer,
    songs: List<ImportSongProjection>
) {
    private val pathIndex: Map<String, ImportSongProjection> =
        songs.filter { it.filePath.isNotBlank() }.associateBy { it.filePath }

    private val fileNameIndex: Map<String, List<ImportSongProjection>> =
        songs.filter { it.filePath.isNotBlank() }
            .groupBy { it.filePath.substringAfterLast('/') }

    private val metadataIndex: Map<String, List<ImportSongProjection>> =
        songs.groupBy { matchKey(it.title, it.artistName) }

    private val titleIndex: Map<String, List<ImportSongProjection>> =
        songs.groupBy { normalizeText(it.title) }

    // 各级命中计数（prepare 单协程内调用，无需同步；用于诊断「为什么没匹配上」）
    var matchedByPath = 0
        private set
    var matchedByFileName = 0
        private set
    var matchedByMetadata = 0
        private set
    var unresolvedCount = 0
        private set

    /**
     * 匹配单条导入记录。
     * @return 命中的本地歌曲；无法确定（无候选或歧义）时返回 null。
     */
    fun match(record: ImportSongRecord): ImportSongProjection? {
        // 第 1 级：绝对路径精确匹配
        normalizer.normalize(record.path)?.let { absolute ->
            pathIndex[absolute]?.let {
                matchedByPath++
                return it
            }
        }

        val fileName = normalizer.fileName(record.path)
        val dirHints = normalizer.directoryHints(record.path)
        // 目录信号（加分项）：…/周杰伦/十一月的萧邦/ → artist=周杰伦, album=十一月的萧邦
        val albumHint = dirHints.lastOrNull()
        val artistDirHint = dirHints.dropLast(1).lastOrNull()

        // 第 2 级：文件名匹配（含扩展名）
        fileNameIndex[fileName]?.let { candidates ->
            if (candidates.size == 1) {
                matchedByFileName++
                return candidates[0]
            }
            disambiguate(candidates, albumHint, artistDirHint)?.let {
                matchedByFileName++
                return it
            }
        }

        // 第 3 级：元数据匹配（双向试探 artist/title 顺序）
        val hit = matchByMetadata(record, fileName, albumHint, artistDirHint)
        if (hit != null) matchedByMetadata++ else unresolvedCount++
        return hit
    }

    /** 各级命中情况汇总，供导入日志输出。 */
    fun summary(): String = "路径=$matchedByPath 文件名=$matchedByFileName 元数据=$matchedByMetadata 未匹配=$unresolvedCount"

    private fun matchByMetadata(
        record: ImportSongRecord,
        fileName: String,
        albumHint: String?,
        artistDirHint: String?
    ): ImportSongProjection? {
        val candidates = buildTitleArtistCandidates(record.titleHint, fileName)

        // 收集全部候选 (title, artist) 命中的不同歌曲
        val hits = linkedMapOf<Long, ImportSongProjection>()
        for ((title, artist) in candidates) {
            val key = matchKey(title, artist)
            val bucket = metadataIndex[key] ?: continue
            val resolved = if (bucket.size == 1) bucket[0]
            else disambiguate(bucket, albumHint, artistDirHint)
            if (resolved != null) hits[resolved.id] = resolved
        }
        if (hits.size == 1) return hits.values.first()
        if (hits.size > 1) {
            disambiguate(hits.values.toList(), albumHint, artistDirHint)?.let { return it }
            return null // 多候选命中不同歌曲且无法消歧 → 宁缺勿错
        }

        // 回退：title-only（readable_name / 主干本身就是纯标题，实测 96%+）
        val titleCandidates = buildList {
            record.titleHint?.takeIf { it.isNotBlank() }?.let { add(it) }
            candidates.forEach { (t, _) -> add(t) }
        }.map { normalizeText(it) }.distinct()

        val titleHits = linkedMapOf<Long, ImportSongProjection>()
        for (t in titleCandidates) {
            val bucket = titleIndex[t] ?: continue
            val resolved = if (bucket.size == 1) bucket[0]
            else disambiguate(bucket, albumHint, artistDirHint)
            if (resolved != null) titleHits[resolved.id] = resolved
        }
        if (titleHits.size == 1) return titleHits.values.first()
        return null
    }

    /**
     * 从文件名主干构建 (title, artist) 候选对：
     * 去扩展名 → 剥离序号前缀 → 剥离杂质词 → 按分隔符切分 → 双向试探。
     */
    private fun buildTitleArtistCandidates(titleHint: String?, fileName: String): List<Pair<String, String>> {
        var stem = fileName.substringBeforeLast('.')
        // 序号前缀（实测 40.2%：02 - / 11- / 01. / 08你的微笑 这类纯数字粘连不剥，避免误伤）
        stem = NUMBER_PREFIX_REGEX.replace(stem, "")
        // 杂质词：括注版本信息 / 品质标记 / Remix 后缀（规则表可扩展）
        stem = JUNK_REGEX.replace(stem, "").trim()

        val result = linkedSetOf<Pair<String, String>>()

        // 取最后一个分隔符切分（应对多段分隔，如 おどるポンポコリン-... - B.B.Queens）
        val delimIdx = stem.lastIndexOfAny(DELIMITERS)
        if (delimIdx > 0) {
            val delim = DELIMITERS.first { stem.startsWith(it, delimIdx) }
            val left = stem.substring(0, delimIdx).trim()
            val right = stem.substring(delimIdx + delim.length).trim()
            if (left.isNotEmpty() && right.isNotEmpty()) {
                // 双向试探；readable_name 启发式优先（通常等于 title）
                val probeA = left to right   // 歌名 - 歌手
                val probeB = right to left   // 歌手 - 歌名
                when (normalizeText(titleHint ?: "")) {
                    normalizeText(left) -> { result += probeA; result += probeB }
                    normalizeText(right) -> { result += probeB; result += probeA }
                    else -> { result += probeA; result += probeB }
                }
            }
        }
        return result.toList()
    }

    /** 多候选消歧：album 加分项 → artist 目录信号。仍歧义返回 null（宁缺勿错）。 */
    private fun disambiguate(
        candidates: List<ImportSongProjection>,
        albumHint: String?,
        artistDirHint: String?
    ): ImportSongProjection? {
        var pool = candidates
        if (!albumHint.isNullOrBlank()) {
            val filtered = pool.filter { normalizeText(it.albumName) == normalizeText(albumHint) }
            if (filtered.size == 1) return filtered[0]
            if (filtered.isNotEmpty()) pool = filtered
        }
        if (!artistDirHint.isNullOrBlank()) {
            val filtered = pool.filter { normalizeText(it.artistName) == normalizeText(artistDirHint) }
            if (filtered.size == 1) return filtered[0]
        }
        return null
    }

    private fun matchKey(title: String, artist: String): String =
        "${normalizeText(title)}|${normalizeText(artist)}"

    private fun normalizeText(text: String): String = text.trim().lowercase()

    companion object {
        /** 02 - / 11- / 01. / 15_Love Story 等序号前缀 */
        private val NUMBER_PREFIX_REGEX = Regex("""^\s*\d{1,3}\s*[-_.、]\s*""")

        /** 杂质词：括注版本信息 / 品质标记 / Remix 后缀（规则表可扩展） */
        private val JUNK_REGEX = Regex(
            """[（(\[].*?(版|高品质|无损|原声带|Remix|remix|Live|live).*?[）)\]]|_原声带$"""
        )

        /** artist/title 分隔符（注意顺序：长的在前，避免 " - " 被 "-" 抢先） */
        private val DELIMITERS = listOf(" - ", " – ", " — ", "_", "-", "–", "—")
    }
}
