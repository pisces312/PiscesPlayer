package com.pisces312.piscesplayer.presentation.screens

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.pisces312.piscesplayer.R
import com.pisces312.piscesplayer.data.ai.AiLibrarySampleMode
import com.pisces312.piscesplayer.data.ai.serendipity.SerendipityTimeOfDay
import com.pisces312.piscesplayer.data.ai.serendipity.SerendipityWeatherGroup
import com.pisces312.piscesplayer.data.home.HomeCard
import com.pisces312.piscesplayer.data.home.HomeCardType
import com.pisces312.piscesplayer.data.home.placeHomeCards
import com.pisces312.piscesplayer.data.model.AI_MIX_SOURCE
import com.pisces312.piscesplayer.data.model.SERENDIPITY_SOURCE
import com.pisces312.piscesplayer.data.preferences.AiPreferencesRepository
import com.pisces312.piscesplayer.presentation.components.AiGenerateEntryCard
import com.pisces312.piscesplayer.presentation.components.AiMixSheet
import com.pisces312.piscesplayer.presentation.components.HomeGradientTopBar
import com.pisces312.piscesplayer.presentation.components.MiniPlayerHeight
import com.pisces312.piscesplayer.presentation.components.ChangelogBottomSheet
import com.pisces312.piscesplayer.presentation.components.RecentAiMixesRow
import com.pisces312.piscesplayer.presentation.components.RootTabScrollToTopEffect
import com.pisces312.piscesplayer.presentation.components.SampleConfig
import com.pisces312.piscesplayer.presentation.components.StreamingProviderSheet
import com.pisces312.piscesplayer.presentation.components.rememberModalSheetState
import com.pisces312.piscesplayer.presentation.home.HomeAddSheet
import com.pisces312.piscesplayer.presentation.home.HomeCardCatalog
import com.pisces312.piscesplayer.presentation.home.HomeDailyMixContent
import com.pisces312.piscesplayer.presentation.home.HomeGrid
import com.pisces312.piscesplayer.presentation.home.HomeGridEmpty
import com.pisces312.piscesplayer.presentation.home.HomeGridSpec
import com.pisces312.piscesplayer.presentation.home.HomeHelpSheet
import com.pisces312.piscesplayer.presentation.home.HomePlaylistPickerSheet
import com.pisces312.piscesplayer.presentation.navigation.Screen
import com.pisces312.piscesplayer.presentation.navigation.navigateSafely
import com.pisces312.piscesplayer.presentation.viewmodel.HomeLayoutViewModel
import com.pisces312.piscesplayer.presentation.viewmodel.PlayerViewModel
import com.pisces312.piscesplayer.presentation.viewmodel.PlaylistViewModel
import com.pisces312.piscesplayer.presentation.viewmodel.SettingsViewModel
import com.pisces312.piscesplayer.ui.theme.RoundedSans
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.roundToInt

private enum class HomeSheet { None, Add, PickPlaylist, Help, Changelog }

