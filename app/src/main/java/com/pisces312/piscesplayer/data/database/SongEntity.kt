package com.pisces312.piscesplayer.data.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.pisces312.piscesplayer.data.model.ArtistRef
import com.pisces312.piscesplayer.data.model.Song
import com.pisces312.piscesplayer.utils.LocalArtworkUri
import com.pisces312.piscesplayer.utils.normalizeMetadataText
import com.pisces312.piscesplayer.utils.normalizeMetadataTextOrEmpty
import org.json.JSONArray
import org.json.JSONObject

/** Integer constants for the `source_type` column — faster than LIKE checks on URI scheme. */
object SourceType {
    const val LOCAL = 0
    const val NAVIDROME = 5
    const val JELLYFIN = 6

    /** Built-in classical MIDI library (assets/classical.zip, extracted to filesDir). */
    const val CLASSICAL = 7

    /**
     * User-provided MIDI files (SAF-scanned directories and individually added documents,
     * referenced by their document URI — see the MIDI library plan §C). Normal user
     * content: counts in stats/playlists/search like local songs; NOT app-provided.
     */
    const val USER_MIDI = 8

    /**
     * App-provided content packs (随 app 分发的内置内容).
     *
     * Single policy rule for everything in this list: app-provided content stays playable,
     * searchable, addable to playlists and eligible for quiz / rhythm pickers, but it is
     * **hidden from the library screen** — every library tab, its counts and its drill-downs
     * carry `source_type NOT IN (7)` (see [MusicDao]), so 音乐学习 is the only way in. It is
     * also never written into user-data pools and never competes as a recommendation
     * candidate — i.e. no listening stats, no recently played, no AI / NLP / smart playlist
     * sampling, no daily mix / your mix, no scrobbles. The reasons: a bundled pack is not
     * the user's library and would drown it in the pools, and user data must never depend on
     * shipped content that resource updates may replace or remove.
     *
     * Future built-in packs register their source type here and everything downstream
     * ([isAppProvided], [Song.isBuiltIn], the home-mix SQL exclusion, the stats gate in
     * MusicService) follows automatically — except the library-tab SQL predicates, which are
     * literal `NOT IN (7)` and must be updated alongside a new pack.
     */
    val APP_PROVIDED: List<Int> = listOf(CLASSICAL)

    /** Whether [sourceType] is an app-provided content pack. See [APP_PROVIDED]. */
    fun isAppProvided(sourceType: Int): Boolean = sourceType in APP_PROVIDED

    /**
     * URI-level view of [isAppProvided] for code paths that only hold a content URI
     * (e.g. MediaItem extras in MusicService).
     */
    fun isAppProvidedContentUri(contentUriString: String?): Boolean =
        contentUriString != null && isAppProvided(fromContentUri(contentUriString))

    /** Derive source type from a content URI string (fallback for migration / conversion). */
    fun fromContentUri(uri: String): Int = when {
        uri.startsWith("navidrome://") -> NAVIDROME
        uri.startsWith("jellyfin://") -> JELLYFIN
        uri.startsWith("piscesmidi://") -> CLASSICAL
        else -> LOCAL
    }
}

