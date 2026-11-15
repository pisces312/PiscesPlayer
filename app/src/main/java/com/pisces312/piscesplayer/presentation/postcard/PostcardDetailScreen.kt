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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pisces312.piscesplayer.R
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
    // Configuration-backed locale so a runtime language switch recomposes the timestamps.
    val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.postcard_detail_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.auth_cd_back),
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
                    text = stringResource(R.string.postcard_revisit, visitCount),
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
                    label = { Text(stringResource(R.string.postcard_side_front)) },
                )
                FilterChip(
                    selected = side == PostcardSide.BACK,
                    onClick = { side = PostcardSide.BACK },
                    label = { Text(stringResource(R.string.postcard_side_back)) },
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
                    InfoRow(stringResource(R.string.postcard_label_song), title)
                    InfoRow(stringResource(R.string.postcard_label_spot), spotLabel)
                    InfoRow(
                        stringResource(R.string.postcard_label_weather),
                        stringResource(
                            R.string.postcard_fantasy_weather,
                            conditionLabel(conditionKey),
                        ),
                    )
                    InfoRow(
                        stringResource(R.string.postcard_label_distance),
                        stringResource(
                            R.string.postcard_fantasy_distance,
                            formatKm(distanceKm),
                        ),
                    )
                    InfoRow(
                        stringResource(R.string.postcard_label_first_visit),
                        formatTime(
                            firstVisitedAtEpochMs,
                            zoneId,
                            stringResource(R.string.postcard_time_format),
                            locale,
                        ),
                    )
                }
            }

            if (visits.size > 1) {
                Text(
                    text = stringResource(R.string.postcard_visit_history),
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
                                    text = formatTime(
                                        visit.visitedAtEpochMs,
                                        zoneId,
                                        stringResource(R.string.postcard_time_format),
                                        locale,
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    text = stringResource(
                                        R.string.postcard_visit_row,
                                        formatKm(visit.distanceKm),
                                        conditionLabel(visit.conditionKeyForLabel()),
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }

            Text(
                text = stringResource(R.string.postcard_privacy_note),
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
