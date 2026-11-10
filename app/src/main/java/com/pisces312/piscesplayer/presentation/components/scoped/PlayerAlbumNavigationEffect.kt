package com.pisces312.piscesplayer.presentation.components.scoped

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.navigation.NavHostController
import com.pisces312.piscesplayer.presentation.navigation.Screen
import com.pisces312.piscesplayer.presentation.navigation.navigateSafelyReplacing
import com.pisces312.piscesplayer.presentation.viewmodel.PlayerViewModel
import kotlinx.coroutines.flow.collectLatest

@Composable
internal fun PlayerAlbumNavigationEffect(
    navController: NavHostController,
    sheetCollapsedTargetY: Float,
    sheetMotionController: SheetMotionController,
    playerViewModel: PlayerViewModel
) {
    val latestSheetCollapsedTargetY by rememberUpdatedState(sheetCollapsedTargetY)
    LaunchedEffect(navController) {
        playerViewModel.albumNavigationRequests.collectLatest { albumId ->
            sheetMotionController.snapCollapsed(latestSheetCollapsedTargetY)
            playerViewModel.collapsePlayerSheet()

            navController.navigateSafelyReplacing(
                route = Screen.AlbumDetail.createRoute(albumId),
                patternToPop = Screen.AlbumDetail.route
            )
        }
    }
}
