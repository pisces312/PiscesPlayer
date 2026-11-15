/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Original work in 2026, sound_postcard feature
 * (RhythmChartLab docs/design/sound-postcard-journey-design.md §5.1, §5.2, §10.1, §10.3).
 */
package com.pisces312.piscesplayer.data.postcard

import android.util.Log
import androidx.room.withTransaction
import com.pisces312.piscesplayer.data.database.PiscesPlayerDatabase
import com.pisces312.piscesplayer.data.database.PostcardAttemptStatus
import com.pisces312.piscesplayer.data.database.PostcardCardEntity
import com.pisces312.piscesplayer.data.database.PostcardJourneyEntity
import com.pisces312.piscesplayer.data.database.PostcardJourneyStatus
import com.pisces312.piscesplayer.data.database.PostcardSongProfileEntity
import com.pisces312.piscesplayer.data.database.PostcardVisitEntity
import com.pisces312.piscesplayer.data.postcard.generate.JourneyPlanner
import com.pisces312.piscesplayer.data.postcard.generate.PostcardComposer
import com.pisces312.piscesplayer.data.postcard.generate.SongFacts
import com.pisces312.piscesplayer.data.postcard.generate.SongFactsAggregation
import com.pisces312.piscesplayer.data.database.PostcardProfileEntity
import com.pisces312.piscesplayer.data.postcard.model.PostcardVersions
import java.time.ZoneId
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What the observer hands to the transaction: everything already frozen on the main thread. */
data class SettlementRequest(
    val attemptId: String,
    val facts: SongFacts,
    val completedAtEpochMs: Long,
    val zoneId: ZoneId,
)

/** Outcome of a settlement, mostly for logging and tests. */
data class SettlementResult(
    val journeyId: String,
    val cardCount: Int,
    val alreadySettled: Boolean,
)

/**
 * The single write path of the feature (design §10.1). One Room transaction per attempt:
 *
 * 1. re-read the attempt, refuse unless it is TRACKING (idempotency gate);
 * 2. read or freeze `postcard_song_profile` (first valid settlement wins forever);
 * 3. plan (frozen region/route) and compose 1..2 specs for this completion;
 * 4. `INSERT journey` (attempt_id unique) — a duplicate callback returns the existing row;
 * 5. upsert cards: first write wins, an existing card only bumps visit counters;
 * 6. insert visit rows `(card_key, journey_id)` — the same journey never counts twice;
 * 7. mark the attempt SETTLED and add the distance to the profile.
 *
 * M0 delivers immediately (`deliverAt == completedAt`, status DELIVERED) per design §4.3;
 * M1 swaps in the 8..40 minute delay without touching this shape.
 */
