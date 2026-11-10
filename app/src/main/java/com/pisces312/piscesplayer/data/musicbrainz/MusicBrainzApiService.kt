package com.pisces312.piscesplayer.data.musicbrainz

import android.os.SystemClock
import com.pisces312.piscesplayer.BuildConfig
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

data class MusicBrainzMatch(
    val recordingId: String,
    val releaseId: String?,
    val artistId: String?,
    val title: String,
    val artist: String,
    val album: String,
    val year: Int,
    val durationMs: Long?,
    val score: Int,
    val disambiguation: String?,
    val releaseGroupId: String? = null,
    val releaseDate: String? = null,
    val albumArtist: String? = null,
    val releaseStatus: String? = null,
    val releaseType: String? = null
)

@Singleton
class MusicBrainzApiService @Inject constructor(
    baseClient: OkHttpClient
) {
    private val client = baseClient.newBuilder()
        .addInterceptor { chain ->
            chain.proceed(
                chain.request().newBuilder()
                    .header(
                        "User-Agent",
                        "PiscesPlayer/${BuildConfig.VERSION_NAME} " +
                            "(https://github.com/pisces312/PiscesPlayer)"
                    )
                    .header("Accept", "application/json")
                    .build()
            )
        }
        .build()
    private val requestMutex = Mutex()
    private var lastRequestStartedAt = 0L

    suspend fun searchRecording(
        title: String,
        artist: String?,
        album: String?,
        durationMs: Long?
    ): List<MusicBrainzMatch> = requestMutex.withLock {
        awaitRateLimit()

        val query = buildRecordingQuery(title, artist, album)
        val url = API_ROOT.toHttpUrl().newBuilder()
            .addPathSegment("recording")
            .addQueryParameter("query", query)
            .addQueryParameter("fmt", "json")
            .addQueryParameter("limit", MAX_RESULTS.toString())
            .build()
        val request = Request.Builder().url(url).get().build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("MusicBrainz returned HTTP ${response.code}")
            }
            parseSearchResponse(
                json = JSONObject(response.body.string()),
                expectedTitle = title,
                expectedArtist = artist.orEmpty(),
                expectedAlbum = album,
                expectedDurationMs = durationMs
            )
        }
    }

    /** Returns null for a release that no longer exists; every other failure is thrown. */
    suspend fun lookupRelease(releaseId: String): JSONObject? = requestMutex.withLock {
        awaitRateLimit()

        val url = API_ROOT.toHttpUrl().newBuilder()
            .addPathSegment("release")
            .addPathSegment(releaseId)
            .addQueryParameter("inc", "recordings+artist-credits+genres+release-groups")
            .addQueryParameter("fmt", "json")
            .build()
        val request = Request.Builder().url(url).get().build()

        client.newCall(request).execute().use { response ->
            when {
                response.code == 404 -> null
                !response.isSuccessful -> throw IOException("MusicBrainz returned HTTP ${response.code}")
                else -> JSONObject(response.body.string())
            }
        }
    }

    private suspend fun awaitRateLimit() {
        val elapsed = SystemClock.elapsedRealtime() - lastRequestStartedAt
        if (elapsed in 0 until MIN_REQUEST_INTERVAL_MS) {
            delay(MIN_REQUEST_INTERVAL_MS - elapsed)
        }
        lastRequestStartedAt = SystemClock.elapsedRealtime()
    }

    companion object {
        private const val API_ROOT = "https://musicbrainz.org/ws/2/"
        private const val MAX_RESULTS = 8
        private const val MIN_REQUEST_INTERVAL_MS = 1_100L

        internal fun buildRecordingQuery(title: String, artist: String?, album: String?): String {
            val terms = mutableListOf("recording:\"${escapeLucene(title)}\"")
            artist?.takeIf { it.isNotBlank() }?.let {
                terms += "artist:\"${escapeLucene(it)}\""
            }
            album?.takeIf { it.isNotBlank() && !it.equals("Unknown Album", ignoreCase = true) }
                ?.let { terms += "release:\"${escapeLucene(it)}\"" }
            return terms.joinToString(" AND ")
        }

        private fun escapeLucene(value: String): String = buildString(value.length) {
            value.trim().forEach { char ->
                if (char in LUCENE_SPECIAL_CHARS) append('\\')
                append(char)
            }
        }

        private val LUCENE_SPECIAL_CHARS = setOf(
            '+', '-', '&', '|', '!', '(', ')', '{', '}', '[', ']', '^', '"', '~', '*', '?', ':', '\\', '/'
        )

        internal fun parseSearchResponse(
            json: JSONObject,
            expectedTitle: String,
            expectedArtist: String,
            expectedAlbum: String?,
            expectedDurationMs: Long?
        ): List<MusicBrainzMatch> {
            val recordings = json.optJSONArray("recordings") ?: JSONArray()
            return (0 until recordings.length()).mapNotNull { index ->
                val recording = recordings.optJSONObject(index) ?: return@mapNotNull null
                val recordingId = recording.optString("id").takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                val title = recording.optString("title").takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                val credits = recording.optJSONArray("artist-credit")
                val artist = MusicBrainzReleaseParser.artistCreditName(credits)
                    .ifBlank { "Unknown Artist" }
                val firstArtist = credits?.optJSONObject(0)?.optJSONObject("artist")
                val artistId = firstArtist?.optString("id")?.takeIf { it.isNotBlank() }
                val release = chooseRelease(recording.optJSONArray("releases"), expectedAlbum)
                val releaseId = release?.optString("id")?.takeIf { it.isNotBlank() }
                val album = release?.optString("title")?.takeIf { it.isNotBlank() }
                    ?: expectedAlbum.orEmpty()
                val releaseGroup = release?.optJSONObject("release-group")
                val date = recording.optString("first-release-date")
                    .ifBlank { release?.optString("date").orEmpty() }
                val year = date.take(4).toIntOrNull() ?: 0
                val duration = recording.optLong("length", -1L).takeIf { it >= 0L }
                val baseScore = recording.optInt("score", 0)
                val adjustedScore = adjustedScore(
                    baseScore = baseScore,
                    resultTitle = title,
                    resultArtist = artist,
                    resultAlbum = album,
                    resultDurationMs = duration,
                    expectedTitle = expectedTitle,
                    expectedArtist = expectedArtist,
                    expectedAlbum = expectedAlbum,
                    expectedDurationMs = expectedDurationMs
                )
                MusicBrainzMatch(
                    recordingId = recordingId,
                    releaseId = releaseId,
                    artistId = artistId,
                    title = title,
                    artist = artist,
                    album = album,
                    year = year,
                    durationMs = duration,
                    score = adjustedScore,
                    disambiguation = recording.optString("disambiguation")
                        .takeIf { it.isNotBlank() },
                    releaseGroupId = releaseGroup?.optString("id")?.takeIf { it.isNotBlank() },
                    releaseDate = release?.optString("date")?.takeIf { it.isNotBlank() }
                        ?: recording.optString("first-release-date").takeIf { it.isNotBlank() },
                    albumArtist = MusicBrainzReleaseParser.artistCreditName(
                        release?.optJSONArray("artist-credit")
                    ).takeIf { it.isNotBlank() },
                    releaseStatus = release?.optString("status")?.takeIf { it.isNotBlank() },
                    releaseType = releaseGroup?.optString("primary-type")?.takeIf { it.isNotBlank() }
                )
            }.sortedByDescending { it.score }
        }

        private fun chooseRelease(releases: JSONArray?, expectedAlbum: String?): JSONObject? {
            if (releases == null || releases.length() == 0) return null
            val candidates = (0 until releases.length()).mapNotNull(releases::optJSONObject)
            val expected = normalize(expectedAlbum.orEmpty())
            return candidates.maxByOrNull { release ->
                var score = 0
                if (normalize(release.optString("title")) == expected && expected.isNotBlank()) score += 10
                if (release.optString("status").equals("Official", ignoreCase = true)) score += 2
                if (release.optString("date").isNotBlank()) score += 1
                when (release.optJSONObject("release-group")?.optString("primary-type")) {
                    "Album", "EP", "Single" -> score += 2
                    "Live", "Compilation", "Remix" -> score -= 1
                }
                score
            }
        }

        private fun adjustedScore(
            baseScore: Int,
            resultTitle: String,
            resultArtist: String,
            resultAlbum: String,
            resultDurationMs: Long?,
            expectedTitle: String,
            expectedArtist: String,
            expectedAlbum: String?,
            expectedDurationMs: Long?
        ): Int {
            var score = baseScore
            if (normalize(resultTitle) == normalize(expectedTitle)) score += 8
            if (normalize(resultArtist) == normalize(expectedArtist)) score += 8
            if (!expectedAlbum.isNullOrBlank() && normalize(resultAlbum) == normalize(expectedAlbum)) score += 6
            if (resultDurationMs != null && expectedDurationMs != null) {
                val delta = kotlin.math.abs(resultDurationMs - expectedDurationMs)
                score += when {
                    delta <= 2_000L -> 8
                    delta <= 5_000L -> 4
                    delta >= 30_000L -> -12
                    else -> 0
                }
            }
            return score.coerceIn(0, 100)
        }

        private fun normalize(value: String): String =
            value.lowercase().filter(Char::isLetterOrDigit)
    }
}
