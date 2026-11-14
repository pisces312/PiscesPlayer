package com.pisces312.piscesplayer.data.musicquiz

import java.util.Date
import java.util.Locale
import java.util.TimeZone
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MusicQuizShareTextTest {

    private val strings = MusicQuizShareText.Strings(
        header = "HEADER",
        recordMarker = " (NEW)",
        statsFormat = "S %1\$d C %2\$d/%3\$d ST %4\$d AVG %5\$.1f",
        statsNoAverageFormat = "S %1\$d C %2\$d/%3\$d ST %4\$d",
        callToAction = "CTA",
    )

    @Test
    fun `build includes stats, call to action and project link`() {
        val text = MusicQuizShareText.build(
            score = 8_650,
            correctCount = 8,
            totalRounds = 10,
            maxStreak = 5,
            averageCorrectSeconds = 3.2f,
            isNewBestScore = false,
            strings = strings,
        )

        val lines = text.lines()
        assertEquals(4, lines.size)
        assertEquals("HEADER", lines[0])
        assertEquals("S 8650 C 8/10 ST 5 AVG 3.2", lines[1])
        assertEquals("CTA", lines[2])
        assertEquals(MusicQuizShareText.PROJECT_LINK, lines[3])
        assertTrue(text.contains("github.com/pisces312/PiscesPlayer"))
    }

    @Test
    fun `new record marker is appended to the header`() {
        val text = MusicQuizShareText.build(
            score = 100,
            correctCount = 1,
            totalRounds = 10,
            maxStreak = 1,
            averageCorrectSeconds = 9.9f,
            isNewBestScore = true,
            strings = strings,
        )

        assertTrue(text.lineSequence().first().contains("HEADER (NEW)"))
    }

    @Test
    fun `no average line when nothing was answered correctly`() {
        val line = MusicQuizShareText.statsLine(
            score = 0,
            correctCount = 0,
            totalRounds = 10,
            maxStreak = 0,
            averageCorrectSeconds = null,
            strings = strings,
        )

        assertEquals("S 0 C 0/10 ST 0", line)
    }

    @Test
    fun `timestamp suffix uses yyyyMMdd-HHmm`() {
        val formatter = java.text.SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("Asia/Shanghai")
        }
        val date = formatter.parse("20261114-0913") as Date

        assertEquals("20261114-0913", MusicQuizShareText.timestampSuffix(date))
    }

    @Test
    fun `stats line omits average when null`() {
        val withAverage = MusicQuizShareText.statsLine(1, 1, 10, 1, 2.5f, strings)
        assertTrue(withAverage.contains("AVG 2.5"))
        assertFalse(MusicQuizShareText.statsLine(1, 1, 10, 1, null, strings).contains("AVG"))
    }
}
