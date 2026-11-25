/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Original work in 2026 (chart import, master generation and difficulty derivation screen).
 */
package com.pisces312.piscesplayer.presentation.rhythm

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pisces312.piscesplayer.R
import com.pisces312.piscesplayer.data.model.Song
import com.pisces312.piscesplayer.data.offline.CloudOfflineRepository
import com.pisces312.piscesplayer.data.rhythm.DspDifficulty
import com.pisces312.piscesplayer.data.rhythm.MasterGenerationResult
import com.pisces312.piscesplayer.data.rhythm.MidiChartOptions
import com.pisces312.piscesplayer.data.rhythm.RhythmAnalysisParams
import com.pisces312.piscesplayer.data.rhythm.RhythmChart
import com.pisces312.piscesplayer.data.rhythm.RhythmChartExport
import com.pisces312.piscesplayer.data.rhythm.RhythmChartMeta
import com.pisces312.piscesplayer.data.rhythm.LatencyRouteKind
import com.pisces312.piscesplayer.data.rhythm.RhythmChartSettings
import com.pisces312.piscesplayer.data.rhythm.RhythmDeriveMode
import com.pisces312.piscesplayer.data.rhythm.RhythmDeriveSettings
import com.pisces312.piscesplayer.data.rhythm.RhythmLatencyStore

import com.pisces312.piscesplayer.data.rhythm.RhythmRepository
import com.pisces312.piscesplayer.data.rhythm.RhythmUiPreferences
import com.pisces312.piscesplayer.data.rhythm.RhythmUiStyle
import com.pisces312.piscesplayer.presentation.components.AutoScrollingText
import com.pisces312.piscesplayer.presentation.components.rememberModalSheetState
import com.pisces312.piscesplayer.presentation.navigation.Screen
import com.pisces312.piscesplayer.presentation.navigation.navigateSafely
import com.pisces312.piscesplayer.presentation.viewmodel.PlayerViewModel
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Master timestamps: minute precision is enough for a per-song singleton. */
private val MASTER_NAME_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault())

/** Derived charts: compact `MMdd HH:mm`, the mode is what tells paired charts apart. */
private val DERIVED_NAME_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("MMdd HH:mm").withZone(ZoneId.systemDefault())

/** Everything the screen needs about one song's charts, loaded in one pass. */
private data class LibrarySnapshot(
    val charts: List<RhythmChartMeta> = emptyList(),
    val settings: Map<String, RhythmChartSettings> = emptyMap(),
    val masterChart: RhythmChart? = null,
    /** Note timestamps per chart, for counts computed AFTER the lead-in mask. */
    val noteTimes: Map<String, List<Int>> = emptyMap(),
) {
    val master: RhythmChartMeta?
        get() = charts.firstOrNull { it.difficultyKey == RhythmChart.DIFFICULTY_RAW }

    val derived: List<RhythmChartMeta>
        get() = charts
            .filter { it.difficultyKey != null && it.difficultyKey != RhythmChart.DIFFICULTY_RAW }
            .sortedByDescending { it.importedAtMs }

    val others: List<RhythmChartMeta>
        get() = charts.filter { it.difficultyKey == null }.sortedByDescending { it.importedAtMs }
}

/** Note count / density the player will actually see, after the chart's lead-in mask. */
private data class VisibleStats(val count: Int, val notesPerSec: Double)

/**
 * The play-time settings one chart will actually run with: the effective latency offset (the
 * chart's override when set, otherwise the current route's calibrated value), the lead-in mask
 * and the note fall time. Always shown on the card so the values are visible before starting.
 */
private data class ChartPlaySettings(val delayMs: Int, val leadInMs: Int, val approachMs: Int)

private fun visibleStats(
    times: List<Int>?,
    leadInMs: Int,
    meta: RhythmChartMeta,
): VisibleStats {
    if (times.isNullOrEmpty()) return VisibleStats(meta.noteCount, meta.averageNps)
    val visible = times.count { it >= leadInMs }
    val last = times.lastOrNull() ?: return VisibleStats(visible, meta.averageNps)
    val spanMs = (last - leadInMs).coerceAtLeast(1)
    return VisibleStats(visible, visible * 1000.0 / spanMs)
}

