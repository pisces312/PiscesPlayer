/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Original work in 2026 for PixelPlayerOSS
 * (docs/plans/manual-tap-chart-mobile-plan.md §3).
 */
package com.pisces312.piscesplayer.data.rhythm

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Audio a tap take was recorded against; kept for provenance and PC-side analysis. */
@Serializable
data class RhythmTapAudio(
    val path: String = "",
    val durationMs: Int = 0,
    val sampleRate: Int = 0,
)

/**
 * One recorded tap. [timeMs] is song time in the audio-clock domain (one decimal, like the PC
 * recorder writes). [key] is the lane key as pressed: "d"/"f"/"j"/"k" in the PC recorder's
 * LANE_KEYS order, empty when unknown.
 */
@Serializable
data class RhythmTapHit(
    val timeMs: Double,
    val key: String = "",
)

/**
 * A manual tap recording ("take").
 *
 * The wire format is field-identical to the PC recorder
 * (RhythmChartLab `rhythm_lab/tap_record.py` `TapTake.to_dict()`): a take recorded on the phone
 * can be analysed on the PC (`tools/tap_config_fit.py`) and a PC take can be imported into a
 * chart here. `hitCount` mirrors `hits.size` like the PC writer does.
 */
@Serializable
data class RhythmTapTake(
    val schemaVersion: Int = SCHEMA_VERSION,
    val kind: String = KIND,
    val audio: RhythmTapAudio = RhythmTapAudio(),
    val startedAt: String = "",
    val hitCount: Int = 0,
    val hits: List<RhythmTapHit> = emptyList(),
) {
    fun toJson(): String = json.encodeToString(serializer(), this)

    companion object {
        const val SCHEMA_VERSION = 1
        const val KIND = "tap-take"

        /** Lane keys in lane order; identical to the PC recorder's `LANE_KEYS`. */
        val KEYS = listOf("d", "f", "j", "k")

        // encodeDefaults so schemaVersion/kind/hitCount are written like the PC writer does;
        // ignoreUnknownKeys tolerates older/newer takes from the PC side.
        private val json = Json {
            encodeDefaults = true
            ignoreUnknownKeys = true
            prettyPrint = true
        }

        /** Parse a take; anything not labelled `tap-take` is rejected, like the PC loader. */
        fun fromJson(text: String): RhythmTapTake {
            val take = json.decodeFromString(serializer(), text)
            require(take.kind == KIND) { "not a tap-take file (kind=${take.kind})" }
            return take
        }

        fun laneOf(key: String): Int? = KEYS.indexOf(key).takeIf { it >= 0 }

        fun keyFor(lane: Int): String = KEYS.getOrElse(lane) { "" }
    }
}
