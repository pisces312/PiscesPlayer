/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Original work in 2026 for PixelPlayerOSS
 * (docs/rhythm-chart-ladder-plan.md §9.4).
 */
package com.pisces312.piscesplayer.presentation.rhythm

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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.pisces312.piscesplayer.R
import com.pisces312.piscesplayer.data.rhythm.RhythmChartSettings
import com.pisces312.piscesplayer.data.rhythm.RhythmLatencyStore
import com.pisces312.piscesplayer.data.rhythm.delayAfterTap

/**
 * Per-chart playback settings. Saving on drag-release (not on every frame) keeps the sidecar
 * write off the gesture path; the values only affect the next run, never the chart itself.
 *
 * The latency offset is an *override*: null follows the route-keyed calibration shown at the
 * top of the setup screen, a value here wins for this chart. The first ±10 tap seeds from the
 * route value so a chart can be nudged relative to its calibration.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RhythmChartSettingsSheet(
    chartName: String,
    settings: RhythmChartSettings,
    routeOffsetMs: Int,
    routeDeviceKey: String,
    onChange: (RhythmChartSettings) -> Unit,
    onDismiss: () -> Unit,
) {
    var delayOverrideMs by remember(chartName) { mutableStateOf(settings.delayOverrideMs) }
    var leadInMs by remember(chartName) { mutableIntStateOf(settings.leadInMs) }
    var approachMs by remember(chartName) { mutableIntStateOf(settings.approachMs) }

    fun commit(
        newDelayOverride: Int? = delayOverrideMs,
        newLeadIn: Int = leadInMs,
        newApproach: Int = approachMs,
    ) {
        delayOverrideMs = newDelayOverride
        leadInMs = newLeadIn
        approachMs = newApproach
        onChange(
            settings.copy(
                delayOverrideMs = newDelayOverride,
                leadInMs = newLeadIn,
                approachMs = newApproach,
            ),
        )
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

            // --- latency override (chart beats route) -----------------------------------------
            Text(
                text = stringResource(R.string.rhythm_settings_delay_override),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val override = delayOverrideMs
                Text(
                    text = if (override != null) {
                        stringResource(R.string.rhythm_generate_offset_value, override)
                    } else {
                        stringResource(
                            R.string.rhythm_settings_delay_follow,
                            stringResource(RhythmLatencyStore.deviceLabelRes(routeDeviceKey)),
                            routeOffsetMs,
                        )
                    },
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                OutlinedButton(
                    onClick = {
                        commit(newDelayOverride = delayAfterTap(override ?: routeOffsetMs, -10))
                    },
                ) {
                    Text("-10")
                }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(
                    onClick = {
                        commit(newDelayOverride = delayAfterTap(override ?: routeOffsetMs, 10))
                    },
                ) {
                    Text("+10")
                }
                Spacer(Modifier.width(8.dp))
                TextButton(
                    onClick = { commit(newDelayOverride = null) },
                    enabled = override != null,
                ) {
                    Text(stringResource(R.string.rhythm_settings_delay_follow_reset))
                }
            }
            Text(
                text = stringResource(R.string.rhythm_settings_delay_override_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // --- lead-in -----------------------------------------------------------------------
            Text(
                text = stringResource(R.string.rhythm_settings_lead_in),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.rhythm_settings_lead_in_value, leadInMs),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                OutlinedButton(
                    onClick = {
                        commit(
                            newLeadIn = (leadInMs - RhythmChartSettings.LEAD_IN_STEP_MS)
                                .coerceAtLeast(0),
                        )
                    },
                ) {
                    Text("-500")
                }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(
                    onClick = {
                        commit(newLeadIn = leadInMs + RhythmChartSettings.LEAD_IN_STEP_MS)
                    },
                ) {
                    Text("+500")
                }
                Spacer(Modifier.width(8.dp))
                TextButton(
                    onClick = { commit(newLeadIn = RhythmChartSettings.DEFAULT_LEAD_IN_MS) },
                ) {
                    Text(stringResource(R.string.rhythm_settings_reset))
                }
            }
            Text(
                text = stringResource(R.string.rhythm_settings_lead_in_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // --- approach ----------------------------------------------------------------------
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
