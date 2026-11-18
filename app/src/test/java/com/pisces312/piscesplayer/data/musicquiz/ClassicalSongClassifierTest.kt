package com.pisces312.piscesplayer.data.musicquiz

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ClassicalSongClassifierTest {

    @Test
    fun `catalog titles match in Chinese and English with decorations stripped`() {
        assertTrue(classical(title = "致爱丽丝"))
        assertTrue(classical(title = "献给爱丽丝（钢琴独奏）"))
        assertTrue(classical(title = "Für Elise"))
        assertTrue(classical(title = "Moonlight Sonata (Piano Solo)"))
        assertTrue(classical(title = "D大调卡农"))
        assertTrue(classical(title = "梁祝"))
        assertTrue(classical(title = "二泉映月"))
    }

    @Test
    fun `composer and performer names match metadata`() {
        assertTrue(classical(title = "Goldberg Variations", artist = "Johann Sebastian Bach"))
        assertTrue(classical(title = "Nocturnes", artist = "Frédéric Chopin"))
        assertTrue(classical(title = "钢琴曲精选", album = "理查德·克莱德曼"))
        assertTrue(classical(title = "Summer", artist = "久石让"))
        assertTrue(classical(title = "River Flows in You", artist = "Yiruma"))
    }

    @Test
    fun `latin names match on word boundaries only`() {
        assertFalse(classical(title = "Bachelor Party", artist = "Some Pop Band"))
        assertFalse(classical(title = "Handle With Care", artist = "Traveling Wilburys"))
        assertTrue(classical(title = "Brandenburg Concerto No. 3", artist = "Bach"))
    }

    @Test
    fun `title tokens and genre labels classify`() {
        assertTrue(classical(title = "E小调小提琴协奏曲"))
        assertTrue(classical(title = "Symphony No. 5", artist = "Unknown"))
        assertTrue(classical(title = "A Random Song", genre = "Classical"))
        assertTrue(classical(title = "A Random Song", genre = "古典"))
        assertTrue(classical(title = "A Random Song", album = "100 Classical Hits"))
    }

    @Test
    fun `pop counterexamples stay non-classical`() {
        assertFalse(classical(title = "夜曲", artist = "周杰伦"))
        assertFalse(classical(title = "四季予你", artist = "程响"))
        assertFalse(classical(title = "月光", artist = "胡彦斌"))
        assertFalse(classical(title = "童年", artist = "罗大佑"))
        assertFalse(classical(title = "听妈妈的话", artist = "周杰伦"))
        assertFalse(classical(title = "My Heart Will Go On", artist = "Celine Dion"))
    }

    @Test
    fun `ambiguous bare piece names do not classify on their own`() {
        assertFalse(classical(title = "四季", artist = "某流行歌手"))
        assertFalse(classical(title = "摇篮曲", artist = "某流行歌手"))
        assertTrue(classical(title = "勃拉姆斯摇篮曲"))
    }

    private fun classical(
        title: String,
        artist: String = "",
        album: String = "",
        genre: String? = null,
    ): Boolean = ClassicalSongClassifier.isClassical(
        title = title,
        artist = artist,
        album = album,
        genre = genre,
    )
}
