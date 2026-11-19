/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Original work in 2026 for PixelPlayerOSS
 * (docs/plans/manual-tap-chart-mobile-plan.md §5-§6: manual tap recording screen).
 */
package com.pisces312.piscesplayer.presentation.rhythm

import android.app.Activity
import android.content.pm.ActivityInfo
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.pisces312.piscesplayer.R
import com.pisces312.piscesplayer.data.offline.CloudOfflineRepository
import com.pisces312.piscesplayer.data.rhythm.RhythmAudioClock
import com.pisces312.piscesplayer.data.rhythm.RhythmPcmDecoder
import com.pisces312.piscesplayer.data.rhythm.RhythmRepository
import com.pisces312.piscesplayer.data.rhythm.RhythmTapAudio
import com.pisces312.piscesplayer.data.rhythm.RhythmTapChart
import com.pisces312.piscesplayer.data.rhythm.RhythmTapHit
import com.pisces312.piscesplayer.data.rhythm.RhythmTapTake
import com.pisces312.piscesplayer.presentation.viewmodel.PlayerViewModel
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val RECORD_BACKGROUND = Color(0xFF0D0F17)
private val RECORD_LANES = Color(0xFF171A25)
private val RECORD_LANES_ALT = Color(0xFF1C202D)
private val RECORD_SEPARATOR = Color(0xFF3A4154)
private val RECORD_HUD_TEXT = Color(0xFFE9EDF7)
private val RECORD_HUD_SECONDARY = Color(0xFF9DA6BA)
private val RECORD_ACCENT = Color(0xFF78C8FF)

/** Default chart name: localized prefix + a compact timestamp, matching the derived-chart style. */
private val RECORD_NAME_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault())

/** One recorded tap flash at the judgement line. */
private data class RecordTapFlash(val lane: Int, val startedAtNanos: Long)

/**
 * Manual tap recording screen: play the song, tap the four lanes where the beats should be,
 * save the result as a playable chart (manual-tap-chart-mobile-plan.md §5-§6).
 *
 * Timing is an identity map: every tap is stamped [RhythmAudioClock.nowMs] at the input callback,
 * the same clock domain the judgement chain uses, so the saved chart plays exactly as tapped.
 * Lanes are kept as pressed; no judgement, score or falling notes exist here.
 */
