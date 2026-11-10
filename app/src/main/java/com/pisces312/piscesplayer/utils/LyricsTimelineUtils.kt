package com.pisces312.piscesplayer.utils

import com.pisces312.piscesplayer.data.model.SyncedLine

/**
 * Timeline helpers for synced lyrics. Kept outside the Compose layer because the playback
 * service also needs to resolve the active line (car lyric title, see
 * `data/service/player/CarLyricTitleController`).
 */

/**
 * End of [line] in milliseconds, clamped to at least the start of [nextLineStartMs].
 * A line whose last word starts after the next line's timestamp (possible with
 * per-word timing) keeps its highlight until that word is reached.
 */
fun resolveLineEndTimeMs(line: SyncedLine, nextLineStartMs: Int): Long {
    val baseEnd = nextLineStartMs.toLong()
    val lastWordStart = line.words?.maxOfOrNull { it.time.toLong() } ?: line.time.toLong()
    return maxOf(baseEnd, lastWordStart + 1L)
}

/**
 * Index of the line covering [position], or -1 when nothing matches (before the first
 * line, or past the end of the last one).
 */
fun resolveCurrentLineIndex(
    lines: List<SyncedLine>,
    position: Long
): Int {
    if (lines.isEmpty()) return -1

    return lines.withIndex().lastOrNull { (index, line) ->
        val nextTime = lines.getOrNull(index + 1)?.time ?: Int.MAX_VALUE
        val lineEndTime = resolveLineEndTimeMs(line, nextTime)
        position in line.time.toLong()..<lineEndTime
    }?.index ?: -1
}

/**
 * One publishable slice of the lyric timeline: a whole line, or one of the segments a line too
 * long for a single-line title field is split into.
 *
 * @param sequence id increasing with the timeline. Together with [text] it forms the publisher's
 *   de-duplication key, so two slices that happen to read the same text are still published
 *   separately.
 * @param timeMs playback position at which this slice becomes the current one.
 * @param text slice contents; empty only for the marker line of a stretch that has nothing to sing
 *   for a while (see [standsForAGap]), which is what hands the title back to the track for it.
 */
data class LyricCue(
    val sequence: Int,
    val timeMs: Long,
    val text: String
)

/**
 * Flattens [lines] into the ordered sequence of slices to publish.
 *
 * A line is cut into `ceil(width / maxColumnsPerCue)` segments of near-equal width, spread evenly
 * over that line's own duration but no further apart than [SEGMENT_DWELL_LIMIT_MS]. Splitting
 * exists because the consumer of these titles is a head unit title field of fixed width that would
 * otherwise truncate the tail of the line; spreading evenly in time is what keeps every segment in
 * step with the vocal.
 *
 * Width is measured in title columns rather than characters (see [titleColumns]), because the
 * field is a fixed amount of *space*, not a fixed number of glyphs: the same field that holds ten
 * Chinese characters holds thirty Latin letters.
 *
 * A line with no text at all is a marker, not a lyric (LRC writes one wherever the vocal pauses),
 * so it is passed over — unless the next line that has any text is at least [BLANK_GAP_LIMIT_MS]
 * away, in which case the marker stands for a stretch with nothing to show and the title is handed
 * back to the track for it. Either way it remains the end of the line before it.
 *
 * @param trackDurationMs consulted for the last line only, which has no successor to borrow an
 *   end from. Pass the player duration; pass anything not greater than the last line's start when
 *   it is unknown (the player reports `C.TIME_UNSET` in that case, which is `Long.MIN_VALUE`).
 * @param maxColumnsPerCue how many title columns one slice may carry; must be positive. Pass
 *   [Int.MAX_VALUE] to publish every line whole.
 */
