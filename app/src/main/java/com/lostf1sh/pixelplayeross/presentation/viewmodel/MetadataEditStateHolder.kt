package com.lostf1sh.pixelplayeross.presentation.viewmodel

import android.content.Context
import android.net.Uri
import com.lostf1sh.pixelplayeross.data.database.MusicDao
import com.lostf1sh.pixelplayeross.data.media.CoverArtUpdate
import com.lostf1sh.pixelplayeross.data.media.CustomMetadataChanges
import com.lostf1sh.pixelplayeross.data.media.CustomMetadataFieldUpdate
import com.lostf1sh.pixelplayeross.data.media.ImageCacheManager
import com.lostf1sh.pixelplayeross.data.media.MetadataEditError
import com.lostf1sh.pixelplayeross.data.media.SongMetadataEditor
import com.lostf1sh.pixelplayeross.data.metadata.MetadataApplyTarget
import com.lostf1sh.pixelplayeross.data.metadata.MetadataCandidate
import com.lostf1sh.pixelplayeross.data.metadata.MetadataFieldSelection
import com.lostf1sh.pixelplayeross.data.metadata.MetadataMergePolicy
import com.lostf1sh.pixelplayeross.data.model.Lyrics
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.data.offline.CloudOfflineRepository
import com.lostf1sh.pixelplayeross.data.repository.MusicRepository
import com.lostf1sh.pixelplayeross.utils.FileDeletionUtils
import com.lostf1sh.pixelplayeross.utils.LyricsUtils
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject

