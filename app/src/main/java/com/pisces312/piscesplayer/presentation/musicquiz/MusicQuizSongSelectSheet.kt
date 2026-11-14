package com.pisces312.piscesplayer.presentation.musicquiz

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAddCheck
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pisces312.piscesplayer.R
import com.pisces312.piscesplayer.data.musicquiz.MusicQuizTrack
import com.pisces312.piscesplayer.presentation.components.SmartImage
import com.pisces312.piscesplayer.presentation.components.SmartImageListTargetSize
import com.pisces312.piscesplayer.presentation.components.rememberModalSheetState

/**
 * Step 1 of "add to playlist": pick which of the finished run's songs to include.
 * All songs start selected; step 2 reuses [com.pisces312.piscesplayer.presentation.components.PlaylistBottomSheet].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MusicQuizSongSelectSheet(
    tracks: List<MusicQuizTrack>,
    onConfirm: (List<MusicQuizTrack>) -> Unit,
    onDismiss: () -> Unit,
) {
    var selectedIds by remember { mutableStateOf(tracks.map { it.id }.toSet()) }
    val sheetState = rememberModalSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { true },
    )

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        contentWindowInsets = { BottomSheetDefaults.modalWindowInsets },
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.music_quiz_playlist_sheet_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = stringResource(
                            R.string.music_quiz_playlist_selected_count,
                            selectedIds.size,
                            tracks.size,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = { selectedIds = tracks.map { it.id }.toSet() }) {
                    Text(stringResource(R.string.music_quiz_playlist_select_all))
                }
                TextButton(onClick = { selectedIds = emptySet() }) {
                    Text(stringResource(R.string.music_quiz_playlist_clear))
                }
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp),
            ) {
                items(tracks, key = { it.id }) { track ->
                    val selected = track.id in selectedIds
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                selectedIds = if (selected) {
                                    selectedIds - track.id
                                } else {
                                    selectedIds + track.id
                                }
                            }
                            .padding(horizontal = 20.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = selected,
                            onCheckedChange = {
                                selectedIds = if (selected) {
                                    selectedIds - track.id
                                } else {
                                    selectedIds + track.id
                                }
                            },
                        )
                        SmartImage(
                            model = track.albumArtUriString,
                            contentDescription = null,
                            modifier = Modifier.size(40.dp),
                            shape = RoundedCornerShape(8.dp),
                            targetSize = SmartImageListTargetSize,
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = track.title,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text = track.artist,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }

            Button(
                onClick = { onConfirm(tracks.filter { it.id in selectedIds }) },
                enabled = selectedIds.isNotEmpty(),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
            ) {
                Icon(Icons.AutoMirrored.Rounded.PlaylistAddCheck, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.music_quiz_playlist_next))
            }
        }
    }
}
