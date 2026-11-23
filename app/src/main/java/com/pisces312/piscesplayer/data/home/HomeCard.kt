package com.pisces312.piscesplayer.data.home

import kotlinx.serialization.Serializable

/**
 * Home card placement spans, see home-cards-grid-plan.md §3.1.
 *
 * The grid is 2 columns wide; a card occupies [HomeCardSize.spanX] columns and
 * [HomeCardSize.spanY] rows of unit cells (the long rectangular grid units).
 */
enum class HomeCardSize(val spanX: Int, val spanY: Int) {
    /** 1×1 — single unit cell, a compact entry tile. */
    S_1x1(1, 1),

    /** 2×1 — a full-row banner. */
    W_2x1(2, 1),

    /** 1×2 — a tall tile for a single emphasized entry. */
    T_1x2(1, 2),

    /** 2×2 — a large tile for a hero entry (e.g. 入梦). */
    L_2x2(2, 2),
}

/** The set of home card types. */
enum class HomeCardType {
    AI_PLAYLIST,
    DAILY_MIX,
    GAME_RHYTHM,
    GAME_QUIZ,
    SOUNDSCAPE,
    LEARN_CLASSICAL,
    LEARN_MIDI,
    LEARN_EAR,
    LEARN_METRONOME,

    /** 流媒体源入口：弹出 Navidrome / Jellyfin 选择（原顶栏云按钮卡片化）。 */
    STREAMING,

    /** 应用设置入口（可选卡片）。 */
    SETTINGS,

    /** A shortcut to a specific user playlist; [HomeCard.targetId] = playlist id. */
    PLAYLIST,
}

/**
 * One home grid card. Persisted as a single JSON list in DataStore (see
 * [HomeLayoutRepository]); the mutable placement geometry is *derived* from
 * the card list order by the first-fit dense placement algorithm
 * ([placeHomeCards]), so an edit is always just a list mutation.
 *
 * @param uid stable identity used as the compose key and drag animation anchor.
 * @param type which entry this card represents.
 * @param targetId only for [HomeCardType.PLAYLIST]; the playlist id.
 */
@Serializable
data class HomeCard(
    val uid: String,
    val type: HomeCardType,
    val targetId: String? = null,
    val size: HomeCardSize,
)

/** The default home layout used on first launch and by「恢复默认」(plan §9, v1.3). */
val DefaultHomeLayout: List<HomeCard> = listOf(
    HomeCard(uid = "default_ai_playlist", type = HomeCardType.AI_PLAYLIST, size = HomeCardSize.L_2x2),
    HomeCard(uid = "default_daily_mix", type = HomeCardType.DAILY_MIX, size = HomeCardSize.L_2x2),
    HomeCard(uid = "default_game_rhythm", type = HomeCardType.GAME_RHYTHM, size = HomeCardSize.S_1x1),
    HomeCard(uid = "default_game_quiz", type = HomeCardType.GAME_QUIZ, size = HomeCardSize.S_1x1),
    HomeCard(uid = "default_soundscape", type = HomeCardType.SOUNDSCAPE, size = HomeCardSize.S_1x1),
)
