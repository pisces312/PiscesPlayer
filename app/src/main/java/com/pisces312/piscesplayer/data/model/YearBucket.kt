// SPDX-License-Identifier: GPL-3.0-or-later
// Original work by pisces312.

package com.pisces312.piscesplayer.data.model

import androidx.compose.runtime.Immutable

/**
 * Year bucket for the Years smart category.
 *
 * Computed on the fly by aggregating the `songs` table on `year`; nothing is written to
 * playlists / playlist_songs:
 * - [year] > 0: bucket for that release year;
 * - [year] == 0: the "unknown year" bucket for tracks with no year tag, always shown last.
 */
@Immutable
data class YearBucket(
    val year: Int,
    val songCount: Int
) {
    val isUnknown: Boolean get() = year <= 0

    companion object {
        const val UNKNOWN_YEAR: Int = 0
    }
}
