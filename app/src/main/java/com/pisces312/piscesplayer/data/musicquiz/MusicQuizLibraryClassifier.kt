package com.pisces312.piscesplayer.data.musicquiz

import android.net.Uri
import com.pisces312.piscesplayer.data.model.Song
import com.pisces312.piscesplayer.data.offline.CloudOfflineRepository
import java.io.File

/**
 * Stateless music-quiz library classification: eligibility, classical flags and kids
 * flags over one library snapshot. Pure input -> output, no database access and no
 * Android dependencies beyond [Song] — safe to run on any dispatcher.
 *
 * Lyrics are deliberately not consulted (2026-11-18 decision): kids detection is
 * catalog + performer-label + metadata keywords only, which makes classification a
 * pure function of the snapshot and removes the lyrics-database dependency entirely.
 */
internal object MusicQuizLibraryClassifier {

    /** Cheap pass: cloud filter + mapping + eligibility. No string classification. */
    fun base(songs: List<Song>): List<MusicQuizTrack> = songs.asSequence()
        .filterNot(CloudOfflineRepository::isCloudSong)
        .map { it.toMusicQuizTrack() }
        .let { MusicQuizQuestionFactory.eligibleTracks(it.toList()) }

    /** Classification pass: classical flags + kids flags over [base]. */
    fun classify(base: List<MusicQuizTrack>): List<MusicQuizTrack> {
        val withClassical = base.map { track ->
            if (track.isClassical) {
                track
            } else {
                track.copy(isClassical = ClassicalSongClassifier.isClassical(track))
            }
        }
        val kidsIds = kidsIdsOf(withClassical)
        return withClassical.map { it.copy(isKidsSong = it.id in kidsIds) }
    }

    /**
     * Tier A first (catalog names + the always-on performer-label rule); keyword
     * fallback only when those hits cannot fill a kids pool. The fallback matches on
     * title/artist/album metadata alone — no lyrics lookup.
     */
    private fun kidsIdsOf(tracks: List<MusicQuizTrack>): Set<String> {
        val tierA = tracks.filter {
            KidsSongClassifier.matchesCatalog(it.title) ||
                KidsSongClassifier.matchesArtistLabel(it.artist)
        }
        val kidsEnough = tierA.size >= MUSIC_QUIZ_ROUND_COUNT &&
            tierA.map { MusicQuizQuestionFactory.normalizedTitle(it.title) }.distinct().size >=
            MUSIC_QUIZ_OPTION_COUNT
        return if (kidsEnough) {
            tierA.mapTo(mutableSetOf()) { it.id }
        } else {
            val tierAIds = tierA.mapTo(mutableSetOf()) { it.id }
            tracks.asSequence()
                .filter { it.id !in tierAIds }
                .filter {
                    KidsSongClassifier.matchesKeywords(
                        title = it.title,
                        artist = it.artist,
                        album = it.album,
                    )
                }
                .mapTo(tierAIds) { it.id }
        }
    }

    private fun Song.toMusicQuizTrack(): MusicQuizTrack {
        val playbackUri = contentUriString.takeIf(String::isNotBlank)
            ?: path.takeIf(String::isNotBlank)?.let { Uri.fromFile(File(it)).toString() }
            .orEmpty()
        return MusicQuizTrack(
            id = id,
            title = title,
            artist = displayArtist,
            albumArtUriString = albumArtUriString,
            durationMs = duration,
            playbackUri = playbackUri,
            year = year,
            genre = genre,
            album = album,
        )
    }
}
