package com.lostf1sh.pixelplayeross.presentation.screens

import com.lostf1sh.pixelplayeross.presentation.navigation.navigateSafely
import com.lostf1sh.pixelplayeross.presentation.navigation.navigateSafelyReplacing

import android.content.Intent
import android.Manifest
import androidx.activity.compose.ReportDrawnWhen
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.media3.common.util.UnstableApi
import androidx.navigation.NavController
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.data.model.AI_MIX_SOURCE
import com.lostf1sh.pixelplayeross.data.model.SERENDIPITY_SOURCE
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.data.ai.AiLibrarySampleMode
import com.lostf1sh.pixelplayeross.data.ai.serendipity.SerendipityTimeOfDay
import com.lostf1sh.pixelplayeross.data.ai.serendipity.SerendipityWeatherGroup
import com.lostf1sh.pixelplayeross.data.preferences.AiPreferencesRepository
import com.lostf1sh.pixelplayeross.data.preferences.CollagePattern
import com.lostf1sh.pixelplayeross.presentation.components.AiGenerateEntryCard
import com.lostf1sh.pixelplayeross.presentation.components.AiMixSheet
import com.lostf1sh.pixelplayeross.presentation.components.AlbumArtCollage
import com.lostf1sh.pixelplayeross.presentation.components.BetaInfoBottomSheet
import com.lostf1sh.pixelplayeross.presentation.jellyfin.dashboard.JellyfinDashboardViewModel
import com.lostf1sh.pixelplayeross.presentation.navidrome.dashboard.NavidromeDashboardViewModel
import com.lostf1sh.pixelplayeross.presentation.components.DailyMixSection
import com.lostf1sh.pixelplayeross.presentation.components.SampleConfig
import com.lostf1sh.pixelplayeross.presentation.components.HomeGradientTopBar
import com.lostf1sh.pixelplayeross.presentation.components.HomeSectionHeader
import com.lostf1sh.pixelplayeross.presentation.components.HomeOptionsBottomSheet
import com.lostf1sh.pixelplayeross.presentation.components.MiniPlayerHeight
import com.lostf1sh.pixelplayeross.presentation.components.RecentAiMixesSection
import com.lostf1sh.pixelplayeross.presentation.components.SmartImage
import com.lostf1sh.pixelplayeross.presentation.components.resolveMainScreenBottomGradientHeight
import com.lostf1sh.pixelplayeross.presentation.model.SettingsCategory
import com.lostf1sh.pixelplayeross.presentation.components.subcomps.PlayingEqIcon
import com.lostf1sh.pixelplayeross.presentation.navigation.Screen
import com.lostf1sh.pixelplayeross.presentation.components.StreamingProviderSheet
import com.lostf1sh.pixelplayeross.presentation.viewmodel.PlayerViewModel
import com.lostf1sh.pixelplayeross.presentation.viewmodel.SettingsViewModel
import com.lostf1sh.pixelplayeross.presentation.viewmodel.PlaylistViewModel
import com.lostf1sh.pixelplayeross.ui.theme.ExpTitleTypography
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import com.lostf1sh.pixelplayeross.ui.theme.ShapeCache
import racra.compose.smooth_corner_rect_library.AbsoluteSmoothCornerShape
import androidx.compose.ui.res.stringResource
import com.lostf1sh.pixelplayeross.presentation.components.rememberModalSheetState
import java.text.NumberFormat
import java.time.format.TextStyle
import java.util.Locale

private const val HomeLoadingPlaceholderMinDurationMillis = 1200L

/**
 * Requested the first time Serendipity is opened; a refusal only costs the mix its extra lines.
 *
 * The location permission is asked for only when the weather source is the device's own position:
 * with a city chosen in settings, Serendipity never needs to know where the phone is.
 */
private val SerendipityStepPermissions = arrayOf(Manifest.permission.ACTIVITY_RECOGNITION)

private val SerendipityLocationPermissions =
    SerendipityStepPermissions + Manifest.permission.ACCESS_COARSE_LOCATION

