package com.pisces312.piscesplayer.presentation.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.pisces312.piscesplayer.R
import com.pisces312.piscesplayer.data.metadata.MetadataApplyTarget
import com.pisces312.piscesplayer.data.metadata.MetadataCandidate
import com.pisces312.piscesplayer.data.metadata.MetadataFieldSelection
import com.pisces312.piscesplayer.data.metadata.MetadataMergePolicy
import com.pisces312.piscesplayer.data.metadata.MetadataQueryBuilder
import com.pisces312.piscesplayer.presentation.viewmodel.MetadataField
import com.pisces312.piscesplayer.presentation.viewmodel.MetadataMatchError
import com.pisces312.piscesplayer.presentation.viewmodel.MetadataMatchPhase
import com.pisces312.piscesplayer.presentation.viewmodel.MetadataMatchUiState
import com.pisces312.piscesplayer.utils.formatDuration
import com.pisces312.piscesplayer.utils.formatReleaseDate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MetadataMatchSheet(
    state: MetadataMatchUiState,
    onDismiss: () -> Unit,
    onUpdateQuery: (title: String, artist: String, album: String) -> Unit,
    onUseFileTags: () -> Unit,
    onUseFileName: () -> Unit,
    onSearch: () -> Unit,
    onSelectCandidate: (MetadataCandidate) -> Unit,
    onBackToResults: () -> Unit,
    onFieldSelected: (MetadataField, Boolean) -> Unit,
    onFillBlankOnlyChange: (Boolean) -> Unit,
    onApplyTargetChange: (MetadataApplyTarget) -> Unit,
    onApply: () -> Unit,
    onDismissError: () -> Unit,
) {
    if (!state.isVisible) return

    Dialog(
        onDismissRequest = { if (!state.isApplying) onDismiss() },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .imePadding()
            ) {
                MetadataMatchHeader(
                    state = state,
                    onDismiss = onDismiss,
                    onBackToResults = onBackToResults
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    when (state.phase) {
                        MetadataMatchPhase.QUERY -> QueryContent(
                            state = state,
                            onUpdateQuery = onUpdateQuery,
                            onUseFileTags = onUseFileTags,
                            onUseFileName = onUseFileName,
                            onSearch = onSearch,
                            onSelectCandidate = onSelectCandidate,
                            onDismissError = onDismissError
                        )

                        MetadataMatchPhase.PREVIEW -> PreviewContent(
                            state = state,
                            onFieldSelected = onFieldSelected,
                            onFillBlankOnlyChange = onFillBlankOnlyChange,
                            onApplyTargetChange = onApplyTargetChange,
                            onDismissError = onDismissError
                        )

                        MetadataMatchPhase.APPLIED -> AppliedContent(
                            state = state,
                            onDone = onDismiss
                        )

                        MetadataMatchPhase.HIDDEN -> Unit
                    }
                }

                if (state.phase == MetadataMatchPhase.PREVIEW) {
                    ApplyBar(state = state, onApply = onApply)
                }
            }
        }
    }
}

