/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Original work, GPL-3.0-or-later.
 */
package com.pisces312.piscesplayer.presentation.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.pisces312.piscesplayer.R
import com.pisces312.piscesplayer.data.ai.AiChatMessage
import com.pisces312.piscesplayer.data.model.Song
import com.pisces312.piscesplayer.presentation.viewmodel.AiSongQaMessage
import com.pisces312.piscesplayer.presentation.viewmodel.AiSongQaPreset
import com.pisces312.piscesplayer.presentation.viewmodel.AiSongQaUiState
import com.pisces312.piscesplayer.presentation.viewmodel.AiSongQaViewModel
import kotlinx.coroutines.launch

/**
 * The player's current-song Q&A sheet.
 *
 * It is intentionally a lightweight conversation surface: no playback controls, no persistent
 * history and no network knowledge beyond the configured OpenAI-compatible provider. The song
 * card is the visible consent boundary for what leaves the device.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun UnifiedPlayerAiQaLayer(
    show: Boolean,
    initialSong: Song?,
    preset: AiSongQaPreset?,
    currentPlayerSong: Song?,
    albumColorScheme: ColorScheme,
    onDismiss: () -> Unit,
    onOpenAiSettings: () -> Unit
) {
    if (!show) return

    val viewModel: AiSongQaViewModel = hiltViewModel()
    val sheetState = rememberModalSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // Keyed on the opening transition only: a track change while the sheet is open must not
    // silently retarget the conversation. The switch action is explicit.
    LaunchedEffect(show) {
        initialSong?.let { viewModel.openSession(it, preset) }
    }
    LaunchedEffect(currentPlayerSong?.id) {
        viewModel.updateCurrentPlayerSong(currentPlayerSong)
    }

    fun dismissSheet() {
        viewModel.dismissSession()
        scope.launch { sheetState.hide() }.invokeOnCompletion {
            if (!sheetState.isVisible) onDismiss()
        }
    }

    androidx.compose.material3.MaterialTheme(
        colorScheme = albumColorScheme,
        typography = MaterialTheme.typography,
        shapes = MaterialTheme.shapes
    ) {
        ModalBottomSheet(
            onDismissRequest = { dismissSheet() },
            sheetState = sheetState
        ) {
            AiSongQaSheetContent(
                state = state,
                onDismiss = ::dismissSheet,
                onOpenAiSettings = {
                    dismissSheet()
                    onOpenAiSettings()
                },
                onNewConversation = viewModel::startNewConversation,
                onToggleSongContext = viewModel::setIncludeSongContext,
                onToggleLyrics = viewModel::setIncludeLyrics,
                onSwitchToCurrentSong = {
                    viewModel.switchToCurrentSong(currentPlayerSong)
                },
                onDraftChange = viewModel::setDraft,
                onSendMessage = viewModel::sendMessage,
                onStop = viewModel::stopGeneration,
                onRetry = viewModel::retryLastFailed
            )
        }
    }
}

@Composable
private fun AiSongQaSheetContent(
    state: AiSongQaUiState,
    onDismiss: () -> Unit,
    onOpenAiSettings: () -> Unit,
    onNewConversation: () -> Unit,
    onToggleSongContext: (Boolean) -> Unit,
    onToggleLyrics: (Boolean) -> Unit,
    onSwitchToCurrentSong: () -> Unit,
    onDraftChange: (String) -> Unit,
    onSendMessage: (String, Boolean?) -> Unit,
    onStop: () -> Unit,
    onRetry: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 420.dp, max = 680.dp)
            .padding(horizontal = 20.dp)
            .navigationBarsPadding()
            .imePadding()
            .padding(bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Rounded.AutoAwesome,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = stringResource(R.string.ai_song_qa_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onNewConversation) {
                Text(stringResource(R.string.ai_song_qa_new_conversation))
            }
            IconButton(onClick = onDismiss) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = stringResource(R.string.cd_close)
                )
            }
        }

        SongQaContextCard(
            state = state,
            onToggleSongContext = onToggleSongContext,
            onSwitchToCurrentSong = onSwitchToCurrentSong
        )

        if (!state.isConfigured) {
            UnconfiguredAiCard(onOpenAiSettings = onOpenAiSettings)
            Spacer(modifier = Modifier.weight(1f))
        } else {
            if (state.messages.isEmpty()) {
                SuggestionSection(
                    state = state,
                    onSendMessage = onSendMessage,
                    onToggleLyrics = onToggleLyrics
                )
            } else {
                MessageList(
                    messages = state.messages,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                )
            }

            state.errorMessage?.let { error ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = error,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = onRetry) {
                        Text(stringResource(R.string.ai_song_qa_retry))
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Bottom
            ) {
                OutlinedTextField(
                    value = state.draft,
                    onValueChange = onDraftChange,
                    modifier = Modifier.weight(1f),
                    minLines = 1,
                    maxLines = 4,
                    placeholder = {
                        Text(stringResource(R.string.ai_song_qa_input_hint))
                    }
                )
                Spacer(modifier = Modifier.width(8.dp))
                FilledIconButton(
                    onClick = {
                        if (state.isSending) {
                            onStop()
                        } else {
                            onSendMessage(state.draft, null)
                        }
                    },
                    enabled = state.isSending || state.draft.isNotBlank()
                ) {
                    if (state.isSending) {
                        Icon(
                            imageVector = Icons.Rounded.Stop,
                            contentDescription = stringResource(R.string.ai_song_qa_stop)
                        )
                    } else {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.Send,
                            contentDescription = stringResource(R.string.ai_song_qa_send)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SongQaContextCard(
    state: AiSongQaUiState,
    onToggleSongContext: (Boolean) -> Unit,
    onSwitchToCurrentSong: () -> Unit
) {
    val song = state.song ?: return
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SmartImage(
                    model = song.albumArtUriString,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = song.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = song.displayArtist,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = song.album,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text =
                        stringResource(
                            if (state.includeSongContext) {
                                R.string.ai_song_qa_context_attached
                            } else {
                                R.string.ai_song_qa_context_removed
                            }
                        ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { onToggleSongContext(!state.includeSongContext) }) {
                    Text(
                        stringResource(
                            if (state.includeSongContext) {
                                R.string.ai_song_qa_context_remove
                            } else {
                                R.string.ai_song_qa_context_add
                            }
                        )
                    )
                }
            }

            if (state.canSwitchToCurrentSong) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onSwitchToCurrentSong) {
                        Text(stringResource(R.string.ai_song_qa_switch_current))
                    }
                }
            }

            Text(
                text = stringResource(R.string.ai_song_qa_privacy_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SuggestionSection(
    state: AiSongQaUiState,
    onSendMessage: (String, Boolean?) -> Unit,
    onToggleLyrics: (Boolean) -> Unit
) {
    // The chip text doubles as the question, so the lyrics flag has to travel with it instead of
    // being recovered by comparing strings later.
    val suggestions = buildList {
        add(QaSuggestion(stringResource(R.string.ai_song_qa_suggest_intro_song)))
        add(QaSuggestion(stringResource(R.string.ai_song_qa_suggest_artist)))
        add(QaSuggestion(stringResource(R.string.ai_song_qa_suggest_album)))
        add(QaSuggestion(stringResource(R.string.ai_song_qa_suggest_style)))
        if (state.hasStoredLyrics) {
            add(
                QaSuggestion(
                    text = stringResource(R.string.ai_song_qa_suggest_lyrics),
                    attachLyrics = true
                )
            )
        }
        add(
            QaSuggestion(
                text = stringResource(R.string.ai_song_qa_suggest_translate),
                attachLyrics = state.hasStoredLyrics
            )
        )
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.ai_song_qa_suggestions_label),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            suggestions.forEach { suggestion ->
                FilterChip(
                    selected = false,
                    onClick = {
                        onSendMessage(
                            suggestion.text,
                            suggestion.attachLyrics.takeIf { it }
                        )
                    },
                    label = { Text(suggestion.text) }
                )
            }
        }

        if (state.hasStoredLyrics) {
            FilterChip(
                selected = state.includeLyrics,
                onClick = { onToggleLyrics(!state.includeLyrics) },
                label = { Text(stringResource(R.string.ai_song_qa_include_lyrics)) }
            )
        }
    }
}

private data class QaSuggestion(
    val text: String,
    val attachLyrics: Boolean = false
)

@Composable
private fun UnconfiguredAiCard(onOpenAiSettings: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Rounded.Settings,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.ai_song_qa_unconfigured_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Text(
                text = stringResource(R.string.ai_song_qa_unconfigured_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedButton(onClick = onOpenAiSettings) {
                Text(stringResource(R.string.ai_song_qa_open_settings))
            }
        }
    }
}

@Composable
private fun MessageList(
    messages: List<AiSongQaMessage>,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    val shouldFollow = remember { derivedStateOf { !listState.canScrollForward } }
    LaunchedEffect(messages.size, messages.lastOrNull()?.content?.length) {
        if (messages.isNotEmpty() && shouldFollow.value) {
            listState.scrollToItem(messages.lastIndex)
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier,
        contentPadding = PaddingValues(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(messages, key = { it.id }) { message ->
            QaMessageBubble(message = message)
        }
    }
}

@Composable
private fun QaMessageBubble(message: AiSongQaMessage) {
    val isUser = message.role == AiChatMessage.Role.USER
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
    ) {
        Surface(
            color =
                if (isUser) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.secondaryContainer,
            shape = MaterialTheme.shapes.large,
            modifier = Modifier.width(300.dp)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                if (message.content.isBlank() && message.isStreaming) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp
                        )
                        Text(
                            text = stringResource(R.string.ai_song_qa_answering),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    Text(
                        text = message.content,
                        style = MaterialTheme.typography.bodyMedium,
                        color =
                            if (isUser) MaterialTheme.colorScheme.onPrimaryContainer
                            else MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }
        }
    }
}
