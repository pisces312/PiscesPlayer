package com.pisces312.piscesplayer.data.classical

/**
 * Style taxonomy of the built-in Mutopia pack (`classical_index.json` `style` field).
 *
 * 「古典熏陶」只收录正统古典（时期风格，见 [CLASSICAL_STYLES]）；其余流派（赞美诗、
 * 艺术歌曲/民歌、爵士、练习曲、进行曲、舞曲）归「MIDI 曲库 · 内置 MIDI」，按
 * [BUILTIN_SECTIONS] 顺序分节。未知流派归 [SECTION_OTHER]（不进古典熏陶）。
 */
object ClassicalStyle {

    /** 正统古典（时期风格）——「古典熏陶」收录范围。 */
    val CLASSICAL_STYLES: Set<String> =
        setOf("Classical", "Romantic", "Baroque", "Renaissance", "Modern")

    /** 非古典流派 —— 「内置 MIDI」分节 key，列表顺序即展示顺序。 */
    val BUILTIN_SECTIONS: List<String> =
        listOf("Hymn", "Song", "Jazz", "Technique", "March", "Popular / Dance")

    const val SECTION_OTHER = "Other"

    fun isClassical(style: String?): Boolean = style in CLASSICAL_STYLES

    /** 非古典流派的展示分节 key。 */
    fun sectionOf(style: String?): String = when {
        style == null -> SECTION_OTHER
        style in BUILTIN_SECTIONS -> style
        else -> SECTION_OTHER
    }
}
