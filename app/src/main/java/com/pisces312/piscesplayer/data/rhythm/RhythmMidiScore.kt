/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Original work in 2026 (SMF parser for MIDI-driven chart generation,
 * docs/../../RhythmChartLab docs/design/rhythm-midi-chart-plan.md §4.1).
 */
package com.pisces312.piscesplayer.data.rhythm

/**
 * One note of a MIDI part. [startMs] is absolute song time in milliseconds, converted
 * through the file's tempo map — for MIDI this is exact, unlike DSP-detected onsets.
 */
data class MidiNote(
    val startMs: Int,
    val durationMs: Int,
    /** MIDI pitch 0..127. */
    val pitch: Int,
    /** Note-on velocity 1..127 (0 means off and never survives parsing). */
    val velocity: Int,
)

/**
 * A playable division of a MIDI file — one track of a format-1 file, or one channel of a
 * format-0 single track (see [RhythmMidiParser]).
 */
data class MidiPart(
    /** Track name meta, or a generated "Part N" when the file carries none. */
    val name: String,
    /** The channel every note lives on, -1 when the track mixes channels. */
    val channel: Int,
    /** GM percussion: every note on channel 9 (drums are lane-mapped by rotation, not pitch). */
    val isDrum: Boolean,
    /** Sorted by [MidiNote.startMs]. */
    val notes: List<MidiNote>,
)

/**
 * A parsed Standard MIDI File: the tempo map as exact beat grid plus the note content
 * split into parts. This is the full input the MIDI chart generator needs — no audio
 * decoding, no estimation.
 */
data class MidiScore(
    val durationMs: Int,
    /** Tempo map: piecewise [RhythmTimingPoint] list, sorted, the first point at timeMs 0. */
    val timing: List<RhythmTimingPoint>,
    val parts: List<MidiPart>,
)

/**
 * Minimal SMF (Standard MIDI File) parser exposing the musical content [RhythmMidiChartGenerator]
 * needs. Deliberately independent from `MidiFingerprint` (data/midilibrary): the fingerprint
 * parser discards track structure and keeps only a canonical event stream, while chart
 * generation needs per-track notes, so sharing code would couple two different consumers.
 *
 * Supported: format 0/1 (format 2 rejected), running status, note-on velocity 0 as note-off,
 * PPQ and SMPTE divisions, tempo maps, track-name meta. Everything else (lyrics, SysEx, …)
 * is skipped.
 */
object RhythmMidiParser {

    /** Parses [bytes] into a [MidiScore]; throws [RhythmGenerationException] on any failure. */
    fun parse(bytes: ByteArray): MidiScore = try {
        parseInner(bytes)
    } catch (e: RhythmGenerationException) {
        throw e
    } catch (e: Exception) {
        throw RhythmGenerationException("Unable to parse the MIDI file")
    }

    // ---- event model -----------------------------------------------------------

    private class RawNote(
        val channel: Int,
        val pitch: Int,
        val velocity: Int,
        val onTick: Long,
        var offTick: Long,
    )

    private class RawTrack(
        var name: String = "",
        val notes: MutableList<RawNote> = ArrayList(),
    )

    // ---- parsing ---------------------------------------------------------------

    private class Reader(val b: ByteArray) {
        var pos = 0

        fun u8(): Int = b[pos++].toInt() and 0xFF

        fun u16(): Int = (u8() shl 8) or u8()

        fun u32(): Long =
            (u8().toLong() shl 24) or (u8().toLong() shl 16) or (u8().toLong() shl 8) or u8().toLong()

        fun varlen(): Long {
            var v = 0L
            repeat(4) {
                val c = u8()
                v = (v shl 7) or (c and 0x7F).toLong()
                if (c and 0x80 == 0) return v
            }
            return v
        }

        fun bytes(n: Int): ByteArray {
            val out = b.copyOfRange(pos, pos + n)
            pos += n
            return out
        }

        fun tag(n: Int): String = bytes(n).toString(Charsets.US_ASCII)

        fun skip(n: Int) {
            pos += n
        }
    }

