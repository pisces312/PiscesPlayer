package com.pisces312.piscesplayer.data.classical

import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A work (作品 / 作品集) inside one composer's classical catalogue: one section of the
 * composer detail screen. Tracks keep index order (movements play in order).
 */
data class ClassicalWork(
    val key: String,
    val names: NameSet,
    val opus: String,
    val trackIds: List<Long>
) {
    val isMultiTrack: Boolean get() = trackIds.size > 1
}

/**
 * Runtime catalogue of data derived from `classical_index.json`, loaded at startup by
 * [ClassicalLibraryInitializer]: work groupings (作品集), per-track `style`, and the
 * per-track file `sha256` (used for user-file/built-in dedup, see the MIDI library plan).
 *
 * Works are **derived**, not stored: the pack schema may later carry precomputed
 * `workId/workTitle/movement` fields (plan P1) — when present those win; until then the
 * [ClassicalWorkDeriver] heuristic groups tracks by opus / title prefix / single piece.
 */
@Singleton
class ClassicalWorkCatalog @Inject constructor() {

    @Volatile
    private var worksByAlbum: Map<Long, List<ClassicalWork>> = emptyMap()

    @Volatile
    private var workByTrack: Map<Long, ClassicalWork> = emptyMap()

    @Volatile
    private var styleByTrack: Map<Long, String> = emptyMap()

    @Volatile
    private var hashByTrack: Map<Long, String> = emptyMap()

    fun load(tracks: List<ClassicalTrack>) {
        worksByAlbum = ClassicalWorkDeriver.derive(tracks)
        workByTrack = buildMap {
            worksByAlbum.values.flatten().forEach { work ->
                work.trackIds.forEach { put(it, work) }
            }
        }
        styleByTrack = tracks.associate { it.id to it.style }
        hashByTrack = tracks.filter { it.sha256.isNotEmpty() }.associate { it.id to it.sha256 }
    }

    fun worksForAlbum(albumId: Long): List<ClassicalWork> = worksByAlbum[albumId].orEmpty()

    fun workOfTrack(trackId: Long): ClassicalWork? = workByTrack[trackId]

    fun styleOf(trackId: Long): String? = styleByTrack[trackId]

    /**
     * 古典熏陶收录判定：目录未装载（null）时视为古典（保持旧行为、不闪空）；
     * 装载后按 [ClassicalStyle] 判定。
     */
    fun isClassicalTrack(trackId: Long): Boolean =
        styleOf(trackId)?.let(ClassicalStyle::isClassical) ?: true

    /** [com.pisces312.piscesplayer.data.model.Song.id] 是字符串型，便捷重载。 */
    fun isClassicalTrack(songId: String): Boolean =
        songId.toLongOrNull()?.let { isClassicalTrack(it) } ?: true

    /** 全文件 SHA-256（index 自带）；无值返回 null。 */
    fun fileSha256Of(trackId: Long): String? = hashByTrack[trackId]

    /** trackId → 全文件 sha256（内置侧 L1 hash 表）。 */
    fun fileHashes(): Map<Long, String> = hashByTrack
}

/**
 * Pure derivation of work groupings from index tracks. Grouping rules per composer,
 * in priority order (see plan §B4):
 *
 * 1. non-blank `opus` → one work per opus ("op. 30" collects its songs / movements);
 * 2. title prefix before a numbered separator ("La Traviata - N1 …" → "La Traviata");
 * 3. otherwise the piece is its own work, and same-titled tracks (different
 *    arrangements) share one work.
 */
object ClassicalWorkDeriver {

    /** `^(prefix) - N1 …` / `^(prefix): 2 …` — a movement/number marker after a separator. */
    private val PREFIX_REGEX = Regex("""^(.+?)\s*[-\u2013\u2014:]\s*(?:N|No\.?)?\s*\d+""", RegexOption.IGNORE_CASE)

    fun derive(tracks: List<ClassicalTrack>): Map<Long, List<ClassicalWork>> =
        tracks.sortedBy { it.id }
            .groupBy { it.albumId }
            .mapValues { (_, albumTracks) -> deriveForAlbum(albumTracks) }

    private fun deriveForAlbum(tracks: List<ClassicalTrack>): List<ClassicalWork> {
        val order = LinkedHashMap<String, MutableList<ClassicalTrack>>()
        for (t in tracks) {
            order.getOrPut(workKey(t)) { mutableListOf() }.add(t)
        }
        return order.map { (key, group) -> buildWork(key, group) }
    }

    fun workKey(t: ClassicalTrack): String {
        val opus = t.opus.trim()
        if (opus.isNotEmpty()) return "opus:${opus.lowercase(Locale.ROOT)}"
        prefixOf(t.title)?.let { return "prefix:${it.lowercase(Locale.ROOT)}" }
        return "title:${normalizeTitle(t.title)}"
    }

    private fun buildWork(key: String, group: List<ClassicalTrack>): ClassicalWork {
        val first = group.first()
        val opus = group.firstOrNull { it.opus.isNotBlank() }?.opus?.trim().orEmpty()
        val names = when {
            key.startsWith("opus:") -> {
                // Same base title across tracks → name the work by the piece ("Rondo");
                // divergent bases ("Abendlied, op. 30, Nr. 2" + "Ich halte…") → the opus itself.
                val bases = group.map { stripOpus(it.titleOriginal, it.opus) }
                if (bases.all { it.isNotBlank() && it == bases.first() }) {
                    NameSet(
                        original = bases.first(),
                        en = group.map { stripOpus(it.titleEn, it.opus) }.firstOrNull { it.isNotBlank() } ?: bases.first(),
                        zh = group.map { stripOpus(it.titleZh, it.opus) }.firstOrNull { it.isNotBlank() }.orEmpty()
                    )
                } else {
                    NameSet(original = opus, en = opus, zh = "")
                }
            }
            key.startsWith("prefix:") -> NameSet(
                original = prefixOf(first.titleOriginal) ?: first.titleOriginal,
                en = prefixOf(first.titleEn) ?: first.titleEn,
                zh = prefixOf(first.titleZh).orEmpty()
            )
            else -> NameSet(
                original = first.titleOriginal,
                en = first.titleEn,
                zh = first.titleZh
            )
        }
        return ClassicalWork(
            key = key,
            names = names,
            opus = opus,
            trackIds = group.map { it.id }
        )
    }

    /** Prefix of a movement-style title, e.g. "La Traviata - N1 Introduzione" → "La Traviata". */
    internal fun prefixOf(title: String): String? =
        PREFIX_REGEX.find(title.trim())?.groupValues?.get(1)?.trim()
            ?.takeIf { it.length >= 2 }

    /** [title] with the [opus] fragment and trailing separators removed; blank when absent. */
    internal fun stripOpus(title: String, opus: String): String {
        if (opus.isBlank()) return title.trim()
        val idx = title.indexOf(opus, ignoreCase = true)
        if (idx < 0) return ""
        return title.substring(0, idx).trim().trimEnd(',', '-', '\u2013', '\u2014', ':', ' ').trim()
    }

    private fun normalizeTitle(title: String): String =
        title.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }
}
