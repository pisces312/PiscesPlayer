package com.pisces312.piscesplayer.data.backup.restore

import android.content.Context
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.pisces312.piscesplayer.data.database.MusicDao
import com.pisces312.piscesplayer.data.model.isSmartPlaylist
import com.pisces312.piscesplayer.data.preferences.PlaylistPreferencesRepository
import com.pisces312.piscesplayer.data.preferences.UserPreferencesRepository
import com.pisces312.piscesplayer.data.worker.SyncWorker
import com.pisces312.piscesplayer.di.BackupGson
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Re-resolves playlist song references that a backup restore could not match against the local
 * library (most importantly backups imported in the first-run wizard, where the initial MediaStore
 * sync has not run yet). Unmatched references are kept in the playlists and parked, together with
 * their metadata, in a preference; this component retries them after every foreground sync and once
 * at app start. References that remain unresolved after a full library pass are pruned then — that
 * is the only point at which a song is allowed to disappear from a restored playlist.
 */
@Singleton
class PlaylistRestoreResolver @Inject constructor(
    @ApplicationContext private val context: Context,
    private val musicDao: MusicDao,
    private val playlistPreferencesRepository: PlaylistPreferencesRepository,
    private val userPreferencesRepository: UserPreferencesRepository,
    @BackupGson private val gson: Gson
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val passMutex = Mutex()
    private val handledWorkIds = mutableSetOf<UUID>()

    @Volatile
    private var started = false

    /** Idempotent; called once from PiscesPlayerApplication.onCreate. */
    fun start() {
        if (started) return
        started = true
        scope.launch { runResolutionPass(pruneUnresolved = false, trigger = "startup") }
        observeSyncCompletions()
    }

    private fun observeSyncCompletions() {
        scope.launch {
            WorkManager.getInstance(context)
                .getWorkInfosForUniqueWorkFlow(SyncWorker.WORK_NAME)
                .map { infos -> infos.filter { it.state == WorkInfo.State.SUCCEEDED } }
                .collect { succeeded ->
                    succeeded.forEach { workInfo ->
                        if (handledWorkIds.add(workInfo.id)) {
                            val fullPass = workInfo.tags.any {
                                it == SyncWorker.TAG_SYNC_MODE_FULL || it == SyncWorker.TAG_SYNC_MODE_REBUILD
                            }
                            runResolutionPass(pruneUnresolved = fullPass, trigger = "sync_succeeded")
                        }
                    }
                }
        }
    }

    /**
     * One resolution attempt. [pruneUnresolved] must only be true after a full (or rebuild) sync,
     * because an incremental pass does not guarantee every on-device file was scanned.
     */
    internal suspend fun runResolutionPass(pruneUnresolved: Boolean, trigger: String) =
        passMutex.withLock {
            val raw = userPreferencesRepository.getPlaylistRestorePendingOnce()
            if (raw.isNullOrBlank()) return@withLock

            val mapType = TypeToken.getParameterized(
                Map::class.java,
                String::class.java,
                PendingSongRef::class.java
            ).type
            val pending: LinkedHashMap<String, PendingSongRef> = runCatching {
                gson.fromJson<Map<String, PendingSongRef>>(raw, mapType)
                    .orEmpty()
                    .toMap(LinkedHashMap())
            }.getOrElse {
                Timber.tag(TAG).w(it, "Unparseable pending playlist refs, discarding")
                userPreferencesRepository.setPlaylistRestorePending(null)
                return@withLock
            }
            if (pending.isEmpty()) {
                userPreferencesRepository.setPlaylistRestorePending(null)
                return@withLock
            }

            val playlists = playlistPreferencesRepository.getPlaylistsOnce()
            val referencedIds = playlists
                .filterNot { it.isSmartPlaylist }
                .flatMapTo(HashSet()) { it.songIds }
            val activeIds = pending.keys.filter { it in referencedIds }
            if (activeIds.isEmpty()) {
                // No playlist references these ids anymore (playlists edited/deleted since restore).
                userPreferencesRepository.setPlaylistRestorePending(null)
                return@withLock
            }

            val matcher = PlaylistSongMatcher(musicDao.getAllLocalSongSummaries())
            if (matcher.isLibraryEmpty) {
                // Still waiting for the first sync; nothing to match against.
                return@withLock
            }

            val resolved = LinkedHashMap<String, String>()
            val unresolved = LinkedHashSet<String>()
            activeIds.forEach { backupId ->
                val target = matcher.resolve(backupId, pending[backupId])
                if (target != null) resolved[backupId] = target else unresolved += backupId
            }

            val pruneIds = if (pruneUnresolved) unresolved else emptySet()
            playlists.forEach { playlist ->
                if (playlist.isSmartPlaylist) return@forEach
                val newSongIds = playlist.songIds.mapNotNull { id ->
                    when {
                        resolved.containsKey(id) -> resolved[id]
                        id in pruneIds -> null
                        else -> id
                    }
                }
                if (newSongIds != playlist.songIds) {
                    playlistPreferencesRepository.reorderSongsInPlaylist(playlist.id, newSongIds)
                }
            }

            resolved.keys.forEach(pending::remove)
            pruneIds.forEach(pending::remove)

            // Drop parked metadata whose reference vanished through unrelated playlist edits.
            val remainingReferenced = playlistPreferencesRepository.getPlaylistsOnce()
                .filterNot { it.isSmartPlaylist }
                .flatMapTo(HashSet()) { it.songIds }
            pending.keys.filterNotTo(ArrayList()) { it in remainingReferenced }
                .forEach(pending::remove)

            userPreferencesRepository.setPlaylistRestorePending(
                pending.takeIf { it.isNotEmpty() }?.let { gson.toJson(it) }
            )
            Timber.tag(TAG).i(
                "Playlist resolution ($trigger): ${resolved.size} resolved, " +
                    "${pending.size} still pending, pruned=${pruneIds.size}"
            )
        }

    companion object {
        private const val TAG = "PlaylistRestoreResolver"
    }
}
