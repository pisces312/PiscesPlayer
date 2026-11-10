package com.pisces312.piscesplayer.data.preferences

/**
 * The car lyric title settings as one value.
 *
 * [UserPreferencesRepository] still exposes the three data-store flows separately, because
 * `CarLyricTitleController` reacts to each of them differently (the toggle restores the real
 * title, the lead simply recomputes, a different cut renumbers the cues). This type is for the
 * consumers that only ask "what are the current settings" — the settings screen and its search
 * index — so that they read one named value instead of three consecutive slots of a positional
 * `combine` array, where inserting an unrelated preference would silently shift them.
 */
data class CarLyricTitleSettings(
    val enabled: Boolean = false,
    val leadMs: Int = UserPreferencesRepository.DEFAULT_CAR_LYRIC_TITLE_LEAD_MS,
    val splitLongLines: Boolean = true
)
