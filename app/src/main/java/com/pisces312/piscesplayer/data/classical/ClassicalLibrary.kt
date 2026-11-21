package com.pisces312.piscesplayer.data.classical

import org.json.JSONArray

/**
 * Shared constants and index parsing for the built-in classical MIDI library.
 *
 * The resource pack is a single asset (`classical.zip`) produced by the P0 tooling
 * (see the classical-midi-library-plan design doc). It contains the verified `.mid`
 * files plus `classical_index.json`, which is the canonical id map: song / album /
 * artist ids are baked in at build time and must stay stable across resource updates.
 *
 * **Pack identity is self-describing**: `classical_pack.json` (the first zip entry)
 * carries a content hash (`packHash`) over the tracks + names payload. The installer
 * compares it against the hash stored at install time, so updating the resource pack
 * is rebuild + swap assets with **zero app-code changes** — there is no version
 * constant to remember to bump. Identical content reproduces the same hash, so
 * app releases that ship an unchanged pack never trigger a reinstall.
 */
object ClassicalLibrary {

    const val SCHEME = "piscesmidi"
    const val URI_PREFIX = "piscesmidi://"

    const val PREFS_NAME = "classical_library"

    /** Installed pack content hash (see [PACK_ENTRY]); null = nothing installed yet. */
    const val PREF_PACK_HASH = "classical_library_pack_hash"

    /** One-shot flag: pre-exclusion listening-stats rows have been purged. */
    const val PREF_STATS_PURGE_DONE = "classical_stats_purge_v1"

    const val SONG_ID_BASE = 15_000_000_000_000L
    const val ALBUM_ID_BASE = 16_000_000_000_000L
    const val ARTIST_ID_BASE = 17_000_000_000_000L

    const val ASSET_ZIP = "classical.zip"
    const val PACK_ENTRY = "classical_pack.json"
    const val INDEX_ENTRY = "classical_index.json"
    const val NAMES_ENTRY = "classical_names.json"
    const val EXTRACT_DIR_NAME = "classical"

    fun contentUri(songId: Long): String = "$URI_PREFIX$songId"

    /** `packHash` from a `classical_pack.json` document. */
    fun parsePackHash(json: String): String =
        org.json.JSONObject(json).optString("packHash")

    fun parseIndex(json: String): List<ClassicalTrack> {
        val root = org.json.JSONObject(json)
        val tracksJson: JSONArray = root.getJSONArray("tracks")
        return buildList {
            for (i in 0 until tracksJson.length()) {
                val t = tracksJson.getJSONObject(i)
                val title = t.getString("title")
                add(
                    ClassicalTrack(
                        id = t.getLong("id"),
                        file = t.getString("file"),
                        title = title,
                        titleOriginal = t.optString("titleOriginal").ifBlank { title },
                        titleEn = t.optString("titleEn"),
                        titleZh = t.optString("titleZh"),
                        composer = t.getString("composer"),
                        albumName = t.optString("albumName").ifBlank { "古典熏陶 · " + t.getString("composer") },
                        albumId = t.optLong("albumId").takeIf { it != 0L }
                            ?: (ALBUM_ID_BASE + t.optLong("composerIndex")),
                        artistId = t.optLong("artistId").takeIf { it != 0L }
                            ?: (ARTIST_ID_BASE + t.optLong("composerIndex")),
                        opus = t.optString("opus").trim(),
                        durationMs = t.getLong("durationMs")
                    )
                )
            }
        }
    }

    /** 解析 `classical_names.json`（song/artist/album id → 原文/英文/中文 曲名目录）。 */
    fun parseNames(json: String): ClassicalNames {
        val root = org.json.JSONObject(json)

        fun section(key: String): Map<Long, NameSet> {
            val obj = root.optJSONObject(key) ?: return emptyMap()
            return buildMap {
                for (k in obj.keys()) {
                    val n = obj.getJSONObject(k)
                    put(
                        k.toLongOrNull() ?: continue,
                        NameSet(
                            original = n.optString("original"),
                            en = n.optString("en"),
                            zh = n.optString("zh")
                        )
                    )
                }
            }
        }

        return ClassicalNames(
            songs = section("songs"),
            artists = section("artists"),
            albums = section("albums")
        )
    }
}

/** 同一实体的三层曲名：原文（原语言）、英文、中文。 */
data class NameSet(
    val original: String,
    val en: String,
    val zh: String
)

/** 运行时曲名目录（来自 `classical_names.json`）。 */
data class ClassicalNames(
    val songs: Map<Long, NameSet>,
    val artists: Map<Long, NameSet>,
    val albums: Map<Long, NameSet>
) {
    companion object {
        val EMPTY = ClassicalNames(emptyMap(), emptyMap(), emptyMap())
    }
}

data class ClassicalTrack(
    val id: Long,
    val file: String,
    val title: String,
    val titleOriginal: String,
    val titleEn: String,
    val titleZh: String,
    val composer: String,
    val albumName: String,
    val albumId: Long,
    val artistId: Long,
    val opus: String,
    val durationMs: Long
)