@Singleton
class JourneySettlement @Inject constructor(
    private val database: PiscesPlayerDatabase,
    private val repository: PostcardRepository,
    private val factsLoader: com.pisces312.piscesplayer.data.postcard.generate.SongFactsLoader,
) {

    private val planner = JourneyPlanner(PostcardVersions.GENERATOR)
    private val composer = PostcardComposer(
        generatorVersion = PostcardVersions.GENERATOR,
        rendererContentVersion = PostcardVersions.RENDERER_CONTENT,
    )

    suspend fun settle(request: SettlementRequest): SettlementResult? = withContext(Dispatchers.IO) {
        // Resolve the content-URI alias OUTSIDE the transaction: its loader switches threads,
        // and a Room transaction is bound to the thread that opened it.
        val profileAlias = request.facts.librarySongId
            ?.let { factsLoader.contentUriAliasForSong(it) }
            ?: factsLoader.uriHashAlias(request.facts.stableSongKey)

        database.withTransaction {
            val attemptDao = database.postcardAttemptDao()
            val attempt = attemptDao.byAttemptId(request.attemptId) ?: return@withTransaction null
            if (attempt.status == PostcardAttemptStatus.SETTLED) {
                // Duplicate callback after a successful transaction: report the existing journey.
                val existing = database.postcardJourneyDao().byAttemptId(request.attemptId)
                return@withTransaction existing?.let {
                    SettlementResult(it.journeyId, it.cardCount, alreadySettled = true)
                }
            }
            if (attempt.status != PostcardAttemptStatus.TRACKING &&
                attempt.status != PostcardAttemptStatus.SETTLEMENT_FAILED
            ) {
                return@withTransaction null
            }

            val songProfileDao = database.postcardSongProfileDao()
            val facts = request.facts
            val frozen = songProfileDao.byKey(facts.stableSongKey)
            val plan = if (frozen != null) {
                planner.planWithFrozenRoute(
                    facts = facts,
                    regionId = frozen.regionId,
                    route = repository.decodeRoute(frozen.routeJson),
                    completedAtEpochMs = request.completedAtEpochMs,
                    zone = request.zoneId,
                )
            } else {
                planner.plan(facts, request.completedAtEpochMs, request.zoneId)
            }

            if (frozen == null) {
                songProfileDao.insertIfAbsent(
                    PostcardSongProfileEntity(
                        stableSongKey = facts.stableSongKey,
                        // SHA-256 of contentUriString (design §5.1), so an M1 library rescan
                        // can remap identities. Never a plaintext path or URI.
                        uriHashAlias = profileAlias,
                        librarySongId = facts.librarySongId,
                        durationMs = facts.durationMs,
                        bpm = facts.bpm,
                        genreKey = facts.genreKey,
                        energy = quantize(facts.energy),
                        lowRatio = quantize(facts.lowRatio),
                        midRatio = quantize(facts.midRatio),
                        highRatio = quantize(facts.highRatio),
                        artworkPaletteJson = facts.artworkPaletteArgb
                            .takeIf { it.isNotEmpty() }
                            ?.let(repository::encodePalette),
                        generatorVersion = PostcardVersions.GENERATOR,
                        regionId = plan.regionId,
                        routeJson = repository.encodeRoute(plan.routeSpotIds),
                        masterGeneratedAtMs = facts.masterGeneratedAtMs,
                        frozenAtEpochMs = request.completedAtEpochMs,
                    )
                )
            }

            val specs = composer.compose(
                facts = facts,
                plan = plan,
                firstCreatedAtEpochMs = request.completedAtEpochMs,
                zoneId = request.zoneId,
            )
            if (specs.isEmpty()) {
                attemptDao.updateStatus(
                    request.attemptId,
                    PostcardAttemptStatus.CANCELLED,
                    "no_spec",
                )
                return@withTransaction null
            }

            val journeyId = UUID.randomUUID().toString()
            val journey = PostcardJourneyEntity(
                journeyId = journeyId,
                attemptId = request.attemptId,
                stableSongKey = facts.stableSongKey,
                completedAtEpochMs = request.completedAtEpochMs,
                completedAtZoneId = request.zoneId.id,
                distanceKm = plan.baseKm,
                cardCount = specs.size,
                // M0: immediate delivery (design §4.3).
                deliverAtEpochMs = request.completedAtEpochMs,
                status = PostcardJourneyStatus.DELIVERED,
                createdAtEpochMs = request.completedAtEpochMs,
            )
            if (database.postcardJourneyDao().insertIfAbsent(journey) == -1L) {
                val existing = database.postcardJourneyDao().byAttemptId(request.attemptId)
                return@withTransaction existing?.let {
                    SettlementResult(it.journeyId, it.cardCount, alreadySettled = true)
                }
            }

            val cardDao = database.postcardCardDao()
            val visitDao = database.postcardVisitDao()
            for (spec in specs) {
                val inserted = cardDao.insertIfAbsent(
                    PostcardCardEntity(
                        cardKey = spec.cardKey,
                        stableSongKey = facts.stableSongKey,
                        librarySongId = facts.librarySongId,
                        songTitleSnapshot = facts.title,
                        artistSnapshot = facts.artist,
                        spotId = spec.spotId,
                        regionId = spec.regionId,
                        conditionKey = spec.conditionKey,
                        seasonKey = spec.seasonKey,
                        generatorVersion = spec.generatorVersion,
                        specJson = repository.encodeSpec(spec),
                        distanceKm = spec.distanceKm,
                        firstVisitedAtEpochMs = request.completedAtEpochMs,
                        firstVisitedAtZoneId = request.zoneId.id,
                        lastVisitedAtEpochMs = request.completedAtEpochMs,
                        visitCount = 1,
                    )
                )
                if (inserted == -1L) {
                    // Revisit: keep the first-visit spec and timestamps, only bump the counters.
                    cardDao.bumpVisit(spec.cardKey, request.completedAtEpochMs)
                }
                visitDao.insertIfAbsent(
                    PostcardVisitEntity(
                        cardKey = spec.cardKey,
                        journeyId = journeyId,
                        visitedAtEpochMs = request.completedAtEpochMs,
                        timeOfDayKey = spec.conditionKey.substringBefore('_'),
                        weatherKey = spec.conditionKey.substringAfter('_'),
                        seasonKey = spec.seasonKey,
                        distanceKm = spec.distanceKm,
                    )
                )
            }

            attemptDao.updateStatus(request.attemptId, PostcardAttemptStatus.SETTLED, null)
            val profileDao = database.postcardProfileDao()
            if (profileDao.get() == null) {
                profileDao.upsert(
                    PostcardProfileEntity(
                        id = 1,
                        travelerName = PostcardRepository.DEFAULT_TRAVELER_NAME,
                        worldVersion = PostcardRepository.WORLD_VERSION,
                        totalDistanceKm = 0f,
                        totalJourneys = 0,
                        createdAtEpochMs = request.completedAtEpochMs,
                    )
                )
            }
            profileDao.addJourney(plan.baseKm)

            SettlementResult(journeyId = journeyId, cardCount = specs.size, alreadySettled = false)
        }
    }

    /** Re-quantize at the persistence boundary; idempotent for already-quantized inputs. */
    private fun quantize(value: Float?): Float? =
        value?.let { SongFactsAggregation.quantize(it) }
}
