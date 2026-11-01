package com.lostf1sh.pixelplayeross.data.musicbrainz

import com.lostf1sh.pixelplayeross.data.metadata.MetadataCandidate
import com.lostf1sh.pixelplayeross.data.metadata.MetadataQuery
import com.lostf1sh.pixelplayeross.data.metadata.MetadataQueryBuilder
import com.lostf1sh.pixelplayeross.data.metadata.MetadataSearchAttempt
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class MetadataSearchResult(
    val candidates: List<MetadataCandidate>,
    val matchedAttempt: MetadataSearchAttempt?
)

@Singleton
class MusicBrainzRepository @Inject constructor(
    private val api: MusicBrainzApiService
) {
    /**
     * Tries the album-qualified query first and relaxes it step by step. An empty result moves
     * to the next attempt; a network failure is reported immediately because a different query
     * cannot fix an unreachable server.
     */
    suspend fun searchCandidates(
        query: MetadataQuery,
        durationMs: Long?
    ): MetadataSearchResult = withContext(Dispatchers.IO) {
        val attempts = MetadataQueryBuilder.searchAttempts(query)
        for (attempt in attempts) {
            val matches = api.searchRecording(
                title = attempt.title,
                artist = attempt.artist,
                album = attempt.album,
                durationMs = durationMs
            )
            if (matches.isNotEmpty()) {
                return@withContext MetadataSearchResult(
                    candidates = matches.map(MusicBrainzMatch::toCandidate),
                    matchedAttempt = attempt
                )
            }
        }
        MetadataSearchResult(candidates = emptyList(), matchedAttempt = attempts.lastOrNull())
    }

    suspend fun loadDetails(
        candidate: MetadataCandidate,
        durationMs: Long?
    ): MetadataCandidate = withContext(Dispatchers.IO) {
        val releaseId = candidate.releaseId ?: return@withContext candidate
        val json = api.lookupRelease(releaseId) ?: return@withContext candidate
        val release = MusicBrainzReleaseParser.parse(json) ?: return@withContext candidate
        MusicBrainzReleaseParser.enrich(candidate, release, durationMs)
    }
}

private fun MusicBrainzMatch.toCandidate(): MetadataCandidate = MetadataCandidate(
    recordingId = recordingId,
    releaseId = releaseId,
    releaseGroupId = releaseGroupId,
    artistId = artistId,
    title = title,
    artist = artist,
    album = album,
    albumArtist = albumArtist,
    year = year,
    releaseDate = releaseDate,
    trackNumber = null,
    discNumber = null,
    genre = null,
    durationMs = durationMs,
    score = score,
    disambiguation = disambiguation,
    releaseStatus = releaseStatus,
    releaseType = releaseType,
    coverArtUrl = releaseId?.let { CoverArtArchiveService.releaseCoverUrl(it) }
)
