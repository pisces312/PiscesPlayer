package com.pisces312.piscesplayer.data.midilibrary

import java.security.MessageDigest

/**
 * SMF (Standard MIDI File) music fingerprint — L2 of the two-level hashing in the MIDI
 * library plan §2-E: SHA-256 over a **canonical event stream with pure-metadata events
 * stripped**, so files that differ only in names/copyright/text/SysEx (re-saved by a
 * different editor) hash the same.
 *
 * Kept (audible) events: note on/off (with velocity), poly pressure, control change,
 * program change, channel pressure, pitch bend, tempo, time signature, key signature.
 * Dropped (metadata): track name / copyright / text / marker / cue / lyrics / instrument
 * name / sequencer-specific / channel prefix / port / unknown meta / SysEx.
 *
 * Canonicalization: note-on with velocity 0 → note-off; ticks convert to absolute
 * microseconds through the tempo map (tolerating different PPQ divisions); events sort
 * deterministically by (time, kind-rank, channel, data). Original channel numbers are
 * **kept** — re-channelizing a GM file can change audition (channel 10 drums), so
 * channel-shifted variants are deliberately not merged (a false negative is safe).
 */
object MidiFingerprint {

    /** 一份 MIDI 的解析结果：音乐指纹 + 时长（展示用）。 */
    data class Analysis(
        val musicHash: String,
        val durationMs: Long
    )

    /** 解析失败（非 SMF/损坏）返回 null —— 调用方退化为"不判重"。 */
    fun analyze(input: ByteArray): Analysis? = runCatching { parse(input) }.getOrNull()

    fun musicHash(input: ByteArray): String? = analyze(input)?.musicHash

    // ---- canonical event model -------------------------------------------------

    private const val RANK_TEMPO = 0
    private const val RANK_TIMESIG = 1
    private const val RANK_KEYSIG = 2
    private const val RANK_PROGRAM = 3
    private const val RANK_CC = 4
    private const val RANK_PRESSURE = 5
    private const val RANK_BEND = 6
    private const val RANK_NOTEOFF = 7
    private const val RANK_NOTEON = 8

    private class CanonEvent(
        val absUs: Long,
        val rank: Int,
        val channel: Int,
        val status: Int,
        val d1: Int,
        val d2: Int,
        val meta: ByteArray?
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

    private class RawEvent(
        val absTick: Long,
        val rank: Int,
        val channel: Int,
        val status: Int,
        val d1: Int,
        val d2: Int,
        val meta: ByteArray?
    )

    private fun parse(input: ByteArray): Analysis {
        val r = Reader(input)
        check(r.tag(4) == "MThd") { "not an SMF" }
        val hdrLen = r.u32().toInt()
        r.u16() // format
        val ntrks = r.u16()
        val division = r.u16()
        if (hdrLen > 6) r.skip(hdrLen - 6)
        check(ntrks > 0) { "no tracks" }

        val smpte = division and 0x8000 != 0
        val smpteFps: Int
        val smpteTpf: Int
        val ppq: Int
        if (smpte) {
            smpteFps = -(division shr 8).toByte().toInt()
            smpteTpf = division and 0xFF
            ppq = 0
            check(smpteFps > 0 && smpteTpf > 0) { "bad SMPTE division" }
        } else {
            smpteFps = 0
            smpteTpf = 0
            ppq = division
            check(ppq > 0) { "bad division" }
        }

        val raw = ArrayList<RawEvent>()
        val tempos = ArrayList<Pair<Long, Long>>() // absTick -> usPerQuarter

        repeat(ntrks) {
            check(r.tag(4) == "MTrk") { "missing MTrk" }
            val len = r.u32().toInt()
            val end = r.pos + len
            var tick = 0L
            var running = -1
            while (r.pos < end) {
                tick += r.varlen()
                var head = r.u8()
                if (head < 0x80) {
                    // running status: head is data1; rewind one byte for the data read.
                    check(running >= 0x80) { "running status without status" }
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
                            0x51 -> {
                                val us = ((mdata[0].toLong() and 0xFF) shl 16) or
                                    ((mdata[1].toLong() and 0xFF) shl 8) or
                                    (mdata[2].toLong() and 0xFF)
                                tempos.add(tick to us)
                                raw.add(RawEvent(tick, RANK_TEMPO, -1, type, us.toInt(), 0, null))
                            }
                            0x58 -> raw.add(RawEvent(tick, RANK_TIMESIG, -1, type, 0, 0, mdata))
                            0x59 -> raw.add(RawEvent(tick, RANK_KEYSIG, -1, type, 0, 0, mdata))
                            else -> Unit // metadata dropped
                        }
                        if (type == 0x2F) break // end of track
                    }
                    head == 0xF0 || head == 0xF7 -> r.skip(r.varlen().toInt()) // SysEx dropped
                    else -> {
                        val hi = head and 0xF0
                        val ch = head and 0x0F
                        val d1 = r.u8()
                        val d2 = if (hi == 0xC0 || hi == 0xD0) 0 else r.u8()
                        val norm = normalize(hi, ch, d1, d2)
                        raw.add(RawEvent(tick, norm.rank, ch, norm.status, norm.d1, norm.d2, null))
                    }
                }
            }
            r.pos = end
        }
        check(raw.isNotEmpty()) { "no events" }

