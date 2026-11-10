package com.pisces312.piscesplayer.utils

import com.pisces312.piscesplayer.data.model.SyncedLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The blank-marker rule at the scale of a real lyrics file, driven through the real parser.
 *
 * The hand-made fixtures in [LyricsTimelineUtilsTest] prove the rule one case at a time; this one
 * proves it survives a file that was written by whoever produced the track. Its shape comes from a
 * 4:25 song whose LRC carries 105 lines, **44** of them markers: `lyrics/blank-marker-shape.lrc`
 * keeps that file's timestamps and, line for line, its width in title columns, with the words
 * replaced by filler. Words are what a suite must not check in, and they are also the one thing
 * this rule never looks at — the timeline follows timestamps, marker positions and column widths,
 * which the fixture reproduces exactly, so every cue below falls on the millisecond it falls on in
 * the song. Regenerating it is described in `docs/car-lyrics-blank-markers.md` §6.
 *
 * The numbers asserted here are the ones the emulator run measured (`loaded 105 synced lines as
 * 89 cues`, the title handed back at cue 9 / 28 / 51), so a regression that would take four
 * minutes and a debug build to catch on a device fails here in milliseconds.
 */
class LyricsTimelineUtilsRealFileTest {

    /** Parsed by the application's own parser: what it does with a marker line is load-bearing. */
    private val syncedLines: List<SyncedLine> = LyricsUtils
        .parseLyrics(
            requireNotNull(javaClass.getResourceAsStream("/lyrics/blank-marker-shape.lrc")) {
                "missing test fixture lyrics/blank-marker-shape.lrc"
            }.bufferedReader(Charsets.UTF_8).use { it.readText() }
        )
        .synced
        .orEmpty()

    private val trackDurationMs = 265_384L

    private val cues = buildLyricCues(syncedLines, trackDurationMs, maxColumnsPerCue = 30)

    private val markers = syncedLines.filter { it.line.isBlank() }

    private val markerTimes = markers.map { it.time.toLong() }

    /** The markers that do hand the title back — instrumentals, not breaths. */
    private val clearingMarkers = listOf(19_510L, 89_820L, 192_930L)

    // --- the fixture itself ---

    @Test
    fun theFixtureSurvivesTheParserWithItsMarkersIntact() {
        // If the parser started dropping text-less lines, the rule under test would have nothing
        // to skip and this suite would go green while the feature broke on every device.
        assertEquals(105, syncedLines.size)
        assertEquals(44, markers.size)
        assertTrue(clearingMarkers.all { it in markerTimes })
    }

    // --- the rule, at real density ---

    @Test
    fun clearsTheTitleThreeTimesInAWholeSongNotOncePerBreath() {
        val clearing = cues.withIndex().filter { it.value.text.isEmpty() }

        // Forty-four markers, three of them handed the title back: before the fix every one of the
        // 41 breaths did too, which is the "title alternates with the song name" report.
        assertEquals(listOf(9, 28, 51), clearing.map { it.index })
        assertEquals(clearingMarkers, clearing.map { it.value.timeMs })
    }

    @Test
    fun keepsTheLineOnScreenAcrossEveryBreathMarker() {
        val breaths = markers.filter { it.time.toLong() !in clearingMarkers }

        assertEquals(41, breaths.size)
        assertTrue(
            "a breath marker must leave the previous line on screen",
            breaths.all { marker ->
                val index = resolveCueIndex(cues, marker.time.toLong())
                index >= 0 && cues[index].text.isNotEmpty()
            }
        )
    }

    @Test
    fun handsTheTitleBackExactlyOnTheThreeMarkersThatStandForAGap() {
        assertTrue(
            clearingMarkers.all { marker ->
                val index = resolveCueIndex(cues, marker)
                index >= 0 && cues[index].text.isEmpty()
            }
        )
    }

    // --- the timeline those markers produce ---

    @Test
    fun buildsTheWholeTimelineInStrictOrder() {
        assertEquals(89, cues.size)
        // resolveCueIndex searches backwards, so out-of-order or duplicated timestamps would make
        // it pick the wrong cue rather than merely look untidy.
        assertTrue(cues.zipWithNext().all { (earlier, later) -> later.timeMs > earlier.timeMs })
    }

    @Test
    fun numbersEveryCueWithoutGaps() {
        // The sequence is the publisher's de-duplication key; a gap or a repeat would make it
        // either re-publish a cue or skip one.
        assertEquals(List(cues.size) { it }, cues.map { it.sequence })
    }

    @Test
    fun splitsTheOpeningCreditLineIntoSixCuesAndKeepsItOnScreen() {
        // The file opens with the credits, 6 cues wide, and the first lyric does not arrive until
        // 31.5s. Credits are lines like any other, so they are shown -- they are not skipped, and
        // they are the reason the first cue sits at 0 rather than at the first sung line.
        assertEquals(0L, cues.first().timeMs)
        assertEquals(6, cues.count { it.timeMs < 16_530L })
    }

    @Test
    fun holdsTheLastCueAtTheTrackTail() {
        // The last line starts 144ms before the track ends, so its end comes from the track
        // duration, not from a segment's worth of fallback: the two cues are 72ms apart.
        assertEquals(265_240L, cues[cues.lastIndex - 1].timeMs)
        assertEquals(265_312L, cues.last().timeMs)
    }
}
