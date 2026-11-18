/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Original work in 2026 for PiscesPlayer
 * (design/piscesplayer docs/white-noise-implementation-plan.md §4).
 */
package com.pisces312.piscesplayer.data.noise

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.SystemClock
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Runtime parameters of one procedural noise track. UI volume 0..1 maps to a
 * -40 dB..0 dB track gain so the bottom of the slider is near-silence instead
 * of a hard cut.
 */
data class NoiseTrackParams(
    val color: NoiseColor = NoiseColor.BROWN,
    val volume: Float = DEFAULT_TRACK_VOLUME,
    val toneHz: Float = NoiseSynthesis.TONE_CUTOFF_DEFAULT_HZ,
    val motionDb: Float = NoiseSynthesis.MOTION_DEPTH_DEFAULT_DB,
    val width: Float = NoiseSynthesis.WIDTH_DEFAULT,
) {
    fun trackGain(): Float = Math.pow(10.0, ((volume - 1f) * TRACK_GAIN_RANGE_DB) / 20.0).toFloat()

    companion object {
        const val DEFAULT_TRACK_VOLUME = 0.4f
        const val TRACK_GAIN_RANGE_DB = 40f
    }
}

/**
 * Owns the single streaming [AudioTrack] and the writer thread for the sleep
 * soundscape: sums all active tracks through [SoundscapeMixer] and drives the
 * start/stop/timer fades.
 *
 * Fades are driven by the writer loop itself (wall-clock paced by the blocking
 * [AudioTrack] write), so a UI volume change is zipper-free (~42 ms), while
 * start fades in over 3 s, a manual stop fades out over 5 s and the sleep timer
 * glides out over 30 s. The mixer's soft limiter guarantees the summed output
 * can never clip no matter how many tracks are stacked.
 *
 * The engine survives independently of any screen: [data.service.SoundscapeService]
 * keeps the process foreground while [phase] is not [Phase.IDLE].
 */
