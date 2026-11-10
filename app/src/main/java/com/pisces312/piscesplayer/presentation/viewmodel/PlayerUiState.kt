package com.pisces312.piscesplayer.presentation.viewmodel

import androidx.compose.runtime.Immutable
import com.pisces312.piscesplayer.data.model.Album
import com.pisces312.piscesplayer.data.model.Artist
import com.pisces312.piscesplayer.data.model.FolderSource
import com.pisces312.piscesplayer.data.model.MusicFolder
import com.pisces312.piscesplayer.data.model.Song
import com.pisces312.piscesplayer.data.model.SearchResultItem
import com.pisces312.piscesplayer.data.model.SortOption
import com.pisces312.piscesplayer.data.model.SearchFilterType
import com.pisces312.piscesplayer.data.model.SearchHistoryItem
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

@Immutable
data class PlayerUiState(
    val currentPlaybackQueue: ImmutableList<Song> = persistentListOf(),
    val currentQueueSourceName: String = "All Songs",
    val searchResults: ImmutableList<SearchResultItem> = persistentListOf(),
    val musicFolders: ImmutableList<MusicFolder> = persistentListOf(),
    val sortOption: SortOption = SortOption.SongDefaultOrder,
    val isLoadingInitialSongs: Boolean = true,
    val isLoadingLibrary: Boolean = true,
    val filteredSongs: ImmutableList<Song> = persistentListOf(),
    val isFiltering: Boolean = false,
    val showDismissUndoBar: Boolean = false,
    val dismissedSong: Song? = null,
    val dismissedQueue: ImmutableList<Song> = persistentListOf(),
    val dismissedQueueName: String = "",
    val dismissedPosition: Long = 0L,
    val currentFolder: MusicFolder? = null,
    val currentFolderPath: String? = null,
    val folderSource: FolderSource = FolderSource.INTERNAL,
    val folderSourceRootPath: String = "",
    val isSdCardAvailable: Boolean = false,
    val lavaLampColors: ImmutableList<androidx.compose.ui.graphics.Color> = persistentListOf(),
    val undoBarVisibleDuration: Long = 4000L,
    val isFolderFilterActive: Boolean = false,
    val isFoldersPlaylistView: Boolean = false,
    val preparingSongId: String? = null,
    val isLoadingLibraryCategories: Boolean = true,
    val isAlbumsListView: Boolean = false,
    val currentFavoriteSortOption: SortOption = SortOption.LikedSongDateLiked,
    val currentAlbumSortOption: SortOption = SortOption.AlbumTitleAZ,
    val currentArtistSortOption: SortOption = SortOption.ArtistNameAZ,
    val currentFolderSortOption: SortOption = SortOption.FolderNameAZ,
    val currentYearSortOption: SortOption = SortOption.YearBucketNewest,
    val currentGenreSortOption: SortOption = SortOption.GenreNameAZ,
    val folderBackGestureNavigationEnabled: Boolean = true,
    val currentSongSortOption: SortOption = SortOption.SongTitleAZ,
    val searchHistory: ImmutableList<SearchHistoryItem> = persistentListOf(),
    val searchQuery: String = "",
    val isSyncingLibrary: Boolean = false,
    val selectedSearchFilter: SearchFilterType = SearchFilterType.ALL,
    val currentStorageFilter: com.pisces312.piscesplayer.data.model.StorageFilter = com.pisces312.piscesplayer.data.model.StorageFilter.ALL,
    val hideLocalMedia: Boolean = false,
    val showQueueItemUndoBar: Boolean = false,
    val lastRemovedQueueSong: Song? = null,
    val lastRemovedQueueIndex: Int = -1
)
