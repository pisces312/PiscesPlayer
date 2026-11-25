/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Original work in 2026 for PixelPlayerOSS
 * (RhythmChartLab docs/design/rhythm-latency-per-device-design.md).
 */
package com.pisces312.piscesplayer.presentation.rhythm

import android.content.Context
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pisces312.piscesplayer.R
import com.pisces312.piscesplayer.data.rhythm.LatencyDevice
import com.pisces312.piscesplayer.data.rhythm.LatencyRouteKind
import com.pisces312.piscesplayer.data.rhythm.LatencySetting
import com.pisces312.piscesplayer.data.rhythm.LatencySource
import com.pisces312.piscesplayer.data.rhythm.parseLatencyInput
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * Device name to show in the card header, the saved list, the calibration overlay and the toasts.
 *
 * Only an identifiable route (Bluetooth MAC / name, USB product name) may show its own name: a
 * built-in speaker reports the phone model as its product name and a 3.5 mm headset reports no
 * identity at all, so both are named by route kind instead. The rendered form is
 * "AirPods Pro (Bluetooth)" / "扬声器" so the same device never shows up under two names.
 */
private fun LatencyDevice.labelWith(kindLabel: String): String =
    if (identifiable && displayName.isNotBlank()) "$displayName ($kindLabel)" else kindLabel

/** Compose flavour of [labelWith], for callers that have `stringResource` available. */
@Composable
internal fun LatencyDevice.displayLabel(): String =
    labelWith(stringResource(kind.labelRes))

/** Non-Compose flavour, for toasts and the song picker screen. */
internal fun LatencyDevice.displayLabel(context: Context): String =
    labelWith(context.getString(kind.labelRes))

/**
 * 「音频延迟」: one card with three subsections — calibration, manual offset, saved devices.
 *
 * Laid out like [GameVisualsCard] on purpose — same container, same 16 dp padding, same 16 px
 * title, sections separated by hairlines instead of a second tinted card. The three subsection
 * titles sit one size below the card title so the card still reads as ONE value with three ways
 * to touch it, not as three unrelated settings.
 *
 * The confirm button is the only write path: typing is a draft, losing focus or closing the
 * sheet discards it. The header value therefore has to say which number is actually in effect —
 * while a draft differs it dims and reads「未应用」.
 */
