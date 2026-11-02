/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Original work in 2026 for PixelPlayerOSS
 * (docs/rhythm-chart-ladder-plan.md §9.3).
 */
package com.lostf1sh.pixelplayeross.presentation.rhythm

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.data.rhythm.DspDifficulty
import com.lostf1sh.pixelplayeross.data.rhythm.LaneStrategy
import com.lostf1sh.pixelplayeross.data.rhythm.RhythmChartDeriver

/** Two notes per cell by default: audible variety without turning every beat into a chord. */
private const val DEFAULT_CHORD = 2

/**
 * Derive difficulties from the stored master chart.
 *
 * Everything here is instant because deriving is a pure function over notes: the note counts are
 * computed live, before the user commits, so "about N notes" is not a guess.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun RhythmDeriveSheet(
    masterNoteCount: Int,
    masterBpm: String,
    deriving: Boolean,
    onComputeEstimates: suspend (LaneStrategy, Int) -> Map<DspDifficulty, Int>,
    onDismiss: () -> Unit,
    onDerive: (List<DspDifficulty>, LaneStrategy, Int) -> Unit,
) {
    var selected by remember { mutableStateOf(DspDifficulty.entries.toSet()) }
    var strategy by remember { mutableStateOf(LaneStrategy.BAND_ORDER) }
    var maxChord by remember { mutableIntStateOf(DEFAULT_CHORD) }
    var estimates by remember { mutableStateOf<Map<DspDifficulty, Int>?>(null) }

    LaunchedEffect(strategy, maxChord) {
        estimates = null
        estimates = onComputeEstimates(strategy, maxChord)
    }

    ModalBottomSheet(onDismissRequest = { if (!deriving) onDismiss() }) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.rhythm_derive_title),
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                text = stringResource(
                    R.string.rhythm_derive_master_line,
                    masterNoteCount,
                    masterBpm,
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Text(
                text = stringResource(R.string.rhythm_generate_difficulty),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            DspDifficulty.entries.forEach { difficulty ->
                DifficultyRow(
                    difficulty = difficulty,
                    checked = difficulty in selected,
                    estimate = estimates?.get(difficulty),
                    onToggle = {
                        selected = if (difficulty in selected) {
                            selected - difficulty
                        } else {
                            selected + difficulty
                        }
                    },
                )
            }
            if (selected.isEmpty()) {
                Text(
                    text = stringResource(R.string.rhythm_derive_none_selected),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Text(
                text = stringResource(R.string.rhythm_derive_lane),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = strategy == LaneStrategy.BAND_ORDER,
                    onClick = { strategy = LaneStrategy.BAND_ORDER },
                    label = { Text(stringResource(R.string.rhythm_derive_lane_band_order)) },
                )
                FilterChip(
                    selected = strategy == LaneStrategy.LOW_OUTSIDE,
                    onClick = { strategy = LaneStrategy.LOW_OUTSIDE },
                    label = { Text(stringResource(R.string.rhythm_derive_lane_low_outside)) },
                )
            }
            Text(
                text = stringResource(R.string.rhythm_derive_lane_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Text(
                text = stringResource(R.string.rhythm_derive_chord),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (size in 1..RhythmChartDeriver.MAX_CHORD_NOTES) {
                    FilterChip(
                        selected = maxChord == size,
                        onClick = { maxChord = size },
                        label = { Text(stringResource(chordLabel(size))) },
                    )
                }
            }
            Text(
                text = stringResource(R.string.rhythm_derive_chord_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // Deriving has no per-note progress to report, so the bar is indeterminate: it says
            // "working" rather than inventing a percentage.
            if (deriving) {
                LinearWavyProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.primaryContainer,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.rhythm_generate_cancel))
                }
                Button(
                    onClick = { onDerive(selected.sorted(), strategy, maxChord) },
                    enabled = selected.isNotEmpty() && !deriving,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.rhythm_derive_start))
                }
            }
        }
    }
}

@Composable
private fun DifficultyRow(
    difficulty: DspDifficulty,
    checked: Boolean,
    estimate: Int?,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = { onToggle() })
        Spacer(Modifier.width(4.dp))
        Text(
            text = difficulty.localizedName(),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = estimate?.let { stringResource(R.string.rhythm_derive_estimate, it) }.orEmpty(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun DspDifficulty.localizedName(): String = stringResource(
    when (this) {
        DspDifficulty.EASY -> R.string.rhythm_generate_difficulty_easy
        DspDifficulty.NORMAL -> R.string.rhythm_generate_difficulty_normal
        DspDifficulty.HARD -> R.string.rhythm_generate_difficulty_hard
    },
)

private fun chordLabel(notesPerCell: Int): Int = when (notesPerCell) {
    1 -> R.string.rhythm_derive_chord_single
    2 -> R.string.rhythm_derive_chord_double
    else -> R.string.rhythm_derive_chord_triple
}
