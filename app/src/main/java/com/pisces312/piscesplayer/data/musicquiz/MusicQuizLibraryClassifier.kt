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

    /**
     * 逐曲目分类证据：只依赖该曲目自身元数据 + 内置目录的纯函数结果。
     * 儿童判定的「库级闸门」（tierA 是否够量决定要不要关键词兜底）不是逐曲目纯函数，
     * 所以缓存证据而非最终 flag，每次从证据重算闸门（见 [applyEvidence]）。
     */
    data class TrackEvidence(
        val isClassical: Boolean,
        val kidsTierA: Boolean,
        val kidsKeyword: Boolean,
    )

    fun evidenceOf(track: MusicQuizTrack): TrackEvidence = TrackEvidence(
        isClassical = track.isClassical || ClassicalSongClassifier.isClassical(track),
        kidsTierA = KidsSongClassifier.matchesCatalog(track.title) ||
            KidsSongClassifier.matchesArtistLabel(track.artist),
        kidsKeyword = KidsSongClassifier.matchesKeywords(
            title = track.title,
            artist = track.artist,
            album = track.album,
        ),
    )

    /** Classification pass: classical flags + kids flags over [base]. */
    fun classify(base: List<MusicQuizTrack>): List<MusicQuizTrack> =
        applyEvidence(base, base.map(::evidenceOf))

    /** 从逐曲目证据组装最终 flag（含儿童库级闸门），与 [classify] 完全同语义。 */
    fun applyEvidence(
        base: List<MusicQuizTrack>,
        evidence: List<TrackEvidence>,
    ): List<MusicQuizTrack> {
        val withClassical = base.mapIndexed { index, track ->
            if (track.isClassical) {
                track
            } else {
                track.copy(isClassical = evidence[index].isClassical)
            }
        }
        val tierA = withClassical.filterIndexed { index, _ -> evidence[index].kidsTierA }
        val kidsEnough = tierA.size >= MUSIC_QUIZ_ROUND_COUNT &&
            tierA.map { MusicQuizQuestionFactory.normalizedTitle(it.title) }.distinct().size >=
            MUSIC_QUIZ_OPTION_COUNT
        val kidsIds = if (kidsEnough) {
            tierA.mapTo(mutableSetOf()) { it.id }
        } else {
            val ids = tierA.mapTo(mutableSetOf()) { it.id }
            withClassical.forEachIndexed { index, track ->
                if (track.id !in ids && evidence[index].kidsKeyword) ids += track.id
            }
            ids
        }
        return withClassical.map { it.copy(isKidsSong = it.id in kidsIds) }
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
