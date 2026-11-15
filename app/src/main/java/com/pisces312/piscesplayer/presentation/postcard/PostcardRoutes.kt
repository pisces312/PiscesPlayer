/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Original work in 2026, sound_postcard feature
 * (RhythmChartLab docs/design/sound-postcard-journey-design.md §11, §11.1, §11.2).
 */
package com.pisces312.piscesplayer.presentation.postcard

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pisces312.piscesplayer.data.database.PostcardVisitEntity
import com.pisces312.piscesplayer.data.postcard.PostcardTrackingBridge
import com.pisces312.piscesplayer.data.postcard.model.PostcardContent
import kotlinx.coroutines.launch

/**
 * Hosts of the M0 screens. The routes stay separate (`postcard_inbox`, `postcard_album`,
 * `postcard_card/{cardKey}`) because the hub card and the album jump in independently; these
 * composables only wire the shared [PostcardViewModel].
 *
 * Navigation model (deliberately flat for the slice):
 * - the inbox lists journeys; opening one jumps to the first card it delivered;
 * - the album lists cards; opening one shows its front/back and visit history.
 */
@Composable
fun PostcardInboxRoute(
    onBack: () -> Unit,
    onOpenAlbum: () -> Unit,
    onOpenCard: (String) -> Unit,
    viewModel: PostcardViewModel = hiltViewModel(),
) {
    val journeys by viewModel.inbox.collectAsStateWithLifecycle()
    val album by viewModel.album.collectAsStateWithLifecycle()
    val trackingStatus by PostcardTrackingBridge.status.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    PostcardInboxScreen(
        journeys = journeys,
        cardCount = album.size,
        trackingStatus = trackingStatus,
        onTakeAlong = { PostcardTrackingBridge.register() },
        onCancelTracking = { PostcardTrackingBridge.cancel() },
        onBack = onBack,
        onOpenJourney = { journeyId ->
            scope.launch {
                val cards = viewModel.cardsOf(journeyId)
                viewModel.markOpened(journeyId)
                cards.firstOrNull()?.let { onOpenCard(it.card.cardKey) }
            }
        },
        onOpenAlbum = onOpenAlbum,
    )
}

@Composable
fun PostcardAlbumRoute(
    onBack: () -> Unit,
    onOpenCard: (String) -> Unit,
    viewModel: PostcardViewModel = hiltViewModel(),
) {
    val album by viewModel.album.collectAsStateWithLifecycle()
    PostcardAlbumScreen(rows = album, onBack = onBack, onOpenCard = onOpenCard)
}

@Composable
fun PostcardDetailRoute(
    cardKey: String,
    onBack: () -> Unit,
    viewModel: PostcardViewModel = hiltViewModel(),
) {
    // Read the card once per key; LaunchedEffect keeps the load out of composition.
    androidx.compose.runtime.LaunchedEffect(cardKey) {
        viewModel.openCard(cardKey)
    }
    val detail by viewModel.detail.collectAsState()
    val current = detail?.takeIf { it.card.cardKey == cardKey }

    if (current == null) {
        DetailPlaceholder(onBack = onBack)
        return
    }

    PostcardDetailScreen(
        title = current.card.songTitleSnapshot,
        conditionKey = current.card.conditionKey,
        spotLabel = spotLabelOf(current.card.regionId, current.card.spotId),
        distanceKm = current.card.distanceKm,
        spec = current.spec,
        visitCount = current.visitCount,
        visits = current.visits,
        firstVisitedAtEpochMs = current.card.firstVisitedAtEpochMs,
        zoneId = current.card.firstVisitedAtZoneId,
        onBack = onBack,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DetailPlaceholder(onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("明信片") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = "返回",
                        )
                    }
                },
            )
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            Text(
                text = "正在读取明信片…",
                modifier = Modifier.padding(20.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

/** Region + spot display name from the versioned content config, defensive on unknown ids. */
internal fun spotLabelOf(regionId: String, spotId: String): String {
    val region = PostcardContent.REGION_BY_ID[regionId] ?: return spotId
    val spot = region.spotById(spotId) ?: return region.nameZh
    return "${region.nameZh} · ${spot.nameZh}"
}
