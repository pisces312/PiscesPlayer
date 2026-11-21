package com.pisces312.piscesplayer.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.reflect.TypeToken
import com.pisces312.piscesplayer.data.database.EngagementDao
import com.pisces312.piscesplayer.data.database.SongEngagementEntity
import com.pisces312.piscesplayer.data.model.MixKind
import com.pisces312.piscesplayer.data.model.MixPick
import com.pisces312.piscesplayer.data.model.Song
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import java.io.File
import java.util.Calendar
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DailyMixManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val engagementDao: EngagementDao
) {

    private val gson = Gson()
    private val legacyScoresFile = File(context.filesDir, "song_scores.json")
    private val fileLock = Any()
    private val statsType = object : TypeToken<MutableMap<String, SongEngagementStats>>() {}.type

    private val migrationMutex = Mutex()

    @Volatile
    private var legacyMigrationComplete = false

    data class SongEngagementStats(
        val playCount: Int = 0,
        val totalPlayDurationMs: Long = 0L,
        val lastPlayedTimestamp: Long = 0L
    )

    private suspend fun ensureLegacyDataMigrated() {
        if (legacyMigrationComplete) return
        migrationMutex.withLock {
            if (!legacyMigrationComplete) {
                migrateLegacyDataIfNeeded()
            }
        }
    }

    /**
     * Migrates engagements from legacy JSON file to Room database.
     * Runs once, lazily, inside the first caller that needs engagement data.
     * Suspend fun running on the caller's coroutine, so no runBlocking needed.
     * The synchronized block only guards the file read (no suspension points inside).
     */
    private suspend fun migrateLegacyDataIfNeeded() {
        if (legacyMigrationComplete || !legacyScoresFile.exists()) {
            legacyMigrationComplete = true
            return
        }

        val entitiesToInsert: List<SongEngagementEntity>? = synchronized(fileLock) {
            if (legacyMigrationComplete) return@synchronized null

            try {
                val legacyData = readLegacyEngagementsLocked()
                if (legacyData.isNotEmpty()) {
                    legacyData.mapNotNull { (songId, stats) ->
                        val id = songId.toLongOrNull() ?: return@mapNotNull null
                        SongEngagementEntity(
                            songId = id,
                            playCount = stats.playCount.coerceAtLeast(0),
                            totalPlayDurationMs = stats.totalPlayDurationMs.coerceAtLeast(0L),
                            lastPlayedTimestamp = stats.lastPlayedTimestamp.coerceAtLeast(0L)
                        )
                    }
                } else null
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Failed to read legacy engagement data")
                null
            }
        }

        if (entitiesToInsert != null) {
            try {
                engagementDao.upsertEngagements(entitiesToInsert)
                Timber.tag(TAG).i("Migrated ${entitiesToInsert.size} engagement records from JSON to Room")

                val backupFile = File(context.filesDir, "song_scores.json.bak")
                legacyScoresFile.renameTo(backupFile)
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Failed to insert legacy engagement data into Room")
            }
        }

        legacyMigrationComplete = true
    }

    /**
     * Reads engagements from Room database.
     * Awaits migration completion before querying, ensuring data consistency
     * without blocking any thread at startup.
     */
    private suspend fun readEngagements(): Map<String, SongEngagementStats> {
        ensureLegacyDataMigrated()
        return engagementDao.getAllEngagements().associate { entity ->
            entity.songId.toString() to SongEngagementStats(
                playCount = entity.playCount,
                totalPlayDurationMs = entity.totalPlayDurationMs,
                lastPlayedTimestamp = entity.lastPlayedTimestamp
            )
        }
    }

    /**
     * Legacy method to read from JSON file during migration.
     */
    private fun readLegacyEngagementsLocked(): MutableMap<String, SongEngagementStats> {
        if (!legacyScoresFile.exists()) {
            return mutableMapOf()
        }

        val raw = runCatching { legacyScoresFile.readText() }
            .onFailure { Timber.tag(TAG).e(it, "Failed to read legacy song scores file") }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: return mutableMapOf()

        return runCatching {
            val element = gson.fromJson(raw, JsonElement::class.java)
            parseEngagementElement(element)
        }.getOrElse { throwable ->
            Timber.tag(TAG).e(throwable, "Failed to parse legacy song scores file")
            mutableMapOf()
        }
    }

    private fun parseEngagementElement(element: JsonElement?): MutableMap<String, SongEngagementStats> {
        if (element == null || element.isJsonNull) {
            return mutableMapOf()
        }

        if (element.isJsonObject) {
            return parseEngagementObject(element.asJsonObject)
        }

        return runCatching {
            val parsed: MutableMap<String, SongEngagementStats> = gson.fromJson(element, statsType)
            parsed.mapValuesTo(mutableMapOf()) { (_, stats) -> sanitizeStats(stats) }
        }.getOrElse {
            Timber.tag(TAG).w("Unsupported song engagement format, ignoring it")
            mutableMapOf()
        }
    }

    private fun parseEngagementObject(obj: JsonObject): MutableMap<String, SongEngagementStats> {
        val result = mutableMapOf<String, SongEngagementStats>()
        for ((key, value) in obj.entrySet()) {
            val stats = parseStatsValue(key, value)
            if (stats != null) {
                result[key] = stats
            } else {
                Timber.tag(TAG).w("Skipping song engagement entry for \"$key\" because it could not be parsed: $value")
            }
        }
        return result
    }

    private fun parseStatsValue(key: String, value: JsonElement): SongEngagementStats? {
        if (value.isJsonObject) {
            val parsedStats = runCatching {
                gson.fromJson(value, SongEngagementStats::class.java)
            }.getOrNull()

            if (parsedStats != null) {
                return sanitizeStats(parsedStats)
            }

            val extracted = extractScore(value)
            if (extracted != null) {
                return SongEngagementStats(playCount = extracted)
            }
        } else {
            val extracted = extractScore(value)
            if (extracted != null) {
                return SongEngagementStats(playCount = extracted)
            }
        }

        Timber.tag(TAG).w("Encountered unsupported engagement value for \"$key\": $value")
        return null
    }

    private fun extractScore(value: JsonElement): Int? {
        if (value.isJsonPrimitive) {
            val primitive = value.asJsonPrimitive
            return when {
                primitive.isNumber -> primitive.asNumber.toInt()
                primitive.isString -> primitive.asString.toIntOrNull()
                else -> null
            }
        }

        if (value.isJsonObject) {
            val obj = value.asJsonObject
            for (key in SCORE_KEY_CANDIDATES) {
                val candidate = obj.get(key)
                if (candidate != null && candidate.isJsonPrimitive) {
                    val primitive = candidate.asJsonPrimitive
                    val parsed = when {
                        primitive.isNumber -> primitive.asNumber.toInt()
                        primitive.isString -> primitive.asString.toIntOrNull()
                        else -> null
                    }
                    if (parsed != null) {
                        return parsed
                    }
                }
            }
        }

        return null
    }

    private fun sanitizeStats(stats: SongEngagementStats): SongEngagementStats {
        return stats.copy(
            playCount = stats.playCount.coerceAtLeast(0),
            totalPlayDurationMs = stats.totalPlayDurationMs.coerceAtLeast(0L),
            lastPlayedTimestamp = stats.lastPlayedTimestamp.coerceAtLeast(0L)
        )
    }

    /**
     * Records a song play using Room's atomic upsert operation.
     * More efficient than JSON read-modify-write.
     */
    suspend fun recordPlay(
        songId: String,
        songDurationMs: Long = 0L,
        timestamp: Long = System.currentTimeMillis()
    ) {
        val id = songId.toLongOrNull() ?: return
        engagementDao.recordPlay(
            songId = id,
            durationMs = songDurationMs.coerceAtLeast(0L),
            timestamp = timestamp.coerceAtLeast(0L)
        )
    }

    suspend fun incrementScore(songId: String) {
        recordPlay(songId)
    }

    suspend fun getScore(songId: String): Int {
        val id = songId.toLongOrNull() ?: return 0
        return engagementDao.getPlayCount(id) ?: 0
    }

    suspend fun getEngagementStats(songId: String): SongEngagementStats? {
        val id = songId.toLongOrNull() ?: return null
        return engagementDao.getEngagement(id)?.let { entity ->
            SongEngagementStats(
                playCount = entity.playCount,
                totalPlayDurationMs = entity.totalPlayDurationMs,
                lastPlayedTimestamp = entity.lastPlayedTimestamp
            )
        }
    }

    suspend fun getAllEngagementStats(): Map<String, SongEngagementStats> {
        return readEngagements()
    }

    private suspend fun computeRankedSongs(
        allSongs: List<Song>,
        favoriteSongIds: Set<String>,
        random: java.util.Random
    ): List<RankedSong> {
        if (allSongs.isEmpty()) return emptyList()

        val engagements = readEngagements()
        val songById = allSongs.associateBy { it.id }
        val now = System.currentTimeMillis()

        val artistAffinity = mutableMapOf<Long, Double>()
        val genreAffinity = mutableMapOf<String, Double>()

        engagements.forEach { (songId, stats) ->
            val song = songById[songId] ?: return@forEach
            val weight = stats.playCount.toDouble() + (stats.totalPlayDurationMs / 60000.0)
            if (weight <= 0) return@forEach
            artistAffinity.merge(song.artistId, weight, Double::plus)
            normalizeGenreKey(song.genre)?.let { genreAffinity.merge(it, weight, Double::plus) }
        }

        val favoriteArtistWeights = mutableMapOf<Long, Int>()
        favoriteSongIds.forEach { id ->
            val song = songById[id] ?: return@forEach
            favoriteArtistWeights.merge(song.artistId, 1, Int::plus)
        }

        val maxPlayCount = engagements.values.maxOfOrNull { it.playCount }?.takeIf { it > 0 } ?: 1
        val maxDuration = engagements.values.maxOfOrNull { it.totalPlayDurationMs }?.takeIf { it > 0L } ?: 1L
        val maxArtistAffinity = artistAffinity.values.maxOrNull()?.takeIf { it > 0 } ?: 1.0
        val maxGenreAffinity = genreAffinity.values.maxOrNull()?.takeIf { it > 0 } ?: 1.0
        val maxFavoriteArtist = favoriteArtistWeights.values.maxOrNull()?.takeIf { it > 0 } ?: 1

        return allSongs.map { song ->
            val stats = engagements[song.id]
            val playCountScore = (stats?.playCount?.toDouble() ?: 0.0) / maxPlayCount
            val durationScore = (stats?.totalPlayDurationMs?.toDouble() ?: 0.0) / maxDuration
            val affinityScore = (playCountScore * 0.7 + durationScore * 0.3).coerceIn(0.0, 1.0)

            val genreKey = normalizeGenreKey(song.genre)
            val artistPreference = artistAffinity[song.artistId]?.div(maxArtistAffinity) ?: 0.0
            val genrePreference = genreKey?.let { (genreAffinity[it] ?: 0.0) / maxGenreAffinity } ?: 0.0
            val favoriteArtistPreference = favoriteArtistWeights[song.artistId]?.toDouble()?.div(maxFavoriteArtist) ?: 0.0
            val preferenceScore = if (genreKey == null) {
                (artistPreference * 0.6) + (favoriteArtistPreference * 0.4)
            } else {
                (artistPreference * 0.45) +
                    (genrePreference * 0.35) +
                    (favoriteArtistPreference * 0.20)
            }

            val recencyScore = computeRecencyScore(stats?.lastPlayedTimestamp, now)
            val noveltyScore = computeNoveltyScore(song.dateAdded, now)
            val favoriteScore = if (favoriteSongIds.contains(song.id)) 1.0 else 0.0
            val baselineScore = if (stats == null) 0.1 else 0.0
            val noise = random.nextDouble() * 0.005

            val finalScore = (preferenceScore * 0.45) +
                (affinityScore * 0.25) +
                (recencyScore * 0.15) +
                (favoriteScore * 0.1) +
                (noveltyScore * 0.05) +
                baselineScore +
                noise

            val discoveryScore = ((1.0 - affinityScore).coerceIn(0.0, 1.0) * 0.6) +
                (noveltyScore * 0.25) +
                (preferenceScore * 0.15)

            RankedSong(
                song = song,
                finalScore = if (finalScore.isNaN() || finalScore.isInfinite()) 0.0 else finalScore,
                discoveryScore = if (discoveryScore.isNaN() || discoveryScore.isInfinite()) 0.0 else discoveryScore,
                affinityScore = affinityScore,
                recencyScore = recencyScore,
                noveltyScore = noveltyScore,
                favoriteScore = favoriteScore
            )
        }
            .sortedWith(compareByDescending<RankedSong> { it.finalScore }.thenBy { it.song.id })
    }

    /**
     * Generates the merged daily mix ("每日推荐"): one list that blends two buckets.
     *
     * History: the home page used to carry two separate local-recommendation sections
     * ("Daily Mix" and "Discover") that ranked songs with the same final score and
     * overlapped heavily. They were split apart — discovery ranked by
     * [RankedSong.discoveryScore], surfacing what the listener has NOT bonded with yet —
     * and are now merged back into a single feed with explicit buckets:
     *
     * - Familiar picks come from [RankedSong.finalScore] with the favorite-aware
     *   diversity quotas; they fill up to 2/3 of [limit] first (the primary taste).
     * - Discovery picks come from [RankedSong.discoveryScore] (low-affinity, recently
     *   added, rarely played tracks) and fill the remaining third. No favorite quota
     *   here: a favorited but never-played track is a rediscovery, not a repeat, and
     *   competes by discovery score alone (it still influences scoring via preference).
     * - Buckets interleave 2 familiar : 1 discovery so sequential playback mixes both
     *   tastes; every pick carries its [MixKind] so the full-list screen can group them.
     * - The daily-seeded shuffle runs before a stable sort on the discovery candidates:
     *   genuinely different discovery scores keep their order while equal-score
     *   candidates rotate day to day (discoveryScore is noise-free, unlike finalScore).
     */
    suspend fun generateDailyMix(
        allSongs: List<Song>,
        favoriteSongIds: Set<String> = emptySet(),
        limit: Int = 30
    ): List<MixPick> {
        if (allSongs.isEmpty()) {
            return emptyList()
        }

        val calendar = Calendar.getInstance()
        val seed = calendar.get(Calendar.YEAR) * 1000 + calendar.get(Calendar.DAY_OF_YEAR)
        val random = java.util.Random(seed.toLong())

        val rankedSongs = computeRankedSongs(allSongs, favoriteSongIds, random)
        if (rankedSongs.isEmpty()) {
            return allSongs.shuffled(random)
                .take(limit.coerceAtMost(allSongs.size))
                .map { MixPick(it, MixKind.FAMILIAR) }
        }

        // Familiar first: it is the primary taste; discovery fills the remaining third.
        val familiarCount = ((limit * 2) / 3).coerceAtMost(allSongs.size)
        val discoveryCount = (limit - familiarCount).coerceAtMost(allSongs.size - familiarCount)

        val familiar = pickFamiliarSongs(rankedSongs, favoriteSongIds, familiarCount, random)
        val familiarIds = familiar.mapTo(HashSet()) { it.id }

        val discoveryCandidates = rankedSongs
            .filterNot { it.song.id in familiarIds }
            .shuffled(random)
            .sortedByDescending { it.discoveryScore }
        val discovery = pickDiscoverySongs(
            candidates = discoveryCandidates,
            allSongs = allSongs,
            excludedIds = familiarIds,
            count = discoveryCount,
            random = random
        )

        return interleaveMixPicks(
            familiar.map { MixPick(it, MixKind.FAMILIAR) },
            discovery.map { MixPick(it, MixKind.DISCOVERY) }
        )
    }

    private fun pickFamiliarSongs(
        rankedSongs: List<RankedSong>,
        favoriteSongIds: Set<String>,
        count: Int,
        random: java.util.Random
    ): List<Song> {
        if (count <= 0 || rankedSongs.isEmpty()) return emptyList()

        val diversityState = DiversityState()
        val selected = pickWithDiversity(rankedSongs, favoriteSongIds, count, diversityState)
        if (selected.size >= count || selected.size == rankedSongs.size) {
            return selected
        }

        val remainingRanked = rankedSongs.filterNot { candidate ->
            selected.any { it.id == candidate.song.id }
        }
        val quotaFill = pickWithDiversity(remainingRanked, favoriteSongIds, count - selected.size, diversityState)
        val combined = (selected + quotaFill).distinctBy { it.id }.toMutableList()

        if (combined.size < count) {
            val remaining = rankedSongs
                .map { it.song }
                .filterNot { song -> combined.any { it.id == song.id } }
                .shuffled(random)
            for (song in remaining) {
                combined.add(song)
                if (combined.size >= count) break
            }
        }

        return combined.take(count.coerceAtMost(combined.size))
    }

    private fun pickDiscoverySongs(
        candidates: List<RankedSong>,
        allSongs: List<Song>,
        excludedIds: Set<String>,
        count: Int,
        random: java.util.Random
    ): List<Song> {
        if (count <= 0 || candidates.isEmpty()) return emptyList()

        val diversityState = DiversityState()
        // No favorite allowance here: the extra artist/genre quota for favorites exists
        // for the familiar picks. In discovery, favorites compete by discovery score
        // alone — they still influence scoring via preference.
        val selected = pickWithDiversity(candidates, emptySet(), count, diversityState)
            .toMutableList()

        if (selected.size < count) {
            val selectedIds = selected.mapTo(HashSet()) { it.id }
            val filler = allSongs
                .filterNot { it.id in selectedIds || it.id in excludedIds }
                .shuffled(random)
            for (song in filler) {
                selected.add(song)
                if (selected.size >= count) break
            }
        }

        return selected.take(count.coerceAtMost(selected.size))
    }

    /** Merges the buckets 2 familiar : 1 discovery; leftovers of either side append at the end. */
    private fun interleaveMixPicks(
        familiar: List<MixPick>,
        discovery: List<MixPick>
    ): List<MixPick> {
        val result = mutableListOf<MixPick>()
        var familiarIndex = 0
        var discoveryIndex = 0
        while (familiarIndex < familiar.size || discoveryIndex < discovery.size) {
            repeat(2) {
                if (familiarIndex < familiar.size) result += familiar[familiarIndex++]
            }
            if (discoveryIndex < discovery.size) result += discovery[discoveryIndex++]
        }
        return result
    }

    private fun pickWithDiversity(
        rankedSongs: List<RankedSong>,
        favoriteSongIds: Set<String>,
        limit: Int,
        state: DiversityState = DiversityState()
    ): List<Song> {
        if (limit <= 0 || rankedSongs.isEmpty()) return emptyList()

        val selected = mutableListOf<Song>()

        for (candidate in rankedSongs) {
            if (selected.size >= limit) break
            val artistId = candidate.song.artistId
            val isFavorite = favoriteSongIds.contains(candidate.song.id)
            val maxPerArtist = if (isFavorite) 3 else 2
            val currentCount = state.artistCounts.getOrDefault(artistId, 0)
            if (currentCount >= maxPerArtist) continue

            val genreKey = normalizeGenreKey(candidate.song.genre)
            val maxPerGenre = if (genreKey == null) {
                maxUnknownGenreCount(limit, isFavorite)
            } else {
                maxKnownGenreCount(limit, isFavorite)
            }
            if (genreKey == null) {
                if (state.unknownGenreCount >= maxPerGenre) continue
            } else {
                val currentGenreCount = state.genreCounts.getOrDefault(genreKey, 0)
                if (currentGenreCount >= maxPerGenre) continue
            }

            selected += candidate.song
            state.artistCounts[artistId] = currentCount + 1
            if (genreKey == null) {
                state.unknownGenreCount += 1
            } else {
                state.genreCounts[genreKey] = state.genreCounts.getOrDefault(genreKey, 0) + 1
            }
        }

        if (selected.size < limit) {
            val selectedIds = selected.mapTo(HashSet()) { it.id }
            for (candidate in rankedSongs) {
                if (selected.size >= limit) break
                if (candidate.song.id in selectedIds) continue
                selected += candidate.song
                selectedIds += candidate.song.id
            }
        }

        return selected.take(limit)
    }

    private fun normalizeGenreKey(rawGenre: String?): String? {
        val normalized = rawGenre
            ?.trim()
            ?.lowercase()
            ?.takeIf { it.isNotBlank() }
            ?: return null

        return if (normalized.contains("unknown")) {
            null
        } else {
            normalized
        }
    }

    private fun maxKnownGenreCount(limit: Int, isFavorite: Boolean): Int {
        val baseCap = when {
            limit <= 12 -> 2
            limit <= 30 -> 3
            else -> 4
        }
        return baseCap + if (isFavorite) 1 else 0
    }

    private fun maxUnknownGenreCount(limit: Int, isFavorite: Boolean): Int {
        val baseCap = when {
            limit <= 12 -> 1
            limit <= 30 -> 2
            else -> 3
        }
        return baseCap + if (isFavorite) 1 else 0
    }

    private fun computeRecencyScore(lastPlayedTimestamp: Long?, now: Long): Double {
        if (lastPlayedTimestamp == null || lastPlayedTimestamp <= 0L) return 0.6
        val daysSinceLastPlay = ((now - lastPlayedTimestamp).coerceAtLeast(0L) / TimeUnit.DAYS.toMillis(1)).toDouble()
        return when {
            daysSinceLastPlay < 1 -> 0.2
            daysSinceLastPlay < 3 -> 0.5
            daysSinceLastPlay < 7 -> 0.7
            daysSinceLastPlay < 14 -> 0.85
            else -> 1.0
        }
    }

    private fun computeNoveltyScore(dateAdded: Long, now: Long): Double {
        if (dateAdded <= 0L) return 0.0
        val dateAddedMillis = if (dateAdded < 10_000_000_000L) {
            TimeUnit.SECONDS.toMillis(dateAdded)
        } else {
            dateAdded
        }
        val daysSinceAdded = ((now - dateAddedMillis).coerceAtLeast(0L) / TimeUnit.DAYS.toMillis(1)).toDouble()
        return (1.0 - (daysSinceAdded / 60.0)).coerceIn(0.0, 1.0)
    }

    private data class RankedSong(
        val song: Song,
        val finalScore: Double,
        val discoveryScore: Double,
        val affinityScore: Double,
        val recencyScore: Double,
        val noveltyScore: Double,
        val favoriteScore: Double
    )

    private data class DiversityState(
        val artistCounts: MutableMap<Long, Int> = mutableMapOf(),
        val genreCounts: MutableMap<String, Int> = mutableMapOf(),
        var unknownGenreCount: Int = 0
    )

    companion object {
        private const val TAG = "DailyMixManager"
        private val SCORE_KEY_CANDIDATES = listOf("score", "count", "value")
    }
}
