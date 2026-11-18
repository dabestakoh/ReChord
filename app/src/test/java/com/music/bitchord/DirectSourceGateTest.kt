package com.music.bitchord

import com.music.bitchord.playback.DirectSourceGate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class DirectSourceGateTest {
    @Test
    fun `replaced preparation does not become a playback error`() = runTest {
        val owner = CoroutineScope(coroutineContext + SupervisorJob())
        val obsolete = owner.async<String> { awaitCancellation() }
        val playback = async { DirectSourceGate.awaitPreparation(obsolete) }
        runCurrent()

        owner.cancel()
        assertNull(playback.await())
        assertTrue(obsolete.isCancelled)
        assertEquals("flac", DirectSourceGate.awaitPreparation(CompletableDeferred("flac")))
    }

    @Test
    fun `already cancelled preparations do not throw loader errors`() = runTest {
        repeat(5) {
            val obsolete = CompletableDeferred<String>()
            obsolete.cancel()
            assertNull(DirectSourceGate.awaitPreparation(obsolete))
        }
    }

    @Test
    fun `user skip still cancels playback without cancelling shared preparation`() = runTest {
        val shared = CompletableDeferred<String>()
        var returned = false
        val playback = async {
            DirectSourceGate.awaitPreparation(shared)
            returned = true
        }
        runCurrent()
        playback.cancelAndJoin()

        assertTrue(playback.isCancelled)
        assertFalse(returned)
        assertFalse(shared.isCancelled)
        shared.complete("flac")
        assertEquals("flac", DirectSourceGate.awaitPreparation(shared))
    }
}