class MetadataEditStateHolder @Inject constructor(
    private val songMetadataEditor: SongMetadataEditor,
    private val musicRepository: MusicRepository,
    private val musicDao: MusicDao,
    private val imageCacheManager: ImageCacheManager,
    private val themeStateHolder: ThemeStateHolder,
    @ApplicationContext private val context: Context
) {

    data class MetadataEditResult(
        val success: Boolean,
        val updatedSong: Song? = null,
        val updatedAlbumArtUri: String? = null,
        val parsedLyrics: Lyrics? = null,
        val error: MetadataEditError? = null,
        val errorMessage: String? = null
    ) {
        /**
         * Returns a user-friendly error message based on the error type
         */
        fun getUserFriendlyErrorMessage(): String {
            return when (error) {
                MetadataEditError.FILE_NOT_FOUND -> "The song file could not be found. It may have been moved or deleted."
                MetadataEditError.NO_WRITE_PERMISSION -> "Cannot edit this file. You may need to grant additional permissions or the file is on read-only storage."
                MetadataEditError.INVALID_INPUT -> errorMessage ?: "Invalid input provided."
                MetadataEditError.UNSUPPORTED_FORMAT -> "This file format is not supported for editing."
                MetadataEditError.TAGLIB_ERROR -> "Failed to write metadata to the file. The file may be corrupted."
                MetadataEditError.TIMEOUT -> "The operation took too long and was cancelled."
                MetadataEditError.FILE_CORRUPTED -> "The file appears to be corrupted or in an unsupported format."
                MetadataEditError.IO_ERROR -> "An error occurred while accessing the file. Please try again."
                MetadataEditError.UNKNOWN, null -> errorMessage ?: "An unknown error occurred while editing metadata."
            }
        }
    }

    suspend fun saveMetadata(
        song: Song,
        newTitle: String,
        newArtist: String,
        newAlbum: String,
        newAlbumArtist: String,
        newComposer: String?,
        newGenre: String?,
        newLyrics: String?,
        newTrackNumber: Int,
        newDiscNumber: Int?,
        newReplayGainTrackGainDb: String? = null,
        newReplayGainAlbumGainDb: String? = null,
        coverArtUpdate: CoverArtUpdate?,
        customMetadataChanges: CustomMetadataChanges = CustomMetadataChanges()
    ): MetadataEditResult = withContext(Dispatchers.IO) {
        
        Timber.tag("MetadataEditStateHolder").d("Starting saveMetadata for: ${song.title}")

        val finalCoverArtUpdate = if (coverArtUpdate == null) {
            val existingMetadata = try {
                 com.lostf1sh.pixelplayeross.data.media.AudioMetadataReader.read(java.io.File(song.path))
            } catch (e: Exception) {
                null
            }
            if (existingMetadata?.artwork != null) {
                Timber.tag("MetadataEditStateHolder").d("Preserving existing embedded artwork")
                CoverArtUpdate(existingMetadata.artwork.bytes, existingMetadata.artwork.mimeType ?: "image/jpeg")
            } else {
                null
            }
        } else if (coverArtUpdate.isDeletion) {
            Timber.tag("MetadataEditStateHolder").d("Artwork deletion requested, skipping preservation")
            coverArtUpdate
        } else {
            coverArtUpdate
        }

        // null means "leave lyrics untouched"; an empty string is an explicit clear.
        val trimmedLyrics: String? = newLyrics?.trim()
        val normalizedLyrics = trimmedLyrics?.takeIf { it.isNotBlank() }
        val parsedLyrics = normalizedLyrics?.let { LyricsUtils.parseLyrics(it) }
        val resolvedSongId = resolveSongIdForMetadataEdit(song)

        if (resolvedSongId == null) {
            Timber.tag("MetadataEditStateHolder").w("Cannot edit metadata for non-numeric song id: ${song.id}")
            return@withContext MetadataEditResult(
                success = false,
                error = MetadataEditError.INVALID_INPUT,
                errorMessage = "This song source does not support metadata editing."
            )
        }

        val result = songMetadataEditor.editSongMetadata(
            newTitle = newTitle,
            newArtist = newArtist,
            newAlbum = newAlbum,
            newAlbumArtist = newAlbumArtist.trim().takeIf { it.isNotBlank() },
            newComposer = newComposer?.trim(),
            newGenre = newGenre,
            newLyrics = trimmedLyrics,
            newTrackNumber = newTrackNumber,
            newDiscNumber = newDiscNumber,
            newReplayGainTrackGainDb = newReplayGainTrackGainDb,
            newReplayGainAlbumGainDb = newReplayGainAlbumGainDb,
            customMetadataChanges = customMetadataChanges,
            coverArtUpdate = finalCoverArtUpdate,
            songId = resolvedSongId,
        )

        Timber.tag("MetadataEditStateHolder").d("Editor result: success=${result.success}, error=${result.error}")

        if (result.success) {
            val refreshedAlbumArtUri = if (coverArtUpdate?.isDeletion == true) {
                null
            } else {
                result.updatedAlbumArtUri ?: song.albumArtUriString
            }
            
            if (newLyrics != null) {
                if (normalizedLyrics != null) {
                    musicRepository.updateLyrics(resolvedSongId, normalizedLyrics)
                } else {
                    musicRepository.resetLyrics(resolvedSongId)
                }
            }

            val updatedSong = song.copy(
                title = newTitle,
                artist = newArtist,
                album = newAlbum,
                albumArtist = newAlbumArtist.trim().takeIf { it.isNotBlank() },
                genre = newGenre ?: song.genre,
                lyrics = normalizedLyrics ?: song.lyrics,
                trackNumber = newTrackNumber,
                discNumber = newDiscNumber,
                albumArtUriString = refreshedAlbumArtUri,
            )

            val freshSongFromRepo = try {
                musicRepository.getSong(song.id).first() ?: updatedSong
            } catch (e: Exception) {
                updatedSong
            }

            val freshSong = freshSongFromRepo.copy(
                albumArtUriString = refreshedAlbumArtUri
            )

            val uriToInvalidate = if (coverArtUpdate?.isDeletion == true) song.albumArtUriString else refreshedAlbumArtUri
            if (uriToInvalidate != null) {
                imageCacheManager.invalidateCoverArtCaches(uriToInvalidate)
            }
            
            themeStateHolder.forceRegenerateColorScheme(refreshedAlbumArtUri)

            MetadataEditResult(
                success = true,
                updatedSong = freshSong,
                updatedAlbumArtUri = freshSong.albumArtUriString,
                parsedLyrics = parsedLyrics
            )
        } else {
            Timber.tag("MetadataEditStateHolder").w("Metadata edit failed: ${result.error} - ${result.errorMessage}")
            MetadataEditResult(
                success = false,
                error = result.error,
                errorMessage = result.errorMessage
            )
        }
    }

    suspend fun applyOnlineMetadata(
        song: Song,
        candidate: MetadataCandidate,
        selection: MetadataFieldSelection,
        coverArt: CoverArtUpdate?,
        target: MetadataApplyTarget
    ): MetadataEditResult = withContext(Dispatchers.IO) {
        if (!selection.hasAny && coverArt == null) {
            return@withContext MetadataEditResult(
                success = false,
                error = MetadataEditError.INVALID_INPUT,
                errorMessage = "No metadata fields were selected."
            )
        }

        val resolvedSongId = resolveSongIdForMetadataEdit(song)
        if (resolvedSongId == null) {
            return@withContext MetadataEditResult(
                success = false,
                error = MetadataEditError.INVALID_INPUT,
                errorMessage = "This song source does not support metadata editing."
            )
        }

        val isCloudSong = CloudOfflineRepository.isCloudSong(song)
        if (target == MetadataApplyTarget.FILE_AND_DATABASE && isCloudSong) {
            return@withContext MetadataEditResult(
                success = false,
                error = MetadataEditError.INVALID_INPUT,
                errorMessage = "Cloud tracks can only update the library database."
            )
        }

        val newTitle = if (selection.title) candidate.title else song.title
        val newArtist = if (selection.artist) candidate.artist else song.artist
        val newAlbum = if (selection.album) candidate.album else song.album
        val newAlbumArtist = if (selection.albumArtist) {
            candidate.albumArtist ?: song.albumArtist.orEmpty()
        } else {
            song.albumArtist.orEmpty()
        }
        val newGenre = if (selection.genre) candidate.genre ?: song.genre else song.genre
        val newTrackNumber = if (selection.trackNumber) {
            candidate.trackNumber ?: song.trackNumber
        } else {
            song.trackNumber
        }
        val newDiscNumber = if (selection.discNumber) candidate.discNumber else song.discNumber
        val releaseDate = if (selection.releaseDate) {
            MetadataMergePolicy.resolvedReleaseDate(candidate)
        } else {
            null
        }

        when (target) {
            MetadataApplyTarget.FILE_AND_DATABASE -> {
                val customFields = buildList {
                    releaseDate?.let { add(CustomMetadataFieldUpdate("DATE", it)) }
                }
                val fileResult = saveMetadata(
                    song = song,
                    newTitle = newTitle,
                    newArtist = newArtist,
                    newAlbum = newAlbum,
                    newAlbumArtist = newAlbumArtist,
                    newComposer = null,
                    newGenre = newGenre,
                    newLyrics = null,
                    newTrackNumber = newTrackNumber,
                    newDiscNumber = newDiscNumber,
                    coverArtUpdate = coverArt,
                    customMetadataChanges = CustomMetadataChanges(fields = customFields)
                )
                if (!fileResult.success) return@withContext fileResult

                musicDao.updateSongOnlineExtras(
                    songId = resolvedSongId,
                    albumArtist = candidate.albumArtist,
                    updateAlbumArtist = selection.albumArtist,
                    year = candidate.year,
                    updateYear = selection.releaseDate,
                    releaseDate = releaseDate,
                    updateReleaseDate = selection.releaseDate,
                    recordingId = candidate.recordingId,
                    releaseId = candidate.releaseId,
                    artistId = candidate.artistId,
                    albumArtUri = null,
                    updateAlbumArt = false
                )

                fileResult.copy(
                    updatedSong = (fileResult.updatedSong ?: song).copy(
                        albumArtist = if (selection.albumArtist) {
                            candidate.albumArtist ?: song.albumArtist
                        } else {
                            song.albumArtist
                        },
                        year = if (selection.releaseDate) candidate.year else song.year,
                        releaseDate = if (selection.releaseDate) releaseDate else song.releaseDate,
                        trackNumber = newTrackNumber,
                        discNumber = newDiscNumber
                    )
                )
            }

            MetadataApplyTarget.DATABASE_ONLY -> {
                songMetadataEditor.updateDatabaseMetadata(
                    songId = resolvedSongId,
                    title = newTitle,
                    artist = newArtist,
                    album = newAlbum,
                    genre = newGenre,
                    trackNumber = newTrackNumber,
                    discNumber = newDiscNumber
                )

                val databaseCoverUrl = if (selection.coverArt && isCloudSong) {
                    candidate.coverArtUrl
                } else {
                    null
                }
                musicDao.updateSongOnlineExtras(
                    songId = resolvedSongId,
                    albumArtist = candidate.albumArtist,
                    updateAlbumArtist = selection.albumArtist,
                    year = candidate.year,
                    updateYear = selection.releaseDate,
                    releaseDate = releaseDate,
                    updateReleaseDate = selection.releaseDate,
                    recordingId = candidate.recordingId,
                    releaseId = candidate.releaseId,
                    artistId = candidate.artistId,
                    albumArtUri = databaseCoverUrl,
                    updateAlbumArt = databaseCoverUrl != null
                )

                if (databaseCoverUrl != null) {
                    imageCacheManager.invalidateCoverArtCaches(databaseCoverUrl)
                    themeStateHolder.forceRegenerateColorScheme(databaseCoverUrl)
                }

                MetadataEditResult(
                    success = true,
                    updatedSong = song.copy(
                        title = newTitle,
                        artist = newArtist,
                        album = newAlbum,
                        albumArtist = if (selection.albumArtist) {
                            candidate.albumArtist ?: song.albumArtist
                        } else {
                            song.albumArtist
                        },
                        genre = newGenre,
                        trackNumber = newTrackNumber,
                        discNumber = newDiscNumber,
                        year = if (selection.releaseDate) candidate.year else song.year,
                        releaseDate = if (selection.releaseDate) releaseDate else song.releaseDate,
                        albumArtUriString = databaseCoverUrl ?: song.albumArtUriString
                    ),
                    updatedAlbumArtUri = databaseCoverUrl ?: song.albumArtUriString
                )
            }
        }
    }

    suspend fun deleteSong(song: Song): Boolean = withContext(Dispatchers.IO) {
        val fileInfo = FileDeletionUtils.getFileInfo(song.path)
        if (fileInfo.exists && fileInfo.canWrite) {
            val success = FileDeletionUtils.deleteFile(context, song.path)
            if (success) {
                
                true
            } else {
                false
            }
        } else {
            false
        }
    }

    private fun resolveSongIdForMetadataEdit(song: Song): Long? {
        song.id.toLongOrNull()?.let { return it }

        val uriCandidates = buildList {
            if (song.contentUriString.isNotBlank()) add(song.contentUriString)
            if (song.id.startsWith("external:")) add(song.id.removePrefix("external:"))
        }

        for (rawUri in uriCandidates) {
            val parsedUri = runCatching { Uri.parse(rawUri) }.getOrNull() ?: continue
            if (parsedUri.scheme != "content") continue

            parsedUri.lastPathSegment?.toLongOrNull()?.let { return it }
        }

        return null
    }
}