private val SerendipityStepPermissions = arrayOf(Manifest.permission.ACTIVITY_RECOGNITION)
private val SerendipityLocationPermissions =
    SerendipityStepPermissions + Manifest.permission.ACCESS_COARSE_LOCATION

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    navController: NavController,
    paddingValuesParent: PaddingValues,
    playerViewModel: PlayerViewModel,
    homeLayoutViewModel: HomeLayoutViewModel = hiltViewModel(),
    playlistViewModel: PlaylistViewModel = hiltViewModel(),
    settingsViewModel: SettingsViewModel = hiltViewModel(),
    onOpenSidebar: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current

    val cards by homeLayoutViewModel.cards.collectAsStateWithLifecycle()
    val userPlaylists by homeLayoutViewModel.userPlaylists.collectAsStateWithLifecycle()
    val playlistMap = remember(userPlaylists) {
        userPlaylists.associateBy { it.id }
    }

    var editMode by rememberSaveable { mutableStateOf(false) }
    var sheet by rememberSaveable { mutableStateOf(HomeSheet.None) }
    var showRestoreDialog by rememberSaveable { mutableStateOf(false) }
    var showStreamingSheet by rememberSaveable { mutableStateOf(false) }
    var draggingUid by rememberSaveable { mutableStateOf<String?>(null) }
    var dragAccum by remember { mutableStateOf(Offset.Zero) }

    // ---- 每日推荐 data (feeds the DAILY_MIX card body, plan §12 decision 9) ----
    // settingsViewModel stays a parameter for future home preferences; the grid card no
    // longer reads collagePattern since v1.3.2 (capsule row replaced the collage).
    val dailyMixSongs by playerViewModel.dailyMixSongs.collectAsStateWithLifecycle()
    val homeMixPreviewSongs by playerViewModel.homeMixPreviewSongs.collectAsStateWithLifecycle()
    val isShuffleEnabled by remember(playerViewModel.stablePlayerState) {
        playerViewModel.stablePlayerState
            .map { it.isShuffleEnabled }
            .distinctUntilChanged()
    }.collectAsStateWithLifecycle(initialValue = false)

    // ---- AI 歌单 serendipity flow (restored in v1.3: the AI card is the old entry card) ----
    val isAiConfigured by playlistViewModel.isAiConfigured.collectAsStateWithLifecycle()
    val aiSerendipitySampleMode by
            playlistViewModel.aiSerendipitySampleMode.collectAsStateWithLifecycle()
    val aiSerendipitySampleSize by
            playlistViewModel.aiSerendipitySampleSize.collectAsStateWithLifecycle()
    val recentAiMixes by playlistViewModel.recentAiMixes.collectAsStateWithLifecycle()
    val serendipityState by playlistViewModel.serendipityState.collectAsStateWithLifecycle()
    val serendipityWantsLocation by
            playlistViewModel.serendipityWantsLocation.collectAsStateWithLifecycle()
    val aiPlaylistPreviewState by playlistViewModel.aiPlaylistPreviewState.collectAsStateWithLifecycle()
    var showAiMixSheet by rememberSaveable { mutableStateOf(false) }
    // Short press auto-generates and then auto-plays (without saving); long press / card tap
    // opens the editable describe sheet and never auto-plays.
    var aiEntryIsQuick by rememberSaveable { mutableStateOf(false) }
    var autoPlayAfterGenerate by rememberSaveable { mutableStateOf(false) }
    // The permission result arrives after the system dialog, so the entry intent is
    // remembered here: a short press starts generation automatically once the callback fires.
    var pendingQuickSerendipity by rememberSaveable { mutableStateOf(false) }
    // Short-press auto-play de-dup key: song ids of the last list already sent to the player.
    var lastAutoPlayedSongIds by remember { mutableStateOf<String?>(null) }
    // Name of the mix the short-press flow already saved; the result sheet uses it to avoid a
    // second identical save and to offer "replay" instead of "play+save".
    var autoSavedName by remember { mutableStateOf<String?>(null) }
    // Content (signals, multi-line prompt, length chips, generate button) is taller than the
    // half-screen anchor, so expand straight away instead of clipping the action button.
    val aiMixSheetState = rememberModalSheetState(skipPartiallyExpanded = true)

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
            navController.navigateSafely(Screen.SettingsCategory.createRoute(com.pisces312.piscesplayer.presentation.model.SettingsCategory.AI.id))
        }
    }

    // The merged daily mix ("每日推荐") preview fallback fills the card only while the
    // curated mix is still empty.
    val usesFallbackHomeMix = remember(dailyMixSongs) {
        dailyMixSongs.isEmpty()
    }
    val mixSongs = remember(dailyMixSongs, homeMixPreviewSongs) {
        if (dailyMixSongs.isNotEmpty()) dailyMixSongs else homeMixPreviewSongs
    }
    // Queue name follows the card title, so "now playing" surfaces read the localized name.
    val dailyMixQueueName = stringResource(R.string.presentation_batch_b_daily_mix_title)

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

    val snackbarHostState = remember { SnackbarHostState() }
    val addSheetState = rememberModalSheetState(skipPartiallyExpanded = true)

    // Toast-style feedback reusing the shared snackbar host.
    val removedEvent by homeLayoutViewModel.removedEvent.collectAsStateWithLifecycle()
    LaunchedEffect(removedEvent) {
        val event = removedEvent ?: return@LaunchedEffect
        val name = event.card.targetId?.let { playlistMap[it]?.name }
            ?: context.getString(HomeCardCatalog.forType(event.card.type).nameRes)
        val result = snackbarHostState.showSnackbar(
            message = context.getString(R.string.home_grid_card_removed, name),
            actionLabel = context.getString(R.string.home_grid_undo),
            duration = SnackbarDuration.Short,
        )
        if (result == SnackbarResult.ActionPerformed) {
            homeLayoutViewModel.undoRemove()
        }
        homeLayoutViewModel.clearRemovedEvent()
    }
    val addedEvent by homeLayoutViewModel.addedEvent.collectAsStateWithLifecycle()
    LaunchedEffect(addedEvent) {
        val event = addedEvent ?: return@LaunchedEffect
        val name = event.card.targetId?.let { playlistMap[it]?.name }
            ?: context.getString(HomeCardCatalog.forType(event.card.type).nameRes)
        snackbarHostState.showSnackbar(
            message = context.getString(R.string.home_grid_card_added, name),
            duration = SnackbarDuration.Short,
        )
        homeLayoutViewModel.clearAddedEvent()
    }

    val listState: LazyListState = rememberLazyListState()
    RootTabScrollToTopEffect(
        playerViewModel = playerViewModel,
        route = Screen.Home.route,
        listState = listState,
    )

    val bottomPadding = MiniPlayerHeight
    val density = LocalDensity.current
    val cellHeightPx = with(density) { HomeGridSpec.CellHeight.toPx() }
    val rowPitchPx = with(density) { HomeGridSpec.RowPitch.toPx() }
    // v1.3.2: the grid derives its cell width from the available width (screen minus
    // the two outer gutters), so the drag math mirrors that: grid width = screen
    // width − 2 × OuterPadding, which is exactly what the grid's Layout measures.
    val gridWidthPx = with(density) {
        (LocalConfiguration.current.screenWidthDp.dp - HomeGridSpec.OuterPadding * 2).toPx()
    }

    fun persistOnExitEdit() {
        homeLayoutViewModel.persist()
        editMode = false
    }

    // v1.3 (plan §12): rich card bodies in browse mode; in edit mode every card degrades to
    // the generic tile so the wiggle / remove badge / size chip read clearly and no inner
    // button can be hit accidentally.
    val richContentFor: (HomeCard) -> (@Composable () -> Unit)? = { card ->
        when (card.type) {
            HomeCardType.AI_PLAYLIST -> {
                {
                    // v1.3.4: the body is passive — a tap anywhere on the card (outside the
                    // capsule / a mix) opens the full AI-mixes list. v1.3.8: the entry card
                    // fills the whole grid cell itself (user-picked plan B), so the old
                    // centring wrapper is gone.
                    AiGenerateEntryCard(
                        configured = isAiConfigured,
                        // Short press on the capsule: gather the moment's signals and generate.
                        onQuickGenerate = { openAiEntry(true) },
                        // Long press on the capsule: the editable describe sheet.
                        onOpenDescribe = { openAiEntry(false) },
                        modifier = Modifier.fillMaxWidth(),
                        // The plump 96dp jelly fits the 2×2 hero only; the 2×1 banner
                        // and 1×1 / 1×2 spans fall back to the 64dp pill.
                        largePill = card.size.spanX == 2 && card.size.spanY == 2,
                        // The recent-mixes shelf only fits spans two rows tall; the
                        // 2×1 banner is the entry row alone.
                        recentContent = if (card.size.spanY == 2 && recentAiMixes.isNotEmpty()) {
                            {
                                RecentAiMixesRow(
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
                                )
                            }
                        } else null,
                    )
                }
            }
            HomeCardType.DAILY_MIX -> {
                {
                    HomeDailyMixContent(
                        card = card,
                        songs = mixSongs,
                        isShuffleEnabled = isShuffleEnabled,
                        onPlayShuffled = {
                            if (usesFallbackHomeMix) {
                                playerViewModel.shuffleAllSongs(queueName = dailyMixQueueName)
                            } else {
                                playerViewModel.playSongsShuffled(
                                    songsToPlay = mixSongs,
                                    queueName = dailyMixQueueName,
                                    startAtZero = true,
                                )
                            }
                        },
                        onSongClick = { song ->
                            if (usesFallbackHomeMix) {
                                playerViewModel.showAndPlaySongFromLibrary(song, queueName = dailyMixQueueName)
                            } else {
                                playerViewModel.showAndPlaySong(song, mixSongs, dailyMixQueueName)
                            }
                        },
                    )
                }
            }
            else -> null
        }
    }
    val contentFor: (HomeCard) -> (@Composable () -> Unit)? = { card ->
        if (editMode) null else richContentFor(card)
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            if (editMode) {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                text = stringResource(R.string.home_grid_edit_title),
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.SemiBold,
                                fontFamily = RoundedSans,
                            )
                            Text(
                                text = stringResource(R.string.home_grid_edit_subtitle),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    actions = {
                        TextButton(onClick = { showRestoreDialog = true }) {
                            Text(stringResource(R.string.home_grid_restore_default))
                        }
                        TextButton(onClick = { persistOnExitEdit() }) {
                            Text(stringResource(R.string.home_grid_done))
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ),
                )
            } else {
                HomeGradientTopBar(
                    onNavigationIconClick = {
                        navController.navigateSafely(Screen.Settings.route)
                    },
                    onHelpClick = { sheet = HomeSheet.Help },
                    isScrolled = listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0,
                    modifier = Modifier,
                    // v1.3.3: cloud / 入梦 are grid cards; the top bar keeps only the
                    // settings button at the far right.
                    extraActions = { Spacer(Modifier.width(14.dp)) },
                )
            }
        },
    ) { innerPadding ->
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                // v1.3 (plan §12 decision 12): in edit mode a tap on empty space exits edit
                // mode (persisting). Card taps are consumed by the cards themselves, so this
                // only fires for background taps.
                .pointerInput(editMode) {
                    if (editMode) {
                        detectTapGestures(onTap = { persistOnExitEdit() })
                    }
                },
            contentPadding = PaddingValues(
                // v1.3.2 (plan §12 decision 13): the outer gutters equal the inter-card
                // gap (12dp) on both sides, so the grid breathes uniformly.
                start = HomeGridSpec.OuterPadding,
                end = HomeGridSpec.OuterPadding,
                top = innerPadding.calculateTopPadding(),
                bottom = paddingValuesParent.calculateBottomPadding() + 38.dp + bottomPadding,
            ),
        ) {
            item(key = "home_grid", contentType = "home_grid") {
                if (cards.isEmpty()) {
                    HomeGridEmpty(
                        onAdd = { sheet = HomeSheet.Add },
                        onRestoreDefault = { showRestoreDialog = true },
                    )
                    Spacer(Modifier.height(16.dp))
                    HomeGrid(
                        cards = emptyList(),
                        playlists = playlistMap,
                        editMode = false,
                        draggingUid = null,
                        onCardClick = {},
                        onCardRemove = {},
                        onCardCycleSize = {},
                        onAddTileClick = { sheet = HomeSheet.Add },
                        showAddTile = true,
                        contentFor = { null },
                    )
                } else {
                    HomeGrid(
                        cards = cards,
                        playlists = playlistMap,
                        editMode = editMode,
                        draggingUid = draggingUid,
                        onCardClick = { card ->
                            if (editMode) return@HomeGrid
                            onHomeCardClick(card, navController, playerViewModel, context, onStreaming = { showStreamingSheet = true })
                        },
                        onCardRemove = { index -> homeLayoutViewModel.removeAt(index) },
                        onCardCycleSize = { uid ->
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            homeLayoutViewModel.cycleSize(uid)
                        },
                        onAddTileClick = { sheet = HomeSheet.Add },
                        showAddTile = true,
                        contentFor = contentFor,
                        // Keyed on Unit on purpose: move() mutates `cards` and entering edit
                        // mode flips `editMode` mid-gesture — a keyed pointerInput would
                        // restart the detector and kill the drag. The gesture block reads the
                        // state delegates directly, so it always sees current values.
                        modifier = Modifier.pointerInput(Unit) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = { startOffset ->
                                    val currentCards = cards
                                    val origin = homeCardOriginAt(
                                        startOffset = startOffset,
                                        cards = currentCards,
                                        rowPitchPx = rowPitchPx,
                                        cellHeightPx = cellHeightPx,
                                        gridWidthPx = gridWidthPx,
                                    )
                                    val hit = origin?.let { (row, col) ->
                                        currentCards.firstOrNull { card ->
                                            val p = placeHomeCards(currentCards).placements[card.uid]
                                            p != null &&
                                                row in p.row until (p.row + card.size.spanY) &&
                                                col in p.column until (p.column + card.size.spanX)
                                        }
                                    }
                                    if (hit != null) {
                                        draggingUid = hit.uid
                                        dragAccum = Offset.Zero
                                        // v1.3: a single long-press gesture does everything —
                                        // it enters edit mode and, if the finger keeps moving,
                                        // drags the card in the same hold (iOS-home style). A
                                        // long press without a move lands in edit mode.
                                        if (!editMode) editMode = true
                                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    }
                                },
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    val uid = draggingUid ?: return@detectDragGesturesAfterLongPress
                                    dragAccum += dragAmount
                                    val insertRow = (dragAccum.y / rowPitchPx).roundToInt()
                                    val fromIndex = cards.indexOfFirst { it.uid == uid }
                                    if (fromIndex >= 0 && insertRow != 0) {
                                        val toIndex = (fromIndex + insertRow)
                                            .coerceIn(0, cards.lastIndex)
                                        if (toIndex != fromIndex) {
                                            homeLayoutViewModel.move(fromIndex, toIndex)
                                            dragAccum = Offset(dragAccum.x, dragAccum.y - insertRow * rowPitchPx)
                                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        }
                                    }
                                },
                                onDragEnd = {
                                    draggingUid = null
                                    dragAccum = Offset.Zero
                                    homeLayoutViewModel.persist()
                                },
                                onDragCancel = {
                                    draggingUid = null
                                    dragAccum = Offset.Zero
                                },
                            )
                        },
                    )
                }
            }
        }
    }

    if (showStreamingSheet) {
        StreamingProviderSheet(
            onDismissRequest = { showStreamingSheet = false },
            isNavidromeLoggedIn = false,
            onNavigateToNavidromeDashboard = {
                showStreamingSheet = false
                navController.navigateSafely(Screen.NavidromeDashboard.route)
            },
            isJellyfinLoggedIn = false,
            onNavigateToJellyfinDashboard = {
                showStreamingSheet = false
                navController.navigateSafely(Screen.JellyfinDashboard.route)
            },
        )
    }

    if (sheet == HomeSheet.Add) {
        ModalBottomSheet(
            onDismissRequest = { sheet = HomeSheet.None },
            sheetState = addSheetState,
        ) {
            HomeAddSheet(
                paddingValues = PaddingValues(),
                isTypePresent = homeLayoutViewModel::isTypePresent,
                isPlaylistPresent = homeLayoutViewModel::isPlaylistPresent,
                playlists = userPlaylists,
                onAddType = { type ->
                    homeLayoutViewModel.addCard(type, HomeCardCatalog.forType(type).defaultSize)
                },
                onAddPlaylist = { playlist ->
                    homeLayoutViewModel.addPlaylistCard(playlist.id, HomeCardCatalog.PLAYLIST.defaultSize)
                },
                onChoosePlaylist = { sheet = HomeSheet.PickPlaylist },
                onEditLayout = {
                    sheet = HomeSheet.None
                    editMode = true
                },
            )
        }
    } else if (sheet == HomeSheet.PickPlaylist) {
        ModalBottomSheet(
            onDismissRequest = { sheet = HomeSheet.None },
            sheetState = addSheetState,
        ) {
            HomePlaylistPickerSheet(
                paddingValues = PaddingValues(),
                playlists = userPlaylists,
                onPick = { playlist ->
                    homeLayoutViewModel.addPlaylistCard(playlist.id, HomeCardCatalog.PLAYLIST.defaultSize)
                    sheet = HomeSheet.None
                },
            )
        }
    } else if (sheet == HomeSheet.Help) {
        // v1.3.5 (plan §12 decision 25): quick-start help for the customizable home.
        ModalBottomSheet(
            onDismissRequest = { sheet = HomeSheet.None },
            sheetState = addSheetState,
        ) {
            HomeHelpSheet(onViewChangelog = { sheet = HomeSheet.Changelog })
        }
    } else if (sheet == HomeSheet.Changelog) {
        ModalBottomSheet(
            onDismissRequest = { sheet = HomeSheet.None },
            sheetState = addSheetState,
        ) {
            ChangelogBottomSheet()
        }
    }

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

    if (showRestoreDialog) {
        AlertDialog(
            onDismissRequest = { showRestoreDialog = false },
            title = { Text(stringResource(R.string.home_grid_restore_confirm_title)) },
            text = { Text(stringResource(R.string.home_grid_restore_confirm_body)) },
            confirmButton = {
                TextButton(onClick = {
                    homeLayoutViewModel.restoreDefault()
                    showRestoreDialog = false
                    editMode = false
                    playerViewModel.sendToast(context.getString(R.string.home_grid_default_restored))
                }) { Text(stringResource(R.string.home_grid_restore_confirm_ok)) }
            },
            dismissButton = {
                TextButton(onClick = { showRestoreDialog = false }) {
                    Text(stringResource(R.string.home_grid_cancel))
                }
            },
        )
    }
}

