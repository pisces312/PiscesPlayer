package com.pisces312.piscesplayer.data.classical

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Classical-scoped favorites: favorited composers (作曲家) and tracks (乐曲), owned by the
 * 「古典熏陶」browse screens.
 *
 * Deliberately **not** the global Liked list — per the `SourceType.APP_PROVIDED` policy,
 * user data must never depend on shipped content that resource updates may replace or
 * remove, and classical tracks must not pollute the library "Liked" tab. Ids are stable
 * across pack updates, so favorites normally survive; display layers silently skip ids a
 * newer pack no longer contains.
 *
 * Backed by the classical library `SharedPreferences` (JSON id → addedAt maps) instead of
 * Room: the scale is tiny and no schema migration is needed. Move to Room only if
 * cross-device sync ever becomes a requirement.
 */
@Singleton
class ClassicalFavoritesStore @Inject constructor(
    @ApplicationContext context: Context
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(ClassicalLibrary.PREFS_NAME, Context.MODE_PRIVATE)

    private val _favoriteComposers = MutableStateFlow(readMap(KEY_COMPOSERS))
    val favoriteComposers: StateFlow<Map<String, Long>> = _favoriteComposers.asStateFlow()

    private val _favoriteTracks = MutableStateFlow(readMap(KEY_TRACKS))
    val favoriteTracks: StateFlow<Map<String, Long>> = _favoriteTracks.asStateFlow()

    fun isComposerFavorite(albumId: Long): Boolean = _favoriteComposers.value.containsKey(albumId.toString())

    fun isTrackFavorite(songId: String): Boolean = _favoriteTracks.value.containsKey(songId)

    /** Toggles the composer (virtual album) and returns the new favorite state. */
    fun toggleComposer(albumId: Long): Boolean = toggle(_favoriteComposers, KEY_COMPOSERS, albumId.toString())

    /** Toggles the track and returns the new favorite state. */
    fun toggleTrack(songId: String): Boolean = toggle(_favoriteTracks, KEY_TRACKS, songId)

    private fun toggle(
        state: MutableStateFlow<Map<String, Long>>,
        key: String,
        id: String
    ): Boolean {
        val current = state.value
        val next = if (current.containsKey(id)) {
            current - id
        } else {
            current + (id to System.currentTimeMillis())
        }
        state.value = next
        writeMap(key, next)
        return next.containsKey(id)
    }

    private fun readMap(key: String): Map<String, Long> {
        val raw = prefs.getString(key, null) ?: return emptyMap()
        return runCatching {
            val obj = JSONObject(raw)
            buildMap {
                for (id in obj.keys()) {
                    put(id, obj.optLong(id))
                }
            }
        }.getOrElse {
            Timber.w(it, "Unreadable classical favorites payload under %s", key)
            emptyMap()
        }
    }

    private fun writeMap(key: String, map: Map<String, Long>) {
        val obj = JSONObject()
        map.forEach { (id, addedAt) -> obj.put(id, addedAt) }
        prefs.edit().putString(key, obj.toString()).apply()
    }

    private companion object {
        const val KEY_COMPOSERS = "classical_favorite_composers"
        const val KEY_TRACKS = "classical_favorite_tracks"
    }
}
