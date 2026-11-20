@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class
)

package com.pisces312.piscesplayer.presentation.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.pisces312.piscesplayer.R
import com.pisces312.piscesplayer.data.classical.ClassicalDisplay
import com.pisces312.piscesplayer.data.classical.ClassicalDisplayNames
import com.pisces312.piscesplayer.data.classical.ClassicalNameCatalog
import com.pisces312.piscesplayer.data.model.Album
import com.pisces312.piscesplayer.data.model.LibraryTabId
import com.pisces312.piscesplayer.data.model.StorageFilter
import com.pisces312.piscesplayer.presentation.navigation.Screen
import com.pisces312.piscesplayer.presentation.navigation.navigateSafely
import com.pisces312.piscesplayer.presentation.viewmodel.ClassicalLibraryViewModel
import com.pisces312.piscesplayer.presentation.viewmodel.PlayerViewModel

/**
 * Library "Classical" tab: the built-in classical MIDI collection, grouped by
 * composer (one virtual album per composer; tapping opens the album detail screen).
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun ClassicalTabContent(
    playerViewModel: PlayerViewModel,
    navController: NavController,
    bottomBarHeight: Dp,
    isRefreshing: Boolean,
    onRefresh: () -> Unit
) {
    val viewModel: ClassicalLibraryViewModel = hiltViewModel()
    val albums by viewModel.albums.collectAsStateWithLifecycle()
    val songs by viewModel.songs.collectAsStateWithLifecycle()
    val trackCount by viewModel.trackCount.collectAsStateWithLifecycle()
    val translationEnabled by viewModel.translationEnabled.collectAsStateWithLifecycle()
    val pullState = rememberPullToRefreshState()
    val queueName = stringResource(R.string.library_tab_classical)

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
        if (albums.isEmpty()) {
            LibraryExpressiveEmptyState(
                tabId = LibraryTabId.CLASSICAL,
                storageFilter = StorageFilter.ALL,
                bottomBarHeight = bottomBarHeight
            )
            return@PullToRefreshBox
        }

        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 160.dp),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = 8.dp,
                bottom = bottomBarHeight + 24.dp
            ),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                ClassicalTabHeader(
                    trackCount = trackCount,
                    composerCount = albums.size,
                    translationEnabled = translationEnabled,
                    onTranslationToggle = { viewModel.setTranslationEnabled(it) },
                    onPlayAll = {
                        songs.firstOrNull()?.let { first ->
                            playerViewModel.playSongs(songs, first, queueName = queueName)
                        }
                    }
                )
            }
            items(albums, key = { it.id }) { album ->
                ClassicalComposerCard(
                    album = album,
                    nameCatalog = viewModel.nameCatalog,
                    translationEnabled = translationEnabled,
                    onClick = {
                        navController.navigateSafely(Screen.AlbumDetail.createRoute(album.id))
                    }
                )
            }
        }
    }
}

@Composable
private fun ClassicalTabHeader(
    trackCount: Int,
    composerCount: Int,
    translationEnabled: Boolean,
    onTranslationToggle: (Boolean) -> Unit,
    onPlayAll: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.classical_tab_summary_format, trackCount, composerCount),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            FilledTonalButton(
                onClick = onPlayAll,
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                )
            ) {
                Icon(
                    Icons.Rounded.PlayArrow,
                    contentDescription = stringResource(R.string.classical_tab_play_all)
                )
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.classical_tab_play_all))
            }
        }
        // 「翻译」开关：译名为主/原文为主，副标题始终双显另一层（见 ClassicalDisplay）
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = stringResource(R.string.classical_tab_translate),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            Switch(
                checked = translationEnabled,
                onCheckedChange = onTranslationToggle
            )
        }
    }
}

private val composerPalette = listOf(
    0xFF5C6BC0 to 0xFF283593,
    0xFF26A69A to 0xFF00695C,
    0xFFEC407A to 0xFFAD1457,
    0xFFFFA726 to 0xFFEF6C00,
    0xFF42A5F5 to 0xFF1565C0,
    0xFF66BB6A to 0xFF2E7D32,
    0xFFAB47BC to 0xFF6A1B9A,
    0xFF8D6E63 to 0xFF4E342E
)

@Composable
private fun ClassicalComposerCard(
    album: Album,
    nameCatalog: ClassicalNameCatalog,
    translationEnabled: Boolean,
    onClick: () -> Unit
) {
    val fallback = album.artist.ifBlank { album.title }
    val names = nameCatalog.albumName(album.id)?.let {
        ClassicalDisplay.resolve(it, translate = translationEnabled)
    } ?: ClassicalDisplayNames(primary = fallback, subtitle = null)
    val composer = names.primary
    val palette = composerPalette[(fallback.hashCode() and Int.MAX_VALUE) % composerPalette.size]
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1.4f)
                    .background(
                        Brush.linearGradient(
                            colors = listOf(
                                androidx.compose.ui.graphics.Color(palette.first),
                                androidx.compose.ui.graphics.Color(palette.second)
                            )
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = composer.trim().take(1).uppercase(),
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Light,
                    color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.85f)
                )
            }
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                Text(
                    text = composer,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                names.subtitle?.let { sub ->
                    Text(
                        text = sub,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    text = stringResource(R.string.classical_card_track_count_format, album.songCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Start
                )
            }
        }
    }
}
