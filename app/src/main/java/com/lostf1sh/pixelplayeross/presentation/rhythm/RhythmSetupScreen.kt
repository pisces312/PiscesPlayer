/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Original work in 2026 (chart import, master generation and difficulty derivation screen).
 */
package com.lostf1sh.pixelplayeross.presentation.rhythm

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.data.offline.CloudOfflineRepository
import com.lostf1sh.pixelplayeross.data.rhythm.DspDifficulty
import com.lostf1sh.pixelplayeross.data.rhythm.LaneStrategy
import com.lostf1sh.pixelplayeross.data.rhythm.MasterGenerationResult
import com.lostf1sh.pixelplayeross.data.rhythm.RhythmAnalysisParams
import com.lostf1sh.pixelplayeross.data.rhythm.RhythmChart
import com.lostf1sh.pixelplayeross.data.rhythm.RhythmChartMeta
import com.lostf1sh.pixelplayeross.data.rhythm.RhythmChartSettings
import com.lostf1sh.pixelplayeross.data.rhythm.RhythmRepository
import com.lostf1sh.pixelplayeross.presentation.navigation.Screen
import com.lostf1sh.pixelplayeross.presentation.navigation.navigateSafely
import com.lostf1sh.pixelplayeross.presentation.viewmodel.PlayerViewModel
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Master timestamps: minute precision is enough for a per-song singleton. */
private val MASTER_NAME_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault())

/** Derived charts: second precision, so repeated derivations stay distinguishable. */
private val DERIVED_NAME_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("MM-dd HH:mm:ss").withZone(ZoneId.systemDefault())

