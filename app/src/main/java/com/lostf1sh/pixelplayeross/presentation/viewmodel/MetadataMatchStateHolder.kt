package com.lostf1sh.pixelplayeross.presentation.viewmodel

import com.lostf1sh.pixelplayeross.data.database.MusicDao
import com.lostf1sh.pixelplayeross.data.database.SongEntity
import com.lostf1sh.pixelplayeross.data.metadata.FileNameMetadataParser
import com.lostf1sh.pixelplayeross.data.metadata.MetadataApplyTarget
import com.lostf1sh.pixelplayeross.data.metadata.MetadataCandidate
import com.lostf1sh.pixelplayeross.data.metadata.MetadataFieldSelection
import com.lostf1sh.pixelplayeross.data.metadata.MetadataMergePolicy
import com.lostf1sh.pixelplayeross.data.metadata.MetadataQuery
import com.lostf1sh.pixelplayeross.data.metadata.MetadataQueryBuilder
import com.lostf1sh.pixelplayeross.data.metadata.SongMetadataSnapshot
import com.lostf1sh.pixelplayeross.data.musicbrainz.CoverArtArchiveService
import com.lostf1sh.pixelplayeross.data.musicbrainz.MusicBrainzRepository
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.data.offline.CloudOfflineRepository
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class MetadataMatchPhase {
    HIDDEN,
    QUERY,
    PREVIEW,
    APPLIED
}

enum class MetadataMatchError {
    NONE,
    NO_FIELDS,
    COVER_UNAVAILABLE,
    SEARCH_FAILED,
    APPLY_FAILED
}

enum class MetadataField {
    TITLE,
    ARTIST,
    ALBUM,
    ALBUM_ARTIST,
    RELEASE_DATE,
    TRACK_NUMBER,
    DISC_NUMBER,
    GENRE,
    COVER_ART
}

data class MetadataMatchUiState(
    val phase: MetadataMatchPhase = MetadataMatchPhase.HIDDEN,
    val song: Song? = null,
    val query: MetadataQuery = MetadataQuery(title = "", artist = "", album = null),
    val candidates: List<MetadataCandidate> = emptyList(),
    val selectedCandidate: MetadataCandidate? = null,
    val selection: MetadataFieldSelection = MetadataFieldSelection(),
    val fillBlankOnly: Boolean = true,
    val applyTarget: MetadataApplyTarget = MetadataApplyTarget.DATABASE_ONLY,
    val canWriteFile: Boolean = false,
    val isCloudSong: Boolean = false,
    val isSearching: Boolean = false,
    val isLoadingDetails: Boolean = false,
    val isApplying: Boolean = false,
    val hasSearched: Boolean = false,
    val manuallyEditedFields: Set<MetadataField> = emptySet(),
    val error: MetadataMatchError = MetadataMatchError.NONE,
    val errorMessage: String? = null,
    val detailsError: String? = null,
    val searchedTitleOnly: Boolean = false,
    val appliedFieldCount: Int = 0,
    val appliedCover: Boolean = false,
    val coverUnavailable: Boolean = false
) {
    val isVisible: Boolean
        get() = phase != MetadataMatchPhase.HIDDEN
}

