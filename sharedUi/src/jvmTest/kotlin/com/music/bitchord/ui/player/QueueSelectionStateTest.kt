package com.music.bitchord.ui.player

import com.music.bitchord.data.model.QueueTier
import com.music.bitchord.data.model.Song
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class QueueSelectionStateTest {

    private fun song(id: String, entryId: String? = null) = Song(
        videoId = id,
        title = "Title $id",
        artist = "Artist",
        albumName = null,
        thumbnailUrl = null,
        queueTier = QueueTier.USER_QUEUE,
        queueEntryId = entryId,
    )

    @Test
    fun startWithEntry_selectsMatchingDuplicateOccurrence() {
        val queue = listOf(
            song("now", "e0"),
            song("dup", "e1"),
            song("dup", "e2"),
        )
        val rows = splitQueue(queue, currentIndex = 0, showHistory = false).rows
        val state = QueueSelectionState()

        state.startWithEntry(rows, timelineIndex = 2, song = queue[2])

        assertTrue(state.active)
        assertFalse(state.isSelected(rows[0]))
        assertTrue(state.isSelected(rows[1]))
        assertEquals(listOf(rows[1]), state.selected(rows))
    }

    @Test
    fun startWithEntry_fallsBackToEmptySelectionWhenRowMissing() {
        val queue = listOf(song("now", "e0"), song("next", "e1"))
        val rows = splitQueue(queue, currentIndex = 0, showHistory = false).rows
        val state = QueueSelectionState()

        state.startWithEntry(rows, timelineIndex = 9, song = song("other", "e9"))

        assertTrue(state.active)
        assertTrue(state.selected(rows).isEmpty())
    }
}