/**
 * Song-scoped rhythm game entry.
 *
 * Two tiers: one master chart per song (analyse once, overwrite at will) and any number of
 * difficulties derived from it. Deriving is instant, so the sheet can show exact note counts
 * before the user commits.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RhythmSetupScreen(
    songId: String,
    navController: androidx.navigation.NavHostController,
    playerViewModel: PlayerViewModel,
    onNavigationIconClick: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Application context: the repository outlives the Activity scope.
    val repository = remember { RhythmRepository(context.applicationContext) }

    val song by playerViewModel.observeSong(songId)
        .collectAsStateWithLifecycle(initialValue = null)

    var snapshot by remember { mutableStateOf(LibrarySnapshot()) }
    var importing by remember { mutableStateOf(false) }
    var importError by remember { mutableStateOf<String?>(null) }
    var pendingDelete by remember { mutableStateOf<RhythmChartMeta?>(null) }
    var pendingDeleteDerived by remember { mutableStateOf(false) }
    var settingsTarget by remember { mutableStateOf<RhythmChartMeta?>(null) }

    // Per-device latency: the resolved value for the output that is connected now, its last
    // measurement (for "restore"), and every saved record — a headset that is not connected still
    // owns a number nobody else can see otherwise.
    var latencySetting by remember { mutableStateOf(RhythmLatencyStore.resolve(context)) }
    var calibratedOffsetMs by remember { mutableStateOf<Int?>(null) }
    var savedLatency by remember { mutableStateOf(RhythmLatencyStore.savedDevices(context)) }
    var nearbyGranted by remember { mutableStateOf(RhythmLatencyStore.hasBluetoothConnect(context)) }
    var showLatencyCalibration by remember { mutableStateOf(false) }

    fun refreshLatency() {
        latencySetting = RhythmLatencyStore.resolve(context)
        savedLatency = RhythmLatencyStore.savedDevices(context)
        nearbyGranted = RhythmLatencyStore.hasBluetoothConnect(context)
        calibratedOffsetMs = RhythmLatencyStore.calibratedOffsetMs(context, latencySetting.device)
    }

    fun latencyLabelOf(device: com.pisces312.piscesplayer.data.rhythm.LatencyDevice): String =
        device.displayName.ifBlank { context.getString(device.kind.labelRes) }

    // The Bluetooth permission is the difference between one shared number and one per headset:
    // asked once, from the settings sheet, and never blocking calibration itself.
    val nearbyLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        RhythmLatencyStore.markNearbyPermissionAsked(context)
        if (granted) {
            // The id flips from bt:<name> to bt:<mac>; move the record so it is not "lost".
            RhythmLatencyStore.adoptNameKeyedRecord(
                context,
                RhythmLatencyStore.activeDevice(context),
            )
        }
        refreshLatency()
    }

    fun requestNearbyPermission() {
        nearbyLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
    }

    // Global game visuals (style + low motion). Persisted immediately on every change so the
    // next gameplay entry renders with the choice even if the process is killed in between.
    val uiPreferences = remember { RhythmUiPreferences(context.applicationContext) }
    var uiStyle by remember { mutableStateOf(uiPreferences.uiStyle) }
    var lowMotion by remember { mutableStateOf(uiPreferences.lowMotion) }

    // Chart export: the format dialog parks the target here until the SAF create-document
    // round trip returns the destination uri.
    var exportTarget by remember { mutableStateOf<RhythmChartMeta?>(null) }
    var pendingExport by remember { mutableStateOf<Pair<RhythmChartMeta, RhythmChartExport.Format>?>(null) }

    var showGenerateSheet by remember { mutableStateOf(false) }
    var showMidiOptions by remember { mutableStateOf(false) }
    var overwriting by remember { mutableStateOf(false) }
    var generating by remember { mutableStateOf(false) }
    var generationProgress by remember { mutableFloatStateOf(0f) }
    var generationError by remember { mutableStateOf<String?>(null) }
    var generationJob by remember { mutableStateOf<Job?>(null) }
    var lastGenerated by remember { mutableStateOf<MasterGenerationResult?>(null) }

    // Entry ①「生成谱面」: its own master analysis run (the derive step opens the derive sheet).
    var quickGenerating by remember { mutableStateOf(false) }
    var quickPhase by remember { mutableStateOf(RhythmQuickPhase.ANALYZING) }
    var quickProgress by remember { mutableFloatStateOf(0f) }
    var quickJob by remember { mutableStateOf<Job?>(null) }

    // Game settings (device latency + game visuals), opened from the top bar.
    var showSettingsSheet by remember { mutableStateOf(false) }

    // Ask once, the first time the settings sheet opens on an unidentified Bluetooth output —
    // that is exactly when the user is trying to calibrate it. Refusing never blocks anything.
    LaunchedEffect(showSettingsSheet) {
        if (
            showSettingsSheet &&
            latencySetting.device.kind == LatencyRouteKind.BLUETOOTH &&
            !nearbyGranted &&
            !RhythmLatencyStore.nearbyPermissionAsked(context)
        ) {
            RhythmLatencyStore.markNearbyPermissionAsked(context)
            requestNearbyPermission()
        }
    }

    var showDeriveSheet by remember { mutableStateOf(false) }
    var deriving by remember { mutableStateOf(false) }

    val cloudUnavailableMessage = stringResource(R.string.rhythm_cloud_not_supported)
    val songTooLongMessage = stringResource(
        R.string.rhythm_generate_song_too_long,
        (RhythmRepository.MAX_ANALYSIS_DURATION_MS / 60_000L).toInt(),
    )
    val cancelledMessage = stringResource(R.string.rhythm_generate_cancelled)
    val customDifficultyLabel = stringResource(R.string.rhythm_derive_custom)
    val modeLabels = mapOf(
        RhythmDeriveMode.GRID to stringResource(R.string.rhythm_derive_mode_grid),
        RhythmDeriveMode.ONSET to stringResource(R.string.rhythm_derive_mode_onset),
        RhythmDeriveMode.BEAT_FIRST to stringResource(R.string.rhythm_derive_mode_beat_first),
    )

    suspend fun reload() {
        val charts = repository.chartsForSong(songId)
        val settings = repository.settingsFor(charts.map { it.chartId })
        val masterChart = charts
            .firstOrNull { it.difficultyKey == RhythmChart.DIFFICULTY_RAW }
            ?.let { repository.loadChart(it.chartId) }
        val noteTimes = charts.mapNotNull { meta ->
            repository.loadChart(meta.chartId)?.let { meta.chartId to it.notes.map { n -> n.timeMs } }
        }.toMap()
        snapshot = LibrarySnapshot(
            charts = charts,
            settings = settings,
            masterChart = masterChart,
            noteTimes = noteTimes,
        )
        refreshLatency()
    }

    fun refresh() {
        scope.launch { reload() }
    }

    /**
     * Start a run: every chart plays as stored (the play-time A/B re-bake was retired). The
     * interface style is already picked on the「游戏视觉」card and persisted
     * ([RhythmUiPreferences]), so the tap navigates straight into the game — no start dialog.
     */
    fun playChart(meta: RhythmChartMeta) {
        navController.navigateSafely(Screen.RhythmGame.createRoute(songId, meta.chartId))
    }

    fun startGeneration(params: RhythmAnalysisParams) {
        val currentSong = song ?: return
        if (CloudOfflineRepository.isCloudSong(currentSong)) {
            generationError = cloudUnavailableMessage
            return
        }
        if (currentSong.duration > RhythmRepository.MAX_ANALYSIS_DURATION_MS) {
            generationError = songTooLongMessage
            return
        }
        val playbackUri = currentSong.contentUriString.takeIf { it.isNotBlank() }
            ?.let(Uri::parse)
            ?: Uri.fromFile(File(currentSong.path))

        generationError = null
        generationProgress = 0f
        generating = true
        generationJob = scope.launch {
            try {
                val result = repository.generateMaster(
                    songId = songId,
                    uri = playbackUri,
                    params = params,
                    onProgress = { generationProgress = it },
                )
                lastGenerated = result
                showGenerateSheet = false
                reload()
            } catch (cancelled: CancellationException) {
                generationError = cancelledMessage
                throw cancelled
            } catch (e: Exception) {
                generationError = e.message ?: e.javaClass.simpleName
            } finally {
                generating = false
                generationJob = null
            }
        }
    }

    /**
     * MIDI 曲目：解析文件里的音符事件与 tempo map 直接成谱（[RhythmRepository.importMidiCharts]），
     * 不走 PCM 解码 + DSP 分析的母带流程，也不要求时长限制。难度档与密度上限由 [options] 决定
     * （快捷生成用默认三档，参数面板则传用户调整后的值）。
     */
    fun startMidiImport(options: MidiChartOptions) {
        val currentSong = song ?: return
        if (CloudOfflineRepository.isCloudSong(currentSong)) {
            generationError = cloudUnavailableMessage
            return
        }
        val playbackUri = currentSong.contentUriString.takeIf { it.isNotBlank() }
            ?.let(Uri::parse)
            ?: Uri.fromFile(File(currentSong.path))

        generationError = null
        generating = true
        scope.launch {
            try {
                val metas = repository.importMidiCharts(
                    songId = songId,
                    uri = playbackUri,
                    options = options,
                )
                reload()
                showMidiOptions = false
                if (metas.isNotEmpty()) {
                    playerViewModel.sendToast(
                        context.getString(R.string.rhythm_midi_generated, metas.size),
                    )
                }
            } catch (e: Exception) {
                generationError = e.message ?: e.javaClass.simpleName
            } finally {
                generating = false
            }
        }
    }

    fun derive(settings: RhythmDeriveSettings) {
        val master = snapshot.masterChart ?: return
        val masterMeta = snapshot.master ?: return
        val modeNames = modeLabels
        // Creation order: hardest first, so the easiest chart lands at the top of the list.
        val specs = settings.difficultySpecs(customDifficultyLabel).sortedByDescending { it.slotsPerBeat }
        deriving = true
        scope.launch {
            try {
                val stamp = DERIVED_NAME_FORMATTER.format(Instant.now())
                val names = buildMap {
                    for (spec in specs) {
                        for (mode in settings.modes) {
                            put(
                                spec to mode,
                                "${spec.label} · " +
                                    "${modeNames.getValue(mode)} · $stamp",
                            )
                        }
                    }
                }
                val created = repository.deriveCharts(
                    songId = songId,
                    master = master,
                    masterMeta = masterMeta,
                    difficulties = specs,
                    modes = settings.modes,
                    laneStrategy = settings.laneStrategy,
                    displayNames = names,
                    maxChord = settings.maxChord,
                    onsetSelection = settings.selection,
                    options = settings.options(),
                )
                showDeriveSheet = false
                reload()
                if (created.isNotEmpty()) {
                    playerViewModel.sendToast(
                        context.getString(R.string.rhythm_derive_done, created.size),
                    )
                }
            } catch (e: Exception) {
                generationError = e.message ?: e.javaClass.simpleName
            } finally {
                deriving = false
            }
        }
    }

    /**
     * Entry ①「生成谱面」(stepwise by design, 2026-11-24): analyse the master first (only when
     * it is missing), then hand over to the derive sheet so every derive parameter stays
     * tunable. MIDI songs have no master — the entry opens the MIDI generation sheet right
     * away. Cancelling the analysis deletes whatever the run created ([RhythmQuickPlay]).
     */
    fun quickGenerate() {
        val currentSong = song ?: return
        if (currentSong.isRhythmMidiSong()) {
            generationError = null
            showMidiOptions = true
            return
        }
        if (snapshot.master != null) {
            if (snapshot.masterChart != null) {
                showDeriveSheet = true
            } else {
                // Master metadata without a loadable body (failed load / stale row): rebuilding
                // it needs the explicit overwrite confirmation, so go through the generate sheet
                // instead of opening a derive sheet with nothing to derive from.
                generationError = null
                overwriting = true
                showGenerateSheet = true
            }
            return
        }
        generationError = null
        quickGenerating = true
        quickProgress = 0f
        quickJob = scope.launch {
            try {
                RhythmQuickPlay(context, repository).ensureMaster(currentSong) { _, value ->
                    quickProgress = value
                }
                reload()
                showDeriveSheet = true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                generationError = e.message ?: e.javaClass.simpleName
            } finally {
                quickGenerating = false
            }
        }
    }

    /** Cancel the running analysis; the pipeline deletes the charts this run created. */
    fun cancelQuickGenerate() {
        val running = quickJob ?: return
        scope.launch {
            running.cancelAndJoin()
            playerViewModel.sendToast(context.getString(R.string.rhythm_quick_cancelled))
        }
    }

    fun saveSettings(meta: RhythmChartMeta, updated: RhythmChartSettings) {
        scope.launch {
            val stored = repository.saveSettings(meta.chartId, updated)
            snapshot = snapshot.copy(settings = snapshot.settings + (meta.chartId to stored))
        }
    }

    /** Write the chart export payload to the SAF destination picked by the user. */
    fun performExport(meta: RhythmChartMeta, format: RhythmChartExport.Format, uri: Uri) {
        val currentSong = song
        scope.launch {
            try {
                val name = withContext(Dispatchers.IO) {
                    val chart = repository.loadChart(meta.chartId)
                        ?: throw IllegalStateException("chart not found")
                    val take = repository.tapTakeFor(meta.chartId)
                    val info = RhythmChartExport.SongInfo(
                        title = currentSong?.title.orEmpty(),
                        artist = currentSong?.artist.orEmpty(),
                        durationMs = currentSong?.duration ?: 0L,
                        audioFileName = currentSong?.path?.let { File(it).name }.orEmpty(),
                        audioFingerprint = currentSong
                            ?.let { RhythmChartExport.audioFingerprint(context, it) }
                            .orEmpty(),
                    )
                    val bytes = RhythmChartExport.build(format, chart, take, info)
                    val out = context.contentResolver.openOutputStream(uri)
                        ?: throw IllegalStateException("cannot open the export destination")
                    out.use { it.write(bytes) }
                    RhythmChartExport.fileName(format, currentSong?.title.orEmpty(), meta.difficultyName)
                }
                playerViewModel.sendToast(context.getString(R.string.rhythm_export_done, name))
            } catch (e: Exception) {
                playerViewModel.sendToast(
                    context.getString(R.string.rhythm_export_failed, e.message ?: e.javaClass.simpleName),
                )
            }
        }
    }

    // Refresh on resume so the best score is current after returning from a run.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, songId) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                scope.launch { reload() }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        importing = true
        importError = null
        scope.launch {
            try {
                repository.importOsu(songId, uri)
                refresh()
            } catch (e: Exception) {
                importError = e.message ?: e.javaClass.simpleName
            } finally {
                importing = false
            }
        }
    }

    val exportLaunchers = rememberExportLaunchers { format, uri ->
        val target = pendingExport
        pendingExport = null
        if (uri != null && target != null) {
            performExport(target.first, format, uri)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    // Song title + artist own the very top of the entry page (2026-11-24);
                    // the fixed screen label only shows while the song is still loading.
                    val headerSong = song
                    if (headerSong != null) {
                        Column {
                            Text(
                                text = headerSong.title,
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = headerSong.displayArtist,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    } else {
                        Text(
                            text = stringResource(R.string.rhythm_setup_title),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigationIconClick) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.auth_cd_back),
                        )
                    }
                },
                actions = {
                    // Device latency + game visuals live behind one settings button (2026-11-24).
                    IconButton(onClick = { showSettingsSheet = true }) {
                        Icon(
                            imageVector = Icons.Outlined.Settings,
                            contentDescription = stringResource(R.string.rhythm_settings_cd),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        val masterMeta = snapshot.master
        val masterBpm = snapshot.masterChart?.timing?.firstOrNull()?.beatMs
            ?.let { 60_000.0 / it }
        val masterBpmText = masterBpm?.let { String.format(Locale.US, "%.1f", it) }.orEmpty()

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            // The mini player is hidden across the whole rhythm game flow (2026-11-22), so
            // no player reservation is needed here; Scaffold.innerPadding already handles
            // the system navigation inset.
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = 16.dp,
                bottom = 16.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                // The single creation entry carries the state-dependent label (2026-11-25): with
                // no master it generates one, with a master present it is the derive entry the
                // master card used to duplicate, and for MIDI songs it opens the MIDI sheet.
                QuickEntryCard(
                    generateTitle = stringResource(
                        when {
                            song?.isRhythmMidiSong() == true -> R.string.rhythm_entry_generate
                            masterMeta != null -> R.string.rhythm_derive_action
                            else -> R.string.rhythm_entry_generate
                        },
                    ),
                    generateHint = stringResource(
                        when {
                            song?.isRhythmMidiSong() == true -> R.string.rhythm_entry_generate_hint_midi
                            masterMeta != null -> R.string.rhythm_entry_derive_hint
                            else -> R.string.rhythm_entry_generate_hint
                        },
                    ),
                    quickGenerating = quickGenerating,
                    quickPhase = quickPhase,
                    quickProgress = quickProgress,
                    importing = importing,
                    enabled = !importing && !generating && !quickGenerating && song != null,
                    onGenerate = { quickGenerate() },
                    onGenerateOptions = {
                        generationError = null
                        if (song?.isRhythmMidiSong() == true) {
                            showMidiOptions = true
                        } else {
                            overwriting = snapshot.master != null
                            showGenerateSheet = true
                        }
                    },
                    onImport = { importLauncher.launch(arrayOf("*/*")) },
                    onTap = {
                        navController.navigateSafely(Screen.RhythmRecord.createRoute(songId))
                    },
                    onCancel = { cancelQuickGenerate() },
                )
            }

            importError?.let { error ->
                item {
                    Card {
                        Text(
                            text = stringResource(R.string.rhythm_import_failed, error),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
            }

            // Failures of the quick generate and of the advanced sheets alike: a sheet closes on
            // success but this page stays, so the message needs a home here.
            generationError?.let { error ->
                item {
                    Card {
                        Text(
                            text = stringResource(R.string.rhythm_import_failed, error),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
            }

            if (song?.isRhythmMidiSong() != true && masterMeta != null) {
                item {
                    SectionHeader(stringResource(R.string.rhythm_master_section))
                }

                item {
                    MasterCard(
                        meta = masterMeta,
                        bpmText = masterBpmText,
                        stats = visibleStats(
                            snapshot.noteTimes[masterMeta.chartId],
                            snapshot.settings[masterMeta.chartId]?.leadInMs ?: 0,
                            masterMeta,
                        ),
                        derivedCount = snapshot.derived.size,
                        onExport = { exportTarget = masterMeta },
                        onRegenerate = {
                            generationError = null
                            overwriting = true
                            showGenerateSheet = true
                        },
                        onDelete = { pendingDelete = masterMeta },
                    )
                }
            }

            if (snapshot.derived.isNotEmpty()) {
                // Delete-all hangs off the section title row (2026-11-25): it acts on exactly the
                // charts listed underneath, and keeps the master card's derive button full width.
                item {
                    SectionHeader(stringResource(R.string.rhythm_derived_section)) {
                        DeleteDerivedPill(
                            count = snapshot.derived.size,
                            enabled = !deriving,
                            onClick = { pendingDeleteDerived = true },
                        )
                    }
                }
                items(snapshot.derived, key = { it.chartId }) { meta ->
                    val settings = snapshot.settings[meta.chartId] ?: RhythmChartSettings.DEFAULT
                    ChartRow(
                        meta = meta,
                        stats = visibleStats(
                            snapshot.noteTimes[meta.chartId],
                            settings.leadInMs,
                            meta,
                        ),
                        playSettings = ChartPlaySettings(
                            delayMs = settings.delayOverrideMs ?: latencySetting.offsetMs,
                            leadInMs = settings.leadInMs,
                            approachMs = settings.approachMs,
                        ),
                        staleMaster = masterMeta != null &&
                            meta.masterGeneratedAtMs != null &&
                            meta.masterGeneratedAtMs != masterMeta.importedAtMs,
                        onPlay = { playChart(meta) },
                        onSettings = { settingsTarget = meta },
                        onDelete = { pendingDelete = meta },
                        // A derived chart only ever exists inside the app, so every one of them
                        // is saved out like a hand-tapped chart.
                        onExport = { exportTarget = meta },
                    )
                }
            }

            if (snapshot.others.isNotEmpty()) {
                item { SectionHeader(stringResource(R.string.rhythm_other_section)) }
                items(snapshot.others, key = { it.chartId }) { meta ->
                    val settings = snapshot.settings[meta.chartId] ?: RhythmChartSettings.DEFAULT
                    ChartRow(
                        meta = meta,
                        stats = visibleStats(
                            snapshot.noteTimes[meta.chartId],
                            settings.leadInMs,
                            meta,
                        ),
                        playSettings = ChartPlaySettings(
                            delayMs = settings.delayOverrideMs ?: latencySetting.offsetMs,
                            leadInMs = settings.leadInMs,
                            approachMs = settings.approachMs,
                        ),
                        staleMaster = false,
                        onPlay = { playChart(meta) },
                        onSettings = { settingsTarget = meta },
                        onDelete = { pendingDelete = meta },
                        // Export is offered for hand-tapped charts, like the derived ones above;
                        // a generated or imported chart already exists outside the app in its
                        // source form.
                        onExport = if (meta.source == RhythmChart.SOURCE_TAP) {
                            { exportTarget = meta }
                        } else {
                            null
                        },
                    )
                }
            }

            if (snapshot.charts.isEmpty() && importError == null && !importing) {
                item {
                    Card {
                        Text(
                            text = stringResource(R.string.rhythm_empty_hint),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
            }
        }
    }

    pendingDelete?.let { meta ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.rhythm_delete_chart_title)) },
            text = { Text(stringResource(R.string.rhythm_delete_chart_message, meta.difficultyName)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                repository.deleteChart(songId, meta.chartId)
                            }
                            refresh()
                            pendingDelete = null
                        }
                    }
                ) {
                    Text(stringResource(R.string.rhythm_delete_chart_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.rhythm_delete_chart_cancel))
                }
            },
        )
    }

    if (pendingDeleteDerived) {
        // Snapshot at composition time: the message shows the exact count that will be deleted.
        val derived = snapshot.derived
        AlertDialog(
            onDismissRequest = { pendingDeleteDerived = false },
            title = { Text(stringResource(R.string.rhythm_delete_derived_title)) },
            text = {
                Text(stringResource(R.string.rhythm_delete_derived_message, derived.size))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                derived.forEach { repository.deleteChart(songId, it.chartId) }
                            }
                            refresh()
                            pendingDeleteDerived = false
                            playerViewModel.sendToast(
                                context.getString(R.string.rhythm_delete_derived_done, derived.size),
                            )
                        }
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Text(stringResource(R.string.rhythm_delete_derived_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteDerived = false }) {
                    Text(stringResource(R.string.rhythm_delete_chart_cancel))
                }
            },
        )
    }

    settingsTarget?.let { meta ->
        RhythmChartSettingsSheet(
            chartName = meta.difficultyName,
            settings = snapshot.settings[meta.chartId] ?: RhythmChartSettings.DEFAULT,
            routeOffsetMs = latencySetting.offsetMs,
            routeLabel = latencyLabelOf(latencySetting.device),
            onChange = { updated -> saveSettings(meta, updated) },
            onDismiss = { settingsTarget = null },
        )
    }

    exportTarget?.let { meta ->
        ExportFormatDialog(
            chartName = meta.difficultyName,
            // Only hand-tapped charts keep a raw take; derived charts must not be offered a
            // format that would fail after the destination is picked.
            canExportTake = meta.source == RhythmChart.SOURCE_TAP,
            onPick = { format ->
                exportTarget = null
                pendingExport = meta to format
                exportLaunchers.getValue(format).launch(
                    RhythmChartExport.fileName(format, song?.title.orEmpty(), meta.difficultyName),
                )
            },
            onDismiss = { exportTarget = null },
        )
    }

    // Game settings from the top bar: device latency and game visuals in one sheet.
    if (showSettingsSheet) {
        ModalBottomSheet(
            onDismissRequest = { showSettingsSheet = false },
            sheetState = rememberModalSheetState(skipPartiallyExpanded = true),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(R.string.rhythm_settings_sheet_title),
                    style = MaterialTheme.typography.titleLarge,
                )
                LatencyDeviceCard(
                    setting = latencySetting,
                    calibratedOffsetMs = calibratedOffsetMs,
                    onCalibrate = {
                        // The overlay is a Box in this window, while the sheet is its own window:
                        // leaving it open would draw the sheet over the overlay and swallow taps
                        // during the measured beats.
                        showSettingsSheet = false
                        showLatencyCalibration = true
                    },
                    onRestore = {
                        RhythmLatencyStore.restoreCalibrated(context, latencySetting.device)
                        refreshLatency()
                    },
                )
                LatencyOffsetField(
                    setting = latencySetting,
                    onCommit = { ms ->
                        RhythmLatencyStore.setManual(context, latencySetting.device, ms)
                        refreshLatency()
                    },
                )
                if (latencySetting.device.kind == LatencyRouteKind.BLUETOOTH && !nearbyGranted) {
                    NearbyDevicesHint(onRequest = { requestNearbyPermission() })
                }
                SavedLatencyDevices(
                    active = latencySetting,
                    saved = savedLatency,
                    onForget = { id ->
                        RhythmLatencyStore.forget(context, id)
                        refreshLatency()
                    },
                )
                GameVisualsCard(
                    uiStyle = uiStyle,
                    lowMotion = lowMotion,
                    onStyleChange = { style ->
                        uiStyle = style
                        uiPreferences.uiStyle = style
                    },
                    onLowMotionChange = { enabled ->
                        lowMotion = enabled
                        uiPreferences.lowMotion = enabled
                    },
                )
            }
        }
    }

    if (showGenerateSheet) {
        RhythmGenerateSheet(
            songTitle = song?.title.orEmpty(),
            overwriting = overwriting && snapshot.master != null,
            generating = generating,
            progress = generationProgress,
            error = generationError,
            onDismiss = {
                if (!generating) {
                    showGenerateSheet = false
                    generationError = null
                }
            },
            onStart = { params -> startGeneration(params) },
            onCancel = { generationJob?.cancel() },
        )
    }

    if (showMidiOptions) {
        RhythmMidiGenerateSheet(
            generating = generating,
            error = generationError,
            onDismiss = {
                if (!generating) {
                    showMidiOptions = false
                    generationError = null
                }
            },
            onGenerate = { options -> startMidiImport(options) },
        )
    }

    if (showDeriveSheet) {
        // The master body is loaded with the snapshot, so this is only null while it is being
        // reloaded; opening the sheet is guarded on the same condition.
        val masterChart = snapshot.masterChart
        if (masterChart != null) {
            RhythmDeriveSheet(
                master = masterChart,
                deriving = deriving,
                onComputeEstimates = { settings ->
                    repository.previewDerivedCounts(
                        master = masterChart,
                        difficulties = settings.difficultySpecs(customDifficultyLabel),
                        modes = settings.modes,
                        laneStrategy = settings.laneStrategy,
                        maxChord = settings.maxChord,
                        onsetSelection = settings.selection,
                        options = settings.options(),
                    )
                },
                onDismiss = { if (!deriving) showDeriveSheet = false },
                onDerive = { settings -> derive(settings) },
            )
        }
    }

    lastGenerated?.let { generated ->
        AlertDialog(
            onDismissRequest = { lastGenerated = null },
            title = { Text(stringResource(R.string.rhythm_generated_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.rhythm_generated_message,
                        generated.meta.noteCount,
                        String.format(Locale.US, "%.1f", generated.meta.averageNps),
                        String.format(Locale.US, "%.1f", generated.diagnostics.usedBpm),
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        lastGenerated = null
                        if (snapshot.masterChart != null) showDeriveSheet = true
                    },
                ) {
                    Text(stringResource(R.string.rhythm_derive_action))
                }
            },
            dismissButton = {
                TextButton(onClick = { lastGenerated = null }) {
                    Text(stringResource(R.string.rhythm_generated_later))
                }
            },
        )
    }

    // Full-screen layer emitted last so it covers the scaffold: calibrate the CURRENT route,
    // apply, and start a run while the link is still warm.
    if (showLatencyCalibration) {
        RhythmCalibrationOverlay(
            currentOffsetMs = latencySetting.offsetMs,
            currentSigmaMs = latencySetting.sigmaMs,
            playerViewModel = playerViewModel,
            onApplied = { applied, sigma, device ->
                showLatencyCalibration = false
                // Came from the sheet: land back on it, showing the value that was just saved.
                showSettingsSheet = true
                val active = RhythmLatencyStore.activeDevice(context)
                refreshLatency()
                playerViewModel.sendToast(
                    if (active.id == device.id) {
                        context.getString(
                            R.string.rhythm_latency_applied,
                            latencyLabelOf(device),
                            applied,
                            sigma.roundToInt(),
                        )
                    } else {
                        context.getString(
                            R.string.rhythm_latency_saved_to_other,
                            latencyLabelOf(device),
                            latencyLabelOf(active),
                        )
                    },
                )
            },
            onDismiss = {
                showLatencyCalibration = false
                showSettingsSheet = true
            },
        )
    }
}

