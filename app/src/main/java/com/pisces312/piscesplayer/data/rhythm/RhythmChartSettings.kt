/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Original work in 2026 for PixelPlayerOSS
 * (docs/rhythm-chart-ladder-plan.md §5, §6.1).
 */
package com.pisces312.piscesplayer.data.rhythm

import kotlinx.serialization.Serializable

/**
 * Per-chart playback tweaks, stored next to the chart as `settings/<chartId>.json`.
 *
 * Deliberately *not* part of [RhythmChart] or of the library index: these change while the
 * user drags a slider, and rewriting a content-addressed chart (or the shared index) on every
 * change would be both slow and destructive. A sidecar with the same base name keeps the
 * lookup one file open.
 *
 * The audio-latency offset is deliberately NOT here: it is a property of the output route
 * (wired / Bluetooth / speaker), stored per route key by [RhythmLatencyStore] and calibrated
 * by tapping along with a generated metronome (see RhythmLatencyCalibration.kt).
 */
@Serializable
data class RhythmChartSettings(
    val schemaVersion: Int = 1,
    /**
     * Per-chart latency override in ms. Null = follow the route-keyed calibration
     * ([RhythmLatencyStore]); a value here takes priority over the route value for this
     * chart only. Set from the chart settings sheet (seeded from the route value) or by
     * tweaking on the pause screen mid-run.
     */
    val delayOverrideMs: Int? = null,
    /**
     * Hide every note charted in the first ms of the song (the per-chart skip-prefix setting).
     * Purely a play-time filter: the chart keeps its notes, and both the played note count and
     * the score denominator use only the visible notes. Default and step: [DEFAULT_LEAD_IN_MS].
     *
     * Default 0 on purpose: on speaker / wired routes the clock lands on the acoustic zero by
     * itself (see RhythmFrameEstimator), so opening notes — even a 283 ms one — are hittable,
     * and hiding them by default only confuses. Bluetooth routes can raise it per chart.
     */
    val leadInMs: Int = DEFAULT_LEAD_IN_MS,
    /** Note fall time in ms, fed to `RhythmPlaySession.approachMs`. */
    val approachMs: Int = 1500,
    val updatedAtMs: Long = 0,
) {
    init {
        require(schemaVersion == 1) { "unsupported settings schemaVersion $schemaVersion" }
    }

    fun clamped(): RhythmChartSettings = copy(
        // Snap to the 500 ms step so a hand-typed value cannot land between steps.
        leadInMs = ((leadInMs.coerceAtLeast(0) + LEAD_IN_STEP_MS / 2) / LEAD_IN_STEP_MS) *
            LEAD_IN_STEP_MS,
        approachMs = approachMs.coerceIn(MIN_APPROACH_MS, MAX_APPROACH_MS),
    )

    companion object {
        const val MIN_APPROACH_MS = 700
        const val MAX_APPROACH_MS = 3000

        /** Lead-in is adjusted in 500 ms steps (UI buttons and storage rounding). */
        const val LEAD_IN_STEP_MS = 500

        /** Default skip-prefix: none — see the property doc for why 500 was rolled back to 0. */
        const val DEFAULT_LEAD_IN_MS = 0

        val DEFAULT = RhythmChartSettings()
    }
}

/** One latency tap: plain addition, no clamp and no wraparound (plan §14). */
fun delayAfterTap(currentMs: Int, deltaMs: Int): Int = currentMs + deltaMs