fun buildLyricCues(
    lines: List<SyncedLine>,
    trackDurationMs: Long,
    maxColumnsPerCue: Int
): List<LyricCue> {
    if (lines.isEmpty()) return emptyList()

    val cues = ArrayList<LyricCue>()
    var sequence = 0

    lines.forEachIndexed { index, line ->
        val startMs = line.time.toLong()
        val text = line.line.trim()
        // A marker with another line right behind it is only the breath between two lines; handing
        // the title back to the track for those few hundred milliseconds is what made the head unit
        // alternate between the lyric and the song name. Passing it over leaves the previous line
        // on screen, which is what the ear expects.
        if (text.isEmpty() && !standsForAGap(lines, index)) return@forEachIndexed

        val segments = splitIntoSegments(text, maxColumnsPerCue)
        val endMs = lineEndMs(lines, index, startMs, trackDurationMs, segments.size)
        val spanMs = (endMs - startMs).coerceAtLeast(0L)
        // An equal share of the line's span, but never more than [SEGMENT_DWELL_LIMIT_MS]: the span
        // is the interval the line *occupies*, which trailing silence, an instrumental gap or the
        // tail of the track inflates far past how long the words are actually sung. Uncapped, the
        // second half of a line followed by a gap — worst of all the last line — waits out the
        // silence instead of following the vocal.
        val dwellMs = (spanMs / segments.size).coerceAtMost(SEGMENT_DWELL_LIMIT_MS)

        segments.forEachIndexed { segmentIndex, segment ->
            cues += LyricCue(
                sequence = sequence++,
                timeMs = startMs + dwellMs * segmentIndex,
                text = segment
            )
        }
    }

    // Per-word timings can push a line's end past the *next* line's timestamp, which would leave
    // the list out of order, and [resolveCueIndex] relies on it being ordered. `sortedBy` is
    // stable, so slices sharing a timestamp keep the order they were built in.
    return cues.sortedBy { it.timeMs }
}

/**
 * Whether the text-less line at [index] stands for a stretch with nothing to sing, i.e. whether the
 * next line that carries any text is at least [BLANK_GAP_LIMIT_MS] away.
 *
 * Lines with text are what it measures against, credits included: what decides the question is
 * whether there is anything to put on screen in the meantime, not whether that something is a
 * lyric. A marker with nothing after it at all has nothing to show either, so the tail of a track
 * counts as a gap.
 */
private fun standsForAGap(lines: List<SyncedLine>, index: Int): Boolean {
    for (next in index + 1..lines.lastIndex) {
        if (lines[next].line.isBlank()) continue
        return lines[next].time.toLong() - lines[index].time >= BLANK_GAP_LIMIT_MS
    }
    return true
}

/**
 * Index of the slice covering [position], or -1 when [position] precedes the first one (intro).
 *
 * The last slice stays current to the end of the track, matching [resolveCurrentLineIndex], so
 * the lyrics never go blank at the tail of a song.
 */
fun resolveCueIndex(cues: List<LyricCue>, position: Long): Int =
    cues.indexOfLast { it.timeMs <= position }

/**
 * Position at which the slice at [index] stops being the current one, or null when there is none
 * left: before the first slice (intro) the boundary is that slice's start; on or past the last
 * slice there is nothing left to announce. The car lyric title controller arms its next wake-up
 * with this.
 */
fun nextCueTimeMs(cues: List<LyricCue>, index: Int): Long? {
    if (cues.isEmpty()) return null
    if (index < 0) return cues.first().timeMs
    if (index >= cues.lastIndex) return null
    return cues[index + 1].timeMs
}

/**
 * End of the line at [index]. A line with a successor ends where that successor starts (see
 * [resolveLineEndTimeMs] for the per-word exception); the final line has nobody to borrow from and
 * falls back to [trackDurationMs], or to one [SEGMENT_DWELL_LIMIT_MS] per segment when the track
 * duration is unknown.
 *
 * `C.TIME_UNSET` is `Long.MIN_VALUE`, which **must** be rejected by comparison: subtracting it
 * first would overflow into a positive span and silently stretch the last line across the whole
 * track.
 */
private fun lineEndMs(
    lines: List<SyncedLine>,
    index: Int,
    startMs: Long,
    trackDurationMs: Long,
    segmentCount: Int
): Long {
    val next = lines.getOrNull(index + 1)
    if (next != null) return resolveLineEndTimeMs(lines[index], next.time)

    if (trackDurationMs > startMs) return trackDurationMs
    return startMs + SEGMENT_DWELL_LIMIT_MS * segmentCount
}

/**
 * Cuts [text] into near-equal segments of at most [maxColumnsPerCue] columns each. The remainder
 * of an uneven division goes to the leading segments, so no two segments differ by more than one
 * column and none of them can exceed the cap.
 */
