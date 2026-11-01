/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Original work in 2026 for PixelPlayerOSS
 * (docs/rhythm-chart-ladder-plan.md §9.4).
 */
package com.lostf1sh.pixelplayeross.presentation.rhythm

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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.data.rhythm.RhythmChartSettings

/**
 * Per-chart playback settings. Saving on drag-release (not on every frame) keeps the sidecar
 * write off the gesture path; the values only affect the next run, never the chart itself.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RhythmChartSettingsSheet(
    chartName: String,
    settings: RhythmChartSettings,
    onChange: (RhythmChartSettings) -> Unit,
    onDismiss: () -> Unit,
) {
    var delayMs by remember(chartName) { mutableIntStateOf(settings.delayMs) }
    var approachMs by remember(chartName) { mutableIntStateOf(settings.approachMs) }

    fun commit(newDelay: Int = delayMs, newApproach: Int = approachMs) {
        delayMs = newDelay
        approachMs = newApproach
        onChange(settings.copy(delayMs = newDelay, approachMs = newApproach))
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.rhythm_settings_title, chartName),
                style = MaterialTheme.typography.titleLarge,
            )

            Text(
                text = stringResource(R.string.rhythm_settings_delay),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.rhythm_generate_offset_value, delayMs),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                OutlinedButton(onClick = { commit(newDelay = delayMs - 1) }) { Text("-1") }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = { commit(newDelay = delayMs + 1) }) { Text("+1") }
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = { commit(newDelay = 0, newApproach = approachMs) }) {
                    Text(stringResource(R.string.rhythm_settings_reset))
                }
            }
            Slider(
                value = delayMs.toFloat(),
                onValueChange = { delayMs = it.toInt() },
                onValueChangeFinished = { commit() },
                valueRange = RhythmChartSettings.MIN_DELAY_MS.toFloat()..
                    RhythmChartSettings.MAX_DELAY_MS.toFloat(),
                steps = 59,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = stringResource(R.string.rhythm_settings_delay_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Text(
                text = stringResource(R.string.rhythm_settings_approach),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = stringResource(R.string.rhythm_generate_offset_value, approachMs),
                style = MaterialTheme.typography.titleMedium,
            )
            Slider(
                value = approachMs.toFloat(),
                onValueChange = { approachMs = it.toInt() },
                onValueChangeFinished = { commit() },
                valueRange = RhythmChartSettings.MIN_APPROACH_MS.toFloat()..
                    RhythmChartSettings.MAX_APPROACH_MS.toFloat(),
                steps = 22,
                modifier = Modifier.fillMaxWidth(),
            )

            Text(
                text = stringResource(R.string.rhythm_settings_auto_saved),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.rhythm_generate_cancel))
            }
        }
    }
}