        // ---- tick → absolute microseconds (tempo map / SMPTE) ---------------------
        tempos.sortBy { it.first }
        var usPerQn = 500_000L
        var tempoIdx = 0
        var segStartTick = 0L
        var segStartUs = 0L
        fun tickToUs(tick: Long): Long {
            if (smpte) return tick * 1_000_000L / (smpteFps.toLong() * smpteTpf)
            while (tempoIdx < tempos.size && tempos[tempoIdx].first <= tick) {
                val (t, us) = tempos[tempoIdx]
                segStartUs += (t - segStartTick) * usPerQn / ppq
                segStartTick = t
                usPerQn = us
                tempoIdx++
            }
            return segStartUs + (tick - segStartTick) * usPerQn / ppq
        }

        val canon = raw.map { e ->
            CanonEvent(
                absUs = tickToUs(e.absTick),
                rank = e.rank,
                channel = e.channel,
                status = e.status,
                d1 = e.d1,
                d2 = e.d2,
                meta = e.meta
            )
        }.sortedWith(
            compareBy({ it.absUs }, { it.rank }, { it.channel }, { it.status }, { it.d1 }, { it.d2 })
        )

        // ---- serialize canonical stream → SHA-256 --------------------------------
        val digest = MessageDigest.getInstance("SHA-256")
        for (e in canon) {
            digest.update(
                byteArrayOf(
                    (e.absUs ushr 56).toByte(), (e.absUs ushr 48).toByte(),
                    (e.absUs ushr 40).toByte(), (e.absUs ushr 32).toByte(),
                    (e.absUs ushr 24).toByte(), (e.absUs ushr 16).toByte(),
                    (e.absUs ushr 8).toByte(), e.absUs.toByte(),
                    e.rank.toByte(), e.channel.toByte(), e.status.toByte(),
                    e.d1.toByte(), e.d2.toByte()
                )
            )
            e.meta?.let {
                digest.update(it.size.toByte())
                digest.update(it)
            }
        }
        val hash = digest.digest().joinToString("") { "%02x".format(it) }
        val endUs = canon.maxOf { it.absUs }
        return Analysis(musicHash = hash, durationMs = (endUs + 999) / 1000)
    }

    private data class Norm(val rank: Int, val status: Int, val d1: Int, val d2: Int)

    /** note-on velocity 0 → note-off 归一；其余保持原值。 */
    private fun normalize(hi: Int, ch: Int, d1: Int, d2: Int): Norm = when (hi) {
        0x90 -> if (d2 == 0) Norm(RANK_NOTEOFF, 0x80 or ch, d1, 0) else Norm(RANK_NOTEON, hi or ch, d1, d2)
        0x80 -> Norm(RANK_NOTEOFF, hi or ch, d1, 0)
        0xA0 -> Norm(RANK_PRESSURE, hi or ch, d1, d2)
        0xB0 -> Norm(RANK_CC, hi or ch, d1, d2)
        0xC0 -> Norm(RANK_PROGRAM, hi or ch, d1, 0)
        0xD0 -> Norm(RANK_PRESSURE, hi or ch, d1, 0)
        0xE0 -> Norm(RANK_BEND, hi or ch, d1, d2)
        else -> error("not a channel event")
    }
}