@Composable
internal fun LatencyCard(
    setting: LatencySetting,
    calibratedOffsetMs: Int?,
    saved: List<LatencySetting>,
    nearbyGranted: Boolean,
    onCalibrate: () -> Unit,
    onRestore: () -> Unit,
    onApply: (Int) -> Unit,
    onForget: (String) -> Unit,
    onRequestNearby: () -> Unit,
) {
    val device = setting.device
    var draft by remember(device.id) { mutableStateOf(formatOffset(setting.offsetMs)) }
    // Follow the stored number when it changes from outside (calibrate, restore, device switch).
    // Typing never changes setting.offsetMs, so an unsaved draft survives recomposition.
    LaunchedEffect(device.id, setting.offsetMs) { draft = formatOffset(setting.offsetMs) }

    val parsed = parseLatencyInput(draft)
    val dirty = parsed != null && parsed != setting.offsetMs
    var justApplied by remember { mutableStateOf(false) }
    LaunchedEffect(dirty) { if (dirty) justApplied = false }
    LaunchedEffect(justApplied) {
        if (justApplied) {
            delay(2_000)
            justApplied = false
        }
    }

    val jitter = setting.sigmaMs
    val jittery = (jitter ?: 0.0) > LATENCY_JITTER_WARN_MS
    val sourceLabel = sourceLabel(setting)
    val sigmaLabel = jitter?.let { stringResource(R.string.rhythm_latency_sigma, it.roundToInt()) }
    val stampLabel = setting.updatedAtMs.takeIf { it != 0L }?.let { updatedAgoLabel(setting) }
    val meta = listOfNotNull(
        device.displayLabel(),
        sourceLabel,
        sigmaLabel,
        stampLabel,
    ).joinToString(" · ")

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
        ) {
            Row(verticalAlignment = Alignment.Top) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.rhythm_latency_section),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = meta,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (jittery) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = stringResource(
                            R.string.rhythm_latency_card_offset,
                            formatOffset(setting.offsetMs),
                        ),
                        style = MaterialTheme.typography.titleMedium,
                        color = if (dirty) {
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.42f)
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    )
                    Text(
                        text = stringResource(
                            if (dirty) {
                                R.string.rhythm_latency_pending_value
                            } else {
                                R.string.rhythm_latency_current_value
                            },
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }

            SectionDivider()

            CalibrationSection(
                calibratedOffsetMs = calibratedOffsetMs,
                manualInEffect = setting.source == LatencySource.MANUAL,
                onCalibrate = onCalibrate,
                onRestore = onRestore,
            )

            SectionDivider()

            Text(
                text = stringResource(R.string.rhythm_latency_manual_title),
                style = MaterialTheme.typography.titleSmall,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OffsetInputField(
                    text = draft,
                    onTextChange = { draft = it },
                    onFlipSign = { draft = toggleLatencySign(draft) },
                )
                Spacer(Modifier.width(12.dp))
                Button(
                    onClick = {
                        val target = parseLatencyInput(draft) ?: return@Button
                        draft = formatOffset(target)
                        justApplied = true
                        onApply(target)
                    },
                    enabled = dirty,
                    modifier = Modifier.height(48.dp),
                ) {
                    Text(
                        text = stringResource(
                            if (justApplied && !dirty) {
                                R.string.rhythm_latency_applied_state
                            } else {
                                R.string.rhythm_latency_apply
                            },
                        ),
                    )
                }
            }
            Text(
                text = stringResource(R.string.rhythm_latency_manual_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )

            if (saved.isNotEmpty()) {
                SectionDivider()
                SavedDevicesSection(active = setting, saved = saved, onForget = onForget)
            }

            if (device.kind == LatencyRouteKind.BLUETOOTH && !nearbyGranted) {
                NearbyDevicesHint(onRequest = onRequestNearby)
            }
        }
    }
}

@Composable
private fun SectionDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(vertical = 14.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
    )
}

/**
 * 校准: what the last measurement was, what the test does, and the button that runs it.
 *
 * The restore action belongs here rather than next to the offset field: it undoes a hand edit,
 * which is only possible because the overlay keeps the last measurement.
 */