@Singleton
class SoundscapeEngine @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    enum class Phase { IDLE, FADING_IN, RUNNING, FADING_OUT }

    private class ActiveTrack(
        val soundId: String,
        val synth: NoiseSynthesis,
        /** Wall-clock (elapsedRealtime) deadline after which a removing track is dropped. */
        val dropAtElapsedMs: Long = Long.MAX_VALUE,
    )

    private val tracksLock = Any()
    private val activeTracks = mutableListOf<ActiveTrack>()

    private val mixer = SoundscapeMixer()

    @Volatile private var masterVolumeUi: Float = DEFAULT_MASTER_VOLUME

    @Volatile private var phaseField: Phase = Phase.IDLE
    private val _phase = MutableStateFlow(Phase.IDLE)
    val phase: StateFlow<Phase> = _phase.asStateFlow()

    private val _activeSoundIds = MutableStateFlow<List<String>>(emptyList())
    val activeSoundIds: StateFlow<List<String>> = _activeSoundIds.asStateFlow()

    private val _remainingMs = MutableStateFlow<Long?>(null)
    val remainingMs: StateFlow<Long?> = _remainingMs.asStateFlow()

    private var writerThread: Thread? = null

    // Fade state, only touched/annotated on the writer thread (except start/stop entry points guarded below).
    @Volatile private var fadeStartNs: Long = 0L
    @Volatile private var fadeDurationMs: Long = 0L
    @Volatile private var fadeStartVolume: Float = 0f
    @Volatile private var timerEndElapsedMs: Long? = null
    @Volatile private var resumeAfterFocusGain: Boolean = false

    val isRunning: Boolean get() = phaseField != Phase.IDLE

    /** UI master volume 0..[MASTER_VOLUME_CAP]; the cap is the hearing-protection ceiling. */
    fun setMasterVolume(volumeUi: Float) {
        masterVolumeUi = volumeUi.coerceIn(0f, MASTER_VOLUME_CAP)
    }

    fun currentMasterVolume(): Float = masterVolumeUi

    /**
     * Starts playback (idempotent). [durationMs] null means play until stopped.
     * Tracks must be registered first via [setTrack]/[removeTrack]; their gains
     * are already smoothed, and the master fade-in masks the start.
     */
    fun start(durationMs: Long?) {
        if (phaseField != Phase.IDLE) return
        synchronized(tracksLock) { if (activeTracks.isEmpty()) return }
        timerEndElapsedMs = durationMs?.let { SystemClock.elapsedRealtime() + it }
        phaseField = Phase.FADING_IN
        _phase.value = Phase.FADING_IN
        fadeStartNs = System.nanoTime()
        fadeDurationMs = FADE_IN_MS
        mixer.setMasterTarget(0f)
        writerThread = Thread({ runWriterLoop() }, "soundscape-engine").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    /**
     * Stops playback with a fade. [fadeOutMs] overrides the default 5 s manual-stop
     * fade (used for focus-loss suspension and becoming-noisy, which want to be quick).
     */
    fun stop(fadeOutMs: Long = FADE_OUT_STOP_MS) {
        val current = phaseField
        if (current == Phase.IDLE || current == Phase.FADING_OUT) return
        fadeStartVolume = effectiveMasterVolume()
        fadeStartNs = System.nanoTime()
        fadeDurationMs = fadeOutMs
        phaseField = Phase.FADING_OUT
        _phase.value = Phase.FADING_OUT
    }

    /** True if the engine was playing before [suspendForFocusLoss]; call before resume. */
    fun suspendForFocusLoss() {
        resumeAfterFocusGain = isRunning
        stop(FOCUS_LOSS_FADE_MS)
    }

    /** Resumes after a transient focus loss if the engine was suspended by one. */
    fun resumeAfterFocusGain() {
        if (!resumeAfterFocusGain) return
        resumeAfterFocusGain = false
        if (phaseField != Phase.IDLE) return
        val remaining = timerEndElapsedMs?.let { it - SystemClock.elapsedRealtime() }
        if (remaining == null || remaining > FOCUS_LOSS_FADE_MS) start(remaining)
    }

    /**
     * Registers or updates a track; a track id may only exist once (mixer sums, not replaces).
     * An already-active track is updated in place (parameter setters are thread-safe and the
     * filter state carries over), so a live slider drag never restarts the generator.
     */
    fun setTrack(soundId: String, params: NoiseTrackParams) {
        synchronized(tracksLock) {
            val i = activeTracks.indexOfFirst { it.soundId == soundId && it.dropAtElapsedMs == Long.MAX_VALUE }
            if (i >= 0) {
                applyParams(activeTracks[i].synth, params)
            } else {
                val synth = NoiseSynthesis(seed = soundId.hashCode().toLong())
                applyParams(synth, params)
                activeTracks.removeAll { it.soundId == soundId }
                activeTracks.add(ActiveTrack(soundId, synth))
            }
        }
        publishSoundIds()
    }

    /** Keeps only the listed track ids; the rest fade out and are dropped like [removeTrack]. */
    fun retainTracks(soundIds: Collection<String>) {
        synchronized(tracksLock) {
            val keep = soundIds.toHashSet()
            val now = SystemClock.elapsedRealtime()
            activeTracks.indices
                .filter { i ->
                    val t = activeTracks[i]
                    t.soundId !in keep && t.dropAtElapsedMs == Long.MAX_VALUE
                }
                .forEach { i ->
                    val track = activeTracks[i]
                    track.synth.setTargetGain(0f)
                    activeTracks[i] = ActiveTrack(track.soundId, track.synth, now + TRACK_REMOVE_FADE_MS)
                }
        }
        publishSoundIds()
    }

    /**
     * Removes a track: its gain is faded out over [TRACK_REMOVE_FADE_MS] and the
     * synth dropped afterwards, never interrupting the remaining tracks.
     */
    fun removeTrack(soundId: String) {
        synchronized(tracksLock) {
            val i = activeTracks.indexOfFirst { it.soundId == soundId }
            if (i >= 0) {
                val track = activeTracks[i]
                track.synth.setTargetGain(0f)
                activeTracks[i] = track.copy(dropAtElapsedMs = SystemClock.elapsedRealtime() + TRACK_REMOVE_FADE_MS)
            }
        }
        publishSoundIds()
    }

    fun hasTrack(soundId: String): Boolean =
        synchronized(tracksLock) { activeTracks.any { it.soundId == soundId && it.dropAtElapsedMs == Long.MAX_VALUE } }

    fun clearTracks() {
        synchronized(tracksLock) { activeTracks.clear() }
        publishSoundIds()
    }

    private fun ActiveTrack.copy(dropAtElapsedMs: Long) =
        ActiveTrack(soundId, synth, dropAtElapsedMs)

    private fun applyParams(synth: NoiseSynthesis, params: NoiseTrackParams) {
        synth.setColor(params.color)
        synth.setTone(params.toneHz)
        synth.setMotion(params.motionDb)
        synth.setWidth(params.width)
        synth.setTargetGain(params.trackGain())
    }

    private fun publishSoundIds() {
        _activeSoundIds.value = synchronized(tracksLock) {
            activeTracks.filter { it.dropAtElapsedMs == Long.MAX_VALUE }.map { it.soundId }
        }
    }

    private fun effectiveMasterVolume(): Float = when (phaseField) {
        Phase.FADING_IN -> masterVolumeUi * fadeProgress().coerceIn(0f, 1f)
        Phase.FADING_OUT -> fadeStartVolume * (1f - fadeProgress().coerceIn(0f, 1f))
        else -> masterVolumeUi
    }

    private fun fadeProgress(): Float {
        val elapsed = (System.nanoTime() - fadeStartNs) / 1_000_000f
        return if (fadeDurationMs <= 0f) 1f else (elapsed / fadeDurationMs).coerceIn(0f, 1.5f)
    }

    private fun runWriterLoop() {
        val minBytes = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_FLOAT,
        )
        val bufferFrames = maxOf(FRAMES_PER_BUFFER, minBytes / (2 * Float.SIZE_BYTES))
        val track = try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build(),
                )
                .setTransferMode(AudioTrack.MODE_STREAM)
                // Roomy buffer: steady background audio, underrun resistance matters
                // far more than latency here.
                .setBufferSizeInBytes(bufferFrames * 2 * Float.SIZE_BYTES * BUFFER_MULTIPLIER)
                .build()
        } catch (e: Exception) {
            phaseField = Phase.IDLE
            _phase.value = Phase.IDLE
            return
        }

        val out = FloatArray(bufferFrames * 2)
        var lastRemainingSec = -1L
        try {
            track.play()
            while (phaseField != Phase.IDLE) {
                val nowElapsedMs = SystemClock.elapsedRealtime()

                // Reap tracks whose removal fade has finished.
                synchronized(tracksLock) {
                    activeTracks.removeAll { it.dropAtElapsedMs <= nowElapsedMs }
                }

                when (phaseField) {
                    Phase.FADING_IN -> {
                        val p = fadeProgress().coerceAtMost(1f)
                        mixer.setMasterTarget(masterVolumeUi * p)
                        if (p >= 1f) {
                            phaseField = Phase.RUNNING
                            _phase.value = Phase.RUNNING
                        }
                    }
                    Phase.RUNNING -> {
                        mixer.setMasterTarget(masterVolumeUi)
                        val endAt = timerEndElapsedMs
                        if (endAt != null && nowElapsedMs >= endAt - TIMER_FADE_OUT_MS) {
                            fadeStartVolume = masterVolumeUi
                            fadeStartNs = System.nanoTime()
                            fadeDurationMs = TIMER_FADE_OUT_MS
                            phaseField = Phase.FADING_OUT
                            _phase.value = Phase.FADING_OUT
                        }
                    }
                    Phase.FADING_OUT -> {
                        val p = fadeProgress().coerceAtMost(1f)
                        mixer.setMasterTarget(fadeStartVolume * (1f - p))
                        if (p >= 1f) {
                            timerEndElapsedMs = null
                            phaseField = Phase.IDLE
                        }
                    }
                    Phase.IDLE -> {}
                }

                if (phaseField == Phase.IDLE) break

                val snapshot = synchronized(tracksLock) { activeTracks.map { it.synth } }
                mixer.mix(out, bufferFrames, snapshot)

                val written = track.write(out, 0, out.size, AudioTrack.WRITE_BLOCKING)
                if (written < 0) {
                    phaseField = Phase.IDLE
                    break
                }

                val remaining = timerEndElapsedMs?.let { it - nowElapsedMs }
                if (remaining == null) {
                    if (_remainingMs.value != null) _remainingMs.value = null
                } else {
                    val sec = remaining / 1000
                    if (sec != lastRemainingSec) {
                        lastRemainingSec = sec
                        _remainingMs.value = remaining
                    }
                }
            }
        } finally {
            _phase.value = Phase.IDLE
            runCatching { track.pause() }
            runCatching { track.flush() }
            runCatching { track.release() }
            _remainingMs.value = null
        }
    }

    companion object {
        const val SAMPLE_RATE = 48_000
        const val FRAMES_PER_BUFFER = 1_024
        const val BUFFER_MULTIPLIER = 4

        /** Hearing-protection ceiling for the UI master volume slider. */
        const val MASTER_VOLUME_CAP = 0.7f
        const val DEFAULT_MASTER_VOLUME = 0.45f

        const val FADE_IN_MS = 3_000L
        const val FADE_OUT_STOP_MS = 5_000L
        const val TIMER_FADE_OUT_MS = 30_000L
        const val FOCUS_LOSS_FADE_MS = 200L
        const val TRACK_REMOVE_FADE_MS = 300L

        fun dBToLinear(db: Float): Float = Math.pow(10.0, db / 20.0).toFloat()
    }
}
