/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Original work in 2026 for PiscesPlayer
 * (design/piscesplayer docs/white-noise-implementation-plan.md §4).
 */
package com.pisces312.piscesplayer.data.noise

/**
 * Spectra the sleep soundscape synth can produce.
 *
 * The spectrum shape of each color (measured in NoiseSynthesisTest) is what makes it
 * feel the way it does: white carries a rising +3dB/octave band energy that reads as
 * harsh "hiss" (and is therefore only an explicit user choice, not the default),
 * while brown falls steeply and reads as distant rain / rumble.
 */
enum class NoiseColor {
    WHITE,
    PINK,
    BROWN,
    DEEP_BROWN,
}