    private fun parseInner(input: ByteArray): MidiScore {
        val r = Reader(input)
        if (r.tag(4) != "MThd") throw RhythmGenerationException("Not a MIDI file")
        val hdrLen = r.u32().toInt()
        val format = r.u16()
        val ntrks = r.u16()
        val division = r.u16()
        if (hdrLen > 6) r.skip(hdrLen - 6)
        if (format == 2) throw RhythmGenerationException("MIDI format 2 is not supported")
        if (ntrks <= 0) throw RhythmGenerationException("MIDI file has no tracks")

        val smpte = division and 0x8000 != 0
        val smpteFps: Int
        val smpteTpf: Int
        val ppq: Int
        if (smpte) {
            smpteFps = -(division shr 8).toByte().toInt()
            smpteTpf = division and 0xFF
            ppq = 0
            if (smpteFps <= 0 || smpteTpf <= 0) {
                throw RhythmGenerationException("Unsupported MIDI time division")
            }
        } else {
            smpteFps = 0
            smpteTpf = 0
            ppq = division
            if (ppq <= 0) throw RhythmGenerationException("Unsupported MIDI time division")
        }

        val tracks = ArrayList<RawTrack>(ntrks)
        // (absTick, usPerQuarter) across all tracks; SMF has one global tempo map.
        val tempos = ArrayList<Pair<Long, Long>>()

        repeat(ntrks) { trackIndex ->
            if (r.tag(4) != "MTrk") throw RhythmGenerationException("MIDI track header is corrupt")
            val len = r.u32().toInt()
            val end = r.pos + len
            val track = RawTrack()
            tracks += track
            var tick = 0L
            var running = -1
            // Open notes keyed by (channel, pitch); a stack because retriggering the same
            // pitch before its off is legal.
            val open = HashMap<Long, ArrayDeque<RawNote>>()
            while (r.pos < end) {
                tick += r.varlen()
                var head = r.u8()
                if (head < 0x80) {
                    if (running < 0x80) throw RhythmGenerationException("MIDI running status is corrupt")
                    r.pos -= 1
                    head = running
                } else if (head < 0xF0) {
                    running = head
                }
                when {
                    head == 0xFF -> {
                        val type = r.u8()
                        val mlen = r.varlen().toInt()
                        val mdata = r.bytes(mlen)
                        when (type) {
                            // First track name wins; later renames are editor noise.
                            0x03 -> if (track.name.isEmpty()) {
                                track.name = mdata.toString(Charsets.UTF_8).trim()
                            }
                            0x51 -> if (mlen == 3) {
                                val us = ((mdata[0].toLong() and 0xFF) shl 16) or
                                    ((mdata[1].toLong() and 0xFF) shl 8) or
                                    (mdata[2].toLong() and 0xFF)
                                tempos += tick to us
                            }
                            0x2F -> {
                                closeOpenNotes(track, open, tick)
                                r.pos = end
                            }
                        }
                    }
                    head == 0xF0 || head == 0xF7 -> r.skip(r.varlen().toInt())
                    else -> {
                        val hi = head and 0xF0
                        val ch = head and 0x0F
                        val d1 = r.u8()
                        val d2 = if (hi == 0xC0 || hi == 0xD0) 0 else r.u8()
                        when (hi) {
                            0x90 -> if (d2 == 0) {
                                noteOff(track, open, ch, d1, tick)
                            } else {
                                open.getOrPut((ch shl 8 or d1).toLong()) { ArrayDeque() }
                                    .addLast(RawNote(ch, d1, d2, tick, tick))
                            }
                            0x80 -> noteOff(track, open, ch, d1, tick)
                        }
                    }
                }
            }
            // End-of-track without an explicit meta: whatever is still ringing stops here.
            closeOpenNotes(track, open, tick)
        }

        // ---- tick → ms through the tempo map --------------------------------------

        // Precompute segments once; conversion is then a pure function callable in any
        // order (the tempo-map scan must not share mutable cursor state with note
        // conversion below).
        val tickToMs: (Long) -> Double = if (smpte) {
            val msPerTick = 1000.0 / (smpteFps * smpteTpf)
            { tick -> tick * msPerTick }
        } else {
            tempos.sortBy { it.first }
            // Events at the same tick: the last one wins (that is what a renderer applies).
            val byTick = tempos.groupBy({ it.first }, { it.second })
            val segStartTicks = byTick.keys.toSortedSet()
            // (startTick, startMs, usPerQuarter); segments between consecutive tempo events.
            data class Seg(val startTick: Long, val startMs: Double, val usPerQn: Long)
            // The implicit 120 BPM default owns everything before the first tempo event;
            // an explicit tempo at tick 0 overrides it by appearing later in the list.
            val segs = ArrayList<Seg>()
            segs += Seg(0, 0.0, 500_000L)
            var segStartTick = 0L
            var segStartMs = 0.0
            var usPerQn = 500_000L
            for (t in segStartTicks) {
                segStartMs += (t - segStartTick) * usPerQn / 1000.0 / ppq
                segs += Seg(t, segStartMs, byTick.getValue(t).last())
                segStartTick = t
                usPerQn = byTick.getValue(t).last()
            }
            // Sorted by startTick, so the last segment with startTick <= tick owns it.
            { tick ->
                val i = segs.indexOfLast { it.startTick <= tick }.coerceAtLeast(0)
                val seg = segs[i]
                seg.startMs + (tick - seg.startTick) * seg.usPerQn / 1000.0 / ppq
            }
        }

        // ---- tempo map → beat grid -------------------------------------------------

        val timing = ArrayList<RhythmTimingPoint>()
        if (!smpte) {
            val gridTicks = tempos.map { it.first }.toSortedSet()
            for (t in gridTicks) {
                val us = tempos.last { it.first == t }.second
                val timeMs = tickToMs(t).toInt()
                if (timing.isNotEmpty() && timing.last().timeMs >= timeMs) {
                    timing.removeAt(timing.size - 1)
                }
                timing += RhythmTimingPoint(timeMs, us / 1000.0)
            }
        }
        if (timing.isEmpty() || timing.first().timeMs > 0) {
            timing.add(0, RhythmTimingPoint(0, 500.0))
        }

        // ---- tracks → parts ---------------------------------------------------------

        val parts = ArrayList<MidiPart>()
        for ((index, track) in tracks.withIndex()) {
            if (track.notes.isEmpty()) continue
            if (format == 0) {
                // One track usually carries every instrument on its own channel.
                track.notes.groupBy { it.channel }.toSortedMap().forEach { (ch, notes) ->
                    parts += MidiPart(
                        name = track.name.ifEmpty { "Part ${ch + 1}" },
                        channel = ch,
                        isDrum = ch == 9,
                        notes = finishNotes(notes, tickToMs),
                    )
                }
            } else {
                val channels = track.notes.mapTo(HashSet()) { it.channel }
                parts += MidiPart(
                    name = track.name.ifEmpty { "Track ${index + 1}" },
                    channel = if (channels.size == 1) channels.first() else -1,
                    isDrum = channels.singleOrNull() == 9,
                    notes = finishNotes(track.notes, tickToMs),
                )
            }
        }
        if (parts.isEmpty()) throw RhythmGenerationException("MIDI file contains no notes")

        val durationMs = parts.maxOf { part -> part.notes.maxOf { it.startMs + it.durationMs } }
        return MidiScore(durationMs = durationMs, timing = timing, parts = parts)
    }

