package com.pisces312.piscesplayer.presentation.musiclearning

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Hearing
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pisces312.piscesplayer.R
import com.pisces312.piscesplayer.presentation.components.MiniPlayerHeight

/**
 * 「音乐学习」root tab: the classical entry plus future learning tools (ear training,
 * metronome, …). Tool cards are placeholders — tapping one shows a「就要来了」toast
 * (see plan §A).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MusicLearningScreen(
    paddingValues: PaddingValues,
    onOpenClassical: () -> Unit,
    onOpenMidiLibrary: () -> Unit,
    onComingSoon: (String) -> Unit
) {
    val comingSoon = stringResource(R.string.music_learning_coming_soon)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.music_learning_title)) },
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(
                bottom = paddingValues.calculateBottomPadding() + MiniPlayerHeight + 24.dp
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                MusicLearningCard(
                    title = stringResource(R.string.library_tab_classical),
                    subtitle = stringResource(R.string.music_learning_classical_subtitle),
                    icon = Icons.Rounded.LibraryMusic,
                    onClick = onOpenClassical,
                )
            }
            item {
                MusicLearningCard(
                    title = stringResource(R.string.midi_library_title),
                    subtitle = stringResource(R.string.music_learning_midi_subtitle),
                    icon = Icons.Rounded.MusicNote,
                    onClick = onOpenMidiLibrary,
                )
            }
            item {
                MusicLearningCard(
                    title = stringResource(R.string.music_learning_ear_training_title),
                    subtitle = stringResource(R.string.music_learning_ear_training_subtitle),
                    icon = Icons.Rounded.Hearing,
                    onClick = { onComingSoon(comingSoon) },
                    placeholder = true,
                )
            }
            item {
                MusicLearningCard(
                    title = stringResource(R.string.music_learning_metronome_title),
                    subtitle = stringResource(R.string.music_learning_metronome_subtitle),
                    icon = Icons.Rounded.Timer,
                    onClick = { onComingSoon(comingSoon) },
                    placeholder = true,
                )
            }
        }
    }
}

@Composable
private fun MusicLearningCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    onClick: () -> Unit,
    placeholder: Boolean = false
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Row(
            modifier = Modifier.padding(20.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = if (placeholder) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.primary
                },
            )
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (placeholder) {
                        PlannedChip()
                    }
                }
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
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
}

@Composable
private fun PlannedChip() {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Text(
            text = stringResource(R.string.music_learning_planned_chip),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}
