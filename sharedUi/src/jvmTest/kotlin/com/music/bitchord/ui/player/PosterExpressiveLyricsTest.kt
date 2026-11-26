package com.music.bitchord.ui.player

import com.music.bitchord.data.lyrics.LyricAlignment
import com.music.bitchord.data.lyrics.LyricLine
import com.music.bitchord.data.lyrics.LyricWord
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PosterExpressiveLyricsTest {

    @Test
    fun wordScalesPicksFirstShortestWordOfAtLeastThreeChars() {
        val scales = posterWordScales(listOf("I", "feel", "the", "electricity", "now"))
        // "the" and "now" are both 3 chars; first shortest ("the" at index 2) gets 1.55x.
        // "electricity" is 11 chars -> 0.74x.
        assertArrayEquals(floatArrayOf(1f, 1f, 1.55f, 0.74f, 1f), scales, 0.0001f)
    }

    @Test
    fun wordScalesDoesNotEmphasizeSingleWordLine() {
        val scales = posterWordScales(listOf("Hello"))
        assertArrayEquals(floatArrayOf(1f), scales, 0.0001f)
    }

    @Test
    fun splitsIntoLettersPreservesJoinedAndCjkScripts() {
        assertTrue(posterSplitsIntoLetters("Hello"))
        assertTrue(posterSplitsIntoLetters("안녕"))
        assertFalse(posterSplitsIntoLetters("مرحبا"))
        assertFalse(posterSplitsIntoLetters("世界"))
        assertEquals(6, posterSungLength("世界"))
        assertEquals(5, posterSungLength("Hello"))
    }

    @Test
    fun prepareLinesFlattensBackgroundAndPairsSubLines() {
        val bg = LyricLine(timeMs = 2_500L, text = "yeah yeah")
        val lines = listOf(
            LyricLine(timeMs = 0L, text = ""),
            LyricLine(timeMs = 2_000L, text = "Hello world", background = bg),
        )
        val subs = listOf(
            LyricLine(timeMs = 0L, text = ""),
            LyricLine(
                timeMs = 2_000L,
                text = "Hola mundo",
                background = LyricLine(timeMs = 2_500L, text = "sí sí"),
            ),
        )
        val prepared = posterPrepareLines(lines, subs, 0.56f)
        assertEquals(2, prepared.size)
        assertEquals("Hello world", prepared[0].line.text)
        assertEquals("Hola mundo", prepared[0].subText)
        assertFalse(prepared[0].isBackground)
        assertEquals("yeah yeah", prepared[1].line.text)
        assertEquals("sí sí", prepared[1].subText)
        assertEquals(LyricAlignment.End, prepared[1].line.alignment)
        assertTrue(prepared[1].isBackground)
    }

    @Test
    fun timingComputesIntroInterludeOverlapAndGapExit() {
        val entries = listOf(
            PosterLineEntry(
                LyricLine(
                    timeMs = 6_000L,
                    text = "First line",
                    words = listOf(
                        LyricWord(6_000L, 7_000L, "First"),
                        LyricWord(7_000L, 8_500L, "line"),
                    ),
                ),
            ),
            PosterLineEntry(
                LyricLine(
                    timeMs = 8_200L,
                    text = "Overlapping line",
                    words = listOf(
                        LyricWord(8_200L, 9_000L, "Overlapping"),
                        LyricWord(9_000L, 10_000L, "line"),
                    ),
                ),
            ),
            PosterLineEntry(
                LyricLine(
                    timeMs = 16_000L,
                    text = "After break",
                    words = listOf(
                        LyricWord(16_000L, 17_000L, "After"),
                        LyricWord(17_000L, 18_000L, "break"),
                    ),
                ),
            ),
        )
        val enterAts = posterEnterTimes(entries)
        val ends = LongArray(entries.size) { i ->
            posterTimedWords(entries[i].line, entries.getOrNull(i + 1)?.line?.timeMs).last().endMs
        }
        val interludes = posterInterludesOf(entries, ends, enterAts, duet = false, rtl = false)
        val overlaps = posterOverlapsOf(entries, ends, enterAts)
        val exitAts = posterExitTimes(entries, ends, enterAts, interludes, overlaps)

        // First line starts at 6s (>= 4s), so intro interlude exists and enters 420ms early.
        assertEquals(6_000L - POSTER_ENTER_LEAD_MS, enterAts[0])
        assertTrue(overlaps[0])
        assertFalse(overlaps[1])
        assertEquals(2, interludes.size)
        assertEquals(-1, interludes[0].afterIndex)
        assertEquals(1, interludes[1].afterIndex)
        // Overlapping line stays until end + 150ms; line before gap exits at end + GapExitDelayMs; last line stays.
        assertEquals(8_500L + 150L, exitAts[0])
        assertEquals(10_000L + POSTER_GAP_EXIT_DELAY_MS, exitAts[1])
        assertNull(exitAts[2])
    }

    @Test
    fun sideOfMirrorsForRtlSongs() {
        val lead = PosterLineEntry(LyricLine(timeMs = 1_000L, text = "مرحبا", alignment = LyricAlignment.Start))
        val answer = PosterLineEntry(LyricLine(timeMs = 2_000L, text = "أهلا", alignment = LyricAlignment.End))
        assertEquals(PosterLineSide.RIGHT, posterSideOf(lead, duet = true, rtl = true))
        assertEquals(PosterLineSide.LEFT, posterSideOf(answer, duet = true, rtl = true))
        assertEquals(PosterLineSide.LEFT, posterSideOf(lead, duet = true, rtl = false))
        assertEquals(PosterLineSide.RIGHT, posterSideOf(answer, duet = true, rtl = false))
    }

    @Test
    fun timedWordsPreservesAllLinesAcrossEmbeddedNewlines() {
        val untimed = LyricLine(timeMs = 1_000L, text = "First line of verse\nSecond line of verse")
        val untimedWords = posterTimedWords(untimed, nextStartMs = 5_000L)
        assertEquals(
            listOf("First", "line", "of", "verse", "Second", "line", "of", "verse"),
            untimedWords.map { it.text },
        )

        val synced = LyricLine(
            timeMs = 1_000L,
            text = "First\nSecond",
            words = listOf(
                LyricWord(1_000L, 2_000L, "First\nSecond"),
            ),
        )
        val syncedWords = posterTimedWords(synced, nextStartMs = 3_000L)
        assertEquals(listOf("First", "Second"), syncedWords.map { it.text })
    }
}
