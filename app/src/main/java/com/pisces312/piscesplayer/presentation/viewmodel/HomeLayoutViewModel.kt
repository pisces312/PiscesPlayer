package com.pisces312.piscesplayer.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pisces312.piscesplayer.data.home.DefaultHomeLayout
import com.pisces312.piscesplayer.data.home.HomeCard
import com.pisces312.piscesplayer.data.home.HomeCardSize
import com.pisces312.piscesplayer.data.home.HomeCardType
import com.pisces312.piscesplayer.data.home.HomeLayoutRepository
import com.pisces312.piscesplayer.data.preferences.PlaylistPreferencesRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

/** The edit-mode row backing one「×」badge +「撤销」snackbar. */
data class HomeCardRemoved(
    val card: HomeCard,
    val index: Int,
)

/** A distinct event that a card was added, so the UI can toast once per add. */
data class HomeCardAdded(val card: HomeCard)

@HiltViewModel
class HomeLayoutViewModel @Inject constructor(
    private val homeLayoutRepository: HomeLayoutRepository,
    playlistPreferencesRepository: PlaylistPreferencesRepository,
) : ViewModel() {

    private val _cards = MutableStateFlow(DefaultHomeLayout)
    val cards: StateFlow<ImmutableList<HomeCard>> =
        _cards.map { it.toPersistentList() }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), persistentListOf())

    /** Local mirror of the user's playlists, so the grid can resolve PLAYLIST cards and the add
     *  sheet can list candidates. Sourced from the same Room flow the playlists tab uses. */
    val userPlaylists = playlistPreferencesRepository.userPlaylistsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _removedEvent = MutableStateFlow<HomeCardRemoved?>(null)
    val removedEvent: StateFlow<HomeCardRemoved?> = _removedEvent.asStateFlow()

    private val _addedEvent = MutableStateFlow<HomeCardAdded?>(null)
    val addedEvent: StateFlow<HomeCardAdded?> = _addedEvent.asStateFlow()

    init {
        viewModelScope.launch {
            homeLayoutRepository.cardsFlow.collect { stored ->
                _cards.value = stored
            }
        }
    }

    /** True when a feature card of this type is already on the grid (fixed types cannot repeat). */
    fun isTypePresent(type: HomeCardType): Boolean = _cards.value.any { it.type == type }

    /** True when a card for this exact playlist is already on the grid (playlists de-dupe by id). */
    fun isPlaylistPresent(playlistId: String): Boolean =
        _cards.value.any { it.type == HomeCardType.PLAYLIST && it.targetId == playlistId }

    /** Adds a feature card; no-op if that type is already present (plan §11 decision 1). */
    fun addCard(type: HomeCardType, defaultSize: HomeCardSize) {
        if (isTypePresent(type)) return
        addCardInternal(HomeCard(uid = UUID.randomUUID().toString(), type = type, size = defaultSize))
    }

    /** Adds a card for a specific playlist; no-op if that playlist is already pinned. */
    fun addPlaylistCard(playlistId: String, defaultSize: HomeCardSize = HomeCardSize.S_1x1) {
        if (isPlaylistPresent(playlistId)) return
        addCardInternal(
            HomeCard(
                uid = UUID.randomUUID().toString(),
                type = HomeCardType.PLAYLIST,
                targetId = playlistId,
                size = defaultSize,
            )
        )
    }

    private fun addCardInternal(card: HomeCard) {
        _cards.update { it + card }
        persist()
        _addedEvent.value = HomeCardAdded(card)
    }

    /** Removes the card at [index] and exposes it on [removedEvent] for the undo snackbar. */
    fun removeAt(index: Int) {
        val snapshot = _cards.value
        if (index !in snapshot.indices) return
        val removed = snapshot[index]
        _cards.update { it.toMutableList().apply { removeAt(index) } }
        persist()
        _removedEvent.value = HomeCardRemoved(card = removed, index = index)
    }

    /** Re-inserts the most recently removed card (snackbar「撤销」). */
    fun undoRemove() {
        val event = _removedEvent.value ?: return
        _cards.update {
            it.toMutableList().apply {
                add(event.index.coerceIn(0, size), event.card)
            }
        }
        persist()
        _removedEvent.value = null
    }

    fun move(fromIndex: Int, toIndex: Int) {
        _cards.update {
            if (fromIndex !in it.indices || toIndex !in it.indices || fromIndex == toIndex) return@update it
            it.toMutableList().apply { add(toIndex, removeAt(fromIndex)) }
        }
        // No persist() on every drag frame; the drop / edit exit persists once.
    }

    /** Moves the card with [uid] to the slot index implied by its pointer position. */
    fun moveCard(uid: String, toIndex: Int) {
        val fromIndex = _cards.value.indexOfFirst { it.uid == uid }
        if (fromIndex >= 0) move(fromIndex, toIndex)
    }

    /** Cycles the card size 1×1 → 2×1 → 2×2 → 1×2 (plan §11 decision 2). */
    fun cycleSize(uid: String) {
        _cards.update { list ->
            list.map { card ->
                if (card.uid == uid) {
                    card.copy(size = nextSize(card.size))
                } else {
                    card
                }
            }
        }
        persist()
    }

    fun restoreDefault() {
        _cards.value = DefaultHomeLayout
        _removedEvent.value = null
        persist()
    }

    /** Persists the current in-memory layout (called on drop / edit-exit / destructive edits). */
    fun persist() {
        val snapshot = _cards.value
        viewModelScope.launch {
            homeLayoutRepository.saveCards(snapshot)
        }
    }

    fun clearRemovedEvent() {
        _removedEvent.value = null
    }

    fun clearAddedEvent() {
        _addedEvent.value = null
    }

    private fun nextSize(size: HomeCardSize): HomeCardSize = when (size) {
        HomeCardSize.S_1x1 -> HomeCardSize.W_2x1
        HomeCardSize.W_2x1 -> HomeCardSize.L_2x2
        HomeCardSize.L_2x2 -> HomeCardSize.T_1x2
        HomeCardSize.T_1x2 -> HomeCardSize.S_1x1
    }
}
