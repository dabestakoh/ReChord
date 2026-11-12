package com.music.bitchord

import com.music.bitchord.playback.SeamlessUpgradeAlignment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SeamlessUpgradeAlignmentTest {
    @Test
    fun `correction seeks to the active position without recreating the logged lead`() {
        val alignment = SeamlessUpgradeAlignment()
        assertEquals(5097L, alignment.correctionTarget(5097, 5177))
        assertEquals(5097L, alignment.correctionTarget(5097, 4900))
        assertNull(alignment.correctionTarget(5097, 5152))
    }

    @Test
    fun `clock jump from the upgrade log resets the muted alignment window`() {
        val alignment = SeamlessUpgradeAlignment()
        assertFalse(alignment.sample(0, 3303, -49, ready = true))
        assertFalse(alignment.sample(51, 3312, 193, ready = true))
        assertFalse(alignment.sample(100, 3400, 25, ready = true))
        assertFalse(alignment.sample(449, 3749, 25, ready = true))
        assertTrue(alignment.sample(450, 3750, 25, ready = true))
    }

    @Test
    fun `alignment requires a ready advancing standby`() {
        val alignment = SeamlessUpgradeAlignment()
        assertFalse(alignment.sample(0, 1000, 0, ready = true))
        assertFalse(alignment.sample(400, 1000, 0, ready = true))
        assertFalse(alignment.sample(420, 1020, 0, ready = false))
        assertFalse(alignment.sample(440, 1040, 0, ready = true))
        assertTrue(alignment.sample(790, 1390, 0, ready = true))
    }
}
