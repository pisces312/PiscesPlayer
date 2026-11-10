package com.pisces312.piscesplayer.presentation.model

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pisces312.piscesplayer.data.model.Song
import com.pisces312.piscesplayer.presentation.viewmodel.PlayerViewModel
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.map

data class RecentlyPlayedHomeState(
    val songs: ImmutableList<RecentlyPlayedSongUiModel>,
    val queue: ImmutableList<Song>
)

@Composable
fun rememberRecentlyPlayedHomeState(
    playerViewModel: PlayerViewModel,
    maxItems: Int = 64
): RecentlyPlayedHomeState {
    val playbackHistory by playerViewModel.playbackHistory.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    val recentSongIds = remember(playbackHistory) {
        collectRecentlyPlayedSongIds(
            playbackHistory = playbackHistory,
            maxItems = maxItems
        )
    }
    val sourceSongsInitialValue = remember(recentSongIds) {
        if (recentSongIds.isEmpty()) persistentListOf<Song>() else null
    }
    val sourceSongs by remember(recentSongIds, playerViewModel) {
        playerViewModel.observeSongs(recentSongIds)
            .map<List<Song>, List<Song>?> { it }
    }.collectAsStateWithLifecycle(initialValue = sourceSongsInitialValue)

    val latestSongs = remember(playbackHistory, sourceSongs) {
        val songs = sourceSongs ?: return@remember persistentListOf()
        mapRecentlyPlayedSongs(
            playbackHistory = playbackHistory,
            songs = songs,
            maxItems = maxItems
        ).toImmutableList()
    }
    // Deliberately not rememberSaveable: the list is derived state that can be rebuilt from
    // playbackHistory, and ImmutableList of Song is not Bundle-storable. Saving it crashed with
    // "MutableState containing [] cannot be saved" whenever the history was empty, and a
    // 64-item Song list would risk TransactionTooLargeException on the Bundle path anyway.
    var songs by remember { mutableStateOf(latestSongs) }
    val latestSongsState = rememberUpdatedState(latestSongs)

    LaunchedEffect(latestSongs, lifecycleOwner) {
        val isVisible = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        if (songs.isEmpty() || !isVisible) {
            songs = latestSongs
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                songs = latestSongsState.value
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val queue = remember(songs) {
        songs.map { it.song }.toImmutableList()
    }

    return RecentlyPlayedHomeState(
        songs = songs,
        queue = queue
    )
}