class MetadataMatchStateHolder @Inject constructor(
    private val musicBrainzRepository: MusicBrainzRepository,
    private val coverArtArchiveService: CoverArtArchiveService,
    private val musicDao: MusicDao,
    private val metadataEditStateHolder: MetadataEditStateHolder
) {
    private val _state = MutableStateFlow(MetadataMatchUiState())
    val state: StateFlow<MetadataMatchUiState> = _state.asStateFlow()

    private var scope: CoroutineScope? = null
    private var job: Job? = null
    private var snapshot: SongMetadataSnapshot? = null

    fun open(song: Song, coroutineScope: CoroutineScope) {
        job?.cancel()
        scope = coroutineScope
        snapshot = null

        val isCloud = CloudOfflineRepository.isCloudSong(song)
        val canWriteFile = !isCloud && song.path.isNotBlank() && File(song.path).exists()
        val query = MetadataQueryBuilder.fromSong(
            title = song.title,
            artist = song.displayArtist,
            album = song.album,
            path = song.path
        )

        _state.value = MetadataMatchUiState(
            phase = MetadataMatchPhase.QUERY,
            song = song,
            query = query,
            applyTarget = if (canWriteFile) {
                MetadataApplyTarget.FILE_AND_DATABASE
            } else {
                MetadataApplyTarget.DATABASE_ONLY
            },
            canWriteFile = canWriteFile,
            isCloudSong = isCloud
        )

        job = coroutineScope.launch {
            val entity = runCatching {
                song.id.toLongOrNull()?.let { musicDao.getSongByIdOnce(it) }
            }.getOrNull()
            snapshot = buildSnapshot(song, entity)
            _state.value = _state.value.copy(manuallyEditedFields = manuallyEditedFields(entity))
        }
    }

    fun updateQuery(title: String, artist: String, album: String) {
        _state.value = _state.value.copy(
            query = _state.value.query.copy(
                title = title,
                artist = artist,
                album = album.trim().takeIf { it.isNotBlank() }
            ),
            candidates = emptyList(),
            hasSearched = false,
            searchedTitleOnly = false,
            error = MetadataMatchError.NONE,
            errorMessage = null
        )
    }

    fun useFileTags() {
        val song = _state.value.song ?: return
        val isCloud = CloudOfflineRepository.isCloudSong(song)
        val canWriteFile = !isCloud && song.path.isNotBlank() && File(song.path).exists()
        _state.value = _state.value.copy(
            query = MetadataQuery(
                title = song.title.takeUnless(MetadataQueryBuilder::isPlaceholder).orEmpty(),
                artist = song.displayArtist.takeUnless(MetadataQueryBuilder::isPlaceholder).orEmpty(),
                album = song.album.takeUnless(MetadataQueryBuilder::isPlaceholder)
            ),
            canWriteFile = canWriteFile,
            candidates = emptyList(),
            hasSearched = false,
            searchedTitleOnly = false,
            error = MetadataMatchError.NONE,
            errorMessage = null
        )
    }

    fun useFileName() {
        val song = _state.value.song ?: return
        val parsed = FileNameMetadataParser.parsePath(song.path) ?: return
        _state.value = _state.value.copy(
            query = MetadataQuery(
                title = parsed.title,
                artist = parsed.artist.orEmpty(),
                album = parsed.album,
                usedFilename = true
            ),
            candidates = emptyList(),
            hasSearched = false,
            searchedTitleOnly = false,
            error = MetadataMatchError.NONE,
            errorMessage = null
        )
    }

    fun search() {
        val current = _state.value
        val query = current.query
        if (current.isSearching || query.title.isBlank()) return
        val coroutineScope = scope ?: return

        job?.cancel()
        _state.value = current.copy(
            isSearching = true,
            candidates = emptyList(),
            selectedCandidate = null,
            error = MetadataMatchError.NONE,
            errorMessage = null,
            hasSearched = false,
            searchedTitleOnly = false
        )
        job = coroutineScope.launch {
            try {
                val result = musicBrainzRepository.searchCandidates(
                    query = query,
                    durationMs = current.song?.duration?.takeIf { it > 0L }
                )
                _state.value = _state.value.copy(
                    isSearching = false,
                    hasSearched = true,
                    candidates = result.candidates,
                    searchedTitleOnly = result.candidates.isNotEmpty() &&
                        result.matchedAttempt?.titleOnlyFallback == true
                )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                _state.value = _state.value.copy(
                    isSearching = false,
                    hasSearched = true,
                    error = MetadataMatchError.SEARCH_FAILED,
                    errorMessage = error.localizedMessage
                )
            }
        }
    }

    fun selectCandidate(candidate: MetadataCandidate) {
        val song = _state.value.song ?: return
        val coroutineScope = scope ?: return
        val currentSnapshot = snapshot ?: buildSnapshot(song, null).also { snapshot = it }

        job?.cancel()
        _state.value = _state.value.copy(
            phase = MetadataMatchPhase.PREVIEW,
            selectedCandidate = candidate,
            selection = MetadataMergePolicy.defaultSelection(currentSnapshot, candidate),
            isLoadingDetails = true,
            detailsError = null,
            error = MetadataMatchError.NONE,
            errorMessage = null
        )

        job = coroutineScope.launch {
            try {
                val detailed = musicBrainzRepository.loadDetails(
                    candidate = candidate,
                    durationMs = song.duration.takeIf { it > 0L }
                )
                val latest = _state.value
                if (latest.selectedCandidate?.key != candidate.key) return@launch
                _state.value = latest.copy(
                    selectedCandidate = detailed,
                    selection = MetadataMergePolicy.defaultSelection(
                        snapshot = currentSnapshot,
                        candidate = detailed,
                        fillBlankOnly = latest.fillBlankOnly
                    ),
                    isLoadingDetails = false
                )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                _state.value = _state.value.copy(
                    isLoadingDetails = false,
                    detailsError = error.localizedMessage
                )
            }
        }
    }

    fun backToResults() {
        job?.cancel()
        _state.value = _state.value.copy(
            phase = MetadataMatchPhase.QUERY,
            selectedCandidate = null,
            selection = MetadataFieldSelection(),
            isLoadingDetails = false,
            detailsError = null,
            error = MetadataMatchError.NONE,
            errorMessage = null
        )
    }

    fun setFieldSelected(field: MetadataField, selected: Boolean) {
        val selection = _state.value.selection
        _state.value = _state.value.copy(
            selection = when (field) {
                MetadataField.TITLE -> selection.copy(title = selected)
                MetadataField.ARTIST -> selection.copy(artist = selected)
                MetadataField.ALBUM -> selection.copy(album = selected)
                MetadataField.ALBUM_ARTIST -> selection.copy(albumArtist = selected)
                MetadataField.RELEASE_DATE -> selection.copy(releaseDate = selected)
                MetadataField.TRACK_NUMBER -> selection.copy(trackNumber = selected)
                MetadataField.DISC_NUMBER -> selection.copy(discNumber = selected)
                MetadataField.GENRE -> selection.copy(genre = selected)
                MetadataField.COVER_ART -> selection.copy(coverArt = selected)
            },
            error = MetadataMatchError.NONE,
            errorMessage = null
        )
    }

    fun setFillBlankOnly(enabled: Boolean) {
        val current = _state.value
        if (!enabled) {
            // Turning the switch off keeps the fields the user already selected.
            _state.value = current.copy(
                fillBlankOnly = false,
                error = MetadataMatchError.NONE,
                errorMessage = null
            )
            return
        }
        val candidate = current.selectedCandidate ?: return
        val currentSnapshot = snapshot
            ?: current.song?.let { buildSnapshot(it, null).also { built -> snapshot = built } }
            ?: return
        val autoSelection = MetadataMergePolicy.defaultSelection(
            snapshot = currentSnapshot,
            candidate = candidate,
            fillBlankOnly = true
        )
        _state.value = current.copy(
            fillBlankOnly = true,
            selection = current.selection.merge(autoSelection),
            error = MetadataMatchError.NONE,
            errorMessage = null
        )
    }

    fun setApplyTarget(target: MetadataApplyTarget) {
        val current = _state.value
        if (target == MetadataApplyTarget.FILE_AND_DATABASE && !current.canWriteFile) return
        val selection = if (target == MetadataApplyTarget.DATABASE_ONLY && !current.isCloudSong) {
            current.selection.copy(coverArt = false)
        } else {
            current.selection
        }
        _state.value = current.copy(
            applyTarget = target,
            selection = selection,
            error = MetadataMatchError.NONE,
            errorMessage = null
        )
    }

    fun apply() {
        val current = _state.value
        val song = current.song ?: return
        val candidate = current.selectedCandidate ?: return
        val coroutineScope = scope ?: return
        if (current.isApplying) return

        val isLocalDatabaseOnly = current.applyTarget == MetadataApplyTarget.DATABASE_ONLY &&
            !current.isCloudSong
        val requestedCover = current.selection.coverArt &&
            !candidate.coverArtUrl.isNullOrBlank() &&
            !isLocalDatabaseOnly
        val selection = current.selection.copy(coverArt = requestedCover)

        if (!selection.hasAny) {
            _state.value = current.copy(
                selection = selection,
                error = MetadataMatchError.NO_FIELDS,
                errorMessage = null
            )
            return
        }

        job?.cancel()
        _state.value = current.copy(
            selection = selection,
            isApplying = true,
            error = MetadataMatchError.NONE,
            errorMessage = null,
            coverUnavailable = false
        )
        job = coroutineScope.launch {
            val coverInFile = current.applyTarget == MetadataApplyTarget.FILE_AND_DATABASE
            val cover = if (requestedCover && coverInFile) {
                val url = candidate.coverArtUrl
                if (url.isNullOrBlank()) {
                    null
                } else {
                    try {
                        coverArtArchiveService.download(url)
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (error: Exception) {
                        null
                    }
                }
            } else {
                null
            }

            val coverApplied = when {
                !requestedCover -> false
                !coverInFile -> true
                else -> cover != null
            }
            val finalSelection = selection.copy(coverArt = coverApplied)
            if (!finalSelection.hasAny) {
                _state.value = _state.value.copy(
                    isApplying = false,
                    selection = finalSelection,
                    error = MetadataMatchError.COVER_UNAVAILABLE,
                    errorMessage = null
                )
                return@launch
            }

            val result = runCatching {
                metadataEditStateHolder.applyOnlineMetadata(
                    song = song,
                    candidate = candidate,
                    selection = finalSelection,
                    coverArt = cover,
                    target = current.applyTarget
                )
            }.getOrElse { error ->
                _state.value = _state.value.copy(
                    isApplying = false,
                    error = MetadataMatchError.APPLY_FAILED,
                    errorMessage = error.localizedMessage
                )
                return@launch
            }

            if (result.success) {
                _state.value = _state.value.copy(
                    phase = MetadataMatchPhase.APPLIED,
                    isApplying = false,
                    selection = finalSelection,
                    appliedFieldCount = finalSelection.count() + if (coverApplied) 1 else 0,
                    appliedCover = coverApplied,
                    coverUnavailable = requestedCover && !coverApplied
                )
            } else {
                _state.value = _state.value.copy(
                    isApplying = false,
                    error = MetadataMatchError.APPLY_FAILED,
                    errorMessage = result.getUserFriendlyErrorMessage()
                )
            }
        }
    }

    fun dismissError() {
        _state.value = _state.value.copy(error = MetadataMatchError.NONE, errorMessage = null)
    }

    fun dismiss() {
        job?.cancel()
        _state.value = MetadataMatchUiState()
    }

    fun close() {
        job?.cancel()
        scope = null
    }

    private fun buildSnapshot(song: Song, entity: SongEntity?): SongMetadataSnapshot =
        SongMetadataSnapshot(
            title = song.title,
            artist = song.displayArtist,
            album = song.album,
            albumArtist = song.albumArtist,
            year = song.year,
            releaseDate = song.releaseDate,
            trackNumber = song.trackNumber,
            discNumber = song.discNumber,
            genre = song.genre,
            hasCoverArt = !song.albumArtUriString.isNullOrBlank(),
            titleUserEdited = entity?.titleUserEdited == true,
            artistUserEdited = entity?.artistUserEdited == true,
            albumUserEdited = entity?.albumUserEdited == true,
            genreUserEdited = entity?.genreUserEdited == true
        )

    private fun manuallyEditedFields(entity: SongEntity?): Set<MetadataField> = buildSet {
        if (entity?.titleUserEdited == true) add(MetadataField.TITLE)
        if (entity?.artistUserEdited == true) add(MetadataField.ARTIST)
        if (entity?.albumUserEdited == true) add(MetadataField.ALBUM)
        if (entity?.genreUserEdited == true) add(MetadataField.GENRE)
    }

    private fun MetadataFieldSelection.merge(
        other: MetadataFieldSelection
    ): MetadataFieldSelection = copy(
        title = title || other.title,
        artist = artist || other.artist,
        album = album || other.album,
        albumArtist = albumArtist || other.albumArtist,
        releaseDate = releaseDate || other.releaseDate,
        trackNumber = trackNumber || other.trackNumber,
        discNumber = discNumber || other.discNumber,
        genre = genre || other.genre,
        coverArt = coverArt || other.coverArt
    )

    private fun MetadataFieldSelection.count(): Int = listOf(
        title,
        artist,
        album,
        albumArtist,
        releaseDate,
        trackNumber,
        discNumber,
        genre
    ).count { it }
}
