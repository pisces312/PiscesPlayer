/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Original work, GPL-3.0-or-later
 */
package com.pisces312.piscesplayer.data.rhythm

import java.util.Locale

/**
 * Ring buffer of one run's clock startup: which device samples were offered, which anchored
 * or were rejected (and why), when the start hold was released, and what the clock reported
 * per frame. Written by [RhythmFrameEstimator] and [RhythmAudioClock]; dumped into the
 * diagnostics export from the pause/result screen so a real-device run can be checked against
 * the intended behaviour (first TIMESTAMP anchor before any HEAD advance, no grace expiry on
 * healthy devices, no songMs increase-then-decrease).
 *
 * Pure Kotlin: callers pass BOOTTIME nanos, so it is JVM-testable. Each line is
 * `"msSinceStart  event"`. The buffer is bounded; the oldest lines fall away once a full
 * startup plus steady state has been recorded.
 */
class RhythmStartupTrace {

    private val lock = Any()
    private var anchorNs: Long = 0L
    private val lines = ArrayDeque<String>()
    private var dropped = 0

    /** Restart the timeline; [nowBootNs] becomes 0 ms on every subsequent line. */
    fun reset(nowBootNs: Long) {
        synchronized(lock) {
            anchorNs = nowBootNs
            lines.clear()
            dropped = 0
        }
    }

    fun add(nowBootNs: Long, event: String) {
        synchronized(lock) {
            val relMs = (nowBootNs - anchorNs) / 1_000_000.0
            lines.addLast(String.format(Locale.US, "%8.1f  %s", relMs, event))
            if (lines.size > MAX_LINES) {
                lines.removeFirst()
                dropped++
            }
        }
    }

    fun dump(): String = synchronized(lock) {
        buildString {
            appendLine("== startup trace (ms since clock start, wall clock) ==")
            if (dropped > 0) appendLine("... $dropped earliest lines dropped (ring buffer full) ...")
            lines.forEach(::appendLine)
        }
    }

    private companion object {
        const val MAX_LINES = 4000
    }
}
