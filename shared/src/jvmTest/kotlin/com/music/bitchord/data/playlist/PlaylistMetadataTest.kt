package com.music.bitchord.data.playlist

import com.music.bitchord.data.model.Song
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaylistMetadataTest {
    private fun song(id: String, duration: String = "3:00") =
        Song(videoId = id, title = id, artist = "Artist", thumbnailUrl = null, durationText = duration)

    @Test fun parsesTrackCountAndExactDuration() {
        assertEquals(PlaylistMetadata.Summary(1234, 8100L),
            PlaylistMetadata.fromHeader("1,234 songs • 2 hours 15 minutes"))
    }

    @Test fun skipsApproximateDurations() {
        assertEquals(null, PlaylistMetadata.fromHeader("100 songs • 6+ hours").durationSeconds)
    }

    @Test fun ignoresHoursOnlyDurationAsCoarseMetadata() {
        assertEquals(null, PlaylistMetadata.fromHeader("2 songs • 3 hours").durationSeconds)
        assertFalse(PlaylistMetadata.hasChanged(
            listOf(song("a", "3:00"), song("b", "4:00")), "2 songs • 3 hours"))
    }

    @Test fun acceptsHourAndMinuteAbbreviations() {
        assertEquals(PlaylistMetadata.Summary(2, 5400L),
            PlaylistMetadata.fromHeader("2 tracks • 1 hr 30 min"))
    }

    @Test fun countStillDetectsChangesWithCoarseDuration() {
        assertTrue(PlaylistMetadata.hasChanged(
            listOf(song("a")), "2 songs • 3 hours"))
    }

    @Test fun detectsNewTrackOnFirstPageWhenHeaderIsMissing() {
        assertTrue(PlaylistMetadata.firstPageChanged(
            listOf(song("a"), song("b")), listOf(song("new"), song("a"))))
    }

    @Test fun unchangedFirstPageAvoidsFullScan() {
        assertFalse(PlaylistMetadata.firstPageChanged(
            listOf(song("a"), song("b"), song("c")), listOf(song("a"), song("b"))))
    }

    @Test fun singlePageTailDeletionTriggersFirstPageChanged() {
        assertTrue(PlaylistMetadata.firstPageChanged(
            listOf(song("a"), song("b"), song("c")),
            listOf(song("a"), song("b")),
            hasContinuation = false,
        ))
    }

    @Test fun emptyFirstPageDoesNotDeleteValidCache() {
        assertFalse(PlaylistMetadata.firstPageChanged(listOf(song("a")), emptyList()))
    }

    @Test fun firstPageReorderingTriggersRefresh() {
        assertTrue(PlaylistMetadata.firstPageChanged(
            listOf(song("a"), song("b")), listOf(song("b"), song("a"))))
    }

    @Test fun returnCooldownPreventsRapidDuplicateFullScans() {
        assertTrue(PlaylistMetadata.shouldRunReturnCheck(100_000L, null, 15_000L))
        assertFalse(PlaylistMetadata.shouldRunReturnCheck(110_000L, 100_000L, 15_000L))
        assertTrue(PlaylistMetadata.shouldRunReturnCheck(115_000L, 100_000L, 15_000L))
    }

    @Test fun returnCooldownRecoversFromClockMovingBackward() {
        assertTrue(PlaylistMetadata.shouldRunReturnCheck(50_000L, 100_000L, 15_000L))
    }

    @Test fun countChangeTriggersRefresh() {
        assertTrue(PlaylistMetadata.hasChanged(listOf(song("a"), song("b")), "3 songs"))
    }

    @Test fun sameCountAndApproximateDurationDoesNotTriggerRefresh() {
        assertFalse(PlaylistMetadata.hasChanged(listOf(song("a"), song("b")), "2 songs • 6+ hours"))
    }

    @Test fun durationChangeTriggersRefreshWhenCountMatches() {
        assertTrue(PlaylistMetadata.hasChanged(listOf(song("a"), song("b")), "2 songs • 15 minutes"))
    }

    @Test fun absentOrUnparseableMetadataNeverInvalidatesCache() {
        assertFalse(PlaylistMetadata.hasChanged(listOf(song("a")), null))
        assertFalse(PlaylistMetadata.hasChanged(listOf(song("a")), "Playlist by somebody"))
    }

    @Test fun roundingDifferenceDoesNotCauseUnnecessaryFullScan() {
        assertFalse(PlaylistMetadata.hasChanged(
            listOf(song("a", "3:23"), song("b", "2:45")), "2 songs • 6 minutes"))
    }

    @Test fun mergeFirstPagePrependsNewLikedSongsAndPreservesTail() {
        val cached = (1..20).map { song("s$it").copy(setVideoId = "set-$it") }
        val fetched = listOf(song("new1").copy(setVideoId = "set-new1")) + cached.take(9)
        val result = PlaylistMetadata.mergeFirstPage(cached, fetched, hasContinuation = true)
        assertTrue(result.anchored)
        assertFalse(result.complete)
        assertEquals(21, result.merged.size)
        assertEquals("new1", result.merged.first().videoId)
        assertEquals("s20", result.merged.last().videoId)
    }

    @Test fun mergeFirstPageRemovesDeletedTrackOnPageOneWithoutDroppingTail() {
        val cached = (1..15).map { song("s$it").copy(setVideoId = "set-$it") }
        val fetched = listOf(cached[0]) + cached.subList(2, 10)
        val result = PlaylistMetadata.mergeFirstPage(cached, fetched, hasContinuation = true)
        assertTrue(result.anchored)
        assertEquals(14, result.merged.size)
        assertFalse(result.merged.any { it.videoId == "s2" })
        assertEquals("s15", result.merged.last().videoId)
    }

    @Test fun mergeFirstPageDeduplicatesLocallyLikedEntryReceivingSetVideoId() {
        val localLiked = song("new1") // setVideoId == null
        val cached = listOf(localLiked) + (1..12).map { song("s$it").copy(setVideoId = "set-$it") }
        val serverPage = listOf(song("new1").copy(setVideoId = "set-new1")) + cached.subList(1, 8)
        val result = PlaylistMetadata.mergeFirstPage(cached, serverPage, hasContinuation = true)
        assertTrue(result.anchored)
        assertEquals(13, result.merged.size)
        assertEquals(1, result.merged.count { it.videoId == "new1" })
        assertEquals("set-new1", result.merged.first().setVideoId)
    }

    @Test fun mergeFirstPageSinglePageMarksComplete() {
        val cached = listOf(song("a"), song("b"))
        val fetched = listOf(song("a"), song("b"), song("c"))
        val result = PlaylistMetadata.mergeFirstPage(cached, fetched, hasContinuation = false)
        assertTrue(result.anchored)
        assertTrue(result.complete)
        assertEquals(listOf("a", "b", "c"), result.merged.map { it.videoId })
    }

    @Test fun mergeFirstPageReturnsUnanchoredWhenNoOverlapExists() {
        val cached = (1..10).map { song("old$it") }
        val fetched = (1..10).map { song("brandNew$it") }
        val result = PlaylistMetadata.mergeFirstPage(cached, fetched, hasContinuation = true)
        assertFalse(result.anchored)
        assertFalse(result.complete)
        assertEquals(cached, result.merged)
    }
}
