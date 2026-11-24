/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Original work in 2026 for PixelPlayerOSS (rhythm game quick-start dialog flow).
 */
package com.pisces312.piscesplayer.presentation.rhythm

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.pisces312.piscesplayer.R
import com.pisces312.piscesplayer.data.model.Song
import com.pisces312.piscesplayer.data.rhythm.RhythmRepository
import com.pisces312.piscesplayer.presentation.navigation.Screen
import com.pisces312.piscesplayer.presentation.navigation.navigateSafely
import com.pisces312.piscesplayer.presentation.navigation.navigateSafelyReplacing
import com.pisces312.piscesplayer.presentation.viewmodel.PlayerViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Quick start behind the song picker's game icon and the song-info quick dialog.
 *
 * A transient dialog screen: with a fixed difficulty it goes straight to work, with
 * [Screen.RhythmQuick.DIFFICULTY_PICK] it first offers Easy/Hard (plus the chart management
 * entry). While the missing charts are prepared it shows the wave progress bar; on success it
 * replaces itself with the game, so backing out of the game never lands here again.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun RhythmQuickGameScreen(
    songId: String,
    difficulty: String,
    navController: NavHostController,
    playerViewModel: PlayerViewModel,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repository = remember { RhythmRepository(context.applicationContext) }
    val song by playerViewModel.observeSong(songId)
        .collectAsStateWithLifecycle(initialValue = null)

    var targetKey by rememberSaveable {
        mutableStateOf(
            if (difficulty == Screen.RhythmQuick.DIFFICULTY_PICK) null else difficulty,
        )
    }
    var phase by remember { mutableStateOf(RhythmQuickPhase.ANALYZING) }
    var progress by remember { mutableFloatStateOf(0f) }
    var error by remember { mutableStateOf<String?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }

    DisposableEffect(Unit) {
        onDispose { job?.cancel() }
    }

    fun start(key: String, current: Song) {
        error = null
        job?.cancel()
        job = scope.launch {
            try {
                val charts = RhythmQuickPlay(context, repository).ensure(current) { p, f ->
                    phase = p
                    progress = f
                }
                val target = charts.target(key)
                    ?: throw IllegalArgumentException(context.getString(R.string.rhythm_quick_no_chart))
                navController.navigateSafelyReplacing(
                    Screen.RhythmGame.createRoute(songId, target.chartId),
                    Screen.RhythmQuick.route,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                error = e.message ?: e.javaClass.simpleName
            }
        }
    }

    // Runs once the target rung and the song are both known; after an error only an explicit
    // retry (which clears the error) starts another attempt.
    LaunchedEffect(targetKey, song, error) {
        val key = targetKey ?: return@LaunchedEffect
        val current = song ?: return@LaunchedEffect
        if (error != null || job?.isActive == true) return@LaunchedEffect
        start(key, current)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.5f)),
        contentAlignment = Alignment.Center,
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            shape = RoundedCornerShape(28.dp),
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                when {
                    targetKey == null -> {
                        Text(
                            text = stringResource(R.string.rhythm_quick_pick_title),
                            style = MaterialTheme.typography.titleLarge,
                        )
                        Text(
                            text = song?.title.orEmpty(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = stringResource(R.string.rhythm_quick_pick_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        QuickDifficultyRow(
                            label = stringResource(R.string.rhythm_quick_easy),
                            onClick = { targetKey = Screen.RhythmQuick.DIFFICULTY_EASY },
                        )
                        QuickDifficultyRow(
                            label = stringResource(R.string.rhythm_quick_hard),
                            onClick = { targetKey = Screen.RhythmQuick.DIFFICULTY_HARD },
                        )
                        TextButton(
                            onClick = {
                                navController.navigateSafelyReplacing(
                                    Screen.RhythmSetup.createRoute(songId),
                                    Screen.RhythmQuick.route,
                                )
                            },
                        ) {
                            Text(stringResource(R.string.rhythm_quick_manage))
                        }
                    }

                    error != null -> {
                        Text(
                            text = stringResource(R.string.rhythm_quick_failed, error.orEmpty()),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                        ) {
                            TextButton(onClick = { navController.popBackStack() }) {
                                Text(stringResource(R.string.cancel))
                            }
                            Spacer(Modifier.width(8.dp))
                            Button(onClick = { error = null }) {
                                Text(stringResource(R.string.rhythm_quick_retry))
                            }
                        }
                    }

                    else -> {
                        Text(
                            text = stringResource(R.string.rhythm_quick_preparing),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = stringResource(
                                if (phase == RhythmQuickPhase.ANALYZING) {
                                    R.string.rhythm_quick_phase_analyze
                                } else {
                                    R.string.rhythm_quick_phase_derive
                                },
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (phase == RhythmQuickPhase.ANALYZING) {
                            LinearWavyProgressIndicator(
                                progress = { progress },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        } else {
                            LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                        ) {
                            TextButton(
                                onClick = {
                                    job?.cancel()
                                    navController.popBackStack()
                                },
                            ) {
                                Text(stringResource(R.string.cancel))
                            }
                        }
                    }
                }
            }
        }
    }
}

/** One Easy/Hard option row of the quick dialog. */
@Composable
private fun QuickDifficultyRow(label: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Rounded.SportsEsports,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