@Composable
private fun CalibrationSection(
    calibratedOffsetMs: Int?,
    manualInEffect: Boolean,
    onCalibrate: () -> Unit,
    onRestore: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.rhythm_latency_calibration_title),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = if (calibratedOffsetMs != null) {
                stringResource(
                    R.string.rhythm_latency_calibrated_at,
                    formatOffset(calibratedOffsetMs),
                )
            } else {
                stringResource(R.string.rhythm_latency_uncalibrated)
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Text(
        text = stringResource(R.string.rhythm_latency_calibration_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp),
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FilledTonalButton(onClick = onCalibrate) {
            Text(
                text = stringResource(
                    if (calibratedOffsetMs != null) {
                        R.string.rhythm_latency_recalibrate
                    } else {
                        R.string.rhythm_latency_calibrate
                    },
                ),
            )
        }
        Spacer(Modifier.weight(1f))
        Text(
            text = stringResource(R.string.rhythm_latency_meta_duration),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (manualInEffect && calibratedOffsetMs != null) {
        TextButton(
            onClick = onRestore,
            modifier = Modifier.padding(top = 2.dp),
        ) {
            Text(
                text = stringResource(
                    R.string.rhythm_latency_restore_calibrated,
                    formatOffset(calibratedOffsetMs),
                ),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/**
 * The offset field: a 48 dp box holding the sign, the number and the unit.
 *
 * Sized down from a stock `OutlinedTextField` (56 dp tall with 16 dp of padding, and its own
 * floating label) because the label belongs to the row above: at 16 px next to a 20 px number the
 * two looked like a caption and a headline rather than one setting.
 *
 * The sign lives inside the box as a tap target, not as a second button beside it — a numeric
 * keyboard has no minus key, and `±` is part of the number, not an action on it.
 */
@Composable
private fun OffsetInputField(
    text: String,
    onTextChange: (String) -> Unit,
    onFlipSign: () -> Unit,
) {
    val focusManager = LocalFocusManager.current
    val signLabel = stringResource(R.string.rhythm_latency_manual_sign)
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .width(132.dp)
            .height(48.dp)
            .border(
                width = if (focused) 2.dp else 1.dp,
                color = if (focused) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.outline
                },
                shape = RoundedCornerShape(6.dp),
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .width(34.dp)
                .clickable(onClick = onFlipSign)
                .semantics { contentDescription = signLabel },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "±",
                fontSize = 16.sp,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Box(
            modifier = Modifier
                .width(1.dp)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
        )
        BasicTextField(
            value = text,
            onValueChange = onTextChange,
            singleLine = true,
            textStyle = TextStyle(
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.End,
            ),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Number,
                imeAction = ImeAction.Done,
            ),
            // Done only closes the keyboard: writing is the confirm button's job.
            keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
            modifier = Modifier
                .weight(1f)
                .padding(start = 8.dp, end = 4.dp)
                .onFocusChanged { focused = it.isFocused },
        )
        Text(
            text = stringResource(R.string.rhythm_latency_manual_unit),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 8.dp),
        )
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
 * Age of the value. A typed number was not measured, so it must not claim to have been: the
 * screen used to read「手动 · 今天测量」for a hand edit.
 */
@Composable
private fun updatedAgoLabel(setting: LatencySetting): String {
    val days = ((System.currentTimeMillis() - setting.updatedAtMs) / 86_400_000L).toInt()
    val manual = setting.source == LatencySource.MANUAL
    return if (days <= 0) {
        stringResource(
            if (manual) R.string.rhythm_latency_edited_today else R.string.rhythm_latency_measured_today,
        )
    } else {
        stringResource(
            if (manual) R.string.rhythm_latency_edited_days else R.string.rhythm_latency_measured_days,
            days,
        )
    }
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
 * Every stored record, inside the card and under a hairline. Needed because the value is per
 * device: without this list a headset that is not connected would keep a number nobody can see
 * or reset.
 *
 * Collapsed by default and collapsed means NO rows: the card header already carries the active
 * device and its value, so this section is management only. The old "preview three rows" version
 * did nothing at all while fewer than four devices were stored — the toggle flipped its label but
 * `take(3)` still showed every row — so a collapsed list is empty, and the control only appears
 * when there is something to hide.
 */
@Composable
private fun SavedDevicesSection(
    active: LatencySetting,
    saved: List<LatencySetting>,
    onForget: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val collapsible = saved.size > 1
    val rotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        label = "savedDevicesChevron",
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .then(
                if (collapsible) {
                    Modifier.clickable(role = Role.Button) { expanded = !expanded }
                } else {
                    Modifier
                },
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.rhythm_latency_saved_devices, saved.size),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.weight(1f),
        )
        if (collapsible) {
            Icon(
                imageVector = Icons.Rounded.KeyboardArrowDown,
                contentDescription = null,
                modifier = Modifier
                    .size(18.dp)
                    .rotate(rotation),
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
    val visible = if (collapsible && !expanded) emptyList() else saved
    visible.forEachIndexed { index, record ->
        if (index > 0) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f))
        }
        SavedDeviceRow(
            record = record,
            active = record.device.id == active.device.id,
            onForget = { onForget(record.device.id) },
        )
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
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = record.device.displayLabel(),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (active) FontWeight.Medium else FontWeight.Normal,
            )
            Text(
                text = "$stateLabel · ${sourceLabel(record)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
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
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
private fun NearbyDevicesHint(onRequest: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.rhythm_latency_generic_device),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onRequest) {
            Text(
                text = stringResource(R.string.rhythm_latency_allow_nearby),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/** Jitter above this is worth a retry; same threshold the entry card used. */
private const val LATENCY_JITTER_WARN_MS = 30.0
