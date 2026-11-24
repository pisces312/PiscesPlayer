/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Original work in 2026 (generation parameter sheet for MIDI chart import).
 */
package com.pisces312.piscesplayer.presentation.rhythm

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.pisces312.piscesplayer.R
import com.pisces312.piscesplayer.data.rhythm.MidiChartOptions
import com.pisces312.piscesplayer.data.rhythm.MidiDifficulty
import com.pisces312.piscesplayer.data.rhythm.MidiLaneStrategy
import com.pisces312.piscesplayer.data.rhythm.MidiRepeatMode
import com.pisces312.piscesplayer.presentation.components.rememberModalSheetState
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The default difficulty rungs with localized labels (shared with quick generate). Two rungs
 * only (2026-11-24): auto-generated MIDI charts cannot guarantee a middle rung's playability,
 * so the ladder stays at Easy/Hard like the DSP derive ladder.
 */
internal fun defaultMidiDifficulties(context: android.content.Context): List<MidiDifficulty> = listOf(
    MidiDifficulty(
        label = context.getString(R.string.rhythm_midi_diff_easy),
        gridDiv = 1,
        maxHitsPerSecond = 1.0,
        maxChord = 1,
    ),
    MidiDifficulty(
        label = context.getString(R.string.rhythm_midi_diff_hard),
        gridDiv = 4,
        maxHitsPerSecond = 4.0,
        maxChord = 2,
    ),
)

@Composable
internal fun defaultMidiDifficulties(): List<MidiDifficulty> =
    defaultMidiDifficulties(androidx.compose.ui.platform.LocalContext.current)

/** One selectable difficulty rung with its editable generation parameters. */
private data class DifficultyDraft(val difficulty: MidiDifficulty, val selected: Boolean)

