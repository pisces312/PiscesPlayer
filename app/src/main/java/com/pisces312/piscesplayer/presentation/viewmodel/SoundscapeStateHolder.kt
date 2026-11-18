/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Original work in 2026 for PiscesPlayer
 * (design/piscesplayer docs/white-noise-implementation-plan.md §4.3 / §5).
 */
package com.pisces312.piscesplayer.presentation.viewmodel

import android.content.Context
import com.pisces312.piscesplayer.R
import com.pisces312.piscesplayer.data.noise.SoundscapeEngine
import com.pisces312.piscesplayer.data.noise.SoundscapeMix
import com.pisces312.piscesplayer.data.noise.SoundscapePreferences
import com.pisces312.piscesplayer.data.noise.SoundscapePreset
import com.pisces312.piscesplayer.data.noise.SoundscapeSound
import com.pisces312.piscesplayer.data.noise.SoundscapeTrackConfig
import com.pisces312.piscesplayer.data.service.SoundscapeService
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * State and actions for the "入梦" sleep soundscape screen.
 *
 * The holder owns the mixer state (tracks / master volume / duration / scene
 * selection) and the policy decisions of the plan:
 * - starting the soundscape pauses music first, explicitly (§4.3);
 * - stopping it never resumes music (§4.3);
 * - every mix mutation is persisted as the "last mix" and mirrored into the
 *   engine live, so changes apply while playing.
 *
 * Ownership protocol mirrors [SearchStateHolder]: initialized by the
 * activity-scoped PlayerViewModel, and a second owner can never steal the scope.
 */
