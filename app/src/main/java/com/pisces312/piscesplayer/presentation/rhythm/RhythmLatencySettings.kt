/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Original work in 2026 for PixelPlayerOSS
 * (RhythmChartLab docs/design/rhythm-latency-per-device-design.md).
 */
package com.pisces312.piscesplayer.presentation.rhythm

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Speaker
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.pisces312.piscesplayer.R
import com.pisces312.piscesplayer.data.rhythm.LatencyDevice
import com.pisces312.piscesplayer.data.rhythm.LatencyRouteKind
import com.pisces312.piscesplayer.data.rhythm.LatencySetting
import com.pisces312.piscesplayer.data.rhythm.LatencySource
import com.pisces312.piscesplayer.data.rhythm.parseLatencyInput
import kotlin.math.roundToInt

/** Device name to show: the framework's product name, or the kind when there is none. */
@Composable
internal fun LatencyDevice.displayLabel(): String =
    displayName.ifBlank { stringResource(kind.labelRes) }

/**
 * The active output and the number the next run will apply.
 *
 * The offset is one value however it was produced: a measurement, a hand edit, or the kind
 * baseline it inherits. The badge says which, because a hand-edited number has no jitter and
 * should not pretend to.
 */
@Composable
internal fun LatencyDeviceCard(
    setting: LatencySetting,
    calibratedOffsetMs: Int?,
    onCalibrate: () -> Unit,
    onRestore: () -> Unit,
) {
    val device = setting.device
    val jitter = setting.sigmaMs
    val jittery = (jitter ?: 0.0) > LATENCY_JITTER_WARN_MS
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(start = 16.dp, end = 12.dp, top = 14.dp, bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (device.kind == LatencyRouteKind.SPEAKER) {
                        Icons.Rounded.Speaker
                    } else {
                        Icons.Rounded.Headphones
                    },
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = device.displayLabel(),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(device.kind.labelRes),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = sourceLabel(setting),
                            style = MaterialTheme.typography.bodySmall,
                            color = when (setting.source) {
                                LatencySource.MANUAL -> MaterialTheme.colorScheme.primary
                                LatencySource.NONE -> MaterialTheme.colorScheme.primary
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                        if (jitter != null) {
                            Text(
                                text = stringResource(
                                    R.string.rhythm_latency_sigma,
                                    jitter.roundToInt(),
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = if (jittery) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                        if (setting.updatedAtMs != 0L) {
                            Text(
                                text = measuredAgoLabel(setting.updatedAtMs),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                if (setting.hasRecord) {
                    Text(
                        text = stringResource(
                            R.string.rhythm_latency_card_offset,
                            formatOffset(setting.offsetMs),
                        ),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (calibratedOffsetMs != null && setting.source == LatencySource.MANUAL) {
                    TextButton(onClick = onRestore) {
                        Text(
                            text = stringResource(
                                R.string.rhythm_latency_restore_calibrated,
                                formatOffset(calibratedOffsetMs),
                            ),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
                Button(onClick = onCalibrate) {
                    Text(
                        text = stringResource(
                            if (setting.hasRecord) {
                                R.string.rhythm_latency_recalibrate
                            } else {
                                R.string.rhythm_latency_calibrate
                            },
                        ),
                    )
                }
            }
        }
    }
}

@Composable
private fun sourceLabel(setting: LatencySetting): String = when (setting.source) {
    LatencySource.CALIBRATED -> stringResource(R.string.rhythm_latency_source_calibrated)
    LatencySource.MANUAL -> stringResource(R.string.rhythm_latency_source_manual)
    LatencySource.INHERITED -> stringResource(
        R.string.rhythm_latency_source_inherited,
        stringResource(setting.inheritedFrom?.labelRes ?: setting.device.kind.labelRes),
    )
    LatencySource.NONE -> stringResource(R.string.rhythm_latency_uncalibrated)
}

/**
 * Hand-edited offset. One field, no slider: the intent here is "I know I want −437", and a step
 * button can only approach it.
 *
 * Commits on IME done, on focus loss and when the sheet leaves composition, never per keystroke —
 * a half-typed `-` or `-4` is not a value. Anything unparsable restores the previous number, so
 * clearing the field never silently writes 0.
 */
@Composable
internal fun LatencyOffsetField(
    setting: LatencySetting,
    onCommit: (Int) -> Unit,
) {
    val focusManager = LocalFocusManager.current
    val signLabel = stringResource(R.string.rhythm_latency_manual_sign)
    var text by remember(setting.device.id, setting.offsetMs) {
        mutableStateOf(formatOffset(setting.offsetMs))
    }
    val latestText = rememberUpdatedState(text)
    val latestValue = rememberUpdatedState(setting.offsetMs)
    val latestCommit = rememberUpdatedState(onCommit)
    var hadFocus by remember { mutableStateOf(false) }

    fun commitNow(raw: String = text) {
        val parsed = parseLatencyInput(raw)
        if (parsed == null) {
            text = formatOffset(setting.offsetMs)
            return
        }
        text = formatOffset(parsed)
        if (parsed != setting.offsetMs) onCommit(parsed)
    }

    // Swiping the sheet away must not drop what was typed.
    DisposableEffect(setting.device.id) {
        onDispose {
            val parsed = parseLatencyInput(latestText.value) ?: return@onDispose
            if (parsed != latestValue.value) latestCommit.value(parsed)
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.rhythm_latency_manual_title),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            singleLine = true,
            textStyle = MaterialTheme.typography.titleMedium.copy(
                textAlign = TextAlign.End,
                fontWeight = FontWeight.Medium,
            ),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Number,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(
                onDone = {
                    commitNow()
                    focusManager.clearFocus()
                },
            ),
            suffix = { Text(stringResource(R.string.rhythm_latency_manual_unit)) },
            modifier = Modifier
                .width(132.dp)
                .onFocusChanged { state ->
                    if (state.isFocused) {
                        hadFocus = true
                    } else if (hadFocus) {
                        hadFocus = false
                        commitNow()
                    }
                },
        )
        Spacer(Modifier.width(8.dp))
        // Most numeric keyboards have no minus key, so the sign cannot depend on typing.
        OutlinedButton(
            modifier = Modifier.semantics {
                contentDescription = signLabel
            },
            onClick = {
                // Commit the flipped text explicitly: `text` was only just assigned, so a second
                // read through a remembered snapshot would still see the old value.
                val flipped = toggleLatencySign(text)
                text = flipped
                commitNow(flipped)
            },
        ) {
            Text(text = "±")
        }
    }
    Text(
        text = stringResource(R.string.rhythm_latency_manual_range),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(
        text = stringResource(R.string.rhythm_latency_manual_shared_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Flips the sign of whatever is in the field, leaving an unparsable draft untouched. */
internal fun toggleLatencySign(raw: String): String {
    val trimmed = raw.trim()
    val negative = trimmed.startsWith("-") || trimmed.startsWith("−")
    val body = if (negative) trimmed.drop(1) else trimmed.removePrefix("+")
    // A lone "-" (or a draft like "4a") is still being typed: flipping it would eat the sign.
    if (parseLatencyInput(body) == null) return raw
    return if (negative) body else "−$body"
}

/**
 * Every stored record. Needed because the value is per device: without this list a headset that is
 * not connected would keep a number nobody can see or reset.
 */
@Composable
internal fun SavedLatencyDevices(
    active: LatencySetting,
    saved: List<LatencySetting>,
    onForget: (String) -> Unit,
) {
    if (saved.isEmpty()) return
    var expanded by remember { mutableStateOf(saved.size <= SAVED_DEVICES_COLLAPSED) }
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.rhythm_latency_saved_devices, saved.size),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { expanded = !expanded }) {
                Icon(
                    imageVector = if (expanded) {
                        Icons.Rounded.KeyboardArrowUp
                    } else {
                        Icons.Rounded.KeyboardArrowDown
                    },
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = stringResource(
                        if (expanded) {
                            R.string.rhythm_latency_saved_collapse
                        } else {
                            R.string.rhythm_latency_saved_expand
                        },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        val visible = if (expanded) saved else saved.take(SAVED_DEVICES_COLLAPSED)
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
            ),
        ) {
            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp)) {
                visible.forEach { record ->
                    SavedDeviceRow(
                        record = record,
                        active = record.device.id == active.device.id,
                        onForget = { onForget(record.device.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SavedDeviceRow(
    record: LatencySetting,
    active: Boolean,
    onForget: () -> Unit,
) {
    val stateLabel = stringResource(
        if (active) R.string.rhythm_latency_device_current else R.string.rhythm_latency_device_disconnected,
    )
    val source = sourceLabel(record)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = record.device.displayLabel(),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (active) FontWeight.Medium else FontWeight.Normal,
            )
            Text(
                text = "$stateLabel · $source",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = stringResource(
                R.string.rhythm_latency_card_offset,
                formatOffset(record.offsetMs),
            ),
            style = MaterialTheme.typography.bodyMedium,
        )
        TextButton(onClick = onForget) {
            Text(
                text = stringResource(R.string.rhythm_latency_forget),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/**
 * Shown when the device cannot be told apart from its siblings. The permission is the difference
 * between "one number for all Bluetooth headsets" and "one number per headset", and it is asked
 * only from here.
 */
@Composable
internal fun NearbyDevicesHint(onRequest: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.rhythm_latency_generic_device),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(
            onClick = onRequest,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
        ) {
            Text(
                text = stringResource(R.string.rhythm_latency_allow_nearby),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/** Jitter above this is worth a retry; same threshold the entry card used. */
private const val LATENCY_JITTER_WARN_MS = 30.0

/** Rows shown while the list is collapsed; the current device is always first. */
private const val SAVED_DEVICES_COLLAPSED = 3
