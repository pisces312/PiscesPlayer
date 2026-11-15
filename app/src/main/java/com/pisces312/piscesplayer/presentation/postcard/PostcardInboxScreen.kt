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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pisces312.piscesplayer.data.database.PostcardJourneyEntity
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The mailbox / 信箱 (design §11.1). M0 shows every settled journey because delivery is
 * immediate; M1 keeps the same list and only filters on `deliverAt <= now`.
 *
 * Each row is a letter, not a card: opening it leads to the first card of the journey. Cards
 * are already stored at settlement, so closing or killing the app never loses one (design §11.2).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PostcardInboxScreen(
    journeys: List<PostcardJourneyEntity>,
    cardCount: Int,
    trackingStatus: com.pisces312.piscesplayer.data.postcard.PostcardTrackingBridge.Status,
    onTakeAlong: () -> Unit,
    onCancelTracking: () -> Unit,
    onBack: () -> Unit,
    onOpenJourney: (String) -> Unit,
    onOpenAlbum: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("信箱") },
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
                    text = if (journeys.isEmpty()) "还没有来信" else "${journeys.size} 封来信 · 相册 $cardCount 张",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                FilledTonalButton(onClick = onOpenAlbum) {
                    Text("去相册")
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
}

@Composable
private fun TakeAlongCard(
    status: com.pisces312.piscesplayer.data.postcard.PostcardTrackingBridge.Status,
    onTakeAlong: () -> Unit,
    onCancel: () -> Unit,
) {
    val tracking = status == com.pisces312.piscesplayer.data.postcard.PostcardTrackingBridge.Status.TRACKING
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
                text = if (tracking) "这首歌正在随行" else "从当前播放开始",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = if (tracking) {
                    "自然听完会寄出明信片；暂停可以继续，切歌不算。"
                } else {
                    "照常听歌即可，不需要盯着屏幕。全部保存在本机。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            if (tracking) {
                FilledTonalButton(onClick = onCancel) {
                    Text("取消随行")
                }
            } else {
                FilledTonalButton(onClick = onTakeAlong) {
                    Text("为当前歌曲带上行李")
                }
            }
            if (status == com.pisces312.piscesplayer.data.postcard.PostcardTrackingBridge.Status.UNAVAILABLE) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "当前歌曲无法随行（云端 / 试听 / 已在随行中）",
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
                text = "照常听歌，自然听完才会有明信片寄出。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "暂停可以继续，切歌不算。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun LetterRow(journey: PostcardJourneyEntity, onClick: () -> Unit) {
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
                    text = "一封来自 ${formatKm(journey.distanceKm)} 公里外的明信片",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${journey.cardCount} 张 · ${formatTime(journey.completedAtEpochMs, journey.completedAtZoneId)}",
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

private val TIME_FORMAT = DateTimeFormatter.ofPattern("M月d日 HH:mm", Locale.CHINA)

internal fun formatTime(epochMs: Long, zoneId: String): String {
    val zone = runCatching { ZoneId.of(zoneId) }.getOrDefault(ZoneId.systemDefault())
    return TIME_FORMAT.format(Instant.ofEpochMilli(epochMs).atZone(zone))
}
