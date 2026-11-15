/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Original work in 2026, sound_postcard feature
 * (RhythmChartLab docs/design/sound-postcard-journey-design.md §5.2, §6.2, §8.9.2).
 */
package com.pisces312.piscesplayer.data.postcard.model

/**
 * Versioned content configuration: the six regions, their spots, fallback palettes and the
 * message template pool (design §10.2). Pure Kotlin, no Android dependency, so the whole
 * content layer is exercised in JVM tests.
 *
 * M0 ships 2 spots per region (12 total, design §12.1). The spot id format
 * (`<regionId>_<nn>`, nn = 01..12) and the unique keys already support the full 72 spots, so
 * extending to 12 per region in M1 is a data change, never a schema or algorithm migration.
 */
object PostcardContent {

    /** Fixed region order — tie-break order for region scoring and map display (design §9.5 spirit). */
    val REGIONS: List<PostcardRegionConfig> = listOf(
        region(
            id = "east_gorge",
            nameZh = "东风峡",
            genreKeys = emptySet(), // terrain-driven: bright & light highs
            fallbackPaletteArgb = listOf(0xFF7EC8E3.toInt(), 0xFF2E86AB.toInt(), 0xFFF4E4BC.toInt(), 0xFFE8F4F8.toInt()),
            spots = listOf(
                spot(1, "灯塔礁", "mid_east_gorge_01", "near_east_gorge_01"),
                spot(2, "贝壳滩", "mid_east_gorge_02", "near_east_gorge_02"),
            ),
        ),
        region(
            id = "white_field",
            nameZh = "白夜原",
            genreKeys = emptySet(), // terrain-driven: cold highs
            fallbackPaletteArgb = listOf(0xFFF5F9FC.toInt(), 0xFFC9DDF0.toInt(), 0xFF6D8FA6.toInt(), 0xFF33475B.toInt()),
            spots = listOf(
                spot(1, "极光坡", "mid_white_field_01", "near_white_field_01"),
                spot(2, "冻湖畔", "mid_white_field_02", "near_white_field_02"),
            ),
        ),
        region(
            id = "mist_valley",
            nameZh = "雾之谷",
            genreKeys = emptySet(), // terrain-driven: heavy lows, slow
            fallbackPaletteArgb = listOf(0xFFB8C4B1.toInt(), 0xFF6E8B74.toInt(), 0xFF42504A.toInt(), 0xFFD9E0D6.toInt()),
            spots = listOf(
                spot(1, "松雾径", "mid_mist_valley_01", "near_mist_valley_01"),
                spot(2, "空谷桥", "mid_mist_valley_02", "near_mist_valley_02"),
            ),
        ),
        region(
            id = "old_town",
            nameZh = "旧城厢",
            genreKeys = setOf("folk", "classical", "jazz"),
            fallbackPaletteArgb = listOf(0xFFD9BFA5.toInt(), 0xFFA9805B.toInt(), 0xFF6B4F35.toInt(), 0xFFF2E7D8.toInt()),
            spots = listOf(
                spot(1, "钟塔巷", "mid_old_town_01", "near_old_town_01"),
                spot(2, "石板市集", "mid_old_town_02", "near_old_town_02"),
            ),
        ),
        region(
            id = "lamp_harbor",
            nameZh = "长灯港",
            genreKeys = setOf("electronic", "city_pop"),
            fallbackPaletteArgb = listOf(0xFF223354.toInt(), 0xFF5B6C9E.toInt(), 0xFFF2A65A.toInt(), 0xFFE8D5B5.toInt()),
            spots = listOf(
                spot(1, "霓虹栈桥", "mid_lamp_harbor_01", "near_lamp_harbor_01"),
                spot(2, "集装箱码头", "mid_lamp_harbor_02", "near_lamp_harbor_02"),
            ),
        ),
        region(
            id = "wild_road",
            nameZh = "荒原道",
            genreKeys = setOf("rock", "metal"),
            fallbackPaletteArgb = listOf(0xFFC7A37A.toInt(), 0xFF9C5F3E.toInt(), 0xFF6E4A31.toInt(), 0xFFE3D3BD.toInt()),
            spots = listOf(
                spot(1, "风蚀岩", "mid_wild_road_01", "near_wild_road_01"),
                spot(2, "废弃公路", "mid_wild_road_02", "near_wild_road_02"),
            ),
        ),
    )

    val REGION_BY_ID: Map<String, PostcardRegionConfig> = REGIONS.associateBy { it.id }

    /**
     * Route length by song duration (design §5.2): `<120s` 1 card, `120..239s` 2, `>=240s` 3.
     * Boundaries: exactly 120000/240000 ms land in the upper bucket.
     */
    fun routeLengthFor(durationMs: Long): Int = when {
        durationMs < 120_000L -> 1
        durationMs < 240_000L -> 2
        else -> 3
    }

    // ---------------------------------------------------------------- message templates

