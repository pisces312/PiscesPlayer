/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Original work in 2026, sound_postcard feature
 * (RhythmChartLab docs/design/sound-postcard-journey-design.md §10.1, §10.3).
 */
package com.pisces312.piscesplayer.data.postcard

import com.pisces312.piscesplayer.data.database.PostcardAttemptDao
import com.pisces312.piscesplayer.data.database.PostcardCardDao
import com.pisces312.piscesplayer.data.database.PostcardCardEntity
import com.pisces312.piscesplayer.data.database.PostcardJourneyDao
import com.pisces312.piscesplayer.data.database.PostcardJourneyEntity
import com.pisces312.piscesplayer.data.database.PostcardProfileDao
import com.pisces312.piscesplayer.data.database.PostcardProfileEntity
import com.pisces312.piscesplayer.data.database.PostcardSongProfileDao
import com.pisces312.piscesplayer.data.database.PostcardVisitDao
import com.pisces312.piscesplayer.data.database.PostcardVisitEntity
import com.pisces312.piscesplayer.data.postcard.model.PostcardSpec
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * Thin persistence facade for the sound_postcard feature: JSON codecs plus the reads the UI
 * needs. Business rules — attempt state machine, planning, settlement transaction — live in
 * [JourneySettlement] and [JourneyPlaybackObserver], never here.
 *
 * Zero network: nothing in `data/postcard` may inject Retrofit / OkHttp (design §10.4).
 */
@Singleton
class PostcardRepository @Inject constructor(
    private val profileDao: PostcardProfileDao,
    private val journeyDao: PostcardJourneyDao,
    private val cardDao: PostcardCardDao,
    private val visitDao: PostcardVisitDao,
    private val songProfileDao: PostcardSongProfileDao,
) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    // ------------------------------------------------------------------ codecs

    fun encodeSpec(spec: PostcardSpec): String = json.encodeToString(PostcardSpec.serializer(), spec)

    fun decodeSpec(specJson: String): PostcardSpec? =
        runCatching { json.decodeFromString(PostcardSpec.serializer(), specJson) }.getOrNull()

    fun encodeRoute(route: List<String>): String =
        json.encodeToString(ListSerializer(String.serializer()), route)

    fun decodeRoute(routeJson: String): List<String> =
        runCatching { json.decodeFromString(ListSerializer(String.serializer()), routeJson) }
            .getOrDefault(emptyList())

    fun encodePalette(palette: List<Int>): String =
        json.encodeToString(ListSerializer(Int.serializer()), palette)

    fun decodePalette(paletteJson: String?): List<Int> {
        if (paletteJson.isNullOrBlank()) return emptyList()
        return runCatching { json.decodeFromString(ListSerializer(Int.serializer()), paletteJson) }
            .getOrDefault(emptyList())
    }

    // ------------------------------------------------------------------ profile (single row)

    /** Creates the id=1 row on first use; M0 has no rename UI. */
    suspend fun ensureProfile(): PostcardProfileEntity = withContext(Dispatchers.IO) {
        profileDao.get() ?: PostcardProfileEntity(
            id = 1,
            travelerName = defaultTravelerName(),
            worldVersion = WORLD_VERSION,
            totalDistanceKm = 0f,
            totalJourneys = 0,
            createdAtEpochMs = System.currentTimeMillis(),
        ).also { profileDao.upsert(it) }
    }

    // ------------------------------------------------------------------ reads for the UI

    /**
     * Inbox feed: journeys whose delivery time has passed, newest completion first.
     * M0 delivers immediately (`deliverAt == completedAt`), so every settled journey shows up.
     */
    fun inbox(now: Long): Flow<List<PostcardJourneyEntity>> = journeyDao.deliveredFeed(now)

    /** Album feed: every card, ordered by first visit — revisits never reorder the shelf. */
    fun album(): Flow<List<PostcardCardEntity>> = cardDao.albumFeed()

    suspend fun cardByKey(cardKey: String): CardView? = withContext(Dispatchers.IO) {
        val card = cardDao.byKey(cardKey) ?: return@withContext null
        CardView(card = card, spec = decodeSpec(card.specJson), visits = visitDao.byCard(cardKey))
    }

    /** Cards a given journey delivered, for the "open the letter" presentation. */
    suspend fun cardsForJourney(journeyId: String): List<CardView> = withContext(Dispatchers.IO) {
        visitDao.byJourney(journeyId).mapNotNull { visit ->
            val card = cardDao.byKey(visit.cardKey) ?: return@mapNotNull null
            CardView(card = card, spec = decodeSpec(card.specJson), visits = visitDao.byCard(visit.cardKey))
        }
    }

    suspend fun markJourneyOpened(journeyId: String) = withContext(Dispatchers.IO) {
        journeyDao.markOpened(journeyId)
    }

    suspend fun journeyCount(): Int = withContext(Dispatchers.IO) { journeyDao.count() }

    suspend fun cardCount(): Int = withContext(Dispatchers.IO) { cardDao.count() }

    suspend fun cardsForSong(stableSongKey: String): List<PostcardCardEntity> =
        withContext(Dispatchers.IO) { cardDao.bySong(stableSongKey) }

    companion object {
        /** Fixed fictional handle for M0 (design §6.2); renaming arrives with M2. */
        const val DEFAULT_TRAVELER_NAME_ZH = "邮差"
        const val DEFAULT_TRAVELER_NAME_EN = "Courier"

        /** Old-stamp world version; only M2 map layers read it. */
        const val WORLD_VERSION = 1

        /**
         * Chosen once when the profile is created and stored, so switching the device language
         * later never rewrites the traveller name (same snapshot rule as message text).
         */
        fun defaultTravelerName(): String =
            if (java.util.Locale.getDefault().language.equals("zh", ignoreCase = true)) {
                DEFAULT_TRAVELER_NAME_ZH
            } else {
                DEFAULT_TRAVELER_NAME_EN
            }
    }
}

/** One card with its decoded spec (null when the stored JSON no longer parses) and history. */
data class CardView(
    val card: PostcardCardEntity,
    val spec: PostcardSpec?,
    val visits: List<PostcardVisitEntity>,
)
