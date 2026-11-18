package com.music.bitchord.data

import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.ShelfItem
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
}
