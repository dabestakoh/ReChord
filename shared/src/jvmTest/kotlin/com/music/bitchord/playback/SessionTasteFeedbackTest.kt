package com.music.bitchord.playback

import com.music.bitchord.data.TopPicks
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.TopPick
import com.music.bitchord.data.model.TopPickKind
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SessionTasteFeedbackTest {

    @AfterTest
    fun tearDown() {
        SessionTasteFeedback.reset()
    }

    private fun song(id: String, artist: String, title: String = "Track $id") = Song(
        videoId = id,
        title = title,
        artist = artist,
        thumbnailUrl = null,
        durationText = "3:30",
    )

    @Test
    fun twoEarlySkipsCoolArtistAndCompletionClearsCooldown() {
        val s1 = song("aaaaaaaaaaa", "Artist One")
        val s2 = song("bbbbbbbbbbb", "Artist One")

        assertFalse(SessionTasteFeedback.recordEarlySkip(s1))
        assertFalse(SessionTasteFeedback.isArtistCooled("Artist One"))

        assertTrue(SessionTasteFeedback.recordEarlySkip(s2))
        assertTrue(SessionTasteFeedback.isArtistCooled("Artist One"))
        assertTrue(SessionTasteFeedback.isSongCooled(s1))

        SessionTasteFeedback.recordCompletedOrLiked(s1)
        assertFalse(SessionTasteFeedback.isArtistCooled("Artist One"))
        assertEquals(s1, SessionTasteFeedback.lastGoodAnchorSong())
    }

    @Test
    fun dismissFeaturedPickCoolsArtistAndSeed() {
        val pick = TopPick(
            kind = TopPickKind.RELATED_SONG,
            title = "Quiet Song",
            subtitle = "Sleepy Band",
            artistName = "Sleepy Band",
            seedTitle = "Quiet Song",
        )
        SessionTasteFeedback.dismissFeaturedPick(pick)
        assertTrue(SessionTasteFeedback.isArtistCooled("Sleepy Band"))
        assertTrue(pick.carouselKey in SessionTasteFeedback.dismissedSeedKeys())
        assertTrue("quiet song" in SessionTasteFeedback.dismissedSeedKeys())
    }

    @Test
    fun featuredSeedsExcludesDislikedCooledDismissedAndIncludesDeepCutLikes() {
        val liked = (1..20).map { i ->
            val id = "vid${i.toString().padStart(8, '0')}"
            song(id, "Artist $i", "Title $i")
        }
        val dislikedId = liked[0].videoId
        val deepCutId = liked[18].videoId
        val local = TopPicks.LocalTaste(
            liked = liked,
            dislikedIds = setOf(dislikedId),
            completedIds = setOf(deepCutId),
            cooledArtists = setOf("artist 2"),
            dismissedSeedKeys = setOf("title 3"),
        )
        val seeds = TopPicks.featuredSeeds(emptyList(), local)
        val seedIds = seeds.map { it.videoId }.toSet()

        assertFalse(dislikedId in seedIds, "Disliked track must not be seeded")
        assertFalse(liked[1].videoId in seedIds, "Cooled artist must not be seeded")
        assertFalse(liked[2].videoId in seedIds, "Dismissed seed title must not be seeded")
        assertTrue(deepCutId in seedIds, "Double-confirmed deep-cut liked song should be seeded")
    }
}
