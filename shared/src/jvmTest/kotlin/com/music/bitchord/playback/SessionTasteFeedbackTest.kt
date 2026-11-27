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

    @Test
    fun recordDislikeImmediatelyCoolsArtist() {
        val s = song("ccccccccccc", "Disliked Band")
        val cooledKey = SessionTasteFeedback.recordDislike(s)
        assertEquals("disliked band", cooledKey)
        assertTrue(SessionTasteFeedback.isArtistCooled("Disliked Band"))
        assertTrue(SessionTasteFeedback.isSongCooled(s))
    }

    @Test
    fun rediscoverLikedPickUsesOlderUnheardLikedSongs() {
        val liked = (1..26).map { i ->
            val id = "vid${i.toString().padStart(8, '0')}"
            song(id, "Artist $i", "Title $i")
        }
        val local = TopPicks.LocalTaste(
            liked = liked,
            recent = listOf(liked[21]),
            completedIds = setOf(liked[24].videoId),
            dislikedIds = setOf(liked[22].videoId),
        )
        val pick = TopPicks.rediscoverLikedPick(emptyList(), local)
        assertTrue(pick != null)
        assertEquals(TopPickKind.REDISCOVER_LIKED, pick.kind)
        val recIds = pick.recommendations.map { it.videoId }
        assertEquals(liked[24].videoId, recIds.first(), "Completed deep-cut liked song should lead")
        assertFalse(liked[0].videoId in recIds, "Newest 20 liked songs should be excluded")
        assertFalse(liked[21].videoId in recIds, "Recently played song should be excluded")
        assertFalse(liked[22].videoId in recIds, "Disliked song should be excluded")
    }

    @Test
    fun queuedIntentClearsCooldownWithoutOverwritingAnchorAndUndoRevertsSkip() {
        val anchor = song("anchor00001", "Main Vibe")
        val target = song("queued00001", "Queued Band")
        SessionTasteFeedback.recordCompletedOrLiked(anchor)

        assertFalse(SessionTasteFeedback.recordEarlySkip(target))
        assertTrue(SessionTasteFeedback.recordEarlySkip(target))
        assertTrue(SessionTasteFeedback.isArtistCooled("Queued Band"))

        // Queuing an online track clears cooldown and boosts affinity without changing anchor.
        SessionTasteFeedback.recordQueuedIntent(target)
        assertFalse(SessionTasteFeedback.isArtistCooled("Queued Band"))
        assertTrue(SessionTasteFeedback.artistAffinity("Queued Band") > 0.0)
        assertEquals(anchor, SessionTasteFeedback.lastGoodAnchorSong())

        // Undo early skip restores prior state cleanly.
        val s3 = song("skipundo001", "Undo Band")
        assertFalse(SessionTasteFeedback.recordEarlySkip(s3))
        assertTrue(SessionTasteFeedback.recordEarlySkip(s3))
        assertTrue(SessionTasteFeedback.isArtistCooled("Undo Band"))
        SessionTasteFeedback.undoEarlySkip(s3)
        assertFalse(SessionTasteFeedback.isArtistCooled("Undo Band"))
    }
}
