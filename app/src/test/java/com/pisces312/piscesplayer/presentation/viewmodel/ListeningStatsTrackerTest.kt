package com.pisces312.piscesplayer.presentation.viewmodel

import android.os.SystemClock
import com.google.common.truth.Truth.assertThat
import com.pisces312.piscesplayer.data.DailyMixManager
import com.pisces312.piscesplayer.data.listenbrainz.ScrobbleManager
import com.pisces312.piscesplayer.data.model.Song
import com.pisces312.piscesplayer.data.stats.PlaybackStatsRepository
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class ListeningStatsTrackerTest {

    private val dailyMixManager: DailyMixManager = mockk(relaxed = true)
    private val playbackStatsRepository: PlaybackStatsRepository = mockk(relaxed = true)
    private val scrobbleManager: ScrobbleManager = mockk(relaxed = true)

    @BeforeEach
    fun setUp() {
        mockkStatic(SystemClock::class)
    }

    @AfterEach
    fun tearDown() {
        unmockkStatic(SystemClock::class)
    }

    @Test
    fun `finalizeCurrentSession preserves listening longer than track duration`() {
        val tracker = ListeningStatsTracker(
            dailyMixManager = dailyMixManager,
            playbackStatsRepository = playbackStatsRepository,
            scrobbleManager = scrobbleManager
        )
        val song = song(
            songId = "looped-song",
            durationMs = TimeUnit.MINUTES.toMillis(3)
        )
        val listenedMs = TimeUnit.MINUTES.toMillis(12)

        every { SystemClock.elapsedRealtime() } returnsMany listOf(
            1_000L,
            1_000L + listenedMs
        )

        tracker.onSongChanged(
            song = song,
            positionMs = 0L,
            durationMs = song.duration,
            isPlaying = true
        )
        tracker.finalizeCurrentSession(forceSynchronousPersistence = true)

        coVerify(timeout = 2_000) {
            dailyMixManager.recordPlay(song.id, listenedMs, any())
        }
        coVerify(timeout = 2_000) {
            playbackStatsRepository.recordPlayback(song.id, listenedMs, any())
        }
    }

    @Test
    fun `onProgress accumulates incremental listening time`() {
        val tracker = ListeningStatsTracker(
            dailyMixManager = dailyMixManager,
            playbackStatsRepository = playbackStatsRepository,
            scrobbleManager = scrobbleManager
        )
        val song = song(songId = "song-1")
        val firstChunkMs = 7_000L
        val secondChunkMs = 8_000L
        val expectedDurationMs = firstChunkMs + secondChunkMs

        every { SystemClock.elapsedRealtime() } returnsMany listOf(
            5_000L,
            5_000L + firstChunkMs,
            5_000L + firstChunkMs + secondChunkMs
        )

        tracker.onSongChanged(
            song = song,
            positionMs = 0L,
            durationMs = song.duration,
            isPlaying = true
        )
        tracker.onProgress(positionMs = firstChunkMs, isPlaying = true)
        tracker.finalizeCurrentSession(forceSynchronousPersistence = true)

        coVerify(timeout = 2_000) {
            playbackStatsRepository.recordPlayback(song.id, expectedDurationMs, any())
        }
        assertThat(expectedDurationMs).isGreaterThan(TimeUnit.SECONDS.toMillis(5))
    }

    @Test
    fun `pendingFragment reports the whole unpersisted session and stays idempotent`() {
        // 刷新按钮不再落盘（删除 flushCurrentSession 之后），所以在途片段是「本 session 从开始
        // 至今的整段」；它必须只读且幂等，否则每次刷新都会把同一次播放重复计入。
        val tracker = ListeningStatsTracker(
            dailyMixManager = dailyMixManager,
            playbackStatsRepository = playbackStatsRepository,
            scrobbleManager = scrobbleManager
        )
        val song = song(songId = "song-1")
        var realtime = 0L
        every { SystemClock.elapsedRealtime() } answers { realtime }

        tracker.onSongChanged(
            song = song,
            positionMs = 0L,
            durationMs = song.duration,
            isPlaying = true
        )

        // 听 6 秒：还没落盘，片段就是这 6 秒。
        realtime = TimeUnit.SECONDS.toMillis(6)
        val first = requireNotNull(tracker.pendingFragment())
        assertThat(first.songId).isEqualTo(song.id)
        assertThat(first.durationMs).isEqualTo(TimeUnit.SECONDS.toMillis(6))

        // 只读：固定 now 连取两次结果必须一致。若它像 accumulateRealtimeListening 那样写回
        // session.accumulatedListeningMs，第二次就会翻倍。
        val frozen = tracker.pendingFragment(nowMillis = 1_000_000L)
        assertThat(tracker.pendingFragment(nowMillis = 1_000_000L)).isEqualTo(frozen)

        // 继续听到 12 秒：片段是整段，而不是 6 + 12 的累加 —— 这正是「连点刷新不翻倍」的保证。
        realtime = TimeUnit.SECONDS.toMillis(12)
        assertThat(requireNotNull(tracker.pendingFragment()).durationMs)
            .isEqualTo(TimeUnit.SECONDS.toMillis(12))

        // finalize 落盘的也是这整段：一条事件、一次播放，随后没有可叠加的片段。
        tracker.finalizeCurrentSession(forceSynchronousPersistence = true)
        coVerify(timeout = 2_000) {
            playbackStatsRepository.recordPlayback(song.id, TimeUnit.SECONDS.toMillis(12), any())
        }
        assertThat(tracker.pendingFragment()).isNull()
    }

    private fun song(songId: String, durationMs: Long = 5 * 60 * 1000L): Song = Song(
        id = songId,
        title = "Song $songId",
        artist = "Artist",
        artistId = 1L,
        album = "Album",
        albumId = 1L,
        path = "/music/$songId.mp3",
        contentUriString = "content://media/external/audio/media/$songId",
        albumArtUriString = null,
        duration = durationMs,
        mimeType = "audio/mpeg",
        bitrate = 320_000,
        sampleRate = 44_100
    )
}
