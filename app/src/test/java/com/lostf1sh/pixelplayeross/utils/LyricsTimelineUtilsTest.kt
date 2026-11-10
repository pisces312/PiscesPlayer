package com.lostf1sh.pixelplayeross.utils

import com.lostf1sh.pixelplayeross.data.model.SyncedLine
import com.lostf1sh.pixelplayeross.data.model.SyncedWord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the playback-position -> lyric-line mapping shared by the lyrics sheet and the car lyric
 * title feature, plus the line -> cue flattening only the latter needs.
 *
 * Cue caps are in *title columns*, not characters: one CJK character is three columns and one
 * Latin letter is one, matching what a head unit title field actually fits (ten Chinese characters
 * or thirty letters). Every test below passes 30 explicitly rather than importing the controller's
 * constant, so the arithmetic stays checkable by hand.
 */
class LyricsTimelineUtilsTest {

    private val lines = listOf(
        SyncedLine(time = 1_000, line = "first"),
        SyncedLine(time = 4_000, line = "second"),
        SyncedLine(time = 7_000, line = "third")
    )

    /** 21 Chinese characters = 63 columns, i.e. three cues of seven characters. */
    private val longChineseLine = "一二三四五六七八九十".repeat(2) + "一"

    /** A short line, then the 21-character one over 6 s (three cues), then the last line. */
    private val cueLines = listOf(
        SyncedLine(time = 1_000, line = "short"),
        SyncedLine(time = 4_000, line = longChineseLine),
        SyncedLine(time = 10_000, line = "final")
    )

    private val trackDurationMs = 20_000L

    // --- resolveCurrentLineIndex (lyrics sheet) ---

    @Test
    fun resolveCurrentLineIndex_returnsMinusOneForEmptyTimeline() {
        assertEquals(-1, resolveCurrentLineIndex(emptyList(), position = 1_000))
    }

    @Test
    fun resolveCurrentLineIndex_returnsMinusOneBeforeTheFirstLine() {
        assertEquals(-1, resolveCurrentLineIndex(lines, position = 0))
        assertEquals(-1, resolveCurrentLineIndex(lines, position = 999))
    }

    @Test
    fun resolveCurrentLineIndex_mapsPositionToItsLine() {
        assertEquals(0, resolveCurrentLineIndex(lines, position = 1_000))
        assertEquals(0, resolveCurrentLineIndex(lines, position = 3_999))
        assertEquals(1, resolveCurrentLineIndex(lines, position = 4_000))
        assertEquals(2, resolveCurrentLineIndex(lines, position = 7_000))
    }

    @Test
    fun resolveCurrentLineIndex_keepsLastLineActiveToTheEndOfTheTrack() {
        // The final line has no successor, so it stays current instead of the lyrics going blank
        // for the remainder of the song.
        assertEquals(2, resolveCurrentLineIndex(lines, position = 999_999))
    }

    @Test
    fun resolveCurrentLineIndex_followsSeekBackwards() {
        assertEquals(2, resolveCurrentLineIndex(lines, position = 8_000))
        assertEquals(1, resolveCurrentLineIndex(lines, position = 5_000))
        assertEquals(0, resolveCurrentLineIndex(lines, position = 1_200))
    }

    @Test
    fun resolveCurrentLineIndex_keepsLineWhileItsLastWordIsStillPending() {
        val wordTimed = listOf(
            SyncedLine(
                time = 1_000,
                line = "held on",
                words = listOf(SyncedWord(time = 1_000, word = "held"), SyncedWord(time = 3_000, word = "on"))
            )
        )

        assertEquals(0, resolveCurrentLineIndex(wordTimed, position = 2_500))
    }

    // --- buildLyricCues ---

    @Test
    fun buildLyricCues_returnsNothingForAnEmptyTimeline() {
        assertEquals(emptyList<LyricCue>(), buildLyricCues(emptyList(), trackDurationMs, maxColumnsPerCue = 30))
    }

