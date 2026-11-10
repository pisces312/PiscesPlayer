/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Original work: import wizard UI rewritten for this codebase (English strings, Material 3).
 */
package com.pisces312.piscesplayer.presentation.screens.import

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.pisces312.piscesplayer.R
import com.pisces312.piscesplayer.data.importer.ImportOptions
import com.pisces312.piscesplayer.data.importer.ImportProgress
import com.pisces312.piscesplayer.data.importer.ImportResult
import com.pisces312.piscesplayer.presentation.viewmodel.ImportViewModel
import kotlin.math.roundToInt

/**
 * Poweramp 导入向导：解析 → 预览 → 选项 → 导入 → 结果。
 * 由调用方通过文件选择器拿到 URI 后交给 [ImportViewModel.onFileSelected] 启动。
 */
@Composable
fun PowerampImportFlow(
    viewModel: ImportViewModel = hiltViewModel(),
    onDismiss: () -> Unit
) {
    val state by viewModel.uiState.collectAsState()

    when (state.step) {
        ImportViewModel.Step.IDLE -> Unit
        ImportViewModel.Step.PARSING -> ParsingDialog()
        ImportViewModel.Step.PREVIEW -> {
            val prepared = state.prepared ?: return
            PreviewDialog(
                preview = prepared.preview,
                impact = state.impact,
                unresolvedCount = prepared.data.songRecords.size - prepared.preview.matchedEstimate,
                onNext = viewModel::confirmPreview,
                onDismiss = onDismiss
            )
        }

        ImportViewModel.Step.OPTIONS -> {
            val prepared = state.prepared ?: return
            OptionsDialog(
                options = state.options,
                favoritesForThreshold = { viewModel.favoritesCountForThreshold(it) },
                onOptionsChange = viewModel::updateOptions,
                onStart = viewModel::startImport,
                onBack = viewModel::backToPreview,
                onDismiss = onDismiss
            )
        }

        ImportViewModel.Step.IMPORTING -> ImportingDialog(
            progress = state.progress,
            onCancel = viewModel::cancelImport
        )

        ImportViewModel.Step.RESULT -> ResultDialog(
            result = state.result,
            onDone = onDismiss
        )

        ImportViewModel.Step.ERROR -> ErrorDialog(
            message = state.errorMessage,
            onDismiss = onDismiss
        )
    }
}

@Composable
private fun ParsingDialog() {
    AlertDialog(
        onDismissRequest = {},
        title = { Text(stringResource(R.string.import_parsing_title)) },
        text = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                CircularProgressIndicator()
                Text(stringResource(R.string.import_parsing_body))
            }
        },
        confirmButton = {}
    )
}

@Composable
private fun PreviewDialog(
    preview: com.pisces312.piscesplayer.data.importer.ImportPreview,
    impact: com.pisces312.piscesplayer.data.importer.PowerampBackupImporter.ImportImpact?,
    unresolvedCount: Int,
    onNext: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.import_preview_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                StatRow(stringResource(R.string.import_preview_songs), preview.songCount)
                StatRow(stringResource(R.string.import_preview_playlists), preview.playlistCount)
                StatRow(stringResource(R.string.import_preview_matched), preview.matchedEstimate)
                StatRow(
                    stringResource(R.string.import_preview_match_rate),
                    "${(preview.matchRate * 100).roundToInt()}%"
                )
                StatRow(stringResource(R.string.import_preview_rated), preview.ratedCount)
                StatRow(stringResource(R.string.import_preview_played), preview.playedCount)
                if (impact != null) {
                    Text(
                        text = "Currently in library: ${impact.currentEngagement} tracked songs, " +
                            "${impact.currentFavorites} favorites, ${impact.currentPlaylists} playlists",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
                if (unresolvedCount > 0) {
                    Text(
                        text = stringResource(R.string.import_preview_unresolved_hint, unresolvedCount),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onNext) { Text(stringResource(R.string.import_wizard_next)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.import_wizard_cancel)) }
        }
    )
}

