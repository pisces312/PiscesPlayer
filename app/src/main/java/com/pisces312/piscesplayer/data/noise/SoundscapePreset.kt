/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Original work in 2026 for PiscesPlayer
 * (design/piscesplayer docs/white-noise-implementation-plan.md §5).
 */
package com.pisces312.piscesplayer.data.noise

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * One track inside a mix: the sound id (a [SoundscapeSound] id in Phase A, a
 * natural-sound id in Phase B) plus the per-track volume and the noise
 * parameters. Parameters that do not apply to a sound are ignored by it.
 */
@Serializable
data class SoundscapeTrackConfig(
    val soundId: String,
    val volume: Float = DEFAULT_TRACK_VOLUME,
    val toneHz: Float = NoiseSynthesis.TONE_CUTOFF_DEFAULT_HZ,
    val motionDb: Float = NoiseSynthesis.MOTION_DEPTH_DEFAULT_DB,
    val width: Float = NoiseSynthesis.WIDTH_DEFAULT,
) {
    fun toParams(): NoiseTrackParams = NoiseTrackParams(
        color = SoundscapeSound.fromId(soundId)?.noiseColor ?: NoiseColor.BROWN,
        volume = volume,
        toneHz = toneHz,
        motionDb = motionDb,
        width = width,
    )

    companion object {
        const val DEFAULT_TRACK_VOLUME = 0.4f
    }
}

/**
 * The current mixer state (unnamed, implicit). Persisted as the "last mix" so
 * the screen can restore the selection after a process restart — restored but
 * never auto-playing.
 */
@Serializable
data class SoundscapeMix(
    val tracks: List<SoundscapeTrackConfig> = emptyList(),
    val masterVolume: Float = SoundscapeEngine.DEFAULT_MASTER_VOLUME,
    /** Sleep timer duration; [DURATION_FOREVER] (-1) means play until stopped. */
    val durationMinutes: Int = DURATION_DEFAULT_MINUTES,
) {
    companion object {
        /** Fixed duration chips; arbitrary minute values are deliberately not offered. */
        val DURATION_OPTIONS_MINUTES = listOf(15, 30, 45, 60, 90, DURATION_FOREVER)
        const val DURATION_FOREVER = -1
        const val DURATION_DEFAULT_MINUTES = 15
    }
}

/**
 * A named, savable scene. Built-ins have a null [name] and are resolved
 * through [builtInId] (localized at display time); user presets carry their
 * name verbatim. [id] < 0 marks a built-in.
 */
@Serializable
data class SoundscapePreset(
    val id: Long,
    val name: String? = null,
    val builtInId: String? = null,
    val tracks: List<SoundscapeTrackConfig>,
    val masterVolume: Float = SoundscapeEngine.DEFAULT_MASTER_VOLUME,
    val durationMinutes: Int = SoundscapeMix.DURATION_DEFAULT_MINUTES,
) {
    val isBuiltIn: Boolean get() = id < 0

    fun toMix(): SoundscapeMix = SoundscapeMix(
        tracks = tracks,
        masterVolume = masterVolume,
        durationMinutes = durationMinutes,
    )

    companion object {
        const val BUILT_IN_BROWN_ONLY = "brown_only"
        const val BUILT_IN_WHITE_MASK = "white_mask"
        const val USER_PRESET_LIMIT = 20

        /** 入梦's two non-deletable system presets. */
        fun builtIns(): List<SoundscapePreset> = listOf(
            SoundscapePreset(
                id = -1L,
                builtInId = BUILT_IN_BROWN_ONLY,
                tracks = listOf(
                    SoundscapeTrackConfig(
                        soundId = SoundscapeSound.BROWN.id,
                        toneHz = 8_000f,
                        motionDb = 1.0f,
                        volume = 0.5f,
                    )
                ),
                masterVolume = 0.5f,
                durationMinutes = 60,
            ),
            SoundscapePreset(
                id = -2L,
                builtInId = BUILT_IN_WHITE_MASK,
                tracks = listOf(
                    SoundscapeTrackConfig(
                        soundId = SoundscapeSound.WHITE.id,
                        toneHz = 10_000f,
                        motionDb = 0f,
                        volume = 0.4f,
                    )
                ),
                masterVolume = 0.45f,
                durationMinutes = SoundscapeMix.DURATION_FOREVER,
            ),
        )
    }
}

