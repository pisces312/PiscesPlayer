package com.pisces312.piscesplayer.data.backup.module

import android.content.Context
import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import com.pisces312.piscesplayer.data.backup.restore.PendingSongRef
import com.pisces312.piscesplayer.data.database.MusicDao
import com.pisces312.piscesplayer.data.database.SongSummary
import com.pisces312.piscesplayer.data.model.AI_MIX_SOURCE
import com.pisces312.piscesplayer.data.model.Playlist
import com.pisces312.piscesplayer.data.model.SERENDIPITY_SOURCE
import com.pisces312.piscesplayer.data.preferences.PlaylistPreferencesRepository
import com.pisces312.piscesplayer.data.preferences.UserPreferencesRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlaylistsModuleHandlerTest {

    private val context: Context = mockk(relaxed = true)
    private val playlistRepo: PlaylistPreferencesRepository = mockk(relaxed = true)
    private val userRepo: UserPreferencesRepository = mockk(relaxed = true)
    private val musicDao: MusicDao = mockk(relaxed = true)
    private val gson = GsonBuilder().serializeNulls().create()

    private val handler = PlaylistsModuleHandler(
        context = context,
        playlistPreferencesRepository = playlistRepo,
        userPreferencesRepository = userRepo,
        musicDao = musicDao,
        gson = gson
    )

    private fun summary(id: Long, title: String, artist: String) =
        SongSummary(id = id, title = title, artistName = artist, albumName = "Album", duration = 10_000L)

    private fun payload(
        playlists: List<Playlist>,
        songMetadata: Map<String, PendingSongRef>? = null
    ): String {
        val map = mutableMapOf<String, Any?>(
            "playlists" to playlists,
            "playlistSongOrderModes" to emptyMap<String, String>(),
            "playlistsSortOption" to "name_az"
        )
        if (songMetadata != null) map["songMetadata"] = songMetadata
        return gson.toJson(map)
    }

    private fun restoredPlaylist(): Playlist {
        val slot = slot<List<Playlist>>()
        coVerify(exactly = 1) { playlistRepo.replaceAllPlaylists(capture(slot)) }
        return slot.captured.single()
    }

    private fun pendingAfterRestore(): Map<String, PendingSongRef> {
        val rawSlot = slot<String>()
        coVerify { userRepo.setPlaylistRestorePending(capture(rawSlot)) }
        val type = TypeToken.getParameterized(
            Map::class.java, String::class.java, PendingSongRef::class.java
        ).type
        return gson.fromJson(rawSlot.captured, type)
    }

    private fun assertNoPending() {
        coVerify(exactly = 1) { userRepo.setPlaylistRestorePending(null) }
    }

    @Test
    fun `export includes AI and Serendipity playlists alongside local ones`() = runTest {
        coEvery { playlistRepo.getPlaylistsOnce() } returns listOf(
            Playlist("p1", "Local", listOf("10")),
            Playlist("p2", "Ai Mix", listOf("10"), source = AI_MIX_SOURCE, aiPrompt = "chill"),
            Playlist("p3", "Serendipity", listOf("10"), source = SERENDIPITY_SOURCE),
            Playlist("p4", "Jellyfin", listOf("10"), source = "JELLYFIN")
        )
        coEvery { musicDao.getAllLocalSongSummaries() } returns emptyList()
        coEvery { playlistRepo.playlistSongOrderModesFlow } returns flowOf(emptyMap())
        coEvery { playlistRepo.playlistsSortOptionFlow } returns flowOf("name_az")

        val json = handler.export()

        val exportedIds = JsonParser.parseString(json)
            .asJsonObject.getAsJsonArray("playlists")
            .map { it.asJsonObject.get("id").asString }
        assertEquals(listOf("p1", "p2", "p3"), exportedIds)
    }

    @Test
    fun `empty library keeps every reference and parks all metadata`() = runTest {
        coEvery { musicDao.getAllLocalSongSummaries() } returns emptyList()
        val playlists = listOf(Playlist("p1", "P1", listOf("10", "11")))
        val metadata = mapOf(
            "10" to PendingSongRef("Song A", "Artist A", "Album", 10_000L),
            "11" to PendingSongRef("Song B", "Artist B", "Album", 10_000L)
        )

        handler.restore(payload(playlists, metadata))

        assertEquals(listOf("10", "11"), restoredPlaylist().songIds)
        assertEquals(setOf("10", "11"), pendingAfterRestore().keys)
    }

    @Test
    fun `direct id match is kept and nothing stays pending`() = runTest {
        coEvery { musicDao.getAllLocalSongSummaries() } returns
            listOf(summary(10, "Song A", "Artist A"))
        val playlists = listOf(Playlist("p1", "P1", listOf("10")))
        val metadata = mapOf("10" to PendingSongRef("Song A", "Artist A", "Album", 10_000L))

        handler.restore(payload(playlists, metadata))

        assertEquals(listOf("10"), restoredPlaylist().songIds)
        assertNoPending()
    }

    @Test
    fun `metadata remaps backup id to current device id`() = runTest {
        coEvery { musicDao.getAllLocalSongSummaries() } returns
            listOf(summary(20, "Song A", "Artist A"))
        val playlists = listOf(Playlist("p1", "P1", listOf("10")))
        val metadata = mapOf("10" to PendingSongRef("Song A", "Artist A", "Album", 10_000L))

        handler.restore(payload(playlists, metadata))

        assertEquals(listOf("20"), restoredPlaylist().songIds)
        assertNoPending()
    }

    @Test
    fun `non-empty library keeps unmatched reference with metadata for retry`() = runTest {
        coEvery { musicDao.getAllLocalSongSummaries() } returns
            listOf(summary(30, "Other", "Other Artist"))
        val playlists = listOf(Playlist("p1", "P1", listOf("10")))
        val metadata = mapOf("10" to PendingSongRef("Song A", "Artist A", "Album", 10_000L))

        handler.restore(payload(playlists, metadata))

        assertEquals(listOf("10"), restoredPlaylist().songIds)
        assertTrue(pendingAfterRestore().containsKey("10"))
    }

    @Test
    fun `non-empty library drops unmatched reference without metadata`() = runTest {
        coEvery { musicDao.getAllLocalSongSummaries() } returns
            listOf(summary(30, "Other", "Other Artist"))
        val playlists = listOf(Playlist("p1", "P1", listOf("10")))

        handler.restore(payload(playlists, songMetadata = null))

        assertTrue(restoredPlaylist().songIds.isEmpty())
        assertNoPending()
    }

    @Test
    fun `snapshot-style payload without metadata clears pending store`() = runTest {
        coEvery { musicDao.getAllLocalSongSummaries() } returns
            listOf(summary(10, "Song A", "Artist A"))
        val playlists = listOf(Playlist("p1", "P1", listOf("10")))

        handler.restore(payload(playlists, songMetadata = null))

        coVerify(exactly = 1) { userRepo.setPlaylistRestorePending(null) }
    }
}