@Composable
fun RhythmRecordScreen(
    songId: String,
    playerViewModel: PlayerViewModel,
    onSaved: () -> Unit,
    onExit: () -> Unit,
) {
    val context = LocalContext.current
    // Application context: the repository outlives the Activity scope.
    val repository = remember { RhythmRepository(context.applicationContext) }
    val scope = rememberCoroutineScope()

    var clock by remember { mutableStateOf<RhythmAudioClock?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var hits by remember { mutableStateOf(listOf<RhythmTapHit>()) }
    var paused by remember { mutableStateOf(false) }
    var countdown by remember { mutableIntStateOf(3) }
    var songMs by remember { mutableDoubleStateOf(0.0) }
    var frameTick by remember { mutableLongStateOf(0L) }
    var runId by remember { mutableIntStateOf(0) }
    var showSavePanel by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var audioInfo by remember { mutableStateOf<RhythmTapAudio?>(null) }
    var playbackPath by remember { mutableStateOf("") }
    var startedAt by remember { mutableStateOf("") }
    var flashes by remember { mutableStateOf(listOf<RecordTapFlash>()) }
    var flashNowNanos by remember { mutableLongStateOf(System.nanoTime()) }
    val touchTracker = remember { RhythmLaneTouchTracker() }

    val cloudUnavailableMessage = stringResource(R.string.rhythm_cloud_not_supported)
    val songMissingMessage = stringResource(R.string.rhythm_song_missing)

    // Same courtesy as the game screen: pause normal playback for the whole stay.
    var shouldResumePlayer by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        val wasPlaying = playerViewModel.stablePlayerState.value.isPlaying
        shouldResumePlayer = wasPlaying
        if (wasPlaying) playerViewModel.playPause()
    }
    DisposableEffect(Unit) {
        onDispose { if (shouldResumePlayer) playerViewModel.playPause() }
    }

    // Decode the song and build the recording clock (offset 0: the take records raw song time).
    LaunchedEffect(songId) {
        withContext(Dispatchers.IO) {
            try {
                val song = playerViewModel.observeSong(songId).first()
                    ?: error(songMissingMessage)
                if (CloudOfflineRepository.isCloudSong(song)) error(cloudUnavailableMessage)
                val playbackUri = song.contentUriString.takeIf { it.isNotBlank() }?.let(Uri::parse)
                    ?: Uri.fromFile(File(song.path))
                val pcm = RhythmPcmDecoder.decode(context, playbackUri)
                playbackPath = playbackUri.toString()
                audioInfo = RhythmTapAudio(
                    path = playbackUri.toString(),
                    durationMs = pcm.durationMs.roundToInt(),
                    sampleRate = pcm.sampleRate,
                )
                clock = RhythmAudioClock(pcm, offsetMs = 0.0)
            } catch (e: Exception) {
                errorMessage = e.message ?: e.javaClass.simpleName
            }
        }
    }

    // Portrait-only, keep the screen awake, release audio on any dispose path.
    val activity = context as? Activity
    val view = LocalView.current
    DisposableEffect(Unit) {
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        view.keepScreenOn = true
        onDispose {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            view.keepScreenOn = false
            clock?.stop()
            clock?.release()
        }
    }

    // Background pause, including system back during recording.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE && !showSavePanel) {
                clock?.pause()
                paused = true
                touchTracker.clear()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    BackHandler(enabled = !showSavePanel) {
        clock?.pause()
        paused = true
        touchTracker.clear()
    }

    // Countdown, then frame-driven progress. runId restarts the loop (re-record).
    LaunchedEffect(clock, runId) {
        val currentClock = clock ?: return@LaunchedEffect
        flashes = emptyList()
        touchTracker.clear()
        currentClock.stop()
        countdown = 3
        repeat(3) {
            delay(1000)
            countdown -= 1
        }
        if (startedAt.isEmpty()) {
            startedAt = Instant.now().toString()
        }
        currentClock.restart()
        // A pause pressed during the countdown must keep the audio silent.
        if (paused) currentClock.pause()
        songMs = currentClock.nowMs()
        while (isActive) {
            withFrameNanos { }
            flashNowNanos = System.nanoTime()
            if (flashes.isNotEmpty()) {
                flashes = flashes.filter {
                    flashNowNanos - it.startedAtNanos <= RECORD_FLASH_MS * 1_000_000L
                }
            }
            if (!paused) {
                songMs = currentClock.nowMs()
                frameTick += 1
                if (currentClock.finished && !showSavePanel) {
                    showSavePanel = true
                    break
                }
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(RECORD_BACKGROUND)
            .safeDrawingPadding(),
    ) {
        val currentClock = clock
        when {
            errorMessage != null -> RecordMessageScreen(
                message = stringResource(R.string.rhythm_load_failed, errorMessage ?: ""),
                onExit = onExit,
            )

            currentClock == null -> RecordMessageScreen(
                message = stringResource(R.string.rhythm_loading),
                onExit = onExit,
            )

            else -> Column(modifier = Modifier.fillMaxSize()) {
                RecordHud(
                    songMs = songMs,
                    durationMs = currentClock.durationMs,
                    hitCount = hits.size,
                    paused = paused,
                    frameTick = frameTick,
                    onTogglePause = {
                        if (paused) currentClock.resume() else currentClock.pause()
                        paused = !paused
                        touchTracker.clear()
                    },
                )
                Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                    RecordPlayfield(
                        touchTracker = touchTracker,
                        flashes = flashes,
                        flashNowNanos = flashNowNanos,
                        inputEnabled = !paused && countdown == 0 && !showSavePanel,
                        onLaneDown = { lane ->
                            val nowMs = currentClock.nowMs()
                            hits = hits + RhythmTapHit(nowMs, RhythmTapTake.keyFor(lane))
                            flashes = flashes + RecordTapFlash(lane, System.nanoTime())
                            frameTick += 1
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                RecordControlBar(
                    hasHits = hits.isNotEmpty(),
                    onUndo = {
                        if (hits.isNotEmpty()) {
                            hits = hits.dropLast(1)
                            frameTick += 1
                        }
                    },
                    onRestart = {
                        hits = emptyList()
                        paused = false
                        startedAt = ""
                        runId += 1
                    },
                    onFinish = {
                        currentClock.pause()
                        paused = true
                        touchTracker.clear()
                        showSavePanel = true
                    },
                )
            }
        }

        if (currentClock != null && countdown > 0 && !paused && !showSavePanel) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = countdown.toString(),
                    color = RECORD_HUD_TEXT,
                    fontSize = 64.sp,
                    fontWeight = FontWeight.ExtraBold,
                )
            }
        }
    }

    if (showSavePanel) {
        val audio = audioInfo
        if (audio != null) {
            TapSavePanel(
                defaultName = stringResource(
                    R.string.rhythm_record_default_name,
                    RECORD_NAME_FORMATTER.format(Instant.now()),
                ),
                audio = audio,
                hits = hits,
                saving = saving,
                onSave = { name, offsetMs ->
                    val take = RhythmTapTake(
                        audio = audio,
                        startedAt = startedAt,
                        hitCount = hits.size,
                        hits = hits,
                    )
                    val chart = RhythmTapChart.toChart(take, name, offsetMs).chart
                    saving = true
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) {
                                repository.saveTapChart(songId, chart, take)
                            }
                            Toast.makeText(
                                context,
                                context.getString(R.string.rhythm_record_saved, name),
                                Toast.LENGTH_SHORT,
                            ).show()
                            onSaved()
                        } catch (e: Exception) {
                            Toast.makeText(
                                context,
                                e.message ?: e.javaClass.simpleName,
                                Toast.LENGTH_SHORT,
                            ).show()
                            saving = false
                        }
                    }
                },
                onDismiss = {
                    if (!saving) {
                        showSavePanel = false
                        // Back to the recording as it was; the clock stays paused for a re-check.
                    }
                },
            )
        }
    }
}

