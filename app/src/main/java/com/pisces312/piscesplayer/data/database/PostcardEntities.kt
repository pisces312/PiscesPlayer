/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Original work in 2026, sound_postcard feature
 * (RhythmChartLab docs/design/sound-postcard-journey-design.md §10.3).
 */
package com.pisces312.piscesplayer.data.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room layer of sound_postcard, M0 scope (design §10.3, §12.1). Six tables land in v17:
 * profile / attempt / song_profile / journey / card / visit. The inventory, offline ledger and
 * claimed-reward tables belong to M1/M2 and will arrive with their own migration — no empty
 * placeholders are created now.
 *
 * Hard invariants (design §4.2.2, §10.3):
 * - `postcard_journey.attempt_id` is UNIQUE: one natural completion settles exactly once;
 * - `postcard_card` is unique on `(stable_song_key, spot_id, condition_key)`: a repeat visit
 *   updates visit counters, never duplicates the card;
 * - `postcard_visit` is unique on `(card_key, journey_id)`: the same journey cannot count
 *   twice on one card;
 * - cards are never cascade-deleted when a library song disappears — [librarySongId] is a weak
 *   reference nulled on rescan mismatch.
 */

/** Single-row traveller profile (id = 1); offline anchors join in M1 via migration. */
@Entity(tableName = "postcard_profile")
data class PostcardProfileEntity(
    @PrimaryKey val id: Int = 1,
    @ColumnInfo(name = "traveler_name") val travelerName: String,
    @ColumnInfo(name = "world_version") val worldVersion: Int,
    @ColumnInfo(name = "total_distance_km") val totalDistanceKm: Float,
    @ColumnInfo(name = "total_journeys") val totalJourneys: Int,
    @ColumnInfo(name = "created_at_epoch_ms") val createdAtEpochMs: Long,
)

/** Attempt states (design §4.2.2), stored as plain strings for migration friendliness. */
object PostcardAttemptStatus {
    const val TRACKING = "TRACKING"
    const val ELIGIBLE = "ELIGIBLE"
    const val SETTLED = "SETTLED"
    const val CANCELLED = "CANCELLED"
    const val SETTLEMENT_FAILED = "SETTLEMENT_FAILED"
}

/** One listening attempt: created at song start, settled or cancelled at song end. */
@Entity(
    tableName = "postcard_attempt",
    indices = [Index(value = ["status"])],
)
data class PostcardAttemptEntity(
    @PrimaryKey @ColumnInfo(name = "attempt_id") val attemptId: String,
    @ColumnInfo(name = "stable_song_key") val stableSongKey: String,
    @ColumnInfo(name = "library_song_id") val librarySongId: Long?,
    @ColumnInfo(name = "started_at_epoch_ms") val startedAtEpochMs: Long,
    /** Player position when tracking began (manual registration may start mid-song). */
    @ColumnInfo(name = "started_position_ms") val startedPositionMs: Long,
    /** Wall-time accumulated while isPlaying == true, in ms. */
    @ColumnInfo(name = "accumulated_play_ms") val accumulatedPlayMs: Long,
    @ColumnInfo(name = "song_duration_ms") val songDurationMs: Long,
    @ColumnInfo(name = "status") val status: String,
    @ColumnInfo(name = "end_reason") val endReason: String? = null,
)

/**
 * Frozen per-song travel profile (design §5.1): quantized base features, generator version,
 * region and the full ordered route, written on the FIRST valid settlement and reused by every
 * re-listen. A later master regeneration or tag edit never moves a familiar song.
 */
@Entity(tableName = "postcard_song_profile")
data class PostcardSongProfileEntity(
    @PrimaryKey @ColumnInfo(name = "stable_song_key") val stableSongKey: String,
    /** SHA-256 of the content URI — the explicit alias for media-library rescans (M1). */
    @ColumnInfo(name = "uri_hash_alias") val uriHashAlias: String,
    @ColumnInfo(name = "library_song_id") val librarySongId: Long?,
    @ColumnInfo(name = "duration_ms") val durationMs: Long,
    @ColumnInfo(name = "bpm") val bpm: Float?,
    @ColumnInfo(name = "genre_key") val genreKey: String?,
    @ColumnInfo(name = "energy") val energy: Float?,
    @ColumnInfo(name = "low_ratio") val lowRatio: Float?,
    @ColumnInfo(name = "mid_ratio") val midRatio: Float?,
    @ColumnInfo(name = "high_ratio") val highRatio: Float?,
    /** JSON int list of quantized ARGB colors; null until M1 cover extraction. */
    @ColumnInfo(name = "artwork_palette_json") val artworkPaletteJson: String?,
    @ColumnInfo(name = "generator_version") val generatorVersion: Int,
    @ColumnInfo(name = "region_id") val regionId: String,
    /** JSON string list of the ordered route spot ids. */
    @ColumnInfo(name = "route_json") val routeJson: String,
    @ColumnInfo(name = "master_generated_at_ms") val masterGeneratedAtMs: Long?,
    @ColumnInfo(name = "frozen_at_epoch_ms") val frozenAtEpochMs: Long,
)

