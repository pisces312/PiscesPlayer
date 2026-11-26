/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Original work in 2026 (shared rhythm game settings sheet).
 */
package com.pisces312.piscesplayer.presentation.rhythm

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.pisces312.piscesplayer.R
import com.pisces312.piscesplayer.data.rhythm.LatencyRouteKind
import com.pisces312.piscesplayer.data.rhythm.RhythmLatencyStore
import com.pisces312.piscesplayer.data.rhythm.RhythmUiPreferences
import com.pisces312.piscesplayer.data.rhythm.RhythmUiStyle
import com.pisces312.piscesplayer.presentation.components.rememberModalSheetState

/**
 * 「游戏设置」: device latency + game visuals in one bottom sheet (2026-11-26).
 *
 * Shared by the song picker's top bar and the per-song setup screen so both entry points
 * stay identical. Every latency change is written to [RhythmLatencyStore] immediately and
 * surfaced through [onLatencyChanged], because the setup screen keeps its own resolved copy
 * for the chart settings header and the run delay.
 *
 * Calibration is delegated to the host ([onCalibrate]): the overlay is a Box in the host
 * window while the sheet is its own window, so the sheet must close first — the host
 * re-opens it after the overlay lands (same round trip on both screens).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RhythmGameSettingsSheet(
    onCalibrate: () -> Unit,
    onDismiss: () -> Unit,
    onLatencyChanged: () -> Unit = {},
) {
    val context = LocalContext.current

    // Per-device latency: the resolved value for the output connected now, its last
    // measurement (for "restore"), and every saved record — a headset that is not connected
    // still owns a number nobody else can see otherwise.
    var latencySetting by remember { mutableStateOf(RhythmLatencyStore.resolve(context)) }
    var calibratedOffsetMs by remember {
        mutableStateOf(RhythmLatencyStore.calibratedOffsetMs(context, latencySetting.device))
    }
    var savedLatency by remember { mutableStateOf(RhythmLatencyStore.savedDevices(context)) }
    var nearbyGranted by remember { mutableStateOf(RhythmLatencyStore.hasBluetoothConnect(context)) }

    fun refreshLatency() {
        latencySetting = RhythmLatencyStore.resolve(context)
        savedLatency = RhythmLatencyStore.savedDevices(context)
        nearbyGranted = RhythmLatencyStore.hasBluetoothConnect(context)
        calibratedOffsetMs = RhythmLatencyStore.calibratedOffsetMs(context, latencySetting.device)
        onLatencyChanged()
    }

    // The Bluetooth permission is the difference between one shared number and one per
    // headset: asked once, from this sheet, and never blocking calibration itself.
    val nearbyLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        RhythmLatencyStore.markNearbyPermissionAsked(context)
        if (granted) {
            // The id flips from bt:<name> to bt:<mac>; move the record so it is not "lost".
            RhythmLatencyStore.adoptNameKeyedRecord(
                context,
                RhythmLatencyStore.activeDevice(context),
            )
        }
        refreshLatency()
    }

    // Ask once, the first time the sheet opens on an unidentified Bluetooth output —
    // that is exactly when the user is trying to calibrate it. Refusing never blocks anything.
    LaunchedEffect(Unit) {
        if (
            latencySetting.device.kind == LatencyRouteKind.BLUETOOTH &&
            !nearbyGranted &&
            !RhythmLatencyStore.nearbyPermissionAsked(context)
        ) {
            RhythmLatencyStore.markNearbyPermissionAsked(context)
            nearbyLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
        }
    }

    // Global game visuals (style + low motion). Persisted immediately on every change so the
    // next gameplay entry renders with the choice even if the process is killed in between.
    val uiPreferences = remember { RhythmUiPreferences(context.applicationContext) }
    var uiStyle by remember { mutableStateOf(uiPreferences.uiStyle) }
    var lowMotion by remember { mutableStateOf(uiPreferences.lowMotion) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.rhythm_settings_sheet_title),
                style = MaterialTheme.typography.titleLarge,
            )
            LatencyCard(
                setting = latencySetting,
                calibratedOffsetMs = calibratedOffsetMs,
                saved = savedLatency,
                nearbyGranted = nearbyGranted,
                onCalibrate = onCalibrate,
                onRestore = {
                    RhythmLatencyStore.restoreCalibrated(context, latencySetting.device)
                    refreshLatency()
                },
                onApply = { ms ->
                    RhythmLatencyStore.setManual(context, latencySetting.device, ms)
                    refreshLatency()
                },
                onForget = { id ->
                    RhythmLatencyStore.forget(context, id)
                    refreshLatency()
                },
                onRequestNearby = { nearbyLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT) },
            )
            GameVisualsCard(
                uiStyle = uiStyle,
                lowMotion = lowMotion,
                onStyleChange = { style ->
                    uiStyle = style
                    uiPreferences.uiStyle = style
                },
                onLowMotionChange = { enabled ->
                    lowMotion = enabled
                    uiPreferences.lowMotion = enabled
                },
            )
        }
    }
}

/**
 * 「游戏视觉」: the global visual-style choice for the rhythm game surface.
 *
 * Classic (default) is the pre-existing plain interface and stays untouched as the fallback;
 * neon opts into the glowing surface. Low motion only tames the neon surface's animated
 * extras (particles, star field, pulses), so it is presented as a neon-only refinement.
 * Every change is persisted immediately ([RhythmUiPreferences]).
 */
@Composable
internal fun GameVisualsCard(
    uiStyle: RhythmUiStyle,
    lowMotion: Boolean,
    onStyleChange: (RhythmUiStyle) -> Unit,
    onLowMotionChange: (Boolean) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = stringResource(R.string.rhythm_visual_section),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(R.string.rhythm_ui_style_label),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            RhythmUiStyle.entries.forEach { style ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onStyleChange(style) },
                ) {
                    RadioButton(
                        selected = uiStyle == style,
                        onClick = { onStyleChange(style) },
                    )
                    Column {
                        Text(
                            text = stringResource(
                                when (style) {
                                    RhythmUiStyle.CLASSIC -> R.string.rhythm_ui_style_classic
                                    RhythmUiStyle.NEON -> R.string.rhythm_ui_style_neon
                                },
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            text = stringResource(
                                when (style) {
                                    RhythmUiStyle.CLASSIC -> R.string.rhythm_ui_style_classic_desc
                                    RhythmUiStyle.NEON -> R.string.rhythm_ui_style_neon_desc
                                },
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.rhythm_visual_low_motion),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = stringResource(R.string.rhythm_visual_low_motion_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = lowMotion,
                    onCheckedChange = onLowMotionChange,
                )
            }
        }
    }
}
