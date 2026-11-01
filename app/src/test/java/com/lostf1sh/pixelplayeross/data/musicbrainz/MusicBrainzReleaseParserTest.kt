package com.lostf1sh.pixelplayeross.data.musicbrainz

import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.metadata.MetadataCandidate
import org.json.JSONObject
import org.junit.jupiter.api.Test

class MusicBrainzReleaseParserTest {

    @Test
    fun `parses multi disc release with joined artist credit`() {
        val json = JSONObject(
            """
            {
              "id": "release-id",
              "title": "Album",
              "date": "2003-07-31",
              "status": "Official",
              "artist-credit": [
                {"name": "Artist A", "joinphrase": " & "},
                {"name": "Artist B", "joinphrase": ""}
              ],
              "release-group": {"id": "group-id", "primary-type": "Album"},
              "genres": [{"name": "pop", "count": 3}, {"name": "rock", "count": 1}],
              "media": [
                {
                  "position": 1,
                  "tracks": [
                    {"number": "1", "title": "One", "length": 100000,
                     "recording": {"id": "rec-1", "title": "One"}}
                  ]
                },
                {
                  "position": 2,
                  "tracks": [
                    {"number": "5", "title": "Two", "length": 200000,
                     "recording": {"id": "rec-2", "title": "Two"}}
                  ]
                }
              ]
            }
            """.trimIndent()
        )

        val release = MusicBrainzReleaseParser.parse(json)!!

        assertThat(release.albumArtist).isEqualTo("Artist A & Artist B")
        assertThat(release.releaseType).isEqualTo("Album")
        assertThat(release.releaseGroupId).isEqualTo("group-id")
        assertThat(release.genre).isEqualTo("pop")
        assertThat(release.media).hasSize(2)
        assertThat(release.media[1].position).isEqualTo(2)
        assertThat(release.media[1].tracks.single().recordingId).isEqualTo("rec-2")
    }

    @Test
    fun `matches the candidate recording on a multi disc release`() {
        val release = MusicBrainzReleaseParser.parse(
            JSONObject(
                """
                {
                  "id": "release-id",
                  "title": "Album",
                  "date": "2003",
                  "media": [
                    {"position": 1, "tracks": [
                      {"number": "3", "title": "Other", "recording": {"id": "other"}}
                    ]},
                    {"position": 2, "tracks": [
                      {"number": "5", "title": "晴天", "recording": {"id": "target"}}
                    ]}
                  ]
                }
                """.trimIndent()
            )
        )!!

        val enriched = MusicBrainzReleaseParser.enrich(
            candidate = candidate(recordingId = "target", title = "晴天"),
            release = release,
            expectedDurationMs = null
        )

        assertThat(enriched.trackNumber).isEqualTo(5)
        assertThat(enriched.discNumber).isEqualTo(2)
    }

    @Test
    fun `falls back to title then duration when recording ids are absent`() {
        val release = MusicBrainzReleaseParser.parse(
            JSONObject(
                """
                {
                  "id": "release-id",
                  "title": "Album",
                  "media": [{"position": 1, "tracks": [
                    {"number": "1", "title": "Intro", "length": 60000},
                    {"number": "2", "title": "Song", "length": 200000}
                  ]}]
                }
                """.trimIndent()
            )
        )!!

        val byTitle = MusicBrainzReleaseParser.enrich(
            candidate = candidate(recordingId = null, title = "Song"),
            release = release,
            expectedDurationMs = null
        )
        assertThat(byTitle.trackNumber).isEqualTo(2)

        val byDuration = MusicBrainzReleaseParser.enrich(
            candidate = candidate(recordingId = null, title = "Unknown"),
            release = release,
            expectedDurationMs = 199_000
        )
        assertThat(byDuration.trackNumber).isEqualTo(2)
    }

    @Test
    fun `leaves vinyl numbering blank`() {
        val release = MusicBrainzReleaseParser.parse(
            JSONObject(
                """
                {
                  "id": "release-id",
                  "title": "Vinyl",
                  "media": [{"position": 1, "tracks": [
                    {"number": "A1", "title": "Side A", "recording": {"id": "rec-a"}}
                  ]}]
                }
                """.trimIndent()
            )
        )!!

        val enriched = MusicBrainzReleaseParser.enrich(
            candidate = candidate(recordingId = "rec-a", title = "Side A"),
            release = release,
            expectedDurationMs = null
        )

        assertThat(enriched.trackNumber).isNull()
    }

    @Test
    fun `missing date and genres do not fail parsing`() {
        val release = MusicBrainzReleaseParser.parse(
            JSONObject("""{"id": "release-id", "title": "Album"}""")
        )!!

        assertThat(release.releaseDate).isNull()
        assertThat(release.genre).isNull()
        assertThat(release.media).isEmpty()
    }

    private fun candidate(recordingId: String?, title: String) = MetadataCandidate(
        recordingId = recordingId,
        releaseId = "release-id",
        releaseGroupId = null,
        artistId = null,
        title = title,
        artist = "Artist",
        album = "Album",
        albumArtist = null,
        year = 0,
        releaseDate = null,
        trackNumber = null,
        discNumber = null,
        genre = null,
        durationMs = null,
        score = 100,
        disambiguation = null,
        releaseStatus = null,
        releaseType = null,
        coverArtUrl = null
    )
}
