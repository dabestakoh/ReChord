package com.music.bitchord.data

import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.model.Song
import kotlin.test.Test
import kotlin.test.assertEquals

class TopPicksTest {
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
