package com.pisces312.piscesplayer.presentation.navigation

import DelimiterConfigScreen
import com.pisces312.piscesplayer.presentation.screens.WordDelimiterConfigScreen
import android.annotation.SuppressLint
import androidx.annotation.OptIn
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.pisces312.piscesplayer.R
import androidx.compose.ui.unit.IntOffset
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.media3.common.util.UnstableApi
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.pisces312.piscesplayer.data.preferences.LaunchTab
import com.pisces312.piscesplayer.data.preferences.UserPreferencesRepository
import com.pisces312.piscesplayer.presentation.screens.AlbumDetailScreen
import com.pisces312.piscesplayer.presentation.screens.AudioBookmarkFolderScreen
import com.pisces312.piscesplayer.presentation.screens.AudioBookmarksScreen
import com.pisces312.piscesplayer.presentation.screens.CloudDownloadsScreen
import com.pisces312.piscesplayer.presentation.screens.UpdatesScreen
import com.pisces312.piscesplayer.presentation.model.SettingsCategory
import com.pisces312.piscesplayer.presentation.screens.AccountsScreen
import com.pisces312.piscesplayer.presentation.screens.AiMixesScreen
import com.pisces312.piscesplayer.presentation.screens.ArtistDetailScreen
import com.pisces312.piscesplayer.presentation.screens.ArtistSettingsScreen
import com.pisces312.piscesplayer.presentation.screens.DailyMixScreen
import com.pisces312.piscesplayer.presentation.screens.EditTransitionScreen
import com.pisces312.piscesplayer.presentation.screens.EasterEggScreen
import com.pisces312.piscesplayer.presentation.screens.ExperimentalSettingsScreen
import com.pisces312.piscesplayer.presentation.screens.GenreDetailScreen
import com.pisces312.piscesplayer.presentation.screens.HomeScreen
import com.pisces312.piscesplayer.presentation.screens.LibraryScreen
import com.pisces312.piscesplayer.presentation.screens.MashupScreen
import com.pisces312.piscesplayer.presentation.screens.NavBarCornerRadiusScreen
import com.pisces312.piscesplayer.presentation.screens.PaletteStyleSettingsScreen
import com.pisces312.piscesplayer.presentation.screens.PlaylistDetailScreen
import com.pisces312.piscesplayer.presentation.screens.RecentlyPlayedScreen

import com.pisces312.piscesplayer.presentation.screens.AboutScreen
import com.pisces312.piscesplayer.presentation.screens.SearchScreen
import com.pisces312.piscesplayer.presentation.screens.AiRequestLogScreen
import com.pisces312.piscesplayer.presentation.screens.ListeningStatsScreen
import com.pisces312.piscesplayer.presentation.screens.StatsHotSongsScreen
import com.pisces312.piscesplayer.presentation.screens.StatsTopAlbumsScreen
import com.pisces312.piscesplayer.presentation.screens.StatsTopArtistsScreen
import com.pisces312.piscesplayer.presentation.screens.YearDetailScreen
import com.pisces312.piscesplayer.presentation.screens.DuplicateSongsScreen
import com.pisces312.piscesplayer.presentation.screens.SettingsScreen
import com.pisces312.piscesplayer.presentation.screens.SettingsCategoryScreen
import com.pisces312.piscesplayer.presentation.screens.import.ThirdPartyImportScreen
import com.pisces312.piscesplayer.presentation.screens.EqualizerScreen
import com.pisces312.piscesplayer.presentation.viewmodel.PlayerViewModel
import com.pisces312.piscesplayer.presentation.viewmodel.StatsViewModel
import com.pisces312.piscesplayer.presentation.viewmodel.PlaylistViewModel
import kotlinx.coroutines.flow.first
import com.pisces312.piscesplayer.presentation.components.ScreenWrapper
import com.pisces312.piscesplayer.presentation.rhythm.MusicGameHubScreen
import com.pisces312.piscesplayer.presentation.rhythm.RhythmGameScreen
import com.pisces312.piscesplayer.presentation.rhythm.RhythmSetupScreen
import com.pisces312.piscesplayer.presentation.rhythm.RhythmSongPickerScreen

