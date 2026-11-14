package com.pisces312.piscesplayer.data.musicquiz

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Pure composition rules for the result screen's share text and default playlist name.
 * Localized wording is resolved by the caller and passed in; this object only assembles.
 */
internal object MusicQuizShareText {
    const val PROJECT_LINK = "https://github.com/pisces312/PiscesPlayer"

    /** Localized pieces the screen resolves from string resources. */
    data class Strings(
        val header: String,
        val recordMarker: String,
        val statsFormat: String,
        val statsNoAverageFormat: String,
        val callToAction: String,
    )

    fun statsLine(
        score: Int,
        correctCount: Int,
        totalRounds: Int,
        maxStreak: Int,
        averageCorrectSeconds: Float?,
        strings: Strings,
    ): String = if (averageCorrectSeconds == null) {
        String.format(
            Locale.getDefault(),
            strings.statsNoAverageFormat,
            score,
            correctCount,
            totalRounds,
            maxStreak,
        )
    } else {
        String.format(
            Locale.getDefault(),
            strings.statsFormat,
            score,
            correctCount,
            totalRounds,
            maxStreak,
            averageCorrectSeconds,
        )
    }

    fun build(
        score: Int,
        correctCount: Int,
        totalRounds: Int,
        maxStreak: Int,
        averageCorrectSeconds: Float?,
        isNewBestScore: Boolean,
        strings: Strings,
    ): String {
        val header = if (isNewBestScore) strings.header + strings.recordMarker else strings.header
        return listOf(
            header,
            statsLine(score, correctCount, totalRounds, maxStreak, averageCorrectSeconds, strings),
            strings.callToAction,
            PROJECT_LINK,
        ).joinToString("\n")
    }

    /** Timestamp fragment for the default playlist name and share image filename: yyyyMMdd-HHmm. */
    fun timestampSuffix(date: Date): String =
        SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(date)
}
