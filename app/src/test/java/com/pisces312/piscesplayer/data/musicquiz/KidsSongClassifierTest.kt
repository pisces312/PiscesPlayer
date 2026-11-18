package com.pisces312.piscesplayer.data.musicquiz

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class KidsSongClassifierTest {

    @Test
    fun `catalog matches Chinese and English aliases with decorations stripped`() {
        assertTrue(KidsSongClassifier.matchesCatalog("小星星（儿歌版）"))
        assertTrue(KidsSongClassifier.matchesCatalog("Twinkle Twinkle Little Star"))
        assertTrue(KidsSongClassifier.matchesCatalog("两只老虎 (Live)"))
        assertTrue(KidsSongClassifier.matchesCatalog("Baby Shark Dance"))
        assertTrue(KidsSongClassifier.matchesCatalog("黑猫警长"))
        assertTrue(KidsSongClassifier.matchesCatalog("你笑起来真好看"))
    }

    @Test
    fun `pop counterexamples never qualify`() {
        assertFalse(keywords("童年", "罗大佑"))
        assertFalse(keywords("听妈妈的话", "周杰伦"))
        assertFalse(keywords("宝贝对不起", "草蜢"))
        assertFalse(keywords("Baby", "Justin Bieber"))
        assertFalse(keywords("星星点灯", "郑智化"))
        assertFalse(keywords("娃娃脸", "后弦"))
    }

    @Test
    fun `strong title word qualifies immediately`() {
        assertTrue(keywords("摇篮曲", "某歌手"))
        assertTrue(keywords("幼儿园歌曲串烧", "某歌手"))
        assertTrue(keywords("Nursery Rhymes Collection", "Some Artist"))
    }

    @Test
    fun `publisher words in artist or album qualify`() {
        assertTrue(keywords("随便什么歌", "宝宝巴士"))
        assertTrue(keywords("随便什么歌", "Cocomelon"))
        assertTrue(keywords("随便什么歌", "某歌手", album = "贝瓦儿歌精选"))
    }

    @Test
    fun `two weak title words qualify`() {
        assertTrue(keywords("小猫和小狗", "某歌手"))
    }

    @Test
    fun `one weak title word needs lyric support`() {
        assertFalse(keywords("小熊的歌", "某歌手"))
        assertTrue(keywords("小熊的歌", "某歌手", lyrics = "小熊 小熊 妈妈 妈妈"))
    }

    @Test
    fun `lyrics alone need three hits including a strong word`() {
        assertTrue(keywords("无名", "某歌手", lyrics = "宝宝 妈妈 星星"))
        assertFalse(keywords("无名", "某歌手", lyrics = "妈妈 爸爸 星星"))
    }

    private fun keywords(
        title: String,
        artist: String = "",
        album: String = "",
        lyrics: String? = null,
    ): Boolean = KidsSongClassifier.matchesKeywords(
        title = title,
        artist = artist,
        album = album,
        lyrics = lyrics,
    )
}