/** Journey delivery states (design §4.3). */
object PostcardJourneyStatus {
    const val DELIVERED = "DELIVERED"
    const val OPENED = "OPENED"
}

/** One settled journey; `attempt_id` unique makes re-callbacks idempotent. */
@Entity(
    tableName = "postcard_journey",
    indices = [
        Index(value = ["attempt_id"], unique = true),
        Index(value = ["deliver_at_epoch_ms", "status"]),
    ],
)
data class PostcardJourneyEntity(
    @PrimaryKey @ColumnInfo(name = "journey_id") val journeyId: String,
    @ColumnInfo(name = "attempt_id") val attemptId: String,
    @ColumnInfo(name = "stable_song_key") val stableSongKey: String,
    @ColumnInfo(name = "completed_at_epoch_ms") val completedAtEpochMs: Long,
    @ColumnInfo(name = "completed_at_zone_id") val completedAtZoneId: String,
    @ColumnInfo(name = "distance_km") val distanceKm: Float,
    @ColumnInfo(name = "card_count") val cardCount: Int,
    /** M0 delivers immediately, so this equals completedAt; M1 adds the 8..40 min delay. */
    @ColumnInfo(name = "deliver_at_epoch_ms") val deliverAtEpochMs: Long,
    @ColumnInfo(name = "status") val status: String,
    @ColumnInfo(name = "created_at_epoch_ms") val createdAtEpochMs: Long,
)

/**
 * One formal collectible card. The primary key IS the logical unique key
 * `(stable_song_key \u001F spotId \u001F conditionKey)` — no autogen id exists anywhere in the
 * feature, so `stampSerial` and exports can never depend on insertion order.
 */
@Entity(
    tableName = "postcard_card",
    indices = [
        Index(value = ["stable_song_key", "spot_id", "condition_key"], unique = true),
        Index(value = ["spot_id", "condition_key"]),
        Index(value = ["first_visited_at_epoch_ms"]),
    ],
)
data class PostcardCardEntity(
    @PrimaryKey @ColumnInfo(name = "card_key") val cardKey: String,
    @ColumnInfo(name = "stable_song_key") val stableSongKey: String,
    /** Weak reference for the "view song" jump; nulled when the library row disappears. */
    @ColumnInfo(name = "library_song_id") val librarySongId: Long?,
    @ColumnInfo(name = "song_title_snapshot") val songTitleSnapshot: String,
    @ColumnInfo(name = "artist_snapshot") val artistSnapshot: String,
    @ColumnInfo(name = "spot_id") val spotId: String,
    @ColumnInfo(name = "region_id") val regionId: String,
    @ColumnInfo(name = "condition_key") val conditionKey: String,
    @ColumnInfo(name = "season_key") val seasonKey: String,
    @ColumnInfo(name = "generator_version") val generatorVersion: Int,
    /** Serialized PostcardSpec; the first-visit spec is never rewritten (design §6.1.2). */
    @ColumnInfo(name = "spec_json") val specJson: String,
    @ColumnInfo(name = "distance_km") val distanceKm: Float,
    @ColumnInfo(name = "first_visited_at_epoch_ms") val firstVisitedAtEpochMs: Long,
    @ColumnInfo(name = "first_visited_at_zone_id") val firstVisitedAtZoneId: String,
    @ColumnInfo(name = "last_visited_at_epoch_ms") val lastVisitedAtEpochMs: Long,
    @ColumnInfo(name = "visit_count") val visitCount: Int,
)

/** One visit of a journey to a card — the append-only history behind "故地重游". */
@Entity(
    tableName = "postcard_visit",
    indices = [
        Index(value = ["card_key", "journey_id"], unique = true),
        Index(value = ["journey_id"]),
        Index(value = ["visited_at_epoch_ms"]),
    ],
    foreignKeys = [
        ForeignKey(
            entity = PostcardCardEntity::class,
            parentColumns = ["card_key"],
            childColumns = ["card_key"],
            // Internal to the feature; library song deletion never touches cards or visits.
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class PostcardVisitEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "visit_id") val visitId: Long = 0,
    @ColumnInfo(name = "card_key") val cardKey: String,
    @ColumnInfo(name = "journey_id") val journeyId: String,
    @ColumnInfo(name = "visited_at_epoch_ms") val visitedAtEpochMs: Long,
    @ColumnInfo(name = "time_of_day_key") val timeOfDayKey: String,
    @ColumnInfo(name = "weather_key") val weatherKey: String,
    @ColumnInfo(name = "season_key") val seasonKey: String,
    @ColumnInfo(name = "distance_km") val distanceKm: Float,
)
