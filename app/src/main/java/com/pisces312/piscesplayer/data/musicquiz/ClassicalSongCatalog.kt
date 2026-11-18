package com.pisces312.piscesplayer.data.musicquiz

/**
 * Built-in classical catalog: piece titles (Chinese + English + aliases), composer/performer
 * names, and title/genre tokens. Pure data, no Android dependency.
 *
 * Short or ambiguous words never appear as bare piece keys ("四季", "月光", "夜曲" would hit
 * pop songs like 《四季予你》《月光》《夜曲》); those works are covered by longer keys and by
 * composer/performer name matching instead.
 */
internal object ClassicalSongCatalog {

    /** Piece titles matched against the song title; each entry lists its common aliases. */
    private val PIECE_TITLES: List<List<String>> = listOf(
        // Beethoven
        listOf("Für Elise", "For Elise", "致爱丽丝", "献给爱丽丝"),
        listOf("Moonlight Sonata", "月光奏鸣曲", "月光曲", "钢琴奏鸣曲月光"),
        listOf("Symphony No. 5", "Symphony No.5", "第五交响曲", "命运交响曲", "贝多芬第五交响曲"),
        listOf("Symphony No. 9", "Symphony No.9", "第九交响曲", "欢乐颂", "贝多芬第九交响曲", "Ode to Joy"),
        listOf("Emperor Concerto", "皇帝协奏曲", "第五钢琴协奏曲"),
        listOf("Pathétique Sonata", "悲怆奏鸣曲", "悲怆"),
        listOf("Für Elise Bagatelle", "贝多芬小品"),
        // Bach / baroque
        listOf("Canon in D", "Canon in D major", "卡农", "D大调卡农", "帕赫贝尔卡农", "Pachelbel Canon"),
        listOf("Air on the G String", "G弦上的咏叹调", "G 弦上的咏叹调"),
        listOf("Brandenburg Concertos", "勃兰登堡协奏曲"),
        listOf("Goldberg Variations", "哥德堡变奏曲"),
        listOf("Toccata and Fugue", "托卡塔与赋格", "d小调托卡塔"),
        listOf("Jesu, Joy of Man's Desiring", "耶稣，世人期盼的喜悦"),
        listOf("Badinerie", "嬉戏曲"),
        // Mozart
        listOf("Turkish March", "Rondo alla Turca", "Alla Turca", "土耳其进行曲"),
        listOf("Eine kleine Nachtmusik", "G大调弦乐小夜曲", "Eine Kleine Nachtmusik"),
        listOf("Requiem", "安魂曲", "莫扎特安魂曲"),
        listOf("Piano Sonata No. 11", "第十一钢琴奏鸣曲"),
        // Chopin
        listOf("Nocturne Op. 9", "Op. 9 No. 2", "降e大调夜曲", "肖邦夜曲", "夜曲 op9"),
        listOf("Fantaisie-Impromptu", "幻想即兴曲", "升c小调幻想即兴曲"),
        listOf("Minute Waltz", "小狗圆舞曲", "一分钟圆舞曲"),
        listOf("Revolutionary Etude", "革命练习曲", "c小调革命练习曲"),
        listOf("Ballade No. 1", "第一叙事曲", "g小调叙事曲"),
        listOf("Polonaise in A-flat", "英雄波兰舞曲", "军队波兰舞曲"),
        // Tchaikovsky / ballet
        listOf("Swan Lake", "天鹅湖"),
        listOf("The Nutcracker", "胡桃夹子"),
        listOf("Waltz of the Flowers", "花之圆舞曲"),
        listOf("Dance of the Sugar Plum Fairy", "糖果仙子舞曲", "糖梅仙子舞曲"),
        listOf("Sleeping Beauty Waltz", "睡美人圆舞曲", "睡美人芭蕾组曲"),
        listOf("Romeo and Juliet", "罗密欧与朱丽叶", "罗密欧与朱丽叶幻想序曲"),
        listOf("Piano Concerto No. 1", "第一钢琴协奏曲", "柴可夫斯基第一钢琴协奏曲"),
        // Strauss family
        listOf("The Blue Danube", "蓝色多瑙河", "蓝色多瑙河圆舞曲"),
        listOf("Radetzky March", "拉德茨基进行曲"),
        listOf("An der schönen blauen Donau", "An der schonen blauen Donau"),
        listOf("Tritsch-Tratsch Polka", "闲聊波尔卡"),
        listOf("Pizzicato Polka", "拨弦波尔卡"),
        listOf("Voices of Spring", "春之声圆舞曲"),
        // Vivaldi
        listOf("The Four Seasons", "四季小提琴协奏曲", "维瓦尔第四季", "小提琴协奏曲四季", "Le quattro stagioni"),
        listOf("Spring from The Four Seasons", "四季·春", "维瓦尔第春"),
        // Schubert / Schumann / Mendelssohn / Brahms
        listOf("Military March", "军队进行曲", "舒伯特军队进行曲"),
        listOf("Ave Maria", "圣母颂", "舒伯特圣母颂"),
        listOf("Serenade", "小夜曲", "舒伯特小夜曲", "Standchen"),
        listOf("Traumerei", "Träumerei", "梦幻曲", "童年情景梦幻曲"),
        listOf("Songs without Words", "无词歌", "春之歌"),
        listOf("Violin Concerto in E minor", "e小调小提琴协奏曲", "门德尔松小提琴协奏曲"),
        listOf("Hungarian Dance", "匈牙利舞曲", "第五匈牙利舞曲"),
        listOf("Brahms Lullaby", "勃拉姆斯摇篮曲", "brahms lullaby"),        // Dvořák / Smetana / Grieg / Elgar
        listOf("New World Symphony", "自新世界交响曲", "自新大陆", "第九交响曲自新世界"),
        listOf("Humoresque", "幽默曲", "德沃夏克幽默曲"),
        listOf("The Moldau", "沃尔塔瓦河", "伏尔塔瓦河", "Vltava"),
        listOf("In the Hall of the Mountain King", "山魔王的宫殿", "在山魔王的宫殿里", "Peer Gynt"),
        listOf("Morning Mood", "晨曲", "培尔金特晨曲"),
        listOf("Salut d'Amour", "爱的致意", "爱的礼赞"),
        listOf("Pomp and Circumstance", "威风堂堂进行曲", "威风堂堂"),
        // Liszt / Rachmaninoff / Debussy / Satie / Ravel
        listOf("Liebestraum", "爱之梦", "李斯特爱之梦"),
        listOf("Hungarian Rhapsody", "匈牙利狂想曲", "第二匈牙利狂想曲"),
        listOf("La Campanella", "李斯特钟"),
        listOf("Rachmaninoff Piano Concerto No. 2", "第二钢琴协奏曲", "拉赫玛尼诺夫第二钢琴协奏曲"),
        listOf("Clair de Lune", "月光德彪西", "德彪西月光"),
        listOf("The Girl with the Flaxen Hair", "亚麻色头发的少女"),
        listOf("Prelude to the Afternoon of a Faun", "牧神午后", "牧神午后前奏曲"),
        listOf("Arabesque", "阿拉伯风格曲", "德彪西阿拉伯风格曲"),
        listOf("Gymnopédie", "裸体歌舞", "吉姆诺佩蒂舞曲"),
        listOf("Boléro", "Bolero", "波莱罗", "波莱罗舞曲"),
        // Opera / vocal
        listOf("Carmen Overture", "卡门序曲", "卡门组曲"),
        listOf("Habanera", "哈巴涅拉", "卡门哈巴涅拉"),
        listOf("Toreador Song", "斗牛士之歌"),
        listOf("The Barber of Seville", "塞维利亚的理发师"),
        listOf("La donna è mobile", "La donna e mobile", "女人善变"),
        listOf("Nessun Dorma", "今夜无人入睡"),
        listOf("Turandot", "图兰朵"),
        listOf("La Traviata", "茶花女"),
        listOf("Messiah", "弥赛亚", "哈利路亚合唱", "Hallelujah Chorus"),
        listOf("Ride of the Valkyries", "女武神的骑行", "女武神"),
        listOf("William Tell Overture", "威廉退尔序曲"),
        listOf("1812 Overture", "1812序曲", "1812 Overture"),
        listOf("Carmen Fantasy", "卡门幻想曲"),
        // Russian / French orchestral
        listOf("Flight of the Bumblebee", "野蜂飞舞"),
        listOf("Danse Macabre", "骷髅之舞", "死之舞"),
        listOf("The Carnival of the Animals", "动物狂欢节"),
        listOf("Pictures at an Exhibition", "图画展览会", "展览会上的图画"),
        listOf("Scheherazade", "天方夜谭", "舍赫拉查德"),
        listOf("Finlandia", "芬兰颂"),
        listOf("Also sprach Zarathustra", "查拉图斯特拉如是说"),
        listOf("The Planets", "行星组曲"),
        // Clayderman / crossover piano (popular in local libraries)
        listOf("Ballade pour Adeline", "水边的阿狄丽娜", "爱德琳的童话"),
        listOf("Mariage d'Amour", "梦中的婚礼"),
        listOf("Souvenirs d'Enfance", "童年的回忆"),
        listOf("A Comme Amour", "秋日私语"),
        listOf("Lyphard Melody", "星空（克莱德曼）"),
        // Chinese works
        listOf("Butterfly Lovers", "梁祝", "梁山伯与祝英台", "梁祝小提琴协奏曲"),
        listOf("Erquan Yingyue", "二泉映月"),
        listOf("Spring on Moonlit River", "春江花月夜"),
        listOf("Ambush from Ten Sides", "十面埋伏"),
        listOf("High Mountains and Flowing Water", "高山流水"),
        listOf("Fishing Boats at Dusk", "渔舟唱晚"),
        listOf("Colorful Clouds Chasing the Moon", "彩云追月"),
        listOf("Horse Racing", "赛马", "二胡赛马"),
        listOf("Liang Xiao", "良宵", "刘天华良宵"),
        listOf("Bright Journey", "光明行"),
        listOf("Cowherd's Flute", "牧童短笛"),
        listOf("Yellow River Piano Concerto", "黄河钢琴协奏曲", "黄河协奏曲"),
        listOf("Ode to the Red Flag", "红旗颂"),
        listOf("Yangguan Sandie", "阳关三叠"),
        listOf("Guangling San", "广陵散"),
        listOf("Plum Blossom Melody", "梅花三弄"),
        listOf("River Water", "江河水"),
        listOf("Hundred Birds Worshipping the Phoenix", "百鸟朝凤"),
    )

