/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Original work in 2026 (chart import and selection screen).
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
import com.lostf1sh.pixelplayeross.data.rhythm.DspGenerationResult
import com.lostf1sh.pixelplayeross.data.rhythm.RhythmChart
import com.lostf1sh.pixelplayeross.data.rhythm.RhythmChartGenParams
import com.lostf1sh.pixelplayeross.data.rhythm.RhythmChartMeta
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

/** Timestamp suffix so repeated generations of the same song stay distinguishable. */
private val CHART_NAME_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault())

/**
 * Song-scoped rhythm game entry: import a 4K osu!mania chart and pick one to play.
 * The chart is always bound to this song; no second song library is created.
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

    var charts by remember { mutableStateOf<List<RhythmChartMeta>>(emptyList()) }
    var importing by remember { mutableStateOf(false) }
    var importError by remember { mutableStateOf<String?>(null) }
    var pendingDelete by remember { mutableStateOf<RhythmChartMeta?>(null) }

    var showGenerateSheet by remember { mutableStateOf(false) }
    var generating by remember { mutableStateOf(false) }
    var generationProgress by remember { mutableFloatStateOf(0f) }
    var generationError by remember { mutableStateOf<String?>(null) }
    var generationJob by remember { mutableStateOf<Job?>(null) }
    var lastGenerated by remember { mutableStateOf<DspGenerationResult?>(null) }

    val cloudUnavailableMessage = stringResource(R.string.rhythm_cloud_not_supported)
    val songTooLongMessage = stringResource(
        R.string.rhythm_generate_song_too_long,
        (RhythmRepository.MAX_ANALYSIS_DURATION_MS / 60_000L).toInt(),
    )
    val cancelledMessage = stringResource(R.string.rhythm_generate_cancelled)

    fun refreshCharts() {
        scope.launch {
            charts = repository.chartsForSong(songId)
        }
    }

    fun startGeneration(params: RhythmChartGenParams) {
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
                val displayName = "DSP ${params.difficulty.label} · " +
                    CHART_NAME_FORMATTER.format(Instant.now())
                val result = repository.generateDsp(
                    songId = songId,
                    uri = playbackUri,
                    params = params,
                    displayName = displayName,
                    onProgress = { generationProgress = it },
                )
                lastGenerated = result
                showGenerateSheet = false
                charts = repository.chartsForSong(songId)
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

    // Refresh on resume so the best score is current after returning from a run.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, songId) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                scope.launch { charts = repository.chartsForSong(songId) }
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
                refreshCharts()
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
                OutlinedButton(
                    onClick = {
                        generationError = null
                        generationProgress = 0f
                        showGenerateSheet = true
                    },
                    enabled = !importing && !generating && song != null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.AutoAwesome,
                        contentDescription = null,
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(stringResource(R.string.rhythm_generate_chart))
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

            if (charts.isEmpty() && importError == null && !importing) {
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

            items(charts, key = { it.chartId }) { meta ->
                ChartCard(
                    meta = meta,
                    onPlay = {
                        navController.navigateSafely(Screen.RhythmGame.createRoute(songId, meta.chartId))
                    },
                    onDelete = { pendingDelete = meta },
                )
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
                            refreshCharts()
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

    if (showGenerateSheet) {
        RhythmGenerateSheet(
            songTitle = song?.title.orEmpty(),
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
                        val chartId = generated.meta.chartId
                        lastGenerated = null
                        navController.navigateSafely(
                            Screen.RhythmGame.createRoute(songId, chartId),
                        )
                    },
                ) {
                    Text(stringResource(R.string.rhythm_generated_play_now))
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
private fun ChartCard(
    meta: RhythmChartMeta,
    onPlay: () -> Unit,
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
                Icon(
                    imageVector = Icons.Outlined.MusicNote,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(10.dp))
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
                                String.format(java.util.Locale.US, "%.1f", meta.averageNps),
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
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
