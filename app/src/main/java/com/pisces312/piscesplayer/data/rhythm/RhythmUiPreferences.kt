/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Original work in 2026 (rhythm game visual-style preferences).
 */
package com.pisces312.piscesplayer.data.rhythm

import android.content.Context
import android.content.SharedPreferences

/**
 * Visual style of the rhythm game surface.
 *
 * [NEON] is the default since 2026-11-25 (user decision: the glow/particle presentation is the
 * face of the game). [CLASSIC] is the plain pre-existing playfield, kept for users who picked it
 * explicitly. The stored value always wins — only never-set preferences flip to neon.
 */
enum class RhythmUiStyle { CLASSIC, NEON }

/**
 * Global rhythm-game visual preferences (song-independent), persisted immediately on every write
 * like [RhythmLatencyStore].
 *
 * - [uiStyle]: neon (default) or classic surface.
 * - [lowMotion]: only meaningful for [RhythmUiStyle.NEON]; turns the animated extras (particles,
 *   star field, beat pulses, screen pulses) off while keeping the glowing judgement line and the
 *   strength-scaled notes.
 */
class RhythmUiPreferences(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var uiStyle: RhythmUiStyle
        get() {
            val stored = prefs.getString(KEY_UI_STYLE, null)
            return RhythmUiStyle.entries.firstOrNull { it.name == stored } ?: RhythmUiStyle.NEON
        }
        set(value) {
            prefs.edit().putString(KEY_UI_STYLE, value.name).apply()
        }

    var lowMotion: Boolean
        get() = prefs.getBoolean(KEY_LOW_MOTION, false)
        set(value) {
            prefs.edit().putBoolean(KEY_LOW_MOTION, value).apply()
        }

    companion object {
        const val PREFS_NAME = "rhythm_ui"
        const val KEY_UI_STYLE = "ui_style"
        const val KEY_LOW_MOTION = "low_motion"
    }
}