@Composable
private fun SectionHeader(
    title: String,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        trailing?.invoke()
    }
}

/**
 * Destructive "delete every derived chart" action, rendered as a pill on the derived-charts
 * section title row (2026-11-25). It keeps the full label including the count, so the label is
 * locked to one line with ellipsis instead of wrapping under the fixed height.
 */
@Composable
private fun DeleteDerivedPill(
    count: Int,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.height(40.dp),
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.error.copy(alpha = 0.55f),
        ),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = MaterialTheme.colorScheme.error,
            disabledContentColor = MaterialTheme.colorScheme.error.copy(alpha = 0.38f),
        ),
        contentPadding = PaddingValues(horizontal = 12.dp),
    ) {
        Icon(
            imageVector = Icons.Outlined.DeleteSweep,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(5.dp))
        Text(
            text = stringResource(R.string.rhythm_delete_derived_all, count),
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 「游戏视觉」: the global visual-style choice for the rhythm game surface.
 *
 * Classic (default) is the pre-existing plain interface and stays untouched as the fallback;
 * neon opts into the glowing surface. Low motion only tames the neon surface's animated
 * extras (particles, star field, pulses), so it is presented as a neon-only refinement.
 * Every change is persisted immediately ([RhythmUiPreferences]).
 */
@Composable
private fun GameVisualsCard(
    uiStyle: RhythmUiStyle,
    lowMotion: Boolean,
    onStyleChange: (RhythmUiStyle) -> Unit,
    onLowMotionChange: (Boolean) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = stringResource(R.string.rhythm_visual_section),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(R.string.rhythm_ui_style_label),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            RhythmUiStyle.entries.forEach { style ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onStyleChange(style) },
                ) {
                    RadioButton(
                        selected = uiStyle == style,
                        onClick = { onStyleChange(style) },
                    )
                    Column {
                        Text(
                            text = stringResource(
                                when (style) {
                                    RhythmUiStyle.CLASSIC -> R.string.rhythm_ui_style_classic
                                    RhythmUiStyle.NEON -> R.string.rhythm_ui_style_neon
                                },
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            text = stringResource(
                                when (style) {
                                    RhythmUiStyle.CLASSIC -> R.string.rhythm_ui_style_classic_desc
                                    RhythmUiStyle.NEON -> R.string.rhythm_ui_style_neon_desc
                                },
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.rhythm_visual_low_motion),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = stringResource(R.string.rhythm_visual_low_motion_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = lowMotion,
                    onCheckedChange = onLowMotionChange,
                )
            }
        }
    }
}

/**
 * The three chart creation entries in one card (2026-11-24):「生成谱面」runs the master analysis
 * and then opens the derive sheet so its parameters stay tunable — long-press it for the
 * analysis parameters —「导入谱面」picks an osu!mania file and「手打谱面」opens the tap recorder.
 * While the analysis runs, the wave progress bar shows below the rows with a cancel button
 * (cancelling deletes what the run created).
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun QuickEntryCard(
    generateTitle: String,
    generateHint: String,
    quickGenerating: Boolean,
    quickPhase: RhythmQuickPhase,
    quickProgress: Float,
    importing: Boolean,
    enabled: Boolean,
    onGenerate: () -> Unit,
    onGenerateOptions: () -> Unit,
    onImport: () -> Unit,
    onTap: () -> Unit,
    onCancel: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            QuickEntryRow(
                icon = Icons.Outlined.AutoAwesome,
                title = generateTitle,
                subtitle = generateHint,
                enabled = enabled,
                onClick = onGenerate,
                onLongClick = onGenerateOptions,
            )
            QuickEntryRow(
                icon = Icons.Outlined.UploadFile,
                title = stringResource(R.string.rhythm_entry_import),
                subtitle = stringResource(R.string.rhythm_entry_import_hint),
                enabled = enabled,
                busy = importing,
                onClick = onImport,
            )
            QuickEntryRow(
                icon = Icons.Outlined.MusicNote,
                title = stringResource(R.string.rhythm_entry_tap),
                subtitle = stringResource(R.string.rhythm_entry_tap_hint),
                enabled = enabled,
                onClick = onTap,
            )
            if (quickGenerating) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp, bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = stringResource(
                            if (quickPhase == RhythmQuickPhase.ANALYZING) {
                                R.string.rhythm_quick_phase_analyze
                            } else {
                                R.string.rhythm_quick_phase_derive
                            },
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (quickPhase == RhythmQuickPhase.ANALYZING) {
                        LinearWavyProgressIndicator(
                            progress = { quickProgress },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(onClick = onCancel) {
                            Text(stringResource(R.string.cancel))
                        }
                    }
                }
            }
        }
    }
}

/** One uniform entry row of the [QuickEntryCard]. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun QuickEntryRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    enabled: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    busy: Boolean = false,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (onLongClick != null) {
                    Modifier.combinedClickable(
                        enabled = enabled,
                        onClick = onClick,
                        onLongClick = onLongClick,
                    )
                } else {
                    Modifier.clickable(enabled = enabled, onClick = onClick)
                },
            )
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (busy) {
            CircularProgressIndicator(
                modifier = Modifier.size(24.dp),
                strokeWidth = 2.dp,
            )
        } else {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (enabled) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The master chart card (2026-11-24 redesign, styled after the playable chart cards below):
 * the name owns a full line, every fact is a rounded chip under it and the icon actions
 * (export / regenerate / delete) sit on the title row. The master itself is not playable any
 * more — its raw onset set is far too dense to tap — deriving is what the master exists for,
 * so deriving lives on the single creation entry at the top of the page instead of being
 * duplicated here; this card carries facts and the icon actions only.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MasterCard(
    meta: RhythmChartMeta,
    bpmText: String,
    stats: VisibleStats,
    derivedCount: Int,
    onExport: () -> Unit,
    onRegenerate: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        val cardContainer = CardDefaults.cardColors().containerColor
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Title row owns the icon actions (2026-11-25): the master is not playable, so the
            // play-settings entry is gone and "regenerate" collapsed into a sync icon.
            Row(verticalAlignment = Alignment.CenterVertically) {
                AutoScrollingText(
                    text = stringResource(R.string.rhythm_master_name),
                    style = MaterialTheme.typography.titleMedium,
                    gradientEdgeColor = cardContainer,
                    modifier = Modifier.weight(1f),
                )
                FilledTonalIconButton(
                    onClick = onExport,
                    modifier = Modifier.size(46.dp),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.FileDownload,
                        contentDescription = stringResource(R.string.rhythm_export_chart_cd),
                    )
                }
                Spacer(Modifier.width(8.dp))
                FilledTonalIconButton(
                    onClick = onRegenerate,
                    modifier = Modifier.size(46.dp),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Sync,
                        contentDescription = stringResource(R.string.rhythm_master_regenerate),
                    )
                }
                Spacer(Modifier.width(8.dp))
                FilledTonalIconButton(
                    onClick = onDelete,
                    modifier = Modifier.size(46.dp),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Delete,
                        contentDescription = stringResource(R.string.rhythm_delete_chart_icon),
                    )
                }
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ChartParamChip(
                    text = stringResource(R.string.rhythm_source_dsp),
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                ChartParamChip(text = stringResource(R.string.rhythm_card_notes, stats.count))
                ChartParamChip(
                    text = stringResource(
                        R.string.rhythm_card_nps,
                        String.format(Locale.US, "%.1f", stats.notesPerSec),
                    ),
                )
                if (bpmText.isNotEmpty()) {
                    ChartParamChip(text = stringResource(R.string.rhythm_master_bpm, bpmText))
                }
                ChartParamChip(
                    text = stringResource(
                        R.string.rhythm_master_generated_at,
                        MASTER_NAME_FORMATTER.format(Instant.ofEpochMilli(meta.importedAtMs)),
                    ),
                )
                ChartParamChip(
                    text = stringResource(R.string.rhythm_master_derived_count, derivedCount),
                )
            }
        }
    }
}

/**
 * One playable chart card (derived or imported/tapped).
 *
 * Layout (2026-11-22 redesign): the title owns a full line and marques when it overflows;
 * every parameter is its own rounded chip below the title (source highlighted, the play-time
 * values de-emphasised as outline chips); the action row keeps the secondary icon buttons on
 * the left and anchors the primary "Start" to the right edge for thumb reach.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChartRow(
    meta: RhythmChartMeta,
    stats: VisibleStats,
    playSettings: ChartPlaySettings,
    staleMaster: Boolean,
    onPlay: () -> Unit,
    onSettings: () -> Unit,
    onDelete: () -> Unit,
    onExport: (() -> Unit)? = null,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        val cardContainer = CardDefaults.cardColors().containerColor
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AutoScrollingText(
                text = meta.difficultyName,
                style = MaterialTheme.typography.titleMedium,
                gradientEdgeColor = cardContainer,
                modifier = Modifier.fillMaxWidth(),
            )

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ChartParamChip(
                    text = stringResource(
                        when (meta.source) {
                            RhythmChart.SOURCE_DSP -> R.string.rhythm_source_dsp
                            RhythmChart.SOURCE_TAP -> R.string.rhythm_source_tap
                            else -> R.string.rhythm_source_osu
                        },
                    ),
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                ChartParamChip(text = stringResource(R.string.rhythm_card_notes, stats.count))
                ChartParamChip(
                    text = stringResource(
                        R.string.rhythm_card_nps,
                        String.format(Locale.US, "%.1f", stats.notesPerSec),
                    ),
                )
                ChartParamChip(
                    text = stringResource(R.string.rhythm_card_best_score, meta.bestScore),
                    contentColor = MaterialTheme.colorScheme.primary,
                )
                ChartParamChip(
                    text = stringResource(R.string.rhythm_card_delay_ms, playSettings.delayMs),
                    outlined = true,
                )
                ChartParamChip(
                    text = stringResource(R.string.rhythm_card_lead_in_ms, playSettings.leadInMs),
                    outlined = true,
                )
                ChartParamChip(
                    text = stringResource(R.string.rhythm_card_approach_ms, playSettings.approachMs),
                    outlined = true,
                )
                if (staleMaster) {
                    ChartParamChip(
                        text = stringResource(R.string.rhythm_chart_stale_master),
                        outlined = true,
                        contentColor = MaterialTheme.colorScheme.error,
                        borderColor = MaterialTheme.colorScheme.error,
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (onExport != null) {
                    FilledTonalIconButton(
                        onClick = onExport,
                        modifier = Modifier.size(46.dp),
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.FileDownload,
                            contentDescription = stringResource(R.string.rhythm_export_chart_cd),
                        )
                    }
                }
                FilledTonalIconButton(
                    onClick = onSettings,
                    modifier = Modifier.size(46.dp),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Settings,
                        contentDescription = stringResource(R.string.rhythm_settings_open),
                    )
                }
                FilledTonalIconButton(
                    onClick = onDelete,
                    modifier = Modifier.size(46.dp),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Delete,
                        contentDescription = stringResource(R.string.rhythm_delete_chart_icon),
                    )
                }
                // Primary action anchored to the right edge: the thumb reaches "Start" without
                // crossing the card.
                Button(
                    onClick = onPlay,
                    modifier = Modifier
                        .weight(1f)
                        .height(46.dp),
                    shape = RoundedCornerShape(23.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.PlayArrow,
                        contentDescription = null,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.rhythm_play_chart))
                }
            }
        }
    }
}

/** Rounded-rectangle parameter chip for the chart card. */
@Composable
private fun ChartParamChip(
    text: String,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    contentColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    outlined: Boolean = false,
    borderColor: Color = MaterialTheme.colorScheme.outlineVariant,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(9.dp),
        color = if (outlined) Color.Transparent else containerColor,
        contentColor = contentColor,
        border = if (outlined) BorderStroke(1.dp, borderColor) else null,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
        )
    }
}

