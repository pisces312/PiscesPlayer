/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Original work in 2026 for PixelPlayerOSS
 * (docs/rhythm-master-timing-variants-and-derive-profiles-plan.md §7).
 */
package com.pisces312.piscesplayer.presentation.rhythm

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.pisces312.piscesplayer.R
import com.pisces312.piscesplayer.data.rhythm.DspDifficulty
import com.pisces312.piscesplayer.data.rhythm.LaneStrategy
import com.pisces312.piscesplayer.data.rhythm.OnsetSelection
import com.pisces312.piscesplayer.data.rhythm.RhythmChart
import com.pisces312.piscesplayer.data.rhythm.RhythmChartDeriver
import com.pisces312.piscesplayer.data.rhythm.RhythmDeriveMode
import com.pisces312.piscesplayer.data.rhythm.RhythmDerivePreset
import com.pisces312.piscesplayer.data.rhythm.RhythmDeriveSettings
import com.pisces312.piscesplayer.data.rhythm.RhythmTimingVariantKey
import com.pisces312.piscesplayer.presentation.components.rememberModalSheetState
import java.util.Locale

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
 * Derive difficulties from the stored master chart.
 *
 * Everything here is instant because deriving is a pure function over notes: the note counts are
 * computed live, before the user commits, so "about N notes" is not a guess. Presets write every
 * field at once and collapse the controls into a read-only summary; touching any control switches
 * the preset chip back to custom (§7.3).
 */
@OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalMaterial3ExpressiveApi::class,
    ExperimentalLayoutApi::class,
)
@Composable
internal fun RhythmDeriveSheet(
    master: RhythmChart,
    deriving: Boolean,
    onComputeEstimates: suspend (RhythmDeriveSettings) -> Map<Pair<DspDifficulty, RhythmDeriveMode>, Int>,
    onDismiss: () -> Unit,
    onDerive: (RhythmDeriveSettings) -> Unit,
) {
    var settings by remember { mutableStateOf(RhythmDeriveSettings()) }
    // Expanded while the values are being fine-tuned; a preset tap collapses them into a summary.
    var expanded by remember { mutableStateOf(true) }
    var estimates by remember { mutableStateOf<Map<Pair<DspDifficulty, RhythmDeriveMode>, Int>?>(null) }
    // Which info dialog is open, if any.
    var infoMode by remember { mutableStateOf<RhythmDeriveMode?>(null) }
    var infoSelection by remember { mutableStateOf<OnsetSelection?>(null) }
    var infoPreset by remember { mutableStateOf<RhythmDerivePreset?>(null) }
    var infoTiming by remember { mutableStateOf<String?>(null) }

    val matchedPreset = RhythmDerivePreset.match(settings)
    val timingChoices = remember(master) { master.timingVariantsFor() }
    val timingAvailable = timingChoices.isNotEmpty()
    val classicApproximate = timingChoices
        .firstOrNull { it.key == RhythmTimingVariantKey.CLASSIC }
        ?.approximate == true
    val loudnessAvailable = master.rmsThresholds.isNotEmpty()
    val masterBpm = remember(master) {
        master.timing.firstOrNull()
            ?.let { String.format(Locale.US, "%.1f", 60_000.0 / it.beatMs) }
            ?: "—"
    }

    LaunchedEffect(
        settings.timingVariant,
        settings.modes,
        settings.selection,
        settings.laneStrategy,
        settings.maxChord,
        settings.minRmsPercentile,
        settings.maxLocalNps,
    ) {
        estimates = null
        estimates = onComputeEstimates(settings)
    }

    val modeLabels = mapOf(
        RhythmDeriveMode.GRID to stringResource(R.string.rhythm_derive_mode_grid),
        RhythmDeriveMode.ONSET to stringResource(R.string.rhythm_derive_mode_onset),
        RhythmDeriveMode.CLASSIC to stringResource(R.string.rhythm_derive_mode_classic),
    )
    val selectionLabels = mapOf(
        OnsetSelection.BALANCED to stringResource(R.string.rhythm_derive_selection_balanced),
        OnsetSelection.MELODY_FIRST to stringResource(R.string.rhythm_derive_selection_melody),
    )
    val profileLabels = mapOf(
        RhythmDerivePreset.CLASSIC_043 to stringResource(R.string.rhythm_derive_profile_classic_043),
        RhythmDerivePreset.RECOMMENDED to stringResource(R.string.rhythm_derive_profile_recommended),
    )
    val timingLabels = mapOf(
        RhythmTimingVariantKey.TRACKED to stringResource(R.string.rhythm_derive_timing_tracked),
        RhythmTimingVariantKey.CLASSIC to stringResource(R.string.rhythm_derive_timing_classic),
    )
    val approxShort = stringResource(R.string.rhythm_derive_timing_approx_short)
    val laneLabels = mapOf(
        LaneStrategy.BAND_ORDER to stringResource(R.string.rhythm_derive_lane_band_order),
        LaneStrategy.LOW_OUTSIDE to stringResource(R.string.rhythm_derive_lane_low_outside),
    )
    val loudnessLabels = LOUDNESS_CHOICES.associate { (value, label) ->
        value to stringResource(label)
    }
    val densityLabels = DENSITY_CHOICES.associate { (value, label) ->
        value to stringResource(label)
    }

    // Open at full height: at partial height the derive button sits on the visible edge and
    // the settings look cut off.
    val sheetState = rememberModalSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = { if (!deriving) onDismiss() },
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
                text = stringResource(R.string.rhythm_derive_title),
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                text = stringResource(
                    R.string.rhythm_derive_master_line,
                    master.noteCount(),
                    masterBpm,
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            SectionLabel(stringResource(R.string.rhythm_derive_profile))
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                RhythmDerivePreset.entries.forEach { preset ->
                    FilterChip(
                        selected = matchedPreset == preset,
                        onClick = {
                            settings = preset.applyTo(settings)
                            expanded = false
                        },
                        label = { Text(profileLabels.getValue(preset)) },
                    )
                    InfoButton(onClick = { infoPreset = preset })
                }
                // State display only: "custom" is selected whenever no built-in preset matches.
                FilterChip(
                    selected = matchedPreset == null,
                    onClick = {},
                    enabled = false,
                    label = { Text(stringResource(R.string.rhythm_derive_profile_custom)) },
                )
            }

            if (matchedPreset != null && !expanded) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = profileSummary(
                            settings = settings,
                            modeLabels = modeLabels,
                            selectionLabels = selectionLabels,
                            timingLabels = timingLabels,
                            laneLabels = laneLabels,
                            loudnessLabels = loudnessLabels,
                            densityLabels = densityLabels,
                            classicApproximate = classicApproximate,
                            approxShort = approxShort,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(12.dp),
                    )
                }
                TextButton(onClick = { expanded = true }) {
                    Text(stringResource(R.string.rhythm_derive_profile_expand))
                }
                EstimatesColumn(
                    settings = settings,
                    estimates = estimates,
                    modes = settings.modes,
                    modeLabels = modeLabels,
                )
            }

            if (expanded) {
                SectionLabel(stringResource(R.string.rhythm_derive_timing))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(RhythmTimingVariantKey.TRACKED, RhythmTimingVariantKey.CLASSIC)
                        .forEach { key ->
                            FilterChip(
                                selected = settings.timingVariant == key,
                                enabled = timingAvailable,
                                onClick = { settings = settings.copy(timingVariant = key) },
                                label = {
                                    Text(
                                        timingLabels.getValue(key) +
                                            if (classicApproximate && key == RhythmTimingVariantKey.CLASSIC) {
                                                " · $approxShort"
                                            } else {
                                                ""
                                            },
                                    )
                                },
                            )
                            InfoButton(onClick = { infoTiming = key })
                        }
                }
                if (classicApproximate) {
                    Text(
                        text = stringResource(R.string.rhythm_derive_timing_approx),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                SectionLabel(stringResource(R.string.rhythm_derive_mode))
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    RhythmDeriveMode.entries.forEach { mode ->
                        FilterChip(
                            selected = mode in settings.modes,
                            onClick = {
                                settings = settings.copy(
                                    modes = if (mode in settings.modes) {
                                        settings.modes - mode
                                    } else {
                                        settings.modes + mode
                                    },
                                )
                            },
                            label = { Text(modeLabels.getValue(mode)) },
                        )
                        InfoButton(onClick = { infoMode = mode })
                    }
                }
                Text(
                    text = stringResource(R.string.rhythm_derive_mode_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (settings.modes.isEmpty()) {
                    Text(
                        text = stringResource(R.string.rhythm_derive_modes_none),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                if (RhythmDeriveMode.ONSET in settings.modes) {
                    SectionLabel(stringResource(R.string.rhythm_derive_selection))
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OnsetSelection.entries.forEach { option ->
                            FilterChip(
                                selected = option == settings.selection,
                                onClick = { settings = settings.copy(selection = option) },
                                label = { Text(selectionLabels.getValue(option)) },
                            )
                            InfoButton(onClick = { infoSelection = option })
                        }
                    }
                    Text(
                        text = stringResource(R.string.rhythm_derive_selection_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                SectionLabel(stringResource(R.string.rhythm_generate_difficulty))
                DspDifficulty.entries.forEach { difficulty ->
                    DifficultyRow(
                        difficulty = difficulty,
                        checked = difficulty in settings.difficulties,
                        estimateText = estimateLabel(difficulty, estimates, settings.modes, modeLabels),
                        onToggle = {
                            settings = settings.copy(
                                difficulties = if (difficulty in settings.difficulties) {
                                    settings.difficulties - difficulty
                                } else {
                                    settings.difficulties + difficulty
                                },
                            )
                        },
                    )
                }
                if (settings.difficulties.isEmpty()) {
                    Text(
                        text = stringResource(R.string.rhythm_derive_none_selected),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                SectionLabel(stringResource(R.string.rhythm_generate_loudness))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LOUDNESS_CHOICES.forEach { (value, label) ->
                        FilterChip(
                            selected = settings.minRmsPercentile == value,
                            enabled = loudnessAvailable,
                            onClick = {
                                settings = settings.copy(minRmsPercentile = value)
                            },
                            label = { Text(stringResource(label)) },
                        )
                    }
                }
                if (!loudnessAvailable) {
                    Text(
                        text = stringResource(R.string.rhythm_derive_loudness_missing),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                SectionLabel(stringResource(R.string.rhythm_generate_density))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DENSITY_CHOICES.forEach { (value, label) ->
                        FilterChip(
                            selected = settings.maxLocalNps == value,
                            onClick = { settings = settings.copy(maxLocalNps = value) },
                            label = { Text(stringResource(label)) },
                        )
                    }
                }

                SectionLabel(stringResource(R.string.rhythm_derive_lane))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = settings.laneStrategy == LaneStrategy.BAND_ORDER,
                        onClick = {
                            settings = settings.copy(laneStrategy = LaneStrategy.BAND_ORDER)
                        },
                        label = { Text(laneLabels.getValue(LaneStrategy.BAND_ORDER)) },
                    )
                    FilterChip(
                        selected = settings.laneStrategy == LaneStrategy.LOW_OUTSIDE,
                        onClick = {
                            settings = settings.copy(laneStrategy = LaneStrategy.LOW_OUTSIDE)
                        },
                        label = { Text(laneLabels.getValue(LaneStrategy.LOW_OUTSIDE)) },
                    )
                }
                Text(
                    text = stringResource(R.string.rhythm_derive_lane_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                SectionLabel(stringResource(R.string.rhythm_derive_chord))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (size in 1..RhythmChartDeriver.MAX_CHORD_NOTES) {
                        FilterChip(
                            selected = settings.maxChord == size,
                            onClick = { settings = settings.copy(maxChord = size) },
                            label = { Text(stringResource(chordLabel(size))) },
                        )
                    }
                }
                Text(
                    text = stringResource(R.string.rhythm_derive_chord_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                if (matchedPreset != null) {
                    TextButton(onClick = { expanded = false }) {
                        Text(stringResource(R.string.rhythm_derive_profile_collapse))
                    }
                }
            }

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
                    // The derived list shows the newest chart first, so derive from hardest
                    // to easiest: the easiest chart then lands at the top.
                    onClick = { onDerive(settings) },
                    enabled = settings.difficulties.isNotEmpty() &&
                        settings.modes.isNotEmpty() &&
                        !deriving,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.rhythm_derive_start))
                }
            }
        }
    }

    infoPreset?.let { preset ->
        DeriveInfoDialog(
            title = profileLabels.getValue(preset),
            body = when (preset) {
                RhythmDerivePreset.CLASSIC_043 ->
                    stringResource(R.string.rhythm_derive_info_profile_classic_043)
                RhythmDerivePreset.RECOMMENDED ->
                    stringResource(R.string.rhythm_derive_info_profile_recommended)
            },
            onDismiss = { infoPreset = null },
        )
    }
    infoTiming?.let { key ->
        DeriveInfoDialog(
            title = timingLabels[key] ?: key,
            body = when (key) {
                RhythmTimingVariantKey.CLASSIC -> stringResource(R.string.rhythm_derive_info_timing_classic)
                else -> stringResource(R.string.rhythm_derive_info_timing_tracked)
            },
            onDismiss = { infoTiming = null },
        )
    }
    infoMode?.let { mode ->
        DeriveInfoDialog(
            title = modeLabels.getValue(mode),
            body = when (mode) {
                RhythmDeriveMode.GRID -> stringResource(R.string.rhythm_derive_info_grid)
                RhythmDeriveMode.ONSET -> stringResource(R.string.rhythm_derive_info_onset)
                RhythmDeriveMode.CLASSIC -> stringResource(R.string.rhythm_derive_info_classic)
            },
            onDismiss = { infoMode = null },
        )
    }
    infoSelection?.let { option ->
        DeriveInfoDialog(
            title = selectionLabels.getValue(option),
            body = when (option) {
                OnsetSelection.BALANCED -> stringResource(R.string.rhythm_derive_info_balanced)
                OnsetSelection.MELODY_FIRST -> stringResource(R.string.rhythm_derive_info_melody)
            },
            onDismiss = { infoSelection = null },
        )
    }
}

@Composable
private fun profileSummary(
    settings: RhythmDeriveSettings,
    modeLabels: Map<RhythmDeriveMode, String>,
    selectionLabels: Map<OnsetSelection, String>,
    timingLabels: Map<String, String>,
    laneLabels: Map<LaneStrategy, String>,
    loudnessLabels: Map<Int?, String>,
    densityLabels: Map<Double?, String>,
    classicApproximate: Boolean,
    approxShort: String,
): String {
    val modes = settings.modes.sorted().joinToString(" / ") { modeLabels.getValue(it) }
    val selection = if (RhythmDeriveMode.ONSET in settings.modes) {
        selectionLabels.getValue(settings.selection)
    } else {
        stringResource(R.string.rhythm_derive_profile_summary_none)
    }
    val difficulties = if (settings.difficulties.size == DspDifficulty.entries.size) {
        stringResource(R.string.rhythm_derive_profile_summary_all)
    } else {
        val names = ArrayList<String>()
        for (difficulty in DspDifficulty.entries) {
            if (difficulty in settings.difficulties) names += difficulty.localizedName()
        }
        names.joinToString(", ")
    }
    return stringResource(
        R.string.rhythm_derive_profile_summary_format,
        (timingLabels[settings.timingVariant] ?: settings.timingVariant) +
            if (settings.timingVariant == RhythmTimingVariantKey.CLASSIC && classicApproximate) {
                " · $approxShort"
            } else {
                ""
            },
        modes,
        selection,
        laneLabels.getValue(settings.laneStrategy),
        stringResource(chordLabel(settings.maxChord)),
        loudnessLabels[settings.minRmsPercentile] ?: "",
        densityLabels[settings.maxLocalNps] ?: "",
        difficulties,
    )
}

@Composable
private fun EstimatesColumn(
    settings: RhythmDeriveSettings,
    estimates: Map<Pair<DspDifficulty, RhythmDeriveMode>, Int>?,
    modes: Set<RhythmDeriveMode>,
    modeLabels: Map<RhythmDeriveMode, String>,
) {
    Column {
        for (difficulty in DspDifficulty.entries) {
            if (difficulty !in settings.difficulties) continue
            Text(
                text = "${difficulty.localizedName()} · ${
                    estimateLabel(difficulty, estimates, modes, modeLabels)
                }",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** The circled-i button that sits after a chart-generation name and opens its description. */
@Composable
private fun InfoButton(onClick: () -> Unit) {
    Icon(
        imageVector = Icons.Outlined.Info,
        contentDescription = stringResource(R.string.rhythm_derive_info_button),
        tint = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .padding(6.dp)
            .size(20.dp),
    )
}

@Composable
private fun DeriveInfoDialog(
    title: String,
    body: String,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.verticalScroll(rememberScrollState()),
            )
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.rhythm_derive_info_close))
            }
        },
    )
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
private fun estimateLabel(
    difficulty: DspDifficulty,
    estimates: Map<Pair<DspDifficulty, RhythmDeriveMode>, Int>?,
    modes: Set<RhythmDeriveMode>,
    modeLabels: Map<RhythmDeriveMode, String>,
): String {
    val current = estimates ?: return ""
    if (modes.isEmpty()) return ""
    if (modes.size == 1) {
        return current[difficulty to modes.first()]
            ?.let { stringResource(R.string.rhythm_derive_estimate, it) }
            .orEmpty()
    }
    return RhythmDeriveMode.entries
        .filter { it in modes }
        .mapNotNull { mode ->
            current[difficulty to mode]?.let { "${modeLabels.getValue(mode)} $it" }
        }
        .joinToString(" · ")
}

@Composable
private fun DifficultyRow(
    difficulty: DspDifficulty,
    checked: Boolean,
    estimateText: String,
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
            text = estimateText,
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