@Entity(
    tableName = "songs",
    indices = [
        Index(value = ["title"], unique = false),
        Index(value = ["album_id"], unique = false),
        Index(value = ["artist_id"], unique = false),
        Index(value = ["artist_name"], unique = false),
        Index(value = ["genre"], unique = false),
        Index(value = ["parent_directory_path"], unique = false),
        Index(value = ["file_path"], unique = false),
        Index(value = ["content_uri_string"], unique = false),
        Index(value = ["date_added"], unique = false),
        Index(value = ["duration"], unique = false),
        Index(value = ["source_type"], unique = false),
        Index(value = ["album_artist_id"], unique = false),
        Index(value = ["parent_directory_path", "source_type", "album_id"], unique = false),
        Index(value = ["parent_directory_path", "source_type", "id"], unique = false)
    ],
    foreignKeys = [
        ForeignKey(
            entity = AlbumEntity::class,
            parentColumns = ["id"],
            childColumns = ["album_id"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = ArtistEntity::class,
            parentColumns = ["id"],
            childColumns = ["artist_id"],
            // NOT NULL column — SET_NULL would abort on delete; orphans cleaned by deleteOrphanedArtists().
            onDelete = ForeignKey.NO_ACTION
        )
    ]
)
data class SongEntity(
    @PrimaryKey val id: Long,
    @ColumnInfo(name = "title") val title: String,
    @ColumnInfo(name = "artist_name") val artistName: String,
    @ColumnInfo(name = "artist_id") val artistId: Long,
    @ColumnInfo(name = "album_artist") val albumArtist: String? = null,
    @ColumnInfo(name = "album_artist_id", defaultValue = "0") val albumArtistId: Long = 0L,
    @ColumnInfo(name = "album_name") val albumName: String,
    @ColumnInfo(name = "album_id") val albumId: Long,
    @ColumnInfo(name = "content_uri_string") val contentUriString: String,
    @ColumnInfo(name = "album_art_uri_string") val albumArtUriString: String?,
    @ColumnInfo(name = "duration") val duration: Long,
    @ColumnInfo(name = "genre") val genre: String?,
    @ColumnInfo(name = "file_path") val filePath: String,
    @ColumnInfo(name = "parent_directory_path") val parentDirectoryPath: String,
    @ColumnInfo(name = "track_number", defaultValue = "0") val trackNumber: Int = 0,
    @ColumnInfo(name = "disc_number", defaultValue = "null") val discNumber: Int? = null,
    @ColumnInfo(name = "year", defaultValue = "0") val year: Int = 0,
    /**
     * Release date, standardized `yyyy-MM-dd` (year-only tags become `yyyy-01-01`).
     * NULL = file never read (backfill pending); "0" = read once, no date info available.
     */
    @ColumnInfo(name = "release_date") val releaseDate: String? = null,
    @ColumnInfo(name = "date_added", defaultValue = "0") val dateAdded: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "mime_type") val mimeType: String? = null,
    @ColumnInfo(name = "bitrate") val bitrate: Int? = null,
    @ColumnInfo(name = "sample_rate") val sampleRate: Int? = null,
    @ColumnInfo(name = "artists_json") val artistsJson: String? = null,
    @ColumnInfo(name = "source_type", defaultValue = "0") val sourceType: Int = SourceType.LOCAL,
    @ColumnInfo(name = "media_store_date_added", defaultValue = "0") val mediaStoreDateAdded: Long = 0L,
    @ColumnInfo(name = "media_store_date_modified", defaultValue = "0") val mediaStoreDateModified: Long = 0L,
    @ColumnInfo(name = "title_user_edited", defaultValue = "0") val titleUserEdited: Boolean = false,
    @ColumnInfo(name = "artist_user_edited", defaultValue = "0") val artistUserEdited: Boolean = false,
    @ColumnInfo(name = "album_user_edited", defaultValue = "0") val albumUserEdited: Boolean = false,
    @ColumnInfo(name = "genre_user_edited", defaultValue = "0") val genreUserEdited: Boolean = false,
    @ColumnInfo(name = "mb_recording_id") val mbRecordingId: String? = null,
    @ColumnInfo(name = "mb_release_id") val mbReleaseId: String? = null,
    @ColumnInfo(name = "mb_artist_id") val mbArtistId: String? = null
)

private fun SongEntity.toSongInternal(artists: List<ArtistRef>): Song {
    return Song(
        id = this.id.toString(),
        title = this.title.normalizeMetadataTextOrEmpty(),
        artist = this.artistName.normalizeMetadataTextOrEmpty(),
        artistId = this.artistId,
        artists = artists,
        album = this.albumName.normalizeMetadataTextOrEmpty(),
        albumId = this.albumId,
        albumArtist = this.albumArtist?.normalizeMetadataText(),
        path = this.filePath,
        contentUriString = this.contentUriString,
        albumArtUriString = LocalArtworkUri.resolveSongArtworkUri(
            storedUri = this.albumArtUriString,
            songId = this.id,
            contentUriString = this.contentUriString
        ),
        duration = this.duration,
        genre = this.genre.normalizeMetadataText(),
        lyrics = null,
        isFavorite = false,
        trackNumber = this.trackNumber,
        discNumber = this.discNumber,
        dateAdded = this.dateAdded,
        year = this.year,
        releaseDate = this.releaseDate,
        navidromeId = if (this.contentUriString.startsWith("navidrome://")) {
            this.contentUriString.removePrefix("navidrome://")
        } else null,
        jellyfinId = if (this.contentUriString.startsWith("jellyfin://")) {
            this.contentUriString.removePrefix("jellyfin://")
        } else null,
        mimeType = this.mimeType,
        bitrate = this.bitrate,
        sampleRate = this.sampleRate
    )
}

fun SongEntity.toSong(): Song {
    val artists = parseArtistsJson(this.artistsJson)
    return toSongInternal(artists = artists)
}

/**
 * Parses the artists_json column back into a list of ArtistRef.
 */
