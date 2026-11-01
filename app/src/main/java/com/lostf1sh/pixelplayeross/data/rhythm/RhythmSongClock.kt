package com.lostf1sh.pixelplayeross.data.rhythm

/** Independent audio-output clock shared by rendering, expiry and input judgement. */
interface RhythmSongClock {
    val durationMs: Double
    var offsetMs: Double
    val finished: Boolean
    val isPlaying: Boolean

    fun nowMs(): Double
    fun start()
    fun pause()
    fun resume()
    fun restart()
    fun stop()
    fun release()
}

/** Manually advanced clock for JVM tests and headless sessions. */
class RhythmManualSongClock(
    override val durationMs: Double,
    override var offsetMs: Double = 0.0,
) : RhythmSongClock {
    private var songMs = 0.0
    private var started = false

    override var isPlaying: Boolean = false
        private set

    override val finished: Boolean get() = started && songMs >= durationMs

    override fun nowMs(): Double = songMs + offsetMs

    fun setTime(songTimeMs: Double) {
        require(songTimeMs.isFinite()) { "歌曲时间必须有限" }
        songMs = songTimeMs.coerceIn(0.0, durationMs)
    }

    override fun start() {
        songMs = 0.0
        started = true
        isPlaying = true
    }

    override fun pause() {
        isPlaying = false
    }

    override fun resume() {
        if (started) isPlaying = true
    }

    override fun restart() = start()

    override fun stop() {
        songMs = 0.0
        started = false
        isPlaying = false
    }

    override fun release() = stop()
}
