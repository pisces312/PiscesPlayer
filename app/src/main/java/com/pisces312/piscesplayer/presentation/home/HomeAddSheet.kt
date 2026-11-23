package com.pisces312.piscesplayer.presentation.home

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pisces312.piscesplayer.R
import com.pisces312.piscesplayer.data.home.HomeCardType
import com.pisces312.piscesplayer.data.model.Playlist
import com.pisces312.piscesplayer.presentation.components.MiniPlayerHeight

/**
 * 「添加卡片」bottom sheet (plan §5.3): grouped feature-card catalog on top, the
 * user's playlists below. Feature cards disable once added (no duplicates,
 * plan §11 decision 1); playlists de-dupe by id so an already-pinned playlist
 * shows「已添加」too.
 */
@Composable
fun HomeAddSheet(
    paddingValues: PaddingValues,
    isTypePresent: (HomeCardType) -> Boolean,
    isPlaylistPresent: (String) -> Boolean,
    playlists: List<Playlist>,
    onAddType: (HomeCardType) -> Unit,
    onAddPlaylist: (Playlist) -> Unit,
    onChoosePlaylist: () -> Unit,
    onEditLayout: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.home_grid_add_card),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
        )
        Text(
            text = stringResource(R.string.home_grid_add_card_subtitle),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp),
        )
        Spacer(Modifier.height(10.dp))

        // Edit-mode entry (v1.2 decision 6): discoverable from the add panel even when the
        // user has not learned the long-press gesture yet.
        Surface(
            onClick = onEditLayout,
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.secondaryContainer,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.home_grid_edit_layout),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = stringResource(R.string.home_grid_edit_layout_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f),
                )
            }
        }
        Spacer(Modifier.height(4.dp))

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f, fill = false),
            contentPadding = PaddingValues(
                start = 14.dp, end = 14.dp,
                bottom = paddingValues.calculateBottomPadding() + MiniPlayerHeight + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            HomeCardCatalog.groups.forEach { group ->
                item(key = "group_${group.nameRes}") {
                    Text(
                        text = stringResource(group.nameRes),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(start = 6.dp, top = 12.dp, bottom = 4.dp),
                    )
                }
                items(group.members, key = { "add_${it.type}" }) { spec ->
                    val added = isTypePresent(spec.type)
                    HomeAddRow(
                        title = stringResource(spec.nameRes),
                        subtitle = spec.subtitleRes?.let { stringResource(it) },
                        sizeLabel = stringResource(
                            R.string.home_grid_size_label, spec.defaultSize.spanX, spec.defaultSize.spanY
                        ),
                        added = added,
                        onClick = { if (!added) onAddType(spec.type) },
                        icon = { iconModifier ->
                            spec.icon.Render(
                                modifier = iconModifier,
                                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                        },
                    )
                }
            }

            item(key = "group_playlists") {
                Text(
                    text = stringResource(R.string.home_grid_group_playlists),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 6.dp, top = 14.dp, bottom = 4.dp),
                )
            }
            items(playlists, key = { "add_pl_${it.id}" }) { playlist ->
                val added = isPlaylistPresent(playlist.id)
                HomeAddRow(
                    title = playlist.name,
                    subtitle = stringResource(
                        R.string.home_grid_playlist_tracks_format, playlist.songIds.size
                    ),
                    sizeLabel = stringResource(
                        R.string.home_grid_size_label,
                        HomeCardCatalog.PLAYLIST.defaultSize.spanX,
                        HomeCardCatalog.PLAYLIST.defaultSize.spanY,
                    ),
                    added = added,
                    onClick = { if (!added) onAddPlaylist(playlist) },
                    icon = { iconModifier ->
                        HomeCardCatalog.PLAYLIST.icon.Render(
                            modifier = iconModifier,
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    },
                )
            }
            item(key = "choose_playlist") {
                HomeAddRow(
                    title = stringResource(R.string.home_grid_choose_playlist),
                    subtitle = stringResource(R.string.home_grid_choose_playlist_subtitle),
                    sizeLabel = null,
                    added = false,
                    onClick = onChoosePlaylist,
                    icon = { iconModifier ->
                        Box(
                            modifier = iconModifier,
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("＋", style = MaterialTheme.typography.titleLarge)
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun HomeAddRow(
    title: String,
    subtitle: String?,
    sizeLabel: String?,
    added: Boolean,
    onClick: () -> Unit,
    icon: @Composable (Modifier) -> Unit,
) {
    val contentAlpha = if (added) 0.45f else 1f
    Surface(
        onClick = onClick,
        enabled = !added,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = if (added) 0.6f else 1f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.secondaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                icon(Modifier.size(20.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = contentAlpha),
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = contentAlpha),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (sizeLabel != null) {
                Spacer(Modifier.width(8.dp))
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.secondaryContainer,
                ) {
                    Text(
                        text = sizeLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            if (added) {
                Text(
                    text = stringResource(R.string.home_grid_added),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Button(onClick = onClick) {
                    Text(stringResource(R.string.home_grid_add))
                }
            }
        }
    }
}
