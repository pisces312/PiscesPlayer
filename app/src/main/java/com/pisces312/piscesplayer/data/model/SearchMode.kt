package com.pisces312.piscesplayer.data.model

import androidx.compose.runtime.Immutable

/**
 * Search matching target, orthogonal to [SearchFilterType] (which filters result kinds).
 *
 * - [GENERAL]: match song/album/artist/playlist metadata (the historic behavior).
 * - [LYRICS]: match the cached lyrics content of songs; results are songs only, so the
 *   search screen hides the [SearchFilterType] chips while this mode is active.
 *
 * Only locally cached lyrics are covered (embedded tags / `.lrc` files / LRCLIB fetches
 * already persisted in the `lyrics` table); there is no online lyrics-content search.
 */
@Immutable
enum class SearchMode {
    GENERAL,
    LYRICS
}