    @Test
    fun buildLyricCues_publishesShortLinesWhole() {
        val cues = buildLyricCues(cueLines, trackDurationMs, maxColumnsPerCue = 30)

        // 5 columns needs no cutting at all, so the line is one cue starting on its own timestamp.
        assertEquals(LyricCue(sequence = 0, timeMs = 1_000L, text = "short"), cues.first())
    }

    @Test
    fun buildLyricCues_fitsThirtyLatinCharactersInOneCue() {
        val fits = buildLyricCues(
            listOf(SyncedLine(time = 0, line = "abcdefghijklmnopqrstuvwxyzabcd")),
            trackDurationMs = 4_000,
            maxColumnsPerCue = 30
        )

        // 30 letters are 30 columns: exactly one full title field, so no cutting.
        assertEquals(1, fits.size)
        assertEquals("abcdefghijklmnopqrstuvwxyzabcd", fits.single().text)
    }

    @Test
    fun buildLyricCues_splitsThirtyOneLatinCharactersInTwo() {
        val cues = buildLyricCues(
            listOf(SyncedLine(time = 0, line = "abcdefghijklmnopqrstuvwxyzabcde")),
            trackDurationMs = 4_000,
            maxColumnsPerCue = 30
        )

        assertEquals(listOf("abcdefghijklmnop", "qrstuvwxyzabcde"), cues.map { it.text })
    }

    @Test
    fun buildLyricCues_countsAWideCharacterAsThreeColumns() {
        val cues = buildLyricCues(
            listOf(SyncedLine(time = 0, line = "一二三四五六七八九十一")),
            trackDurationMs = 4_000,
            maxColumnsPerCue = 30
        )

        // 11 Chinese characters are 33 columns, i.e. just past the cap: 6 + 5 characters.
        assertEquals(listOf("一二三四五六", "七八九十一"), cues.map { it.text })
    }

    @Test
    fun buildLyricCues_mixesScriptsOnTheSameColumnBudget() {
        val cues = buildLyricCues(
            listOf(SyncedLine(time = 0, line = "你好世界 hello beautiful world")),
            trackDurationMs = 4_000,
            maxColumnsPerCue = 30
        )

        // 12 columns of Chinese + 22 of Latin = 34; the cut lands after the Chinese because that is
        // the nearest break character within the lookback.
        assertEquals(listOf("你好世界", "hello beautiful world"), cues.map { it.text })
    }

    @Test
    fun buildLyricCues_splitsALongLineIntoEvenSegments() {
        val cues = buildLyricCues(cueLines, trackDurationMs, maxColumnsPerCue = 30)
        val longLine = cues.filter { it.sequence in 1..3 }

        // 63 columns -> ceil(63/30) = 3 segments of 7 characters, spread evenly over the line's 6 s.
        assertEquals(
            listOf("一二三四五六七", "八九十一二三四", "五六七八九十一"),
            longLine.map { it.text }
        )
        assertEquals(listOf(4_000L, 6_000L, 8_000L), longLine.map { it.timeMs })
    }

    @Test
    fun buildLyricCues_spacesSegmentsEvenlyWithinTheirLine() {
        val cues = buildLyricCues(cueLines, trackDurationMs, maxColumnsPerCue = 30)
        val longLine = cues.filter { it.sequence in 1..3 }

        // The car lyric title publishes every cue early by one constant, so these gaps — and only
        // these gaps — set the rhythm on screen; a per-segment offset would show up right here.
        assertEquals(
            listOf(2_000L, 2_000L),
            longLine.zipWithNext { earlier, later -> later.timeMs - earlier.timeMs }
        )
    }

    @Test
    fun buildLyricCues_spreadsTheRemainderOverTheLeadingSegments() {
        val cues = buildLyricCues(
            listOf(SyncedLine(time = 0, line = "甲乙丙丁戊己庚辛壬癸子丑寅卯辰巳午未申")),
            trackDurationMs = 4_000,
            maxColumnsPerCue = 30
        )

        // 19 characters = 57 columns -> 2 segments, 10 then 9: never more than one character apart,
        // never over the cap.
        assertEquals(listOf("甲乙丙丁戊己庚辛壬癸", "子丑寅卯辰巳午未申"), cues.map { it.text })
    }

