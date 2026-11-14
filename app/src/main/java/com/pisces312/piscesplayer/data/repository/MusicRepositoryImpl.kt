package com.pisces312.piscesplayer.data.repository



import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore

import com.pisces312.piscesplayer.data.model.Song
import com.pisces312.piscesplayer.data.repository.ArtistImageRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton
import androidx.core.net.toUri
import com.pisces312.piscesplayer.data.database.FavoritesDao
import com.pisces312.piscesplayer.data.database.MusicDao
import com.pisces312.piscesplayer.data.database.SearchHistoryDao
import com.pisces312.piscesplayer.data.database.SearchHistoryEntity
import com.pisces312.piscesplayer.data.database.SongEntity
import com.pisces312.piscesplayer.data.database.toAlbum
import com.pisces312.piscesplayer.data.database.toArtist
import com.pisces312.piscesplayer.data.database.toSearchHistoryItem
import com.pisces312.piscesplayer.data.database.toSong
import com.pisces312.piscesplayer.data.model.Album
import com.pisces312.piscesplayer.data.model.Artist
import com.pisces312.piscesplayer.data.model.Genre
import com.pisces312.piscesplayer.data.model.Lyrics
import com.pisces312.piscesplayer.data.model.LyricsSourcePreference
import com.pisces312.piscesplayer.data.model.MusicFolder
import com.pisces312.piscesplayer.data.model.Playlist
import com.pisces312.piscesplayer.data.model.SearchFilterType
import com.pisces312.piscesplayer.data.model.SearchHistoryItem
import com.pisces312.piscesplayer.data.model.SearchResultItem
import com.pisces312.piscesplayer.data.model.SortOption
import com.pisces312.piscesplayer.data.model.FolderSource
import com.pisces312.piscesplayer.data.model.StorageFilter
import com.pisces312.piscesplayer.data.model.YearBucket
import com.pisces312.piscesplayer.data.preferences.PlaylistPreferencesRepository
import com.pisces312.piscesplayer.data.preferences.UserPreferencesRepository
import com.pisces312.piscesplayer.ui.theme.GenreThemeUtils
import com.pisces312.piscesplayer.utils.DirectoryFilterUtils
import com.pisces312.piscesplayer.utils.LogUtils
import com.pisces312.piscesplayer.utils.StorageType
import com.pisces312.piscesplayer.utils.StorageUtils
import com.pisces312.piscesplayer.utils.toHexString
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.map
import androidx.paging.filter
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CoroutineScope
import timber.log.Timber

