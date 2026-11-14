package com.pisces312.piscesplayer.presentation.musicquiz

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.EmojiEvents
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pisces312.piscesplayer.R
import com.pisces312.piscesplayer.data.musicquiz.MusicQuizRunSummary
import com.pisces312.piscesplayer.data.musicquiz.MusicQuizShareText

private val ShareCorrectColor = Color(0xFF2E7D32)

/**
 * Offscreen-only result card rendered into a PNG for image sharing.
 * Width is fixed so the captured bitmap keeps a stable size.
 */
@Composable
internal fun MusicQuizShareCard(
    summary: MusicQuizRunSummary,
    isNewBestScore: Boolean,
    modifier: Modifier = Modifier,
) {
    val strings = MusicQuizShareText.Strings(
        header = stringResource(R.string.music_quiz_share_header),
        recordMarker = stringResource(R.string.music_quiz_share_new_record),
        statsFormat = stringResource(R.string.music_quiz_share_stats),
        statsNoAverageFormat = stringResource(R.string.music_quiz_share_stats_no_avg),
        callToAction = stringResource(R.string.music_quiz_share_call_to_action),
    )
    Card(
        modifier = modifier.width(360.dp),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Rounded.EmojiEvents,
                    contentDescription = null,
                    modifier = Modifier.size(28.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.music_quiz_share_card_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
            Spacer(Modifier.height(12.dp))
            Text(
                text = summary.score.toString(),
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
            )
            if (isNewBestScore) {
                Text(
                    text = stringResource(R.string.music_quiz_share_new_record),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = MusicQuizShareText.statsLine(
                    score = summary.score,
                    correctCount = summary.correctCount,
                    totalRounds = summary.totalRounds,
                    maxStreak = summary.maxStreak,
                    averageCorrectSeconds = if (summary.correctCount > 0) {
                        summary.averageCorrectAnswerMs / 1_000f
                    } else {
                        null
                    },
                    strings = strings,
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(12.dp))
            HorizontalDivider()
            Spacer(Modifier.height(8.dp))
            summary.records.forEach { record ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = if (record.isCorrect) "✓" else "✗",
                        modifier = Modifier.width(22.dp),
                        color = if (record.isCorrect) {
                            ShareCorrectColor
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = "${record.question.track.title} - ${record.question.track.artist}",
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(
                text = MusicQuizShareText.PROJECT_LINK,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