/**
 * Parameter sheet for MIDI chart generation.
 *
 * MIDI generation is a cheap deterministic function of the file, so there is no preview to
 * compute: pick the difficulty rungs (each becomes one chart per playable track), tune their
 * density caps and chord sizes, choose how notes map onto the four lanes (melody follow by
 * default), how repeated hits alternate, and the rhythm-breathing knobs (weak-note filter,
 * phrase breathing, density gradient, jump penalty), and generate.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun RhythmMidiGenerateSheet(
    generating: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onGenerate: (MidiChartOptions) -> Unit,
) {
    // Hoisted out of remember {}: the default rungs read their labels from stringResource.
    val defaults = defaultMidiDifficulties()
    var drafts by remember {
        mutableStateOf(defaults.map { DifficultyDraft(it, selected = true) })
    }
    var laneStrategy by remember { mutableStateOf(MidiLaneStrategy.MELODIC) }
    var repeatMode by remember { mutableStateOf(MidiRepeatMode.TWO_LANE) }
    var weakNoteVelocity by remember { mutableStateOf(16f) }
    var phraseBreathing by remember { mutableStateOf(true) }
    var densityGradient by remember { mutableStateOf(true) }
    var jumpPenalty by remember { mutableStateOf(true) }

    // Open at full height: at partial height the generate button sits on the visible edge
    // and the settings look cut off.
    val sheetState = rememberModalSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = { if (!generating) onDismiss() },
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.rhythm_midi_options_title),
                style = MaterialTheme.typography.titleLarge,
            )

            SectionLabel(stringResource(R.string.rhythm_midi_options_difficulties))
            drafts.forEachIndexed { index, draft ->
                DifficultyEditor(
                    draft = draft,
                    enabled = !generating,
                    onToggle = {
                        drafts = drafts.mapIndexed { i, d ->
                            if (i == index) d.copy(selected = !d.selected) else d
                        }
                    },
                    onNpsChange = { nps ->
                        drafts = drafts.mapIndexed { i, d ->
                            if (i == index) d.copy(difficulty = d.difficulty.copy(maxHitsPerSecond = nps)) else d
                        }
                    },
                    onMaxChordChange = { maxChord ->
                        drafts = drafts.mapIndexed { i, d ->
                            if (i == index) d.copy(difficulty = d.difficulty.copy(maxChord = maxChord)) else d
                        }
                    },
                )
            }
            if (drafts.none { it.selected }) {
                Text(
                    text = stringResource(R.string.rhythm_midi_options_none_selected),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            SectionLabel(stringResource(R.string.rhythm_midi_options_lane))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = laneStrategy == MidiLaneStrategy.MELODIC,
                    enabled = !generating,
                    onClick = { laneStrategy = MidiLaneStrategy.MELODIC },
                    label = { Text(stringResource(R.string.rhythm_midi_options_lane_melodic)) },
                )
                FilterChip(
                    selected = laneStrategy == MidiLaneStrategy.PITCH_RANGE,
                    enabled = !generating,
                    onClick = { laneStrategy = MidiLaneStrategy.PITCH_RANGE },
                    label = { Text(stringResource(R.string.rhythm_midi_options_lane_pitch)) },
                )
                FilterChip(
                    selected = laneStrategy == MidiLaneStrategy.ROUND_ROBIN,
                    enabled = !generating,
                    onClick = { laneStrategy = MidiLaneStrategy.ROUND_ROBIN },
                    label = { Text(stringResource(R.string.rhythm_midi_options_lane_round_robin)) },
                )
            }

            SectionLabel(stringResource(R.string.rhythm_midi_options_repeat))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = repeatMode == MidiRepeatMode.TWO_LANE,
                    enabled = !generating,
                    onClick = { repeatMode = MidiRepeatMode.TWO_LANE },
                    label = { Text(stringResource(R.string.rhythm_midi_options_repeat_two)) },
                )
                FilterChip(
                    selected = repeatMode == MidiRepeatMode.THREE_LANE,
                    enabled = !generating,
                    onClick = { repeatMode = MidiRepeatMode.THREE_LANE },
                    label = { Text(stringResource(R.string.rhythm_midi_options_repeat_three)) },
                )
            }

            SliderRow(
                label = stringResource(R.string.rhythm_midi_options_weak_filter),
                valueText = weakNoteVelocity.roundToInt().toString(),
                value = weakNoteVelocity,
                range = 0f..40f,
                steps = 39,
                enabled = !generating,
                // The stepped slider only delivers whole velocities.
                onValueChange = { weakNoteVelocity = it.roundToInt().toFloat() },
            )
            SwitchRow(
                label = stringResource(R.string.rhythm_midi_options_phrase_breathing),
                checked = phraseBreathing,
                enabled = !generating,
                onCheckedChange = { phraseBreathing = it },
            )
            SwitchRow(
                label = stringResource(R.string.rhythm_midi_options_density_gradient),
                checked = densityGradient,
                enabled = !generating,
                onCheckedChange = { densityGradient = it },
            )
            SwitchRow(
                label = stringResource(R.string.rhythm_midi_options_jump_penalty),
                checked = jumpPenalty,
                enabled = !generating,
                onCheckedChange = { jumpPenalty = it },
            )

            error?.let { message ->
                Text(
                    text = stringResource(R.string.rhythm_generate_failed, message),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(
                    onClick = onDismiss,
                    enabled = !generating,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.rhythm_generate_cancel))
                }
                Button(
                    onClick = {
                        onGenerate(
                            MidiChartOptions(
                                difficulties = drafts.filter { it.selected }.map { it.difficulty },
                                laneStrategy = laneStrategy,
                                repeatMode = repeatMode,
                                weakNoteVelocity = weakNoteVelocity.roundToInt(),
                                phraseBreathing = phraseBreathing,
                                densityGradient = densityGradient,
                                jumpPenalty = jumpPenalty,
                            ),
                        )
                    },
                    enabled = !generating && drafts.any { it.selected },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.rhythm_midi_options_generate))
                }
            }
        }
    }
}

@Composable
private fun DifficultyEditor(
    draft: DifficultyDraft,
    enabled: Boolean,
    onToggle: () -> Unit,
    onNpsChange: (Double) -> Unit,
    onMaxChordChange: (Int) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = draft.selected,
                onCheckedChange = { onToggle() },
                enabled = enabled,
            )
            Spacer(Modifier.width(4.dp))
            Text(
                text = draft.difficulty.label,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = stringResource(
                    R.string.rhythm_midi_options_nps,
                    String.format(Locale.US, "%.1f", draft.difficulty.maxHitsPerSecond),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (draft.selected) {
            SliderRow(
                label = stringResource(R.string.rhythm_midi_options_hits_per_sec),
                valueText = String.format(Locale.US, "%.1f", draft.difficulty.maxHitsPerSecond),
                value = draft.difficulty.maxHitsPerSecond.toFloat(),
                range = 0.5f..8.0f,
                steps = 14,
                enabled = enabled,
                // The stepped slider already delivers exact 0.5 multiples.
                onValueChange = { onNpsChange(it.toDouble()) },
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.rhythm_midi_options_max_chord),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                for (size in 1..2) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(
                            selected = draft.difficulty.maxChord == size,
                            onClick = { onMaxChordChange(size) },
                            enabled = enabled,
                        )
                        Text(
                            text = size.toString(),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun SwitchRow(
    label: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
        )
    }
}

@Composable
private fun SliderRow(
    label: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    enabled: Boolean,
    onValueChange: (Float) -> Unit,
) {
    Column {
        Row {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = valueText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = range,
            steps = steps,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