@OptIn(UnstableApi::class)
@SuppressLint("UnrememberedGetBackStackEntry")
@Composable
fun AppNavigation(
    playerViewModel: PlayerViewModel,
    navController: NavHostController,
    paddingValues: PaddingValues,
    userPreferencesRepository: UserPreferencesRepository,
    onSearchBarActiveChange: (Boolean) -> Unit,
    onOpenSidebar: () -> Unit
) {
    var startDestination by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        startDestination = userPreferencesRepository.launchTabFlow
            .first()
            .toRoute()
    }

    startDestination?.let { initialRoute ->
        NavHost(
            navController = navController,
            startDestination = initialRoute
        ) {
            composable(
                Screen.Home.route,
                enterTransition = {
                    mainRootEnterTransition(
                        fromRoute = initialState.destination.route,
                        toRoute = targetState.destination.route,
                        fallback = enterTransition()
                    )
                },
                exitTransition = {
                    mainRootExitTransition(
                        fromRoute = initialState.destination.route,
                        toRoute = targetState.destination.route,
                        fallback = exitTransition()
                    )
                },
                popEnterTransition = {
                    mainRootEnterTransition(
                        fromRoute = initialState.destination.route,
                        toRoute = targetState.destination.route,
                        fallback = popEnterTransition()
                    )
                },
                popExitTransition = {
                    mainRootExitTransition(
                        fromRoute = initialState.destination.route,
                        toRoute = targetState.destination.route,
                        fallback = popExitTransition()
                    )
                },
            ) {
                ScreenWrapper(navController = navController, playerViewModel = playerViewModel) {
                    HomeScreen(
                        navController = navController, 
                        paddingValuesParent = paddingValues, 
                        playerViewModel = playerViewModel,
                        onOpenSidebar = onOpenSidebar
                    )
                }
            }
            composable(
                Screen.Search.route,
                enterTransition = {
                    mainRootEnterTransition(
                        fromRoute = initialState.destination.route,
                        toRoute = targetState.destination.route,
                        fallback = enterTransition()
                    )
                },
                exitTransition = {
                    mainRootExitTransition(
                        fromRoute = initialState.destination.route,
                        toRoute = targetState.destination.route,
                        fallback = exitTransition()
                    )
                },
                popEnterTransition = {
                    mainRootEnterTransition(
                        fromRoute = initialState.destination.route,
                        toRoute = targetState.destination.route,
                        fallback = popEnterTransition()
                    )
                },
                popExitTransition = {
                    mainRootExitTransition(
                        fromRoute = initialState.destination.route,
                        toRoute = targetState.destination.route,
                        fallback = popExitTransition()
                    )
                },
            ) {
                ScreenWrapper(navController = navController, playerViewModel = playerViewModel) {
                    SearchScreen(
                        paddingValues = paddingValues,
                        playerViewModel = playerViewModel,
                        navController = navController,
                        onSearchBarActiveChange = onSearchBarActiveChange
                    )
                }
            }
            composable(
                Screen.Library.route,
                enterTransition = {
                    mainRootEnterTransition(
                        fromRoute = initialState.destination.route,
                        toRoute = targetState.destination.route,
                        fallback = enterTransition()
                    )
                },
                exitTransition = {
                    mainRootExitTransition(
                        fromRoute = initialState.destination.route,
                        toRoute = targetState.destination.route,
                        fallback = exitTransition()
                    )
                },
                popEnterTransition = {
                    mainRootEnterTransition(
                        fromRoute = initialState.destination.route,
                        toRoute = targetState.destination.route,
                        fallback = popEnterTransition()
                    )
                },
                popExitTransition = {
                    mainRootExitTransition(
                        fromRoute = initialState.destination.route,
                        toRoute = targetState.destination.route,
                        fallback = popExitTransition()
                    )
                },
            ) {
                ScreenWrapper(navController = navController, playerViewModel = playerViewModel) {
                    LibraryScreen(navController = navController, playerViewModel = playerViewModel)
                }
            }
            composable(
                Screen.Settings.route,
                enterTransition = { enterTransition() },
                exitTransition = { exitTransition() },
                popEnterTransition = { popEnterTransition() },
                popExitTransition = { popExitTransition() },
            ) {
                ScreenWrapper(navController = navController, playerViewModel = playerViewModel) {
                    SettingsScreen(
                        navController = navController,
                        playerViewModel = playerViewModel,
                        onNavigationIconClick = {
                            navController.popBackStack()
                        }
                    )
                }
            }
            composable(
                route = Screen.Accounts.route,
                arguments = listOf(navArgument("highlightKey") { type = NavType.StringType; nullable = true; defaultValue = null }),
                enterTransition = { enterTransition() },
                exitTransition = { exitTransition() },
                popEnterTransition = { popEnterTransition() },
                popExitTransition = { popExitTransition() },
            ) {
                ScreenWrapper(navController = navController, playerViewModel = playerViewModel) {
                    AccountsScreen(
                        onBackClick = { navController.popBackStack() },
                        onOpenNavidromeDashboard = {
                            navController.navigateSafely(Screen.NavidromeDashboard.route)
                        },
                        onOpenJellyfinDashboard = {
                            navController.navigateSafely(Screen.JellyfinDashboard.route)
                        }
                    )
                }
            }
            composable(
                route = Screen.SettingsCategory.route,
                arguments = listOf(
                    navArgument("categoryId") { type = NavType.StringType },
                    navArgument("highlightKey") { type = NavType.StringType; nullable = true; defaultValue = null }
                ),
                enterTransition = { enterTransition() },
                exitTransition = { exitTransition() },
                popEnterTransition = { popEnterTransition() },
                popExitTransition = { popExitTransition() },
            ) { backStackEntry ->
                ScreenWrapper(navController = navController, playerViewModel = playerViewModel) {
                    val categoryId = backStackEntry.arguments?.getString("categoryId")
                    val highlightKey = backStackEntry.arguments?.getString("highlightKey")
                    if (categoryId != null) {
                        SettingsCategoryScreen(
                            categoryId = categoryId,
                            highlightKey = highlightKey,
                            navController = navController,
                            playerViewModel = playerViewModel,
                            onBackClick = { navController.popBackStack() }
                        )
                    }
                }
            }
            composable(
                route = Screen.ThirdPartyImport.route,
                enterTransition = { enterTransition() },
                exitTransition = { exitTransition() },
                popEnterTransition = { popEnterTransition() },
                popExitTransition = { popExitTransition() },
            ) {
                ScreenWrapper(navController = navController, playerViewModel = playerViewModel) {
                    ThirdPartyImportScreen(navController = navController)
                }
            }
            composable(
                route = Screen.PaletteStyle.route,
                arguments = listOf(navArgument("highlightKey") { type = NavType.StringType; nullable = true; defaultValue = null }),
                enterTransition = { enterTransition() },
                exitTransition = { exitTransition() },
                popEnterTransition = { popEnterTransition() },
                popExitTransition = { popExitTransition() },
            ) {
                ScreenWrapper(navController = navController, playerViewModel = playerViewModel) {
                    PaletteStyleSettingsScreen(
                        playerViewModel = playerViewModel,
                        onBackClick = { navController.popBackStack() }
                    )
                }
            }
            composable(
                route = Screen.Experimental.route,
                arguments = listOf(navArgument("highlightKey") { type = NavType.StringType; nullable = true; defaultValue = null }),
                enterTransition = { enterTransition() },
                exitTransition = { exitTransition() },
                popEnterTransition = { popEnterTransition() },
                popExitTransition = { popExitTransition() },
            ) {
                ScreenWrapper(navController = navController, playerViewModel = playerViewModel) {
                    ExperimentalSettingsScreen(
                        navController = navController,
                        playerViewModel = playerViewModel,
                        onNavigationIconClick = { navController.popBackStack() }
                    )
                }
            }
            composable(
                Screen.DailyMixScreen.route,
                enterTransition = { enterTransition() },
                exitTransition = { exitTransition() },
                popEnterTransition = { popEnterTransition() },
                popExitTransition = { popExitTransition() },
            ) {
                ScreenWrapper(navController = navController, playerViewModel = playerViewModel) {
                    DailyMixScreen(
                        playerViewModel = playerViewModel,
                        navController = navController
                    )
                }
            }
            composable(
                Screen.RecentlyPlayed.route,
                enterTransition = { enterTransition() },
                exitTransition = { exitTransition() },
                popEnterTransition = { popEnterTransition() },
                popExitTransition = { popExitTransition() },
            ) {
                ScreenWrapper(navController = navController, playerViewModel = playerViewModel) {
                    RecentlyPlayedScreen(
                        playerViewModel = playerViewModel,
                        navController = navController
                    )
                }
            }
            composable(
                Screen.Stats.route,
                enterTransition = {
                    mainRootEnterTransition(
                        fromRoute = initialState.destination.route,
                        toRoute = targetState.destination.route,
                        fallback = enterTransition()
                    )
                },
                exitTransition = {
                    mainRootExitTransition(
                        fromRoute = initialState.destination.route,
                        toRoute = targetState.destination.route,
                        fallback = exitTransition()
                    )
                },
                popEnterTransition = {
                    mainRootEnterTransition(
                        fromRoute = initialState.destination.route,
                        toRoute = targetState.destination.route,
                        fallback = popEnterTransition()
                    )
                },
                popExitTransition = {
                    mainRootExitTransition(
                        fromRoute = initialState.destination.route,
                        toRoute = targetState.destination.route,
                        fallback = popExitTransition()
                    )
                },
            ) {
                val statsViewModel: StatsViewModel = hiltViewModel()
                ScreenWrapper(navController = navController, playerViewModel = playerViewModel) {
                    ListeningStatsScreen(
                        navController = navController,
                        paddingValuesParent = paddingValues,
                        playerViewModel = playerViewModel,
                        statsViewModel = statsViewModel
                    )
                }
            }
            composable(
                route = Screen.AiMixes.route,
                enterTransition = { enterTransition() },
                exitTransition = { exitTransition() },
                popEnterTransition = { popEnterTransition() },
                popExitTransition = { popExitTransition() },
            ) {
                ScreenWrapper(navController = navController, playerViewModel = playerViewModel) {
                    AiMixesScreen(
                        onBack = { navController.popBackStack() },
                        paddingValues = paddingValues,
                        playerViewModel = playerViewModel
                    )
                }
            }
            // The "show all" stats screens reuse the stats destination's ViewModel so the
            // selected time range carries over instead of falling back to the default.
            composable(
                route = Screen.StatsHotSongs.route,
                enterTransition = { enterTransition() },
                exitTransition = { exitTransition() },
                popEnterTransition = { popEnterTransition() },
                popExitTransition = { popExitTransition() },
            ) { backStackEntry ->
                val statsViewModel: StatsViewModel = hiltViewModel(
                    remember(backStackEntry) {
                        navController.getBackStackEntry(Screen.Stats.route)
                    }
                )
                val hotSongsQueueName = stringResource(R.string.presentation_batch_g_stats_hot_songs)
                ScreenWrapper(navController = navController, playerViewModel = playerViewModel) {
                    StatsHotSongsScreen(
                        onBack = { navController.popBackStack() },
                        onSongClick = { songId, queueSongIds ->
                            playerViewModel.playSongByIds(
                                startSongId = songId,
                                queueSongIds = queueSongIds,
                                queueName = hotSongsQueueName
                            )
                        },
                        paddingValues = paddingValues,
                        statsViewModel = statsViewModel
                    )
                }
            }
            composable(
                route = Screen.StatsTopArtists.route,
                enterTransition = { enterTransition() },
                exitTransition = { exitTransition() },
                popEnterTransition = { popEnterTransition() },
                popExitTransition = { popExitTransition() },
            ) { backStackEntry ->
                val statsViewModel: StatsViewModel = hiltViewModel(
                    remember(backStackEntry) {
                        navController.getBackStackEntry(Screen.Stats.route)
                    }
                )
                ScreenWrapper(navController = navController, playerViewModel = playerViewModel) {
                    StatsTopArtistsScreen(
                        onBack = { navController.popBackStack() },
                        onArtistClick = { artist ->
                            statsViewModel.resolveArtistId(artist)?.let { artistId ->
                                navController.navigateSafely(Screen.ArtistDetail.createRoute(artistId))
                            }
                        },
                        paddingValues = paddingValues,
                        statsViewModel = statsViewModel
                    )
                }
            }
            composable(
                route = Screen.StatsTopAlbums.route,
                enterTransition = { enterTransition() },
                exitTransition = { exitTransition() },
                popEnterTransition = { popEnterTransition() },
                popExitTransition = { popExitTransition() },
            ) { backStackEntry ->
                val statsViewModel: StatsViewModel = hiltViewModel(
                    remember(backStackEntry) {
                        navController.getBackStackEntry(Screen.Stats.route)
                    }
                )
                ScreenWrapper(navController = navController, playerViewModel = playerViewModel) {
                    StatsTopAlbumsScreen(
                        onBack = { navController.popBackStack() },
                        onAlbumClick = { album ->
                            statsViewModel.resolveAlbumId(album)?.let { albumId ->
                                navController.navigateSafely(Screen.AlbumDetail.createRoute(albumId))
                            }
                        },
                        paddingValues = paddingValues,
                        statsViewModel = statsViewModel
                    )
                }
            }
            composable(
                route = Screen.AiRequestLog.route,
                enterTransition = { enterTransition() },
                exitTransition = { exitTransition() },
                popEnterTransition = { popEnterTransition() },
                popExitTransition = { popExitTransition() },
            ) {
                ScreenWrapper(navController = navController, playerViewModel = playerViewModel) {
                    AiRequestLogScreen(navController = navController)
                }
            }
            composable(
                route = Screen.Duplicates.route,
                arguments = listOf(navArgument("highlightKey") { type = NavType.StringType; nullable = true; defaultValue = null }),
                enterTransition = { enterTransition() },
                exitTransition = { exitTransition() },
                popEnterTransition = { popEnterTransition() },
                popExitTransition = { popExitTransition() },
            ) {
                ScreenWrapper(navController = navController, playerViewModel = playerViewModel) {
                    DuplicateSongsScreen(
                        navController = navController,
                        playerViewModel = playerViewModel
                    )
                }
            }
            composable(
                route = Screen.PlaylistDetail.route,
                arguments = listOf(navArgument("playlistId") { type = NavType.StringType }),
                enterTransition = { enterTransition() },
                exitTransition = { exitTransition() },
                popEnterTransition = { popEnterTransition() },
                popExitTransition = { popExitTransition() },
            ) { backStackEntry ->
                val playlistId = backStackEntry.arguments?.getString("playlistId")
                val playlistViewModel: PlaylistViewModel = hiltViewModel()
                if (playlistId != null) {
                    ScreenWrapper(navController = navController, playerViewModel = playerViewModel) {
                        PlaylistDetailScreen(
                            playlistId = playlistId,
                            playerViewModel = playerViewModel,
                            playlistViewModel = playlistViewModel,
                            onBackClick = { navController.popBackStack() },
                            onDeletePlayListClick = { navController.popBackStack() },
                            navController = navController
                        )
                    }
                }
            }

            composable(
                Screen.DJSpace.route,
                enterTransition = { enterTransition() },
                exitTransition = { exitTransition() },
                popEnterTransition = { popEnterTransition() },
                popExitTransition = { popExitTransition() },
            ) {
                ScreenWrapper(navController = navController, playerViewModel = playerViewModel) {
                    MashupScreen()
                }
            }
            composable(
                route = Screen.GenreDetail.route,
                arguments = listOf(navArgument("genreId") { type = NavType.StringType }),
                enterTransition = { enterTransition() },
                exitTransition = { exitTransition() },
                popEnterTransition = { popEnterTransition() },
                popExitTransition = { popExitTransition() },
            ) { backStackEntry ->
                val genreId = backStackEntry.arguments?.getString("genreId")
                if (genreId != null) {
                    ScreenWrapper(navController = navController, playerViewModel = playerViewModel) {
                        GenreDetailScreen(
                            navController = navController,
                            genreId = genreId,
                            playerViewModel = playerViewModel
                        )
                    }
                } else {
                    Text(stringResource(R.string.nav_error_genre_id_missing), modifier = Modifier)
                }
            }
            composable(
                route = Screen.AlbumDetail.route,
                arguments = listOf(navArgument("albumId") { type = NavType.StringType }),
                enterTransition = { enterTransition() },
                exitTransition = { exitTransition() },
                popEnterTransition = { popEnterTransition() },
                popExitTransition = { popExitTransition() },
            ) { backStackEntry ->
                val albumId = backStackEntry.arguments?.getString("albumId")
                if (albumId != null) {
                    ScreenWrapper(navController = navController, playerViewModel = playerViewModel) {
                        AlbumDetailScreen(
                            albumId = albumId,
                            navController = navController,
                            playerViewModel = playerViewModel
                        )
                    }
                }
            }
            composable(
                route = Screen.ArtistDetail.route,
                arguments = listOf(navArgument("artistId") { type = NavType.StringType }),
                enterTransition = { enterTransition() },
                exitTransition = { exitTransition() },
                popEnterTransition = { popEnterTransition() },
                popExitTransition = { popExitTransition() },
            ) { backStackEntry ->
                val artistId = backStackEntry.arguments?.getString("artistId")
                if (artistId != null) {
                    ScreenWrapper(navController = navController, playerViewModel = playerViewModel) {
                        ArtistDetailScreen(
                            artistId = artistId,
                            navController = navController,
                            playerViewModel = playerViewModel
                        )
                    }
                }
            }
            composable(
                route = Screen.YearDetail.route,
                arguments = listOf(navArgument("year") { type = NavType.IntType }),
                enterTransition = { enterTransition() },
                exitTransition = { exitTransition() },
                popEnterTransition = { popEnterTransition() },
                popExitTransition = { popExitTransition() },
            ) { backStackEntry ->
                val year = backStackEntry.arguments?.getInt("year")
                if (year != null) {
                    ScreenWrapper(navController = navController, playerViewModel = playerViewModel) {
                        YearDetailScreen(
                            navController = navController,
                            playerViewModel = playerViewModel,
                            paddingValues = paddingValues
                        )
                    }
                }
            }
            composable(
                route = Screen.NavBarCrRad.route,
                arguments = listOf(navArgument("highlightKey") { type = NavType.StringType; nullable = true; defaultValue = null }),
                enterTransition = { enterTransition() },
                exitTransition = { exitTransition() },
                popEnterTransition = { popEnterTransition() },
                popExitTransition = { popExitTransition() },
            ) {
                ScreenWrapper(navController = navController, playerViewModel = playerViewModel) {
                    NavBarCornerRadiusScreen(navController)
                }
            }
            composable(
                route = Screen.EditTransition.route,
                arguments = listOf(navArgument("playlistId") {
                    type = NavType.StringType
                    nullable = true
                }),
                enterTransition = { enterTransition() },
                exitTransition = { exitTransition() },
                popEnterTransition = { popEnterTransition() },
                popExitTransition = { popExitTransition() },
            ) {
                ScreenWrapper(navController = navController, playerViewModel = playerViewModel) {
                    EditTransitionScreen(navController = navController)
                }
            }
            composable(
                Screen.About.route,
                enterTransition = { enterTransition() },
                exitTransition = { exitTransition() },
                popEnterTransition = { popEnterTransition() },
                popExitTransition = { popExitTransition() },
            ) {
                ScreenWrapper(navController = navController, playerViewModel = playerViewModel) {
                    AboutScreen(
                        navController = navController,
                        viewModel = playerViewModel,
                        onNavigationIconClick = { navController.popBackStack() }
                    )
                }
            }
            composable(
                Screen.EasterEgg.route,
                enterTransition = { enterTransition() },
                exitTransition = { exitTransition() },
                popEnterTransition = { popEnterTransition() },
                popExitTransition = { popExitTransition() },
            ) {
                ScreenWrapper(navController = navController, playerViewModel = playerViewModel) {
                    EasterEggScreen(
                        viewModel = playerViewModel,
                        onNavigationIconClick = { navController.popBackStack() },
                    )
                }
            }
            composable(
                route = Screen.ArtistSettings.route,
                arguments = listOf(navArgument("highlightKey") { type = NavType.StringType; nullable = true; defaultValue = null }),
                enterTransition = { enterTransition() },
                exitTransition = { exitTransition() },
                popEnterTransition = { popEnterTransition() },
                popExitTransition = { popExitTransition() },
            ) {
                ScreenWrapper(navController = navController, playerViewModel = playerViewModel) {
                    ArtistSettingsScreen(navController = navController)
                }
            }
            composable(
                Screen.DelimiterConfig.route,
                enterTransition = { enterTransition() },
                exitTransition = { exitTransition() },
                popEnterTransition = { popEnterTransition() },
                popExitTransition = { popExitTransition() },
            ) {
                ScreenWrapper(navController = navController, playerViewModel = playerViewModel) {
                    DelimiterConfigScreen(navController = navController)
                }
            }
            composable(
                Screen.WordDelimiterConfig.route,
                enterTransition = { enterTransition() },
                exitTransition = { exitTransition() },
                popEnterTransition = { popEnterTransition() },
                popExitTransition = { popExitTransition() },
            ) {
                ScreenWrapper(navController = navController, playerViewModel = playerViewModel) {
                    WordDelimiterConfigScreen(navController = navController)
                }
            }
            composable(
                route = Screen.Equalizer.route,
                arguments = listOf(navArgument("highlightKey") { type = NavType.StringType; nullable = true; defaultValue = null }),
                enterTransition = { enterTransition() },
                exitTransition = { exitTransition() },
                popEnterTransition = { popEnterTransition() },
                popExitTransition = { popExitTransition() },
            ) {
                ScreenWrapper(navController = navController, playerViewModel = playerViewModel) {
                    EqualizerScreen(
                        navController = navController,
                        playerViewModel = playerViewModel
                    )
                }
            }
            composable(
                route = Screen.DeviceCapabilities.route,
                arguments = listOf(navArgument("highlightKey") { type = NavType.StringType; nullable = true; defaultValue = null }),
                enterTransition = { enterTransition() },
                exitTransition = { exitTransition() },
                popEnterTransition = { popEnterTransition() },
                popExitTransition = { popExitTransition() },
            ) {
                ScreenWrapper(navController = navController, playerViewModel = playerViewModel) {
                    com.pisces312.piscesplayer.presentation.screens.DeviceCapabilitiesScreen(
                        navController = navController,
                        playerViewModel = playerViewModel
                    )
                }
            }
            composable(
                Screen.NavidromeDashboard.route,
                enterTransition = { enterTransition() },
                exitTransition = { exitTransition() },
                popEnterTransition = { popEnterTransition() },
                popExitTransition = { popExitTransition() },
            ) {
                ScreenWrapper(navController = navController, playerViewModel = playerViewModel) {
                    com.pisces312.piscesplayer.presentation.navidrome.dashboard.NavidromeDashboardScreen(
                        onBack = { navController.popBackStack() }
                    )
                }
            }
            composable(
                Screen.JellyfinDashboard.route,
                enterTransition = { enterTransition() },
                exitTransition = { exitTransition() },
                popEnterTransition = { popEnterTransition() },
                popExitTransition = { popExitTransition() },
            ) {
                ScreenWrapper(navController = navController, playerViewModel = playerViewModel) {
                    com.pisces312.piscesplayer.presentation.jellyfin.dashboard.JellyfinDashboardScreen(
                        onBack = { navController.popBackStack() }
                    )
                }
            }
            composable(
                Screen.CloudDownloads.route,
                enterTransition = { enterTransition() },
                exitTransition = { exitTransition() },
                popEnterTransition = { popEnterTransition() },
                popExitTransition = { popExitTransition() },
            ) {
                ScreenWrapper(navController = navController, playerViewModel = playerViewModel) {
                    CloudDownloadsScreen(onBack = { navController.popBackStack() })
                }
            }
            composable(
                Screen.Updates.route,
                enterTransition = { enterTransition() },
                exitTransition = { exitTransition() },
                popEnterTransition = { popEnterTransition() },
                popExitTransition = { popExitTransition() },
            ) {
                ScreenWrapper(navController = navController, playerViewModel = playerViewModel) {
                    UpdatesScreen(
                        onBack = { navController.popBackStack() }
                    )
                }
            }
            composable(
                Screen.MusicGameHub.route,
                enterTransition = { enterTransition() },
                exitTransition = { exitTransition() },
                popEnterTransition = { popEnterTransition() },
                popExitTransition = { popExitTransition() },
            ) {
                ScreenWrapper(navController = navController, playerViewModel = playerViewModel) {
                    MusicGameHubScreen(
                        onBack = { navController.popBackStack() },
                        onOpenRhythm = {
                            navController.navigateSafely(Screen.RhythmSongPicker.route)
                        },
                    )
                }
            }
            composable(
                Screen.RhythmSongPicker.route,
                enterTransition = { enterTransition() },
                exitTransition = { exitTransition() },
                popEnterTransition = { popEnterTransition() },
                popExitTransition = { popExitTransition() },
            ) {
                ScreenWrapper(navController = navController, playerViewModel = playerViewModel) {
                    RhythmSongPickerScreen(
                        playerViewModel = playerViewModel,
                        onBack = { navController.popBackStack() },
                        onSongSelected = { songId ->
                            navController.navigateSafely(Screen.RhythmSetup.createRoute(songId))
                        },
                    )
                }
            }
            composable(
                Screen.RhythmSetup.route,
                arguments = listOf(navArgument("songId") { type = NavType.StringType }),
                enterTransition = { enterTransition() },
                exitTransition = { exitTransition() },
                popEnterTransition = { popEnterTransition() },
                popExitTransition = { popExitTransition() },
            ) { backStackEntry ->
                ScreenWrapper(navController = navController, playerViewModel = playerViewModel) {
                    RhythmSetupScreen(
                        songId = backStackEntry.arguments?.getString("songId").orEmpty(),
                        navController = navController,
                        playerViewModel = playerViewModel,
                        onNavigationIconClick = { navController.popBackStack() },
                    )
                }
            }
            composable(
                Screen.RhythmGame.route,
                arguments = listOf(
                    navArgument("songId") { type = NavType.StringType },
                    navArgument("chartId") { type = NavType.StringType },
                    navArgument("logic") {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    },
                ),
                enterTransition = { enterTransition() },
                exitTransition = { exitTransition() },
                popEnterTransition = { popEnterTransition() },
                popExitTransition = { popExitTransition() },
            ) { backStackEntry ->
                RhythmGameScreen(
                    songId = backStackEntry.arguments?.getString("songId").orEmpty(),
                    chartId = backStackEntry.arguments?.getString("chartId").orEmpty(),
                    playLogic = backStackEntry.arguments?.getString("logic"),
                    playerViewModel = playerViewModel,
                    onExit = { navController.popBackStack() },
                )
            }
            composable(
                Screen.AudioBookmarks.route,
                enterTransition = { enterTransition() },
                exitTransition = { exitTransition() },
                popEnterTransition = { popEnterTransition() },
                popExitTransition = { popExitTransition() },
            ) {
                AudioBookmarksScreen(
                    navController = navController,
                    playerViewModel = playerViewModel,
                    onOpenSidebar = onOpenSidebar
                )
            }
            composable(
                Screen.AudioBookmarkFolder.route,
                arguments = listOf(navArgument("songId") { type = NavType.StringType }),
                enterTransition = { enterTransition() },
                exitTransition = { exitTransition() },
                popEnterTransition = { popEnterTransition() },
                popExitTransition = { popExitTransition() },
            ) { backStackEntry ->
                AudioBookmarkFolderScreen(
                    songId = backStackEntry.arguments?.getString("songId").orEmpty(),
                    navController = navController,
                    playerViewModel = playerViewModel
                )
            }
        }
    }
}

