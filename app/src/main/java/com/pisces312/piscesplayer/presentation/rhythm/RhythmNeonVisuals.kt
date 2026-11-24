/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Original work in 2026 (neon rhythm visuals: pure helpers and palette).
 */
package com.pisces312.piscesplayer.presentation.rhythm

import androidx.compose.ui.graphics.Color
import com.pisces312.piscesplayer.data.rhythm.RhythmTimingPoint

/*
 * Pure, JVM-testable logic behind the neon playfield: grade mapping, beat-grid math, combo
 * tiers and the note strength scaling. Nothing here touches Compose state, Android or the
 * judgement engine — the visuals only ever read values the engine already produced.
 */

/** Result grade shown by the neon result screen. */
enum class RhythmGrade { S, A, B, C }

/**
 * Grade from hit-value accuracy (0..1 as returned by `RhythmJudgementEngine.accuracy`):
 * ≥ 95 % S, ≥ 90 % A, ≥ 80 % B, anything else C.
 */
fun rhythmGradeFor(accuracy: Double): RhythmGrade = when {
    accuracy >= 0.95 -> RhythmGrade.S
    accuracy >= 0.90 -> RhythmGrade.A
    accuracy >= 0.80 -> RhythmGrade.B
    else -> RhythmGrade.C
}

/** Colour of a grade letter, gold for the top tier. */
fun rhythmGradeColor(grade: RhythmGrade): Color = when (grade) {
    RhythmGrade.S -> Color(0xFFFFD24B)
    RhythmGrade.A -> Color(0xFF5CE1FF)
    RhythmGrade.B -> Color(0xFF7FFFC2)
    RhythmGrade.C -> Color(0xFFB8BCC9)
}

/** Combo colour tiers: base, then 30 / 50 / 100 hit milestones. */
enum class RhythmComboTier { BASE, TIER_30, TIER_50, TIER_100 }

/** Tier a combo counter belongs to: 30/50/100 hits recolour the counter. */
fun rhythmComboTier(combo: Int): RhythmComboTier = when {
    combo >= 100 -> RhythmComboTier.TIER_100
    combo >= 50 -> RhythmComboTier.TIER_50
    combo >= 30 -> RhythmComboTier.TIER_30
    else -> RhythmComboTier.BASE
}

fun rhythmComboTierColor(tier: RhythmComboTier): Color = when (tier) {
    RhythmComboTier.BASE -> Color(0xFFB9E7FF)
    RhythmComboTier.TIER_30 -> Color(0xFF5CE1FF)
    RhythmComboTier.TIER_50 -> Color(0xFFFF66D9)
    RhythmComboTier.TIER_100 -> Color(0xFFFFC24B)
}

/**
 * True when the combo crossed a [step]-hit milestone between [previous] and [current], used for
 * the full-screen brightness pulse (every 50 combo). Crossing-based so a chord that lands two
 * hits in one frame cannot skip a milestone, and a replayed value cannot double-fire.
 */
fun rhythmComboPulseCrossed(previous: Int, current: Int, step: Int = 50): Boolean {
    if (step <= 0 || current <= previous) return false
    return current / step > previous / step
}

/**
 * Beat timestamps in `[fromMs, toMs]` from a piecewise tempo map.
 *
 * Each [RhythmTimingPoint] starts a segment at `timeMs` with beat length `beatMs`, running until
 * the next point (half-open, so a segment boundary is emitted by the following segment's first
 * beat only). Segments with `beatMs <= 0` contribute nothing. Empty timing → empty list.
 */
