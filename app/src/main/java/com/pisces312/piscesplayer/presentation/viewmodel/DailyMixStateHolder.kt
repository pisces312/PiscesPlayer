package com.pisces312.piscesplayer.presentation.viewmodel

import com.pisces312.piscesplayer.data.DailyMixManager
import com.pisces312.piscesplayer.data.model.MixKind
import com.pisces312.piscesplayer.data.model.MixPick
import com.pisces312.piscesplayer.data.model.Song
import com.pisces312.piscesplayer.data.preferences.UserPreferencesRepository
import com.pisces312.piscesplayer.data.repository.MusicRepository
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import timber.log.Timber

/**
 * Manages the merged daily mix ("每日推荐") state: one list that blends the familiar
 * and discovery buckets. Extracted from PlayerViewModel to improve modularity.
 *
 * Responsibilities:
 * - Generate and update the daily mix
 * - Persist and restore mix state (song ids + bucket tokens)
 * - Check if mix needs updating based on day change
 */
@Singleton
class DailyMixStateHolder @Inject constructor(
    private val dailyMixManager: DailyMixManager,
    private val userPreferencesRepository: UserPreferencesRepository,
    private val musicRepository: MusicRepository
) {
    private var scope: CoroutineScope? = null
    private var updateJob: Job? = null

    /** The full merged mix with bucket tags, for the grouped full-list screen. */
    private val _dailyMixPicks = MutableStateFlow<ImmutableList<MixPick>>(persistentListOf())
    val dailyMixPicks: StateFlow<ImmutableList<MixPick>> = _dailyMixPicks.asStateFlow()

    /** The same mix as plain songs, for playback contexts and the home collage. */
    private val _dailyMixSongs = MutableStateFlow<ImmutableList<Song>>(persistentListOf())
    val dailyMixSongs: StateFlow<ImmutableList<Song>> = _dailyMixSongs.asStateFlow()

    private fun setPicks(picks: List<MixPick>) {
        _dailyMixPicks.value = picks.toImmutableList()
        _dailyMixSongs.value = picks.map { it.song }.toImmutableList()
    }

    /**
     * The ViewModel that owns this holder's scope. This is a `@Singleton`, so a second
     * ViewModel instance must never be able to re-bind (or tear down) the scope of the
     * first one: only the owner may [onCleared] it.
     */
    private var owner: Any? = null

    /**
     * Initialize with coroutine scope from ViewModel.
     */
    fun initialize(owner: Any, coroutineScope: CoroutineScope) {
        val currentOwner = this.owner
        if (currentOwner != null && currentOwner !== owner) {
            Timber.w(
                "DailyMixStateHolder.initialize ignored: already owned by %s",
                currentOwner::class.java.simpleName
            )
            return
        }
        this.owner = owner
        scope = coroutineScope
    }

    /**
     * Remove a song from the daily mix.
     */
    fun removeFromDailyMix(songId: String) {
        setPicks(_dailyMixPicks.value.filterNot { it.song.id == songId })
    }

    /**
     * Update the daily mix with new songs.
     * Uses getAllSongsOnce() to load songs on-demand instead of keeping a permanent subscription.
     */
    fun updateDailyMix(favoriteSongIdsFlow: kotlinx.coroutines.flow.Flow<Set<String>>) {
        updateJob?.cancel()
        updateJob = scope?.launch(Dispatchers.IO) {
            val allSongs = musicRepository.getAllSongsOnce()
            if (allSongs.isNotEmpty()) {
                val favoriteIds = favoriteSongIdsFlow.first()

                val mix = dailyMixManager.generateDailyMix(allSongs, favoriteIds)
                setPicks(mix)
                userPreferencesRepository.saveDailyMixSongIds(
                    songIds = mix.map { it.song.id },
                    kinds = mix.map { it.kind.storageToken }
                )
            } else {
                setPicks(emptyList())
            }
        }
    }

    /**
     * Load persisted daily mix from storage using direct DB queries by IDs
     * instead of combining with the full allSongs flow.
     */
    fun loadPersistedDailyMix() {
        scope?.launch {
            val dailyMixIds = userPreferencesRepository.dailyMixSongIdsFlow.first()
            if (dailyMixIds.isNotEmpty() && _dailyMixPicks.value.isEmpty()) {
                val kinds = userPreferencesRepository.dailyMixSongKindsFlow.first()
                val songs = withContext(Dispatchers.IO) {
                    musicRepository.getSongsByIds(dailyMixIds).first()
                }
                if (songs.isNotEmpty()) {
                    val songMap = songs.associateBy { it.id }
                    // .takeIf: ids persisted before built-in classical was excluded from
                    // the mix pools must not sneak back in through the restore path.
                    val picks = dailyMixIds.mapIndexedNotNull { index, id ->
                        val song = songMap[id]?.takeIf { !it.isBuiltIn } ?: return@mapIndexedNotNull null
                        MixPick(song, MixKind.fromStorageToken(kinds.getOrNull(index)))
                    }
                    setPicks(picks)
                }
            }
        }
    }

    /**
     * Force update the daily mix regardless of day.
     */
    fun forceUpdate(favoriteSongIdsFlow: kotlinx.coroutines.flow.Flow<Set<String>>) {
        scope?.launch {
            updateDailyMix(favoriteSongIdsFlow)
            userPreferencesRepository.saveLastDailyMixUpdateTimestamp(System.currentTimeMillis())
        }
    }

    /**
     * Check if daily mix needs updating (new day) and update if so.
     *
     * Compares calendar dates, not the day-of-year counter: that counter wraps
     * back to 1 on Jan 1st, so a same-day-of-year comparison would skip the
     * refresh across the year boundary.
     */
    fun checkAndUpdateIfNeeded(favoriteSongIdsFlow: kotlinx.coroutines.flow.Flow<Set<String>>) {
        scope?.launch {
            val lastUpdate = userPreferencesRepository.lastDailyMixUpdateFlow.first()
            val today = java.time.LocalDate.now()
            val lastUpdateDay = java.time.Instant.ofEpochMilli(lastUpdate)
                .atZone(java.time.ZoneId.systemDefault())
                .toLocalDate()

            if (today != lastUpdateDay) {
                updateDailyMix(favoriteSongIdsFlow)
                userPreferencesRepository.saveLastDailyMixUpdateTimestamp(System.currentTimeMillis())
            }
        }
    }

    fun onCleared(owner: Any) {
        val currentOwner = this.owner
        if (currentOwner != null && currentOwner !== owner) {
            Timber.w(
                "DailyMixStateHolder.onCleared ignored: called by %s but owned by %s",
                owner::class.java.simpleName,
                currentOwner::class.java.simpleName
            )
            return
        }
        updateJob?.cancel()
        scope = null
        this.owner = null
    }
}
