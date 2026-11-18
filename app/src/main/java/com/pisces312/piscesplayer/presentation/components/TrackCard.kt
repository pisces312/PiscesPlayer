/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Original work in 2026 for PiscesPlayer
 * (design/piscesplayer docs/white-noise-implementation-plan.md §3.2 / §3.4).
 */
package com.pisces312.piscesplayer.presentation.components

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pisces312.piscesplayer.R
import com.pisces312.piscesplayer.data.noise.NoiseSynthesis
import com.pisces312.piscesplayer.data.noise.SoundscapeTrackConfig
import com.pisces312.piscesplayer.data.noise.SoundscapeSound
import kotlin.math.roundToInt

/**
 * One track of the current mix: name (tap to expand the noise parameters),
 * volume slider, remove button. Parameter changes apply live while playing —
 * the synth updates in place without restarting.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackCard(
    config: SoundscapeTrackConfig,
    onConfigChange: (SoundscapeTrackConfig) -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by rememberSaveable(config.soundId) { mutableStateOf(false) }
    val sound = SoundscapeSound.fromId(config.soundId)
    val name = sound?.let { stringResource(it.nameRes) } ?: config.soundId
    // Natural recordings have no synthesis parameters; only synth tracks expand.
    val hasParams = sound?.isSynth == true

    Card(
        modifier = modifier
            .fillMaxWidth()
            .animateContentSize(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier
                        .weight(1f)
                        .then(if (hasParams) Modifier.clickable { expanded = !expanded } else Modifier),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${(config.volume * 100).roundToInt()}%",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (hasParams) {
                    IconButton(onClick = { expanded = !expanded }) {
                        Icon(
                            imageVector = if (expanded) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.KeyboardArrowDown,
                            contentDescription = stringResource(
                                if (expanded) R.string.soundscape_collapse_track else R.string.soundscape_expand_track
                            ),
                        )
                    }
                }
                IconButton(onClick = onRemove) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = stringResource(R.string.soundscape_cd_remove_track),
                    )
                }
            }

            ParameterSlider(
                label = stringResource(R.string.soundscape_track_volume),
                valueLabel = "${(config.volume * 100).roundToInt()}%",
                value = config.volume,
                onValueChange = { onConfigChange(config.copy(volume = it)) },
            )

            if (expanded && hasParams) {
                ParameterSlider(
                    label = stringResource(R.string.soundscape_tone),
                    valueLabel = stringResource(R.string.soundscape_tone_range),
                    value = (config.toneHz - NoiseSynthesis.TONE_CUTOFF_MIN_HZ) /
                        (NoiseSynthesis.TONE_CUTOFF_MAX_HZ - NoiseSynthesis.TONE_CUTOFF_MIN_HZ),
                    onValueChange = { fraction ->
                        onConfigChange(
                            config.copy(
                                toneHz = NoiseSynthesis.TONE_CUTOFF_MIN_HZ +
                                    fraction * (NoiseSynthesis.TONE_CUTOFF_MAX_HZ - NoiseSynthesis.TONE_CUTOFF_MIN_HZ)
                            )
                        )
                    },
                )
                ParameterSlider(
                    label = stringResource(R.string.soundscape_motion),
                    valueLabel = stringResource(R.string.soundscape_motion_range),
                    value = config.motionDb / NoiseSynthesis.MOTION_DEPTH_MAX_DB,
                    onValueChange = { fraction ->
                        onConfigChange(config.copy(motionDb = fraction * NoiseSynthesis.MOTION_DEPTH_MAX_DB))
                    },
                )
                ParameterSlider(
                    label = stringResource(R.string.soundscape_width),
                    valueLabel = stringResource(R.string.soundscape_width_range),
                    value = config.width,
                    onValueChange = { fraction -> onConfigChange(config.copy(width = fraction)) },
                )
            }
        }
    }
}

@Composable
private fun ParameterSlider(
    label: String,
    valueLabel: String,
    value: Float,
    onValueChange: (Float) -> Unit,
) {
    Column(modifier = Modifier.padding(top = 2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = valueLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Slider(
            value = value.coerceIn(0f, 1f),
            onValueChange = { onValueChange(it.coerceIn(0f, 1f)) },
            colors = androidx.compose.material3.SliderDefaults.colors(
                activeTrackColor = MaterialTheme.colorScheme.tertiary,
            ),
        )
    }
}
