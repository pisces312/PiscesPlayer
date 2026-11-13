package com.pisces312.piscesplayer.presentation.rhythm

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pisces312.piscesplayer.R
import com.pisces312.piscesplayer.data.model.Song
import com.pisces312.piscesplayer.data.offline.CloudOfflineRepository
import com.pisces312.piscesplayer.data.rhythm.RhythmChartMeta
import com.pisces312.piscesplayer.presentation.components.MiniPlayerBottomSpacer
import com.pisces312.piscesplayer.presentation.components.MiniPlayerHeight
import com.pisces312.piscesplayer.presentation.components.SmartImage
import com.pisces312.piscesplayer.presentation.components.SmartImageListTargetSize
import com.pisces312.piscesplayer.presentation.viewmodel.PlayerViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RhythmSongPickerScreen(
    playerViewModel: PlayerViewModel,
    onBack: () -> Unit,
    onSongSelected: (String) -> Unit,
    pickerViewModel: RhythmSongPickerViewModel = hiltViewModel(),
) {
    val allSongs by playerViewModel.allSongsFlow.collectAsStateWithLifecycle()
    val playbackHistory by playerViewModel.playbackHistory.collectAsStateWithLifecycle()
    val stablePlayerState by playerViewModel.stablePlayerState.collectAsStateWithLifecycle()
    val chartCatalog by pickerViewModel.chartCatalog.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner, pickerViewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) pickerViewModel.refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    var query by rememberSaveable { mutableStateOf("") }
    var scope by rememberSaveable { mutableStateOf(RhythmSongScope.RECENT) }
    var difficulty by rememberSaveable { mutableStateOf(RhythmDifficultyFilter.ALL) }
    var chartFilter by rememberSaveable { mutableStateOf(RhythmChartFilter.ALL) }
    val filters = RhythmSongFilterState(scope, difficulty, chartFilter)
    val listState = rememberLazyListState()
    val locale = remember { java.util.Locale.getDefault() }

    val localSongs = remember(allSongs) {
        allSongs
            .filterNot { CloudOfflineRepository.isCloudSong(it) }
            .distinctBy(Song::id)
    }
    val songsById = remember(localSongs) { localSongs.associateBy(Song::id) }
    val recentSongIds = remember(playbackHistory) {
        recentRhythmSongIds(playbackHistory.map { it.songId to it.timestamp })
    }
    val candidates = remember(localSongs, chartCatalog) {
        localSongs.map { it.toCandidate(chartCatalog.orEmpty()[it.id].orEmpty()) }
    }
    val filteredCandidates = remember(candidates, recentSongIds, query, filters, locale) {
        filterRhythmSongCandidates(candidates, recentSongIds, query, filters, locale)
    }
    val filteredSongs = remember(filteredCandidates, songsById) {
        filteredCandidates.mapNotNull { candidate ->
            songsById[candidate.id]?.let { it to candidate }
        }
    }
    // The setup screen resolves songs from the Room library. Quick play and deleted songs can
    // remain in player state, but offering them here would lead to a permanently loading setup.
    val currentSong = stablePlayerState.currentSong?.let { songsById[it.id] }
    val recentLocalCount = remember(recentSongIds, songsById) {
        recentSongIds.count(songsById::containsKey)
    }

    LaunchedEffect(query, scope, difficulty, chartFilter) {
        listState.scrollToItem(0)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.rhythm_setup_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.auth_cd_back),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                placeholder = { Text(stringResource(R.string.rhythm_picker_search_hint)) },
                leadingIcon = {
                    Icon(imageVector = Icons.Rounded.Search, contentDescription = null)
                },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) {
                            Icon(
                                imageVector = Icons.Rounded.Close,
                                contentDescription = stringResource(R.string.rhythm_picker_clear_search),
                            )
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(18.dp),
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                RhythmFilterSelector(
                    selected = scope,
                    options = listOf(
                        RhythmSongScope.RECENT to stringResource(R.string.rhythm_picker_scope_recent),
                        RhythmSongScope.ALL to stringResource(R.string.rhythm_picker_scope_all),
                    ),
                    label = stringResource(
                        if (scope == RhythmSongScope.RECENT) {
                            R.string.rhythm_picker_scope_recent
                        } else {
                            R.string.rhythm_picker_scope_all
                        }
                    ),
                    emphasized = scope != RhythmSongScope.RECENT,
                    onSelected = { scope = it },
                    modifier = Modifier.weight(1f),
                )
                RhythmFilterSelector(
                    selected = difficulty,
                    options = difficultyOptions(),
                    label = stringResource(
                        R.string.rhythm_picker_difficulty_chip,
                        difficultyLabel(difficulty),
                    ),
                    emphasized = difficulty != RhythmDifficultyFilter.ALL,
                    onSelected = { selected ->
                        filters.withDifficulty(selected).let {
                            difficulty = it.difficulty
                            chartFilter = it.charts
                        }
                    },
                    modifier = Modifier.weight(1.25f),
                )
                RhythmFilterSelector(
                    selected = chartFilter,
                    options = chartOptions(),
                    label = stringResource(
                        R.string.rhythm_picker_charts_chip,
                        chartFilterLabel(chartFilter),
                    ),
                    emphasized = chartFilter != RhythmChartFilter.ALL,
                    onSelected = { selected ->
                        filters.withCharts(selected).let {
                            difficulty = it.difficulty
                            chartFilter = it.charts
                        }
                    },
                    modifier = Modifier.weight(1.15f),
                )
            }

            val loadedChartCatalog = chartCatalog
            if (currentSong != null && query.isBlank() && loadedChartCatalog != null) {
                // Unknown chart state is not the same as a song with no charts.
                val currentCandidate = currentSong.toCandidate(
                    loadedChartCatalog[currentSong.id].orEmpty(),
                )
                Text(
                    text = stringResource(R.string.rhythm_picker_current_song),
                    modifier = Modifier.padding(horizontal = 20.dp),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(6.dp))
                RhythmSongRow(
                    song = currentSong,
                    summary = rhythmChartSummary(currentCandidate, difficulty),
                    onClick = { onSongSelected(currentSong.id) },
                    modifier = Modifier.padding(horizontal = 16.dp),
                    emphasized = true,
                )
                Spacer(Modifier.height(12.dp))
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = pluralStringResource(
                        R.plurals.rhythm_picker_result_count,
                        filteredSongs.size,
                        stringResource(
                            if (scope == RhythmSongScope.RECENT) {
                                R.string.rhythm_picker_scope_recent
                            } else {
                                R.string.rhythm_picker_scope_all
                            }
                        ),
                        filteredSongs.size,
                    ),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!filters.isDefault) {
                    TextButton(
                        onClick = {
                            scope = RhythmSongScope.RECENT
                            difficulty = RhythmDifficultyFilter.ALL
                            chartFilter = RhythmChartFilter.ALL
                        },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                    ) {
                        Text(stringResource(R.string.rhythm_picker_reset))
                    }
                }
            }

            when {
                chartCatalog == null -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                }
                filteredSongs.isEmpty() -> {
                    RhythmPickerEmptyState(
                        localLibraryEmpty = localSongs.isEmpty(),
                        query = query,
                        recentEmpty = scope == RhythmSongScope.RECENT && recentLocalCount == 0,
                        filtersActive = !filters.isDefault,
                        showSearchAll = scope == RhythmSongScope.RECENT && query.isNotBlank(),
                        onClearSearch = { query = "" },
                        onSearchAll = { scope = RhythmSongScope.ALL },
                        onShowAll = { scope = RhythmSongScope.ALL },
                        onReset = {
                            scope = RhythmSongScope.RECENT
                            difficulty = RhythmDifficultyFilter.ALL
                            chartFilter = RhythmChartFilter.ALL
                        },
                    )
                }
                else -> {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            start = 16.dp,
                            end = 16.dp,
                            top = 8.dp,
                            // Scaffold.innerPadding already includes the system navigation inset.
                            bottom = MiniPlayerHeight + MiniPlayerBottomSpacer + 16.dp,
                        ),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(
                            items = filteredSongs,
                            key = { (song, _) -> song.id },
                            contentType = { "rhythm_song" },
                        ) { (song, candidate) ->
                            RhythmSongRow(
                                song = song,
                                summary = rhythmChartSummary(candidate, difficulty),
                                onClick = { onSongSelected(song.id) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun <T> RhythmFilterSelector(
    selected: T,
    options: List<Pair<T, String>>,
    label: String,
    emphasized: Boolean,
    onSelected: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        FilterChip(
            selected = emphasized,
            onClick = { expanded = true },
            label = {
                Text(
                    text = label,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            trailingIcon = {
                Icon(
                    imageVector = Icons.Rounded.ArrowDropDown,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
            },
            modifier = Modifier.fillMaxWidth(),
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            options.forEach { (value, optionLabel) ->
                DropdownMenuItem(
                    text = { Text(optionLabel) },
                    leadingIcon = {
                        if (value == selected) {
                            Icon(imageVector = Icons.Rounded.Check, contentDescription = null)
                        }
                    },
                    onClick = {
                        onSelected(value)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun RhythmSongRow(
    song: Song,
    summary: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    emphasized: Boolean = false,
) {
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (emphasized) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerLow
            },
        ),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SmartImage(
                model = song.albumArtUriString,
                contentDescription = null,
                modifier = Modifier.size(56.dp),
                shape = RoundedCornerShape(14.dp),
                targetSize = SmartImageListTargetSize,
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = song.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = song.displayArtist,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = summary,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun RhythmPickerEmptyState(
    localLibraryEmpty: Boolean,
    query: String,
    recentEmpty: Boolean,
    filtersActive: Boolean,
    showSearchAll: Boolean,
    onClearSearch: () -> Unit,
    onSearchAll: () -> Unit,
    onShowAll: () -> Unit,
    onReset: () -> Unit,
) {
    val title = when {
        localLibraryEmpty -> stringResource(R.string.rhythm_picker_empty_local)
        query.isNotBlank() -> stringResource(R.string.rhythm_picker_empty_search)
        recentEmpty -> stringResource(R.string.rhythm_picker_empty_recent)
        else -> stringResource(R.string.rhythm_picker_empty_filters)
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        when {
            localLibraryEmpty -> Unit
            query.isNotBlank() -> {
                if (showSearchAll) {
                    TextButton(onClick = onSearchAll) {
                        Text(stringResource(R.string.rhythm_picker_search_all))
                    }
                }
                TextButton(onClick = onClearSearch) {
                    Text(stringResource(R.string.rhythm_picker_clear_search))
                }
            }
            recentEmpty -> TextButton(onClick = onShowAll) {
                Text(stringResource(R.string.rhythm_picker_show_all))
            }
            filtersActive -> TextButton(onClick = onReset) {
                Text(stringResource(R.string.rhythm_picker_reset))
            }
        }
    }
}

@Composable
private fun rhythmChartSummary(
    candidate: RhythmSongCandidate,
    selectedDifficulty: RhythmDifficultyFilter,
): String = when {
    candidate.chartCount == 0 -> stringResource(R.string.rhythm_picker_no_charts)
    selectedDifficulty != RhythmDifficultyFilter.ALL -> stringResource(
        R.string.rhythm_picker_difficulty_chart_count,
        difficultyLabel(selectedDifficulty),
        candidate.countForDifficulty(selectedDifficulty),
    )
    else -> {
        val labels = candidate.presentDifficulties().map { difficultyLabel(it) }
        pluralStringResource(
            R.plurals.rhythm_picker_chart_summary,
            candidate.chartCount,
            candidate.chartCount,
            labels.joinToString(" / "),
        )
    }
}

@Composable
private fun difficultyOptions(): List<Pair<RhythmDifficultyFilter, String>> = listOf(
    RhythmDifficultyFilter.ALL to difficultyLabel(RhythmDifficultyFilter.ALL),
    RhythmDifficultyFilter.EASY to difficultyLabel(RhythmDifficultyFilter.EASY),
    RhythmDifficultyFilter.NORMAL to difficultyLabel(RhythmDifficultyFilter.NORMAL),
    RhythmDifficultyFilter.HARD to difficultyLabel(RhythmDifficultyFilter.HARD),
    RhythmDifficultyFilter.UNGRADED to difficultyLabel(RhythmDifficultyFilter.UNGRADED),
)

@Composable
private fun chartOptions(): List<Pair<RhythmChartFilter, String>> = listOf(
    RhythmChartFilter.ALL to chartFilterLabel(RhythmChartFilter.ALL),
    RhythmChartFilter.WITH_CHARTS to chartFilterLabel(RhythmChartFilter.WITH_CHARTS),
    RhythmChartFilter.WITHOUT_CHARTS to chartFilterLabel(RhythmChartFilter.WITHOUT_CHARTS),
)

@Composable
private fun difficultyLabel(difficulty: RhythmDifficultyFilter): String = stringResource(
    when (difficulty) {
        RhythmDifficultyFilter.ALL -> R.string.rhythm_picker_difficulty_all
        RhythmDifficultyFilter.EASY -> R.string.rhythm_generate_difficulty_easy
        RhythmDifficultyFilter.NORMAL -> R.string.rhythm_generate_difficulty_normal
        RhythmDifficultyFilter.HARD -> R.string.rhythm_generate_difficulty_hard
        RhythmDifficultyFilter.UNGRADED -> R.string.rhythm_picker_difficulty_ungraded
    },
)

@Composable
private fun chartFilterLabel(filter: RhythmChartFilter): String = stringResource(
    when (filter) {
        RhythmChartFilter.ALL -> R.string.rhythm_picker_charts_all
        RhythmChartFilter.WITH_CHARTS -> R.string.rhythm_picker_charts_present
        RhythmChartFilter.WITHOUT_CHARTS -> R.string.rhythm_picker_charts_missing
    },
)

private fun Song.toCandidate(charts: List<RhythmChartMeta>): RhythmSongCandidate =
    RhythmSongCandidate(
        id = id,
        title = title,
        artist = displayArtist,
        difficultyKeys = charts.map(RhythmChartMeta::difficultyKey),
    )