/** Everything the screen needs about one song's charts, loaded in one pass. */
private data class LibrarySnapshot(
    val charts: List<RhythmChartMeta> = emptyList(),
    val settings: Map<String, RhythmChartSettings> = emptyMap(),
    val masterChart: RhythmChart? = null,
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
    var settingsTarget by remember { mutableStateOf<RhythmChartMeta?>(null) }

    var showGenerateSheet by remember { mutableStateOf(false) }
    var overwriting by remember { mutableStateOf(false) }
    var generating by remember { mutableStateOf(false) }
    var generationProgress by remember { mutableFloatStateOf(0f) }
    var generationError by remember { mutableStateOf<String?>(null) }
    var generationJob by remember { mutableStateOf<Job?>(null) }
    var lastGenerated by remember { mutableStateOf<MasterGenerationResult?>(null) }

    var showDeriveSheet by remember { mutableStateOf(false) }
    var deriving by remember { mutableStateOf(false) }

    val cloudUnavailableMessage = stringResource(R.string.rhythm_cloud_not_supported)
    val songTooLongMessage = stringResource(
        R.string.rhythm_generate_song_too_long,
        (RhythmRepository.MAX_ANALYSIS_DURATION_MS / 60_000L).toInt(),
    )
    val cancelledMessage = stringResource(R.string.rhythm_generate_cancelled)
    val difficultyLabels = mapOf(
        DspDifficulty.EASY to stringResource(R.string.rhythm_generate_difficulty_easy),
        DspDifficulty.NORMAL to stringResource(R.string.rhythm_generate_difficulty_normal),
        DspDifficulty.HARD to stringResource(R.string.rhythm_generate_difficulty_hard),
    )

    suspend fun reload() {
        val charts = repository.chartsForSong(songId)
        val settings = repository.settingsFor(charts.map { it.chartId })
        val masterChart = charts
            .firstOrNull { it.difficultyKey == RhythmChart.DIFFICULTY_RAW }
            ?.let { repository.loadChart(it.chartId) }
        snapshot = LibrarySnapshot(charts = charts, settings = settings, masterChart = masterChart)
    }

    fun refresh() {
        scope.launch { reload() }
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

    fun derive(difficulties: List<DspDifficulty>, strategy: LaneStrategy) {
        val master = snapshot.masterChart ?: return
        val masterMeta = snapshot.master ?: return
        val labels = difficultyLabels
        deriving = true
        scope.launch {
            try {
                val stamp = DERIVED_NAME_FORMATTER.format(Instant.now())
                val names = difficulties.associateWith { "${labels[it]} · $stamp" }
                val created = repository.deriveCharts(
                    songId = songId,
                    master = master,
                    masterMeta = masterMeta,
                    difficulties = difficulties,
                    laneStrategy = strategy,
                    displayNames = names,
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

    fun saveSettings(meta: RhythmChartMeta, updated: RhythmChartSettings) {
        scope.launch {
            val stored = repository.saveSettings(meta.chartId, updated)
            snapshot = snapshot.copy(settings = snapshot.settings + (meta.chartId to stored))
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.rhythm_setup_title),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigationIconClick) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.auth_cd_back),
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
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                SongHeader(song = song)
            }

            item {
                Button(
                    onClick = { importLauncher.launch(arrayOf("*/*")) },
                    enabled = !importing && !generating && song != null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                ) {
                    if (importing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.width(12.dp))
                    } else {
                        Icon(
                            imageVector = Icons.Outlined.UploadFile,
                            contentDescription = null,
                        )
                        Spacer(Modifier.width(10.dp))
                    }
                    Text(stringResource(R.string.rhythm_import_osu))
                }
            }

            item {
                if (masterMeta == null) {
                    MasterMissingCard(
                        enabled = !generating && song != null,
                        onGenerate = {
                            generationError = null
                            overwriting = false
                            showGenerateSheet = true
                        },
                    )
                } else {
                    MasterCard(
                        meta = masterMeta,
                        bpmText = masterBpmText,
                        delayMs = snapshot.settings[masterMeta.chartId]?.delayMs ?: 0,
                        deriving = deriving,
                        onPlay = {
                            navController.navigateSafely(
                                Screen.RhythmGame.createRoute(songId, masterMeta.chartId),
                            )
                        },
                        onDerive = { if (snapshot.masterChart != null) showDeriveSheet = true },
                        onRegenerate = {
                            generationError = null
                            overwriting = true
                            showGenerateSheet = true
                        },
                        onSettings = { settingsTarget = masterMeta },
                        onDelete = { pendingDelete = masterMeta },
                    )
                }
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

            if (snapshot.derived.isNotEmpty()) {
                item { SectionHeader(stringResource(R.string.rhythm_derived_section)) }
                items(snapshot.derived, key = { it.chartId }) { meta ->
                    ChartRow(
                        meta = meta,
                        delayMs = snapshot.settings[meta.chartId]?.delayMs ?: 0,
                        staleMaster = masterMeta != null &&
                            meta.masterGeneratedAtMs != null &&
                            meta.masterGeneratedAtMs != masterMeta.importedAtMs,
                        onPlay = {
                            navController.navigateSafely(
                                Screen.RhythmGame.createRoute(songId, meta.chartId),
                            )
                        },
                        onSettings = { settingsTarget = meta },
                        onDelete = { pendingDelete = meta },
                    )
                }
            }

            if (snapshot.others.isNotEmpty()) {
                item { SectionHeader(stringResource(R.string.rhythm_other_section)) }
                items(snapshot.others, key = { it.chartId }) { meta ->
                    ChartRow(
                        meta = meta,
                        delayMs = snapshot.settings[meta.chartId]?.delayMs ?: 0,
                        staleMaster = false,
                        onPlay = {
                            navController.navigateSafely(
                                Screen.RhythmGame.createRoute(songId, meta.chartId),
                            )
                        },
                        onSettings = { settingsTarget = meta },
                        onDelete = { pendingDelete = meta },
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

    settingsTarget?.let { meta ->
        RhythmChartSettingsSheet(
            chartName = meta.difficultyName,
            settings = snapshot.settings[meta.chartId] ?: RhythmChartSettings.DEFAULT,
            onChange = { updated -> saveSettings(meta, updated) },
            onDismiss = { settingsTarget = null },
        )
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

    if (showDeriveSheet) {
        // The master body is loaded with the snapshot, so this is only null while it is being
        // reloaded; opening the sheet is guarded on the same condition.
        val masterChart = snapshot.masterChart
        if (masterChart != null) {
            RhythmDeriveSheet(
                masterNoteCount = masterChart.noteCount(),
                masterBpm = String.format(
                    Locale.US,
                    "%.1f",
                    60_000.0 / masterChart.timing.first().beatMs,
                ),
                deriving = deriving,
                onComputeEstimates = { strategy ->
                    repository.previewDerivedCounts(
                        master = masterChart,
                        difficulties = DspDifficulty.entries,
                        laneStrategy = strategy,
                    )
                },
                onDismiss = { if (!deriving) showDeriveSheet = false },
                onDerive = { difficulties, strategy -> derive(difficulties, strategy) },
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
}

@Composable
private fun SongHeader(song: Song?) {
    Column {
        Text(
            text = song?.title ?: stringResource(R.string.rhythm_song_loading),
            style = MaterialTheme.typography.titleLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = song?.displayArtist.orEmpty(),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun MasterMissingCard(enabled: Boolean, onGenerate: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.rhythm_master_missing_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(R.string.rhythm_master_missing_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                onClick = onGenerate,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(
                    imageVector = Icons.Outlined.AutoAwesome,
                    contentDescription = null,
                )
                Spacer(Modifier.width(10.dp))
                Text(stringResource(R.string.rhythm_master_generate))
            }
        }
    }
}

@Composable
private fun MasterCard(
    meta: RhythmChartMeta,
    bpmText: String,
    delayMs: Int,
    deriving: Boolean,
    onPlay: () -> Unit,
    onDerive: () -> Unit,
    onRegenerate: () -> Unit,
    onSettings: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Outlined.MusicNote,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.rhythm_master_name),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = stringResource(
                            R.string.rhythm_chart_stats,
                            meta.noteCount,
                            String.format(Locale.US, "%.1f", meta.averageNps),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onSettings) {
                    Icon(
                        imageVector = Icons.Outlined.Settings,
                        contentDescription = stringResource(R.string.rhythm_settings_open),
                    )
                }
                IconButton(onClick = onDelete) {
                    Icon(
                        imageVector = Icons.Outlined.Delete,
                        contentDescription = stringResource(R.string.rhythm_delete_chart_icon),
                    )
                }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (bpmText.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.rhythm_master_bpm, bpmText),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (delayMs != 0) {
                    Text(
                        text = stringResource(R.string.rhythm_generate_offset_value, delayMs),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Text(
                    text = stringResource(
                        R.string.rhythm_master_generated_at,
                        MASTER_NAME_FORMATTER.format(Instant.ofEpochMilli(meta.importedAtMs)),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(onClick = onPlay, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.rhythm_play_chart))
                }
                OutlinedButton(
                    onClick = onDerive,
                    enabled = !deriving,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.rhythm_derive_action))
                }
                TextButton(onClick = onRegenerate) {
                    Text(stringResource(R.string.rhythm_master_regenerate))
                }
            }
        }
    }
}

@Composable
private fun ChartRow(
    meta: RhythmChartMeta,
    delayMs: Int,
    staleMaster: Boolean,
    onPlay: () -> Unit,
    onSettings: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = meta.difficultyName,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(
                                if (meta.source == RhythmChart.SOURCE_DSP) {
                                    R.string.rhythm_source_dsp
                                } else {
                                    R.string.rhythm_source_osu
                                },
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            text = stringResource(
                                R.string.rhythm_chart_stats,
                                meta.noteCount,
                                String.format(Locale.US, "%.1f", meta.averageNps),
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (delayMs != 0) {
                            Text(
                                text = stringResource(R.string.rhythm_generate_offset_value, delayMs),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        if (staleMaster) {
                            Text(
                                text = stringResource(R.string.rhythm_chart_stale_master),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
                IconButton(onClick = onSettings) {
                    Icon(
                        imageVector = Icons.Outlined.Settings,
                        contentDescription = stringResource(R.string.rhythm_settings_open),
                    )
                }
                IconButton(onClick = onDelete) {
                    Icon(
                        imageVector = Icons.Outlined.Delete,
                        contentDescription = stringResource(R.string.rhythm_delete_chart_icon),
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.rhythm_best_score, meta.bestScore),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                OutlinedButton(onClick = onPlay) {
                    Text(stringResource(R.string.rhythm_play_chart))
                }
            }
        }
    }
}
