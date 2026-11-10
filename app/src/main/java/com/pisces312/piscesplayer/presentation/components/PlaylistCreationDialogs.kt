@file:OptIn(
    ExperimentalLayoutApi::class,
    ExperimentalMaterial3Api::class,
    ExperimentalMaterial3ExpressiveApi::class
)

package com.pisces312.piscesplayer.presentation.components

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FlexibleBottomAppBar
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LargeExtendedFloatingActionButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumExtendedFloatingActionButton
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenu
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.pisces312.piscesplayer.data.ai.AiLibrarySampleMode
import com.pisces312.piscesplayer.presentation.viewmodel.NlpPlaylistPreviewState
import com.pisces312.piscesplayer.ui.theme.RoundedSans
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.max
import kotlin.math.min
import racra.compose.smooth_corner_rect_library.AbsoluteSmoothCornerShape
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.pisces312.piscesplayer.R
import androidx.compose.ui.text.style.TextOverflow

@Composable
fun PlaylistCreationTypeDialog(
    visible: Boolean,
    onDismiss: () -> Unit,
    onManualSelected: () -> Unit,
    onDescribeSelected: () -> Unit
) {
    if (!visible) return

    val dialogShape = AbsoluteSmoothCornerShape(
        cornerRadiusTL = 24.dp,
        smoothnessAsPercentTL = 60,
        cornerRadiusTR = 24.dp,
        smoothnessAsPercentTR = 60,
        cornerRadiusBL = 40.dp,
        smoothnessAsPercentBL = 60,
        cornerRadiusBR = 40.dp,
        smoothnessAsPercentBR = 60
    )

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = true)
    ) {
        Surface(
            shape = dialogShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = stringResource(R.string.presentation_batch_e_create_playlist_title),
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.headlineSmall.copy(
                            fontFamily = RoundedSans,
                            fontWeight = FontWeight.Bold
                        )
                    )
                    Text(
                        text = stringResource(R.string.presentation_batch_e_create_playlist_subtitle),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f)
                    )
                }

                CreationModeCard(
                    title = stringResource(R.string.presentation_batch_e_creation_mode_manual),
                    subtitle = stringResource(R.string.presentation_batch_e_creation_mode_manual_subtitle),
                    icon = {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.PlaylistAdd,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    },
                    onClick = onManualSelected,
                    enabled = true,
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )

                CreationModeCard(
                    title = stringResource(R.string.presentation_batch_e_creation_mode_describe),
                    subtitle = stringResource(R.string.presentation_batch_e_creation_mode_describe_subtitle),
                    icon = {
                        Icon(
                            imageVector = Icons.Rounded.AutoAwesome,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    },
                    onClick = onDescribeSelected,
                    enabled = true,
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
        }
    }
}

/**
 * Controls over which slice of the library the model is shown.
 *
 * Only the AI flow needs this; the offline engine reads the whole library and has no such knob, so
 * it passes null and the pickers are hidden.
 */
data class SampleConfig(
    val modes: List<AiLibrarySampleMode>,
    val mode: AiLibrarySampleMode,
    val sizes: List<Int>,
    val size: Int,
    val onModeChange: (AiLibrarySampleMode) -> Unit,
    val onSizeChange: (Int) -> Unit
)

/**
 * "Describe it" creation mode: the user types a natural-language description
 * ("songs to lift weights to"), the offline NLP engine ranks the library against it,
 * and the matched songs are previewed before being saved as a regular playlist.
 * Fully offline — no network, no API keys.
 */
