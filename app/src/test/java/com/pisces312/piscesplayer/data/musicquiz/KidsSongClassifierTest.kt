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
    fun `catalog matches the user library kids playlist titles`() {
        assertTrue(KidsSongClassifier.matchesCatalog("春之歌"))
        assertTrue(KidsSongClassifier.matchesCatalog("阳光男孩 阳光女孩"))
        assertTrue(KidsSongClassifier.matchesCatalog("花园种花"))
        assertTrue(KidsSongClassifier.matchesCatalog("星仔走天涯（咪咪流浪记）"))
        assertTrue(KidsSongClassifier.matchesCatalog("我是挖掘机"))
        assertTrue(KidsSongClassifier.matchesCatalog("我不上你的当"))
        assertTrue(KidsSongClassifier.matchesCatalog("延吉之歌"))
        assertTrue(KidsSongClassifier.matchesCatalog("小青蛙"))
        assertTrue(KidsSongClassifier.matchesCatalog("加油鸭"))
        assertTrue(KidsSongClassifier.matchesCatalog("萤火虫"))
        assertTrue(KidsSongClassifier.matchesCatalog("爸爸去哪儿"))
        assertTrue(KidsSongClassifier.matchesCatalog("洗澡歌"))
        assertTrue(KidsSongClassifier.matchesCatalog("找爸爸"))
        assertTrue(KidsSongClassifier.matchesCatalog("小白船 [朝鲜族民歌]"))
        assertTrue(KidsSongClassifier.matchesCatalog("妈妈我爱你"))
        assertTrue(KidsSongClassifier.matchesCatalog("最棒的老爸"))
        assertTrue(KidsSongClassifier.matchesCatalog("大家一起喜羊羊（国语）"))
        assertTrue(KidsSongClassifier.matchesCatalog("Red River Valley"))
        assertTrue(KidsSongClassifier.matchesCatalog("Dinosaur Stomp"))
        assertTrue(KidsSongClassifier.matchesCatalog("coco ball"))
        assertTrue(KidsSongClassifier.matchesCatalog("Zunea-Zunea"))
        assertTrue(KidsSongClassifier.matchesCatalog("The Alphabet Song"))
        assertTrue(KidsSongClassifier.matchesCatalog("Schnappi"))
        assertTrue(KidsSongClassifier.matchesCatalog("I've Been Working On The Railroad"))
    }

    @Test
    fun `punctuation differences never break catalog matching`() {
        assertTrue(KidsSongClassifier.matchesCatalog("Row, Row, Row Your Boat"))
        assertTrue(KidsSongClassifier.matchesCatalog("If You’re Happy and You Know It"))
        assertTrue(KidsSongClassifier.matchesCatalog("She'll Be Coming Round the Mountain"))
        assertTrue(KidsSongClassifier.matchesCatalog("Rock A Bye Baby"))
        assertTrue(KidsSongClassifier.matchesCatalog("You Are My Sunshine"))
    }

    @Test
    fun `performer label rule qualifies kids labels always`() {
        assertTrue(KidsSongClassifier.matchesArtistLabel("贝瓦儿歌"))
        assertTrue(KidsSongClassifier.matchesArtistLabel("贝乐虎儿歌"))
        assertTrue(KidsSongClassifier.matchesArtistLabel("贝瓦"))
        assertTrue(KidsSongClassifier.matchesArtistLabel("宝宝巴士儿歌"))
        assertTrue(KidsSongClassifier.matchesArtistLabel("北京快乐儿歌合唱团"))
    }

    @Test
    fun `performer label rule rejects ordinary artists`() {
        assertFalse(KidsSongClassifier.matchesArtistLabel("周杰伦"))
        assertFalse(KidsSongClassifier.matchesArtistLabel("筷子兄弟"))
        assertFalse(KidsSongClassifier.matchesArtistLabel("Children of Bodom"))
        assertFalse(KidsSongClassifier.matchesArtistLabel(""))
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
