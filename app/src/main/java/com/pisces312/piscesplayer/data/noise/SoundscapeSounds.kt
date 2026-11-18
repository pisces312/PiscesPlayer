/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Original work in 2026 for PiscesPlayer
 * (design/piscesplayer docs/white-noise-implementation-plan.md §3.3).
 *
 * Natural field recordings: awr1001/natures-music-android, audio licensed
 * CC BY 4.0 (© Aaron Roberts / App-O-Matic); see docs/DEPENDENCY_LICENSES.md.
 * Sources were trimmed to ~11–30 s, loudness-normalized to a -21 dBFS bed
 * level and faded at both ends; the seamless loop crossfade happens at
 * playback time in PcmLoopPlayer.
 */
package com.pisces312.piscesplayer.data.noise

import androidx.annotation.RawRes
import androidx.annotation.StringRes
import com.pisces312.piscesplayer.R

/**
 * The sound library: one entry per addable sound. A sound id identifies a
 * track within a mix and selects the engine source behind it —
 * [noiseColor] sounds run the procedural synth, [rawRes] sounds loop a
 * decoded natural recording through [AssetLoopSource]. The same id can only
 * exist once per mix ("three layers of rain" is a mis-tap, not a feature),
 * while any subset of different ids can be stacked.
 */
enum class SoundscapeSound(
    val id: String,
    /** Procedural generator color; null for natural recordings. */
    val noiseColor: NoiseColor?,
    @StringRes val nameRes: Int,
    /** Raw OGG loop resource; null for procedural noise. */
    @RawRes val rawRes: Int? = null,
) {
    WHITE("white", NoiseColor.WHITE, R.string.soundscape_noise_white),
    PINK("pink", NoiseColor.PINK, R.string.soundscape_noise_pink),
    BROWN("brown", NoiseColor.BROWN, R.string.soundscape_noise_brown),
    DEEP_BROWN("deep_brown", NoiseColor.DEEP_BROWN, R.string.soundscape_noise_deep_brown),

    RAIN("rain", null, R.string.soundscape_nature_rain, R.raw.soundscape_rain),
    WIND("wind", null, R.string.soundscape_nature_wind, R.raw.soundscape_wind),
    THUNDER("thunder", null, R.string.soundscape_nature_thunder, R.raw.soundscape_thunder),
    FIRE("fire", null, R.string.soundscape_nature_fire, R.raw.soundscape_fire),
    OCEAN("ocean", null, R.string.soundscape_nature_ocean, R.raw.soundscape_ocean),
    CRICKETS("crickets", null, R.string.soundscape_nature_crickets, R.raw.soundscape_crickets);

    /** True for synthesized noise colors (tone/motion/width parameters apply). */
    val isSynth: Boolean get() = noiseColor != null

    companion object {
        fun fromId(id: String): SoundscapeSound? = entries.firstOrNull { it.id == id }
    }
}
