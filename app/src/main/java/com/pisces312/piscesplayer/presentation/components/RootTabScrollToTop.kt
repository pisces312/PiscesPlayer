package com.pisces312.piscesplayer.presentation.components

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.pisces312.piscesplayer.presentation.viewmodel.PlayerViewModel
import kotlinx.coroutines.flow.filter
import timber.log.Timber

/**
 * Scrolls [listState] to the top when the user double-taps the bottom-nav / rail
 * entry for [route].
 */
@Composable
fun RootTabScrollToTopEffect(
    playerViewModel: PlayerViewModel,
    route: String,
    listState: LazyListState
) {
    LaunchedEffect(playerViewModel, route, listState) {
        playerViewModel.rootTabDoubleTapEvents
            .filter { it == route }
            .collect {
                Timber.tag("RootTabDoubleTap").d("scroll list to top route=%s", route)
                if (listState.firstVisibleItemIndex != 0 || listState.firstVisibleItemScrollOffset != 0) {
                    listState.scrollToItem(0)
                }
            }
    }
}

/** Same as [RootTabScrollToTopEffect] for a grid (e.g. albums). */
@Composable
fun RootTabScrollToTopGridEffect(
    playerViewModel: PlayerViewModel,
    route: String,
    gridState: LazyGridState
) {
    LaunchedEffect(playerViewModel, route, gridState) {
        playerViewModel.rootTabDoubleTapEvents
            .filter { it == route }
            .collect {
                Timber.tag("RootTabDoubleTap").d("scroll grid to top route=%s", route)
                if (gridState.firstVisibleItemIndex != 0 || gridState.firstVisibleItemScrollOffset != 0) {
                    gridState.scrollToItem(0)
                }
            }
    }
}