@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class MusicRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val userPreferencesRepository: UserPreferencesRepository,
    private val playlistPreferencesRepository: PlaylistPreferencesRepository,
    private val searchHistoryDao: SearchHistoryDao,
    private val musicDao: MusicDao,
    private val lyricsRepository: LyricsRepository,
    private val songRepository: SongRepository,
    private val favoritesDao: FavoritesDao,
    private val artistImageRepository: ArtistImageRepository,
    private val folderTreeBuilder: FolderTreeBuilder
) : MusicRepository {

    companion object {
        /** Maximum number of search results to load at once to avoid memory issues with large libraries. */
        private const val SEARCH_RESULTS_LIMIT = 100
        private const val UNKNOWN_GENRE_NAME = "Unknown"
        private const val UNKNOWN_GENRE_ID = "unknown"
    }

    private val directoryScanMutex = Mutex()
    private val repositoryScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val defaultLibraryPagingConfig = PagingConfig(
        pageSize = 50,
        enablePlaceholders = true,
        maxSize = 250
    )
    @Volatile private var prefetchJob: Job? = null
    @Volatile private var currentSongArtistPrefetchJob: Job? = null
    @Volatile private var currentSongArtistPrefetchSongId: Long? = null

    private suspend fun Artist.withFolderArtwork(): Artist {
        if (!customImageUri.isNullOrBlank()) return this
        val folderImage = artistImageRepository.getFolderArtistImageUrl(id, name) ?: return this
        return copy(imageUrl = folderImage)
    }

    private fun Flow<List<Artist>>.withFolderArtwork(): Flow<List<Artist>> =
        combine(this, artistImageRepository.folderArtworkRevision) { artists, _ ->
            artists.map { it.withFolderArtwork() }
        }

    private fun normalizePath(path: String): String =
        runCatching { File(path).canonicalPath }.getOrElse { File(path).absolutePath }

    /**
     * Favorite state source of truth is the `favorites` table. Overlay it at the repository
     * boundary so `Song.isFavorite` stays correct even if the `songs.is_favorite` cache drifts.
     */
    private fun Flow<List<SongEntity>>.toSongsWithFavoriteState(): Flow<List<Song>> =
        combine(this, favoritesDao.getFavoriteSongIds()) { entities, favoriteIds ->
            val favorites = favoriteIds.toSet()
            entities.map { it.toSong().copy(isFavorite = it.id in favorites) }
        }

    private suspend fun List<SongEntity>.toSongsWithFavoriteStateOnce(): List<Song> {
        val favorites = favoritesDao.getFavoriteSongIdsOnce().toSet()
        return map { it.toSong().copy(isFavorite = it.id in favorites) }
    }

    private fun Flow<SongEntity?>.toSongWithFavoriteState(): Flow<Song?> =
        combine(this, favoritesDao.getFavoriteSongIds()) { entity, favoriteIds ->
            entity?.toSong()?.copy(isFavorite = entity.id in favoriteIds.toSet())
        }

    private suspend fun SongEntity?.toSongWithFavoriteStateOnce(): Song? {
        if (this == null) return null
        val isFavorite = favoritesDao.getFavoriteSongIdsOnce().contains(id)
        return toSong().copy(isFavorite = isFavorite)
    }

    /** Cached directory filter — recomputed when allowed/blocked dirs preferences change or when the set of song folders changes. */
    data class CachedDirFilter(val allowedParentDirs: List<String> = emptyList(), val applyFilter: Boolean = false)

    @OptIn(ExperimentalCoroutinesApi::class)
    private val cachedDirFilter: StateFlow<CachedDirFilter> = combine(
        userPreferencesRepository.allowedDirectoriesFlow,
        userPreferencesRepository.blockedDirectoriesFlow
    ) { allowed, blocked -> allowed to blocked }
        .flatMapLatest { (allowed, blocked) ->
            if (blocked.isEmpty()) {
                // No blocked dirs => no directory filter is applied. Avoid observing the
                // songs table at all in this (common) case.
                flowOf(CachedDirFilter(emptyList(), false))
            } else {
                // Recompute whenever the set of song folders changes (e.g. after a sync),
                // so the filter never freezes at a stale/empty snapshot. Previously this
                // was computed once, eagerly at construction — before the first sync — which
                // on some devices left allowedParentDirs empty while applyFilter stayed true,
                // making queue-building queries (getSongIdsSorted) return nothing and collapse
                // the playback queue to just the tapped song.
                musicDao.getDistinctParentDirectoriesFlow()
                    .distinctUntilChanged()
                    .map { parentDirs ->
                        val (dirs, apply) = DirectoryFilterUtils.computeAllowedParentDirs(
                            allowedDirs = allowed,
                            blockedDirs = blocked,
                            getAllParentDirs = { parentDirs },
                            normalizePath = ::normalizePath
                        )
                        CachedDirFilter(dirs, apply)
                    }
            }
        }
        .stateIn(repositoryScope, SharingStarted.Eagerly, CachedDirFilter())

    private fun List<Artist>.missingImageCandidates(): List<Pair<Long, String>> =
        asSequence()
            .filter { it.effectiveImageUrl.isNullOrBlank() && it.name.isNotBlank() }
            .map { it.id to it.name }
            .distinctBy { (_, name) -> name.trim().lowercase() }
            .toList()

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun getAudioFiles(): Flow<List<Song>> {
        return combine(
            userPreferencesRepository.allowedDirectoriesFlow,
            userPreferencesRepository.blockedDirectoriesFlow
        ) { allowedDirs, blockedDirs ->
            allowedDirs to blockedDirs
        }.flatMapLatest { (allowedDirs, blockedDirs) ->
            flow {
                val (allowedParentDirs, applyDirectoryFilter) =
                    computeAllowedDirs(allowedDirs, blockedDirs)
                emit(
                    musicDao.getAllSongs(
                        allowedParentDirs = allowedParentDirs,
                        applyDirectoryFilter = applyDirectoryFilter
                    )
                )
            }.flatMapLatest { it }
        }.toSongsWithFavoriteState().distinctUntilChanged().conflate().flowOn(Dispatchers.IO)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun getPaginatedSongs(sortOption: SortOption, storageFilter: com.pisces312.piscesplayer.data.model.StorageFilter): Flow<PagingData<Song>> {
        return songRepository.getPaginatedSongs(sortOption, storageFilter)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun getPaginatedAlbums(
        sortOption: SortOption,
        storageFilter: StorageFilter,
        minTracks: Int
    ): Flow<PagingData<Album>> {
        return combine(
            userPreferencesRepository.allowedDirectoriesFlow,
            userPreferencesRepository.blockedDirectoriesFlow
        ) { allowedDirs, blockedDirs ->
            allowedDirs to blockedDirs
        }.flatMapLatest { (allowedDirs, blockedDirs) ->
            flow {
                val (allowedParentDirs, applyDirectoryFilter) =
                    computeAllowedDirs(allowedDirs, blockedDirs)
                emit(
                    Pager(
                        config = defaultLibraryPagingConfig,
                        pagingSourceFactory = {
                            musicDao.getAlbumsPaginated(
                                allowedParentDirs = allowedParentDirs,
                                applyDirectoryFilter = applyDirectoryFilter,
                                filterMode = storageFilter.toFilterMode(),
                                sortOrder = sortOption.storageKey,
                                minTracks = minTracks
                            )
                        }
                    ).flow
                )
            }.flatMapLatest { it }
        }.map { pagingData ->
            pagingData.map { entity -> entity.toAlbum() }
        }.flowOn(Dispatchers.IO)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun getPaginatedArtists(
        sortOption: SortOption,
        storageFilter: StorageFilter
    ): Flow<PagingData<Artist>> {
        return combine(
            userPreferencesRepository.allowedDirectoriesFlow,
            userPreferencesRepository.blockedDirectoriesFlow,
            userPreferencesRepository.groupByAlbumArtistFlow,
            artistImageRepository.folderArtworkRevision
        ) { allowedDirs, blockedDirs, groupByAlbumArtist, _ ->
            Triple(allowedDirs, blockedDirs, groupByAlbumArtist)
        }.flatMapLatest { (allowedDirs, blockedDirs, groupByAlbumArtist) ->
            flow {
                val (allowedParentDirs, applyDirectoryFilter) =
                    computeAllowedDirs(allowedDirs, blockedDirs)
                emit(
                    Pager(
                        config = defaultLibraryPagingConfig,
                        pagingSourceFactory = {
                            if (groupByAlbumArtist) {
                                musicDao.getArtistsPaginatedByAlbumArtist(
                                    allowedParentDirs = allowedParentDirs,
                                    applyDirectoryFilter = applyDirectoryFilter,
                                    filterMode = storageFilter.toFilterMode(),
                                    sortOrder = sortOption.storageKey
                                )
                            } else {
                                musicDao.getArtistsPaginated(
                                    allowedParentDirs = allowedParentDirs,
                                    applyDirectoryFilter = applyDirectoryFilter,
                                    filterMode = storageFilter.toFilterMode(),
                                    sortOrder = sortOption.storageKey
                                )
                            }
                        }
                    ).flow
                )
            }.flatMapLatest { it }
        }.map { pagingData ->
            pagingData.map { entity -> entity.toArtist().withFolderArtwork() }
        }.flowOn(Dispatchers.IO)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun getPaginatedFavoriteSongs(sortOption: SortOption, storageFilter: StorageFilter): Flow<PagingData<Song>> {
        return songRepository.getPaginatedFavoriteSongs(sortOption, storageFilter)
    }

    override suspend fun getFavoriteSongsOnce(storageFilter: StorageFilter): List<Song> {
        return songRepository.getFavoriteSongsOnce(storageFilter)
    }

    override suspend fun getFavoriteSongsPage(
        limit: Int,
        offset: Int,
        sortOption: SortOption,
        storageFilter: StorageFilter
    ): List<Song> = withContext(Dispatchers.IO) {
        val filter = cachedDirFilter.value
        musicDao.getFavoriteSongsPage(
            allowedParentDirs = filter.allowedParentDirs,
            applyDirectoryFilter = filter.applyFilter,
            sortOrder = sortOption.storageKey,
            filterMode = storageFilter.toFilterMode(),
            limit = limit,
            offset = offset
        ).toSongsWithFavoriteStateOnce()
    }

    override fun getFavoriteSongCountFlow(storageFilter: StorageFilter): Flow<Int> {
        return songRepository.getFavoriteSongCountFlow(storageFilter)
    }

    override fun getSongCountFlow(): Flow<Int> {
        return musicDao.getSongCount().distinctUntilChanged()
    }

    override fun getCloudSongCountFlow(): Flow<Int> {
        return musicDao.getCloudSongCount().distinctUntilChanged()
    }

    override suspend fun getRandomSongs(limit: Int): List<Song> = withContext(Dispatchers.IO) {
        val filter = cachedDirFilter.value
        musicDao.getRandomSongs(limit, filter.allowedParentDirs, filter.applyFilter).toSongsWithFavoriteStateOnce()
    }

    override suspend fun getSongsPage(
        limit: Int,
        offset: Int,
        sortOption: SortOption,
        storageFilter: StorageFilter
    ): List<Song> = withContext(Dispatchers.IO) {
        val filter = cachedDirFilter.value
        musicDao.getSongsPage(
            allowedParentDirs = filter.allowedParentDirs,
            applyDirectoryFilter = filter.applyFilter,
            sortOrder = sortOption.storageKey,
            filterMode = storageFilter.toFilterMode(),
            limit = limit,
            offset = offset
        ).toSongsWithFavoriteStateOnce()
    }

    override suspend fun getAlbumsPage(
        limit: Int,
        offset: Int,
        sortOption: SortOption,
        storageFilter: StorageFilter,
        minTracks: Int
    ): List<Album> = withContext(Dispatchers.IO) {
        val filter = cachedDirFilter.value
        musicDao.getAlbumsPage(
            allowedParentDirs = filter.allowedParentDirs,
            applyDirectoryFilter = filter.applyFilter,
            sortOrder = sortOption.storageKey,
            filterMode = storageFilter.toFilterMode(),
            minTracks = minTracks,
            limit = limit,
            offset = offset
        ).map { it.toAlbum() }
    }

    override suspend fun getArtistsPage(
        limit: Int,
        offset: Int,
        sortOption: SortOption,
        storageFilter: StorageFilter
    ): List<Artist> = withContext(Dispatchers.IO) {
        val filter = cachedDirFilter.value
        musicDao.getArtistsPage(
            allowedParentDirs = filter.allowedParentDirs,
            applyDirectoryFilter = filter.applyFilter,
            sortOrder = sortOption.storageKey,
            filterMode = storageFilter.toFilterMode(),
            limit = limit,
            offset = offset
        ).map { it.toArtist().withFolderArtwork() }
    }

    override suspend fun getFirstPlayableSong(): Song? = withContext(Dispatchers.IO) {
        val allowedDirs = userPreferencesRepository.allowedDirectoriesFlow.first()
        val blockedDirs = userPreferencesRepository.blockedDirectoriesFlow.first()
        val (allowedParentDirs, applyDirectoryFilter) =
            computeAllowedDirs(allowedDirs, blockedDirs)
        musicDao.getFirstPlayableSong(
            allowedParentDirs = allowedParentDirs,
            applyDirectoryFilter = applyDirectoryFilter
        ).toSongWithFavoriteStateOnce()
    }

    /**
     * Compute allowed parent directories by subtracting blocked dirs from all known dirs.
     * Returns Pair(allowedDirs, applyFilter) for use with Room DAO filtered queries.
     */
    private suspend fun computeAllowedDirs(
        allowedDirs: Set<String>,
        blockedDirs: Set<String>
    ): Pair<List<String>, Boolean> {
        return DirectoryFilterUtils.computeAllowedParentDirs(
            allowedDirs = allowedDirs,
            blockedDirs = blockedDirs,
            getAllParentDirs = { musicDao.getDistinctParentDirectories() },
            normalizePath = ::normalizePath
        )
    }

    private fun StorageFilter.toFilterMode(): Int = when (this) {
        StorageFilter.ALL -> 0
        StorageFilter.OFFLINE -> 1
        StorageFilter.ONLINE -> 2
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun getAlbums(storageFilter: StorageFilter, minTracks: Int): Flow<List<Album>> {
        return combine(
            userPreferencesRepository.allowedDirectoriesFlow,
            userPreferencesRepository.blockedDirectoriesFlow
        ) { allowedDirs, blockedDirs ->
            allowedDirs to blockedDirs
        }.flatMapLatest { (allowedDirs, blockedDirs) ->
            val (allowedParentDirs, applyFilter) = computeAllowedDirs(allowedDirs, blockedDirs)
            musicDao.getAlbums(allowedParentDirs, applyFilter, storageFilter.toFilterMode(), minTracks)
                .map { entities -> entities.map { it.toAlbum() } }
                .distinctUntilChanged()
        }.conflate().flowOn(Dispatchers.IO)
    }

    override fun getAlbumById(id: Long): Flow<Album?> {
        return musicDao.getAlbumById(id).map { it?.toAlbum() }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun getArtists(storageFilter: StorageFilter): Flow<List<Artist>> {
        return combine(
            userPreferencesRepository.allowedDirectoriesFlow,
            userPreferencesRepository.blockedDirectoriesFlow
        ) { allowedDirs, blockedDirs ->
            allowedDirs to blockedDirs
        }.flatMapLatest { (allowedDirs, blockedDirs) ->
            val (allowedParentDirs, applyFilter) = computeAllowedDirs(allowedDirs, blockedDirs)
            musicDao.getArtistsWithSongCountsFiltered(
                allowedParentDirs = allowedParentDirs,
                applyDirectoryFilter = applyFilter,
                filterMode = storageFilter.toFilterMode()
            )
                .distinctUntilChanged()
                .map { entities -> entities.map { it.toArtist() } }
                .withFolderArtwork()
                .map { artists ->
                    val missingImages = artists.missingImageCandidates()
                    if (missingImages.isNotEmpty()) {
                        prefetchJob?.cancel()
                        prefetchJob = repositoryScope.launch {
                            artistImageRepository.prefetchArtistImages(missingImages)
                        }
                    }
                    artists
                }
        }.conflate().flowOn(Dispatchers.IO)
    }

    override fun getSongsForAlbum(albumId: Long): Flow<List<Song>> {
        return musicDao.getSongsByAlbumId(albumId).toSongsWithFavoriteState().map { songs ->
            songs.sortedBy { it.trackNumber }
        }.flowOn(Dispatchers.IO)
    }

    override fun getArtistById(artistId: Long): Flow<Artist?> {
        return combine(
            musicDao.getArtistById(artistId),
            artistImageRepository.folderArtworkRevision
        ) { entity, _ -> entity?.toArtist()?.withFolderArtwork() }.flowOn(Dispatchers.IO)
    }

    override suspend fun getArtistIdByName(name: String): Long? = withContext(Dispatchers.IO) {
        musicDao.getArtistIdByName(name)
    }

    override fun getArtistsForSong(songId: Long): Flow<List<Artist>> {
        return musicDao.getArtistsForSong(songId)
            .map { entities -> entities.map { it.toArtist() } }
            .withFolderArtwork()
            .distinctUntilChanged()
            .onEach { artists ->
                val missingImages = artists.missingImageCandidates()
                if (missingImages.isNotEmpty()) {
                    val isNewSong = currentSongArtistPrefetchSongId != songId
                    if (isNewSong) {
                        currentSongArtistPrefetchJob?.cancel()
                        currentSongArtistPrefetchSongId = songId
                    } else if (currentSongArtistPrefetchJob?.isActive == true) {
                        return@onEach
                    }

                    currentSongArtistPrefetchJob = repositoryScope.launch {
                        artistImageRepository.prefetchArtistImages(missingImages)
                    }
                }
            }
            .flowOn(Dispatchers.IO)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun getSongsForArtist(artistId: Long): Flow<List<Song>> {
        return userPreferencesRepository.groupByAlbumArtistFlow.flatMapLatest { groupByAlbumArtist ->
            if (groupByAlbumArtist) {
                musicDao.getSongsForArtistByAlbumArtist(artistId)
            } else {
                musicDao.getSongsForArtist(artistId)
            }
        }.toSongsWithFavoriteState().flowOn(Dispatchers.IO)
    }

    override suspend fun getAllUniqueAudioDirectories(): Set<String> = withContext(Dispatchers.IO) {
        LogUtils.d(this, "getAllUniqueAudioDirectories")
        directoryScanMutex.withLock {
            val directories = mutableSetOf<String>()
            val projection = arrayOf(MediaStore.Audio.Media.DATA)
            val selection = "(${MediaStore.Audio.Media.IS_MUSIC} != 0 OR ${MediaStore.Audio.Media.DATA} LIKE '%.m4a' OR ${MediaStore.Audio.Media.DATA} LIKE '%.flac')"
            context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection, selection, null, null
            )?.use { c ->
                val dataColumn = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
                while (c.moveToNext()) {
                    File(c.getString(dataColumn)).parent?.let { directories.add(it) }
                }
            }
            LogUtils.i(this, "Found ${directories.size} unique audio directories")
            return@withLock directories
        }
    }

    override fun getAllUniqueAlbumArtUris(): Flow<List<Uri>> {
        return musicDao.getAllUniqueAlbumArtUrisFromSongs().map { uriStrings ->
            uriStrings.mapNotNull { it.toUri() }
        }.flowOn(Dispatchers.IO)
    }

    override fun searchSongs(query: String, titleOnly: Boolean): Flow<List<Song>> {
        if (query.isBlank()) return flowOf(emptyList())
        return combine(
            userPreferencesRepository.allowedDirectoriesFlow,
            userPreferencesRepository.blockedDirectoriesFlow
        ) { allowedDirs, blockedDirs ->
            allowedDirs to blockedDirs
        }.flatMapLatest { (allowedDirs, blockedDirs) ->
            flow {
                val (allowedParentDirs, applyDirectoryFilter) =
                    computeAllowedDirs(allowedDirs, blockedDirs)
                emit(
                    musicDao.searchSongsLimited(
                        query = query,
                        allowedParentDirs = allowedParentDirs,
                        applyDirectoryFilter = applyDirectoryFilter,
                        limit = SEARCH_RESULTS_LIMIT,
                        titleOnly = titleOnly
                    )
                )
            }.flatMapLatest { it }
        }.toSongsWithFavoriteState().flowOn(Dispatchers.IO)
    }


    override fun searchSongsByLyrics(query: String): Flow<List<Song>> {
        if (query.isBlank()) return flowOf(emptyList())
        return combine(
            userPreferencesRepository.allowedDirectoriesFlow,
            userPreferencesRepository.blockedDirectoriesFlow
        ) { allowedDirs, blockedDirs ->
            allowedDirs to blockedDirs
        }.flatMapLatest { (allowedDirs, blockedDirs) ->
            flow {
                val (allowedParentDirs, applyDirectoryFilter) =
                    computeAllowedDirs(allowedDirs, blockedDirs)
                emit(
                    musicDao.searchSongsByLyricsLimited(
                        query = query,
                        allowedParentDirs = allowedParentDirs,
                        applyDirectoryFilter = applyDirectoryFilter,
                        limit = SEARCH_RESULTS_LIMIT
                    )
                )
            }.flatMapLatest { it }
        }.toSongsWithFavoriteState().flowOn(Dispatchers.IO)
    }

    override fun searchAlbums(query: String, minTracks: Int): Flow<List<Album>> {
        if (query.isBlank()) return flowOf(emptyList())
        return musicDao.searchAlbums(query, emptyList(), false, minTracks).map { entities ->
            entities.map { it.toAlbum() }
        }.flowOn(Dispatchers.IO)
    }

    override fun searchArtists(query: String): Flow<List<Artist>> {
        if (query.isBlank()) return flowOf(emptyList())
        return musicDao.searchArtists(query, emptyList(), false).map { entities ->
            entities.map { it.toArtist() }
        }.withFolderArtwork().flowOn(Dispatchers.IO)
    }

    override suspend fun searchPlaylists(query: String): List<Playlist> {
        if (query.isBlank()) return emptyList()
        return playlistPreferencesRepository.userPlaylistsFlow.first()
            .filter { playlist ->
                playlist.name.contains(query, ignoreCase = true)
            }
    }

    override fun searchAll(query: String, filterType: SearchFilterType): Flow<List<SearchResultItem>> {
        if (query.isBlank()) return flowOf(emptyList())
        val playlistsFlow = flow { emit(searchPlaylists(query)) }

        return combine(
            userPreferencesRepository.minTracksPerAlbumFlow
        ) { (minTracks) ->
            minTracks
        }.flatMapLatest { minTracks ->
            when (filterType) {
                SearchFilterType.ALL -> {
                    combine(
                        searchSongs(query),
                        searchAlbums(query, minTracks),
                        searchArtists(query),
                        playlistsFlow
                    ) { songs, albums, artists, playlists ->
                        mutableListOf<SearchResultItem>().apply {
                            songs.forEach { add(SearchResultItem.SongItem(it)) }
                            albums.forEach { add(SearchResultItem.AlbumItem(it)) }
                            artists.forEach { add(SearchResultItem.ArtistItem(it)) }
                            playlists.forEach { add(SearchResultItem.PlaylistItem(it)) }
                        }
                    }
                }
                SearchFilterType.SONGS -> searchSongs(query, titleOnly = true).map { songs -> songs.map { SearchResultItem.SongItem(it) } }
                SearchFilterType.ALBUMS -> searchAlbums(query, minTracks).map { albums -> albums.map { SearchResultItem.AlbumItem(it) } }
                SearchFilterType.ARTISTS -> searchArtists(query).map { artists -> artists.map { SearchResultItem.ArtistItem(it) } }
                SearchFilterType.PLAYLISTS -> playlistsFlow.map { playlists -> playlists.map { SearchResultItem.PlaylistItem(it) } }
            }
        }.flowOn(Dispatchers.Default)
    }

    override suspend fun addSearchHistoryItem(query: String) {
        withContext(Dispatchers.IO) {
            searchHistoryDao.deleteByQuery(query)
            searchHistoryDao.insert(SearchHistoryEntity(query = query, timestamp = System.currentTimeMillis()))
        }
    }

    override suspend fun getRecentSearchHistory(limit: Int): List<SearchHistoryItem> {
        return withContext(Dispatchers.IO) {
            searchHistoryDao.getRecentSearches(limit).map { it.toSearchHistoryItem() }
        }
    }

    override suspend fun deleteSearchHistoryItemByQuery(query: String) {
        withContext(Dispatchers.IO) {
            searchHistoryDao.deleteByQuery(query)
        }
    }

    override suspend fun clearSearchHistory() {
        withContext(Dispatchers.IO) {
            searchHistoryDao.clearAll()
        }
    }

    override fun getMusicByGenre(genreId: String): Flow<List<Song>> {
        return combine(
            userPreferencesRepository.mockGenresEnabledFlow,
            userPreferencesRepository.allowedDirectoriesFlow,
            userPreferencesRepository.blockedDirectoriesFlow
        ) { mockEnabled, allowedDirs, blockedDirs ->
            Triple(mockEnabled, allowedDirs, blockedDirs)
        }.flatMapLatest { (mockEnabled, allowedDirs, blockedDirs) ->
            flow {
                val (allowedParentDirs, applyDirectoryFilter) =
                    computeAllowedDirs(allowedDirs, blockedDirs)
                val genreName = if (mockEnabled) "Mock" else genreId
                emit(
                    if (genreName.equals("unknown", ignoreCase = true)) {
                        musicDao.getSongsWithNullGenre(
                            allowedParentDirs = allowedParentDirs,
                            applyDirectoryFilter = applyDirectoryFilter
                        )
                    } else {
                        // getSongsByGenreContaining uses a LIKE query so that a song stored as
                        // "Rock, Pop" is returned when browsing either "Rock" or "Pop".
                        musicDao.getSongsByGenreContaining(
                            genreName = genreName,
                            genrePrefix = "$genreName,%",          // "Rock,..." / "Rock, ..."
                            genreSuffixWithSpace = "%, $genreName", // "..., Rock"
                            genreSuffix = "%,$genreName",          // "...,Rock"
                            genreMiddleWithSpace = "%, $genreName,%", // "..., Rock,..."
                            genreMiddle = "%,$genreName,%",        // "...,Rock,..."
                            allowedParentDirs = allowedParentDirs,
                            applyDirectoryFilter = applyDirectoryFilter
                        )
                    }
                )
            }.flatMapLatest { it }
        }.toSongsWithFavoriteState().flowOn(Dispatchers.IO)
    }

    override fun getSongsByIds(songIds: List<String>): Flow<List<Song>> {
        if (songIds.isEmpty()) return flowOf(emptyList())
        val longIds = songIds.mapNotNull { it.toLongOrNull() }
        if (longIds.isEmpty()) return flowOf(emptyList())
        return musicDao.getSongsByIds(longIds, emptyList(), false).toSongsWithFavoriteState().map { songs ->
            val songMap = songs.associateBy { it.id }
            songIds.mapNotNull { songMap[it] }
        }.flowOn(Dispatchers.IO)
    }

    override suspend fun getSongByPath(path: String): Song? {
        return withContext(Dispatchers.IO) {
            musicDao.getSongByPath(path).toSongWithFavoriteStateOnce()
        }
    }

    override suspend fun invalidateCachesDependentOnAllowedDirectories() {
        Timber.tag("MusicRepo").i("invalidateCachesDependentOnAllowedDirectories called. Reactive flows will update automatically.")
    }

    override suspend fun getAllSongsOnce(): List<Song> = withContext(Dispatchers.IO) {
        val allowedDirs = userPreferencesRepository.allowedDirectoriesFlow.first()
        val blockedDirs = userPreferencesRepository.blockedDirectoriesFlow.first()
        val (allowedParentDirs, applyDirectoryFilter) =
            computeAllowedDirs(allowedDirs, blockedDirs)
        musicDao.getAllSongs(
            allowedParentDirs = allowedParentDirs,
            applyDirectoryFilter = applyDirectoryFilter
        ).first().toSongsWithFavoriteStateOnce()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun getDistinctAlbumArtSongs(): Flow<List<Song>> {
        return combine(
            userPreferencesRepository.allowedDirectoriesFlow,
            userPreferencesRepository.blockedDirectoriesFlow
        ) { allowedDirs, blockedDirs ->
            allowedDirs to blockedDirs
        }.flatMapLatest { (allowedDirs, blockedDirs) ->
            flow {
                val (allowedParentDirs, applyDirectoryFilter) =
                    computeAllowedDirs(allowedDirs, blockedDirs)
                emit(
                    musicDao.getDistinctAlbumArtSongs(
                        allowedParentDirs = allowedParentDirs,
                        applyDirectoryFilter = applyDirectoryFilter
                    )
                )
            }.flatMapLatest { it }
        }.toSongsWithFavoriteState().distinctUntilChanged().flowOn(Dispatchers.IO)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun getHomeMixPreviewSongs(limit: Int): Flow<List<Song>> {
        return combine(
            userPreferencesRepository.allowedDirectoriesFlow,
            userPreferencesRepository.blockedDirectoriesFlow
        ) { allowedDirs, blockedDirs ->
            allowedDirs to blockedDirs
        }.flatMapLatest { (allowedDirs, blockedDirs) ->
            flow {
                val (allowedParentDirs, applyDirectoryFilter) =
                    computeAllowedDirs(allowedDirs, blockedDirs)
                emit(
                    musicDao.getHomeMixPreviewSongs(
                        limit = limit,
                        allowedParentDirs = allowedParentDirs,
                        applyDirectoryFilter = applyDirectoryFilter
                    )
                )
            }.flatMapLatest { it }
        }.toSongsWithFavoriteState().distinctUntilChanged().flowOn(Dispatchers.IO)
    }

    override suspend fun getAllAlbumsOnce(storageFilter: StorageFilter, minTracks: Int): List<Album> = withContext(Dispatchers.IO) {
        val filter = cachedDirFilter.value
        musicDao.getAlbumsPage(
            allowedParentDirs = filter.allowedParentDirs,
            applyDirectoryFilter = filter.applyFilter,
            sortOrder = SortOption.AlbumTitleAZ.storageKey,
            filterMode = storageFilter.toFilterMode(),
            minTracks = minTracks,
            limit = Int.MAX_VALUE,
            offset = 0
        ).map { it.toAlbum() }
    }

    override suspend fun getAllArtistsOnce(): List<Artist> = withContext(Dispatchers.IO) {
        val filter = cachedDirFilter.value
        musicDao.getArtistsWithSongCountsFiltered(
            allowedParentDirs = filter.allowedParentDirs,
            applyDirectoryFilter = filter.applyFilter,
            filterMode = StorageFilter.ALL.toFilterMode()
        ).first().map { it.toArtist().withFolderArtwork() }
    }

    override suspend fun setFavoriteStatus(songId: String, isFavorite: Boolean) = withContext(Dispatchers.IO) {
        val id = songId.toLongOrNull() ?: return@withContext
        if (isFavorite) {
            // 保留已存在的评分：setFavorite 是 REPLACE，不带 rating 会把它覆盖回 0。
            val existingRating = favoritesDao.getRating(id) ?: 0
            favoritesDao.setFavorite(
                com.pisces312.piscesplayer.data.database.FavoritesEntity(
                    songId = id,
                    isFavorite = true,
                    rating = existingRating
                )
            )
        } else {
            // 软删除：只清收藏标记，保留评分；两者皆空再清行。
            favoritesDao.clearFavoriteFlag(id)
            favoritesDao.purgeIfEmpty(id)
        }
    }

    override suspend fun setSongRating(songId: String, rating: Int) = withContext(Dispatchers.IO) {
        val id = songId.toLongOrNull() ?: return@withContext
        val normalized = rating.coerceIn(0, 5)
        favoritesDao.upsertRating(id, normalized, System.currentTimeMillis())
        // 评分被清除后，若该行既未收藏也无评分则删除
        if (normalized == 0) {
            favoritesDao.purgeIfEmpty(id)
        }
    }

    override suspend fun getSongRating(songId: String): Int? = withContext(Dispatchers.IO) {
        val id = songId.toLongOrNull() ?: return@withContext null
        favoritesDao.getRating(id)
    }

    override fun observeSongRating(songId: String): Flow<Int> {
        val id = songId.toLongOrNull() ?: return flowOf(0)
        return favoritesDao.observeRating(id).map { it ?: 0 }
    }

    override suspend fun getFavoriteSongIdsOnce(): Set<String> = withContext(Dispatchers.IO) {
        favoritesDao.getFavoriteSongIdsOnce()
            .map { it.toString() }
            .toSet()
    }

    override fun getFavoriteSongIdsFlow(): Flow<Set<String>> {
        return favoritesDao.getFavoriteSongIds()
            .map { ids -> ids.asSequence().map(Long::toString).toSet() }
            .distinctUntilChanged()
    }

    override suspend fun toggleFavoriteStatus(songId: String): Boolean = withContext(Dispatchers.IO) {
        val id = songId.toLongOrNull() ?: return@withContext false
        val isFav = favoritesDao.isFavorite(id) ?: false
        val newFav = !isFav
        setFavoriteStatus(songId, newFav)
        return@withContext newFav
    }

    override fun getSong(songId: String): Flow<Song?> {
        val longId = songId.toLongOrNull()
        return if (longId != null) {
            musicDao.getSongById(longId).toSongWithFavoriteState().flowOn(Dispatchers.IO)
        } else {
            flowOf(null)
        }
    }

    override fun getGenres(): Flow<List<Genre>> {
        return combine(
            userPreferencesRepository.allowedDirectoriesFlow,
            userPreferencesRepository.blockedDirectoriesFlow
        ) { allowedDirs, blockedDirs ->
            allowedDirs to blockedDirs
        }.flatMapLatest { (allowedDirs, blockedDirs) ->
            flow {
                val (allowedParentDirs, applyDirectoryFilter) =
                    computeAllowedDirs(allowedDirs, blockedDirs)
                emit(
                    combine(
                        musicDao.getUniqueGenres(
                            allowedParentDirs = allowedParentDirs,
                            applyDirectoryFilter = applyDirectoryFilter
                        ),
                        musicDao.hasUnknownGenre(
                            allowedParentDirs = allowedParentDirs,
                            applyDirectoryFilter = applyDirectoryFilter
                        )
                    ) { genreNames, hasUnknown ->
                        val knownGenres = genreNames
                            .asSequence()
                            .flatMap { raw -> raw.split(",") } // split "Rock, Pop" → ["Rock", "Pop"]
                            .map { it.trim() }
                            .filter { it.isNotBlank() }
                            .map { buildGenre(it) }
                            .distinctBy { it.id }
                            .sortedBy { it.name.lowercase() }
                            .toList()
                        val unknownAlreadyPresent = knownGenres.any { it.id == UNKNOWN_GENRE_ID }
                        if (hasUnknown && !unknownAlreadyPresent) {
                            knownGenres + buildGenre(UNKNOWN_GENRE_NAME)
                        } else {
                            knownGenres
                        }
                    }
                )
            }.flatMapLatest { it }
        }.conflate().flowOn(Dispatchers.IO)
    }

    override fun getYearBuckets(sortOption: SortOption): Flow<List<YearBucket>> {
        return combine(
            userPreferencesRepository.allowedDirectoriesFlow,
            userPreferencesRepository.blockedDirectoriesFlow
        ) { allowedDirs, blockedDirs ->
            allowedDirs to blockedDirs
        }.flatMapLatest { (allowedDirs, blockedDirs) ->
            val (allowedParentDirs, applyDirectoryFilter) =
                computeAllowedDirs(allowedDirs, blockedDirs)
            // Known years are ordered in SQL; the unknown-year bucket is always appended last,
            // regardless of sort direction.
            combine(
                musicDao.getYearBuckets(
                    allowedParentDirs = allowedParentDirs,
                    applyDirectoryFilter = applyDirectoryFilter,
                    sortOrder = sortOption.storageKey
                ),
                musicDao.getUnknownYearCount(
                    allowedParentDirs = allowedParentDirs,
                    applyDirectoryFilter = applyDirectoryFilter
                )
            ) { knownRows, unknownCount ->
                buildList {
                    knownRows.forEach { row -> add(YearBucket(row.year, row.songCount)) }
                    if (unknownCount > 0) add(YearBucket(YearBucket.UNKNOWN_YEAR, unknownCount))
                }
            }
        }.conflate().flowOn(Dispatchers.IO)
    }

    override fun getSongsByYear(year: Int, sortOption: SortOption): Flow<List<Song>> {
        return combine(
            userPreferencesRepository.allowedDirectoriesFlow,
            userPreferencesRepository.blockedDirectoriesFlow
        ) { allowedDirs, blockedDirs ->
            allowedDirs to blockedDirs
        }.flatMapLatest { (allowedDirs, blockedDirs) ->
            val (allowedParentDirs, applyDirectoryFilter) =
                computeAllowedDirs(allowedDirs, blockedDirs)
            musicDao.getSongsByYear(
                year = year,
                allowedParentDirs = allowedParentDirs,
                applyDirectoryFilter = applyDirectoryFilter,
                sortOrder = sortOption.storageKey
            )
        }.toSongsWithFavoriteState().distinctUntilChanged().conflate().flowOn(Dispatchers.IO)
    }

    private fun buildGenre(genreName: String): Genre {
        val id = if (genreName.equals(UNKNOWN_GENRE_NAME, ignoreCase = true)) {
            UNKNOWN_GENRE_ID
        } else {
            genreName
                .lowercase()
                .replace(" ", "_")
                .replace("/", "_")
        }
        val lightThemeColor = GenreThemeUtils.getGenreThemeColor(id, isDark = false)
        val darkThemeColor = GenreThemeUtils.getGenreThemeColor(id, isDark = true)
        return Genre(
            id = id,
            name = genreName,
            lightColorHex = lightThemeColor.container.toHexString(),
            onLightColorHex = lightThemeColor.onContainer.toHexString(),
            darkColorHex = darkThemeColor.container.toHexString(),
            onDarkColorHex = darkThemeColor.onContainer.toHexString()
        )
    }

    override suspend fun getLyrics(
        song: Song,
        sourcePreference: LyricsSourcePreference,
        forceRefresh: Boolean
    ): Lyrics? {
        return lyricsRepository.getLyrics(song, sourcePreference, forceRefresh)
    }

    override suspend fun getStoredLyrics(song: Song): Pair<Lyrics, String>? {
        return lyricsRepository.getStoredLyrics(song)
    }

    /**
     * Fetches a song's lyrics from the LRCLIB API, persists them in the database
     * and returns them as a parsed Lyrics object.
     *
     * @param song The song to look up lyrics for.
     * @return A Result object containing the Lyrics object if found, or an error.
     */
    override suspend fun getLyricsFromRemote(song: Song): Result<Pair<Lyrics, String>> {
        return lyricsRepository.fetchFromRemote(song)
    }

    override suspend fun searchRemoteLyrics(song: Song): Result<Pair<String, List<LyricsSearchResult>>> {
        return lyricsRepository.searchRemote(song)
    }

    override suspend fun searchRemoteLyricsByQuery(title: String, artist: String?): Result<Pair<String, List<LyricsSearchResult>>> {
        return lyricsRepository.searchRemoteByQuery(title, artist)
    }

    override suspend fun updateLyrics(songId: Long, lyrics: String) {
        lyricsRepository.updateLyrics(songId, lyrics)
    }

    override suspend fun resetLyrics(songId: Long) {
        lyricsRepository.resetLyrics(songId)
    }

    override suspend fun resetAllLyrics() {
        lyricsRepository.resetAllLyrics()
    }

    override fun getMusicFolders(storageFilter: StorageFilter): Flow<List<MusicFolder>> {
        return combine(
            userPreferencesRepository.allowedDirectoriesFlow,
            userPreferencesRepository.blockedDirectoriesFlow,
            userPreferencesRepository.isFolderFilterActiveFlow,
            userPreferencesRepository.foldersSourceFlow
        ) { allowedDirs, blockedDirs, isFolderFilterActive, folderSource ->
            FolderFlowConfig(
                allowedDirs = allowedDirs,
                blockedDirs = blockedDirs,
                isFolderFilterActive = isFolderFilterActive,
                folderSource = folderSource
            )
        }.flatMapLatest { config ->
            flow {
                val (allowedParentDirs, applyDirectoryFilter) = computeAllowedDirs(
                    allowedDirs = config.allowedDirs,
                    blockedDirs = config.blockedDirs
                )
                emit(
                    musicDao.getFolderSongs(
                        allowedParentDirs = allowedParentDirs,
                        applyDirectoryFilter = applyDirectoryFilter,
                        filterMode = storageFilter.toFilterMode()
                    ).map { folderSongs ->
                        folderTreeBuilder.buildFolderTree(
                            folderSongs = folderSongs,
                            allowedDirs = config.allowedDirs,
                            blockedDirs = config.blockedDirs,
                            isFolderFilterActive = config.isFolderFilterActive,
                            folderSource = config.folderSource,
                            context = context
                        )
                    }
                )
            }.flatMapLatest { it }
        }.conflate().flowOn(Dispatchers.IO)
    }

    private data class FolderFlowConfig(
        val allowedDirs: Set<String>,
        val blockedDirs: Set<String>,
        val isFolderFilterActive: Boolean,
        val folderSource: FolderSource
    )

    override suspend fun deleteById(id: Long) {
        musicDao.deleteById(id)
    }

    override suspend fun getSongIdsSorted(
        sortOption: SortOption,
        storageFilter: com.pisces312.piscesplayer.data.model.StorageFilter
    ): List<Long> = withContext(Dispatchers.IO) {
        val filter = cachedDirFilter.value
        musicDao.getSongIdsSorted(
            allowedParentDirs = filter.allowedParentDirs,
            applyDirectoryFilter = filter.applyFilter,
            sortOrder = sortOption.storageKey,
            filterMode = storageFilter.toFilterMode()
        )
    }

    override suspend fun getFavoriteSongIdsSorted(
        sortOption: SortOption,
        storageFilter: com.pisces312.piscesplayer.data.model.StorageFilter
    ): List<Long> = withContext(Dispatchers.IO) {
        val filter = cachedDirFilter.value
        musicDao.getFavoriteSongIdsSorted(
            allowedParentDirs = filter.allowedParentDirs,
            applyDirectoryFilter = filter.applyFilter,
            sortOrder = sortOption.storageKey,
            filterMode = storageFilter.toFilterMode()
        )
    }

    override suspend fun getSongIdByContentUri(contentUri: String): Long? =
        withContext(Dispatchers.IO) {
            musicDao.getSongIdByContentUri(contentUri)
        }

}
