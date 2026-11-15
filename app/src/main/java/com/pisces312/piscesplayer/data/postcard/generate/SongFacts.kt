/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Original work in 2026, sound_postcard feature
 * (RhythmChartLab docs/design/sound-postcard-journey-design.md §5, §5.1).
 */
package com.pisces312.piscesplayer.data.postcard.generate

import com.pisces312.piscesplayer.data.rhythm.RhythmNote
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Immutable snapshot of everything one journey may derive from the song (design §5.1).
 *
 * Loaded once at settlement, quantized, and frozen into `postcard_song_profile` on the first
 * valid journey of a song; later re-listens reuse the frozen profile, so a later master
 * regeneration or tag edit can never move a familiar song to another region.
 *
 * M0 leaves the artwork fields empty (cover palette extraction is M1, design §12.1) — the
 * shape is final so no persisted data ever migrates.
 */
data class SongFacts(
    /** Opaque identity, never shown, never contains a plaintext path. */
    val stableSongKey: String,
    /** Library row for the "view song" jump; null after the song is deleted — cards survive. */
    val librarySongId: Long?,
    val title: String,
    val artist: String,
    val durationMs: Long,
    /** Tagged BPM already validated to 40..220 by TrackBpmRepository; null = absent. */
    val bpm: Float?,
    /** Normalized genre key, null when unrecognized — never guessed from artist or filename. */
    val genreKey: String?,
    /** 0..1 fixed-quantile aggregate of the DSP master, or null without usable data. */
    val energy: Float?,
    /** Band occupancy ratios over the master's valid notes; null when no valid band exists. */
    val lowRatio: Float?,
    val midRatio: Float?,
    val highRatio: Float?,
    /** Quantized local cover palette, 0..5 ARGB entries; empty in M0 / no cover. */
    val artworkPaletteArgb: List<Int>,
    val artworkBrightness: Float?,
    /** When the DSP master used as the source was generated; provenance only. */
    val masterGeneratedAtMs: Long?,
)

/** Result of the pure DSP aggregation (design §5.1, fixed algorithm). */
data class DspFeatures(
    val energy: Float?,
    val lowRatio: Float?,
    val midRatio: Float?,
    val highRatio: Float?,
)

/**
 * Fixed, device-independent aggregation over a DSP master's notes (design §5.1):
 *
 * - per-note strength: `rms > 0 ? rms : strength > 0 ? strength : 0` (0 = unusable);
 * - `energy = clamp(0.6 * P50 + 0.4 * P85)` over the usable strengths (nearest-rank
 *   percentiles: `sorted[ceil(p*n) - 1]`);
 * - band ratios weight each valid-band note by that same strength, substituting 0.01 when the
 *   strength is missing, accumulating bands 0..2 / 3..5 / 6..7 and dividing by the total;
 * - all strengths zero -> energy null; no valid band -> all ratios null;
 * - every persisted float is quantized to 0.001; no random sampling, no parallel reduction
 *   order (single-threaded accumulators only).
 */
object SongFactsAggregation {

    /** Quantize a float to 1/1000 — the precision persisted in song profiles and specs. */
    fun quantize(value: Float): Float = (value * 1000f).roundToInt() / 1000f

    fun dspFeatures(notes: List<RhythmNote>): DspFeatures {
        val strengths = notes.map { note ->
            when {
                note.rms > 0f -> note.rms
                note.strength > 0f -> note.strength
                else -> 0f
            }
        }
        val usable = strengths.filter { it > 0f }
        val energy = if (usable.isEmpty()) {
            null
        } else {
            val sorted = usable.sorted()
            quantize(clamp01(0.6f * percentile(sorted, 0.50f) + 0.4f * percentile(sorted, 0.85f)))
        }

        val banded = notes.filter { it.band in 0..7 }
        if (banded.isEmpty()) {
            return DspFeatures(energy = energy, lowRatio = null, midRatio = null, highRatio = null)
        }

        var low = 0.0
        var mid = 0.0
        var high = 0.0
        var total = 0.0
        for (note in banded) {
            // Same effective strength as the energy aggregate; 0.01 when it is missing.
            val strength = if (note.rms > 0f) {
                note.rms.toDouble()
            } else if (note.strength > 0f) {
                note.strength.toDouble()
            } else {
                0.01
            }
            total += strength
            when (note.band) {
                in 0..2 -> low += strength
                in 3..5 -> mid += strength
                else -> high += strength
            }
        }
        return DspFeatures(
            energy = energy,
            lowRatio = quantize((low / total).toFloat()),
            midRatio = quantize((mid / total).toFloat()),
            highRatio = quantize((high / total).toFloat()),
        )
    }

    /**
     * Genre normalization (design §5.1): lowercase, trim, then a fixed alias table. Unknown
     * values map to null — region scoring then falls back to equal priors plus stable jitter,
     * which is still deterministic. Never guesses from artist or filename.
     */
    fun normalizeGenre(raw: String?): String? {
        val key = raw?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        return GENRE_ALIASES[key]
    }

    private val GENRE_ALIASES: Map<String, String> = buildMap {
        fun alias(target: String, vararg names: String) {
            for (name in names) put(name, target)
        }
        alias("folk", "folk", "民谣", "acoustic", "acoustic rock", "singer-songwriter")
        alias("classical", "classical", "古典", "classical music", "orchestral")
        alias("jazz", "jazz", "爵士", "swing", "bossa nova", "bossanova", "vocal jazz")
        alias("electronic", "electronic", "电子", "electronica", "edm", "techno", "house",
            "deep house", "trance", "idm", "drum and bass", "dnb", "dubstep", "synthpop",
            "synth-pop", "electro")
        alias("city_pop", "city_pop", "城市流行", "city pop", "citypop", "city-pop")
        alias("rock", "rock", "摇滚", "punk", "punk rock", "post-rock", "shoegaze")
        alias("metal", "metal", "金属", "heavy metal", "metalcore", "thrash metal", "death metal",
            "doom metal", "black metal")
    }

    /** Nearest-rank percentile on a pre-sorted list: `sorted[ceil(p*n) - 1]`. */
    internal fun percentile(sorted: List<Float>, p: Float): Float {
        require(sorted.isNotEmpty()) { "percentile of empty list" }
        val rank = ceil(p * sorted.size).toInt().coerceIn(1, sorted.size)
        return sorted[rank - 1]
    }

    private fun clamp01(value: Float): Float = value.coerceIn(0f, 1f)
}
