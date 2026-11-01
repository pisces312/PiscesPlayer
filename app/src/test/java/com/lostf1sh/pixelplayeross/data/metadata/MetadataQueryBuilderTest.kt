package com.lostf1sh.pixelplayeross.data.metadata

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class MetadataQueryBuilderTest {

    @Test
    fun `prefers complete tags over the file name`() {
        val query = MetadataQueryBuilder.fromSong(
            title = "晴天",
            artist = "周杰伦",
            album = "叶惠美",
            path = "/music/01 - Someone - Other.flac"
        )

        assertThat(query.title).isEqualTo("晴天")
        assertThat(query.artist).isEqualTo("周杰伦")
        assertThat(query.album).isEqualTo("叶惠美")
        assertThat(query.usedFilename).isFalse()
    }

    @Test
    fun `falls back to file name when tags are placeholders`() {
        val query = MetadataQueryBuilder.fromSong(
            title = "Unknown Title",
            artist = "",
            album = "Unknown Album",
            path = "/music/01 - Artist - Real Title.flac"
        )

        assertThat(query.title).isEqualTo("Real Title")
        assertThat(query.artist).isEqualTo("Artist")
        assertThat(query.album).isNull()
        assertThat(query.usedFilename).isTrue()
    }

    @Test
    fun `treats a tag equal to the file stem as a file name`() {
        val query = MetadataQueryBuilder.fromSong(
            title = "Artist - Title",
            artist = "",
            album = null,
            path = "/music/Artist - Title.mp3"
        )

        assertThat(query.title).isEqualTo("Title")
        assertThat(query.artist).isEqualTo("Artist")
    }

    @Test
    fun `attempts narrow from album to title only`() {
        val attempts = MetadataQueryBuilder.searchAttempts(
            MetadataQuery(title = "成都", artist = "赵雷", album = "无法长大")
        )

        assertThat(attempts).hasSize(3)
        assertThat(attempts[0]).isEqualTo(MetadataSearchAttempt("成都", "赵雷", "无法长大"))
        assertThat(attempts[1]).isEqualTo(MetadataSearchAttempt("成都", "赵雷", null))
        assertThat(attempts[2].artist).isNull()
        assertThat(attempts[2].titleOnlyFallback).isTrue()
    }

    @Test
    fun `does not duplicate attempts when artist is missing`() {
        val attempts = MetadataQueryBuilder.searchAttempts(
            MetadataQuery(title = "成都", artist = "", album = null)
        )

        assertThat(attempts).hasSize(1)
        assertThat(attempts.single().titleOnlyFallback).isTrue()
    }

    @Test
    fun `escapes lucene syntax`() {
        val attempt = MetadataSearchAttempt("Love (Live): Part II", "AC/DC", "Hits + More")

        assertThat(MetadataQueryBuilder.displayQuery(attempt))
            .isEqualTo(
                "recording:\"Love \\(Live\\)\\: Part II\" AND " +
                    "artist:\"AC\\/DC\" AND release:\"Hits \\+ More\""
            )
    }
}