private fun String.toRoute(): String = when (this) {
    LaunchTab.SEARCH -> Screen.Search.route
    LaunchTab.LIBRARY -> Screen.Library.route
    LaunchTab.STATS -> Screen.Stats.route
    else -> Screen.Home.route
}

private enum class MainRootDirection {
    FORWARD,
    BACKWARD
}

private const val BOTTOM_NAV_TRANSITION_DURATION = 380

private val BottomNavEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

private val MAIN_ROOT_TRANSITION_SPEC =
    tween<IntOffset>(durationMillis = BOTTOM_NAV_TRANSITION_DURATION, easing = BottomNavEasing)

private val MAIN_ROOT_FADE_SPEC =
    tween<Float>(durationMillis = BOTTOM_NAV_TRANSITION_DURATION / 2, easing = BottomNavEasing)

private fun mainRootDirection(
    fromRoute: String?,
    toRoute: String?
): MainRootDirection? {
    val fromIndex = mainRootRouteIndex(fromRoute) ?: return null
    val toIndex = mainRootRouteIndex(toRoute) ?: return null
    if (fromIndex == toIndex) return null
    return if (toIndex > fromIndex) MainRootDirection.FORWARD else MainRootDirection.BACKWARD
}

private fun mainRootEnterTransition(
    fromRoute: String?,
    toRoute: String?,
    fallback: EnterTransition
): EnterTransition = when (mainRootDirection(fromRoute, toRoute)) {
    MainRootDirection.FORWARD -> {
        slideInHorizontally(
            animationSpec = MAIN_ROOT_TRANSITION_SPEC,
            initialOffsetX = { (it * 0.5f).toInt() }
        ) + fadeIn(animationSpec = MAIN_ROOT_FADE_SPEC)
    }
    MainRootDirection.BACKWARD -> {
        slideInHorizontally(
            animationSpec = MAIN_ROOT_TRANSITION_SPEC,
            initialOffsetX = { -(it * 0.5f).toInt() }
        ) + fadeIn(animationSpec = MAIN_ROOT_FADE_SPEC)
    }
    null -> fallback
}

private fun mainRootExitTransition(
    fromRoute: String?,
    toRoute: String?,
    fallback: ExitTransition
): ExitTransition = when (mainRootDirection(fromRoute, toRoute)) {
    MainRootDirection.FORWARD -> {
        slideOutHorizontally(
            animationSpec = MAIN_ROOT_TRANSITION_SPEC,
            targetOffsetX = { -(it * 0.5f).toInt() }
        ) + fadeOut(animationSpec = MAIN_ROOT_FADE_SPEC)
    }
    MainRootDirection.BACKWARD -> {
        slideOutHorizontally(
            animationSpec = MAIN_ROOT_TRANSITION_SPEC,
            targetOffsetX = { (it * 0.5f).toInt() }
        ) + fadeOut(animationSpec = MAIN_ROOT_FADE_SPEC)
    }
    null -> fallback
}
