package com.pisces312.piscesplayer.data.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalPlaylistDaoTest {

    private lateinit var database: PixelPlayerDatabase
    private lateinit var playlistDao: LocalPlaylistDao

    @Before
    fun createDatabase() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, PixelPlayerDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        playlistDao = database.localPlaylistDao()
    }

    @After
    fun closeDatabase() {
        database.close()
    }

    @Test
    fun repeatedTracksRemainAtEachPlaylistPosition() = runTest {
        playlistDao.replacePlaylistSongs(
            playlistId = "playlist-1",
            songIds = listOf("11", "22", "22", "33"),
        )

        val storedIds = playlistDao.observePlaylistSongs("playlist-1")
            .first()
            .map(PlaylistSongEntity::songId)

        assertThat(storedIds)
            .containsExactly(11L, 22L, 22L, 33L)
            .inOrder()
    }

    /**
     * `playlist_songs` has no foreign key to `playlists`, so this guards the DAO-level cleanup:
     * deleting one playlist must drop its own song rows and leave every other playlist alone.
     */
    @Test
    fun deletingPlaylistRemovesOnlyItsOwnSongRows() = runTest {
        playlistDao.upsertPlaylist(PlaylistEntity(id = "playlist-1", name = "AI Mix"))
        playlistDao.upsertPlaylist(PlaylistEntity(id = "playlist-2", name = "Kept"))
        playlistDao.replacePlaylistSongs("playlist-1", listOf("11", "22"))
        playlistDao.replacePlaylistSongs("playlist-2", listOf("33"))

        playlistDao.deletePlaylist("playlist-1")

        assertThat(playlistDao.getPlaylistById("playlist-1")).isNull()
        assertThat(playlistDao.observePlaylistSongs("playlist-1").first()).isEmpty()
        assertThat(playlistDao.getPlaylistById("playlist-2")).isNotNull()
        assertThat(playlistDao.observePlaylistSongs("playlist-2").first().map(PlaylistSongEntity::songId))
            .containsExactly(33L)
    }

    /**
     * The streamed thought process is stored verbatim on the playlist row and reads back with the
     * rest of the AI metadata; playlists that never had one stay null instead of empty text.
     */
    @Test
    fun aiThinkingRoundTripsThroughThePlaylistRow() = runTest {
        val thinking = "Weigh the mood first, then drop anything with a driving beat."
        playlistDao.upsertPlaylist(
            PlaylistEntity(
                id = "playlist-1",
                name = "AI Mix",
                source = "AI",
                aiPrompt = "rainy evening",
                aiThinking = thinking,
            )
        )
        playlistDao.upsertPlaylist(PlaylistEntity(id = "playlist-2", name = "Manual"))

        val rows = playlistDao.observePlaylistsWithSongs().first().associateBy { it.playlist.id }

        assertThat(rows.getValue("playlist-1").playlist.aiThinking).isEqualTo(thinking)
        assertThat(rows.getValue("playlist-2").playlist.aiThinking).isNull()
    }

    /** Deleting twice - or an id that never existed - must be a no-op rather than an error. */
    @Test
    fun deletingPlaylistTwiceIsANoOp() = runTest {
        playlistDao.upsertPlaylist(PlaylistEntity(id = "playlist-1", name = "AI Mix"))
        playlistDao.replacePlaylistSongs("playlist-1", listOf("a"))
        playlistDao.upsertPlaylist(PlaylistEntity(id = "playlist-2", name = "Kept"))

        playlistDao.deletePlaylist("playlist-1")
        playlistDao.deletePlaylist("playlist-1")
        playlistDao.deletePlaylist("never-existed")

        assertThat(playlistDao.observePlaylistsWithSongs().first().map { it.playlist.id })
            .containsExactly("playlist-2")
    }
}
