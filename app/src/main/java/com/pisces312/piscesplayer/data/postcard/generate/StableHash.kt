/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Original work in 2026, sound_postcard feature
 * (RhythmChartLab docs/design/sound-postcard-journey-design.md §0.3, §10.2).
 */
package com.pisces312.piscesplayer.data.postcard.generate

import java.security.MessageDigest

/**
 * Deterministic hashing for the sound_postcard pipeline.
 *
 * Every seed that can influence a persisted postcard spec goes through SHA-256 with an explicit
 * part layout — never `String.hashCode()`, never iteration order, never the current time. The
 * resulting values must be byte-stable across processes, upgrades and devices.
 *
 * Part layout: a seed is `SHA-256(parts joined by U+0000)`. `deriveSeed` prepends the parent
 * seed's bytes so children stay bound to it; `deriveLong` re-hashes with its domain parts and
 * reads the first 8 bytes big-endian (sign bit cleared, so the value is always >= 0).
 */
object StableHash {

    private const val PART_SEPARATOR = 0.toChar().toString()

    /** SHA-256 of [data]; fixed UTF-8 everywhere else in this object. */
    fun sha256(data: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(data)

    fun sha256(text: String): ByteArray = sha256(text.toByteArray(Charsets.UTF_8))

    fun hex(bytes: ByteArray): String = buildString(bytes.size * 2) {
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            append(HEX[v ushr 4])
            append(HEX[v and 0x0F])
        }
    }

    /**
     * Base seed of a song's travel identity (design §0.3):
     * `SHA-256("sound_postcard\0" + generatorVersion + "\0" + stableSongKey)`.
     *
     * Only region, route and base composition are derived from it; card faces additionally mix
     * spot/condition/renderer inputs (see [deriveSeed]).
     */
    fun songSeed(generatorVersion: Int, stableSongKey: String): ByteArray =
        sha256(joinParts("sound_postcard", generatorVersion.toString(), stableSongKey))

    /**
     * Child seed: `SHA-256(parent \0 part1 \0 part2 ...)`. Used for weatherSeed and variantSeed
     * so a derived value can never be confused with a shorter/longer concatenation of its parts.
     */
    fun deriveSeed(parent: ByteArray, vararg parts: String): ByteArray =
        sha256(parent + joinParts(*parts).toByteArray(Charsets.UTF_8))

    /**
     * Deterministic non-negative long from [seed] mixed with [parts]:
     * first 8 bytes of `SHA-256(seed \0 parts)`, big-endian, sign bit cleared.
     */
    fun deriveLong(seed: ByteArray, vararg parts: String): Long {
        val digest = deriveSeed(seed, *parts)
        var value = 0L
        for (i in 0 until 8) {
            value = (value shl 8) or (digest[i].toLong() and 0xFF)
        }
        return value and Long.MAX_VALUE
    }

    /** [deriveLong] reduced to `0 until [mod]` — the only sanctioned "randomness". */
    fun deriveLongMod(seed: ByteArray, mod: Long, vararg parts: String): Long =
        deriveLong(seed, *parts) % mod

    private fun joinParts(vararg parts: String): String = parts.joinToString(PART_SEPARATOR)

    private val HEX = "0123456789abcdef".toCharArray()
}