    /**
     * Template pool (design §6.2). M0 ships one line per region x weather (18) plus 2 generic;
     * M1 grows this toward 3 per group + 6 generic. Selection is deterministic via
     * `variantSeed + templateId` — the args only do safe formatting, never HTML concatenation.
     *
     * Text is original; the pool intentionally avoids the vocabulary of any existing game.
     */
    val MESSAGE_TEMPLATES: List<PostcardMessageTemplate> = listOf(
        // east_gorge
        template("msg_east_gorge_sunny", "east_gorge", PostcardWeather.SUNNY, "走了 {distance} 公里。海风把浪推到脚边，礁石上的光一闪一闪。"),
        template("msg_east_gorge_rainy", "east_gorge", PostcardWeather.RAINY, "走了 {distance} 公里。雨点敲着礁石，潮声比平时更近。"),
        template("msg_east_gorge_snowy", "east_gorge", PostcardWeather.SNOWY, "走了 {distance} 公里。海岸落了雪，浪还是暖的。"),
        // white_field
        template("msg_white_field_sunny", "white_field", PostcardWeather.SUNNY, "走了 {distance} 公里。雪原亮得晃眼，远处有极光在慢慢卷。"),
        template("msg_white_field_rainy", "white_field", PostcardWeather.RAINY, "走了 {distance} 公里。冻雨敲在湖面上，天地都是灰蓝色。"),
        template("msg_white_field_snowy", "white_field", PostcardWeather.SNOWY, "走了 {distance} 公里。雪停了很久，脚印是原野上唯一的记号。"),
        // mist_valley
        template("msg_mist_valley_sunny", "mist_valley", PostcardWeather.SUNNY, "走了 {distance} 公里。雾散到半山，松针上的水珠亮晶晶。"),
        template("msg_mist_valley_rainy", "mist_valley", PostcardWeather.RAINY, "走了 {distance} 公里。雾散得慢，风里有苔藓和湿石头的味道。"),
        template("msg_mist_valley_snowy", "mist_valley", PostcardWeather.SNOWY, "走了 {distance} 公里。山谷静得出奇，落雪盖住了回声。"),
        // old_town
        template("msg_old_town_sunny", "old_town", PostcardWeather.SUNNY, "走了 {distance} 公里。钟塔的影子刚好铺满巷口。"),
        template("msg_old_town_rainy", "old_town", PostcardWeather.RAINY, "走了 {distance} 公里。石板路把雨声收进巷子里，檐下躲了一会。"),
        template("msg_old_town_snowy", "old_town", PostcardWeather.SNOWY, "走了 {distance} 公里。屋顶全白了，面包房的窗还亮着。"),
        // lamp_harbor
        template("msg_lamp_harbor_sunny", "lamp_harbor", PostcardWeather.SUNNY, "走了 {distance} 公里。桅杆林立，海鸥在集装箱上吵架。"),
        template("msg_lamp_harbor_rainy", "lamp_harbor", PostcardWeather.RAINY, "走了 {distance} 公里。栈桥的灯在雨里晕成一圈圈橙色。"),
        template("msg_lamp_harbor_snowy", "lamp_harbor", PostcardWeather.SNOWY, "走了 {distance} 公里。雪落在龙门吊上，港口第一次这么安静。"),
        // wild_road
        template("msg_wild_road_sunny", "wild_road", PostcardWeather.SUNNY, "走了 {distance} 公里。风把云推得很快，岩层的影子一路跟着。"),
        template("msg_wild_road_rainy", "wild_road", PostcardWeather.RAINY, "走了 {distance} 公里。锈铁皮被雨打响，荒原深处有雷。"),
        template("msg_wild_road_snowy", "wild_road", PostcardWeather.SNOWY, "走了 {distance} 公里。废弃公路埋进雪里，只有里程碑还露着头。"),
        // generic
        template("msg_generic_0", null, null, "走了 {distance} 公里。路上没什么事，挺好的。"),
        template("msg_generic_1", null, null, "走了 {distance} 公里。鞋底沾了点远方的土。"),
    )

    private fun region(
        id: String,
        nameZh: String,
        genreKeys: Set<String>,
        fallbackPaletteArgb: List<Int>,
        spots: List<PostcardSpotConfig>,
    ) = PostcardRegionConfig(
        id = id,
        nameZh = nameZh,
        genreKeys = genreKeys,
        fallbackPaletteArgb = fallbackPaletteArgb,
        // `spot()` builds the bare ordinal; the region prefix is attached exactly once here.
        spots = spots.map { it.copy(id = "${id}_${it.id}") },
    )

    private fun spot(number: Int, nameZh: String, midComponentId: String, nearComponentId: String) =
        PostcardSpotConfig(
            id = "%02d".format(number),
            nameZh = nameZh,
            midComponentId = midComponentId,
            nearComponentId = nearComponentId,
        )
}

/** One fictional region of the postal map (design §8.9.2) — 6 total, never real geography. */
data class PostcardRegionConfig(
    val id: String,
    val nameZh: String,
    /** Genre keys whose genrePrior targets this region (0.65 vs 0.05, design §5.2). */
    val genreKeys: Set<String>,
    val fallbackPaletteArgb: List<Int>,
    val spots: List<PostcardSpotConfig>,
) {
    val farComponentId: String get() = "far_$id"

    fun spotById(spotId: String): PostcardSpotConfig? = spots.firstOrNull { it.id == spotId }
}

/**
 * One photo spot. [id] embeds the zero-padded ordinal within the region (`old_town_01`);
 * ordinals are stable for M0's 2 spots and the full 12 of M1+.
 */
data class PostcardSpotConfig(
    val id: String,
    val nameZh: String,
    val midComponentId: String,
    val nearComponentId: String,
)

/**
 * One message template line. [regionId] and [weather] null together means the generic pool.
 */
data class PostcardMessageTemplate(
    val id: String,
    val regionId: String?,
    val weather: PostcardWeather?,
    val text: String,
)

private fun template(id: String, regionId: String?, weather: PostcardWeather?, text: String) =
    PostcardMessageTemplate(id, regionId, weather, text)