/** Hit count, progress and the last tap's spacing — the recorder's only feedback besides the flash. */
@Composable
private fun RecordHud(
    songMs: Double,
    durationMs: Double,
    hitCount: Int,
    paused: Boolean,
    frameTick: Long,
    onTogglePause: () -> Unit,
) {
    @Suppress("UNUSED_EXPRESSION") frameTick
    val progress = if (durationMs > 0.0) {
        (songMs / durationMs).toFloat().coerceIn(0f, 1f)
    } else {
        0f
    }
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.rhythm_record_hits, hitCount),
                    color = RECORD_HUD_TEXT,
                    fontSize = 21.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = String.format(
                        Locale.US,
                        "%.2fs / %.2fs",
                        songMs / 1000.0,
                        durationMs / 1000.0,
                    ),
                    color = RECORD_HUD_SECONDARY,
                    fontSize = 11.sp,
                    letterSpacing = 1.2.sp,
                )
            }
            TextButton(onClick = onTogglePause) {
                Text(
                    text = stringResource(
                        if (paused) R.string.rhythm_resume else R.string.rhythm_pause,
                    ),
                    color = RECORD_ACCENT,
                )
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(2.dp)
                .background(Color(0xFF303748)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(progress)
                    .fillMaxHeight()
                    .background(RECORD_ACCENT),
            )
        }
    }
}

