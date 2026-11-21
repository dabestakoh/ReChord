package com.music.bitchord.data.lyrics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject

class LyricsProvidersTest {
    private fun json(value: String) = lyricsJson.parseToJsonElement(value) as JsonObject

    @Test
    fun youLyPlusRequestPreservesCreditsAndRecordingIdentity() {
        val primary = LyricsPlus.requestUrl(
            "https://lyricsplus.binimum.org", "Duet (Live)", "Lead, Guest", 196_840, "Concert +", null, "za0moRpUMVY",
        )
        assertEquals("Duet (Live)", primary.queryParameter("title"))
        assertEquals("Lead, Guest", primary.queryParameter("artist"))
        assertEquals("196", primary.queryParameter("duration"))
        assertEquals("Concert +", primary.queryParameter("album"))
        assertEquals("za0moRpUMVY", primary.queryParameter("id"))
        assertNull(primary.queryParameter("isrc"))
        val retry = LyricsPlus.requestUrl(
            "https://lyricsplus.binimum.org", "Duet (Live)", "Lead, Guest", 196_840, "Concert +", "AAABC1234567", "local:1",
        )
        assertEquals("AAABC1234567", retry.queryParameter("isrc"))
        assertNull(retry.queryParameter("id"))
    }

    @Test
    fun lyricsPlusUsesSyncedThenStructuredThenPlain() {
        val mixed = """{"syncedLyrics":"[00:02.30]Timed line","lyrics":[{"time":7000,"text":"Structured"}],"plainLyrics":"Plain"}"""
        val matched = LyricsPlus.decode(mixed)?.single { !it.isGap }
        assertEquals("Timed line", matched?.text)
        assertEquals(2300L, matched?.timeMs)
        assertEquals("Structured", LyricsPlus.decode(mixed.replace("[00:02.30]Timed line", "bad field"))
            ?.single { !it.isGap }?.text)
        val plain = LyricsPlus.decode("""{"syncedLyrics":" ","lyrics":{},"plainLyrics":"Plain line"}""")?.single()
        assertEquals("Plain line", plain?.text)
        assertEquals(0L, plain?.timeMs)
        assertNull(LyricsPlus.decode("""{"syncedLyrics":"not timed","lyrics":[],"plainLyrics":" "}"""))
    }

    @Test
    fun netEasePrefersWordTimingAndFallsBackToLrc() {
        val parsed = DirectCatalogLyrics.netEaseLines(json(
            """{"yrc":{"lyric":"[1500,800](1500,400,0)One (1900,400,-1)day"},"lrc":{"lyric":"[00:01.50]Fallback"}}""",
        ))?.last()
        assertEquals(listOf("One", "day"), parsed?.words?.map { it.text })
        assertEquals(1900L, parsed?.words?.last()?.startMs)
        val fallback = DirectCatalogLyrics.netEaseLines(json("""{"lrc":{"lyric":"[00:04.00]Another day"}}"""))?.last()
        assertEquals(4000L, fallback?.timeMs)
        assertFalse(fallback?.isWordSynced ?: true)
    }

    @Test
    fun qqMusicDecodesLineTimesAndCaptionsKeepDuration() {
        val encoded = java.util.Base64.getEncoder().encodeToString("[00:02.00]A line".toByteArray())
        assertEquals(2000L, DirectCatalogLyrics.qqLines(json("""{"lyric":"$encoded"}"""))?.last()?.timeMs)
        val cues = YouTubeTranscriptLyrics.parseCaptionEvents(json(
            """{"events":[{"tStartMs":100,"segs":[{"utf8":"♪"}]},{"tStartMs":200,"dDurationMs":530,"segs":[{"utf8":"First "},{"utf8":"line"}]}]}""",
        ))
        assertEquals("First line", cues?.single()?.text)
        assertEquals(730L, cues?.single()?.sungUntilMs)
    }

    @Test
    fun deezerFallsBackFromWordsToLineThenPlain() {
        val rich = DirectCatalogLyrics.deezerLines(json(
            """{"synchronizedWordByWordLines":[{"start":2000,"words":[{"start":2000,"end":2200,"word":"Go"}]}],"synchronizedLines":[{"milliseconds":4000,"line":"Line"}],"text":"Plain"}""",
        ))?.last()
        assertTrue(rich?.isWordSynced == true)
        val timed = DirectCatalogLyrics.deezerLines(json("""{"synchronizedLines":[{"milliseconds":4000,"line":"Line"}]}"""))?.last()
        assertEquals(4000L, timed?.timeMs)
        assertEquals("Plain", DirectCatalogLyrics.deezerLines(json("""{"text":"Plain"}"""))?.single()?.text)
    }

    @Test
    fun languageBeatsTimingAndTiesKeepProviderOrder() {
        val translated = result(LyricsSource.QQ_MUSIC, "中文歌词完整", true)
        val original = result(LyricsSource.LRCLIB, "우리 함께 노래해요")
        val timed = result(LyricsSource.LYRICS_PLUS, "우리 함께 노래해요", true)
        val sameTiming = result(LyricsSource.BETTER_LYRICS, "우리 함께 노래해요", true)
        assertEquals(original, chooseLyrics("노래", "Artist", null, listOf(translated, original)))
        assertEquals(timed, chooseLyrics("노래", "Artist", null, listOf(original, timed, sameTiming)))
        assertNull(chooseLyrics("노래", "Artist", null, listOf(translated)))
    }

    @Test
    fun nativeEvidenceAndCatalogPriorityRemainIndependentOfProviderOrder() {
        val latin = result(LyricsSource.LRCLIB, "You and I")
        val singleWrongLocale = result(LyricsSource.LYRICS_PLUS, "中文歌词完整", true)
        assertEquals(latin, chooseLyrics("Latin Title", "Artist", null, listOf(singleWrongLocale, latin)))

        val devanagari = result(LyricsSource.YOUTUBE_MUSIC, "तुम मेरी दुनिया हो", true)
        val originalRomanized = result(LyricsSource.BETTER_LYRICS, "mera dil tum hi ho")
        assertEquals(originalRomanized, chooseLyrics("Song", "Artist", null, listOf(devanagari, originalRomanized)))

        val catalog = result(LyricsSource.QQ_MUSIC, "我們一起唱歌")
        val other = result(LyricsSource.LRCLIB, "我們一起唱歌")
        assertEquals(catalog, chooseLyrics("我們一起唱歌", "Artist", null, listOf(other, catalog)))
    }

    private fun result(source: LyricsSource, text: String, wordTimed: Boolean = false) =
        LyricsRepository.Result(source, listOf(
            LyricLine(1000L, text, if (wordTimed) listOf(LyricWord(1000L, 2000L, text)) else emptyList()),
        ))
}
