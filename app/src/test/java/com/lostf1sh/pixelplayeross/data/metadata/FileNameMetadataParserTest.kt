package com.lostf1sh.pixelplayeross.data.metadata

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class FileNameMetadataParserTest {

    @Test
    fun `parses track artist and title`() {
        val parsed = FileNameMetadataParser.parse("01 - Artist - Title.flac")

        assertThat(parsed).isNotNull()
        assertThat(parsed!!.trackNumber).isEqualTo(1)
        assertThat(parsed.artist).isEqualTo("Artist")
        assertThat(parsed.title).isEqualTo("Title")
    }

    @Test
    fun `parses artist album track and title`() {
        val parsed = FileNameMetadataParser.parse("Artist - Album - 03 - Title.mp3")

        assertThat(parsed).isNotNull()
        assertThat(parsed!!.artist).isEqualTo("Artist")
        assertThat(parsed.album).isEqualTo("Album")
        assertThat(parsed.trackNumber).isEqualTo(3)
        assertThat(parsed.title).isEqualTo("Title")
    }

    @Test
    fun `parses artist and title without track`() {
        val parsed = FileNameMetadataParser.parse("Artist - Title")

        assertThat(parsed).isNotNull()
        assertThat(parsed!!.artist).isEqualTo("Artist")
        assertThat(parsed.title).isEqualTo("Title")
        assertThat(parsed.trackNumber).isNull()
    }

    @Test
    fun `parses title only`() {
        val parsed = FileNameMetadataParser.parse("晴天")

        assertThat(parsed).isNotNull()
        assertThat(parsed!!.title).isEqualTo("晴天")
        assertThat(parsed.artist).isNull()
    }

    @Test
    fun `strips bitrate noise but keeps version words`() {
        val parsed = FileNameMetadataParser.parse("Artist - Title (Live) [320k]")

        assertThat(parsed).isNotNull()
        assertThat(parsed!!.title).isEqualTo("Title (Live)")
    }

    @Test
    fun `keeps remix and instrumental markers`() {
        assertThat(FileNameMetadataParser.parse("Artist - Song (Remix)")!!.title)
            .isEqualTo("Song (Remix)")
        assertThat(FileNameMetadataParser.parse("Artist - Song 伴奏")!!.title)
            .isEqualTo("Song 伴奏")
    }

    @Test
    fun `normalizes full width separators`() {
        val parsed = FileNameMetadataParser.parse("周杰倫·晴天")

        assertThat(parsed).isNotNull()
        assertThat(parsed!!.artist).isEqualTo("周杰倫")
        assertThat(parsed.title).isEqualTo("晴天")
    }
}
