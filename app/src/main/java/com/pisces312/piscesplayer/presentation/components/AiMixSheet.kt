package com.pisces312.piscesplayer.presentation.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pisces312.piscesplayer.R
import com.pisces312.piscesplayer.data.model.Song
import com.pisces312.piscesplayer.presentation.viewmodel.AiGenerationStage
import com.pisces312.piscesplayer.presentation.viewmodel.NlpPlaylistPreviewState
import com.pisces312.piscesplayer.presentation.viewmodel.PlaylistViewModel
import com.pisces312.piscesplayer.presentation.viewmodel.SerendipityUiState

/** Lengths offered as one-tap chips; they map straight to the generator's max length. */
private val AI_MIX_LENGTHS = listOf(15, 25, 40)

private val AI_MIX_IDEAS = listOf(
    R.string.ai_mix_idea_late_night,
    R.string.ai_mix_idea_commute,
    R.string.ai_mix_idea_workout,
    R.string.ai_mix_idea_focus,
    R.string.ai_mix_idea_cleaning,
    R.string.ai_mix_idea_sunday
)

/**
 * Home screen AI mix flow: describe a mood, get a list, then play it or just save it.
 *
 * Three phases live in one sheet — input, generating, result — so the prompt the user typed
 * stays on screen the whole time and "regenerate" is a single tap.
 *
 * The same sheet also serves both Serendipity gestures: short press auto-generates and lands
 * straight on the result phase, long press stops on the input phase so the user can describe it
 * themselves. Passing a non-null [serendipity] shows the signals that were read as chips, keeps
 * the prompt editable (and allowed to stay blank), and shares the result phase, play/save actions
 * and the error handling between the two entries.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiMixSheet(
    state: NlpPlaylistPreviewState,
    sampleConfig: SampleConfig,
    onGenerate: (String, Int) -> Unit,
    onSave: (name: String, songs: List<Song>, prompt: String, startPlayback: Boolean) -> Unit,
    onDismiss: () -> Unit,
    serendipity: SerendipityUiState? = null,
    /** Localized labels for the signals that were actually read; empty means "nothing known". */
    serendipityChips: List<String> = emptyList(),
    /** Suggested playlist name built from the same signals ("Rain · Evening · 19:20"). */
    serendipityDefaultName: String? = null,
    /** Non-null when the short-press flow already saved this list under that name. */
    autoSavedName: String? = null,
    /** Replay the given result from the start without saving or closing. */
    onReplay: ((List<Song>) -> Unit)? = null,
    onReshuffleSerendipity: (() -> Unit)? = null,
    onRephraseSerendipity: (() -> Unit)? = null
) {
    var prompt by rememberSaveable { mutableStateOf("") }
    var maxLength by rememberSaveable {
        mutableIntStateOf(PlaylistViewModel.DEFAULT_AI_MIX_LENGTH)
    }
    var advancedExpanded by rememberSaveable { mutableStateOf(false) }

    // Editable copy of the generated result: removing a song must not fight the view model state.
    val resultSongs = remember { mutableStateListOf<Song>() }
    var mixName by rememberSaveable { mutableStateOf("") }
    // Last name this sheet produced on its own: an untouched name is replaced on regenerate,
    // an edited one is left alone.
    var lastGeneratedName by rememberSaveable { mutableStateOf("") }

    val ideas = AI_MIX_IDEAS.map { stringResource(it) }

    // Serendipity composes a sentence from the moment's signals before the sheet opens. The
    // describe entry intentionally starts with an empty field ("the prompt may stay blank"):
    // the first composed sentence is only kept as the generation fallback. A later reshuffle or
    // AI rephrase — both user-initiated — overwrites whatever is in the field.
    var lastSeenSerendipityPrompt by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(serendipity?.prompt) {
        val composed = serendipity?.prompt
        if (composed.isNullOrBlank()) return@LaunchedEffect
        if (lastSeenSerendipityPrompt == null) {
            lastSeenSerendipityPrompt = composed
            return@LaunchedEffect
        }
        if (composed != lastSeenSerendipityPrompt) {
            lastSeenSerendipityPrompt = composed
            prompt = composed
        }
    }

    LaunchedEffect(state) {
        if (state.hasResult && state.songs.isNotEmpty()) {
            resultSongs.clear()
            resultSongs.addAll(state.songs)
            val generated = serendipityDefaultName ?: aiMixDefaultName(prompt, ideas)
            if (mixName.isBlank() || mixName == lastGeneratedName) mixName = generated
            lastGeneratedName = generated
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            // Keep the action row clear of the system nav bar / gesture pill: without this the
            // bottom "regenerate" control sits under the bar and looks cut off.
            .navigationBarsPadding()
            .padding(bottom = 24.dp),
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
                text =
                        if (serendipity != null) stringResource(R.string.ai_serendipity_sheet_title)
                        else stringResource(R.string.ai_mix_sheet_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onDismiss) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = stringResource(R.string.cd_close)
                )
            }
        }

        AnimatedContent(
            targetState = when {
                state.isGenerating -> AiMixPhase.Generating
                state.hasResult -> AiMixPhase.Result
                else -> AiMixPhase.Input
            },
            label = "ai_mix_phase"
        ) { phase ->
            when (phase) {
                AiMixPhase.Input -> InputPhase(
                    prompt = prompt,
                    ideas = ideas,
                    onPromptChange = { prompt = it },
                    maxLength = maxLength,
                    onLengthChange = { maxLength = it },
                    advancedExpanded = advancedExpanded,
                    onAdvancedToggle = { advancedExpanded = it },
                    sampleConfig = sampleConfig,
                    onGenerate = { onGenerate(prompt.trim(), maxLength) },
                    serendipity = serendipity,
                    serendipityChips = serendipityChips,
                    onChipTap = { chip -> prompt = appendChipToPrompt(prompt, chip) },
                    onReshuffle = onReshuffleSerendipity,
                    onRephrase = onRephraseSerendipity
                )

                AiMixPhase.Generating -> GeneratingPhase(state)

                AiMixPhase.Result -> ResultPhase(
                    state = state,
                    songs = resultSongs,
                    onRemove = { resultSongs.remove(it) },
                    mixName = mixName,
                    onNameChange = { mixName = it },
                    autoSavedName = autoSavedName,
                    onPlay = { onSave(mixName, resultSongs.toList(), prompt.trim(), true) },
                    onSaveOnly = { onSave(mixName, resultSongs.toList(), prompt.trim(), false) },
                    onReplay = onReplay?.let { replay -> { replay(resultSongs.toList()) } },
                    onRegenerate = { onGenerate(prompt.trim(), maxLength) }
                )
            }
        }
    }
}

