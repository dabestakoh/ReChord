package com.music.bitchord.playback

import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.model.Song
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class AutoplayRadioTest {
    private fun song(id: String) = Song(id, id, "Artist $id", null)

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