    /** Composers and performers; matched against title, artist and album. */
    private val ARTIST_NAMES: List<String> = listOf(
        // Western composers
        "Bach", "Mozart", "Beethoven", "Chopin", "Liszt", "Debussy", "Tchaikovsky",
        "Vivaldi", "Schubert", "Brahms", "Haydn", "Mendelssohn", "Dvořák", "Dvorak",
        "Rachmaninoff", "Rachmaninov", "Saint-Saëns", "Saint-Saens", "Grieg", "Bizet",
        "Verdi", "Puccini", "Strauss", "Pachelbel", "Smetana", "Elgar",
        "Sibelius", "Satie", "Rossini", "Wagner", "Handel", "Schumann", "Ravel",
        "Prokofiev", "Czerny", "Clementi", "Offenbach", "Purcell", "Corelli",
        "Boccherini", "Kuhlau", "Albinoni", "Ponchielli", "Dukas", "Massenet",
        "Shostakovich", "Rimsky-Korsakov", "Mussorgsky", "Bartók", "Bartok",
        "Fauré", "Faure",
        "J. S. Bach", "L. v. Beethoven", "P. I. Tchaikovsky",
        // Chinese composers
        "冼星海", "何占豪", "陈钢", "华彦钧", "阿炳", "刘天华", "贺绿汀", "吕其明",
        // Chinese instrumentalists (traditional repertoire recordings)
        "刘德海", "闵惠芬", "王中山", "俞丽拿", "盛中国", "于红梅", "张维良",
        // Performers (pianists explicitly per product request)
        "郎朗", "李云迪", "殷承宗", "傅聪", "薛伟", "吕思清",
        "Lang Lang", "Yundi Li", "Yundi",
        // Crossover piano commonly filed as "piano classics"; kept per product request
        "Richard Clayderman", "理查德·克莱德曼", "克莱德曼",
        "久石让", "Hisai", "Joe Hisaishi", "Yiruma", "李闰珉",
    )