private enum class AiMixPhase { Input, Generating, Result }

/** Appends a tapped signal chip to the prompt, skipping a duplicate of the same text. */
internal fun appendChipToPrompt(prompt: String, chip: String): String {
    if (chip.isBlank()) return prompt
    if (prompt.contains(chip)) return prompt
    return if (prompt.isBlank()) chip else "$prompt $chip"
}

@Composable
private fun InputPhase(
    prompt: String,
    ideas: List<String>,
    onPromptChange: (String) -> Unit,
    maxLength: Int,
    onLengthChange: (Int) -> Unit,
    advancedExpanded: Boolean,
    onAdvancedToggle: (Boolean) -> Unit,
    sampleConfig: SampleConfig,
    onGenerate: () -> Unit,
    serendipity: SerendipityUiState?,
    serendipityChips: List<String>,
    onChipTap: (String) -> Unit,
    onReshuffle: (() -> Unit)?,
    onRephrase: (() -> Unit)?
) {
    // Only the input phase scrolls: the result phase hosts its own LazyColumn, so wrapping the
    // shared outer Column would nest two vertical scrollers with infinite-height constraints.
    // This keeps the generate button reachable on small screens, large fonts or with the IME up.
    Column(
        modifier = Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (serendipity != null) {
            SerendipitySignals(
                state = serendipity,
                chips = serendipityChips,
                onChipTap = onChipTap,
                onReshuffle = onReshuffle,
                onRephrase = onRephrase
            )
        }

        OutlinedTextField(
            value = prompt,
            onValueChange = onPromptChange,
            modifier = Modifier.fillMaxWidth(),
            minLines = if (serendipity != null) 3 else 2,
            label =
                    if (serendipity != null) {
                        { Text(stringResource(R.string.ai_serendipity_prompt_label)) }
                    } else null,
            placeholder = { Text(stringResource(R.string.ai_mix_prompt_hint)) }
        )

        // One-tap ideas stay available beside the signals: they replace the field, they do not
        // throw the context away — generation falls back to the composed sentence when blank.
        Text(
            text = stringResource(R.string.ai_mix_ideas_label),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ideas.forEach { idea ->
                FilterChip(
                    selected = prompt.trim().equals(idea, ignoreCase = true),
                    onClick = { onPromptChange(idea) },
                    label = { Text(idea) }
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AI_MIX_LENGTHS.forEach { length ->
                FilterChip(
                    selected = maxLength == length,
                    onClick = { onLengthChange(length) },
                    label = { Text(length.toString()) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                )
            }
        }

        // Both flows expose sampling controls; HomeScreen injects the matching SampleConfig
        // (Serendipity has its own random-by-default settings, the describe flow its own).
        TextButton(onClick = { onAdvancedToggle(!advancedExpanded) }) {
            Text(stringResource(R.string.ai_mix_advanced))
        }
        if (advancedExpanded) {
            SampleModeDropdown(
                modes = sampleConfig.modes,
                selected = sampleConfig.mode,
                enabled = true,
                onSelect = sampleConfig.onModeChange
            )
            SampleSizeDropdown(
                options = sampleConfig.sizes,
                selected = sampleConfig.size,
                enabled = true,
                onSelect = sampleConfig.onSizeChange
            )
        }

        Button(
            onClick = onGenerate,
            // Blank prompt is allowed: the view model falls back to the composed moment sentence
            // (or a generic "surprise me") so the primary action never has to stay disabled.
            enabled = serendipity?.isCollecting != true,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(
                imageVector = Icons.Rounded.AutoAwesome,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(stringResource(R.string.ai_mix_generate))
        }
    }
}

/**
 * What Serendipity based the prompt on, plus the two ways to change the wording.
 *
 * The chips only ever show signals that were actually read — a missing weather line is the honest
 * answer to "why does this mix ignore the rain?". Tapping a chip appends its text to the prompt
 * so the user can keep just the signals they care about.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SerendipitySignals(
    state: SerendipityUiState,
    chips: List<String>,
    onChipTap: (String) -> Unit,
    onReshuffle: (() -> Unit)?,
    onRephrase: (() -> Unit)?
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (state.isCollecting) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LoadingIndicator(modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.ai_serendipity_collecting),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else if (chips.isNotEmpty()) {
            Text(
                text = stringResource(R.string.ai_serendipity_signals_label),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = stringResource(R.string.ai_serendipity_chip_tap_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                chips.forEach { chip ->
                    SuggestionChip(
                        onClick = { onChipTap(chip) },
                        enabled = !state.isCollecting,
                        label = { Text(chip) }
                    )
                }
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            if (onReshuffle != null) {
                TextButton(onClick = onReshuffle, enabled = !state.isCollecting) {
                    Text(stringResource(R.string.ai_serendipity_reshuffle))
                }
            }
            if (onRephrase != null) {
                TextButton(onClick = onRephrase, enabled = !state.isRephrasing && !state.isCollecting) {
                    Text(stringResource(R.string.ai_serendipity_rephrase))
                }
            }
            if (state.isRephrasing) {
                Spacer(modifier = Modifier.width(4.dp))
                LoadingIndicator(modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = stringResource(R.string.ai_serendipity_rephrasing),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (state.rephraseFailed) {
            Text(
                text = stringResource(R.string.ai_serendipity_rephrase_failed),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}

/**
 * Generation in progress: a status line, plus the model's chain of thought when the provider
 * streams one. Providers that do not think (or when thinking is off) keep the plain spinner.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun GeneratingPhase(state: NlpPlaylistPreviewState) {
    val isThinking = state.stage == AiGenerationStage.THINKING
    val hasThinking = state.thinkingText.isNotBlank()

    if (!hasThinking) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                LoadingIndicator(modifier = Modifier.size(56.dp))
                Text(
                    text = stringResource(R.string.ai_mix_generating),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        return
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            LoadingIndicator(modifier = Modifier.size(24.dp))
            Text(
                text =
                    stringResource(
                        if (isThinking) R.string.ai_thinking_status
                        else R.string.ai_mix_generating
                    ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        ThinkingCard(thinkingText = state.thinkingText, isThinking = isThinking)
    }
}

/**
 * The accumulated thought process, collapsed as soon as the model starts answering.
 *
 * A manual toggle wins over that default: once the user has touched it, [isThinking] flipping is
 * no longer allowed to change the expansion.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ThinkingCard(
    thinkingText: String,
    isThinking: Boolean,
    /**
     * False when an outer list already scrolls this content — the result phase hosts the card as
     * one of its items, so the card must not start a second scroller nested in the same viewport.
     */
    innerScroll: Boolean = true
) {
    // Starts expanded only while the model is still thinking: in the result phase the card is
    // composed after the answer arrived, and a thought process that long has to be opt-in there.
    var expanded by remember { mutableStateOf(isThinking) }
    var userToggled by remember { mutableStateOf(false) }
    val scrollState = rememberScrollState()

    LaunchedEffect(isThinking) {
        if (!isThinking && !userToggled) expanded = false
    }
    // Follow the stream: latest sentence stays visible while it is still being written.
    LaunchedEffect(thinkingText, expanded) {
        if (expanded && innerScroll) scrollState.scrollTo(scrollState.maxValue)
    }

    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize()
    ) {
        Column(modifier = Modifier.padding(start = 12.dp, top = 8.dp, end = 4.dp, bottom = 12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = stringResource(R.string.ai_thinking_section),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                if (isThinking) {
                    LoadingIndicator(modifier = Modifier.size(14.dp))
                }
                IconButton(onClick = {
                    expanded = !expanded
                    userToggled = true
                }) {
                    Icon(
                        imageVector =
                            if (expanded) Icons.Rounded.ExpandMore else Icons.Rounded.ChevronRight,
                        contentDescription = stringResource(R.string.ai_thinking_toggle)
                    )
                }
            }

            if (expanded) {
                Text(
                    text = thinkingText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier =
                            Modifier.padding(end = 8.dp).let { base ->
                                if (innerScroll) {
                                    base.heightIn(max = 220.dp).verticalScroll(scrollState)
                                } else {
                                    base
                                }
                            }
                )
            }
        }
    }
}

@Composable
private fun ResultPhase(
    state: NlpPlaylistPreviewState,
    songs: List<Song>,
    onRemove: (Song) -> Unit,
    mixName: String,
    onNameChange: (String) -> Unit,
    autoSavedName: String?,
    onPlay: () -> Unit,
    onSaveOnly: () -> Unit,
    onReplay: (() -> Unit)?,
    onRegenerate: () -> Unit
) {
    // The thought process and the songs share one scroll region: ExpressiveScrollBar only accepts a
    // lazy list/grid state, so a second scroller could not have a shared, drag-able indicator.
    val listState = rememberLazyListState()
    val showScrollBar by remember {
        derivedStateOf { listState.canScrollForward || listState.canScrollBackward }
    }
    val thinkingText = state.thinkingText

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        state.errorMessage?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error
            )
        }

        if (songs.isNotEmpty()) {
            OutlinedTextField(
                value = mixName,
                onValueChange = onNameChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.ai_playlist_name_label)) },
                singleLine = true
            )

            Text(
                text = stringResource(R.string.ai_mix_result_count, songs.size),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (songs.isNotEmpty() || thinkingText.isNotBlank()) {
            // Cap the list so the action row + regenerate stay on screen beside the mini player
            // and nav bar. 200.dp shows several rows and keeps the sheet readable at 411×914 dp.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 200.dp)
            ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    contentPadding = PaddingValues(
                        bottom = 8.dp,
                        end = if (showScrollBar) 24.dp else 0.dp
                    )
                ) {
                    if (thinkingText.isNotBlank()) {
                        item(key = "ai_thinking", contentType = "ai_thinking") {
                            // Collapsed by default: the thought process is long, and the songs are
                            // what the user came back for. Expanding is one tap on the header.
                            ThinkingCard(
                                thinkingText = thinkingText,
                                isThinking = false,
                                innerScroll = false
                            )
                        }
                    }
                    items(songs, key = { it.id }) { song ->
                        MixSongRow(song = song, onRemove = { onRemove(song) })
                    }
                }

                if (showScrollBar) {
                    ExpressiveScrollBar(
                        listState = listState,
                        modifier = Modifier.align(Alignment.CenterEnd)
                    )
                }
            }
        }

        if (songs.isEmpty() && state.errorMessage == null) {
            Text(
                text = stringResource(R.string.presentation_batch_e_describe_no_matches),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // After short-press auto-save the list is already in the library: keep "replay" and an
        // optional re-save only when the user renamed it, instead of creating a duplicate.
        val autoSaved = autoSavedName != null
        val nameMatchesAutoSave = autoSaved && mixName.trim() == autoSavedName

        if (autoSaved) {
            Text(
                text = stringResource(R.string.ai_mix_auto_saved, autoSavedName.orEmpty()),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (autoSaved) {
                TextButton(onClick = onSaveOnly, enabled = songs.isNotEmpty() && !nameMatchesAutoSave) {
                    Text(stringResource(R.string.ai_mix_save_as))
                }
                Spacer(modifier = Modifier.weight(1f))
                Button(onClick = { onReplay?.invoke() }, enabled = songs.isNotEmpty() && onReplay != null) {
                    Icon(
                        imageVector = Icons.Rounded.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.ai_mix_replay))
                }
            } else {
                TextButton(onClick = onSaveOnly, enabled = songs.isNotEmpty()) {
                    Text(stringResource(R.string.ai_mix_save_only))
                }
                Spacer(modifier = Modifier.weight(1f))
                Button(onClick = onPlay, enabled = songs.isNotEmpty()) {
                    Icon(
                        imageVector = Icons.Rounded.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.ai_mix_play))
                }
            }
        }

        FilledTonalButton(
            onClick = onRegenerate,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.presentation_batch_e_describe_regenerate))
        }
    }
}

@Composable
private fun MixSongRow(song: Song, onRemove: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SmartImage(
            model = song.albumArtUriString,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.size(44.dp)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
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
        IconButton(onClick = onRemove) {
            Icon(
                imageVector = Icons.Rounded.Close,
                contentDescription = stringResource(R.string.ai_mix_remove_song)
            )
        }
    }
}