/** Map a pointer position (grid-local px) to the (row, column) of the cell under it. */
private fun homeCardOriginAt(
    startOffset: Offset,
    cards: List<HomeCard>,
    rowPitchPx: Float,
    cellHeightPx: Float,
    gridWidthPx: Float,
): Pair<Int, Int>? {
    val placement = placeHomeCards(cards)
    val columnPitchPx = gridWidthPx / 2f
    val col = (startOffset.x / columnPitchPx).toInt().coerceIn(0, 1)
    val row = (startOffset.y / rowPitchPx).toInt()
    if (row < 0) return null
    return row to col
}

/** Route a home card tap to its destination screen (plan §4). */
private fun onHomeCardClick(
    card: HomeCard,
    navController: NavController,
    playerViewModel: PlayerViewModel,
    context: android.content.Context,
    onStreaming: () -> Unit,
) {
    when (card.type) {
        HomeCardType.AI_PLAYLIST -> navController.navigateSafely(Screen.AiMixes.route)
        HomeCardType.DAILY_MIX -> navController.navigateSafely(Screen.DailyMixScreen.route)
        HomeCardType.GAME_RHYTHM -> navController.navigateSafely(Screen.RhythmSongPicker.route)
        HomeCardType.GAME_QUIZ -> navController.navigateSafely(Screen.MusicQuiz.route)
        HomeCardType.SOUNDSCAPE -> navController.navigateSafely(Screen.SleepSoundscape.route)
        HomeCardType.LEARN_CLASSICAL -> navController.navigateSafely(Screen.ClassicalBrowse.route)
        HomeCardType.LEARN_MIDI -> navController.navigateSafely(Screen.MidiLibrary.route)
        HomeCardType.LEARN_EAR, HomeCardType.LEARN_METRONOME ->
            playerViewModel.sendToast(context.getString(R.string.music_learning_coming_soon))
        HomeCardType.STREAMING -> onStreaming()
        HomeCardType.SETTINGS -> navController.navigateSafely(Screen.Settings.route)
        HomeCardType.PLAYLIST -> card.targetId?.let {
            navController.navigateSafely(Screen.PlaylistDetail.createRoute(it))
        }
    }
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
