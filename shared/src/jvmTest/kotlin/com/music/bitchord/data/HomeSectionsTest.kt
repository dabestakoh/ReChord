package com.music.bitchord.data

import com.music.bitchord.data.model.HomeSections
import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.ShelfItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class HomeSectionsTest {
    @Test
    fun homePromotesRecentsAndQuickPicksAndHidesOnlyTheDefaultShelves() {
        val titles = listOf("Albums", "Top songs", "Quick picks", "Trending", "Recents", "Trending community playlists")
        val items = listOf(ShelfItem("One", "", null, "1", null), ShelfItem("Two", "", null, "2", null))
        val feed = titles.map { HomeShelf(it, items) }
        assertEquals(
            listOf("Recents", "Quick picks", "Albums", "Trending community playlists"),
            HomeSections.arrange(feed, emptyMap()).map { it.title },
        )
        val overrides = mapOf(HomeSections.RECENTS to false, HomeSections.TRENDING to true)
        assertEquals(
            listOf("Quick picks", "Albums", "Trending", "Trending community playlists"),
            HomeSections.arrange(feed, overrides).map { it.title },
        )
    }

    @Test
    fun homeFiltersRetiredShelvesAndOrdersDiscoveryBeforeLibrary() {
        val titles = listOf(
            "From the community", "Listen again", "TOMORROW X TOGETHER - PERFECT STORM",
            "Albums & singles", "Music videos", "New music videos", "Fresh finds, old favorites",
            "Your daily discover", "Mixed for you", "From your library",
        )
        val feed = titles.map { HomeShelf(it, emptyList()) }
        assertEquals(
            listOf("Mixed for you", "Your daily discover", "Fresh finds, old favorites", "Listen again", "From your library", "From the community"),
            HomeSections.arrange(feed, emptyMap()).map { it.title },
        )
    }

    @Test
    fun localizedKnownShelvesAndBrowseEndpointsKeepTheirVisibilityId() {
        assertEquals(HomeSections.QUICK_PICKS, HomeSections.key(HomeShelf("Lựa chọn nhanh", emptyList())))
        val shelf = HomeShelf("Albums", emptyList(), moreBrowseId = "browse")
        val hidden = mapOf(HomeSections.key(shelf) to false)
        assertFalse(HomeSections.visible(HomeSections.key(shelf.copy(title = "Álbumes")), hidden))
    }
}
