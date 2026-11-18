/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Original work in 2026 for PiscesPlayer
 * (design/piscesplayer docs/white-noise-implementation-plan.md §3.2).
 */
package com.pisces312.piscesplayer.presentation.components

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.pisces312.piscesplayer.R
import com.pisces312.piscesplayer.data.noise.SoundscapePreset

/**
 * The horizontal scene chip row: recall by tap, delete user scenes by
 * long-press (built-ins are immune), "＋" saves the current mix as a new scene.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun PresetChipRow(
    presets: List<SoundscapePreset>,
    selectedPresetId: Long?,
    onRecall: (SoundscapePreset) -> Unit,
    onDelete: (SoundscapePreset) -> Unit,
    onAddNew: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .horizontalScroll(rememberScrollState())
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        presets.forEach { preset ->
            val label = preset.name
                ?: when (preset.builtInId) {
                    SoundscapePreset.BUILT_IN_BROWN_ONLY -> stringResource(R.string.soundscape_preset_brown_only)
                    SoundscapePreset.BUILT_IN_PINK_ONLY -> stringResource(R.string.soundscape_preset_pink_only)
                    SoundscapePreset.BUILT_IN_WHITE_MASK -> stringResource(R.string.soundscape_preset_white_mask)
                    else -> stringResource(R.string.soundscape_scene_label)
                }
            FilterChip(
                selected = selectedPresetId == preset.id,
                onClick = { onRecall(preset) },
                label = { Text(label) },
                modifier = Modifier.combinedClickable(
                    onClick = { onRecall(preset) },
                    onLongClick = { onDelete(preset) },
                ),
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.tertiaryContainer,
                    selectedLabelColor = MaterialTheme.colorScheme.onTertiaryContainer,
                ),
            )
        }
        FilterChip(
            selected = false,
            onClick = onAddNew,
            label = { Text(stringResource(R.string.soundscape_preset_save_short)) },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Rounded.Add,
                    contentDescription = null,
                    modifier = Modifier.padding(start = 4.dp),
                )
            },
        )
    }
}
