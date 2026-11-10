package com.pisces312.piscesplayer.data.backup.module

import android.content.Context
import android.util.Base64
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import com.pisces312.piscesplayer.data.model.AI_MIX_SOURCE
import com.pisces312.piscesplayer.data.model.Playlist
import com.pisces312.piscesplayer.data.model.SERENDIPITY_SOURCE
import com.pisces312.piscesplayer.data.model.SortOption
import com.pisces312.piscesplayer.data.model.isSmartPlaylistSource
import com.pisces312.piscesplayer.data.backup.model.BackupSection
import com.pisces312.piscesplayer.data.backup.restore.PlaylistSongMatcher
import com.pisces312.piscesplayer.data.backup.restore.PendingSongRef
import com.pisces312.piscesplayer.data.database.MusicDao
import com.pisces312.piscesplayer.data.preferences.PlaylistPreferencesRepository
import com.pisces312.piscesplayer.data.preferences.PreferenceBackupEntry
import com.pisces312.piscesplayer.data.preferences.UserPreferencesRepository
import com.pisces312.piscesplayer.di.BackupGson
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PlaylistsModuleHandler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val playlistPreferencesRepository: PlaylistPreferencesRepository,
    private val userPreferencesRepository: UserPreferencesRepository,
    private val musicDao: MusicDao,
    @BackupGson private val gson: Gson
) : BackupModuleHandler {

    override val section = BackupSection.PLAYLISTS

    override suspend fun export(): String = withContext(Dispatchers.IO) {
        val allPlaylists = playlistPreferencesRepository.getPlaylistsOnce()

        val playlists = allPlaylists.filter { isBackedUpPlaylistSource(it.source) }

        val cloudSongIds = buildCloudSongIdSet()

        val allLocalSummaries = musicDao.getAllLocalSongSummaries()
        val summaryById = allLocalSummaries.associateBy { it.id.toString() }

        val songMetadata = mutableMapOf<String, PendingSongRef>()
        val filteredPlaylists = playlists.map { playlist ->
            val localSongIds = playlist.songIds.filter { id -> id !in cloudSongIds }
            localSongIds.forEach { id ->
                if (id !in songMetadata) {
                    summaryById[id]?.let { summary ->
                        songMetadata[id] = PendingSongRef(
                            title = summary.title,
                            artist = summary.artistName,
                            album = summary.albumName,
                            duration = summary.duration
                        )
                    }
                }
            }
            playlist.copy(songIds = localSongIds)
        }

        val coverImages = mutableMapOf<String, String>()
        filteredPlaylists.forEach { playlist ->
            val uri = playlist.coverImageUri ?: return@forEach
            readFileAsBase64(uri)?.let { coverImages[playlist.id] = it }
        }

        val payload = PlaylistsBackupPayload(
            playlists = filteredPlaylists,
            playlistSongOrderModes = playlistPreferencesRepository.playlistSongOrderModesFlow.first(),
            playlistsSortOption = playlistPreferencesRepository.playlistsSortOptionFlow.first(),
            songMetadata = songMetadata.ifEmpty { null },
            coverImages = coverImages.ifEmpty { null }
        )
        gson.toJson(payload)
    }

    override suspend fun countEntries(): Int = withContext(Dispatchers.IO) {
        val playlists = playlistPreferencesRepository.getPlaylistsOnce()
            .count { isBackedUpPlaylistSource(it.source) }
        val orderModes = playlistPreferencesRepository.playlistSongOrderModesFlow.first()
        val sortOption = playlistPreferencesRepository.playlistsSortOptionFlow.first()
        playlists + orderModes.size + if (sortOption.isNotBlank()) 1 else 0
    }

    override suspend fun snapshot(): String = withContext(Dispatchers.IO) {
        val payload = PlaylistsBackupPayload(
            playlists = playlistPreferencesRepository.getPlaylistsOnce(),
            playlistSongOrderModes = playlistPreferencesRepository.playlistSongOrderModesFlow.first(),
            playlistsSortOption = playlistPreferencesRepository.playlistsSortOptionFlow.first()
        )
        gson.toJson(payload)
    }

    override suspend fun restore(payload: String) = withContext(Dispatchers.IO) {
        val element = JsonParser.parseString(payload)
        if (element.isJsonArray) {
            restoreLegacyPreferenceEntries(payload)
            return@withContext
        }

        val parsed = runCatching {
            gson.fromJson(payload, PlaylistsBackupPayload::class.java)
        }.getOrElse { e ->
            throw IllegalStateException("Playlists payload could not be parsed: ${e.message}", e)
        } ?: throw IllegalStateException("Playlists payload could not be parsed: empty JSON document")

        val backupPlaylists = parsed.playlists.orEmpty()
        val songMetadata = parsed.songMetadata
        val coverImages = parsed.coverImages

        // Resolve against whatever the local library currently holds. It is often empty here —
        // e.g. a backup imported in the first-run wizard before the initial MediaStore sync — and
        // in that case references must be kept verbatim and retried later, never dropped.
        val matcher = PlaylistSongMatcher(musicDao.getAllLocalSongSummaries())
        val pending = LinkedHashMap<String, PendingSongRef>()
        val resolvedPlaylists = resolvePlaylists(backupPlaylists, songMetadata, matcher, pending)

        val finalPlaylists = if (coverImages != null && coverImages.isNotEmpty()) {
            restoreCoverImages(resolvedPlaylists, coverImages)
        } else {
            resolvedPlaylists
        }

        playlistPreferencesRepository.replaceAllPlaylists(finalPlaylists)
        userPreferencesRepository.setPlaylistRestorePending(
            pending.takeIf { it.isNotEmpty() }?.let { gson.toJson(it) }
        )
        playlistPreferencesRepository.setPlaylistSongOrderModes(parsed.playlistSongOrderModes.orEmpty())
        playlistPreferencesRepository.setPlaylistsSortOption(
            parsed.playlistsSortOption ?: SortOption.PlaylistNameAZ.storageKey
        )
        userPreferencesRepository.clearLegacyUserPlaylists()
    }

    override suspend fun rollback(snapshot: String) = restore(snapshot)

    private fun readFileAsBase64(path: String): String? {
        return try {
            val file = File(path)
            if (!file.exists() || file.length() == 0L) return null
            val bytes = file.readBytes()
            Base64.encodeToString(bytes, Base64.NO_WRAP)
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed to read cover image: $path")
            null
        }
    }

    private fun restoreCoverImages(
        playlists: List<Playlist>,
        coverImages: Map<String, String>
    ): List<Playlist> {
        return playlists.map { playlist ->
            val base64 = coverImages[playlist.id] ?: return@map playlist
            try {
                val bytes = Base64.decode(base64, Base64.NO_WRAP)
                val fileName = "playlist_cover_${playlist.id}.jpg"
                val file = File(context.filesDir, fileName)
                file.writeBytes(bytes)
                playlist.copy(coverImageUri = file.absolutePath)
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "Failed to restore cover image for playlist ${playlist.id}")
                playlist.copy(coverImageUri = null)
            }
        }
    }

    /**
     * Maps backup song IDs to current device song IDs. The policy for an unmatched ID depends on
     * whether the library is even ready:
     *
     * - matched (direct ID verified, or metadata match) → use the resolved ID;
     * - library empty (sync hasn't run yet) → keep the backup ID verbatim; park metadata for the
     *   deferred resolver, when available;
     * - library present, metadata available but still unmatched → keep + park for a later sync
     *   (the file may be imported afterwards);
     * - library present, no metadata to retry with → drop, the ID can never be verified.
     */
    private fun resolvePlaylists(
        playlists: List<Playlist>,
        songMetadata: Map<String, PendingSongRef>?,
        matcher: PlaylistSongMatcher,
        pending: MutableMap<String, PendingSongRef>
    ): List<Playlist> {
        val resolutionCache = HashMap<String, String?>()
        var totalSongs = 0
        var resolvedCount = 0
        var deferredCount = 0
        var droppedCount = 0

        fun resolveId(backupSongId: String): String? {
            if (resolutionCache.containsKey(backupSongId)) return resolutionCache[backupSongId]
            totalSongs++
            val meta = songMetadata?.get(backupSongId)
            val matched = matcher.resolve(backupSongId, meta)
            val outcome: String? = when {
                matched != null -> {
                    resolvedCount++
                    matched
                }
                matcher.isLibraryEmpty || meta != null -> {
                    // Library not ready, or retryable once more files are scanned: keep the ref.
                    if (meta != null) pending[backupSongId] = meta
                    deferredCount++
                    backupSongId
                }
                else -> {
                    droppedCount++
                    null
                }
            }
            resolutionCache[backupSongId] = outcome
            return outcome
        }

        return playlists.map { playlist ->
            val resolvedSongIds = playlist.songIds.mapNotNull(::resolveId)
            playlist.copy(songIds = resolvedSongIds)
        }.also {
            if (deferredCount > 0 || droppedCount > 0) {
                Timber.tag(TAG).w(
                    "Playlist restore: $resolvedCount/$totalSongs resolved, " +
                        "$deferredCount deferred for later sync, $droppedCount dropped"
                )
            }
        }
    }

    private suspend fun buildCloudSongIdSet(): Set<String> {
        val cloudIds = mutableSetOf<String>()
        musicDao.getAllNavidromeSongIds().mapTo(cloudIds) { it.toString() }
        musicDao.getAllJellyfinSongIds().mapTo(cloudIds) { it.toString() }
        return cloudIds
    }

    private suspend fun restoreLegacyPreferenceEntries(payload: String) {
        val type = TypeToken.getParameterized(List::class.java, PreferenceBackupEntry::class.java).type
        val entries: List<PreferenceBackupEntry> = gson.fromJson(payload, type)

        val playlists = entries.firstOrNull { it.key == LEGACY_USER_PLAYLISTS_KEY }
            ?.stringValue
            ?.let { raw ->
                runCatching {
                    val playlistType = TypeToken.getParameterized(List::class.java, Playlist::class.java).type
                    gson.fromJson<List<Playlist>>(raw, playlistType)
                }.getOrDefault(emptyList())
            }
            .orEmpty()

        val playlistSongOrderModes = entries.firstOrNull { it.key == LEGACY_PLAYLIST_ORDER_MODES_KEY }
            ?.stringValue
            ?.let { raw ->
                runCatching {
                    val mapType = TypeToken.getParameterized(
                        Map::class.java,
                        String::class.java,
                        String::class.java
                    ).type
                    gson.fromJson<Map<String, String>>(raw, mapType)
                }.getOrDefault(emptyMap())
            }
            .orEmpty()

        val playlistsSortOption = entries.firstOrNull { it.key == LEGACY_PLAYLIST_SORT_OPTION_KEY }
            ?.stringValue
            ?: SortOption.PlaylistNameAZ.storageKey

        playlistPreferencesRepository.replaceAllPlaylists(playlists)
        userPreferencesRepository.setPlaylistRestorePending(null)
        playlistPreferencesRepository.setPlaylistSongOrderModes(playlistSongOrderModes)
        playlistPreferencesRepository.setPlaylistsSortOption(playlistsSortOption)
        userPreferencesRepository.clearLegacyUserPlaylists()
    }

    private data class PlaylistsBackupPayload(
        val playlists: List<Playlist>? = null,
        val playlistSongOrderModes: Map<String, String>? = null,
        val playlistsSortOption: String? = null,
        /** Song metadata for cross-device matching. Key = songId from backup. Null in legacy/snapshot payloads. */
        val songMetadata: Map<String, PendingSongRef>? = null,
        /** Base64-encoded cover images. Key = playlist ID. Null if no custom covers. */
        val coverImages: Map<String, String>? = null
    )

    companion object {
        private const val TAG = "PlaylistsModuleHandler"

        /**
         * Playlist sources that are backed up. Cloud-sourced playlists are excluded; AI mixes
         * ([AI_MIX_SOURCE] / [SERENDIPITY_SOURCE]) are local playlists carrying their ai_* fields,
         * so they travel with the rest.
         */
        private fun isBackedUpPlaylistSource(source: String): Boolean =
            source == "LOCAL" ||
                source == AI_MIX_SOURCE ||
                source == SERENDIPITY_SOURCE ||
                isSmartPlaylistSource(source)

        const val LEGACY_USER_PLAYLISTS_KEY = "user_playlists_json_v1"
        const val LEGACY_PLAYLIST_ORDER_MODES_KEY = "playlist_song_order_modes"
        const val LEGACY_PLAYLIST_SORT_OPTION_KEY = "playlists_sort_option"
        val PLAYLIST_KEYS = setOf(
            LEGACY_USER_PLAYLISTS_KEY,
            LEGACY_PLAYLIST_ORDER_MODES_KEY,
            LEGACY_PLAYLIST_SORT_OPTION_KEY
        )
    }
}
