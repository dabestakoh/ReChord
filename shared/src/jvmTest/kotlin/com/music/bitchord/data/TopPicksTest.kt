package com.music.bitchord.data

import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.model.Song
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import com.music.bitchord.data.model.TopPick
import com.music.bitchord.data.model.TopPickKind

class TopPicksTest {
    @Test
    fun lateCardsKeepShownTracksInPlaceAndRejectDuplicateRelease() {
        val song = Song("track", "DESERVE", "BABYMONSTER", null, albumId = "release")
        val initial = TopPick(kind = TopPickKind.RELATED_SONG, title = song.title, recommendations = listOf(song))
        val duplicate = TopPick(kind = TopPickKind.LATEST_RELEASE, title = song.title, browseId = song.albumId)
        val new = TopPick(kind = TopPickKind.ARTIST_AND_SIMILAR, title = "TWICE", artistBrowseId = "artist")
        assertEquals(listOf(initial, new), TopPicks.appendFeaturedCards(listOf(initial), listOf(duplicate, new)))
    }

    @Test
    fun featuredReleaseRejectsItsTrackAndSameCoverAtDifferentSizes() {
        val release = TopPick(
            kind = TopPickKind.LATEST_RELEASE,
            title = "DESERVE",
            subtitle = "Single",
            artistName = "BABYMONSTER",
            browseId = "MPRE1",
            images = listOf("https://example.test/cover=w600"),
        )
        val other = Song("different", "Different", "Different Artist", "https://example.test/other")
        assertTrue(TopPicks.sameFeaturedItem(release, other.copy(albumId = "MPRE1")))
        assertTrue(TopPicks.sameFeaturedItem(release, other.copy(thumbnailUrl = "https://example.test/cover=w256")))
        assertTrue(TopPicks.sameFeaturedItem(release, other.copy(title = "Deserve", artist = "Babymonster")))
        assertFalse(TopPicks.sameFeaturedItem(release, other))
    }

    @Test
    fun quickPicksSpreadRepeatArtistsWhileKeepingTheirRelativeOrder() {
        fun song(id: String, artist: String) = ShelfItem(id, artist, null, id, null)

        val first = song("one", "First Artist")
        val repeat = song("two", "first artist")
        val other = song("three", "Second Artist")
        val unknown = song("four", "")
        val items = listOf(first, repeat, other, unknown)
        val shelf = HomeShelf("Quick Picks", items)

        assertEquals(listOf(first, other, unknown, repeat), TopPicks.diversifyQuickPicks(shelf).items)
        val recent = shelf.copy(title = "Recently played")
        assertEquals(recent, TopPicks.diversifyQuickPicks(recent))
    }

    @Test
    fun exploreRanksReleasesFromBothHistoriesWithoutDroppingOtherArtists() {
        val unrelated = ShelfItem("Album 1", "Annabelle", null, null, "MPRE1")
        val localRelease = ShelfItem("Album 2", "Ann · 2026", null, null, "MPRE2")
        val youtubeRelease = ShelfItem("Album 3", "2026 · Other Artist", null, null, "MPRE3")
        val history = listOf(Song("yt", "Played on YouTube", "Other Artist", null))
        val device = TopPicks.LocalTaste(songs = listOf(Song("local", "Played on ReChord", "Ann", null)))

        assertEquals(
            listOf(youtubeRelease, localRelease, unrelated),
            TopPicks.rankReleases(listOf(unrelated, localRelease, youtubeRelease), history, device),
        )
    }
}
