package com.pisces312.piscesplayer.data.midilibrary

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

class MidiFingerprintTest {

    // ---- tiny SMF writer -------------------------------------------------------

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

    private fun smf(division: Int, tracks: List<List<ByteArray>>): ByteArray {
        val head = "MThd".toByteArray(Charsets.US_ASCII) + be32(6) +
            be16(if (tracks.size > 1) 1 else 0) + be16(tracks.size) + be16(division)
        return tracks.fold(head) { acc, t -> acc + trackOf(t) }
    }

    private fun ev(delta: Int, vararg b: Int): ByteArray =
        varlen(delta) + ByteArray(b.size) { b[it].toByte() }

    private fun meta(delta: Int, type: Int, data: ByteArray): ByteArray =
        varlen(delta) + byteArrayOf(0xFF.toByte(), type.toByte()) + varlen(data.size) + data

    private fun name(delta: Int, text: String) = meta(delta, 0x03, text.toByteArray())
    private fun copyright(delta: Int, text: String) = meta(delta, 0x02, text.toByteArray())

    private fun sysex(delta: Int, payload: ByteArray): ByteArray =
        varlen(delta) + byteArrayOf(0xF0.toByte()) + varlen(payload.size) + payload

    private fun tempo(delta: Int, us: Int) = meta(
        delta, 0x51,
        byteArrayOf((us shr 16).toByte(), (us shr 8).toByte(), us.toByte())
    )

    private fun hashOf(bytes: ByteArray): String =
        requireNotNull(MidiFingerprint.musicHash(bytes)) { "parse failed" }

    // ---- tests -----------------------------------------------------------------

    @Test
    fun `metadata-only differences do not change the fingerprint`() {
        val notes = listOf(ev(0, 0x90, 60, 100), ev(96, 0x80, 60, 0))
        val plain = smf(96, listOf(notes))
        val decorated = smf(
            96,
            listOf(
                listOf(
                    copyright(0, "(c) 2026 somebody"),
                    name(0, "Piano right hand"),
                    sysex(0, byteArrayOf(0x7E, 0x7F, 0x09, 0x01, 0xF7.toByte()))
                ) + notes
            )
        )
        assertEquals(hashOf(plain), hashOf(decorated))
    }

    @Test
    fun `different PPQ divisions with scaled deltas hash the same`() {
        val at96 = smf(96, listOf(listOf(ev(0, 0x90, 60, 100), ev(96, 0x80, 60, 0))))
        val at480 = smf(480, listOf(listOf(ev(0, 0x90, 60, 100), ev(480, 0x80, 60, 0))))
        assertEquals(hashOf(at96), hashOf(at480))
    }

    @Test
    fun `running status hashes the same as explicit status`() {
        val explicit = smf(
            96,
            listOf(
                listOf(
                    ev(0, 0x90, 60, 100),
                    ev(96, 0x90, 64, 100),
                    ev(96, 0x80, 64, 0),
                    ev(0, 0x80, 60, 0)
                )
            )
        )
        val running = smf(
            96,
            listOf(
                listOf(
                    ev(0, 0x90, 60, 100),
                    ev(96, 64, 100),
                    ev(96, 0x80, 64, 0),
                    ev(0, 60, 0)
                )
            )
        )
        assertEquals(hashOf(explicit), hashOf(running))
    }

    @Test
    fun `note-on with zero velocity normalizes to note-off`() {
        val withNoteOff =
            smf(96, listOf(listOf(ev(0, 0x90, 60, 100), ev(96, 0x80, 60, 64))))
        val withZeroNoteOn =
            smf(96, listOf(listOf(ev(0, 0x90, 60, 100), ev(96, 0x90, 60, 0))))
        assertEquals(hashOf(withNoteOff), hashOf(withZeroNoteOn))
    }

    @Test
    fun `track split does not change the fingerprint`() {
        val oneTrack = smf(
            96,
            listOf(listOf(name(0, "A"), ev(0, 0x90, 60, 100), ev(96, 0x80, 60, 0)))
        )
        val twoTracks = smf(
            96,
            listOf(
                listOf(name(0, "A"), ev(0, 0x90, 60, 100)),
                listOf(ev(96, 0x80, 60, 0))
            )
        )
        assertEquals(hashOf(oneTrack), hashOf(twoTracks))
    }

    @Test
    fun `pitch differences change the fingerprint`() {
        val c4 = smf(96, listOf(listOf(ev(0, 0x90, 60, 100), ev(96, 0x80, 60, 0))))
        val d4 = smf(96, listOf(listOf(ev(0, 0x90, 62, 100), ev(96, 0x80, 62, 0))))
        assertNotEquals(hashOf(c4), hashOf(d4))
    }

    @Test
    fun `velocity differences change the fingerprint`() {
        val loud = smf(96, listOf(listOf(ev(0, 0x90, 60, 100), ev(96, 0x80, 60, 0))))
        val soft = smf(96, listOf(listOf(ev(0, 0x90, 60, 90), ev(96, 0x80, 60, 0))))
        assertNotEquals(hashOf(loud), hashOf(soft))
    }

    @Test
    fun `tempo differences change the fingerprint and the duration`() {
        val slow = smf(96, listOf(listOf(tempo(0, 1_000_000), ev(0, 0x90, 60, 100), ev(96, 0x80, 60, 0))))
        val fast = smf(96, listOf(listOf(tempo(0, 500_000), ev(0, 0x90, 60, 100), ev(96, 0x80, 60, 0))))
        assertNotEquals(hashOf(slow), hashOf(fast))
        assertEquals(1000L, MidiFingerprint.analyze(slow)!!.durationMs)
        assertEquals(500L, MidiFingerprint.analyze(fast)!!.durationMs)
    }

    @Test
    fun `duration comes from the last event`() {
        // 96 tpqn at the default 120 bpm → one quarter = 500 ms.
        val bytes = smf(96, listOf(listOf(ev(0, 0x90, 60, 100), ev(96, 0x80, 60, 0))))
        val analysis = MidiFingerprint.analyze(bytes)
        assertNotNull(analysis)
        assertEquals(500L, analysis!!.durationMs)
    }

    @Test
    fun `garbage input parses to null instead of throwing`() {
        assertEquals(null, MidiFingerprint.analyze(byteArrayOf(1, 2, 3, 4)))
    }
}
