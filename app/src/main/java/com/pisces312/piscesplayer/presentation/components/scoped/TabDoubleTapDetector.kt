package com.pisces312.piscesplayer.presentation.components.scoped

import android.os.SystemClock

/**
 * Detects a double-tap on the same root-nav tab without Compose state,
 * so the remembered click lambda always sees the latest timestamps.
 */
internal class TabDoubleTapDetector(
    private val windowMillis: Long = 350L,
    private val nowProvider: () -> Long = { SystemClock.elapsedRealtime() }
) {
    private var lastTimestamp = 0L
    private var lastRoute: String? = null

    /** Returns true when this click completes a double-tap on an already-selected tab. */
    fun onClick(route: String, isAlreadySelected: Boolean): Boolean {
        val now = nowProvider()
        val isDoubleTapOnCurrent =
            isAlreadySelected &&
                lastRoute == route &&
                lastTimestamp != 0L &&
                now - lastTimestamp <= windowMillis
        lastTimestamp = now
        lastRoute = route
        return isDoubleTapOnCurrent
    }

    fun reset() {
        lastTimestamp = 0L
        lastRoute = null
    }
}