/** One SAF create-document launcher per export format; the picker suggests the right mime type. */
@Composable
private fun rememberExportLaunchers(
    onResult: (RhythmChartExport.Format, Uri?) -> Unit,
): Map<RhythmChartExport.Format, ActivityResultLauncher<String>> {
    val osu = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(RhythmChartExport.Format.OSU.mimeType),
    ) { onResult(RhythmChartExport.Format.OSU, it) }
    val prc = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(RhythmChartExport.Format.PRC.mimeType),
    ) { onResult(RhythmChartExport.Format.PRC, it) }
    val json = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(RhythmChartExport.Format.CHART_JSON.mimeType),
    ) { onResult(RhythmChartExport.Format.CHART_JSON, it) }
    val take = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(RhythmChartExport.Format.TAP_JSON.mimeType),
    ) { onResult(RhythmChartExport.Format.TAP_JSON, it) }
    return remember {
        mapOf(
            RhythmChartExport.Format.OSU to osu,
            RhythmChartExport.Format.PRC to prc,
            RhythmChartExport.Format.CHART_JSON to json,
            RhythmChartExport.Format.TAP_JSON to take,
        )
    }
}

/**
 * Format picker for chart export: osu!mania beatmap, `.prc` bundle, unified chart JSON or, when
 * the chart keeps one ([canExportTake]), the raw tap take. The selection is confirmed before the
 * document picker opens.
 */
