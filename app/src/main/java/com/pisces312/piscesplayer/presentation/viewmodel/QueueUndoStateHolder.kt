package com.pisces312.piscesplayer.presentation.viewmodel

import androidx.media3.common.Player
import androidx.media3.session.MediaController
import com.pisces312.piscesplayer.data.model.Song
import com.pisces312.piscesplayer.utils.MediaItemBuilder
import dagger.hilt.android.scopes.ViewModelScoped
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@ViewModelScoped
class QueueUndoStateHolder @Inject constructor() {
    private var queueItemUndoTimerJob: Job? = null

    /**
     * Removes [songId] from the playing queue, keeping the data needed to put it back.
     *
     * @param queueSource player to **read** the queue from — the engine's master player, never
     *   [mediaController]: while the car lyric title hides the playlist from session consumers, a
     *   controller sees a single-item queue (`docs/car-lyrics-avrcp-gate.md` §5.2) and no song but
     *   the current one would be found. Writes still go through the controller.
     */
    fun removeSongFromQueue(
        scope: CoroutineScope,
        mediaController: MediaController?,
        queueSource: Player,
        songId: String,
        getUiState: () -> PlayerUiState,
        updateUiState: (((PlayerUiState) -> PlayerUiState) -> Unit),
    ) {
        val controller = mediaController ?: return
        
        var indexToRemove = -1
        for (i in 0 until queueSource.mediaItemCount) {
            if (queueSource.getMediaItemAt(i).mediaId == songId) {
                indexToRemove = i
                break
            }
        }
        
        if (indexToRemove == -1) return

        val currentQueue = getUiState().currentPlaybackQueue
        val removedSong = currentQueue.find { it.id == songId } ?: return

        controller.removeMediaItem(indexToRemove)

        updateUiState {
            it.copy(
                showQueueItemUndoBar = true,
                lastRemovedQueueSong = removedSong,
                lastRemovedQueueIndex = indexToRemove
            )
        }

        queueItemUndoTimerJob?.cancel()
        queueItemUndoTimerJob = scope.launch {
            delay(4000L)
            if (getUiState().showQueueItemUndoBar) {
                updateUiState {
                    it.copy(
                        showQueueItemUndoBar = false,
                        lastRemovedQueueSong = null,
                        lastRemovedQueueIndex = -1
                    )
                }
            }
        }
    }

    fun undoRemoveSongFromQueue(
        mediaController: MediaController?,
        queueSource: Player,
        getUiState: () -> PlayerUiState,
        updateUiState: (((PlayerUiState) -> PlayerUiState) -> Unit),
    ) {
        val uiState = getUiState()
        val song = uiState.lastRemovedQueueSong ?: return
        val index = uiState.lastRemovedQueueIndex
        if (index < 0) return

        mediaController?.let { controller ->
            val mediaItem = MediaItemBuilder.build(song)
            val insertAt = index.coerceAtMost(queueSource.mediaItemCount)
            controller.addMediaItem(insertAt, mediaItem)
        }

        hideQueueItemUndoBar(updateUiState)
    }

    fun hideQueueItemUndoBar(
        updateUiState: (((PlayerUiState) -> PlayerUiState) -> Unit),
    ) {
        queueItemUndoTimerJob?.cancel()
        updateUiState {
            it.copy(
                showQueueItemUndoBar = false,
                lastRemovedQueueSong = null,
                lastRemovedQueueIndex = -1
            )
        }
    }

    fun onCleared() {
        queueItemUndoTimerJob?.cancel()
    }
}