    private fun noteOff(
        track: RawTrack,
        open: HashMap<Long, ArrayDeque<RawNote>>,
        channel: Int,
        pitch: Int,
        tick: Long,
    ) {
        val key = (channel shl 8 or pitch).toLong()
        val note = open[key]?.removeFirstOrNull() ?: return
        note.offTick = tick
        track.notes += note
    }

    private fun closeOpenNotes(
        track: RawTrack,
        open: HashMap<Long, ArrayDeque<RawNote>>,
        tick: Long,
    ) {
        for ((_, stack) in open) {
            while (true) {
                val note = stack.removeFirstOrNull() ?: break
                note.offTick = tick
                track.notes += note
            }
        }
        open.clear()
    }

    /** Converts raw ticks to finished, time-sorted [MidiNote]s; zero-length notes get 1 ms. */
    private fun finishNotes(
        notes: List<RawNote>,
        tickToMs: (Long) -> Double,
    ): List<MidiNote> = notes
        .map { n ->
            val startMs = tickToMs(n.onTick)
            val endMs = tickToMs(n.offTick).coerceAtLeast(startMs + 1.0)
            MidiNote(
                startMs = startMs.toInt(),
                durationMs = (endMs - startMs).toInt(),
                pitch = n.pitch,
                velocity = n.velocity,
            )
        }
        .sortedWith(compareBy({ it.startMs }, { it.pitch }))
}
