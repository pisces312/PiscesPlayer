/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Original work in 2026, sound_postcard feature
 * (RhythmChartLab docs/design/sound-postcard-journey-design.md §4.2).
 */
package com.pisces312.piscesplayer.data.postcard

/**
 * Validity rules of a listening attempt (design §4.2), as pure Kotlin so the JVM tests can
 * enumerate the boundaries (89.999 s, 90 s, 59.999%, forward seek) without an Android runtime.
 *
 * Both thresholds are AND-ed: the accumulated `isPlaying` time must reach 90 seconds **and**
 * 60% of the song duration. A song shorter than 90 s can therefore never produce a formal card.
 */
object JourneyValidity {

    /** Seeking forward by more than this invalidates the attempt. */
    const val FORWARD_SEEK_TOLERANCE_MS = 2_000L

    /** Absolute floor of accumulated playback. */
    const val MIN_PLAY_MS = 90_000L

    /** Fraction of the song duration that must also be reached. */
    const val MIN_PLAY_RATIO = 0.6f

    /** The required accumulated time for [durationMs]: `max(90 s, 60%)`. */
    fun requiredPlayMs(durationMs: Long): Long =
        maxOf(MIN_PLAY_MS, (durationMs * MIN_PLAY_RATIO).toLong())

    /** Whether a completion of [durationMs] with [accumulatedMs] heard may settle. */
    fun isValidListen(
        durationMs: Long,
        accumulatedMs: Long,
        invalidatedBySeek: Boolean,
    ): Boolean {
        if (invalidatedBySeek) return false
        if (durationMs <= 0L) return false
        return accumulatedMs >= requiredPlayMs(durationMs)
    }
}
