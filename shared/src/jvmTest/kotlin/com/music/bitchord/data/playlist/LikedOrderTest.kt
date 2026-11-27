package com.music.bitchord.data.playlist

import com.music.bitchord.data.model.Song
import kotlin.test.Test
import kotlin.test.assertEquals

class LikedOrderTest {

    private fun song(id: String) = Song(
        videoId = id,
        title = "Track $id",
        artist = "Artist $id",
        thumbnailUrl = null,
        durationText = "3:00",
    )

    @Test
    fun nullOrEmptyCustomOrderPreservesNaturalOrder() {
        val songs = listOf(song("a"), song("b"), song("c"))
        assertEquals(listOf("a", "b", "c"), applyLikedCustomOrder(songs, null).map { it.videoId })
        assertEquals(listOf("a", "b", "c"), applyLikedCustomOrder(songs, emptyList()).map { it.videoId })
    }

    @Test
    fun customOrderReordersMatchingSongs() {
        val songs = listOf(song("a"), song("b"), song("c"), song("d"))
        val custom = listOf("c", "a", "d", "b")
        assertEquals(
            listOf("c", "a", "d", "b"),
            applyLikedCustomOrder(songs, custom).map { it.videoId },
        )
    }

    @Test
    fun newlyLikedSongsPrependAtTopInYouTubeOrder() {
        val songs = listOf(song("new1"), song("new2"), song("a"), song("b"), song("c"))
        val custom = listOf("c", "b", "a")
        assertEquals(
            listOf("new1", "new2", "c", "b", "a"),
            applyLikedCustomOrder(songs, custom).map { it.videoId },
        )
    }

    @Test
    fun unlikedSongsDropCleanlyWithoutBreakingRemainingOrder() {
        val songs = listOf(song("a"), song("c"), song("d"))
        val custom = listOf("d", "removed1", "c", "removed2", "a")
        assertEquals(
            listOf("d", "c", "a"),
            applyLikedCustomOrder(songs, custom).map { it.videoId },
        )
    }

    @Test
    fun dedupeSongsByVideoIdPreservesFirstOccurrence() {
        val songs = listOf(song("a"), song("b"), song("a"), song("c"), song("b"))
        assertEquals(
            listOf("a", "b", "c"),
            dedupeSongsByVideoId(songs).map { it.videoId },
        )
    }
}