    /** Title tokens (曲式/体裁词) matched against the title. */
    private val TITLE_TOKENS: List<String> = listOf(
        "奏鸣曲", "交响曲", "协奏曲", "序曲", "前奏曲", "赋格", "变奏曲", "圆舞曲",
        "小步舞曲", "进行曲", "组曲", "练习曲", "叙事曲", "即兴曲", "狂想曲",
        "随想曲", "小夜曲", "船歌", "托卡塔", "波兰舞曲", "玛祖卡",
        "sonata", "symphony", "concerto", "overture", "prelude", "fugue",
        "variation", "variations", "waltz", "minuet", "suite", "etude",
        "nocturne", "ballade", "impromptu", "rhapsody", "caprice", "serenade",
        "barcarolle", "toccata", "polonaise", "mazurka",
    )

    /** Genre tokens matched against genre and album. */
    private val GENRE_TOKENS: List<String> = listOf(
        "classical", "古典", "baroque", "巴洛克", "orchestral", "管弦",
        "symphony", "交响", "chamber", "室内乐", "opera", "歌剧", "ballet", "芭蕾",
    )

    val pieceKeys: Set<String> = PIECE_TITLES
        .flatten()
        .map { MusicQuizText.normalizeTitle(it) }
        .filter { it.isNotEmpty() }
        .toSet()

    val artistKeys: List<String> = ARTIST_NAMES
        .map { MusicQuizText.normalizeLoose(it).trim() }
        .filter { it.isNotEmpty() }

    val titleTokenKeys: List<String> = TITLE_TOKENS
        .map { MusicQuizText.normalizeLoose(it).trim() }
        .filter { it.isNotEmpty() }

    val genreTokenKeys: List<String> = GENRE_TOKENS
        .map { MusicQuizText.normalizeLoose(it).trim() }
        .filter { it.isNotEmpty() }
}