@Composable
private fun MetadataMatchHeader(
    state: MetadataMatchUiState,
    onDismiss: () -> Unit,
    onBackToResults: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (state.phase == MetadataMatchPhase.PREVIEW) {
            IconButton(
                onClick = onBackToResults,
                enabled = !state.isApplying
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = stringResource(R.string.metadata_match_back)
                )
            }
        } else {
            Spacer(modifier = Modifier.width(8.dp))
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                modifier = Modifier.padding(horizontal = 8.dp),
                text = stringResource(
                    when (state.phase) {
                        MetadataMatchPhase.QUERY -> R.string.metadata_match_title
                        MetadataMatchPhase.PREVIEW -> R.string.metadata_match_review_title
                        MetadataMatchPhase.APPLIED -> R.string.metadata_match_applied_title
                        MetadataMatchPhase.HIDDEN -> R.string.metadata_match_title
                    }
                ),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )
            if (state.phase == MetadataMatchPhase.QUERY) {
                Text(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    text = stringResource(R.string.metadata_match_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        IconButton(
            onClick = onDismiss,
            enabled = !state.isApplying
        ) {
            Icon(
                imageVector = Icons.Rounded.Close,
                contentDescription = stringResource(R.string.metadata_match_close)
            )
        }
    }
}

@Composable
private fun QueryContent(
    state: MetadataMatchUiState,
    onUpdateQuery: (title: String, artist: String, album: String) -> Unit,
    onUseFileTags: () -> Unit,
    onUseFileName: () -> Unit,
    onSearch: () -> Unit,
    onSelectCandidate: (MetadataCandidate) -> Unit,
    onDismissError: () -> Unit,
) {
    val query = state.query
    val queryExpression = MetadataQueryBuilder.searchAttempts(query)
        .firstOrNull()
        ?.let(MetadataQueryBuilder::displayQuery)

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text(
                text = stringResource(R.string.metadata_match_query_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        item {
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = query.title,
                onValueChange = { onUpdateQuery(it, query.artist, query.album.orEmpty()) },
                label = { Text(stringResource(R.string.song_field_title)) },
                singleLine = true,
                enabled = !state.isSearching
            )
        }

        item {
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = query.artist,
                onValueChange = { onUpdateQuery(query.title, it, query.album.orEmpty()) },
                label = { Text(stringResource(R.string.song_field_artist)) },
                singleLine = true,
                enabled = !state.isSearching
            )
        }

        item {
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = query.album.orEmpty(),
                onValueChange = { onUpdateQuery(query.title, query.artist, it) },
                label = { Text(stringResource(R.string.song_field_album)) },
                singleLine = true,
                enabled = !state.isSearching
            )
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AssistChip(
                    modifier = Modifier.weight(1f),
                    onClick = onUseFileTags,
                    enabled = !state.isSearching,
                    label = { Text(stringResource(R.string.metadata_match_use_file_tags)) }
                )
                AssistChip(
                    modifier = Modifier.weight(1f),
                    onClick = onUseFileName,
                    enabled = !state.isSearching,
                    label = { Text(stringResource(R.string.metadata_match_use_file_name)) }
                )
            }
        }

        if (queryExpression != null) {
            item {
                Text(
                    text = stringResource(
                        R.string.metadata_match_query_expression,
                        queryExpression
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        item {
            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = onSearch,
                enabled = query.title.isNotBlank() && !state.isSearching
            ) {
                Icon(Icons.Rounded.Search, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.metadata_match_search))
            }
        }

        if (state.error != MetadataMatchError.NONE) {
            item {
                MetadataErrorBanner(
                    error = state.error,
                    message = state.errorMessage,
                    onDismiss = onDismissError
                )
            }
        }

        if (state.isSearching) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text(
                        text = stringResource(R.string.metadata_match_searching),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }

        if (state.searchedTitleOnly) {
            item {
                Text(
                    text = stringResource(R.string.metadata_match_title_only_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (state.candidates.isNotEmpty()) {
            item {
                Text(
                    text = stringResource(R.string.metadata_match_results_heading, state.candidates.size),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
            }
            items(state.candidates) { candidate ->
                CandidateRow(
                    candidate = candidate,
                    onClick = { onSelectCandidate(candidate) }
                )
            }
        } else if (state.hasSearched && !state.isSearching &&
            state.error != MetadataMatchError.SEARCH_FAILED
        ) {
            item {
                Text(
                    text = stringResource(R.string.metadata_match_no_results),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        item { Spacer(modifier = Modifier.height(24.dp)) }
    }
}

@Composable
private fun CandidateRow(
    candidate: MetadataCandidate,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(20.dp)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SmartImage(
                model = candidate.coverArtUrl,
                contentDescription = null,
                modifier = Modifier.size(56.dp),
                shape = RoundedCornerShape(12.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = candidate.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = listOfNotNull(
                        candidate.artist.takeIf { it.isNotBlank() },
                        candidate.album.takeIf { it.isNotBlank() },
                        candidate.year.takeIf { it > 0 }?.toString(),
                        candidate.durationMs?.takeIf { it > 0 }?.let(::formatDuration)
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = stringResource(R.string.metadata_match_result_score, candidate.score),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    listOfNotNull(candidate.releaseStatus, candidate.releaseType)
                        .joinToString(" · ")
                        .takeIf { it.isNotBlank() }
                        ?.let { releaseInfo ->
                            Text(
                                text = releaseInfo,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PreviewContent(
    state: MetadataMatchUiState,
    onFieldSelected: (MetadataField, Boolean) -> Unit,
    onFillBlankOnlyChange: (Boolean) -> Unit,
    onApplyTargetChange: (MetadataApplyTarget) -> Unit,
    onDismissError: () -> Unit,
) {
    val song = state.song ?: return
    val candidate = state.selectedCandidate ?: return
    val selection = state.selection
    val resolvedDate = MetadataMergePolicy.resolvedReleaseDate(candidate)
    val localDatabaseOnly = state.applyTarget == MetadataApplyTarget.DATABASE_ONLY &&
        !state.isCloudSong

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            CandidateSummaryCard(candidate = candidate)
        }

        if (state.isLoadingDetails) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text(
                        text = stringResource(R.string.metadata_match_loading_details),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        if (state.detailsError != null) {
            item {
                NoticeCard(
                    text = stringResource(R.string.metadata_match_details_failed),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
        }

        item {
            FillBlankOnlyCard(
                checked = state.fillBlankOnly,
                onCheckedChange = onFillBlankOnlyChange
            )
        }

        item {
            Text(
                text = stringResource(R.string.metadata_match_fields_heading),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                MetadataFieldRow(
                    label = stringResource(R.string.song_field_title),
                    currentValue = song.title,
                    newValue = candidate.title,
                    checked = selection.title,
                    enabled = candidate.title.isNotBlank(),
                    manuallyEdited = MetadataField.TITLE in state.manuallyEditedFields,
                    onCheckedChange = { onFieldSelected(MetadataField.TITLE, it) }
                )
                MetadataFieldRow(
                    label = stringResource(R.string.song_field_artist),
                    currentValue = song.displayArtist,
                    newValue = candidate.artist,
                    checked = selection.artist,
                    enabled = candidate.artist.isNotBlank(),
                    manuallyEdited = MetadataField.ARTIST in state.manuallyEditedFields,
                    onCheckedChange = { onFieldSelected(MetadataField.ARTIST, it) }
                )
                MetadataFieldRow(
                    label = stringResource(R.string.song_field_album),
                    currentValue = song.album,
                    newValue = candidate.album,
                    checked = selection.album,
                    enabled = candidate.album.isNotBlank(),
                    manuallyEdited = MetadataField.ALBUM in state.manuallyEditedFields,
                    onCheckedChange = { onFieldSelected(MetadataField.ALBUM, it) }
                )
                MetadataFieldRow(
                    label = stringResource(R.string.song_field_album_artist),
                    currentValue = song.albumArtist,
                    newValue = candidate.albumArtist,
                    checked = selection.albumArtist,
                    enabled = !candidate.albumArtist.isNullOrBlank(),
                    onCheckedChange = { onFieldSelected(MetadataField.ALBUM_ARTIST, it) }
                )
                MetadataFieldRow(
                    label = stringResource(R.string.song_field_release_date),
                    currentValue = formatReleaseDate(song.releaseDate)
                        ?: song.year.takeIf { it > 0 }?.toString(),
                    newValue = resolvedDate,
                    checked = selection.releaseDate,
                    enabled = !resolvedDate.isNullOrBlank(),
                    onCheckedChange = { onFieldSelected(MetadataField.RELEASE_DATE, it) }
                )
                MetadataFieldRow(
                    label = stringResource(R.string.song_field_track_number),
                    currentValue = song.trackNumber.takeIf { it > 0 }?.toString(),
                    newValue = candidate.trackNumber?.toString(),
                    checked = selection.trackNumber,
                    enabled = (candidate.trackNumber ?: 0) > 0,
                    onCheckedChange = { onFieldSelected(MetadataField.TRACK_NUMBER, it) }
                )
                MetadataFieldRow(
                    label = stringResource(R.string.song_field_disc_number),
                    currentValue = song.discNumber?.toString(),
                    newValue = candidate.discNumber?.toString(),
                    checked = selection.discNumber,
                    enabled = (candidate.discNumber ?: 0) > 0,
                    onCheckedChange = { onFieldSelected(MetadataField.DISC_NUMBER, it) }
                )
                MetadataFieldRow(
                    label = stringResource(R.string.song_field_genre),
                    currentValue = song.genre,
                    newValue = candidate.genre,
                    checked = selection.genre,
                    enabled = !candidate.genre.isNullOrBlank(),
                    manuallyEdited = MetadataField.GENRE in state.manuallyEditedFields,
                    onCheckedChange = { onFieldSelected(MetadataField.GENRE, it) }
                )
                MetadataFieldRow(
                    label = stringResource(R.string.cd_cover_art),
                    currentValue = if (song.albumArtUriString.isNullOrBlank()) {
                        stringResource(R.string.metadata_match_cover_missing)
                    } else {
                        stringResource(R.string.metadata_match_cover_present)
                    },
                    newValue = when {
                        candidate.coverArtUrl.isNullOrBlank() -> null
                        localDatabaseOnly -> stringResource(R.string.metadata_match_cover_file_only_hint)
                        else -> stringResource(R.string.metadata_match_cover_available)
                    },
                    checked = selection.coverArt,
                    enabled = !candidate.coverArtUrl.isNullOrBlank() && !localDatabaseOnly,
                    onCheckedChange = { onFieldSelected(MetadataField.COVER_ART, it) }
                )
            }
        }

        item {
            ApplyTargetSection(
                state = state,
                onApplyTargetChange = onApplyTargetChange
            )
        }

        if (state.error != MetadataMatchError.NONE) {
            item {
                MetadataErrorBanner(
                    error = state.error,
                    message = state.errorMessage,
                    onDismiss = onDismissError
                )
            }
        }

        item { Spacer(modifier = Modifier.height(16.dp)) }
    }
}

@Composable
private fun CandidateSummaryCard(candidate: MetadataCandidate) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(22.dp)
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SmartImage(
                model = candidate.coverArtUrl,
                contentDescription = stringResource(R.string.cd_cover_art),
                modifier = Modifier.size(88.dp),
                shape = RoundedCornerShape(16.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = candidate.title,
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = listOfNotNull(
                        candidate.artist.takeIf { it.isNotBlank() },
                        candidate.album.takeIf { it.isNotBlank() }
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = listOfNotNull(
                        candidate.year.takeIf { it > 0 }?.toString(),
                        candidate.releaseStatus,
                        candidate.releaseType
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                candidate.disambiguation?.takeIf { it.isNotBlank() }?.let { note ->
                    Text(
                        text = note,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    text = stringResource(R.string.metadata_match_result_score, candidate.score),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
private fun FillBlankOnlyCard(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) },
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(18.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.metadata_match_fill_blank_only),
                    style = MaterialTheme.typography.bodyLarge
                )
                Text(
                    text = stringResource(R.string.metadata_match_fill_blank_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
    }
}

@Composable
private fun MetadataFieldRow(
    label: String,
    currentValue: String?,
    newValue: String?,
    checked: Boolean,
    enabled: Boolean,
    manuallyEdited: Boolean = false,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onCheckedChange(!checked) }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled
        )
        Column(modifier = Modifier.weight(1f)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (enabled) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
                if (manuallyEdited) {
                    Text(
                        text = stringResource(R.string.metadata_match_manual_badge),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                }
            }
            Text(
                text = stringResource(
                    R.string.metadata_match_current_value,
                    currentValue?.takeIf { it.isNotBlank() }
                        ?: stringResource(R.string.metadata_match_empty_value)
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (enabled) {
                Text(
                    text = stringResource(
                        R.string.metadata_match_new_value,
                        newValue?.takeIf { it.isNotBlank() }
                            ?: stringResource(R.string.metadata_match_empty_value)
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun ApplyTargetSection(
    state: MetadataMatchUiState,
    onApplyTargetChange: (MetadataApplyTarget) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.metadata_match_apply_target),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold
        )
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            SegmentedButton(
                selected = state.applyTarget == MetadataApplyTarget.FILE_AND_DATABASE,
                onClick = { onApplyTargetChange(MetadataApplyTarget.FILE_AND_DATABASE) },
                enabled = state.canWriteFile,
                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2)
            ) {
                Text(stringResource(R.string.metadata_match_target_file_and_db))
            }
            SegmentedButton(
                selected = state.applyTarget == MetadataApplyTarget.DATABASE_ONLY,
                onClick = { onApplyTargetChange(MetadataApplyTarget.DATABASE_ONLY) },
                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2)
            ) {
                Text(stringResource(R.string.metadata_match_target_db_only))
            }
        }
        val hint = when {
            state.isCloudSong -> stringResource(R.string.metadata_match_target_cloud_hint)
            !state.canWriteFile -> stringResource(R.string.metadata_match_target_file_unavailable)
            state.applyTarget == MetadataApplyTarget.DATABASE_ONLY ->
                stringResource(R.string.metadata_match_target_db_only_hint)
            else -> null
        }
        hint?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ApplyBar(
    state: MetadataMatchUiState,
    onApply: () -> Unit,
) {
    Surface(tonalElevation = 3.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                modifier = Modifier.weight(1f),
                text = stringResource(
                    R.string.metadata_match_selected_count,
                    state.selection.selectionCount()
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(
                onClick = onApply,
                enabled = !state.isApplying && state.selection.hasAny
            ) {
                if (state.isApplying) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.metadata_match_applying))
                } else {
                    Text(stringResource(R.string.metadata_match_apply))
                }
            }
        }
    }
}

@Composable
private fun MetadataErrorBanner(
    error: MetadataMatchError,
    message: String?,
    onDismiss: () -> Unit,
) {
    val text = when (error) {
        MetadataMatchError.NO_FIELDS -> stringResource(R.string.metadata_match_no_fields)
        MetadataMatchError.COVER_UNAVAILABLE ->
            stringResource(R.string.metadata_match_cover_unavailable)
        MetadataMatchError.SEARCH_FAILED -> stringResource(R.string.metadata_match_search_failed)
        MetadataMatchError.APPLY_FAILED -> stringResource(R.string.metadata_match_apply_failed)
        MetadataMatchError.NONE -> return
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.errorContainer,
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            message?.takeIf { it.isNotBlank() }?.let { detail ->
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.metadata_match_dismiss_error))
            }
        }
    }
}

@Composable
private fun NoticeCard(
    text: String,
    color: androidx.compose.ui.graphics.Color,
    contentColor: androidx.compose.ui.graphics.Color,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = color,
        shape = RoundedCornerShape(16.dp)
    ) {
        Text(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = contentColor
        )
    }
}

@Composable
private fun AppliedContent(
    state: MetadataMatchUiState,
    onDone: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Rounded.CheckCircle,
            contentDescription = null,
            modifier = Modifier.size(72.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.metadata_match_applied_count, state.appliedFieldCount),
            style = MaterialTheme.typography.headlineSmall
        )
        if (state.appliedCover) {
            Text(
                modifier = Modifier.padding(top = 6.dp),
                text = stringResource(R.string.metadata_match_applied_cover),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (state.coverUnavailable) {
            Text(
                modifier = Modifier.padding(top = 6.dp),
                text = stringResource(R.string.metadata_match_cover_unavailable),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error
            )
        }
        Spacer(modifier = Modifier.height(24.dp))
        Button(onClick = onDone) {
            Text(stringResource(R.string.metadata_match_done))
        }
    }
}

private fun MetadataFieldSelection.selectionCount(): Int = listOf(
    title,
    artist,
    album,
    albumArtist,
    releaseDate,
    trackNumber,
    discNumber,
    genre,
    coverArt
).count { it }