private fun parseArtistsJson(json: String?): List<ArtistRef> {
    if (json.isNullOrBlank()) return emptyList()
    return try {
        val arr = JSONArray(json)
        (0 until arr.length()).map { i ->
            val obj = arr.getJSONObject(i)
            ArtistRef(
                id = obj.getLong("id"),
                name = obj.getString("name"),
                isPrimary = obj.optBoolean("primary", false)
            )
        }
    } catch (_: Exception) {
        emptyList()
    }
}

/**
 * Serializes a list of ArtistRef to JSON for storage in artists_json column.
 */
fun serializeArtistRefs(artists: List<ArtistRef>): String {
    val arr = JSONArray()
    artists.forEach { ref ->
        arr.put(JSONObject().apply {
            put("id", ref.id)
            put("name", ref.name)
            put("primary", ref.isPrimary)
        })
    }
    return arr.toString()
}

fun List<SongEntity>.toSongs(): List<Song> {
    return this.map { it.toSong() }
}

fun Song.toEntity(filePathFromMediaStore: String, parentDirFromMediaStore: String): SongEntity {
    return SongEntity(
        id = this.id.toLong(),
        title = this.title,
        artistName = this.artist,
        artistId = this.artistId,
        albumArtist = this.albumArtist,
        albumName = this.album,
        albumId = this.albumId,
        contentUriString = this.contentUriString,
        albumArtUriString = this.albumArtUriString,
        duration = this.duration,
        genre = this.genre,
        trackNumber = this.trackNumber,
        discNumber = this.discNumber,
        filePath = filePathFromMediaStore,
        parentDirectoryPath = parentDirFromMediaStore,
        dateAdded = this.dateAdded,
        year = this.year,
        releaseDate = this.releaseDate,
        mimeType = this.mimeType,
        bitrate = this.bitrate,
        sampleRate = this.sampleRate,
        sourceType = SourceType.fromContentUri(this.contentUriString)
    )
}

/** Lightweight projection for backup song matching. */
data class SongSummary(
    val id: Long,
    val title: String,
    @ColumnInfo(name = "artist_name") val artistName: String,
    @ColumnInfo(name = "album_name") val albumName: String,
    val duration: Long
)

/** Lightweight projection for the release-date backfill pass. */
data class SongReleaseDateStub(
    val id: Long,
    @ColumnInfo(name = "file_path") val filePath: String
)

/**
 * Merges an incoming sync row with the existing database row so upserts cannot wipe
 * user-owned or first-seen fields.
 *
 * Preserved from [this] (existing):
 * - `*_user_edited` flags and, when set, the corresponding display columns
 * - `date_added` (first-added time)
 * - MusicBrainz ids (only filled when previously empty)
 *
 * Everything else is taken from [incoming] (MediaStore / cloud scan).
 */
fun SongEntity.mergingUserOwnedFieldsFrom(existing: SongEntity): SongEntity {
    return copy(
        title = if (existing.titleUserEdited) existing.title else title,
        artistName = if (existing.artistUserEdited) existing.artistName else artistName,
        albumName = if (existing.albumUserEdited) existing.albumName else albumName,
        genre = if (existing.genreUserEdited) existing.genre else genre,
        dateAdded = existing.dateAdded,
        titleUserEdited = existing.titleUserEdited,
        artistUserEdited = existing.artistUserEdited,
        albumUserEdited = existing.albumUserEdited,
        genreUserEdited = existing.genreUserEdited,
        mbRecordingId = existing.mbRecordingId ?: mbRecordingId,
        mbReleaseId = existing.mbReleaseId ?: mbReleaseId,
        mbArtistId = existing.mbArtistId ?: mbArtistId,
    )
}

fun Song.toEntityWithoutPaths(): SongEntity {
    return SongEntity(
        id = this.id.toLong(),
        title = this.title,
        artistName = this.artist,
        artistId = this.artistId,
        albumArtist = this.albumArtist,
        albumName = this.album,
        albumId = this.albumId,
        contentUriString = this.contentUriString,
        albumArtUriString = this.albumArtUriString,
        duration = this.duration,
        genre = this.genre,
        trackNumber = this.trackNumber,
        discNumber = this.discNumber,
        filePath = "",
        parentDirectoryPath = "",
        dateAdded = this.dateAdded,
        year = this.year,
        releaseDate = this.releaseDate,
        mimeType = this.mimeType,
        bitrate = this.bitrate,
        sampleRate = this.sampleRate,
        sourceType = SourceType.fromContentUri(this.contentUriString)
    )
}
