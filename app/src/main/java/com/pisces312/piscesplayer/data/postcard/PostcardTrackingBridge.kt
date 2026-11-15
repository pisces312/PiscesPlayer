/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Original work in 2026, sound_postcard feature
 * (RhythmChartLab docs/design/sound-postcard-journey-design.md §4.4, §11).
 */
package com.pisces312.piscesplayer.data.postcard

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide bridge between the player UI and the service-side observer (same pattern as
 * `EotStateHolder`, which already links `PlayerViewModel` and `MusicService`).
 *
 * The UI must never hold a `Player` reference to "register" a journey: the observer needs the
 * service's master player, and registering from a `MediaController` would risk a second
 * business path (design §4.2.1 forbids that). So `MusicService` installs the callback once, the
 * UI only asks for it and reports the outcome.
 *
 * [status] backs the "带上它" chip state: IDLE (nothing travelling), TRACKING (an attempt is
 * live), UNAVAILABLE (cloud / quick play / no current item). "Already tracking another song"
 * deliberately maps to TRACKING, not UNAVAILABLE — the live attempt must stay visible.
 */
object PostcardTrackingBridge {

    enum class Status { IDLE, TRACKING, UNAVAILABLE }

    /** Installed by `MusicService`; reports the observer's three-valued registration result. */
    private var register: (() -> JourneyPlaybackObserver.Companion.RegisterOutcome)? = null

    /** Installed by `MusicService`; cancels the live attempt without a reward. */
    private var cancel: (() -> Unit)? = null

    private val _status = MutableStateFlow(Status.IDLE)
    val status: StateFlow<Status> = _status.asStateFlow()

    fun install(
        register: () -> JourneyPlaybackObserver.Companion.RegisterOutcome,
        cancel: () -> Unit,
    ) {
        this.register = register
        this.cancel = cancel
    }

    fun uninstall() {
        register = null
        cancel = null
        _status.value = Status.IDLE
    }

    /**
     * Manual "带上它": registers the currently playing local song, never touches playback.
     *
     * ALREADY_TRACKING keeps (or restores) TRACKING instead of downgrading to UNAVAILABLE:
     * another song is still travelling and will still settle — the chip must keep saying so.
     */
    fun register(): Boolean {
        val outcome = register?.invoke()
        when (outcome) {
            JourneyPlaybackObserver.Companion.RegisterOutcome.STARTED,
            JourneyPlaybackObserver.Companion.RegisterOutcome.ALREADY_TRACKING,
            -> _status.value = Status.TRACKING
            else -> _status.value = Status.UNAVAILABLE
        }
        return outcome == JourneyPlaybackObserver.Companion.RegisterOutcome.STARTED
    }

    /** "取消随行": drops the live attempt; the song keeps playing untouched. */
    fun cancel() {
        cancel?.invoke()
        _status.value = Status.IDLE
    }

    /** Called by the observer when an attempt ends, settles or is cancelled. */
    fun markIdle() {
        _status.value = Status.IDLE
    }
}
