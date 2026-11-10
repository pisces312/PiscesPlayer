@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class
)

package com.pisces312.piscesplayer.presentation.screens

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.pisces312.piscesplayer.data.model.LibraryTabId
import com.pisces312.piscesplayer.data.model.StorageFilter
import com.pisces312.piscesplayer.presentation.navigation.Screen
import com.pisces312.piscesplayer.presentation.navigation.navigateSafely
import com.pisces312.piscesplayer.presentation.screens.library.components.GenreCategoriesGrid
import com.pisces312.piscesplayer.presentation.viewmodel.PlayerViewModel
import kotlinx.collections.immutable.persistentListOf

/**
 * Library "Genres" tab (L1): browse the library by genre.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun GenresTabContent(
    playerViewModel: PlayerViewModel,
    navController: NavController,
    bottomBarHeight: Dp,
    isRefreshing: Boolean,
    onRefresh: () -> Unit
) {
    val genres by playerViewModel.genres.collectAsStateWithLifecycle(
        initialValue = persistentListOf()
    )
    val pullState = rememberPullToRefreshState()

    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = onRefresh,
        state = pullState,
        modifier = Modifier.fillMaxSize(),
        indicator = {
            PullToRefreshDefaults.LoadingIndicator(
                state = pullState,
                isRefreshing = isRefreshing,
                modifier = Modifier.align(Alignment.TopCenter)
            )
        }
    ) {
        if (genres.isEmpty()) {
            LibraryExpressiveEmptyState(
                tabId = LibraryTabId.GENRES,
                storageFilter = StorageFilter.ALL,
                bottomBarHeight = bottomBarHeight
            )
            return@PullToRefreshBox
        }

        GenreCategoriesGrid(
            genres = genres,
            onGenreClick = { genre ->
                val encodedGenreId = java.net.URLEncoder.encode(genre.id, "UTF-8")
                navController.navigateSafely(Screen.GenreDetail.createRoute(encodedGenreId))
            },
            playerViewModel = playerViewModel,
            modifier = Modifier.fillMaxSize()
        )
    }
}
