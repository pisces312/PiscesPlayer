package com.lostf1sh.pixelplayeross.data.rhythm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class OsuManiaParserTest {

    private fun chart(vararg objects: String): String = buildString {
        appendLine("osu file format v14")
        appendLine("[General]")
        appendLine("Mode: 3")
        appendLine("[Metadata]")
        appendLine("Title: Test")
        appendLine("Artist: Tester")
        appendLine("Version: Normal")
        appendLine("[Difficulty]")
        appendLine("CircleSize: 4")
        appendLine("[TimingPoints]")
        appendLine("0,500,4,2,0,60,1,0")
        appendLine("1000,-100,4,2,0,60,0,0")
        appendLine("[HitObjects]")
        objects.forEach { appendLine(it) }
    }

    @Test
    fun parsesLaneFromXAndKeepsChords() {
        val parsed = OsuManiaParser.parse(
            chart(
                "0,192,1000,1,0,0:0:0:0:",
                "256,192,1000,1,0,0:0:0:0:",
                "511,192,2000,1,0,0:0:0:0:",
            )
        )

        assertEquals(3, parsed.notes.size)
        assertEquals(0, parsed.notes[0].lane)
        assertEquals(2, parsed.notes[1].lane) // x=256 -> lane 2
        assertEquals(3, parsed.notes[2].lane) // x=511 -> lane 3
        assertEquals(0, parsed.notes[0].id)
        assertEquals(1, parsed.notes[1].id)
        assertEquals(2, parsed.notes[2].id)
        assertEquals(2000, parsed.durationMs)
    }

    @Test
    fun deduplicatesSameLaneSameTimeButKeepsOtherLanes() {
        val parsed = OsuManiaParser.parse(
            chart(
                "0,192,1000,1,0,0:0:0:0:",
                "10,192,1000,1,0,0:0:0:0:",
                "256,192,1000,1,0,0:0:0:0:",
            )
        )

        assertEquals(2, parsed.notes.size)
        assertEquals(setOf(0, 2), parsed.notes.map { it.lane }.toSet())
    }

    @Test
    fun ignoresInheritedTimingPoints() {
        val parsed = OsuManiaParser.parse(chart("0,192,1000,1,0,0:0:0:0:"))

        assertEquals(1, parsed.timing.size)
        assertEquals(500.0, parsed.timing[0].beatMs, 0.0)
    }

    @Test
    fun rejectsNonManiaChart() {
        val text = chart().replace("Mode: 3", "Mode: 0")
        assertThrows(OsuParseException::class.java) { OsuManiaParser.parse(text) }
    }

    @Test
    fun rejectsNonFourKeyChart() {
        val text = chart().replace("CircleSize: 4", "CircleSize: 7")
        assertThrows(OsuParseException::class.java) { OsuManiaParser.parse(text) }
    }

    @Test
    fun rejectsHoldNotes() {
        assertThrows(OsuParseException::class.java) {
            OsuManiaParser.parse(chart("0,192,1000,128,2000,0:0:0:0:"))
        }
    }

    @Test
    fun rejectsEmptyHitObjects() {
        assertThrows(OsuParseException::class.java) { OsuManiaParser.parse(chart()) }
    }

    @Test
    fun handlesCrlfAndUnknownSections() {
        val text = chart("0,192,1000,1,0,0:0:0:0:")
            .replace("\n", "\r\n") + "[Events]\r\n0,0\r\n"
        val parsed = OsuManiaParser.parse(text)

        assertEquals(1, parsed.notes.size)
        assertTrue(parsed.timing.isNotEmpty())
    }
}