/**
 * Simplified playfield: four lanes with the game's key area and judgement line, but no falling
 * notes — taps are input, the flash is the only feedback. Geometry and pointer lifecycle match
 * the game exactly ([RhythmPlayfieldGeometry], [RhythmLaneTouchTracker]), so the lane a tap lands
 * in is the lane the game would have judged.
 */
@Composable
private fun RecordPlayfield(
    touchTracker: RhythmLaneTouchTracker,
    flashes: List<RecordTapFlash>,
    flashNowNanos: Long,
    inputEnabled: Boolean,
    onLaneDown: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(inputEnabled, touchTracker) {
        if (!inputEnabled) touchTracker.clear()
    }

    Canvas(
        modifier = modifier.pointerInput(inputEnabled, touchTracker) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent()
                    val geometry = RhythmPlayfieldGeometry(size.width.toFloat(), size.height.toFloat())
                    event.changes.forEach { change ->
                        val pointerId = change.id.value
                        if (change.pressed && !change.previousPressed) {
                            if (inputEnabled) {
                                val lane = geometry.laneAt(change.position.x, change.position.y)
                                if (lane != null && touchTracker.press(pointerId, lane)) {
                                    onLaneDown(lane)
                                }
                            }
                        } else if (!change.pressed) {
                            touchTracker.release(pointerId)
                        }
                    }
                }
            }
        },
    ) {
        val geometry = RhythmPlayfieldGeometry(size.width, size.height)
        val laneWidth = geometry.laneWidthPx
        val keyHeight = size.height - geometry.hitLineY
        val pressed = touchTracker.pressedLanes()

        drawRect(RECORD_BACKGROUND)
        for (lane in 0 until RHYTHM_LANE_COUNT) {
            val x = lane * laneWidth
            val laneColors = RECORD_LANE_KEYS[lane]
            drawRect(
                color = if (lane % 2 == 0) RECORD_LANES else RECORD_LANES_ALT,
                topLeft = Offset(x, 0f),
                size = Size(laneWidth, size.height),
            )
            if (lane > 0) {
                drawLine(
                    color = RECORD_SEPARATOR.copy(alpha = 0.65f),
                    start = Offset(x, 0f),
                    end = Offset(x, size.height),
                    strokeWidth = 1f,
                )
            }
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        laneColors.copy(alpha = 0.55f),
                        laneColors.copy(alpha = 0.78f),
                    ),
                    startY = geometry.hitLineY,
                    endY = size.height,
                ),
                topLeft = Offset(x, geometry.hitLineY),
                size = Size(laneWidth, keyHeight),
            )
            if (pressed[lane]) {
                drawRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.34f),
                            Color.White.copy(alpha = 0.10f),
                        ),
                        startY = geometry.hitLineY,
                        endY = size.height,
                    ),
                    topLeft = Offset(x, geometry.hitLineY),
                    size = Size(laneWidth, keyHeight),
                )
            }
        }

        val effectStrokePx = 3.dp.toPx()
        for (flash in flashes) {
            val elapsedMs = (flashNowNanos - flash.startedAtNanos).coerceAtLeast(0L) / 1_000_000f
            if (elapsedMs > RECORD_FLASH_MS) continue
            val progress = (elapsedMs / RECORD_FLASH_MS).coerceIn(0f, 1f)
            drawCircle(
                color = RECORD_LANE_KEYS[flash.lane].copy(alpha = 0.9f * (1f - progress)),
                radius = laneWidth * (0.18f + 0.30f * progress),
                center = Offset((flash.lane + 0.5f) * laneWidth, geometry.hitLineY),
                style = Stroke(width = effectStrokePx * (1f - progress * 0.35f)),
            )
        }

        drawLine(
            color = Color.White.copy(alpha = 0.82f),
            start = Offset(0f, geometry.hitLineY),
            end = Offset(size.width, geometry.hitLineY),
            strokeWidth = 2f,
        )
    }
}