@Singleton
class SoundscapeStateHolder @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferences: SoundscapePreferences,
    val engine: SoundscapeEngine,
) {

    private val _mix = MutableStateFlow(SoundscapeMix())
    val mix: StateFlow<SoundscapeMix> = _mix.asStateFlow()

    /** The preset the current mix was recalled from, or null when it has been edited since. */
    private val _selectedPresetId = MutableStateFlow<Long?>(null)
    val selectedPresetId: StateFlow<Long?> = _selectedPresetId.asStateFlow()

    private val _presets = MutableStateFlow(SoundscapePreset.builtIns())
    val presets: StateFlow<List<SoundscapePreset>> = _presets.asStateFlow()

    private val _firstRunHintVisible = MutableStateFlow(false)
    val firstRunHintVisible: StateFlow<Boolean> = _firstRunHintVisible.asStateFlow()

    val phase: StateFlow<SoundscapeEngine.Phase> get() = engine.phase
    val remainingMs: StateFlow<Long?> get() = engine.remainingMs

    private var scope: CoroutineScope? = null
    private var toastEmitter: (suspend (String) -> Unit)? = null
    private var pauseMusic: () -> Boolean = { false }
    private var restoreJob: kotlinx.coroutines.Job? = null

    /** The ViewModel that owns this holder's scope; see [SearchStateHolder]. */
    private var owner: Any? = null

    fun initialize(
        owner: Any,
        scope: CoroutineScope,
        toastEmitter: suspend (String) -> Unit,
        /** Pauses music if playing; returns true when music actually was playing. */
        pauseMusic: () -> Boolean,
    ) {
        val currentOwner = this.owner
        if (currentOwner != null && currentOwner !== owner) {
            Timber.w(
                "SoundscapeStateHolder.initialize ignored: already owned by %s",
                currentOwner::class.java.simpleName
            )
            return
        }
        this.owner = owner
        this.scope = scope
        this.toastEmitter = toastEmitter
        this.pauseMusic = pauseMusic

        // Restore the last mix (selection only — never auto-playing, §5.1) and the
        // first-run hint flag. Idempotent across re-initializations.
        restoreJob?.cancel()
        restoreJob = scope.launch {
            preferences.lastMix.collect { restored ->
                if (!engine.isRunning) {
                    _mix.value = restored
                    engine.setMasterVolume(restored.masterVolume)
                }
            }
        }
        preferences.presets
            .onEach { _presets.value = it }
            .launchIn(scope)
        preferences.firstRunHintShown
            .onEach { shown -> _firstRunHintVisible.value = !shown }
            .launchIn(scope)
    }

    // ---------------- mix editing ----------------

    /** Adds a sound (duplicate ids are ignored, §3.3) with its default track volume. */
    fun addSound(sound: SoundscapeSound) {
        if (_mix.value.tracks.any { it.soundId == sound.id }) return
        updateMix(_mix.value.copy(tracks = _mix.value.tracks + SoundscapeTrackConfig(soundId = sound.id)))
    }

    fun removeTrack(soundId: String) {
        updateMix(_mix.value.copy(tracks = _mix.value.tracks.filterNot { it.soundId == soundId }))
        engine.removeTrack(soundId)
    }

    /** Updates one track's parameters; changes are audible immediately while playing. */
    fun updateTrack(soundId: String, transform: (SoundscapeTrackConfig) -> SoundscapeTrackConfig) {
        val tracks = _mix.value.tracks.map { if (it.soundId == soundId) transform(it) else it }
        updateMix(_mix.value.copy(tracks = tracks))
        _mix.value.tracks.firstOrNull { it.soundId == soundId }?.let { config ->
            engine.setTrack(soundId, config.toParams())
        }
    }

    fun setMasterVolume(volume: Float) {
        engine.setMasterVolume(volume)
        updateMix(_mix.value.copy(masterVolume = engine.currentMasterVolume()))
    }

    fun setDurationMinutes(minutes: Int) {
        updateMix(_mix.value.copy(durationMinutes = minutes))
    }

    private fun updateMix(mix: SoundscapeMix) {
        _mix.value = mix
        _selectedPresetId.value = null
        val scope = this.scope ?: return
        scope.launch {
            runCatching { preferences.setLastMix(mix) }
                .onFailure { Timber.w(it, "Failed to persist soundscape last mix") }
        }
    }

    // ---------------- presets ----------------

    fun recallPreset(preset: SoundscapePreset) {
        _selectedPresetId.value = preset.id
        _mix.value = preset.toMix()
        engine.setMasterVolume(preset.masterVolume)
        syncEngineTracks()
        val scope = this.scope ?: return
        scope.launch {
            runCatching { preferences.setLastMix(preset.toMix()) }
                .onFailure { Timber.w(it, "Failed to persist recalled soundscape preset") }
        }
    }

    /**
     * Saves the current mix as a named scene. Returns false when the user-scene
     * limit is reached (the caller shows the limit toast).
     */
    fun saveCurrentAsPreset(name: String, onResult: (Boolean) -> Unit = {}) {
        val scope = this.scope ?: return
        val current = _mix.value
        if (current.tracks.isEmpty()) {
            onResult(false)
            return
        }
        scope.launch {
            val preset = SoundscapePreset(
                id = System.currentTimeMillis(),
                name = name,
                tracks = current.tracks,
                masterVolume = current.masterVolume,
                durationMinutes = current.durationMinutes,
            )
            val accepted = runCatching { preferences.savePreset(preset) }.getOrElse {
                Timber.w(it, "Failed to save soundscape preset")
                false
            }
            if (accepted) _selectedPresetId.value = preset.id
            onResult(accepted)
        }
    }

    fun deletePreset(preset: SoundscapePreset) {
        if (preset.isBuiltIn) return
        val scope = this.scope ?: return
        scope.launch {
            runCatching { preferences.deletePreset(preset.id) }
                .onFailure { Timber.w(it, "Failed to delete soundscape preset") }
            if (_selectedPresetId.value == preset.id) _selectedPresetId.value = null
        }
    }

    fun dismissFirstRunHint() {
        _firstRunHintVisible.value = false
        val scope = this.scope ?: return
        scope.launch {
            runCatching { preferences.markFirstRunHintShown() }
        }
    }

    // ---------------- playback ----------------

    /**
     * The main button: starts the mix (pausing music first) or fades it out.
     * Returns the resulting phase so the caller can react (e.g. disable the
     * button during fades).
     */
    fun togglePlayback(): SoundscapeEngine.Phase {
        val current = engine.phase.value
        when {
            current == SoundscapeEngine.Phase.IDLE -> startMix()
            else -> stopMix()
        }
        return engine.phase.value
    }

    private fun startMix() {
        val mix = _mix.value
        if (mix.tracks.isEmpty()) return

        val musicWasPlaying = pauseMusic()
        if (musicWasPlaying) {
            scope?.launch {
                toastEmitter?.invoke(context.getString(R.string.soundscape_music_stopped_tip))
            }
        }

        engine.setMasterVolume(mix.masterVolume)
        syncEngineTracks()

        val durationMs = mix.durationMinutes.toDurationMs()
        engine.start(durationMs)
        SoundscapeService.start(context, durationMs)

        scope?.launch {
            val tipShown = preferences.hearingTipShown.first()
            if (!tipShown) {
                toastEmitter?.invoke(context.getString(R.string.soundscape_hearing_tip))
                preferences.markHearingTipShown()
            }
        }
    }

    private fun stopMix() {
        engine.stop()
        SoundscapeService.stop(context)
    }

    /** Mirrors the current mix tracks into the engine (replacing removed ones). */
    private fun syncEngineTracks() {
        val configs = _mix.value.tracks
        engine.retainTracks(configs.map { it.soundId })
        for (config in configs) {
            engine.setTrack(config.soundId, config.toParams())
        }
    }

    fun onCleared(owner: Any) {
        val currentOwner = this.owner
        if (currentOwner != null && currentOwner !== owner) {
            Timber.w(
                "SoundscapeStateHolder.onCleared ignored: called by %s but owned by %s",
                owner::class.java.simpleName,
                currentOwner::class.java.simpleName
            )
            return
        }
        restoreJob?.cancel()
        restoreJob = null
        scope = null
        toastEmitter = null
        pauseMusic = { false }
        this.owner = null
    }

    private companion object {
        fun Int.toDurationMs(): Long? =
            if (this == SoundscapeMix.DURATION_FOREVER) null else this * 60_000L
    }
}