private fun splitIntoSegments(text: String, maxColumnsPerCue: Int): List<String> {
    if (text.isEmpty()) return listOf("")

    // Cumulative columns per character boundary, so a cut expressed as a column budget can be
    // resolved to a character index. Strictly increasing: every character adds at least one.
    val columns = IntArray(text.length + 1)
    for (index in text.indices) {
        columns[index + 1] = columns[index] + titleColumns(text[index])
    }
    val totalColumns = columns.last()
    // Also the overflow guard for the division below: it is what makes `Int.MAX_VALUE` (splitting
    // turned off) safe.
    if (totalColumns <= maxColumnsPerCue) return listOf(text)

    val segmentCount = (totalColumns + maxColumnsPerCue - 1) / maxColumnsPerCue
    val baseColumns = totalColumns / segmentCount
    val remainderColumns = totalColumns % segmentCount

    // Ideal cuts are cumulative and never depend on a previous adjustment, so one moved cut
    // cannot drag the rest of the line along with it.
    val idealCuts = IntArray(segmentCount - 1)
    var budget = 0
    for (index in idealCuts.indices) {
        budget += baseColumns + if (index < remainderColumns) 1 else 0
        idealCuts[index] = cutForColumns(columns, budget)
    }

    val segments = ArrayList<String>(segmentCount)
    var start = 0
    for (index in idealCuts.indices) {
        val nextIdealCut = idealCuts.getOrNull(index + 1) ?: text.length
        val cut = breakAdjustedCut(text, columns, idealCuts[index], nextIdealCut, start, maxColumnsPerCue)
        // Trimmed so a cut that landed right after a space does not publish a trailing blank; a
        // segment that is nothing but the break itself collapses to empty and is dropped by the
        // publisher, which is also what an instrumental marker does.
        segments += text.substring(start, cut).trim()
        start = cut
    }
    segments += text.substring(start).trim()
    return segments
}

/**
 * Smallest cut index whose accumulated columns reach [columns], as a character index in
 * `1..text.length - 1` — the upper bound keeps the final segment from coming out empty.
 */
private fun cutForColumns(prefixColumns: IntArray, columns: Int): Int {
    var index = 1
    while (index < prefixColumns.lastIndex && prefixColumns[index] < columns) index++
    return index
}

/**
 * Pulls [idealCut] back to the nearest break character, so a word is not bisected mid-way — but
 * only as far as keeps both the segment ending here and the one starting here within
 * [maxColumnsPerCue].
 *
 * The *ideal* next cut is what gets checked, not the final one: the next cut may move back as
 * well, so this is a deliberate approximation rather than a search. Erring on the side of
 * `idealCut` is always safe, because the ideal lengths already respect the cap.
 *
 * Both the lookback and the cap check are measured in columns: ten columns is three Chinese
 * characters but ten Latin letters, which is what a word boundary in English needs.
 */
private fun breakAdjustedCut(
    text: String,
    prefixColumns: IntArray,
    idealCut: Int,
    nextIdealCut: Int,
    previousCut: Int,
    maxColumnsPerCue: Int
): Int {
    val earliestCut =
        cutForColumns(prefixColumns, prefixColumns[idealCut] - MAX_CUT_LOOKBACK_COLUMNS)
            .coerceAtLeast(previousCut + 1)
    for (cut in idealCut downTo earliestCut) {
        if (!isBreakCharacter(text[cut - 1])) continue
        if (prefixColumns[nextIdealCut] - prefixColumns[cut] > maxColumnsPerCue) continue
        return cut
    }
    return idealCut
}

/**
 * How many columns [character] occupies in the head unit title field.
 *
 * The field is a fixed amount of space, and the unit this feature was measured on draws a Latin
 * letter at about a third of a CJK character's width: ten Chinese characters fill it, thirty
 * letters do. Counting 3 and 1 is what lets a single cap serve both scripts — and a line mixing
 * them on the same rule, which counting characters cannot do.
 */
private fun titleColumns(character: Char): Int =
    if (character.isWideTitleGlyph()) WIDE_GLYPH_COLUMNS else 1

/** Full-width and East Asian wide forms, i.e. the glyphs that render at [WIDE_GLYPH_COLUMNS]. */
private fun Char.isWideTitleGlyph(): Boolean =
    code in 0x1100..0x115F ||   // Hangul Jamo
        code in 0x2E80..0xA4CF ||   // CJK radicals, Kangxi, CJK ideographs, Yi
        code in 0xAC00..0xD7A3 ||   // Hangul syllables
        code in 0xF900..0xFAFF ||   // CJK compatibility ideographs
        code in 0xFE30..0xFE6F ||   // CJK compatibility forms
        code in 0xFF00..0xFF60 ||   // full-width forms (half-width katakana at 0xFF61+ stays narrow)
        code in 0xFFE0..0xFFE6

/**
 * Whether a segment may end just after [character]. Whitespace and the punctuation a phrase
 * naturally ends on, so both `Hello world` and `你好，世界` break on a readable boundary. Opening
 * brackets are deliberately absent: they belong with the text that follows them.
 */
private fun isBreakCharacter(character: Char): Boolean =
    character.isWhitespace() || character in BREAK_CHARACTERS

