/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Original work in 2026, sound_postcard feature
 * (RhythmChartLab docs/design/sound-postcard-journey-design.md §10.3).
 */
package com.pisces312.piscesplayer.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * DAOs for the M0 postcard tables. Idempotency is expressed with `OnConflictStrategy.IGNORE`
 * returning the row id (-1 on conflict) rather than silent success, so the settlement
 * transaction (M0-3, design §10.1) can tell "first write" from "already settled".
 */
@Dao
interface PostcardProfileDao {
    @Query("SELECT * FROM postcard_profile WHERE id = 1")
    suspend fun get(): PostcardProfileEntity?

    @Upsert
    suspend fun upsert(profile: PostcardProfileEntity)

    @Query(
        "UPDATE postcard_profile SET total_distance_km = total_distance_km + :km, " +
            "total_journeys = total_journeys + 1 WHERE id = 1",
    )
    suspend fun addJourney(km: Float)
}

@Dao
interface PostcardAttemptDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(attempt: PostcardAttemptEntity)

    @Query("SELECT * FROM postcard_attempt WHERE attempt_id = :attemptId")
    suspend fun byAttemptId(attemptId: String): PostcardAttemptEntity?

    @Query("SELECT * FROM postcard_attempt WHERE status = 'TRACKING' ORDER BY started_at_epoch_ms DESC LIMIT 1")
    suspend fun tracking(): PostcardAttemptEntity?

    @Query("UPDATE postcard_attempt SET status = :status, end_reason = :endReason WHERE attempt_id = :attemptId")
    suspend fun updateStatus(attemptId: String, status: String, endReason: String?)

    @Query("UPDATE postcard_attempt SET accumulated_play_ms = :accumulatedPlayMs WHERE attempt_id = :attemptId")
    suspend fun updateAccumulated(attemptId: String, accumulatedPlayMs: Long)

    /** Process-death recovery (design §4.2.2): unresolved attempts become CANCELLED, no rewards. */
    @Query("UPDATE postcard_attempt SET status = 'CANCELLED', end_reason = :reason WHERE status = 'TRACKING'")
    suspend fun cancelAllTracking(reason: String)
}

@Dao
interface PostcardSongProfileDao {
    /** IGNORE + row id: the first valid settlement wins; later re-listens reuse the freeze. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(profile: PostcardSongProfileEntity): Long

    @Query("SELECT * FROM postcard_song_profile WHERE stable_song_key = :stableSongKey")
    suspend fun byKey(stableSongKey: String): PostcardSongProfileEntity?
}

@Dao
interface PostcardJourneyDao {
    /** IGNORE + row id is the settlement idempotency seam for duplicate callbacks. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(journey: PostcardJourneyEntity): Long

    @Query("SELECT * FROM postcard_journey WHERE attempt_id = :attemptId")
    suspend fun byAttemptId(attemptId: String): PostcardJourneyEntity?

    @Query("SELECT * FROM postcard_journey WHERE deliver_at_epoch_ms <= :now ORDER BY completed_at_epoch_ms DESC")
    fun deliveredFeed(now: Long): Flow<List<PostcardJourneyEntity>>

    /** Delivery sweep for cold start / entering the inbox (design §4.3): flips due rows only. */
    @Query("UPDATE postcard_journey SET status = 'DELIVERED' WHERE status = 'PENDING' AND deliver_at_epoch_ms <= :now")
    suspend fun deliverDue(now: Long): Int

    @Query("UPDATE postcard_journey SET status = 'OPENED' WHERE journey_id = :journeyId")
    suspend fun markOpened(journeyId: String)

    @Query("SELECT COUNT(*) FROM postcard_journey")
    suspend fun count(): Int
}

@Dao
interface PostcardCardDao {
    /**
     * First write of a card; conflicts are IGNOREd and signalled via the -1 row id so the
     * caller bumps [bumpVisit] instead — specJson/firstVisited are never replaced.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(card: PostcardCardEntity): Long

    @Query("SELECT * FROM postcard_card WHERE card_key = :cardKey")
    suspend fun byKey(cardKey: String): PostcardCardEntity?

    @Query("UPDATE postcard_card SET visit_count = visit_count + 1, last_visited_at_epoch_ms = :visitedAt WHERE card_key = :cardKey")
    suspend fun bumpVisit(cardKey: String, visitedAt: Long)

    @Query("SELECT * FROM postcard_card ORDER BY first_visited_at_epoch_ms DESC")
    fun albumFeed(): Flow<List<PostcardCardEntity>>

    @Query("SELECT * FROM postcard_card WHERE stable_song_key = :stableSongKey ORDER BY first_visited_at_epoch_ms DESC")
    suspend fun bySong(stableSongKey: String): List<PostcardCardEntity>

    @Query("SELECT COUNT(*) FROM postcard_card")
    suspend fun count(): Int
}

@Dao
interface PostcardVisitDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(visit: PostcardVisitEntity): Long

    @Query("SELECT * FROM postcard_visit WHERE card_key = :cardKey ORDER BY visited_at_epoch_ms DESC")
    suspend fun byCard(cardKey: String): List<PostcardVisitEntity>

    @Query("SELECT * FROM postcard_visit WHERE journey_id = :journeyId ORDER BY visited_at_epoch_ms DESC")
    suspend fun byJourney(journeyId: String): List<PostcardVisitEntity>

    @Query("SELECT COUNT(*) FROM postcard_visit WHERE journey_id = :journeyId")
    suspend fun countByJourney(journeyId: String): Int
}
