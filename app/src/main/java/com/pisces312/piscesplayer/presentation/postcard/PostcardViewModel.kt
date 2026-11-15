/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Original work in 2026, sound_postcard feature
 * (RhythmChartLab docs/design/sound-postcard-journey-design.md §11, §11.1, §11.2).
 */
package com.pisces312.piscesplayer.presentation.postcard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pisces312.piscesplayer.data.database.PostcardCardEntity
import com.pisces312.piscesplayer.data.database.PostcardJourneyEntity
import com.pisces312.piscesplayer.data.postcard.CardView
import com.pisces312.piscesplayer.data.postcard.PostcardRepository
import com.pisces312.piscesplayer.data.postcard.model.PostcardSpec
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** One card row in the album, already decoded for the list. */
data class AlbumRow(
    val cardKey: String,
    val spec: PostcardSpec?,
    val title: String,
    val artist: String,
    val spotId: String,
    val conditionKey: String,
    val visitCount: Int,
    val firstVisitedAtEpochMs: Long,
)

/** One letter in the inbox: the journey plus the cards it carries. */
data class InboxLetter(
    val journey: PostcardJourneyEntity,
    val cards: List<CardView>,
)

@HiltViewModel
class PostcardViewModel @Inject constructor(
    private val repository: PostcardRepository,
) : ViewModel() {

    private val now = System.currentTimeMillis()

    /** M0 delivers immediately, so the inbox is every settled journey. */
    val inbox: StateFlow<List<PostcardJourneyEntity>> = repository.inbox(now)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val album: StateFlow<List<AlbumRow>> = repository.album()
        .map { cards -> cards.map(::toAlbumRow) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _detail = MutableStateFlow<CardDetail?>(null)
    val detail: StateFlow<CardDetail?> = _detail.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    init {
        viewModelScope.launch { repository.ensureProfile() }
    }

    /** Opens a card: the stored first-visit spec plus its visit history. */
    fun openCard(cardKey: String) {
        viewModelScope.launch {
            _loading.value = true
            val view = repository.cardByKey(cardKey)
            _detail.value = view?.let {
                CardDetail(
                    card = it.card,
                    spec = it.spec,
                    visitCount = it.visits.size.coerceAtLeast(it.card.visitCount),
                    visits = it.visits,
                )
            }
            _loading.value = false
        }
    }

    /** Loads the cards of one letter so the open animation has content (no extra DB writes). */
    suspend fun cardsOf(journeyId: String): List<CardView> = repository.cardsForJourney(journeyId)

    fun markOpened(journeyId: String) {
        viewModelScope.launch { repository.markJourneyOpened(journeyId) }
    }

    private fun toAlbumRow(card: PostcardCardEntity): AlbumRow = AlbumRow(
        cardKey = card.cardKey,
        spec = repository.decodeSpec(card.specJson),
        title = card.songTitleSnapshot,
        artist = card.artistSnapshot,
        spotId = card.spotId,
        conditionKey = card.conditionKey,
        visitCount = card.visitCount,
        firstVisitedAtEpochMs = card.firstVisitedAtEpochMs,
    )
}

/** Detail payload for the card screen. */
data class CardDetail(
    val card: PostcardCardEntity,
    val spec: PostcardSpec?,
    val visitCount: Int,
    val visits: List<com.pisces312.piscesplayer.data.database.PostcardVisitEntity>,
)