@Composable
private fun OptionsDialog(
    options: ImportOptions,
    favoritesForThreshold: (Int) -> Int,
    onOptionsChange: (ImportOptions) -> Unit,
    onStart: () -> Unit,
    onBack: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.import_options_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                OptionSwitch(
                    label = stringResource(R.string.import_options_mode_replace),
                    checked = options.replaceMode,
                    onCheckedChange = { onOptionsChange(options.copy(replaceMode = it)) }
                )
                Text(
                    text = if (options.replaceMode) {
                        stringResource(R.string.import_options_mode_replace_desc)
                    } else {
                        stringResource(R.string.import_options_mode_merge_desc)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, bottom = 8.dp)
                )

                OptionSwitch(
                    label = stringResource(R.string.import_options_playlists),
                    checked = options.importPlaylists,
                    onCheckedChange = { onOptionsChange(options.copy(importPlaylists = it)) }
                )
                OptionSwitch(
                    label = stringResource(R.string.import_options_history),
                    checked = options.importHistory,
                    onCheckedChange = { onOptionsChange(options.copy(importHistory = it)) }
                )
                OptionSwitch(
                    label = stringResource(R.string.import_options_engagement),
                    checked = options.importEngagement,
                    onCheckedChange = { onOptionsChange(options.copy(importEngagement = it)) }
                )
                OptionSwitch(
                    label = stringResource(R.string.import_options_favorites),
                    checked = options.importFavorites,
                    onCheckedChange = { onOptionsChange(options.copy(importFavorites = it)) }
                )

                if (options.importFavorites) {
                    val threshold = options.favoriteRatingThreshold
                    Text(
                        text = stringResource(R.string.import_options_threshold),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 12.dp)
                    )
                    Slider(
                        value = threshold.toFloat(),
                        onValueChange = {
                            onOptionsChange(options.copy(favoriteRatingThreshold = it.roundToInt().coerceIn(1, 5)))
                        },
                        valueRange = 1f..5f,
                        steps = 3
                    )
                    Text(
                        text = stringResource(
                            R.string.import_options_threshold_desc,
                            threshold,
                            favoritesForThreshold(threshold)
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onStart) { Text(stringResource(R.string.import_wizard_start)) }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onBack) { Text(stringResource(R.string.import_wizard_back)) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.import_wizard_cancel)) }
            }
        }
    )
}

@Composable
private fun OptionSwitch(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun ImportingDialog(
    progress: ImportProgress?,
    onCancel: () -> Unit
) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text(stringResource(R.string.import_importing_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (progress == null || progress.total <= 1) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(
                        progress = { progress.current.toFloat() / progress.total.toFloat() },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                Text(
                    text = progress?.let { stepLabel(it.step) }
                        ?: stringResource(R.string.import_step_matching),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text(stringResource(R.string.import_wizard_cancel)) }
        },
        confirmButton = {}
    )
}

@Composable
private fun stepLabel(step: ImportProgress.Step): String = when (step) {
    ImportProgress.Step.PARSING -> stringResource(R.string.import_step_parsing)
    ImportProgress.Step.MATCHING -> stringResource(R.string.import_step_matching)
    ImportProgress.Step.PLAYLISTS -> stringResource(R.string.import_step_playlists)
    ImportProgress.Step.HISTORY -> stringResource(R.string.import_step_history)
    ImportProgress.Step.ENGAGEMENT -> stringResource(R.string.import_step_engagement)
    ImportProgress.Step.FAVORITES -> stringResource(R.string.import_step_favorites)
}

@Composable
private fun ResultDialog(
    result: ImportResult?,
    onDone: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text(stringResource(R.string.import_result_title)) },
        text = {
            if (result == null) return@AlertDialog
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                StatRow(stringResource(R.string.import_result_matched), result.matchedSongs)
                StatRow(stringResource(R.string.import_result_unresolved), result.unresolvedSongs)
                StatRow(stringResource(R.string.import_result_playlists_created), result.playlistsCreated)
                StatRow(stringResource(R.string.import_result_playlists_merged), result.playlistsMerged)
                StatRow(stringResource(R.string.import_result_history), result.historyEventsImported)
                StatRow(stringResource(R.string.import_result_engagement), result.engagementImported)
                StatRow(stringResource(R.string.import_result_favorites), result.favoritesImported)
                StatRow(stringResource(R.string.import_result_ratings), result.ratingsSaved)
                if (result.skippedEmptyPlaylists > 0) {
                    StatRow(
                        stringResource(R.string.import_result_skipped_empty),
                        result.skippedEmptyPlaylists
                    )
                }
                if (result.unresolvedExamples.isNotEmpty()) {
                    Text(
                        text = stringResource(
                            R.string.import_result_unresolved_examples,
                            result.unresolvedExamples.joinToString(", ")
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDone) { Text(stringResource(R.string.import_wizard_done)) }
        }
    )
}

@Composable
private fun ErrorDialog(
    message: String?,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.import_error_title)) },
        text = {
            Text(
                text = message ?: stringResource(R.string.import_error_title),
                style = MaterialTheme.typography.bodyMedium
            )
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.import_wizard_done)) }
        }
    )
}

@Composable
private fun StatRow(label: String, value: Int) {
    StatRow(label = label, value = value.toString())
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(text = value, style = MaterialTheme.typography.bodyMedium)
    }
}