fun rhythmBeatTimes(
    timing: List<RhythmTimingPoint>,
    fromMs: Double,
    toMs: Double,
    maxBeats: Int = 10_000,
): List<Double> {
    if (timing.isEmpty() || fromMs > toMs || maxBeats <= 0) return emptyList()
    val out = ArrayList<Double>()
    for (index in timing.indices) {
        if (out.size >= maxBeats) break
        val segment = timing[index]
        val beatMs = segment.beatMs
        if (beatMs <= 0.0) continue
        val segmentStart = segment.timeMs.toDouble()
        // Half-open segment: the boundary beat belongs to the next segment.
        val segmentEnd = if (index + 1 < timing.size) {
            timing[index + 1].timeMs.toDouble()
        } else {
            toMs + 1.0
        }
        val lastMs = minOf(toMs, segmentEnd)
        if (lastMs < segmentStart || lastMs < fromMs) continue
        // First beat at or after max(fromMs, segmentStart).
        val firstMs = maxOf(fromMs, segmentStart)
        var k = Math.ceil((firstMs - segmentStart) / beatMs).toLong().coerceAtLeast(0L)
        while (out.size < maxBeats) {
            val beat = segmentStart + k * beatMs
            if (beat > lastMs || beat >= segmentEnd) break
            if (beat >= fromMs) out += beat
            k++
        }
    }
    return out
}

/**
 * Where [nowMs] sits inside its current beat, 0 at the beat and just under 1 right before the
 * next one; -1.0 when the timing map has no segment covering [nowMs] (or no timing at all).
 */
fun rhythmBeatPhase(timing: List<RhythmTimingPoint>, nowMs: Double): Double {
    if (timing.isEmpty()) return -1.0
    var segment: RhythmTimingPoint? = null
    for (point in timing) {
        if (point.timeMs.toDouble() <= nowMs) segment = point else break
    }
    val active = segment ?: return -1.0
    if (active.beatMs <= 0.0) return -1.0
    val phase = (nowMs - active.timeMs) / active.beatMs
    return phase - kotlin.math.floor(phase)
}

/**
 * Breathing brightness for the beat pulse: 1 exactly on a beat, easing out to 0 across the
 * beat. 0 when there is no usable timing — the pulse just stays off.
 */
fun rhythmBeatPulse(timing: List<RhythmTimingPoint>, nowMs: Double): Float {
    val phase = rhythmBeatPhase(timing, nowMs)
    if (phase < 0.0) return 0f
    val decay = 1.0 - phase
    return (decay * decay).toFloat().coerceIn(0f, 1f)
}

/**
 * Note brightness from `RhythmNote.strength` (velocity/127-ish). Unknown strength (0, e.g. osu
 * imports) maps to a neutral mid value, so mixed libraries look consistent: strong notes glow
 * hotter, weak notes dimmer.
 */
fun rhythmNoteBrightness(strength: Float): Float {
    val normalized = if (strength <= 0f) 0.5f else strength.coerceIn(0f, 1f)
    return 0.72f + 0.28f * normalized
}

/** Note thickness multiplier from the same strength: accents read as physically bigger. */
fun rhythmNoteThickness(strength: Float): Float {
    val normalized = if (strength <= 0f) 0.5f else strength.coerceIn(0f, 1f)
    return 0.85f + 0.40f * normalized
}

/** Neon palette: everything the playfield and overlays draw with. */
internal object RhythmNeonPalette {
    val background = Color(0xFF070A12)
    val laneEven = Color(0xFF111624)
    val laneOdd = Color(0xFF151B2B)
    val separator = Color(0xFF2A3450)

    /** Per-lane glow hues (key area, note core, particles). */
    val laneTop = listOf(
        Color(0xFF54E8FF),
        Color(0xFFFF7ADF),
        Color(0xFF63FFB0),
        Color(0xFFFFC466),
    )
    val laneNote = listOf(
        Color(0xFF6FE9FF),
        Color(0xFFFF92E4),
        Color(0xFF7FFFC2),
        Color(0xFFFFD28A),
    )
    val laneBottom = listOf(
        Color(0xFF0E5FC8),
        Color(0xFF9C22C4),
        Color(0xFF0D9B62),
        Color(0xFFD0521C),
    )

    val judgementLine = Color(0xFF8BE9FD)
    val noteActive = Color(0xFFB5E6FF)
    val missFlash = Color(0xFFFF3B55)
    val star = Color(0xFF9BB4FF)
}
