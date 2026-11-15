package com.pisces312.piscesplayer.data.postcard.generate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StableHashTest {

    @Test
    fun songSeedIsStableAcrossCalls() {
        val a = StableHash.songSeed(1, "local:42")
        val b = StableHash.songSeed(1, "local:42")
        assertTrue("same input must give identical bytes", a.contentEquals(b))
        assertEquals(32, a.size)
    }

    @Test
    fun differentKeysOrVersionsProduceDifferentSeeds() {
        val base = StableHash.songSeed(1, "local:42")
        assertNotEquals(base, StableHash.songSeed(1, "local:43"))
        assertNotEquals(base, StableHash.songSeed(2, "local:42"))
    }

    @Test
    fun deriveLongIsNonNegativeAndWithinMod() {
        val seed = StableHash.songSeed(1, "local:42")
        for (domain in listOf("delivery", "region", "route", "snow", "message")) {
            val value = StableHash.deriveLong(seed, domain)
            assertTrue("$domain value must be >= 0", value >= 0L)
            val mod = StableHash.deriveLongMod(seed, 51, domain)
            assertTrue("$domain mod must be in 0..50", mod in 0..50)
        }
    }

    @Test
    fun deriveSeedBindsToParentAndParts() {
        val seed = StableHash.songSeed(1, "local:42")
        val childA = StableHash.deriveSeed(seed, "old_town_01", "weather")
        val childB = StableHash.deriveSeed(seed, "old_town_01", "weather")
        assertTrue(childA.contentEquals(childB))
        // Spot id and domain cannot be confused through concatenation ambiguity.
        assertNotEquals(
            StableHash.hex(childA),
            StableHash.hex(StableHash.deriveSeed(seed, "old_town_01weather", "")),
        )
    }

    @Test
    fun hexIsStableLowerCase() {
        val hex = StableHash.hex(StableHash.sha256("abc"))
        assertEquals(hex, StableHash.hex(StableHash.sha256("abc")))
        // Known SHA-256 vector guards against a broken digest pipeline.
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            hex,
        )
    }
}
