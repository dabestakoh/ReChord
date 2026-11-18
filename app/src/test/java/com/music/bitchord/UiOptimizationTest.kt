package com.music.bitchord

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.snapshots.SnapshotStateObserver
import com.music.bitchord.playback.pollWhilePlaying
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.advanceTimeBy
import com.music.bitchord.playback.PlayerReads
import com.music.bitchord.playback.PlayerState
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.music.bitchord.data.model.Song
import com.music.bitchord.playback.refreshQueueItem
import com.music.bitchord.ui.components.uniqueKeys
import com.music.bitchord.ui.components.backdrop.internal.ShapeProvider
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class UiOptimizationTest {
    @Test
    fun `playhead polling stops while paused and resumes without a composition restart`() = runTest {
        val playing = mutableStateOf(false)
        var polls = 0
        val job = launch { pollWhilePlaying({ playing.value }, 100) { polls++ } }
        try {
            runCurrent()
            assertEquals(0, polls)
            playing.value = true
            Snapshot.sendApplyNotifications()
            runCurrent()
            assertEquals(1, polls)
            advanceTimeBy(100)
            runCurrent()
            assertEquals(2, polls)
            playing.value = false
            Snapshot.sendApplyNotifications()
            runCurrent()
            advanceTimeBy(500)
            runCurrent()
            assertEquals(2, polls)
            playing.value = true
            Snapshot.sendApplyNotifications()
            runCurrent()
            assertEquals(3, polls)
        } finally {
            job.cancelAndJoin()
        }
    }

    @Test
    fun `buffering does not invalidate a consumer that only reads the current song`() {
        val initial = PlayerState(song = song("a"))
        val snapshot = mutableStateOf(initial)
        val reads = PlayerReads(snapshot)
        val observer = SnapshotStateObserver { it() }
        var songChanges = 0
        var loadingChanges = 0
        observer.start()
        try {
            observer.observeReads("song", { _: String -> songChanges++ }) { reads.song }
            observer.observeReads("loading", { _: String -> loadingChanges++ }) { reads.isLoading }
            snapshot.value = initial.copy(isLoading = true)
            Snapshot.sendApplyNotifications()
            assertEquals(0, songChanges)
            assertEquals(1, loadingChanges)
        } finally {
            observer.stop()
            observer.clear()
        }
    }

    @Test
    fun `feed duplicates and keys containing occurrence suffixes remain distinct`() {
        val ids = listOf("Top", "Top", "Top#1", "", "", "3:Top#0")
        val keys = uniqueKeys(ids) { it }
        assertEquals(ids.size, keys.toSet().size)
        assertEquals(keys, uniqueKeys(ids) { it })
    }

    @Test
    fun `unrelated insertions do not move existing item identities`() {
        val before = uniqueKeys(listOf("a", "b", "a")) { it }
        val after = uniqueKeys(listOf("new", "a", "b", "a")) { it }
        assertEquals(before, after.drop(1))
    }

    private fun song(id: String) = Song(videoId = id, title = id, artist = "artist", thumbnailUrl = null)

    @Test
    fun `identical metadata retains the queue instance`() {
        val queue = listOf(song("a"), song("b"))
        assertSame(queue, refreshQueueItem(queue, 1, queue[1].copy()))
        assertSame(queue, refreshQueueItem(queue, -1, song("c")))
        assertSame(queue, refreshQueueItem(queue, queue.size, song("c")))
    }

    @Test
    fun `changed metadata updates only the selected occurrence without mutating the queue`() {
        val original = song("a")
        val queue = listOf(original, original, song("b"))
        val updated = original.copy(title = "new title")
        val result = refreshQueueItem(queue, 1, updated)
        assertNotSame(queue, result)
        assertEquals(listOf(original, updated, song("b")), result)
        assertEquals(original, queue[1])
    }

    @Test
    fun `same shape block reuses identity and cached outlines while geometry still invalidates`() {
        var calls = 0
        val shape = object : Shape {
            override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
                calls++
                return Outline.Rectangle(Rect(0f, 0f, size.width, size.height))
            }
        }
        val block: () -> Shape = { shape }
        val provider = ShapeProvider(block)
        assertEquals(provider, ShapeProvider(block))
        assertEquals(provider.hashCode(), ShapeProvider(block).hashCode())
        assertNotEquals(provider, ShapeProvider { shape })
        val first = provider.shape.createOutline(Size(10f, 10f), LayoutDirection.Ltr, Density(1f))
        assertSame(first, provider.shape.createOutline(Size(10f, 10f), LayoutDirection.Ltr, Density(1f)))
        assertEquals(1, calls)
        provider.shape.createOutline(Size(20f, 10f), LayoutDirection.Ltr, Density(1f))
        provider.shape.createOutline(Size(20f, 10f), LayoutDirection.Rtl, Density(1f))
        provider.shape.createOutline(Size(20f, 10f), LayoutDirection.Rtl, Density(2f))
        assertEquals(4, calls)
    }
}