/** Undo / re-record / finish. No save here: the save panel owns naming and warnings. */
@Composable
private fun RecordControlBar(
    hasHits: Boolean,
    onUndo: () -> Unit,
    onRestart: () -> Unit,
    onFinish: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedButton(
            onClick = onUndo,
            enabled = hasHits,
            modifier = Modifier.weight(1f),
        ) {
            Text(stringResource(R.string.rhythm_record_undo))
        }
        OutlinedButton(
            onClick = onRestart,
            modifier = Modifier.weight(1f),
        ) {
            Text(stringResource(R.string.rhythm_record_restart))
        }
        TextButton(
            onClick = onFinish,
            enabled = hasHits,
            modifier = Modifier.weight(1f),
        ) {
            Text(
                text = stringResource(R.string.rhythm_record_finish),
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

/**
 * Save panel: name, optional constant offset fine-tune, live statistics and the conversion's
 * warnings (merged double taps, tight same-lane pairs, lead-in notes) — nothing is applied
 * silently except the documented merge (manual-tap-chart-mobile-plan.md §6).
 */
@Composable
private fun TapSavePanel(
    defaultName: String,
    audio: RhythmTapAudio,
    hits: List<RhythmTapHit>,
    saving: Boolean,
    onSave: (name: String, offsetMs: Double) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(defaultName) }
    var offsetMs by remember { mutableDoubleStateOf(0.0) }

    val take = remember(hits, audio) {
        RhythmTapTake(
            audio = audio,
            hitCount = hits.size,
            hits = hits,
        )
    }
    // Preview is the exact conversion used on save, so the warnings describe what will be stored.
    val preview = remember(take, name, offsetMs) {
        runCatching { RhythmTapChart.toChart(take, name, offsetMs) }.getOrNull()
    }
    val warnings = preview?.warnings
    val noteCount = preview?.chart?.noteCount() ?: hits.size
    val nps = preview?.chart?.averageNps() ?: 0.0

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.rhythm_record_save_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.rhythm_record_name_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.rhythm_record_offset_label),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedButton(onClick = { offsetMs -= 10.0 }) {
                        Text("−10")
                    }
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.rhythm_generate_offset_value, offsetMs.toInt()),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.width(6.dp))
                    OutlinedButton(onClick = { offsetMs += 10.0 }) {
                        Text("+10")
                    }
                }
                Text(
                    text = stringResource(
                        R.string.rhythm_chart_stats,
                        noteCount,
                        String.format(Locale.US, "%.1f", nps),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                warnings?.mergedCount?.takeIf { it > 0 }?.let { merged ->
                    Text(
                        text = stringResource(R.string.rhythm_record_warn_merged, merged),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                warnings?.closePairs?.takeIf { it.isNotEmpty() }?.let { pairs ->
                    Text(
                        text = stringResource(R.string.rhythm_record_warn_close, pairs.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                warnings?.leadInCount?.takeIf { it > 0 }?.let { leadIn ->
                    Text(
                        text = stringResource(R.string.rhythm_record_warn_lead_in, leadIn),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(name.trim().ifEmpty { defaultName }, offsetMs) },
                enabled = !saving && name.isNotBlank(),
            ) {
                Text(stringResource(R.string.rhythm_record_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !saving) {
                Text(stringResource(R.string.rhythm_record_save_cancel))
            }
        },
    )
}

private const val RECORD_FLASH_MS = 210f

/** Loading / error placeholder, same role as the game screen's message screen. */
@Composable
private fun RecordMessageScreen(message: String, onExit: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = message,
                color = RECORD_HUD_TEXT,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
            TextButton(onClick = onExit) {
                Text(stringResource(R.string.rhythm_record_exit))
            }
        }
    }
}

/** Key-area colour per lane, matching the game's lane identity. */
private val RECORD_LANE_KEYS = listOf(
    Color(0xFF54E8FF),
    Color(0xFFFF7ADF),
    Color(0xFF63FFB0),
    Color(0xFFFFC466),
)