    @Test
    fun buildLyricCues_breaksOnWhitespaceInsteadOfMidWord() {
        val cues = buildLyricCues(
            listOf(SyncedLine(time = 0, line = "quick brown fox jumps over lazy dog")),
            trackDurationMs = 4_000,
            maxColumnsPerCue = 30
        )

        // The even cut would land inside "jumps"; the space two columns back wins. Finding it at
        // all is what the ten-column lookback is for — three characters, as the CJK cap needs,
        // would end the first segment mid-word.
        assertEquals(listOf("quick brown fox", "jumps over lazy dog"), cues.map { it.text })
    }

    @Test
    fun buildLyricCues_usesTheTrackDurationForTheLastLine() {
        val cues = buildLyricCues(cueLines, trackDurationMs, maxColumnsPerCue = 30)

        // The last line has no successor to borrow an end from: 20 s track - 10 s start = 10 s.
        assertEquals(LyricCue(sequence = 4, timeMs = 10_000L, text = "final"), cues.last())
    }

    @Test
    fun buildLyricCues_fallsBackWhenTheTrackDurationIsUnset() {
        // `C.TIME_UNSET` is Long.MIN_VALUE, which has to be rejected by comparison: subtracting it
        // first would overflow into a positive span and stretch the line across the whole track.
        val cues = buildLyricCues(
            listOf(SyncedLine(time = 1_000, line = "abcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrst")),
            trackDurationMs = Long.MIN_VALUE,
            maxColumnsPerCue = 30
        )

        assertEquals(2, cues.size)
        assertEquals(1_000L, cues[0].timeMs)
        assertEquals(4_000L, cues[1].timeMs - cues[0].timeMs)
    }

    @Test
    fun buildLyricCues_capsTheDwellOfALineFollowedByAGap() {
        val cues = buildLyricCues(
            listOf(
                SyncedLine(time = 0, line = "abcdefghijklmnopqrstuvwxyzabcdefghijklmnop"),
                SyncedLine(time = 20_000, line = "next")
            ),
            trackDurationMs = 40_000,
            maxColumnsPerCue = 30
        )

        // 40 letters are two cues, and the line's span is 20 s because that is where the next line
        // starts — but the gap after the words is silence, so the second cue follows the first
        // after 4 s rather than after 10 s.
        assertEquals(listOf(0L, 4_000L), cues.take(2).map { it.timeMs })
    }

    @Test
    fun buildLyricCues_capsTheDwellOfTheLastLineAtTheTrackTail() {
        val cues = buildLyricCues(
            listOf(SyncedLine(time = 10_000, line = "abcdefghijklmnopqrstuvwxyzabcdefghijklmnop")),
            trackDurationMs = 40_000,
            maxColumnsPerCue = 30
        )

        // Nothing follows the last line, so its span runs to the end of the track — 30 s, most of it
        // tail. The second cue must not wait for it: that is the case where the uncapped arithmetic
        // pushed a half-line a dozen seconds away from the vocal.
        assertEquals(listOf(10_000L, 14_000L), cues.map { it.timeMs })
    }

    @Test
    fun buildLyricCues_capsOnlyWhenASegmentWouldOutlastTheLimit() {
        fun timesFor(spanMs: Int) = buildLyricCues(
            listOf(
                SyncedLine(time = 0, line = longChineseLine),
                SyncedLine(time = spanMs, line = "next")
            ),
            trackDurationMs = 60_000,
            maxColumnsPerCue = 30
        ).take(3).map { it.timeMs }

        // 63 columns are three cues; 12 s spread over them is exactly 4 s each, so the cap is a
        // no-op and the even division stands.
        assertEquals(listOf(0L, 4_000L, 8_000L), timesFor(12_000))
        // One millisecond more and the even share would be 4_033 / 8_066 — spreading the excess over
        // the first two cues is what the cap exists to prevent.
        assertEquals(listOf(0L, 4_000L, 8_000L), timesFor(12_100))
    }

