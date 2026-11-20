package com.pisces312.piscesplayer.data.classical

import org.json.JSONArray

/**
 * Shared constants and index parsing for the built-in classical MIDI library.
 *
 * The resource pack is a single asset (`classical.zip`) produced by the P0 tooling
 * (see the classical-midi-library-plan design doc). It contains the verified `.mid`
 * files plus `classical_index.json`, which is the canonical id map: song / album /
 * artist ids are baked in at build time and must stay stable across resource updates.
 */
object ClassicalLibrary {

    const val SCHEME = "piscesmidi"
    const val URI_PREFIX = "piscesmidi://"

    /** Bump when the shipped resource pack changes; drives the first-launch installer. */
    const val RESOURCE_VERSION = 1

    const val PREFS_NAME = "classical_library"
    const val PREF_VERSION = "classical_library_version"

    /** One-shot flag: pre-exclusion listening-stats rows have been purged. */
    const val PREF_STATS_PURGE_DONE = "classical_stats_purge_v1"

    const val SONG_ID_BASE = 15_000_000_000_000L
    const val ALBUM_ID_BASE = 16_000_000_000_000L
    const val ARTIST_ID_BASE = 17_000_000_000_000L

    const val ASSET_ZIP = "classical.zip"
    const val INDEX_ENTRY = "classical_index.json"
    const val EXTRACT_DIR_NAME = "classical"

    fun contentUri(songId: Long): String = "$URI_PREFIX$songId"

    fun isBuiltInUri(contentUriString: String?): Boolean =
        contentUriString?.startsWith(URI_PREFIX) == true

    fun parseIndex(json: String): List<ClassicalTrack> {
        val root = org.json.JSONObject(json)
        val tracksJson: JSONArray = root.getJSONArray("tracks")
        return buildList {
            for (i in 0 until tracksJson.length()) {
                val t = tracksJson.getJSONObject(i)
                add(
                    ClassicalTrack(
                        id = t.getLong("id"),
                        file = t.getString("file"),
                        title = t.getString("title"),
                        titleZh = t.optString("titleZh"),
                        composer = t.getString("composer"),
                        albumName = t.optString("albumName").ifBlank { "古典熏陶 · " + t.getString("composer") },
                        albumId = t.optLong("albumId").takeIf { it != 0L }
                            ?: (ALBUM_ID_BASE + t.optLong("composerIndex")),
                        artistId = t.optLong("artistId").takeIf { it != 0L }
                            ?: (ARTIST_ID_BASE + t.optLong("composerIndex")),
                        durationMs = t.getLong("durationMs")
                    )
                )
            }
        }
    }
}

data class ClassicalTrack(
    val id: Long,
    val file: String,
    val title: String,
    val titleZh: String,
    val composer: String,
    val albumName: String,
    val albumId: Long,
    val artistId: Long,
    val durationMs: Long
)
