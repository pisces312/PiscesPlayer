package com.pisces312.piscesplayer.data.rhythm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the tap-take wire format to the PC recorder's
 * (RhythmChartLab `rhythm_lab/tap_record.py` `TapTake.to_dict()`): a take recorded here must be
 * readable by `tools/tap_config_fit.py`, and a PC take must parse here.
 */
class RhythmTapTakeTest {

    /** A take written exactly like the PC recorder writes it. */
    private val pcTakeJson = """
        {
          "schemaVersion": 1,
          "kind": "tap-take",
          "audio": {
            "path": "test-audio/Ying_Zhe_Feng_Xiang_Qian_Chong.mp3",
            "durationMs": 172875,
            "sampleRate": 44100
          },
          "startedAt": "2026-11-19T18:00:00+08:00",
          "hitCount": 3,
          "hits": [
            {"timeMs": 1196.0, "key": "d"},
            {"timeMs": 1548.3, "key": "j"},
            {"timeMs": 1900.6, "key": "k"}
          ]
        }
    """.trimIndent()

    @Test
    fun parsesPcRecorderTake() {
        val take = RhythmTapTake.fromJson(pcTakeJson)

        assertEquals(1, take.schemaVersion)
        assertEquals("tap-take", take.kind)
        assertEquals("test-audio/Ying_Zhe_Feng_Xiang_Qian_Chong.mp3", take.audio.path)
        assertEquals(172875, take.audio.durationMs)
        assertEquals(44100, take.audio.sampleRate)
        assertEquals("2026-11-19T18:00:00+08:00", take.startedAt)
        assertEquals(3, take.hitCount)
        assertEquals(
            listOf(1196.0 to "d", 1548.3 to "j", 1900.6 to "k"),
            take.hits.map { it.timeMs to it.key },
        )
    }

    @Test
    fun roundTripKeepsEveryField() {
        val take = RhythmTapTake.fromJson(pcTakeJson)

        val decoded = RhythmTapTake.fromJson(take.toJson())

        assertEquals(take, decoded)
    }

    @Test
    fun serializedJsonCarriesThePcFieldNames() {
        val json = RhythmTapTake.fromJson(pcTakeJson).toJson()

        // The PC loader reads these keys by name; renaming any of them breaks interop.
        for (key in listOf("schemaVersion", "kind", "audio", "startedAt", "hitCount", "hits", "timeMs", "key")) {
            assertTrue("missing key $key", "\"$key\"" in json)
        }
    }

    @Test
    fun rejectsFilesNotLabelledTapTake() {
        val osuLike = """{"kind": "chart", "hits": []}"""

        val error = runCatching { RhythmTapTake.fromJson(osuLike) }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
    }

    @Test
    fun keyLaneMappingMatchesPcLaneKeys() {
        // PC LANE_KEYS = {"d": 0, "f": 1, "j": 2, "k": 3}.
        assertEquals(listOf(0, 1, 2, 3), RhythmTapTake.KEYS.map { RhythmTapTake.laneOf(it) })
        assertEquals("d", RhythmTapTake.keyFor(0))
        assertEquals("f", RhythmTapTake.keyFor(1))
        assertEquals("j", RhythmTapTake.keyFor(2))
        assertEquals("k", RhythmTapTake.keyFor(3))
        assertEquals(null, RhythmTapTake.laneOf("x"))
        assertEquals(null, RhythmTapTake.laneOf(""))
    }
}
