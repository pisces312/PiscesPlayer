package com.pisces312.piscesplayer.presentation.viewmodel

import com.google.common.truth.Truth.assertThat
import com.pisces312.piscesplayer.data.classical.ClassicalWork
import com.pisces312.piscesplayer.data.classical.NameSet
import com.pisces312.piscesplayer.data.model.Album
import com.pisces312.piscesplayer.data.model.Song
import org.junit.jupiter.api.Test

class ClassicalWorkSetsTest {

    @Test
    fun `groups works across composers preserving track order`() {
        val workA = work("opus:op. 1", "Op. 1", trackIds = listOf(1, 2))
        val workB = work("opus:op. 2", "Op. 2", trackIds = listOf(3))
        val works = mapOf(1L to workA, 2L to workA, 3L to workB)
        // Deliberately unsorted input: output tracks follow id order.
        val songs = listOf(
            song("2", albumId = 100),
            song("1", albumId = 100),
            song("3", albumId = 100),
        )
        val albums = listOf(album(100))

        val result = buildAllWorkSets(albums, songs) { id -> works[id] }

        assertThat(result.map { it.work.key }).containsExactly("opus:op. 1", "opus:op. 2").inOrder()
        assertThat(result.first().tracks.map { it.id }).containsExactly("1", "2").inOrder()
        assertThat(result[1].tracks.map { it.id }).containsExactly("3").inOrder()
    }

    @Test
    fun `tracks missing from catalog fall into per-track fallback works`() {
        val workA = work("opus:op. 1", "Op. 1", trackIds = listOf(1))
        val works = mapOf(1L to workA)
        val songs = listOf(song("1", albumId = 100), song("9", albumId = 100))
        val albums = listOf(album(100))

        val result = buildAllWorkSets(albums, songs) { id -> works[id] }

        assertThat(result.map { it.work.key }).containsExactly("opus:op. 1", "fallback:9").inOrder()
        val fallback = result[1]
        assertThat(fallback.tracks.map { it.id }).containsExactly("9")
        assertThat(fallback.work.names.original).isEqualTo("Song 9")
    }

    @Test
    fun `composers without classical songs contribute nothing`() {
        val songs = listOf(song("1", albumId = 100), song("2", albumId = 100))
        val albums = listOf(album(100), album(200))

        val result = buildAllWorkSets(albums, songs) { null }

        assertThat(result).hasSize(2)
        assertThat(result.all { it.albumId == 100L }).isTrue()
    }

    @Test
    fun `songs from albums outside the list are ignored`() {
        val songs = listOf(song("1", albumId = 100), song("2", albumId = 999))
        val albums = listOf(album(100))

        val result = buildAllWorkSets(albums, songs) { null }

        assertThat(result.map { it.albumId }).containsExactly(100L)
        assertThat(result.flatMap { it.tracks }.map { it.id }).containsExactly("1")
    }

    private fun work(key: String, opus: String, trackIds: List<Long>) = ClassicalWork(
        key = key,
        names = NameSet(original = "Work $key", en = "Work $key", zh = ""),
        opus = opus,
        trackIds = trackIds,
    )

    private fun album(id: Long) = Album.empty().copy(id = id, artist = "Composer $id")

    private fun song(id: String, albumId: Long, title: String = "Song $id") =
        Song.emptySong().copy(id = id, albumId = albumId, title = title)
}
