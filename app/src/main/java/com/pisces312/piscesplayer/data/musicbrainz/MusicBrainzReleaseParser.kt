package com.pisces312.piscesplayer.data.musicbrainz

import com.pisces312.piscesplayer.data.metadata.MetadataCandidate
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

data class MusicBrainzTrack(
    val recordingId: String?,
    val title: String,
    val number: String?,
    val lengthMs: Long?
)

data class MusicBrainzMedium(
    val position: Int,
    val tracks: List<MusicBrainzTrack>
)

data class MusicBrainzReleaseInfo(
    val releaseId: String,
    val releaseGroupId: String?,
    val title: String,
    val albumArtist: String?,
    val releaseDate: String?,
    val releaseStatus: String?,
    val releaseType: String?,
    val genre: String?,
    val media: List<MusicBrainzMedium>
)

data class MusicBrainzTrackMatch(
    val track: MusicBrainzTrack,
    val discNumber: Int
)

object MusicBrainzReleaseParser {

    fun parse(json: JSONObject): MusicBrainzReleaseInfo? {
        val releaseId = json.optString("id").takeIf { it.isNotBlank() } ?: return null
        val title = json.optString("title").takeIf { it.isNotBlank() } ?: return null

        val releaseGroup = json.optJSONObject("release-group")
        val media = buildList {
            val mediaArray = json.optJSONArray("media") ?: JSONArray()
            for (index in 0 until mediaArray.length()) {
                val medium = mediaArray.optJSONObject(index) ?: continue
                val position = medium.optInt("position", index + 1)
                val tracksArray = medium.optJSONArray("tracks") ?: JSONArray()
                val tracks = buildList {
                    for (trackIndex in 0 until tracksArray.length()) {
                        val track = tracksArray.optJSONObject(trackIndex) ?: continue
                        val recording = track.optJSONObject("recording")
                        val trackTitle = track.optString("title")
                            .ifBlank { recording?.optString("title").orEmpty() }
                        if (trackTitle.isBlank()) continue
                        add(
                            MusicBrainzTrack(
                                recordingId = recording?.optString("id")
                                    ?.takeIf { it.isNotBlank() },
                                title = trackTitle,
                                number = track.optString("number").takeIf { it.isNotBlank() },
                                lengthMs = track.optLong("length", -1L).takeIf { it >= 0L }
                                    ?: recording?.optLong("length", -1L)?.takeIf { it >= 0L }
                            )
                        )
                    }
                }
                add(MusicBrainzMedium(position = position, tracks = tracks))
            }
        }

        return MusicBrainzReleaseInfo(
            releaseId = releaseId,
            releaseGroupId = releaseGroup?.optString("id")?.takeIf { it.isNotBlank() },
            title = title,
            albumArtist = artistCreditName(json.optJSONArray("artist-credit"))
                .takeIf { it.isNotBlank() },
            releaseDate = json.optString("date").takeIf { it.isNotBlank() }
                ?: releaseGroup?.optString("first-release-date")?.takeIf { it.isNotBlank() },
            releaseStatus = json.optString("status").takeIf { it.isNotBlank() },
            releaseType = releaseGroup?.optString("primary-type")?.takeIf { it.isNotBlank() },
            genre = bestGenre(json),
            media = media
        )
    }

    fun findTrack(
        release: MusicBrainzReleaseInfo,
        recordingId: String?,
        title: String,
        expectedDurationMs: Long?
    ): MusicBrainzTrackMatch? {
        val flattened = release.media.flatMap { medium ->
            medium.tracks.map { track -> MusicBrainzTrackMatch(track, medium.position) }
        }
        if (flattened.isEmpty()) return null

        recordingId?.let { id ->
            flattened.firstOrNull { it.track.recordingId == id }?.let { return it }
        }

        val normalizedTitle = normalize(title)
        flattened.firstOrNull { normalize(it.track.title) == normalizedTitle }?.let { return it }

        if (expectedDurationMs != null) {
            flattened.firstOrNull { match ->
                val length = match.track.lengthMs ?: return@firstOrNull false
                kotlin.math.abs(length - expectedDurationMs) <= 2_000L
            }?.let { return it }
        }
        return null
    }

    fun enrich(
        candidate: MetadataCandidate,
        release: MusicBrainzReleaseInfo,
        expectedDurationMs: Long?
    ): MetadataCandidate {
        val match = findTrack(
            release = release,
            recordingId = candidate.recordingId,
            title = candidate.title,
            expectedDurationMs = expectedDurationMs
        )
        val releaseDate = release.releaseDate ?: candidate.releaseDate
        val coverUrl = candidate.coverArtUrl
            ?: release.releaseGroupId?.let { CoverArtArchiveService.releaseGroupCoverUrl(it) }

        return candidate.copy(
            releaseId = release.releaseId,
            releaseGroupId = release.releaseGroupId ?: candidate.releaseGroupId,
            album = release.title,
            albumArtist = release.albumArtist ?: candidate.albumArtist,
            year = releaseDate?.take(4)?.toIntOrNull() ?: candidate.year,
            releaseDate = releaseDate,
            trackNumber = match?.track?.number?.let(::parseTrackNumber) ?: candidate.trackNumber,
            discNumber = match?.discNumber ?: candidate.discNumber,
            genre = release.genre ?: candidate.genre,
            durationMs = match?.track?.lengthMs ?: candidate.durationMs,
            releaseStatus = release.releaseStatus ?: candidate.releaseStatus,
            releaseType = release.releaseType ?: candidate.releaseType,
            coverArtUrl = coverUrl
        )
    }

    internal fun artistCreditName(credits: JSONArray?): String {
        if (credits == null) return ""
        return buildString {
            for (index in 0 until credits.length()) {
                val credit = credits.optJSONObject(index) ?: continue
                val name = credit.optString("name").ifBlank {
                    credit.optJSONObject("artist")?.optString("name").orEmpty()
                }
                append(name)
                append(credit.optString("joinphrase"))
            }
        }
    }

    private fun bestGenre(json: JSONObject): String? {
        listOf("genres", "tags").forEach { key ->
            val array = json.optJSONArray(key) ?: return@forEach
            var bestName: String? = null
            var bestCount = Int.MIN_VALUE
            for (index in 0 until array.length()) {
                val entry = array.optJSONObject(index) ?: continue
                val name = entry.optString("name").takeIf { it.isNotBlank() } ?: continue
                val count = entry.optInt("count", 0)
                if (count > bestCount) {
                    bestCount = count
                    bestName = name
                }
            }
            if (bestName != null) return bestName
        }
        return null
    }

    private fun parseTrackNumber(raw: String): Int? =
        raw.trim().takeWhile(Char::isDigit).toIntOrNull()

    private fun normalize(value: String): String =
        value.lowercase(Locale.ROOT).filter(Char::isLetterOrDigit)
}
