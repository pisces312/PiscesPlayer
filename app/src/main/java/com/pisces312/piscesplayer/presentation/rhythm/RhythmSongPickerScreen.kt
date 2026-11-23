/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Original work in 2026 (rhythm game song picker).
 */
package com.pisces312.piscesplayer.presentation.rhythm

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
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
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
import com.pisces312.piscesplayer.data.model.SortOption
import com.pisces312.piscesplayer.data.offline.CloudOfflineRepository
import com.pisces312.piscesplayer.data.rhythm.RhythmChartMeta
import com.pisces312.piscesplayer.data.rhythm.RhythmLatencyStore
import com.pisces312.piscesplayer.presentation.components.LibrarySortBottomSheet
import com.pisces312.piscesplayer.presentation.components.SmartImage
import com.pisces312.piscesplayer.presentation.components.SmartImageListTargetSize
import com.pisces312.piscesplayer.presentation.viewmodel.PlayerViewModel
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.launch

/**
 * Rhythm game song picker.
 *
 * Two swipeable tabs modelled on the library tab pattern (2026-11-22): tab 1 lists songs
 * that already have charts (with a 全部/最近 scope filter and a difficulty filter), tab 2
 * lists songs without charts. Both tabs share the search field and the common song sort
 * options; the tab itself replaces the old "chart presence" dropdown filter.
 */
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
    val chartCatalog by pickerViewModel.chartCatalog.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current

    // The output route decides how much latency the game must compensate, so the Bluetooth
    // hint re-probes every time the screen comes back (a headset may have (dis)connected).
    var routeProbeTick by remember { mutableIntStateOf(0) }
    DisposableEffect(lifecycleOwner, pickerViewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                pickerViewModel.refresh()
                routeProbeTick += 1
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val bluetoothRoute = remember(routeProbeTick) {
        RhythmLatencyStore.deviceKey(context) == RhythmLatencyStore.DEVICE_BLUETOOTH
    }

    var query by rememberSaveable { mutableStateOf("") }
    var scope by rememberSaveable { mutableStateOf(RhythmSongScope.ALL) }
    var difficulty by rememberSaveable { mutableStateOf(RhythmDifficultyFilter.ALL) }
    // The common song sort, persisted by storage key across process death. The default keeps
    // the tab's natural order: title for 全部, recency for 最近.
    var sortKey by rememberSaveable { mutableStateOf(SortOption.SongDefaultOrder.storageKey) }
    val sortOption = remember(sortKey) {
        SortOption.SONGS.firstOrNull { it.storageKey == sortKey } ?: SortOption.SongDefaultOrder
    }
    var showSortSheet by remember { mutableStateOf(false) }

    val pagerState = rememberPagerState(pageCount = { TAB_COUNT })
    val pagerScope = rememberCoroutineScope()
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
    val tabCounts = remember(candidates) {
        listOf(
            candidates.count { it.chartCount > 0 },
            candidates.count { it.chartCount == 0 },
        )
    }
    val recentLocalCount = remember(recentSongIds, songsById) {
        recentSongIds.count(songsById::containsKey)
    }

    // The tab IS the chart-presence filter; scope/difficulty are shared by both tabs and only
    // surfaced in the with-charts one.
    val filtersWithCharts = RhythmSongFilterState(scope, difficulty, RhythmChartFilter.WITH_CHARTS)
    val filtersWithoutCharts = RhythmSongFilterState(scope, difficulty, RhythmChartFilter.WITHOUT_CHARTS)
    val filteredByTab = remember(candidates, recentSongIds, query, filtersWithCharts, filtersWithoutCharts, locale, songsById, sortOption) {
        listOf(filtersWithCharts, filtersWithoutCharts).map { filters ->
            val filtered = filterRhythmSongCandidates(candidates, recentSongIds, query, filters, locale)
                .mapNotNull { candidate -> songsById[candidate.id]?.let { it to candidate } }
            sortRhythmSongPairs(filtered, sortOption)
        }
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

            if (bluetoothRoute) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                    ),
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = stringResource(R.string.rhythm_bt_warning_title),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.rhythm_bt_warning_body),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                        )
                    }
                }
            }

            PrimaryTabRow(selectedTabIndex = pagerState.currentPage) {
                PickerTab(
                    title = stringResource(R.string.rhythm_picker_tab_with_charts),
                    count = tabCounts[0],
                    selected = pagerState.currentPage == 0,
                    onClick = { pagerScope.launch { pagerState.animateScrollToPage(0) } },
                )
                PickerTab(
                    title = stringResource(R.string.rhythm_picker_tab_without_charts),
                    count = tabCounts[1],
                    selected = pagerState.currentPage == 1,
                    onClick = { pagerScope.launch { pagerState.animateScrollToPage(1) } },
                )
            }

            HorizontalPager(
                state = pagerState,
                modifier = Modifier.weight(1f),
            ) { page ->
                val listState = rememberLazyListState()
                val filters = if (page == 0) filtersWithCharts else filtersWithoutCharts
                val filteredSongs = filteredByTab[page]

                LaunchedEffect(query, scope, difficulty, sortKey) {
                    listState.scrollToItem(0)
                }

                Column(modifier = Modifier.fillMaxSize()) {
                    // Tab 1 owns the 全部/最近 scope and the difficulty filter; tab 2 needs
                    // neither (it is a flat "no charts yet" view). Both keep the sort entry.
                    FlowRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (page == 0) {
                            FilterChip(
                                selected = scope == RhythmSongScope.ALL,
                                onClick = { scope = RhythmSongScope.ALL },
                                label = { Text(stringResource(R.string.rhythm_picker_scope_all)) },
                            )
                            FilterChip(
                                selected = scope == RhythmSongScope.RECENT,
                                onClick = { scope = RhythmSongScope.RECENT },
                                label = { Text(stringResource(R.string.rhythm_picker_scope_recent)) },
                            )
                            RhythmFilterSelector(
                                selected = difficulty,
                                options = difficultyOptions(),
                                label = stringResource(
                                    R.string.rhythm_picker_difficulty_chip,
                                    difficultyLabel(difficulty),
                                ),
                                emphasized = difficulty != RhythmDifficultyFilter.ALL,
                                onSelected = { difficulty = it },
                            )
                        }
                        FilterChip(
                            selected = sortOption != SortOption.SongDefaultOrder,
                            onClick = { showSortSheet = true },
                            label = {
                                Text(
                                    text = if (sortOption == SortOption.SongDefaultOrder) {
                                        stringResource(R.string.rhythm_picker_sort_label)
                                    } else {
                                        sortOption.displayName
                                    },
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
                        )
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
                        if (scope != RhythmSongScope.ALL || difficulty != RhythmDifficultyFilter.ALL) {
                            TextButton(
                                onClick = {
                                    scope = RhythmSongScope.ALL
                                    difficulty = RhythmDifficultyFilter.ALL
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
                                filtersActive = scope != RhythmSongScope.ALL ||
                                    difficulty != RhythmDifficultyFilter.ALL,
                                showSearchAll = scope == RhythmSongScope.RECENT && query.isNotBlank(),
                                onClearSearch = { query = "" },
                                onSearchAll = { scope = RhythmSongScope.ALL },
                                onShowAll = { scope = RhythmSongScope.ALL },
                                onReset = {
                                    scope = RhythmSongScope.ALL
                                    difficulty = RhythmDifficultyFilter.ALL
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
                                    // Scaffold.innerPadding already includes the system
                                    // navigation inset; the mini player is hidden on this
                                    // route (2026-11-22), so no player reservation either.
                                    bottom = 16.dp,
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
    }

    if (showSortSheet) {
        LibrarySortBottomSheet(
            title = stringResource(R.string.presentation_batch_d_sort_by),
            options = SortOption.SONGS.toImmutableList(),
            selectedOption = sortOption,
            onDismiss = { showSortSheet = false },
            onOptionSelected = { option ->
                sortKey = option.storageKey
                showSortSheet = false
            },
            onDirectionToggle = { option -> sortKey = option.storageKey },
        )
    }
}

private const val TAB_COUNT = 2

@Composable
private fun PickerTab(
    title: String,
    count: Int,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Tab(
        selected = selected,
        onClick = onClick,
        text = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                )
                Spacer(Modifier.width(5.dp))
                Text(
                    text = count.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    )
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
) {
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
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
private fun difficultyLabel(difficulty: RhythmDifficultyFilter): String = stringResource(
    when (difficulty) {
        RhythmDifficultyFilter.ALL -> R.string.rhythm_picker_difficulty_all
        RhythmDifficultyFilter.EASY -> R.string.rhythm_generate_difficulty_easy
        RhythmDifficultyFilter.NORMAL -> R.string.rhythm_generate_difficulty_normal
        RhythmDifficultyFilter.HARD -> R.string.rhythm_generate_difficulty_hard
        RhythmDifficultyFilter.UNGRADED -> R.string.rhythm_picker_difficulty_ungraded
    },
)

private fun Song.toCandidate(charts: List<RhythmChartMeta>): RhythmSongCandidate =
    RhythmSongCandidate(
        id = id,
        title = title,
        artist = displayArtist,
        difficultyKeys = charts.map(RhythmChartMeta::difficultyKey),
    )
