/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Original work in 2026 for PiscesPlayer
 * (design/piscesplayer docs/white-noise-implementation-plan.md §3).
 */
package com.pisces312.piscesplayer.presentation.screens

import android.widget.Toast
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.pisces312.piscesplayer.R
import com.pisces312.piscesplayer.data.noise.SoundscapeEngine
import com.pisces312.piscesplayer.data.noise.SoundscapeMix
import com.pisces312.piscesplayer.data.noise.SoundscapePreset
import com.pisces312.piscesplayer.data.noise.SoundscapeSound
import com.pisces312.piscesplayer.presentation.components.BreathingWaves
import com.pisces312.piscesplayer.presentation.components.PresetChipRow
import com.pisces312.piscesplayer.presentation.components.SoundscapeLibrarySheet
import com.pisces312.piscesplayer.presentation.components.TrackCard
import com.pisces312.piscesplayer.presentation.viewmodel.PlayerViewModel
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/**
 * "入梦" — the sleep soundscape mixer screen. A remote control over the
 * soundscape engine/service: starting the mix pauses music, and the sound keeps
 * playing in the foreground service after leaving the page.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SleepSoundscapeScreen(
    playerViewModel: PlayerViewModel,
    onBack: () -> Unit,
) {
    val holder = playerViewModel.soundscapeStateHolder
    val mix by holder.mix.collectAsStateWithLifecycle()
    val presets by holder.presets.collectAsStateWithLifecycle()
    val selectedPresetId by holder.selectedPresetId.collectAsStateWithLifecycle()
    val phase by holder.phase.collectAsStateWithLifecycle()
    val remainingMs by holder.remainingMs.collectAsStateWithLifecycle()
    val hintVisible by holder.firstRunHintVisible.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var libraryOpen by rememberSaveable { mutableStateOf(false) }
    var savePresetOpen by rememberSaveable { mutableStateOf(false) }
    var presetName by rememberSaveable { mutableStateOf("") }
    var deleteConfirmPreset by remember { mutableStateOf<SoundscapePreset?>(null) }

    // Default scene name = track summary ("棕噪声 + 粉噪声"); resolved here because
    // stringResource cannot be called from the plain onAddNew lambda.
    val defaultSceneName = defaultPresetName(mix)

    // Screen-local toast channel (the soundscape is not the player sheet).
    LaunchedEffect(playerViewModel, lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            launch {
                playerViewModel.toastEvents.collect { message ->
                    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.soundscape_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.auth_cd_back),
                        )
                    }
                },
                actions = {
                    Text(
                        text = formatCountdown(remainingMs),
                        style = MaterialTheme.typography.labelLarge,
                        color = if (phase == SoundscapeEngine.Phase.IDLE) {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        } else {
                            MaterialTheme.colorScheme.tertiary
                        },
                        modifier = Modifier.padding(end = 16.dp),
                    )
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp),
        ) {
            Text(
                text = stringResource(R.string.soundscape_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
            )

            BreathingWaves(
                trackAmplitudes = mix.tracks.map { it.volume },
                trackMotions = mix.tracks.map { it.motionDb / 4f },
                playing = phase != SoundscapeEngine.Phase.IDLE,
            )

            // Primary transport stays directly under the visualization so it is
            // reachable on entry, while the mixer below remains vertically scrollable.
            val playing = phase != SoundscapeEngine.Phase.IDLE
            val fading = phase == SoundscapeEngine.Phase.FADING_OUT
            Button(
                onClick = { holder.togglePlayback() },
                enabled = !fading && mix.tracks.isNotEmpty(),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                colors = if (playing) {
                    androidx.compose.material3.ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.tertiary,
                        contentColor = MaterialTheme.colorScheme.onTertiary,
                    )
                } else {
                    androidx.compose.material3.ButtonDefaults.buttonColors()
                },
            ) {
                Text(
                    text = when {
                        fading -> stringResource(R.string.soundscape_fading)
                        playing -> stringResource(R.string.soundscape_stop)
                        else -> stringResource(R.string.soundscape_start)
                    },
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            if (!playing) {
                Text(
                    text = stringResource(R.string.soundscape_start_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                )
            }
            Spacer(Modifier.height(12.dp))

            if (hintVisible) {
                HintBar(onDismiss = { holder.dismissFirstRunHint() })
                Spacer(Modifier.height(8.dp))
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.soundscape_scene_label),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
                Icon(
                    imageVector = Icons.Rounded.SwapHoriz,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = stringResource(R.string.soundscape_scene_swipe_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            PresetChipRow(
                presets = presets,
                selectedPresetId = selectedPresetId,
                onRecall = { holder.recallPreset(it) },
                onDelete = { preset ->
                    if (preset.isBuiltIn) {
                        holder.deletePreset(preset)
                    } else {
                        deleteConfirmPreset = preset
                    }
                },
                onAddNew = {
                    presetName = defaultSceneName
                    savePresetOpen = true
                },
            )

            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.soundscape_current_mix_label),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))

            mix.tracks.forEach { config ->
                TrackCard(
                    config = config,
                    onConfigChange = { updated ->
                        holder.updateTrack(config.soundId) { updated }
                    },
                    onRemove = { holder.removeTrack(config.soundId) },
                )
                Spacer(Modifier.height(8.dp))
            }

            Card(
                onClick = { libraryOpen = true },
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                ),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 16.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Add,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.tertiary,
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = stringResource(R.string.soundscape_add_sound),
                        color = MaterialTheme.colorScheme.tertiary,
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }
            Spacer(Modifier.height(16.dp))

            // Master volume, hard-capped at 70% (hearing protection ceiling).
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.soundscape_master_volume),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                val capped = mix.masterVolume >= SoundscapeEngine.MASTER_VOLUME_CAP - 0.005f
                Text(
                    text = if (capped) {
                        stringResource(R.string.soundscape_volume_capped)
                    } else {
                        "${(mix.masterVolume * 100).roundToInt()}%"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Slider(
                value = mix.masterVolume,
                onValueChange = { holder.setMasterVolume(it) },
                valueRange = 0f..SoundscapeEngine.MASTER_VOLUME_CAP,
                colors = SliderDefaults.colors(
                    activeTrackColor = MaterialTheme.colorScheme.tertiary,
                ),
            )

            Text(
                text = stringResource(R.string.soundscape_duration_label),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .padding(top = 8.dp, bottom = 20.dp)
                    .horizontalScroll(rememberScrollState()),
            ) {
                SoundscapeMix.DURATION_OPTIONS_MINUTES.forEach { minutes ->
                    FilterChip(
                        selected = mix.durationMinutes == minutes,
                        onClick = { holder.setDurationMinutes(minutes) },
                        label = {
                            Text(
                                if (minutes == SoundscapeMix.DURATION_FOREVER) {
                                    stringResource(R.string.soundscape_duration_forever)
                                } else {
                                    stringResource(R.string.soundscape_duration_minutes, minutes)
                                }
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.tertiaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onTertiaryContainer,
                        ),
                    )
                }
            }

            // This route hides both the mini player and app navigation bar.
            // The Scaffold's system navigation inset plus this spacer keeps the
            // final duration option fully reachable when scrolling to the end.
            Spacer(Modifier.height(24.dp))
        }
    }

    if (libraryOpen) {
        SoundscapeLibrarySheet(
            activeSoundIds = mix.tracks.map { it.soundId }.toSet(),
            onAddSound = { holder.addSound(it) },
            onDismiss = { libraryOpen = false },
        )
    }

    if (savePresetOpen) {
        SavePresetSheet(
            initialName = presetName,
            onNameChange = { presetName = it },
            onSave = {
                savePresetOpen = false
                holder.saveCurrentAsPreset(presetName.trim()) { accepted ->
                    if (!accepted) {
                        Toast.makeText(
                            context,
                            context.getString(R.string.soundscape_preset_limit_toast),
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                }
            },
            onDismiss = { savePresetOpen = false },
        )
    }

    deleteConfirmPreset?.let { preset ->
        AlertDialog(
            onDismissRequest = { deleteConfirmPreset = null },
            title = { Text(stringResource(R.string.soundscape_preset_delete_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.soundscape_preset_delete_message,
                        preset.name ?: preset.builtInId.orEmpty(),
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    holder.deletePreset(preset)
                    deleteConfirmPreset = null
                }) {
                    Text(stringResource(R.string.soundscape_preset_delete_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteConfirmPreset = null }) {
                    Text(stringResource(R.string.soundscape_preset_delete_cancel))
                }
            },
        )
    }
}

@Composable
private fun HintBar(onDismiss: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f),
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.soundscape_first_run_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.soundscape_hint_dismiss))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SavePresetSheet(
    initialName: String,
    onNameChange: (String) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.soundscape_preset_save_title),
                style = MaterialTheme.typography.titleMedium,
            )
            OutlinedTextField(
                value = initialName,
                onValueChange = onNameChange,
                singleLine = true,
                label = { Text(stringResource(R.string.soundscape_preset_name_label)) },
                modifier = Modifier.fillMaxWidth(),
            )
            FilledTonalButton(
                onClick = onSave,
                enabled = initialName.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.soundscape_preset_save_confirm))
            }
        }
    }
}

@Composable
private fun formatCountdown(remainingMs: Long?): String {
    if (remainingMs == null) return ""
    val totalSeconds = remainingMs / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format(java.util.Locale.US, "%d:%02d", minutes, seconds)
}

/** Default scene name: the track summary, e.g. "棕噪声 + 雨". */
@Composable
private fun defaultPresetName(mix: SoundscapeMix): String {
    val names = mix.tracks
        .mapNotNull { SoundscapeSound.fromId(it.soundId) }
        .map { stringResource(it.nameRes) }
    return names.joinToString(" + ")
}