/**
 * DataStore persistence for the sleep soundscape (§5.2): the implicit last
 * mix, the user's saved scenes, and the two one-shot hint flags. Nothing
 * lives in Room — a scene is a UI-level object, not library data.
 */
@Singleton
class SoundscapePreferences @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    private val json: Json,
) {
    val lastMix: Flow<SoundscapeMix> = dataStore.data.map { prefs ->
        decode(prefs[LAST_MIX_KEY]) ?: SoundscapeMix(tracks = brownOnlyTrack())
    }

    val presets: Flow<List<SoundscapePreset>> = dataStore.data.map { prefs ->
        decode<List<SoundscapePreset>>(prefs[PRESETS_KEY])?.plus(SoundscapePreset.builtIns())
            ?: SoundscapePreset.builtIns()
    }

    val hearingTipShown: Flow<Boolean> = dataStore.data.map { it[HEARING_TIP_KEY] ?: false }

    val firstRunHintShown: Flow<Boolean> = dataStore.data.map { it[FIRST_RUN_HINT_KEY] ?: false }

    suspend fun setLastMix(mix: SoundscapeMix) {
        dataStore.edit { it[LAST_MIX_KEY] = json.encodeToString(mix) }
    }

    /**
     * Adds or replaces a user preset. Returns false when the limit of
     * [SoundscapePreset.USER_PRESET_LIMIT] user scenes would be exceeded.
     */
    suspend fun savePreset(preset: SoundscapePreset): Boolean {
        var accepted = false
        dataStore.edit { prefs ->
            val current = userPresets(prefs)
            val withoutOld = current.filterNot { it.id == preset.id }
            if (withoutOld.size >= SoundscapePreset.USER_PRESET_LIMIT) {
                return@edit
            }
            accepted = true
            prefs[PRESETS_KEY] = json.encodeToString(withoutOld + preset)
        }
        return accepted
    }

    suspend fun deletePreset(id: Long) {
        dataStore.edit { prefs ->
            val current = userPresets(prefs)
            prefs[PRESETS_KEY] = json.encodeToString(current.filterNot { it.id == id })
        }
    }

    suspend fun markHearingTipShown() {
        dataStore.edit { it[HEARING_TIP_KEY] = true }
    }

    suspend fun markFirstRunHintShown() {
        dataStore.edit { it[FIRST_RUN_HINT_KEY] = true }
    }

    private fun userPresets(prefs: Preferences): List<SoundscapePreset> =
        decode(prefs[PRESETS_KEY]) ?: emptyList()

    /** First-run default for the implicit mix: the built-in brown preset's tracks. */
    private fun brownOnlyTrack(): List<SoundscapeTrackConfig> =
        SoundscapePreset.builtIns().first { it.builtInId == SoundscapePreset.BUILT_IN_BROWN_ONLY }.tracks

    private inline fun <reified T> decode(encoded: String?): T? =
        encoded?.let { runCatching { json.decodeFromString<T>(it) }.getOrNull() }

    private companion object {
        val LAST_MIX_KEY = stringPreferencesKey("sleep_soundscape_last_mix")
        val PRESETS_KEY = stringPreferencesKey("sleep_soundscape_presets")
        val HEARING_TIP_KEY = booleanPreferencesKey("sleep_soundscape_hearing_tip_shown")
        val FIRST_RUN_HINT_KEY = booleanPreferencesKey("sleep_soundscape_first_run_hint_shown")
    }
}
