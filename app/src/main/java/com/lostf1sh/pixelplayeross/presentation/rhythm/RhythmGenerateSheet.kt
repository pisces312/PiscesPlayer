/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Original work in 2026 for PixelPlayerOSS
 * (docs/rhythm-chart-ladder-plan.md §9.2).
 */
package com.lostf1sh.pixelplayeross.presentation.rhythm

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.data.rhythm.RhythmAnalysisParams
import com.lostf1sh.pixelplayeross.presentation.components.rememberModalSheetState

private val LOUDNESS_CHOICES = listOf<Pair<Int?, Int>>(
    null to R.string.rhythm_generate_loudness_off,
    25 to R.string.rhythm_generate_loudness_medium,
    40 to R.string.rhythm_generate_loudness_strong,
)

private val DENSITY_CHOICES = listOf<Pair<Double?, Int>>(
    null to R.string.rhythm_generate_density_off,
    8.0 to R.string.rhythm_generate_density_8,
    5.0 to R.string.rhythm_generate_density_5,
)

/**
 * Parameter sheet for the master chart.
 *
 * Only analysis knobs live here: difficulty is not a generation parameter any more, it is a way
 * to thin out the master afterwards (see `RhythmDeriveSheet`). Defaults stay the measured-best
 * configuration — no loudness filter, no density cap; the two filters are an escape hatch for
 * noisy material, not an improvement (both measured worse than plain quantization).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RhythmGenerateSheet(
    songTitle: String,
    overwriting: Boolean,
    generating: Boolean,
    progress: Float,
    error: String?,
    onDismiss: () -> Unit,
    onStart: (RhythmAnalysisParams) -> Unit,
    onCancel: () -> Unit,
) {
    var loudness by remember { mutableStateOf<Int?>(null) }
    var density by remember { mutableStateOf<Double?>(null) }
    var advancedOpen by remember { mutableStateOf(false) }
    var bpmText by remember { mutableStateOf("") }
    var offsetMs by remember { mutableFloatStateOf(0f) }

    val bpmValue = bpmText.trim().takeIf { it.isNotEmpty() }?.toDoubleOrNull()
    val bpmInvalid = bpmText.isNotBlank() && (bpmValue == null || bpmValue !in 60.0..200.0)

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
                text = stringResource(R.string.rhythm_generate_master_title),
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                text = songTitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            if (generating) {
                GenerationProgress(progress = progress, onCancel = onCancel)
            } else {
                if (overwriting) {
                    Text(
                        text = stringResource(R.string.rhythm_master_overwrite_message),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                GenerationParams(
                    loudness = loudness,
                    onLoudnessChange = { loudness = it },
                    density = density,
                    onDensityChange = { density = it },
                    advancedOpen = advancedOpen,
                    onAdvancedToggle = { advancedOpen = !advancedOpen },
                    bpmText = bpmText,
                    onBpmTextChange = { bpmText = it },
                    bpmInvalid = bpmInvalid,
                    offsetMs = offsetMs,
                    onOffsetChange = { offsetMs = it },
                )

                error?.let { message ->
                    Text(
                        text = stringResource(R.string.rhythm_generate_failed, message),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                Spacer(Modifier.height(4.dp))
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
                        onClick = {
                            onStart(
                                RhythmAnalysisParams(
                                    minRmsPercentile = loudness,
                                    maxLocalNps = density,
                                    bpmOverride = bpmValue,
                                    extraOffsetMs = offsetMs.toInt(),
                                ),
                            )
                        },
                        enabled = !bpmInvalid,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(
                            stringResource(
                                if (overwriting) {
                                    R.string.rhythm_master_overwrite_confirm
                                } else {
                                    R.string.rhythm_generate_start
                                },
                            ),
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun GenerationProgress(progress: Float, onCancel: () -> Unit) {
    val animated by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 300),
        label = "rhythmGenerationProgress",
    )
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.rhythm_generate_running),
            style = MaterialTheme.typography.bodyMedium,
        )
        LinearWavyProgressIndicator(
            progress = { animated },
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.primaryContainer,
        )
        Text(
            text = "${(animated * 100).toInt()}%",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(onClick = onCancel) {
                Text(stringResource(R.string.rhythm_generate_cancel))
            }
        }
    }
}

@Composable
private fun GenerationParams(
    loudness: Int?,
    onLoudnessChange: (Int?) -> Unit,
    density: Double?,
    onDensityChange: (Double?) -> Unit,
    advancedOpen: Boolean,
    onAdvancedToggle: () -> Unit,
    bpmText: String,
    onBpmTextChange: (String) -> Unit,
    bpmInvalid: Boolean,
    offsetMs: Float,
    onOffsetChange: (Float) -> Unit,
) {
    SectionLabel(stringResource(R.string.rhythm_generate_loudness))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        LOUDNESS_CHOICES.forEach { (value, label) ->
            FilterChip(
                selected = loudness == value,
                onClick = { onLoudnessChange(value) },
                label = { Text(stringResource(label)) },
            )
        }
    }

    SectionLabel(stringResource(R.string.rhythm_generate_density))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        DENSITY_CHOICES.forEach { (value, label) ->
            FilterChip(
                selected = density == value,
                onClick = { onDensityChange(value) },
                label = { Text(stringResource(label)) },
            )
        }
    }

    TextButton(onClick = onAdvancedToggle) {
        Text(
            stringResource(
                if (advancedOpen) {
                    R.string.rhythm_generate_advanced_hide
                } else {
                    R.string.rhythm_generate_advanced
                },
            ),
        )
    }

    if (advancedOpen) {
        OutlinedTextField(
            value = bpmText,
            onValueChange = { input ->
                onBpmTextChange(input.filter { char -> char.isDigit() || char == '.' })
            },
            label = { Text(stringResource(R.string.rhythm_generate_bpm)) },
            placeholder = { Text(stringResource(R.string.rhythm_generate_bpm_placeholder)) },
            singleLine = true,
            isError = bpmInvalid,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
        if (bpmInvalid) {
            Text(
                text = stringResource(R.string.rhythm_generate_bpm_invalid),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.rhythm_generate_offset),
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = stringResource(R.string.rhythm_generate_offset_value, offsetMs.toInt()),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Slider(
            value = offsetMs,
            onValueChange = onOffsetChange,
            valueRange = -100f..100f,
            steps = 39,
            modifier = Modifier.fillMaxWidth(),
        )
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