    @Test
    fun buildLyricCues_publishesEveryLineWholeWhenSplittingIsOff() {
        val cues = buildLyricCues(
            listOf(SyncedLine(time = 0, line = longChineseLine)),
            trackDurationMs = 4_000,
            maxColumnsPerCue = Int.MAX_VALUE
        )

        // The "split long lines" setting turned off: an unbounded budget must not overflow into a
        // smaller segment count than one, nor cut at all.
        assertEquals(1, cues.size)
        assertEquals(longChineseLine, cues.single().text)
    }

    @Test
    fun buildLyricCues_skipsABlankMarkerThatIsJustABreath() {
        val cues = buildLyricCues(
            listOf(
                SyncedLine(time = 1_000, line = "sung"),
                SyncedLine(time = 4_000, line = "   "),
                SyncedLine(time = 7_000, line = "again")
            ),
            trackDurationMs = 10_000,
            maxColumnsPerCue = 30
        )

        // LRC writes a marker wherever the vocal pauses, and here the next line is only three
        // seconds behind it: handing the title back to the track for that moment is exactly what
        // made the head unit alternate between the lyric and the song name. The marker is passed
        // over, so the line before it simply stays on screen.
        assertEquals(listOf("sung", "again"), cues.map { it.text })
    }

    @Test
    fun buildLyricCues_clearsTheTitleOnABlankMarkerThatStandsForAnInstrumentalStretch() {
        val cues = buildLyricCues(
            listOf(
                SyncedLine(time = 1_000, line = "sung"),
                SyncedLine(time = 4_000, line = ""),
                SyncedLine(time = 30_000, line = "again")
            ),
            trackDurationMs = 40_000,
            maxColumnsPerCue = 30
        )

        // Twenty-six seconds with nothing to sing is an instrumental stretch, and the real track
        // title is what belongs on screen for it. An empty cue is how that gets expressed.
        assertEquals(listOf("sung", "", "again"), cues.map { it.text })
        assertEquals(listOf(1_000L, 4_000L, 30_000L), cues.map { it.timeMs })
    }

    @Test
    fun buildLyricCues_clearsTheTitleOnATrailingBlankMarker() {
        val cues = buildLyricCues(
            listOf(
                SyncedLine(time = 1_000, line = "sung"),
                SyncedLine(time = 4_000, line = "")
            ),
            trackDurationMs = 10_000,
            maxColumnsPerCue = 30
        )

        // A marker with nothing at all after it has nothing to show on the strength of it either,
        // so the tail of a track counts as a gap just like an instrumental stretch does.
        assertEquals(listOf("sung", ""), cues.map { it.text })
    }

    @Test
    fun buildLyricCues_stillEndsALineAtTheBlankMarkerItSkips() {
        val cues = buildLyricCues(
            listOf(
                SyncedLine(time = 0, line = longChineseLine),
                SyncedLine(time = 6_000, line = ""),
                SyncedLine(time = 9_000, line = "again")
            ),
            trackDurationMs = 20_000,
            maxColumnsPerCue = 30
        )

        // Giving the marker's turn to nobody must not stretch the line before it: the marker is
        // still the moment that line stopped being sung, so its three cues stay 2s apart. Measured
        // against the next line that is actually shown they would be 3s apart.
        assertEquals(listOf(0L, 2_000L, 4_000L, 9_000L), cues.map { it.timeMs })
        assertTrue(cues.none { it.text.isEmpty() })
    }

    @Test
    fun buildLyricCues_numbersCuesInTimelineOrder() {
        val cues = buildLyricCues(cueLines, trackDurationMs, maxColumnsPerCue = 30)

        // The sequence is the publisher's de-duplication key, so it has to identify one cue exactly.
        assertEquals(listOf(0, 1, 2, 3, 4), cues.map { it.sequence })
    }

