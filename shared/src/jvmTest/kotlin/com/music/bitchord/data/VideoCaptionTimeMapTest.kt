package com.music.bitchord.data

import com.music.bitchord.data.lyrics.LyricLine
import com.music.bitchord.data.lyrics.videoCaptionTimeMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class VideoCaptionTimeMapTest {
    private val first = "Follow the silver light"
    private val second = "Listen to the distant rain"
    private val third = "Turn around and find me"

    @Test
    fun followsLongIntroAndExtraScene() {
        val lyrics = listOf(LyricLine(8_000, first), LyricLine(20_000, second), LyricLine(32_000, third))
        val captions = listOf(
            LyricLine(15_000, first),
            LyricLine(27_000, second),
            LyricLine(44_000, third),
        )
        val map = assertNotNull(videoCaptionTimeMap(lyrics, captions, 60_000, lyricsOffsetMs = 0))
        assertEquals(7_000L, map.map(0))
        assertEquals(29_000L, map.map(22_000))
        assertEquals(44_000L, map.map(32_000))
        assertEquals(62_000L, map.map(50_000))
    }

    @Test
    fun declinesRepeatedOrUnrelatedCaptions() {
        val lyrics = listOf(LyricLine(8_000, first), LyricLine(20_000, second), LyricLine(32_000, third))
        val repeated = listOf(
            LyricLine(15_000, first), LyricLine(19_000, first),
            LyricLine(27_000, second), LyricLine(44_000, "A different recording"),
        )
        assertNull(videoCaptionTimeMap(lyrics, repeated, 60_000, lyricsOffsetMs = 0))
    }
}
