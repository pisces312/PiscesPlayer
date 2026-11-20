package com.pisces312.piscesplayer.data.classical

import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 内置古典曲库的运行时曲名目录（原文 / 英文 / 中文），由
 * [ClassicalLibraryInitializer] 从解压出来的 `classical_names.json` 装载。
 *
 * 曲名映射的 source of truth 是 P0 工具链的 `title_map.json`（人工维护），
 * 本目录只是它的运行时派生缓存 —— 只读，不落库、不回写。
 */
@Singleton
class ClassicalNameCatalog @Inject constructor() {

    @Volatile
    private var names: ClassicalNames = ClassicalNames.EMPTY

    fun load(loaded: ClassicalNames) {
        names = loaded
    }

    fun songName(songId: Long): NameSet? = names.songs[songId]

    /** [com.pisces312.piscesplayer.data.model.Song.id] 是字符串型，便捷重载。 */
    fun songName(songId: String): NameSet? = songId.toLongOrNull()?.let { names.songs[it] }

    fun artistName(artistId: Long): NameSet? = names.artists[artistId]

    fun albumName(albumId: Long): NameSet? = names.albums[albumId]
}

/** 双语展示结果：主标题 + 可选副标题（与主标题不同的那一层）。 */
data class ClassicalDisplayNames(
    val primary: String,
    val subtitle: String?
)

/**
 * 古典曲名双语展示规则（产品决定）：
 * - **翻译开**：译名为主、原文为副；译名跟随系统语言（中文环境→中文名，英文环境→英文名）。
 * - **翻译关**：原文为主、译名为副。
 * - 两行永远是「原文 + 译名」两种文字（主副标题双显）；译名缺失/与原文相同时只显示一层。
 */
object ClassicalDisplay {

    fun resolve(
        names: NameSet,
        translate: Boolean,
        useChinese: Boolean = isChineseLocale()
    ): ClassicalDisplayNames {
        val translated = if (useChinese) {
            names.zh.takeIf { it.hasCjk() } ?: names.en
        } else {
            names.en
        }
        return if (translate && translated.isNotBlank() && translated != names.original) {
            ClassicalDisplayNames(
                primary = translated,
                subtitle = names.original.takeIf { it.isNotBlank() && it != translated }
            )
        } else {
            ClassicalDisplayNames(
                primary = names.original.ifBlank { translated },
                subtitle = translated.takeIf { it.isNotBlank() && it != names.original }
            )
        }
    }

    fun isChineseLocale(): Boolean =
        Locale.getDefault().language.startsWith("zh")

    private fun String.hasCjk(): Boolean = any { it in '\u4e00'..'\u9fff' }
}
