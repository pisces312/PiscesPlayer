/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Original work in 2026 for PiscesPlayer
 * (design/piscesplayer docs/white-noise-implementation-plan.md §3.3).
 */
package com.pisces312.piscesplayer.data.noise

import androidx.annotation.StringRes
import com.pisces312.piscesplayer.R

/**
 * The Phase A sound library: one entry per procedural noise color. A sound id
 * identifies a track within a mix and a [NoiseColor] for the synth; noise
 * colors are separate sounds, so any subset of them can be stacked (e.g.
 * brown + pink is a real use case), while the same id can only exist once
 * per mix ("three layers of rain" is a mis-tap, not a feature).
 *
 * Phase B will append natural sounds with the same id contract but an
 * [AssetLoopSource] behind them — this catalog is the seam.
 */
enum class SoundscapeSound(
    val id: String,
    val noiseColor: NoiseColor,
    @StringRes val nameRes: Int,
) {
    WHITE("white", NoiseColor.WHITE, R.string.soundscape_noise_white),
    PINK("pink", NoiseColor.PINK, R.string.soundscape_noise_pink),
    BROWN("brown", NoiseColor.BROWN, R.string.soundscape_noise_brown),
    DEEP_BROWN("deep_brown", NoiseColor.DEEP_BROWN, R.string.soundscape_noise_deep_brown);

    companion object {
        fun fromId(id: String): SoundscapeSound? = entries.firstOrNull { it.id == id }
    }
}
