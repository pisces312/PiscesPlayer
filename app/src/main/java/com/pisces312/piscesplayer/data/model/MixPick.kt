package com.pisces312.piscesplayer.data.model

/**
 * Which half of the daily mix a pick came from.
 *
 * The home page carries a single local-recommendation feed ("每日推荐") that blends
 * these two buckets; the full list screen groups them back under their own headers.
 */
enum class MixKind(val storageToken: String) {
    /** Familiar picks: high-affinity favourites driven by listening history. */
    FAMILIAR("familiar"),

    /** Discovery picks: low-affinity, recently added or rarely played tracks. */
    DISCOVERY("discovery");

    companion object {
        /**
         * Reads a persisted bucket token. Unknown or missing tokens fall back to
         * [FAMILIAR]: ids persisted before the merge were the familiar-songs mix.
         */
        fun fromStorageToken(token: String?): MixKind =
            entries.firstOrNull { it.storageToken.equals(token, ignoreCase = true) } ?: FAMILIAR
    }
}

/** A generated daily-mix entry: the song plus the bucket it was picked for. */
data class MixPick(
    val song: Song,
    val kind: MixKind
)
