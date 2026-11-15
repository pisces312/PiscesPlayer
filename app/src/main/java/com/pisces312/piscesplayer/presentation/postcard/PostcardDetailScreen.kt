/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Original work in 2026, sound_postcard feature
 * (RhythmChartLab docs/design/sound-postcard-journey-design.md §11.2).
 */
package com.pisces312.piscesplayer.presentation.postcard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pisces312.piscesplayer.data.database.PostcardVisitEntity

/**
 * Card detail / 详情 (design §11.2): front and back faces, the visit history and the song
 * snapshots. Everything shown comes from the stored spec, never from a live re-computation —
 * a renamed song or a regenerated master cannot rewrite an old card.
 *
 * Export, map placement and same-song-other-season comparison are M2/M3.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PostcardDetailScreen(
    title: String,
    conditionKey: String,
    spotLabel: String,
    distanceKm: Float,
    spec: com.pisces312.piscesplayer.data.postcard.model.PostcardSpec?,
    visitCount: Int,
    visits: List<PostcardVisitEntity>,
    firstVisitedAtEpochMs: Long,
    zoneId: String,
    onBack: () -> Unit,
) {
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
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            var side by remember { mutableStateOf(PostcardSide.FRONT) }

            if (visitCount > 1) {
                Text(
                    text = "故地重游 · 第 $visitCount 次",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            PostcardFace(
                spec = spec,
                side = side,
                modifier = Modifier.fillMaxWidth(),
                cornerRadius = 14,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = side == PostcardSide.FRONT,
                    onClick = { side = PostcardSide.FRONT },
                    label = { Text("正面") },
                )
                FilterChip(
                    selected = side == PostcardSide.BACK,
                    onClick = { side = PostcardSide.BACK },
                    label = { Text("背面") },
                )
            }

            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    InfoRow("歌曲", title)
                    InfoRow("地点", spotLabel)
                    InfoRow("天气", conditionLabel(conditionKey) + " · 虚构天气")
                    InfoRow("里程", "${formatKm(distanceKm)} 公里（幻想数值）")
                    InfoRow("首访", formatTime(firstVisitedAtEpochMs, zoneId))
                }
            }

            if (visits.size > 1) {
                Text(
                    text = "到访历史",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    ),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        visits.take(8).forEach { visit ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    text = formatTime(visit.visitedAtEpochMs, zoneId),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    text = "${formatKm(visit.distanceKm)} km · ${conditionLabel(visit.conditionKeyForLabel())}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }

            Text(
                text = "本功能不读取真实位置与天气；地点与天气来自虚构邮路。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 1.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = Color.Unspecified,
        )
    }
}

/** Visit rows store the two halves separately; rebuild the `conditionKey` shape for labels. */
private fun PostcardVisitEntity.conditionKeyForLabel(): String = "${timeOfDayKey}_${weatherKey}"
