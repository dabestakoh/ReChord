package com.music.bitchord.playback

import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.model.Song
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoplayRadioTest {
    private fun song(id: String) = Song(id, id, "Artist $id", null)

    @Test
    fun artistCardContinuesItsOwnPageBeforeStartingNewRadio() = runBlocking {
        val first = song("aaaaaaaaaaa")
        val next = song("bbbbbbbbbbb")
        val banned = song("ccccccccccc").copy(artist = "Seed Artist", artistId = "seed")
        val calls = mutableListOf<String?>()
        val radio = AutoplayRadio(
            page = { _, cursor, _ ->
                calls += cursor
                YtMusicRepository.SongPage(listOf(first, banned, next), null)
            },
            related = { error("Should use artist continuation first") },
            artistSongs = { _, _ -> error("Should use artist continuation first") },
        )
        radio.continueArtistRadio(first, "artist-page-2", "Seed Artist", "seed")
        assertEquals(listOf(next.videoId), radio.load(listOf(first), listOf(first), 5).map { it.videoId })
        assertEquals(listOf("artist-page-2"), calls)
    }

    @Test
    fun resumesOnlyWhenEndedAndListenerStillWantsPlayback() {
        assertTrue(shouldResumeAutoplay(ended = true, playWhenReady = true, hasNext = true))
        assertFalse(shouldResumeAutoplay(ended = true, playWhenReady = false, hasNext = true))
        assertFalse(shouldResumeAutoplay(ended = false, playWhenReady = true, hasNext = true))
        assertFalse(shouldResumeAutoplay(ended = true, playWhenReady = true, hasNext = false))
    }

    @Test
    fun continuesLatestEligibleSeedBeforeTryingEarlierSeeds() = runBlocking {
        val earlier = song("aaaaaaaaaaa")
        val latest = song("bbbbbbbbbbb")
        val first = song("ccccccccccc")
        val next = song("ddddddddddd")
        val calls = mutableListOf<Pair<String, String?>>()
        val radio = AutoplayRadio(
            page = { id, token, _ ->
                calls += id to token
                if (token == null) YtMusicRepository.SongPage(listOf(latest, first), "page-2")
                else YtMusicRepository.SongPage(listOf(next), null)
            },
            related = { error("No related fallback expected") },
            artistSongs = { _, _ -> error("No artist fallback expected") },
        )
        val queue = listOf(earlier, latest, song("content://local"))
        assertEquals(listOf(first.videoId), radio.load(queue, queue, 1).map { it.videoId })
        assertEquals(listOf(next.videoId), radio.load(queue + first, queue + first, 1).map { it.videoId })
        assertEquals(listOf(latest.videoId to null, latest.videoId to "page-2"), calls)
    }

    @Test
    fun fallsBackThroughRelatedToArtistWhenEarlierSourcesRepeatQueue() = runBlocking {
        val seed = song("aaaaaaaaaaa")
        val result = song("bbbbbbbbbbb")
        val calls = mutableListOf<String>()
        val radio = AutoplayRadio(
            page = { _, _, _ ->
                calls += "radio"
                YtMusicRepository.SongPage(listOf(seed), null)
            },
            related = { calls += "related"; listOf(seed) },
            artistSongs = { _, _ -> calls += "artist"; listOf(result) },
        )
        assertEquals(listOf(result.videoId), radio.load(listOf(seed), listOf(seed), 3).map { it.videoId })
        assertEquals(listOf("radio", "related", "artist"), calls)
    }
}
