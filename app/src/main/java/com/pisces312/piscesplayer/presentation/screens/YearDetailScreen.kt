package com.pisces312.piscesplayer.presentation.screens

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.navigation.NavHostController
import com.pisces312.piscesplayer.R
import com.pisces312.piscesplayer.data.media.CustomMetadataChanges
import com.pisces312.piscesplayer.data.model.Song
import com.pisces312.piscesplayer.data.model.SortOption
import com.pisces312.piscesplayer.data.model.YearBucket
import com.pisces312.piscesplayer.presentation.components.LibrarySortBottomSheet
import com.pisces312.piscesplayer.presentation.components.MiniPlayerHeight
import com.pisces312.piscesplayer.presentation.components.PlaylistBottomSheet
import com.pisces312.piscesplayer.presentation.components.SongInfoBottomSheet
import com.pisces312.piscesplayer.presentation.components.subcomps.EnhancedSongListItem
import com.pisces312.piscesplayer.presentation.navigation.Screen
import com.pisces312.piscesplayer.presentation.navigation.navigateSafely
import com.pisces312.piscesplayer.presentation.viewmodel.PlayerViewModel
import com.pisces312.piscesplayer.presentation.viewmodel.PlaylistViewModel
import com.pisces312.piscesplayer.presentation.viewmodel.YearDetailViewModel
import com.pisces312.piscesplayer.utils.formatDuration
import com.pisces312.piscesplayer.utils.formatSongCount
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Year smart playlist detail (L2): shows all songs for a given year or unknown year.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun YearDetailScreen(
    navController: NavHostController,
    playerViewModel: PlayerViewModel,
    paddingValues: PaddingValues,
    viewModel: YearDetailViewModel = hiltViewModel(),
    playlistViewModel: PlaylistViewModel = hiltViewModel()
) {
    val songs by viewModel.songs.collectAsStateWithLifecycle()
    val currentSortOption by viewModel.currentSortOption.collectAsStateWithLifecycle()
    val playlistUiState by playlistViewModel.uiState.collectAsStateWithLifecycle()
    val favoriteSongIds by playerViewModel.favoriteSongIds.collectAsStateWithLifecycle()

    val currentSongId by remember(playerViewModel.stablePlayerState) {
        playerViewModel.stablePlayerState.map { it.currentSong?.id }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(initialValue = null)
    val isPlaying by remember(playerViewModel.stablePlayerState) {
        playerViewModel.stablePlayerState.map { it.isPlaying }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(initialValue = false)

    var showSortSheet by remember { mutableStateOf(false) }
    var showSongInfoBottomSheet by remember { mutableStateOf(false) }
    var showPlaylistBottomSheet by remember { mutableStateOf(false) }
    var selectedSongForInfo by remember { mutableStateOf<Song?>(null) }

    val year = viewModel.year
    val isUnknown = year == YearBucket.UNKNOWN_YEAR
    val title = if (isUnknown) stringResource(R.string.unknown_year) else year.toString()
    val queueName = title
    val totalDurationMs = remember(songs) { songs.sumOf { it.duration } }
    val bottomBarHeightDp = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val context = LocalContext.current

    val bgColors = listOf(
        MaterialTheme.colorScheme.secondary.copy(alpha = 0.24f),
        MaterialTheme.colorScheme.surface.copy(alpha = 0.55f),
        MaterialTheme.colorScheme.surface
    )
    val backgroundBrush = remember(bgColors) {
        Brush.verticalGradient(colors = bgColors, endY = 1200f)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundBrush)
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                bottom = paddingValues.calculateBottomPadding() + MiniPlayerHeight + 16.dp
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item(key = "year_detail_header") {
                YearDetailHeader(
                    title = title,
                    isUnknown = isUnknown,
                    songCount = songs.size,
                    totalDurationMs = totalDurationMs
                )
            }

            if (songs.isNotEmpty()) {
                item(key = "year_detail_actions") {
                    YearDetailActions(
                        onPlay = {
                            val first = songs.firstOrNull() ?: return@YearDetailActions
                            playerViewModel.playSongs(songs.toImmutableList(), first, queueName)
                        },
                        onShuffle = {
                            playerViewModel.playSongsShuffled(
                                songsToPlay = songs.toImmutableList(),
                                queueName = queueName,
                                startAtZero = true
                            )
                        },
                        onSort = { showSortSheet = true },
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                }

                items(items = songs, key = { it.id }, contentType = { "year_song" }) { song ->
                    EnhancedSongListItem(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        song = song,
                        isCurrentSong = currentSongId == song.id,
                        isPlaying = currentSongId == song.id && isPlaying,
                        onClick = {
                            playerViewModel.playSongs(songs.toImmutableList(), song, queueName)
                        },
                        onMoreOptionsClick = { tapped ->
                            selectedSongForInfo = tapped
                            showSongInfoBottomSheet = true
                        }
                    )
                }
            } else {
                item(key = "year_detail_empty") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 48.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = stringResource(R.string.year_detail_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }

        FilledIconButton(
            onClick = { navController.popBackStack() },
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface
            ),
            modifier = Modifier
                .statusBarsPadding()
                .padding(start = 10.dp, top = 8.dp)
                .clip(CircleShape)
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                contentDescription = stringResource(R.string.year_detail_back)
            )
        }
    }

    if (showSortSheet) {
        LibrarySortBottomSheet(
            title = stringResource(R.string.year_detail_sort_by),
            options = SortOption.YEAR_SONGS.toImmutableList(),
            selectedOption = currentSortOption,
            onDismiss = { showSortSheet = false },
            onOptionSelected = { option ->
                viewModel.setSortOption(option)
                showSortSheet = false
            },
            onDirectionToggle = { option -> viewModel.setSortOption(option) }
        )
    }

    val infoSong = selectedSongForInfo
    if (showSongInfoBottomSheet && infoSong != null) {
        SongInfoBottomSheet(
            song = infoSong,
            isFavorite = favoriteSongIds.contains(infoSong.id),
            onToggleFavorite = { playerViewModel.toggleFavoriteSpecificSong(infoSong) },
            onDismiss = {
                showSongInfoBottomSheet = false
                showPlaylistBottomSheet = false
            },
            onPlaySong = { playerViewModel.playSongs(songs.toImmutableList(), infoSong, queueName) },
            onAddToQueue = { playerViewModel.addSongToQueue(infoSong) },
            onAddNextToQueue = { playerViewModel.addSongNextToQueue(infoSong) },
            onNavigateToRhythmGame = {
                navController.navigateSafely(Screen.RhythmSetup.createRoute(infoSong.id))
                showSongInfoBottomSheet = false
            },
            onAddToPlayList = { showPlaylistBottomSheet = true },
            onDeleteFromDevice = { activity, song, onResult ->
                playerViewModel.deleteFromDevice(activity, song, onResult)
            },
            onNavigateToAlbum = {
                navController.navigateSafely(Screen.AlbumDetail.createRoute(infoSong.albumId))
                showSongInfoBottomSheet = false
            },
            onNavigateToArtist = {
                navController.navigateSafely(Screen.ArtistDetail.createRoute(infoSong.artistId))
                showSongInfoBottomSheet = false
            },
            onNavigateToArtistById = { artistId ->
                navController.navigateSafely(Screen.ArtistDetail.createRoute(artistId))
                showSongInfoBottomSheet = false
            },
            onNavigateToGenre = {
                infoSong.genre?.let {
                    navController.navigateSafely(
                        Screen.GenreDetail.createRoute(java.net.URLEncoder.encode(it, "UTF-8"))
                    )
                }
                showSongInfoBottomSheet = false
            },
            onNavigateToYear = { year ->
                navController.navigateSafely(Screen.YearDetail.createRoute(year))
                showSongInfoBottomSheet = false
            },
            onEditSong = { newTitle, newArtist, newAlbum, newAlbumArtist, newComposer, newGenre, newLyrics, newTrackNumber, newDiscNumber, replayGainTrackGainDb, replayGainAlbumGainDb, coverArtUpdate, customMetadataChanges ->
                playerViewModel.editSongMetadata(
                    infoSong,
                    newTitle,
                    newArtist,
                    newAlbum,
                    newAlbumArtist,
                    newComposer,
                    newGenre,
                    newLyrics,
                    newTrackNumber,
                    newDiscNumber,
                    replayGainTrackGainDb,
                    replayGainAlbumGainDb,
                    coverArtUpdate,
                    customMetadataChanges
                )
            },
            removeFromListTrigger = {}
        )

        if (showPlaylistBottomSheet) {
            PlaylistBottomSheet(
                playlistUiState = playlistUiState,
                songs = listOf(infoSong).toImmutableList(),
                onDismiss = { showPlaylistBottomSheet = false },
                bottomBarHeight = bottomBarHeightDp,
                playerViewModel = playerViewModel
            )
        }
    }
}

@Composable
private fun YearDetailHeader(
    title: String,
    isUnknown: Boolean,
    songCount: Int,
    totalDurationMs: Long
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 96.dp, bottom = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(128.dp)
                .clip(RoundedCornerShape(28.dp))
                .background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center
        ) {
            if (isUnknown) {
                Icon(
                    painter = painterResource(R.drawable.rounded_calendar_view_week_24),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.size(56.dp)
                )
            } else {
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    maxLines = 1
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        Text(
            text = title,
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        Spacer(Modifier.height(6.dp))

        val meta = buildString {
            append(formatSongCount(songCount))
            if (totalDurationMs > 0) {
                append(" · ")
                append(formatDuration(totalDurationMs))
            }
        }
        Text(
            text = meta,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun YearDetailActions(
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    onSort: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        FilledTonalButton(
            onClick = onPlay,
            modifier = Modifier.weight(1f)
        ) {
            Icon(Icons.Rounded.PlayArrow, contentDescription = null)
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.year_detail_play), maxLines = 1)
        }
        FilledTonalButton(
            onClick = onShuffle,
            modifier = Modifier.weight(1f)
        ) {
            Icon(Icons.Rounded.Shuffle, contentDescription = null)
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.year_detail_shuffle), maxLines = 1)
        }
        IconButton(onClick = onSort) {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.Sort,
                contentDescription = stringResource(R.string.year_detail_sort)
            )
        }
    }
}
