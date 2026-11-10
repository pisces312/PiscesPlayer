package com.pisces312.piscesplayer.presentation.components.scoped

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import com.pisces312.piscesplayer.data.model.Song
import com.pisces312.piscesplayer.presentation.components.SaveQueueOverlayData
import com.pisces312.piscesplayer.presentation.components.SongInfoTab
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.collections.immutable.toImmutableList

/**
 * Owns transient overlay state that is coupled to the sheet scene:
 * - save queue modal
 * - song info modal selection
 */
internal class SheetModalOverlayController(
    private val scope: CoroutineScope,
    private val queueSheetControllerProvider: () -> QueueSheetController,
    private val animationDurationMs: Int,
    private val onCollapsePlayerSheet: () -> Unit
) {
    var pendingSaveQueueOverlay: SaveQueueOverlayData? by mutableStateOf(null)
        private set

    var selectedSongForInfo: Song? by mutableStateOf(null)
        private set

    var songInfoInitialTab: SongInfoTab by mutableStateOf(SongInfoTab.OPTIONS)
        private set

    fun updateSelectedSongForInfo(
        song: Song?,
        tab: SongInfoTab = SongInfoTab.OPTIONS
    ) {
        selectedSongForInfo = song
        songInfoInitialTab = if (song != null) tab else SongInfoTab.OPTIONS
    }

    fun dismissSaveQueueOverlay() {
        pendingSaveQueueOverlay = null
    }

    fun launchSaveQueueOverlay(
        songs: List<Song>,
        defaultName: String,
        onConfirm: (String, Set<String>) -> Unit
    ) {
        if (pendingSaveQueueOverlay != null) return
        scope.launch {
            queueSheetControllerProvider().animateTo(false)
            onCollapsePlayerSheet()
            delay(animationDurationMs.toLong())
            pendingSaveQueueOverlay = SaveQueueOverlayData(songs.toImmutableList(), defaultName, onConfirm)
        }
    }
}

@Composable
internal fun rememberSheetModalOverlayController(
    scope: CoroutineScope,
    queueSheetController: QueueSheetController,
    animationDurationMs: Int,
    onCollapsePlayerSheet: () -> Unit
): SheetModalOverlayController {
    val queueSheetControllerState = rememberUpdatedState(queueSheetController)
    val onCollapsePlayerSheetState = rememberUpdatedState(onCollapsePlayerSheet)

    return remember(scope, animationDurationMs) {
        SheetModalOverlayController(
            scope = scope,
            queueSheetControllerProvider = { queueSheetControllerState.value },
            animationDurationMs = animationDurationMs,
            onCollapsePlayerSheet = { onCollapsePlayerSheetState.value() }
        )
    }
}