@Composable
private fun ExportFormatDialog(
    chartName: String,
    canExportTake: Boolean,
    onPick: (RhythmChartExport.Format) -> Unit,
    onDismiss: () -> Unit,
) {
    var selected by remember { mutableStateOf(RhythmChartExport.Format.OSU) }
    val options = buildList {
        add(
            Triple(
                RhythmChartExport.Format.OSU,
                R.string.rhythm_export_format_osu,
                R.string.rhythm_export_format_osu_desc,
            ),
        )
        add(
            Triple(
                RhythmChartExport.Format.PRC,
                R.string.rhythm_export_format_prc,
                R.string.rhythm_export_format_prc_desc,
            ),
        )
        add(
            Triple(
                RhythmChartExport.Format.CHART_JSON,
                R.string.rhythm_export_format_json,
                R.string.rhythm_export_format_json_desc,
            ),
        )
        if (canExportTake) {
            add(
                Triple(
                    RhythmChartExport.Format.TAP_JSON,
                    R.string.rhythm_export_format_take,
                    R.string.rhythm_export_format_take_desc,
                ),
            )
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.rhythm_export_title)) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.rhythm_export_subtitle, chartName),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(8.dp))
                options.forEach { (format, label, description) ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selected = format },
                    ) {
                        RadioButton(
                            selected = selected == format,
                            onClick = { selected = format },
                        )
                        Column {
                            Text(
                                text = stringResource(label),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                text = stringResource(description),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onPick(selected) }) {
                Text(stringResource(R.string.rhythm_export_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}
