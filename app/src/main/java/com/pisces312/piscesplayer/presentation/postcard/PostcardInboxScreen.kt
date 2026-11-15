/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Original work in 2026, sound_postcard feature
 * (RhythmChartLab docs/design/sound-postcard-journey-design.md §11, §11.1).
 */
package com.pisces312.piscesplayer.presentation.postcard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.HelpOutline
import androidx.compose.material.icons.rounded.MailOutline
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pisces312.piscesplayer.R
import com.pisces312.piscesplayer.data.database.PostcardJourneyEntity
import com.pisces312.piscesplayer.data.postcard.PostcardTrackingBridge
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The mailbox (design §11.1). M0 shows every settled journey because delivery is immediate;
 * M1 keeps the same list and only filters on `deliverAt <= now`.
 *
 * Each row is a letter, not a card: opening it leads to the first card of the journey. Cards
 * are already stored at settlement, so closing or killing the app never loses one (design §11.2).
 * All copy is localized through `strings_postcard.xml`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PostcardInboxScreen(
    journeys: List<PostcardJourneyEntity>,
    cardCount: Int,
    trackingStatus: PostcardTrackingBridge.Status,
    onTakeAlong: () -> Unit,
    onCancelTracking: () -> Unit,
    onBack: () -> Unit,
    onOpenJourney: (String) -> Unit,
    onOpenAlbum: () -> Unit,
) {
    var showHelp by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.postcard_inbox_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.auth_cd_back),
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { showHelp = true }) {
                        Icon(
                            imageVector = Icons.Rounded.HelpOutline,
                            contentDescription = stringResource(R.string.postcard_help_cd),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            // Manual registration (design §4.4 / §11.1): registers the currently playing
            // local song from its current position. It never starts, pauses or reorders
            // playback — the button only tells the service-side observer to start tracking.
            TakeAlongCard(
                status = trackingStatus,
                onTakeAlong = onTakeAlong,
                onCancel = onCancelTracking,
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (journeys.isEmpty()) {
                        stringResource(R.string.postcard_inbox_empty)
                    } else {
                        stringResource(R.string.postcard_inbox_count, journeys.size, cardCount)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                FilledTonalButton(onClick = onOpenAlbum) {
                    Text(stringResource(R.string.postcard_go_album))
                }
            }

            if (journeys.isEmpty()) {
                EmptyState()
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(journeys, key = { it.journeyId }) { journey ->
                        LetterRow(journey = journey, onClick = { onOpenJourney(journey.journeyId) })
                    }
                }
            }
        }
    }

    if (showHelp) {
        PostcardHelpSheet(onDismiss = { showHelp = false })
    }
}

@Composable
private fun TakeAlongCard(
    status: PostcardTrackingBridge.Status,
    onTakeAlong: () -> Unit,
    onCancel: () -> Unit,
) {
    val tracking = status == PostcardTrackingBridge.Status.TRACKING
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (tracking) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainer
            },
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(
                    if (tracking) R.string.postcard_tracking_title else R.string.postcard_take_along_title,
                ),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(
                    if (tracking) R.string.postcard_tracking_hint else R.string.postcard_take_along_hint,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            if (tracking) {
                FilledTonalButton(onClick = onCancel) {
                    Text(stringResource(R.string.postcard_cancel_tracking))
                }
            } else {
                FilledTonalButton(onClick = onTakeAlong) {
                    Text(stringResource(R.string.postcard_take_along_action))
                }
            }
            if (status == PostcardTrackingBridge.Status.UNAVAILABLE) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.postcard_unavailable),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun EmptyState() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Rounded.MailOutline,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.postcard_inbox_empty_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.postcard_inbox_empty_hint_2),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun LetterRow(journey: PostcardJourneyEntity, onClick: () -> Unit) {
    val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        shape = RoundedCornerShape(14.dp),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Rounded.MailOutline,
                contentDescription = null,
                modifier = Modifier.size(32.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(
                        R.string.postcard_letter_from,
                        formatKm(journey.distanceKm),
                    ),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(
                        R.string.postcard_letter_detail,
                        journey.cardCount,
                        formatTime(
                            journey.completedAtEpochMs,
                            journey.completedAtZoneId,
                            stringResource(R.string.postcard_time_format),
                            locale,
                        ),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

internal fun formatKm(km: Float): String =
    "%.1f".format(Locale.ROOT, km)

/**
 * Formats a first-visit timestamp with a locale-supplied pattern. The pattern (not a hardcoded
 * `M月d日`) comes from `postcard_time_format`, so the Chinese and English builds both read
 * naturally while the stored epoch/zone stay locale-independent.
 */
internal fun formatTime(epochMs: Long, zoneId: String, pattern: String, locale: Locale): String {
    val zone = runCatching { ZoneId.of(zoneId) }.getOrDefault(ZoneId.systemDefault())
    val formatter = runCatching { DateTimeFormatter.ofPattern(pattern, locale) }
        .getOrDefault(DateTimeFormatter.ofPattern("MMM d, HH:mm", Locale.US))
    return formatter.format(Instant.ofEpochMilli(epochMs).atZone(zone))
}
