/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Original work in 2026 (JVM tests for the SMF parser behind MIDI chart generation).
 */
package com.pisces312.piscesplayer.data.rhythm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class RhythmMidiParserTest {

    // ---- tiny SMF writer (same shape as MidiFingerprintTest's) -------------------

    private fun varlen(v: Int): ByteArray {
        val out = ArrayList<Byte>()
        var value = v
        out.add((value and 0x7F).toByte())
        value = value shr 7
        while (value > 0) {
            out.add(((value and 0x7F) or 0x80).toByte())
            value = value shr 7
        }
        return out.reversed().toByteArray()
    }

    private fun be16(v: Int) = byteArrayOf((v shr 8).toByte(), v.toByte())
    private fun be32(v: Int) =
        byteArrayOf((v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte())

    private fun trackOf(events: List<ByteArray>): ByteArray {
        val body = events.fold(ByteArray(0)) { acc, e -> acc + e } +
            varlen(0) + byteArrayOf(0xFF.toByte(), 0x2F, 0x00)
        return "MTrk".toByteArray(Charsets.US_ASCII) + be32(body.size) + body
    }

    private fun smf(format: Int, division: Int, tracks: List<List<ByteArray>>): ByteArray {
        val head = "MThd".toByteArray(Charsets.US_ASCII) + be32(6) +
            be16(format) + be16(tracks.size) + be16(division)
        return tracks.fold(head) { acc, t -> acc + trackOf(t) }
    }

    private fun ev(delta: Int, vararg b: Int): ByteArray =
        varlen(delta) + ByteArray(b.size) { b[it].toByte() }

    private fun meta(delta: Int, type: Int, data: ByteArray): ByteArray =
        varlen(delta) + byteArrayOf(0xFF.toByte(), type.toByte()) + varlen(data.size) + data

    private fun name(delta: Int, text: String) = meta(delta, 0x03, text.toByteArray())

    private fun tempo(delta: Int, us: Int) = meta(
        delta, 0x51,
        byteArrayOf((us shr 16).toByte(), (us shr 8).toByte(), us.toByte())
    )

    private fun parse(bytes: ByteArray): MidiScore = RhythmMidiParser.parse(bytes)

    private fun parseMustFail(bytes: ByteArray) {
        try {
            parse(bytes)
            fail("expected RhythmGenerationException")
        } catch (e: RhythmGenerationException) {
            // expected
        }
    }

    // ---- tests ---------------------------------------------------------------------

    @Test
    fun `format 1 tracks become named parts with exact times`() {
        // PPQ 480, explicit 120 BPM: one quarter = 500 ms.
        val bytes = smf(
            1, 480,
            listOf(
                listOf(
                    name(0, "Piano"),
                    ev(0, 0x90, 60, 100),
                    ev(480, 0x80, 60, 0),
                    ev(0, 0x90, 64, 90),
                    ev(480, 0x80, 64, 0),
                ),
                listOf(
                    name(0, "Bass"),
                    ev(960, 0x91, 36, 80),
                    ev(480, 0x81, 36, 0),
                ),
            ),
        )

        val score = parse(bytes)

        assertEquals(2, score.parts.size)
        val piano = score.parts[0]
        assertEquals("Piano", piano.name)
        assertEquals(0, piano.channel)
        assertFalse(piano.isDrum)
        assertEquals(2, piano.notes.size)
        assertEquals(0, piano.notes[0].startMs)
        assertEquals(500, piano.notes[0].durationMs)
        assertEquals(500, piano.notes[1].startMs)
        val bass = score.parts[1]
        assertEquals("Bass", bass.name)
        assertEquals(1, bass.channel)
        assertEquals(1000, bass.notes[0].startMs)
        assertEquals(1500, score.durationMs)
        assertEquals(listOf(RhythmTimingPoint(0, 500.0)), score.timing)
    }

    @Test
    fun `tempo map converts ticks piecewise`() {
        // 120 BPM until tick 480 (500 ms), then 240 BPM (250 ms per quarter).
        val bytes = smf(
            1, 480,
            listOf(
                listOf(
                    tempo(0, 500_000),
                    ev(0, 0x90, 60, 100),
                    ev(480, 0x80, 60, 0),
                    tempo(0, 250_000),
                    ev(0, 0x90, 62, 100),
                    ev(480, 0x80, 62, 0),
                ),
            ),
        )

        val score = parse(bytes)

        assertEquals(
            listOf(RhythmTimingPoint(0, 500.0), RhythmTimingPoint(500, 250.0)),
            score.timing,
        )
        val part = score.parts.single()
        assertEquals(500, part.notes.first { it.pitch == 62 }.startMs)
        assertEquals(750, score.durationMs)
    }

    @Test
    fun `tempo after tick 0 leaves the default grid before it`() {
        // No tempo until tick 960: default 120 BPM owns ticks 0..960 (1000 ms).
        val bytes = smf(
            1, 480,
            listOf(
                listOf(
                    ev(0, 0x90, 60, 100),
                    ev(480, 0x80, 60, 0),
                    tempo(480, 250_000),
                    ev(0, 0x90, 62, 100),
                    ev(480, 0x80, 62, 0),
                ),
            ),
        )

        val score = parse(bytes)

        assertEquals(listOf(RhythmTimingPoint(0, 500.0), RhythmTimingPoint(1000, 250.0)), score.timing)
        assertEquals(0, score.parts[0].notes[0].startMs)
        assertEquals(1000, score.parts[0].notes[1].startMs)
    }

    @Test
    fun `note-on with zero velocity ends the note`() {
        val bytes = smf(
            1, 96,
            listOf(listOf(ev(0, 0x90, 60, 100), ev(96, 0x90, 60, 0))),
        )

        val note = parse(bytes).parts[0].notes.single()

        assertEquals(0, note.startMs)
        assertEquals(500, note.durationMs)
    }

    @Test
    fun `running status parses like explicit status`() {
        val bytes = smf(
            1, 96,
            listOf(
                listOf(
                    ev(0, 0x90, 60, 100),
                    ev(96, 64, 100),
                    ev(96, 0x80, 64, 0),
                    ev(0, 60, 0),
                ),
            ),
        )

        val notes = parse(bytes).parts[0].notes

        assertEquals(listOf(60, 64), notes.map { it.pitch })
        assertEquals(listOf(0, 500), notes.map { it.startMs })
    }

    @Test
    fun `an unclosed note rings until the end of its track`() {
        val bytes = smf(
            1, 96,
            listOf(listOf(ev(0, 0x90, 60, 100), ev(96, 0x90, 64, 100), ev(96, 0x80, 64, 0))),
        )

        val notes = parse(bytes).parts[0].notes

        val hanging = notes.single { it.pitch == 60 }
        assertEquals(0, hanging.startMs)
        assertEquals(1000, hanging.durationMs)
    }

    @Test
    fun `format 0 splits a single track into channel parts`() {
        val bytes = smf(
            0, 96,
            listOf(
                listOf(
                    name(0, "All"),
                    ev(0, 0x90, 60, 100),
                    ev(96, 0x80, 60, 0),
                    ev(0, 0x91, 48, 100),
                    ev(96, 0x81, 48, 0),
                    ev(0, 0x99, 38, 100),
                    ev(96, 0x89, 38, 0),
                ),
            ),
        )

        val score = parse(bytes)

        assertEquals(3, score.parts.size)
        assertEquals(listOf(0, 1, 9), score.parts.map { it.channel })
        assertTrue(score.parts[2].isDrum)
        assertFalse(score.parts[0].isDrum)
    }

    @Test
    fun `a mixed-channel track reports channel minus one`() {
        val bytes = smf(
            1, 96,
            listOf(
                listOf(
                    ev(0, 0x90, 60, 100),
                    ev(96, 0x80, 60, 0),
                    ev(0, 0x91, 48, 100),
                    ev(96, 0x81, 48, 0),
                ),
            ),
        )

        val part = parse(bytes).parts.single()

        assertEquals(-1, part.channel)
        assertFalse(part.isDrum)
    }

    @Test
    fun `SMPTE division converts at frames per second`() {
        // 0xE728: 25 fps, 40 ticks per frame → exactly 1 ms per tick.
        val bytes = smf(
            1, 0xE728,
            listOf(listOf(ev(0, 0x90, 60, 100), ev(100, 0x80, 60, 0))),
        )

        val score = parse(bytes)

        assertEquals(100, score.durationMs)
        assertEquals(100, score.parts[0].notes[0].durationMs)
    }

    @Test
    fun `garbage input fails with a generation exception`() {
        parseMustFail(byteArrayOf(1, 2, 3, 4))
    }

    @Test
    fun `a file without notes fails`() {
        val bytes = smf(1, 96, listOf(listOf(name(0, "Empty"), tempo(0, 500_000))))
        parseMustFail(bytes)
    }

    @Test
    fun `format 2 is rejected`() {
        val bytes = smf(2, 96, listOf(listOf(ev(0, 0x90, 60, 100), ev(96, 0x80, 60, 0))))
        parseMustFail(bytes)
    }
}
