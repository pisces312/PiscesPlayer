package com.pisces312.piscesplayer.data.musicquiz

/**
 * Tier B keyword tables for kids-song classification. The lists are deliberately narrow:
 * every weak word must survive the counter-example tests in KidsSongClassifierTest
 * (e.g. 《童年》《听妈妈的话》《宝贝对不起》 must NOT qualify).
 */
internal object KidsSongKeywords {

    /** Title words that almost only appear in children's songs. */
    val strongTitleWords: List<String> = listOf(
        "儿歌", "童谣", "摇篮曲", "幼儿园", "早教", "胎教", "少儿", "儿童", "童声",
        "宝宝", "贝贝", "小朋友",
        "nursery", "lullaby", "toddler", "preschool", "sing-along", "sing along",
        "for kids", "for children", "kids song", "kids songs",
        "mother goose", "sesame street", "cocomelon", "pinkfong", "super simple",
    )

    /** Artist/album words that identify a kids-content publisher. */
    val strongArtistWords: List<String> = listOf(
        "贝瓦", "宝宝巴士", "巴士儿歌", "萌宝", "儿歌", "童谣", "少儿", "儿童",
        "nursery", "cocomelon", "pinkfong", "super simple", "mother goose",
        "sesame street", "kids", "children",
    )

    /** Single words that are suggestive but never sufficient alone. */
    val weakTitleWords: List<String> = listOf(
        // animals
        "小猫", "小狗", "小兔", "小熊", "小鸭", "小鸡", "青蛙", "蝴蝶", "蜗牛", "小鸟",
        "duckling", "bunny", "teddy",
        // nature
        "太阳", "月亮", "星星", "彩虹", "春天", "雪花", "小雨", "花朵", "twinkle",
        // family
        "妈妈", "爸爸", "爷爷", "奶奶", "娃娃", "baby", "bedtime",
        // play
        "捉迷藏", "丢手绢", "滑滑梯", "跷跷板", "peek-a-boo",
        // numbers / letters
        "数字歌", "字母歌", "abc", "alphabet", "five little",
    )

    /** Words scanned in lyrics; strong words need only one lyric hit to support a weak title. */
    val lyricWords: List<String> = strongTitleWords + weakTitleWords
}