    @Test
    fun buildLyricCues_ordersCuesThatPerWordTimingPushedPastTheNextLine() {
        val cues = buildLyricCues(
            listOf(
                SyncedLine(
                    time = 1_000,
                    line = longChineseLine,
                    // A last word far past the next line's timestamp stretches the line's own end.
                    words = listOf(SyncedWord(time = 6_000, word = "一"))
                ),
                SyncedLine(time = 2_000, line = "next")
            ),
            trackDurationMs = 20_000,
            maxColumnsPerCue = 30
        )

        // Out-of-order timestamps are possible with per-word timing, and resolveCueIndex relies on
        // the list being sorted.
        assertEquals(cues.map { it.timeMs }.sorted(), cues.map { it.timeMs })
    }

    // --- resolveCueIndex / nextCueTimeMs (car lyric title) ---

    @Test
    fun resolveCueIndex_returnsMinusOneForAnEmptyTimeline() {
        assertEquals(-1, resolveCueIndex(emptyList(), position = 1_000))
    }

    @Test
    fun resolveCueIndex_returnsMinusOneDuringTheIntro() {
        val cues = buildLyricCues(cueLines, trackDurationMs, maxColumnsPerCue = 30)

        // Nothing to show before the first line, which is what keeps the real title on screen.
        assertEquals(-1, resolveCueIndex(cues, position = 0))
        assertEquals(-1, resolveCueIndex(cues, position = 999))
    }

    @Test
    fun resolveCueIndex_switchesExactlyOnTheCueTimestamp() {
        val cues = buildLyricCues(cueLines, trackDurationMs, maxColumnsPerCue = 30)

        assertEquals(0, resolveCueIndex(cues, position = 1_000))
        assertEquals(0, resolveCueIndex(cues, position = 3_999))
        assertEquals(1, resolveCueIndex(cues, position = 4_000))
        assertEquals(1, resolveCueIndex(cues, position = 5_999))
        assertEquals(3, resolveCueIndex(cues, position = 9_999))
        assertEquals(4, resolveCueIndex(cues, position = 10_000))
    }

    @Test
    fun resolveCueIndex_keepsTheLastCueToTheEndOfTheTrack() {
        val cues = buildLyricCues(cueLines, trackDurationMs, maxColumnsPerCue = 30)

        assertEquals(4, resolveCueIndex(cues, position = 999_999))
    }

    @Test
    fun resolveCueIndex_followsSeekBackwards() {
        val cues = buildLyricCues(cueLines, trackDurationMs, maxColumnsPerCue = 30)

        assertEquals(3, resolveCueIndex(cues, position = 8_000))
        assertEquals(1, resolveCueIndex(cues, position = 5_000))
        assertEquals(0, resolveCueIndex(cues, position = 1_200))
    }

    @Test
    fun nextCueTimeMs_returnsNullForAnEmptyTimeline() {
        assertEquals(null, nextCueTimeMs(emptyList(), index = 0))
    }

    @Test
    fun nextCueTimeMs_beforeTheFirstCueTargetsIt() {
        val cues = buildLyricCues(cueLines, trackDurationMs, maxColumnsPerCue = 30)

        // Intro: the wake-up should land exactly on the first cue's timestamp.
        assertEquals(1_000L, nextCueTimeMs(cues, index = -1))
    }

    @Test
    fun nextCueTimeMs_targetsTheFollowingCue() {
        val cues = buildLyricCues(cueLines, trackDurationMs, maxColumnsPerCue = 30)

        // Mid-line, the next wake-up is the next *segment* of the same line, not the next line.
        assertEquals(6_000L, nextCueTimeMs(cues, index = 1))
        assertEquals(8_000L, nextCueTimeMs(cues, index = 2))
        assertEquals(10_000L, nextCueTimeMs(cues, index = 3))
    }

