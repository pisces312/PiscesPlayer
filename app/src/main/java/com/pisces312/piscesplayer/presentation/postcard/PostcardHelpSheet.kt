/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Original work in 2026, sound_postcard feature
 * (RhythmChartLab docs/design/sound-postcard-journey-design.md §11.1, §6.2).
 */
package com.pisces312.piscesplayer.presentation.postcard

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pisces312.piscesplayer.R
import com.pisces312.piscesplayer.data.postcard.model.PostcardContent
import com.pisces312.piscesplayer.data.postcard.model.PostcardFigure
import com.pisces312.piscesplayer.data.postcard.model.PostcardPaletteSource
import com.pisces312.piscesplayer.data.postcard.model.PostcardSky
import com.pisces312.piscesplayer.data.postcard.model.PostcardSpec
import com.pisces312.piscesplayer.data.postcard.model.PostcardStamp
import com.pisces312.piscesplayer.data.postcard.model.PostcardVersions
import java.util.Locale

/**
 * "How to play" help, opened from the mailbox top bar. Explains the M0 loop in four steps and
 * shows three sample postcards so a new user sees the reward before earning the first one.
 * The samples are hand-built demo specs — the same [PostcardRenderer] that renders real cards
 * draws them, so the preview is honest about what a finished card looks like.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PostcardHelpSheet(onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        PostcardHelpContent()
    }
}

@Composable
private fun PostcardHelpContent() {
    val languageTag = LocalConfiguration.current.locales[0].toLanguageTag()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = stringResource(R.string.postcard_help_title),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
        )
        HelpStep(number = 1, textRes = R.string.postcard_help_step1)
        HelpStep(number = 2, textRes = R.string.postcard_help_step2)
        HelpStep(number = 3, textRes = R.string.postcard_help_step3)
        HelpStep(number = 4, textRes = R.string.postcard_help_step4)

        Spacer(Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.postcard_help_examples_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = stringResource(R.string.postcard_help_examples_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            demoPostcards(languageTag).forEach { demo ->
                Column(modifier = Modifier.width(272.dp)) {
                    PostcardFace(
                        spec = demo.spec,
                        side = PostcardSide.FRONT,
                        modifier = Modifier.fillMaxWidth(),
                        cornerRadius = 12,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = demo.caption,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.postcard_privacy_note),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(28.dp))
    }
}

@Composable
private fun HelpStep(number: Int, textRes: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = "$number.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = stringResource(textRes),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private class DemoPostcard(val caption: String, val spec: PostcardSpec)

/**
 * Three sample journeys across regions, times of day and weather. Palettes come from the real
 * region fallback palettes and messages from the real template pool, so the samples differ
 * from production cards only in the song and date being fictional.
 */
@Composable
private fun demoPostcards(languageTag: String): List<DemoPostcard> = listOf(
    DemoPostcard(
        caption = spotLabelOf("east_gorge", "east_gorge_01"),
        spec = demoSpec(
            regionId = "east_gorge",
            spotId = "east_gorge_01",
            conditionKey = "morning_sunny",
            cloudLevel = 1,
            moonPhase = null,
            poseId = "figure_walk",
            templateId = "msg_east_gorge_sunny",
            km = 86.5f,
            serial = 128,
            songTitle = stringResource(R.string.postcard_demo_song_1),
            artist = stringResource(R.string.postcard_demo_artist_1),
            languageTag = languageTag,
        ),
    ),
    DemoPostcard(
        caption = spotLabelOf("lamp_harbor", "lamp_harbor_01"),
        spec = demoSpec(
            regionId = "lamp_harbor",
            spotId = "lamp_harbor_01",
            conditionKey = "night_sunny",
            cloudLevel = 0,
            moonPhase = 4,
            poseId = "figure_rest",
            templateId = "msg_lamp_harbor_sunny",
            km = 231.4f,
            serial = 342,
            songTitle = stringResource(R.string.postcard_demo_song_2),
            artist = stringResource(R.string.postcard_demo_artist_2),
            languageTag = languageTag,
        ),
    ),
    DemoPostcard(
        caption = spotLabelOf("mist_valley", "mist_valley_02"),
        spec = demoSpec(
            regionId = "mist_valley",
            spotId = "mist_valley_02",
            conditionKey = "evening_rainy",
            cloudLevel = 2,
            moonPhase = null,
            poseId = "figure_umbrella",
            templateId = "msg_mist_valley_rainy",
            km = 12.8f,
            serial = 7,
            songTitle = stringResource(R.string.postcard_demo_song_3),
            artist = stringResource(R.string.postcard_demo_artist_3),
            languageTag = languageTag,
        ),
    ),
)

private fun demoSpec(
    regionId: String,
    spotId: String,
    conditionKey: String,
    cloudLevel: Int,
    moonPhase: Int?,
    poseId: String,
    templateId: String,
    km: Float,
    serial: Int,
    songTitle: String,
    artist: String,
    languageTag: String,
): PostcardSpec {
    val region = PostcardContent.REGION_BY_ID.getValue(regionId)
    val distance = "%.1f".format(Locale.ROOT, km)
    val message = PostcardContent.messageTextFor(templateId, languageTag)
        ?.replace("{distance}", distance)
        .orEmpty()
    return PostcardSpec(
        specVersion = PostcardVersions.SPEC,
        generatorVersion = PostcardVersions.GENERATOR,
        rendererContentVersion = PostcardVersions.RENDERER_CONTENT,
        cardKey = "demo\u001F$spotId\u001F$conditionKey",
        songSeedHash = "demo",
        spotId = spotId,
        regionId = regionId,
        conditionKey = conditionKey,
        seasonKey = "autumn",
        sky = PostcardSky(
            gradientId = "sky_$conditionKey",
            cloudLevel = cloudLevel,
            moonPhase = moonPhase,
        ),
        far = region.farComponentId,
        mid = "mid_$spotId",
        near = "near_$spotId",
        figure = PostcardFigure(poseId),
        componentIds = emptyList(),
        layoutVariant = 0,
        paletteSource = PostcardPaletteSource.FALLBACK,
        paletteArgb = region.fallbackPaletteArgb,
        stamp = PostcardStamp(
            date = "2026-11-15",
            spotName = region.spotById(spotId)?.nameFor(languageTag) ?: spotId,
            serial = serial,
        ),
        distanceKm = km,
        messageTemplateId = templateId,
        messageArgs = mapOf("distance" to distance),
        messageTextSnapshot = message,
        songTitleSnapshot = songTitle,
        artistSnapshot = artist,
        firstCreatedAtEpochMs = 0L,
        firstCreatedAtZoneId = "GMT",
        stampSerial = serial,
    )
}
