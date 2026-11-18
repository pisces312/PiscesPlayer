/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Original work in 2026 for PiscesPlayer
 * (design/piscesplayer docs/white-noise-implementation-plan.md §3.3).
 */
package com.pisces312.piscesplayer.presentation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.pisces312.piscesplayer.R
import com.pisces312.piscesplayer.data.noise.SoundscapeSound

/**
 * The "add sound" library sheet. The same sound cannot join the mix twice
 * (already-added sounds are check-marked and disabled); the sheet stays open
 * for consecutive adds and closes through the "Done" button.
 *
 * Nature sounds are listed but locked: Phase B ships the asset pipeline.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SoundscapeLibrarySheet(
    activeSoundIds: Set<String>,
    onAddSound: (SoundscapeSound) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.soundscape_add_sound),
                style = MaterialTheme.typography.titleMedium,
            )

            Text(
                text = stringResource(R.string.soundscape_library_noise_section),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SoundscapeSound.entries.forEach { sound ->
                    val inMix = sound.id in activeSoundIds
                    SuggestionChip(
                        onClick = { if (!inMix) onAddSound(sound) },
                        enabled = !inMix,
                        label = { Text(stringResource(sound.nameRes)) },
                        icon = if (inMix) {
                            {
                                Icon(
                                    imageVector = Icons.Rounded.Check,
                                    contentDescription = null,
                                )
                            }
                        } else {
                            null
                        },
                        colors = SuggestionChipDefaults.suggestionChipColors(
                            disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        ),
                    )
                }
            }

            Text(
                text = stringResource(R.string.soundscape_library_nature_section),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                NaturePlaceholder(stringResource(R.string.soundscape_nature_rain))
                NaturePlaceholder(stringResource(R.string.soundscape_nature_wind))
                NaturePlaceholder(stringResource(R.string.soundscape_nature_thunder))
                NaturePlaceholder(stringResource(R.string.soundscape_nature_fire))
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                Button(onClick = onDismiss) {
                    Text(stringResource(R.string.soundscape_library_done))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NaturePlaceholder(label: String) {
    SuggestionChip(
        onClick = {},
        enabled = false,
        label = { Text(label) },
        icon = {
            Icon(
                imageVector = Icons.Rounded.Lock,
                contentDescription = null,
            )
        },
    )
}