    @Test
    fun nextCueTimeMs_onTheLastCueReturnsNull() {
        val cues = buildLyricCues(cueLines, trackDurationMs, maxColumnsPerCue = 30)

        assertEquals(null, nextCueTimeMs(cues, index = 4))
        assertEquals(null, nextCueTimeMs(cues, index = 99))
    }

    @Test
    fun nextCueTimeMs_neverLooksBackwards() {
        val cues = buildLyricCues(cueLines, trackDurationMs, maxColumnsPerCue = 30)

        assertTrue(
            (0 until cues.lastIndex).all { index ->
                nextCueTimeMs(cues, index)!! > cues[index].timeMs
            }
        )
    }

    // --- instrumental placeholder lyrics (car lyric title) ---

    /** Timestamps are irrelevant to the verdict, but the real model is what the controller holds. */
    private fun List<String>.isPlaceholderOnly(): Boolean =
        isInstrumentalPlaceholderLyrics(
            mapIndexed { index, text -> SyncedLine(time = index * 1_000, line = text) }
        )

    @Test
    fun placeholderLyrics_recognisesTheCommonChineseAndLatinForms() {
        listOf(
            "纯音乐",
            "纯音乐，请欣赏",
            "（純音樂）",
            "[Instrumental]",
            "纯音乐版",
            "此歌曲为没有填词的纯音乐，请您欣赏",
            "暂无歌词",
            "No lyrics"
        ).forEach { form ->
            assertTrue("expected a placeholder: $form", listOf(form).isPlaceholderOnly())
        }
    }

    @Test
    fun placeholderLyrics_acceptsBlankMarkersAndCreditLinesAroundTheAnnouncement() {
        assertTrue(
            listOf(
                "",
                "作词 : 无",
                "作曲 : 某某",
                "曲版权管理方：某某工作室",
                "纯音乐，请欣赏",
                "   "
            ).isPlaceholderOnly()
        )
    }

    @Test
    fun placeholderLyrics_keepsTheLyricsWhenAnyRealLineIsPresent() {
        // Whole-file verdict: one stray placeholder next to real singing must not suppress it.
        assertFalse(listOf("第一句歌词", "纯音乐，请欣赏", "第二句歌词").isPlaceholderOnly())
    }

    @Test
    fun placeholderLyrics_doesNotMistakeALyricLineMentioningTheWordForAnAnnouncement() {
        assertFalse(listOf("只剩纯音乐在响的夜里").isPlaceholderOnly())
        assertFalse(listOf("只剩纯音乐在响的夜里", "纯音乐，请欣赏").isPlaceholderOnly())
    }

    @Test
    fun placeholderLyrics_needsAtLeastOneAnnouncement() {
        assertFalse(emptyList<String>().isPlaceholderOnly())
        assertFalse(listOf("", "  ").isPlaceholderOnly())
        assertFalse(listOf("作词 : 某某", "作曲 : 某某").isPlaceholderOnly())
    }

    @Test
    fun placeholderLyrics_aRealLyricsFileIsNotAPlaceholder() {
        // Same 105-line fixture the blank-marker suite uses; a false positive here would silence
        // the lyrics of an ordinary song on every Bluetooth head unit.
        val lines = LyricsUtils.parseLyrics(
            requireNotNull(javaClass.getResourceAsStream("/lyrics/blank-marker-shape.lrc")) {
                "missing test fixture lyrics/blank-marker-shape.lrc"
            }.bufferedReader(Charsets.UTF_8).use { it.readText() }
        ).synced.orEmpty()

        assertEquals(105, lines.size)
        assertFalse(isInstrumentalPlaceholderLyrics(lines))
    }

    @Test
    fun placeholderLyrics_clearingTheLinesLeavesNoCuesToPublish() {
        // The controller drops the lines on a positive verdict and reuses the empty-cue branch,
        // which is what hands the title back to the track without a single scheduled wake-up.
        assertTrue(buildLyricCues(emptyList(), trackDurationMs, maxColumnsPerCue = 30).isEmpty())
    }
}
