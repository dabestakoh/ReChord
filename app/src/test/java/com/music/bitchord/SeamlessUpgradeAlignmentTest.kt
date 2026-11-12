package com.music.bitchord

import com.music.bitchord.playback.SeamlessUpgradeAlignment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SeamlessUpgradeAlignmentTest {
    @Test
    fun `window smooths clock jumps while retaining a sustained offset`() {
        val alignment = SeamlessUpgradeAlignment()
        assertNull(alignment.measureDrift(0, -49))
        assertNull(alignment.measureDrift(51, 193))
        assertNull(alignment.measureDrift(100, 30))
        assertNull(alignment.measureDrift(200, -49))
        assertEquals(19L, alignment.measureDrift(250, -30))
        assertNull(alignment.measureDrift(260, 301))

        alignment.reset()
        for (now in 0L..200L step 50L) {
            assertNull(alignment.measureDrift(now, 88))
        }
        assertEquals(88L, alignment.measureDrift(250, 88))
    }

    @Test
    fun `a seek requires sustained drift in the same direction`() {
        val alignment = SeamlessUpgradeAlignment()
        alignment.sample(0, 5000, 148, ready = true)
        alignment.sample(250, 5250, -102, ready = true)
        alignment.sample(500, 5500, 148, ready = true)
        assertNull(alignment.correctionTarget(500, 5648))
        alignment.sample(849, 5849, 148, ready = true)
        assertNull(alignment.correctionTarget(849, 5997))
        alignment.sample(850, 5850, 148, ready = true)
        assertEquals(5998L, alignment.correctionTarget(850, 5998))
        alignment.sample(870, 5870, 148, ready = false)
        assertNull(alignment.correctionTarget(870, 6018))
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
