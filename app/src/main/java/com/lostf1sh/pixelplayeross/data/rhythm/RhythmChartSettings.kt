/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Original work in 2026 for PixelPlayerOSS
 * (docs/rhythm-chart-ladder-plan.md §5, §6.1).
 */
package com.lostf1sh.pixelplayeross.data.rhythm

import kotlinx.serialization.Serializable

/**
 * Per-chart playback tweaks, stored next to the chart as `settings/<chartId>.json`.
 *
 * Deliberately *not* part of [RhythmChart] or of the library index: these change while the
 * user drags a slider, and rewriting a content-addressed chart (or the shared index) on every
 * change would be both slow and destructive. A sidecar with the same base name keeps the
 * lookup one file open.
 */
@Serializable
data class RhythmChartSettings(
    val schemaVersion: Int = 1,
    /**
     * Audio latency compensation in ms, fed to `RhythmAudioClock.offsetMs`. Positive means the
     * clock runs ahead of the audio, so notes reach the judgement line earlier — increase it
     * when the beat is heard later than it is seen.
     */
    val delayMs: Int = 0,
    /** Note fall time in ms, fed to `RhythmPlaySession.approachMs`. */
    val approachMs: Int = 1500,
    val updatedAtMs: Long = 0,
) {
    init {
        require(schemaVersion == 1) { "unsupported settings schemaVersion $schemaVersion" }
    }

    fun clamped(): RhythmChartSettings = copy(
        // Delay is intentionally unbounded: compensation must be able to reach any value on
        // badly out-of-sync devices (plan §14), and an extreme offset only shifts the clock.
        approachMs = approachMs.coerceIn(MIN_APPROACH_MS, MAX_APPROACH_MS),
    )

    companion object {
        const val MIN_APPROACH_MS = 700
        const val MAX_APPROACH_MS = 3000

        val DEFAULT = RhythmChartSettings()
    }
}

/** One latency tap: plain addition, no clamp and no wraparound (plan §14). */
fun delayAfterTap(currentMs: Int, deltaMs: Int): Int = currentMs + deltaMs