@Composable
fun DescribePlaylistDialog(
    visible: Boolean,
    state: NlpPlaylistPreviewState,
    onGenerate: (String) -> Unit,
    onSave: (name: String, songIds: List<String>) -> Unit,
    onDismiss: () -> Unit,
    title: String = stringResource(R.string.presentation_batch_e_describe_playlist_title),
    subtitle: String = stringResource(R.string.presentation_batch_e_describe_playlist_subtitle),
    sampleConfig: SampleConfig? = null
) {
    if (!visible) return

    var description by rememberSaveable { mutableStateOf("") }
    var playlistName by rememberSaveable { mutableStateOf("") }

    // A finished generation gets a fresh default name; the user can still overwrite it.
    LaunchedEffect(state) {
        if (state.hasResult && state.songs.isNotEmpty()) {
            playlistName = timestampName()
        }
    }

    val dialogShape = AbsoluteSmoothCornerShape(
        cornerRadiusTL = 24.dp,
        smoothnessAsPercentTL = 60,
        cornerRadiusTR = 24.dp,
        smoothnessAsPercentTR = 60,
        cornerRadiusBL = 40.dp,
        smoothnessAsPercentBL = 60,
        cornerRadiusBR = 40.dp,
        smoothnessAsPercentBR = 60
    )

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = true)
    ) {
        Surface(
            shape = dialogShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
                    .animateContentSize(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = title,
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.headlineSmall.copy(
                            fontFamily = RoundedSans,
                            fontWeight = FontWeight.Bold
                        )
                    )
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f)
                    )
                }

                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !state.isGenerating,
                    minLines = 2,
                    placeholder = {
                        Text(text = stringResource(R.string.presentation_batch_e_describe_playlist_hint))
                    }
                )

                if (sampleConfig != null) {
                    SampleModeDropdown(
                            modes = sampleConfig.modes,
                            selected = sampleConfig.mode,
                            enabled = !state.isGenerating,
                            onSelect = sampleConfig.onModeChange
                    )
                    SampleSizeDropdown(
                            options = sampleConfig.sizes,
                            selected = sampleConfig.size,
                            enabled = !state.isGenerating,
                            onSelect = sampleConfig.onSizeChange
                    )
                }

                FilledTonalButton(
                    onClick = { onGenerate(description.trim()) },
                    enabled = description.isNotBlank() && !state.isGenerating,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (state.isGenerating) {
                        LoadingIndicator(modifier = Modifier.size(24.dp))
                    } else {
                        Text(
                            text = stringResource(
                                if (state.hasResult) {
                                    R.string.presentation_batch_e_describe_regenerate
                                } else {
                                    R.string.presentation_batch_e_describe_generate
                                }
                            )
                        )
                    }
                }

                if (state.hasResult && !state.isGenerating) {
                    if (state.errorMessage != null) {
                        Text(
                            text = state.errorMessage,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else if (state.songs.isEmpty()) {
                        Text(
                            text = stringResource(R.string.presentation_batch_e_describe_no_matches),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        OutlinedTextField(
                            value = playlistName,
                            onValueChange = { playlistName = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(stringResource(R.string.ai_playlist_name_label)) },
                            singleLine = true
                        )
                        Text(
                            text = pluralStringResource(
                                R.plurals.presentation_batch_e_describe_result_count,
                                state.songs.size,
                                state.songs.size
                            ),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 220.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(state.songs, key = { it.id }) { song ->
                                Column(modifier = Modifier.fillMaxWidth()) {
                                    Text(
                                        text = song.title,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = song.displayArtist,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(text = stringResource(R.string.cancel))
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    FilledTonalButton(
                        onClick = {
                            onSave(playlistName.trim().ifBlank { timestampName() }, state.songs.map { it.id })
                        },
                        enabled = state.songs.isNotEmpty() && !state.isGenerating && playlistName.isNotBlank()
                    ) {
                        Text(text = stringResource(R.string.presentation_batch_e_describe_save))
                    }
                }
            }
        }
    }
}

@Composable
private fun CreationModeCard(
    title: String,
    subtitle: String,
    icon: @Composable () -> Unit,
    onClick: () -> Unit,
    enabled: Boolean,
    containerColor: Color,
    contentColor: Color
) {
    Card(
        onClick = onClick,
        enabled = enabled,
        colors = CardDefaults.cardColors(containerColor = containerColor),
        shape = AbsoluteSmoothCornerShape(
            cornerRadiusTL = 22.dp,
            smoothnessAsPercentTL = 60,
            cornerRadiusTR = 22.dp,
            smoothnessAsPercentTR = 60,
            cornerRadiusBL = 22.dp,
            smoothnessAsPercentBL = 60,
            cornerRadiusBR = 22.dp,
            smoothnessAsPercentBR = 60
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                color = contentColor.copy(alpha = 0.16f),
                shape = AbsoluteSmoothCornerShape(
                    cornerRadiusTL = 12.dp,
                    smoothnessAsPercentTL = 60,
                    cornerRadiusTR = 18.dp,
                    smoothnessAsPercentTR = 60,
                    cornerRadiusBL = 18.dp,
                    smoothnessAsPercentBL = 60,
                    cornerRadiusBR = 12.dp,
                    smoothnessAsPercentBR = 60
                )
            ) {
                Row(modifier = Modifier.padding(10.dp)) {
                    icon()
                }
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = contentColor
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = contentColor.copy(alpha = 0.8f)
                )
            }
        }
    }
}

/**
 * Picks how many song titles are sent to the model as context.
 *
 * Only shown for the AI flow: the offline engine reads the whole library anyway, so it has no
 * such knob.
 */
@Composable
fun SampleSizeDropdown(
    options: List<Int>,
    selected: Int,
    enabled: Boolean,
    onSelect: (Int) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
                value = stringResource(R.string.ai_playlist_sample_size_value, selected),
                onValueChange = {},
                readOnly = true,
                enabled = enabled,
                label = { Text(stringResource(R.string.ai_playlist_sample_size_label)) },
                supportingText = { Text(stringResource(R.string.ai_playlist_sample_size_hint)) },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier =
                        Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                                .fillMaxWidth(),
                singleLine = true
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { size ->
                DropdownMenuItem(
                        text = {
                            Text(stringResource(R.string.ai_playlist_sample_size_value, size))
                        },
                        onClick = {
                            onSelect(size)
                            expanded = false
                        }
                )
            }
        }
    }
}