/**
 * How long one segment of a split line stays on screen, at most.
 *
 * It is both the share of a line's span when that span is short enough and the ceiling when it is
 * not, which is also what the unknown-duration fallback in [lineEndMs] is built from: with no
 * track duration to borrow an end from, every segment simply gets this much.
 */
private const val SEGMENT_DWELL_LIMIT_MS = 4_000L

/**
 * How long a stretch has to be without a single line of text before a marker line hands the title
 * back to the track.
 *
 * LRC writes a marker after most lines and those are breath gaps, well under a second in the worst
 * case observed on a real file (2.4s on the longest line of a slow song): restoring the real title
 * for them is what made it flicker. This is comfortably above that and still well below a real
 * instrumental stretch or an outro, so a marker that clears the title is telling the truth.
 */
private const val BLANK_GAP_LIMIT_MS = 6_000L

/** Columns a full-width glyph takes in the title field; a narrow glyph takes exactly one. */
private const val WIDE_GLYPH_COLUMNS = 3

/** How far back a cut may be pulled to land on a break character, measured in title columns. */
private const val MAX_CUT_LOOKBACK_COLUMNS = 10

private const val BREAK_CHARACTERS = "、，。！？；：,.!?;:…—·’”)）】》」』"

// --- instrumental placeholder lyrics -------------------------------------------------------

/**
 * Original work, GPL-3.0-or-later.
 *
 * Whether a synced-lyrics file contains nothing but an announcement that the track has no vocals,
 * e.g. a single `纯音乐，请欣赏` line or `[Instrumental]`.
 *
 * This is a **whole-file** verdict on purpose. Such a line parses into an ordinary [SyncedLine],
 * and the last line borrows the track duration as its end (see [lineEndMs]), so that one line
 * becomes a cue covering the entire song: the car lyric title then sits on "纯音乐" until the
 * track ends instead of ever handing the title back to the track.
 *
 * A line that merely mentions the words is not enough — as soon as any line looks like real sung
 * text, the lyrics are kept. Failing to recognise an unusual placeholder only preserves the old
 * behaviour, whereas a false positive would suppress a whole song's lyrics, so the recognised
 * forms are matched against the entire normalised line instead of by substring search.
 *
 * Credit lines (`作词 : …`) count as non-lyric content *here only*; they remain publishable in
 * [buildLyricCues], which deliberately does not filter them (see
 * `docs/car-lyrics-blank-markers.md` §4).
 */
fun isInstrumentalPlaceholderLyrics(lines: List<SyncedLine>): Boolean {
    val texts = lines.map { normalizePlaceholderText(it.line) }.filter { it.isNotEmpty() }
    if (texts.isEmpty()) return false

    var markerCount = 0
    for (text in texts) {
        when {
            PLACEHOLDER_LINE.matches(text) -> markerCount++
            isCreditLine(text) -> Unit
            else -> return false
        }
    }
    return markerCount > 0
}

/** Strips wrappers, whitespace and punctuation so one anchored pattern covers CJK and Latin. */
private fun normalizePlaceholderText(raw: String): String =
    PLACEHOLDER_NOISE.replace(raw.trim(), "").lowercase()

private fun isCreditLine(text: String): Boolean =
    text.length <= MAX_CREDIT_CHARS && CREDIT_PREFIXES.any { text.startsWith(it) }

/**
 * Anchored on purpose: `只剩纯音乐在响的夜里` is a lyric line, not an announcement, and must not
 * match. The optional groups cover the phrasing the large Chinese LRC providers use.
 */
private val PLACEHOLDER_LINE = Regex(
    """^(?:此|该)?(?:歌曲|曲目)?(?:为|是)?(?:没有填词|没有人声|无歌词|无人声)?(?:的)?""" +
        """(?:纯音乐|純音樂|纯乐曲|纯器乐|器乐|无歌词|暂无歌词|没有歌词|instrumental|nolyrics?|novocal|インストゥルメンタル|インスト)""" +
        """(?:版)?(?:请您|请)?(?:欣赏|聆听)?$"""
)

private val PLACEHOLDER_NOISE = Regex(
    """[\s\[\](){}【】「」『』（）〈〉《》.,!?;:、，。！？；：…—\-~·'"`/\\|♪♫]+"""
)

private val CREDIT_PREFIXES = listOf(
    "作词", "作曲", "编曲", "演唱", "原唱", "和声", "混音", "录音", "制作人", "监制", "出品",
    "版权", "曲版权管理方", "词版权管理方", "lyricist", "composer", "arranger", "producer",
    "writtenby", "musicby", "lyricsby"
)

/** Long enough for a credit line, short enough that a real lyric cannot hide behind a prefix. */
private const val MAX_CREDIT_CHARS = 48
