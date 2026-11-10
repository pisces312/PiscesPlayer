package com.pisces312.piscesplayer.data.backup.restore

import com.pisces312.piscesplayer.data.database.SongSummary

/**
 * Metadata of a backed-up playlist song, persisted alongside unresolved references so they can
 * be matched against the local library once a sync has populated it. Serialized with the backup
 * Gson both inside the backup payload and in the pending-resolution preference.
 *
 * The fields are nullable because a payload whose keys do not line up with this class -- a backup
 * written by a build whose obfuscation mapping differed, for instance -- deserializes into an
 * all-blank entry rather than failing outright. [hasIdentity] reports such an entry as unusable,
 * and callers then treat it exactly as if no metadata had been carried.
 */
data class PendingSongRef(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val duration: Long = 0L
) {
    /**
     * True only when both a title and an artist are present. [PlaylistSongMatcher.metadataMatches]
     * compares the pair, so a half-filled entry could never match by metadata anyway -- and treating
     * it as usable would reject a perfectly good direct-ID hit, silently dropping the song.
     */
    val hasIdentity: Boolean
        get() = !title.isNullOrBlank() && !artist.isNullOrBlank()
}

/**
 * Pure (Android-free) matcher that maps backup song IDs to the IDs of songs in the current local
 * library. Shared by the one-shot restore pass ([com.pisces312.piscesplayer.data.backup.module.
 * PlaylistsModuleHandler]) and the deferred post-sync pass ([PlaylistRestoreResolver]) so both use
 * exactly the same matching rules.
 *
 * Strategy per backup ID:
 * 1. Direct ID match, verified against stored metadata when available → keep the ID.
 * 2. Metadata match on normalized title + artist, disambiguated by album then duration.
 * 3. No confident match → null; callers decide whether to keep the reference for a later pass.
 */
class PlaylistSongMatcher(summaries: List<SongSummary>) {

    val isLibraryEmpty: Boolean = summaries.isEmpty()

    private val byId: Map<String, SongSummary> = summaries.associateBy { it.id.toString() }

    private val metadataIndex: Map<String, List<SongSummary>> =
        buildMap<String, MutableList<SongSummary>> {
            summaries.forEach { song ->
                getOrPut(matchKey(song.title, song.artistName)) { mutableListOf() }.add(song)
            }
        }

    /**
     * Resolves a single backup ID. [metadata] comes from the backup (or the pending store); it is
     * null for legacy payloads that never carried it, and an entry that cannot identify a song
     * ([PendingSongRef.hasIdentity]) is handled exactly the same way.
     */
    fun resolve(backupSongId: String, metadata: PendingSongRef?): String? {
        val usableMetadata = metadata?.takeIf { it.hasIdentity }

        val directMatch = byId[backupSongId]
        if (directMatch != null) {
            if (usableMetadata == null) return backupSongId
            if (metadataMatches(usableMetadata, directMatch)) return backupSongId
        }

        if (usableMetadata == null) {
            // No metadata to fall back on: only an exact ID hit is trustworthy.
            return if (directMatch != null) backupSongId else null
        }

        val candidates = metadataIndex[matchKey(usableMetadata.title, usableMetadata.artist)] ?: return null
        if (candidates.size == 1) return candidates[0].id.toString()

        val albumMatches = candidates.filter { candidate ->
            normalize(candidate.albumName) == normalize(usableMetadata.album)
        }
        if (albumMatches.size == 1) return albumMatches[0].id.toString()

        val durationPool = albumMatches.ifEmpty { candidates }
        val durationMatches = durationPool.filter { candidate ->
            kotlin.math.abs(candidate.duration - usableMetadata.duration) <= DURATION_TOLERANCE_MS
        }
        return if (durationMatches.size == 1) durationMatches[0].id.toString() else null
    }

    private fun metadataMatches(metadata: PendingSongRef, song: SongSummary): Boolean =
        normalize(metadata.title) == normalize(song.title) &&
            normalize(metadata.artist) == normalize(song.artistName)

    private fun matchKey(title: String?, artist: String?): String =
        "${normalize(title)}|${normalize(artist)}"

    private fun normalize(text: String?): String = text?.trim()?.lowercase().orEmpty()

    companion object {
        private const val DURATION_TOLERANCE_MS = 2000L
    }
}