/**
 * Picks how the library slice is chosen: the most played titles (stable, so repeated prompts hit
 * the response cache) or a fresh shuffle (every song stays reachable).
 */
@Composable
fun SampleModeDropdown(
    modes: List<AiLibrarySampleMode>,
    selected: AiLibrarySampleMode,
    enabled: Boolean,
    onSelect: (AiLibrarySampleMode) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
                value = stringResource(sampleModeLabel(selected)),
                onValueChange = {},
                readOnly = true,
                enabled = enabled,
                label = { Text(stringResource(R.string.ai_playlist_sample_mode_label)) },
                supportingText = { Text(stringResource(sampleModeHint(selected))) },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier =
                        Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                                .fillMaxWidth(),
                singleLine = true
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            modes.forEach { mode ->
                DropdownMenuItem(
                        text = { Text(stringResource(sampleModeLabel(mode))) },
                        onClick = {
                            onSelect(mode)
                            expanded = false
                        }
                )
            }
        }
    }
}

@StringRes
private fun sampleModeLabel(mode: AiLibrarySampleMode): Int =
        when (mode) {
            AiLibrarySampleMode.MOST_PLAYED -> R.string.ai_sample_mode_most_played
            AiLibrarySampleMode.RANDOM -> R.string.ai_sample_mode_random
        }

@StringRes
private fun sampleModeHint(mode: AiLibrarySampleMode): Int =
        when (mode) {
            AiLibrarySampleMode.MOST_PLAYED -> R.string.ai_sample_mode_most_played_hint
            AiLibrarySampleMode.RANDOM -> R.string.ai_sample_mode_random_hint
        }

/** Default playlist name: when the list was generated. Editable before saving. */
private fun timestampName(): String =
        LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmm"))