@androidx.annotation.OptIn(UnstableApi::class)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    navController: NavController,
    paddingValuesParent: PaddingValues,
    playerViewModel: PlayerViewModel,
    settingsViewModel: SettingsViewModel = hiltViewModel(),
    navidromeViewModel: NavidromeDashboardViewModel = hiltViewModel(),
    jellyfinViewModel: JellyfinDashboardViewModel = hiltViewModel(),
    playlistViewModel: PlaylistViewModel = hiltViewModel(),
    onOpenSidebar: () -> Unit
) {
    val context = LocalContext.current
    val isBenchmarkMode = remember {
        (context as? android.app.Activity)?.intent?.getBooleanExtra("is_benchmark", false) ?: false
    }
    val settingsUiState by settingsViewModel.uiState.collectAsStateWithLifecycle()
    val dailyMixSongs by playerViewModel.dailyMixSongs.collectAsStateWithLifecycle()
    val curatedYourMixSongs by playerViewModel.yourMixSongs.collectAsStateWithLifecycle()
    val homeMixPreviewSongs by playerViewModel.homeMixPreviewSongs.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    val isAiConfigured by playlistViewModel.isAiConfigured.collectAsStateWithLifecycle()
    val aiSerendipitySampleMode by
            playlistViewModel.aiSerendipitySampleMode.collectAsStateWithLifecycle()
    val recentAiMixes by playlistViewModel.recentAiMixes.collectAsStateWithLifecycle()
    val serendipityState by playlistViewModel.serendipityState.collectAsStateWithLifecycle()
    val serendipityWantsLocation by
            playlistViewModel.serendipityWantsLocation.collectAsStateWithLifecycle()
    var showAiMixSheet by remember { mutableStateOf(false) }
    // Short press auto-generates and then auto-plays (without saving); long press opens the
    // editable describe sheet and never auto-plays.
    var aiEntryIsQuick by remember { mutableStateOf(false) }
    var autoPlayAfterGenerate by remember { mutableStateOf(false) }

    // The permission result arrives after the system dialog, so the entry intent is
    // remembered here: a short press starts generation automatically once the callback fires.
    var pendingQuickSerendipity by remember { mutableStateOf(false) }

    val openSerendipitySheet: (Boolean) -> Unit = { quick ->
        aiEntryIsQuick = quick
        autoPlayAfterGenerate = quick
        if (quick) playlistViewModel.quickGenerateSerendipity()
        else playlistViewModel.openSerendipity()
        showAiMixSheet = true
    }

    // Asked the first time Serendipity is used, never at startup. The result is ignored on
    // purpose: a refusal only means the prompt loses its weather and step lines.
    val serendipityPermissionLauncher =
            rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions()
            ) { openSerendipitySheet(pendingQuickSerendipity) }

    // Both gestures share this guard: with no provider configured they route to AI settings.
    // "quick" is the short press: collect signals and generate without another tap.
    val openAiEntry: (Boolean) -> Unit = { quick ->
        if (isAiConfigured) {
            pendingQuickSerendipity = quick
            serendipityPermissionLauncher.launch(
                    if (serendipityWantsLocation) SerendipityLocationPermissions
                    else SerendipityStepPermissions
            )
        } else {
            navController.navigateSafely(Screen.SettingsCategory.createRoute(SettingsCategory.AI.id))
        }
    }

    val usesFallbackHomeMix = remember(curatedYourMixSongs, dailyMixSongs) {
        curatedYourMixSongs.isEmpty() && dailyMixSongs.isEmpty()
    }
    val yourMixSongs = remember(curatedYourMixSongs, dailyMixSongs, homeMixPreviewSongs) {
        when {
            curatedYourMixSongs.isNotEmpty() -> curatedYourMixSongs
            dailyMixSongs.isNotEmpty() -> dailyMixSongs
            else -> homeMixPreviewSongs
        }
    }
    var homePlaceholderRefreshGeneration by rememberSaveable { mutableIntStateOf(0) }
    var hasHomeLoadingMinimumElapsed by rememberSaveable(homePlaceholderRefreshGeneration) {
        mutableStateOf(false)
    }

    LaunchedEffect(homePlaceholderRefreshGeneration, yourMixSongs.isEmpty()) {
        if (yourMixSongs.isEmpty()) {
            hasHomeLoadingMinimumElapsed = false
            delay(HomeLoadingPlaceholderMinDurationMillis)
            hasHomeLoadingMinimumElapsed = true
        } else {
            hasHomeLoadingMinimumElapsed = true
        }
    }

    val shouldShowYourMixLoadingPlaceholder = yourMixSongs.isEmpty() && !hasHomeLoadingMinimumElapsed

    ReportDrawnWhen {
        yourMixSongs.isNotEmpty() || hasHomeLoadingMinimumElapsed || isBenchmarkMode
    }

    val currentSong by remember(playerViewModel.stablePlayerState) {
        playerViewModel.stablePlayerState.map { it.currentSong }
    }.collectAsStateWithLifecycle(initialValue = null)

    val isShuffleEnabled by remember(playerViewModel.stablePlayerState) {
        playerViewModel.stablePlayerState
            .map { it.isShuffleEnabled }
            .distinctUntilChanged()
    }.collectAsStateWithLifecycle(initialValue = false)

    val bottomPadding = if (currentSong != null) MiniPlayerHeight else 0.dp
    val navBarCompactMode by playerViewModel.navBarCompactMode.collectAsStateWithLifecycle()
    val bottomGradientHeight = resolveMainScreenBottomGradientHeight(navBarCompactMode)

    var showOptionsBottomSheet by remember { mutableStateOf(false) }
    var showBetaInfoBottomSheet by remember { mutableStateOf(false) }
    var showStreamingProviderSheet by remember { mutableStateOf(false) }
    val sheetState = rememberModalSheetState()
    val betaSheetState = rememberModalSheetState()
    // Content (signals, multi-line prompt, length chips, generate button) is taller than the
    // half-screen anchor, so expand straight away instead of clipping the action button.
    val aiMixSheetState = rememberModalSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    LocalContext.current

    val listState = rememberSaveable(saver = LazyListState.Saver) { LazyListState() }
    com.lostf1sh.pixelplayeross.presentation.components.RootTabScrollToTopEffect(
        playerViewModel = playerViewModel,
        route = com.lostf1sh.pixelplayeross.presentation.navigation.Screen.Home.route,
        listState = listState
    )
    val density = LocalDensity.current
    val scrollThresholdPx = remember(density) { with(density) { 180.dp.toPx() } }
    val isScrolledPastThreshold = remember {
        derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > scrollThresholdPx }
    }

    var savedScrollIndex by rememberSaveable { mutableIntStateOf(0) }
    var savedScrollOffset by rememberSaveable { mutableIntStateOf(0) }
    var needsScrollRestore by rememberSaveable { mutableStateOf(false) }

    DisposableEffect(lifecycleOwner, listState) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) {
                savedScrollIndex = listState.firstVisibleItemIndex
                savedScrollOffset = listState.firstVisibleItemScrollOffset
                needsScrollRestore = true
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(
        needsScrollRestore,
        yourMixSongs.isNotEmpty(),
        dailyMixSongs.isNotEmpty(),
        recentAiMixes.isNotEmpty()
    ) {
        if (!needsScrollRestore) return@LaunchedEffect
        val totalItems = listState.layoutInfo.totalItemsCount
        if (totalItems == 0) return@LaunchedEffect
        val targetIndex = savedScrollIndex.coerceIn(0, (totalItems - 1).coerceAtLeast(0))
        listState.scrollToItem(targetIndex, savedScrollOffset)
        needsScrollRestore = false
    }

    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)

    Box(
        modifier = Modifier.fillMaxSize()
    ) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            topBar = {
                HomeGradientTopBar(
                    onNavigationIconClick = {
                        navController.navigateSafely(Screen.Settings.route)
                    },
                    onBetaClick = {
                        showBetaInfoBottomSheet = true
                    },
                    onStreamingClick = {
                          showStreamingProviderSheet = true
                    },
                    onMenuClick = {
                    },
                    isScrolled = isScrolledPastThreshold.value
                )
            }
        ) { innerPadding ->
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background),
                contentPadding = PaddingValues(
                    top = innerPadding.calculateTopPadding(),
                    bottom = paddingValuesParent.calculateBottomPadding()
                            + 38.dp + bottomPadding
                ),
                verticalArrangement = Arrangement.spacedBy(32.dp)
            ) {
                // Always declared: the scroll position is restored by index, so an item that
                // appears only after the library size is known would shift everything by one and
                // push this card above the viewport.
                item(
                    key = "ai_playlist_entry",
                    contentType = "ai_playlist_entry"
                ) {
                    AiGenerateEntryCard(
                        configured = isAiConfigured,
                        modifier = Modifier.padding(horizontal = 16.dp),
                        // Short press: gather signals and generate straight away, then auto-play.
                        onQuickGenerate = { openAiEntry(true) },
                        // Long press / card tap: open the editable describe sheet with the same
                        // signal chips; the prompt may stay blank.
                        onOpenDescribe = { openAiEntry(false) }
                    )
                }
                if (recentAiMixes.isNotEmpty()) {
                    item(
                        key = "recent_ai_mixes_section",
                        contentType = "recent_ai_mixes_section"
                    ) {
                        RecentAiMixesSection(
                            mixes = recentAiMixes,
                            onMixClick = { mix ->
                                scope.launch {
                                    val songs = playlistViewModel.songsOf(mix.id)
                                    if (songs.isNotEmpty()) {
                                        playerViewModel.playSongs(
                                            songsToPlay = songs,
                                            startSong = songs.first(),
                                            queueName = mix.name,
                                            playlistId = mix.id
                                        )
                                    }
                                }
                            },
                            onShowAll = {
                                navController.navigateSafely(Screen.AiMixes.route)
                            }
                        )
                    }
                }
                if (yourMixSongs.isEmpty()) {
                    item(
                        key = "your_mix_placeholder",
                        contentType = "your_mix_placeholder"
                    ) {
                        if (shouldShowYourMixLoadingPlaceholder) {
                            YourMixLoadingPlaceholder()
                        } else {
                            YourMixEmptyPlaceholder(
                                onRefresh = {
                                    homePlaceholderRefreshGeneration++
                                    settingsViewModel.refreshLibrary()
                                    playerViewModel.forceUpdateDailyMix()
                                }
                            )
                        }
                    }
                } else {
                    item(
                        key = "your_mix_header",
                        contentType = "your_mix_header"
                    ) {
                        YourMixHeader(
                            isShuffleEnabled = isShuffleEnabled,
                            onPlayShuffled = {
                                if (usesFallbackHomeMix) {
                                    playerViewModel.shuffleAllSongs(queueName = "Your Mix")
                                } else {
                                    playerViewModel.playSongsShuffled(
                                        songsToPlay = yourMixSongs,
                                        queueName = "Your Mix",
                                        startAtZero = true,
                                    )
                                }
                            }
                        )
                    }
                }

                if (yourMixSongs.isNotEmpty()) {
                    item(
                        key = "album_art_collage",
                        contentType = "album_art_collage"
                    ) {
                        val basePattern = settingsUiState.collagePattern
                        val isAutoRotate = settingsUiState.collageAutoRotate
                        val patterns = remember { CollagePattern.entries }

                        val activePattern = if (isAutoRotate) {
                            var rotationIndex by rememberSaveable { mutableIntStateOf(-1) }
                            LaunchedEffect(Unit) { rotationIndex++ }
                            remember(rotationIndex) {
                                patterns[rotationIndex.coerceAtLeast(0) % patterns.size]
                            }
                        } else {
                            basePattern
                        }

                        AlbumArtCollage(
                            modifier = Modifier.fillMaxWidth(),
                            songs = yourMixSongs,
                            padding = 14.dp,
                            height = 400.dp,
                            pattern = activePattern,
                            onSongClick = { song ->
                                if (usesFallbackHomeMix) {
                                    playerViewModel.showAndPlaySongFromLibrary(song, queueName = "Your Mix")
                                } else {
                                    playerViewModel.showAndPlaySong(song, yourMixSongs, "Your Mix")
                                }
                            }
                        )
                    }
                }

                if (dailyMixSongs.isNotEmpty()) {
                    item(
                        key = "daily_mix_section",
                        contentType = "daily_mix_section"
                    ) {
                        DailyMixSection(
                            songs = dailyMixSongs,
                            onClickOpen = {
                                navController.navigateSafely(Screen.DailyMixScreen.route)
                            },
                            onNavigateToAlbum = { song ->
                                navController.navigateSafelyReplacing(
                                    route = Screen.AlbumDetail.createRoute(song.albumId),
                                    patternToPop = Screen.AlbumDetail.route
                                )
                            },
                            onNavigateToArtist = { song ->
                                navController.navigateSafelyReplacing(
                                    route = Screen.ArtistDetail.createRoute(song.artistId),
                                    patternToPop = Screen.ArtistDetail.route
                                )
                            },
                            onNavigateToGenre = { song ->
                                song.genre?.let {
                                    navController.navigateSafely(Screen.GenreDetail.createRoute(java.net.URLEncoder.encode(it, "UTF-8")))
                                }
                            },
                            onNavigateToYear = { song ->
                                song.year.takeIf { it > 0 }?.let {
                                    navController.navigateSafely(Screen.YearDetail.createRoute(it))
                                }
                            },
                            onNavigateToRhythmGame = { song ->
                                navController.navigateSafely(Screen.RhythmSetup.createRoute(song.id))
                            },
                            playerViewModel = playerViewModel
                        )
                    }
                }

            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .height(bottomGradientHeight)
                .background(
                    brush = Brush.verticalGradient(
                        colorStops = arrayOf(
                            0.0f to Color.Transparent,
                            0.2f to Color.Transparent,
                            0.8f to MaterialTheme.colorScheme.surfaceContainerLowest,
                            1.0f to MaterialTheme.colorScheme.surfaceContainerLowest
                        )
                    )
                )
        ) {

        }
    }
    if (showOptionsBottomSheet) {
        ModalBottomSheet(
            onDismissRequest = { showOptionsBottomSheet = false },
            sheetState = sheetState
        ) {
            HomeOptionsBottomSheet(
                onNavigateToMashup = {
                    scope.launch {
                        sheetState.hide()
                    }.invokeOnCompletion {
                        if (!sheetState.isVisible) {
                            showOptionsBottomSheet = false
                            navController.navigateSafely(Screen.DJSpace.route)
                        }
                    }
                }
            )
        }
    }
    if (showBetaInfoBottomSheet) {
        ModalBottomSheet(
            onDismissRequest = { showBetaInfoBottomSheet = false },
            sheetState = betaSheetState,
        ) {
            BetaInfoBottomSheet()
        }
    }
    val aiPlaylistPreviewState by playlistViewModel.aiPlaylistPreviewState.collectAsStateWithLifecycle()
    val aiSerendipitySampleSize by
            playlistViewModel.aiSerendipitySampleSize.collectAsStateWithLifecycle()

    // Short-press auto-play de-dup key: song ids of the last list already sent to the player.
    var lastAutoPlayedSongIds by remember { mutableStateOf<String?>(null) }
    // Name of the mix the short-press flow already saved; the result sheet uses it to avoid a
    // second identical save and to offer "replay" instead of "play+save".
    var autoSavedName by remember { mutableStateOf<String?>(null) }

    fun resetAiMixSheetSession() {
        playlistViewModel.resetAiPlaylistPreview()
        playlistViewModel.closeSerendipity()
        showAiMixSheet = false
        autoPlayAfterGenerate = false
        lastAutoPlayedSongIds = null
        autoSavedName = null
    }

    // Close the AI sheet the same way the options sheet does: animate `sheetState` out first,
    // then drop the composable. Toggling `showAiMixSheet` alone leaves the expanded sheet on
    // screen, so the header close button looks dead.
    fun dismissAiMixSheet() {
        scope.launch {
            aiMixSheetState.hide()
        }.invokeOnCompletion {
            if (!aiMixSheetState.isVisible) {
                resetAiMixSheetSession()
            }
        }
    }

    LaunchedEffect(Unit) {
        playlistViewModel.aiMixSaved.collect { mix ->
            if (mix.startPlayback && mix.songs.isNotEmpty()) {
                playerViewModel.playSongs(
                    songsToPlay = mix.songs,
                    startSong = mix.songs.first(),
                    queueName = mix.name,
                    playlistId = mix.playlistId
                )
            }
            playerViewModel.sendToast(context.getString(R.string.ai_mix_saved, mix.name))
        }
    }

    // Serendipity keeps its signals in localized chips, and names the mix after them. Both are
    // derived here rather than in the sheet so the sheet stays free of resource lookups.
    val serendipityContext = serendipityState?.context
    val serendipityChips = mutableListOf<String>()
    var serendipityDefaultName: String? = null
    if (serendipityContext != null) {
        serendipityChips +=
                stringResource(
                        R.string.ai_serendipity_chip_time,
                        serendipityContext.weekday.getDisplayName(TextStyle.FULL, Locale.getDefault()),
                        serendipityContext.clockTime,
                        stringResource(serendipityTimeOfDayLabelRes(serendipityContext.timeOfDay))
                )
        serendipityContext.weather?.let { weather ->
            serendipityChips +=
                    stringResource(
                            R.string.ai_serendipity_chip_weather,
                            stringResource(serendipityWeatherLabelRes(weather.group)),
                            weather.temperatureC
                    )
        }
        // The chip is read by the user, so it takes the localized name when there is one; the
        // prompt itself keeps the Latin spelling.
        (serendipityContext.cityLabel ?: serendipityContext.city)?.let { serendipityChips += it }
        serendipityContext.stepsToday?.let { steps ->
            serendipityChips +=
                    stringResource(
                            R.string.ai_serendipity_chip_steps,
                            NumberFormat.getIntegerInstance().format(steps)
                    )
        }
        serendipityDefaultName =
                buildList {
                            serendipityContext.weather?.let {
                                add(stringResource(serendipityWeatherLabelRes(it.group)))
                            }
                            add(stringResource(serendipityTimeOfDayLabelRes(serendipityContext.timeOfDay)))
                        }
                        .joinToString(" · ") + " · " + serendipityContext.clockTime
    }

    // Short-press auto-play + auto-save: start the generated list and file it under the
    // Serendipity default name as soon as it lands. Keyed on the song ids so a recomposition or
    // an unrelated state copy cannot replay or re-save it.
    val autoPlayQueueName = serendipityDefaultName ?: stringResource(R.string.ai_serendipity_sheet_title)
    LaunchedEffect(
        autoPlayAfterGenerate,
        aiPlaylistPreviewState.hasResult,
        aiPlaylistPreviewState.isGenerating,
        aiPlaylistPreviewState.songs,
        autoPlayQueueName
    ) {
        if (!autoPlayAfterGenerate) return@LaunchedEffect
        val songs = aiPlaylistPreviewState.songs
        if (aiPlaylistPreviewState.isGenerating ||
            !aiPlaylistPreviewState.hasResult ||
            songs.isEmpty()
        ) {
            return@LaunchedEffect
        }
        val songKey = songs.joinToString(separator = "|") { it.id }
        if (songKey == lastAutoPlayedSongIds) return@LaunchedEffect
        lastAutoPlayedSongIds = songKey
        // Save first with playback: the aiMixSaved collector starts the queue and toasts, while
        // the sheet stays open on the result phase. startPlayback = true would double-play if we
        // also called playSongs here, so playback comes only from the save event.
        playlistViewModel.saveAiMix(
            name = autoPlayQueueName,
            songs = songs,
            prompt = serendipityState?.prompt.orEmpty(),
            startPlayback = true,
            source = SERENDIPITY_SOURCE
        )
        autoSavedName = autoPlayQueueName
    }

    if (showAiMixSheet) {
        ModalBottomSheet(
            onDismissRequest = { dismissAiMixSheet() },
            sheetState = aiMixSheetState
        ) {
            AiMixSheet(
                state = aiPlaylistPreviewState,
                // One sampling profile for the whole button: Serendipity's own (random / wide slice).
                sampleConfig =
                        SampleConfig(
                            modes = AiLibrarySampleMode.entries,
                            mode = aiSerendipitySampleMode,
                            sizes = AiPreferencesRepository.LIBRARY_SAMPLE_SIZE_OPTIONS,
                            size = aiSerendipitySampleSize,
                            onModeChange = playlistViewModel::setAiSerendipitySampleMode,
                            onSizeChange = playlistViewModel::setAiSerendipitySampleSize
                        ),
                serendipity = serendipityState,
                serendipityChips = serendipityChips,
                serendipityDefaultName = serendipityDefaultName,
                autoSavedName = autoSavedName,
                onReplay = { songs ->
                    if (songs.isNotEmpty()) {
                        playerViewModel.playSongs(
                            songsToPlay = songs,
                            startSong = songs.first(),
                            queueName = autoSavedName ?: autoPlayQueueName
                        )
                    }
                },
                onReshuffleSerendipity = playlistViewModel::reshuffleSerendipityPrompt,
                onRephraseSerendipity = playlistViewModel::rephraseSerendipityPrompt,
                onGenerate = { prompt, maxLength ->
                    playlistViewModel.generateSerendipityPreview(prompt, maxLength)
                },
                onSave = { name, songs, prompt, startPlayback ->
                    playlistViewModel.saveAiMix(
                        name = name,
                        songs = songs,
                        prompt = prompt,
                        startPlayback = startPlayback,
                        source =
                                if (aiEntryIsQuick) SERENDIPITY_SOURCE
                                else AI_MIX_SOURCE
                    )
                    dismissAiMixSheet()
                },
                onDismiss = { dismissAiMixSheet() }
            )
        }
    }
    if (showStreamingProviderSheet) {
        val isNavidromeLoggedIn by navidromeViewModel.isLoggedIn.collectAsStateWithLifecycle()
        val isJellyfinLoggedIn by jellyfinViewModel.isLoggedIn.collectAsStateWithLifecycle()
        StreamingProviderSheet(
            onDismissRequest = { showStreamingProviderSheet = false },
            isNavidromeLoggedIn = isNavidromeLoggedIn,
            onNavigateToNavidromeDashboard = {
                navController.navigateSafely(Screen.NavidromeDashboard.route)
            },
            isJellyfinLoggedIn = isJellyfinLoggedIn,
            onNavigateToJellyfinDashboard = {
                navController.navigateSafely(Screen.JellyfinDashboard.route)
            }
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun YourMixLoadingPlaceholder() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(256.dp)
            .padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        LoadingIndicator(
            modifier = Modifier.size(128.dp),
            color = MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
private fun YourMixEmptyPlaceholder(
    onRefresh: () -> Unit
) {
    val colors = MaterialTheme.colorScheme

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 256.dp)
            .padding(horizontal = 24.dp, vertical = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Surface(
                modifier = Modifier.size(76.dp),
                shape = ShapeCache.expressiveHero,
                color = colors.secondaryContainer,
                contentColor = colors.onSecondaryContainer
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Rounded.MusicNote,
                        contentDescription = null,
                        modifier = Modifier.size(34.dp)
                    )
                }
            }

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = stringResource(R.string.home_empty_placeholder_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = colors.onSurface,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = stringResource(R.string.home_empty_placeholder_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            FilledTonalButton(
                onClick = onRefresh,
                shape = AbsoluteSmoothCornerShape(
                    cornerRadiusTL = 22.dp,
                    smoothnessAsPercentTR = 60,
                    cornerRadiusBR = 22.dp,
                    smoothnessAsPercentTL = 60,
                    cornerRadiusBL = 22.dp,
                    smoothnessAsPercentBR = 60,
                    cornerRadiusTR = 22.dp,
                    smoothnessAsPercentBL = 60,
                )
            ) {
                Icon(
                    imageVector = Icons.Rounded.Refresh,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(text = stringResource(R.string.home_empty_placeholder_refresh))
            }
        }
    }
}

@Composable
fun YourMixHeader(
    isShuffleEnabled: Boolean = false,
    onPlayShuffled: () -> Unit
) {
    val colors = MaterialTheme.colorScheme

    HomeSectionHeader(
        // Translations keep an explicit line break from the old hero layout
        // ("Your\nMix"); this header is single-line, so collapse it to a space.
        title = stringResource(R.string.home_your_mix_title).replace('\n', ' '),
        subtitle = stringResource(R.string.home_your_mix_subtitle),
        action = {
            FilledTonalIconButton(
                onClick = onPlayShuffled,
                modifier = Modifier.size(56.dp),
                colors = IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor = if (isShuffleEnabled) colors.primary else colors.tertiaryContainer,
                    contentColor = if (isShuffleEnabled) colors.onPrimary else colors.onTertiaryContainer
                )
            ) {
                Icon(
                    painter = painterResource(R.drawable.rounded_shuffle_24),
                    contentDescription = stringResource(R.string.cd_shuffle_play),
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    )
}


@Composable
fun SongListItemFavs(
    modifier: Modifier = Modifier,
    cardCorners: Dp = 12.dp,
    title: String,
    artist: String,
    albumArtUrl: String?,
    isPlaying: Boolean,
    isCurrentSong: Boolean,
    onClick: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val containerColor = if (isCurrentSong) colors.primaryContainer.copy(alpha = 0.46f) else colors.surfaceContainer
    val contentColor = if (isCurrentSong) colors.primary else colors.onSurface

    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(cardCorners),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Row(
            modifier = Modifier
                .padding(12.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier
                    .weight(0.9f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SmartImage(
                    model = albumArtUrl,
                    contentDescription = stringResource(R.string.cd_album_art_for_title, title),
                    contentScale = ContentScale.Crop,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.size(48.dp)
                )
                Spacer(Modifier.width(16.dp))
                Column(modifier = Modifier) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = if (isCurrentSong) FontWeight.Bold else FontWeight.Normal,
                        color = contentColor,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = artist, style = MaterialTheme.typography.bodyMedium,
                        color = contentColor.copy(alpha = 0.7f),
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Spacer(Modifier.width(16.dp))
            if (isCurrentSong) {
                PlayingEqIcon(
                    modifier = Modifier
                        .weight(0.1f)
                        .padding(start = 8.dp)
                        .size(width = 18.dp, height = 16.dp),
                    color = colors.primary,
                    isPlaying = isPlaying
                )
            }
        }
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun SongListItemFavsWrapper(
    song: Song,
    playerViewModel: PlayerViewModel,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val stablePlayerState by playerViewModel.stablePlayerState.collectAsStateWithLifecycle()

    val isThisSongPlaying = remember(song.id, stablePlayerState.currentSong?.id, stablePlayerState.isPlaying) {
        song.id == stablePlayerState.currentSong?.id
    }

    SongListItemFavs(
        modifier = modifier,
        cardCorners = 0.dp,
        title = song.title,
        artist = song.displayArtist,
        albumArtUrl = song.albumArtUriString,
        isPlaying = stablePlayerState.isPlaying,
        isCurrentSong = song.id == stablePlayerState.currentSong?.id,
        onClick = onClick
    )
}


/** Localized label for a time-of-day bucket, used by the Serendipity chips and mix name. */
@androidx.annotation.StringRes
private fun serendipityTimeOfDayLabelRes(timeOfDay: SerendipityTimeOfDay): Int =
    when (timeOfDay) {
        SerendipityTimeOfDay.MORNING -> R.string.ai_serendipity_time_morning
        SerendipityTimeOfDay.AFTERNOON -> R.string.ai_serendipity_time_afternoon
        SerendipityTimeOfDay.EVENING -> R.string.ai_serendipity_time_evening
        SerendipityTimeOfDay.LATE_NIGHT -> R.string.ai_serendipity_time_late_night
    }

/** Localized label for a coarse weather bucket, used by the Serendipity chips and mix name. */
@androidx.annotation.StringRes
private fun serendipityWeatherLabelRes(group: SerendipityWeatherGroup): Int =
    when (group) {
        SerendipityWeatherGroup.CLEAR -> R.string.ai_serendipity_weather_clear
        SerendipityWeatherGroup.CLOUDY -> R.string.ai_serendipity_weather_cloudy
        SerendipityWeatherGroup.FOG -> R.string.ai_serendipity_weather_fog
        SerendipityWeatherGroup.RAIN -> R.string.ai_serendipity_weather_rain
        SerendipityWeatherGroup.SNOW -> R.string.ai_serendipity_weather_snow
        SerendipityWeatherGroup.THUNDERSTORM -> R.string.ai_serendipity_weather_thunderstorm
    }
