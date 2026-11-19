package com.music.bitchord.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackMemoryBudgetTest {
    @Test
    fun keepsActiveReadAheadAheadOfStandbyAndReducesCombinedTargetOnLowRam() {
        val normal = PlaybackMemoryBudget(384L * MIB, lowRamDevice = false)
        val constrained = PlaybackMemoryBudget(384L * MIB, lowRamDevice = true)
        val smallHeap = PlaybackMemoryBudget(128L * MIB, lowRamDevice = false)

        assertEquals(32 * MIB, totalTarget(normal, underPressure = false))
        assertEquals(24 * MIB, totalTarget(constrained, underPressure = false))
        assertEquals(totalTarget(constrained, false), totalTarget(smallHeap, false))
        assertTrue(constrained.artworkCacheBytes < normal.artworkCacheBytes)
        assertTrue(constrained.browseCacheEntries < normal.browseCacheEntries)
        assertTrue(constrained.backBufferMs < normal.backBufferMs)
        for (budget in listOf(normal, constrained, smallHeap)) {
            for (underPressure in listOf(false, true)) {
                assertTrue(
                    budget.targetBytes(PlaybackBufferRole.ACTIVE, underPressure) >
                        budget.targetBytes(PlaybackBufferRole.STANDBY, underPressure),
                )
            }
        }
    }

    @Test
    fun pressureReducesAllTargetsAndLeavesRoomForLosslessProof() {
        val budget = PlaybackMemoryBudget(192L * MIB, lowRamDevice = false)

        assertFalse(budget.isUnderPressure(143L * MIB))
        assertTrue(budget.isUnderPressure(144L * MIB))
        assertEquals(18 * MIB, totalTarget(budget, underPressure = true))
        for (role in PlaybackBufferRole.entries) {
            assertTrue(budget.targetBytes(role, true) < budget.targetBytes(role, false))
        }
        assertTrue(budget.minimumReadAheadUs(PlaybackBufferRole.QUALITY_CHECK) > 12_000_000L)
    }

    private fun totalTarget(budget: PlaybackMemoryBudget, underPressure: Boolean): Int =
        PlaybackBufferRole.entries.sumOf { budget.targetBytes(it, underPressure) }

    private companion object {
        const val MIB = 1024 * 1024
    }
}
